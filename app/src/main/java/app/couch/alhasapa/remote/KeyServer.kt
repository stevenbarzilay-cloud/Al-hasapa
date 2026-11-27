package app.couch.alhasapa.remote

import android.content.Context
import android.graphics.Bitmap
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.couch.alhasapa.data.CouchState
import app.couch.alhasapa.data.SoloSide
import app.couch.alhasapa.diag.Flight
import app.couch.alhasapa.remote.KeyLink.Msg
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.Reader
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The TV's side of the phone-as-keyboard link: listens on the home network while the app is in front, announces itself so the phone finds it,
 * pairs a phone once with four digits shown on the TV, and hands what a paired phone types to [onInput] on the main thread.
 *
 * Who may type: only devices on the home network ([KeyLink.isHomeAddress]), only after pairing (a random token the phone keeps), at most
 * four phones at once, and at a human pace. Six wrong codes in a row close pairing for five minutes. Nothing is sent back but the name of
 * the screen, and, to a phone in touch mode that proved its pairing, pictures of the game's page sealed for it alone (1.9.8): one at a
 * time, the next only when the phone has shown the last, at a pace the box can afford.
 */
class KeyServer(
    context: Context,
    private val store: RemoteStore = RemoteStore(context),
    private val onInput: (Msg) -> Unit = { RemoteKeys.deliver(it) },
    private val preferredPort: Int = KeyLink.PORT,
    private val advertise: Boolean = true,
    private val tvName: String = Build.MODEL.orEmpty().ifBlank { "TV" },
    /** A site's login from the phone (decrypted), on the main thread: the site's name and how many cookies were set, or null. */
    private val onLogins: (String) -> Pair<String, Int>? = { payload -> LoginReceiver.receive(context, payload) },
    /** Told to the phone and compared with what the phone tells (see [KeyLink.TOGETHER_SINCE] and [RemoteKeys.Ui.older]), and this app's version by name. */
    private val appCode: Int = KeyLink.TOGETHER_SINCE,
    private val appVer: String = app.couch.alhasapa.BuildConfig.VERSION_NAME.removeSuffix("-tv"),
    /** A phone is connected, or the last one left: the TV's Wi-Fi stays awake meanwhile (see [WifiLatency]). */
    private val onLinked: (Boolean) -> Unit = { on -> WifiLatency.hold(context, on) },
    /** What the phones in touch mode are shown: the game's page of the screen in front, or null (no game is open). */
    private val frameSource: () -> FrameSource? = { RemoteKeys.front?.frameSource },
    /** A phone shows the game in touch mode, or the last one stopped (main thread): the TV's paw stays away meanwhile. */
    private val onMirroring: (Boolean) -> Unit = { on -> RemoteKeys.touchModeChanged(on) },
    /** Both of us as this TV knows us, and the notes the phones leave (1.9.9). */
    private val book: CoupleBook = RepoCoupleBook { (context.applicationContext as? app.couch.alhasapa.CouchApp)?.repo },
    /** Where the phones tell each other of the games played alone (1.11): made here once, handed to each paired phone. */
    private val relay: () -> RelayConfig? = { RelayStore(context).ensure() },
    /**
     * A guest handed in a side of a game played alone here (2.0), as [who]: the home's own phones are told through the relay, as they
     * are of each other's (the guest's phone has no relay of this couch, and its own home hears nothing of it).
     */
    private val onGuestSide: (who: String) -> Unit = { who ->
        (context.applicationContext as? app.couch.alhasapa.CouchApp)?.let { a ->
            Thread({ runCatching { SoloRelay(a, a.repo).send(who) } }, "guest-solo").start()
        }
    },
    /** The evening it is (a guest of the evening is one until it ends). */
    private val evening: () -> String = { app.couch.alhasapa.data.Couple.evening() },
) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val sender = Executors.newSingleThreadExecutor { r -> Thread(r, "keys-send").apply { isDaemon = true } }
    private val lock = Any()
    @Volatile private var server: ServerSocket? = null
    private val peers = CopyOnWriteArrayList<Peer>()
    private var registration: NsdManager.RegistrationListener? = null

    private var code: String? = null
    private var codeFor: String? = null
    /** What the phone being paired asks to come as (2.0): shown with the code, so whoever reads it out knows what they let in. */
    private var codeAs: String? = null
    private var codeAt = 0L
    private var failures = 0
    private var closedUntil = 0L

    /** The name of what the TV shows, for the phone's status line. */
    @Volatile var screen: String = ""
        set(value) {
            field = value
            peers.forEach { if (it.ready) it.send(Msg.Screen(value, resultReady, touchGame)) }
        }

    /** A game's end screen is up on the TV: the phones offer to save the result. */
    @Volatile var resultReady: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            peers.forEach { if (it.ready) it.send(Msg.Screen(screen, value, touchGame)) }
        }

    /** The game in front is played by dragging (1.9.8): the phones turn to touch mode by themselves. */
    @Volatile var touchGame: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            peers.forEach { if (it.ready) it.send(Msg.Screen(screen, resultReady, value)) }
        }

    /** The screen in front changed, and with it what there is to picture: the phones in touch mode get the new one. */
    fun framesChanged() { main.post { frameTick() } }

    /** What the TV shows about the link (the pairing code, the phones connected). */
    val ui: StateFlow<RemoteKeys.Ui> get() = state
    private val state = MutableStateFlow(RemoteKeys.Ui())

    val port: Int get() = server?.localPort ?: 0

    /** Not on the main thread: it opens a socket. */
    fun start() {
        if (server != null) return
        val s = runCatching { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(preferredPort)) } }.getOrNull()
            ?: runCatching { ServerSocket(0) }.getOrNull()
        if (s == null) { Flight.log("phone keyboard: could not listen on the home network"); return }
        server = s
        Thread({ accept(s) }, "keys-accept").apply { isDaemon = true }.start()
        ShareBus.add(pushShares)
        if (advertise) announce(s.localPort)
        Flight.log("phone keyboard: waiting on ${addresses().joinToString()} port ${s.localPort}")
        publish()
    }

    fun stop() {
        val s = server ?: return
        server = null
        ShareBus.remove(pushShares)
        peers.forEach { it.close(bye = "the TV app went to the background") }
        runCatching { s.close() }
        withdraw()
        synchronized(lock) { code = null; codeFor = null; codeAs = null }
        publish()
        Flight.log("phone keyboard: stopped")
    }

    /**
     * A change made on the TV (1.12): its recent share goes to every phone connected. Made ready off the thread that made the change
     * (the main one, as a rule: a copy of the state is not made twice there), and only when a phone is there to hear it.
     */
    private val shareOut = Executors.newSingleThreadExecutor { r -> Thread(r, "share-out").apply { isDaemon = true } }
    private val pushShares: () -> Unit = {
        if (peers.any { it.ready }) shareOut.execute {
            runCatching {
                // The home's own phones get what changed; a guest only the contests they may see (2.0).
                if (peers.any { it.member } && book.couch() != null) {
                    val shares = ShareBus.recent(book)
                    peers.forEach { p -> if (p.member) shares.forEach { p.send(Msg.Share(it)) } }
                }
                peers.forEach { p -> if (p.ready && p.guest) p.sendContests() }
            }
        }
    }

    /** The state changed by what another device brought (a home phone's side through the relay, say): the guests' contests follow. */
    fun stateChanged() {
        if (peers.any { it.ready && it.guest }) shareOut.execute { runCatching { peers.forEach { p -> if (p.ready && p.guest) p.sendContests() } } }
    }

    private fun accept(s: ServerSocket) {
        while (!s.isClosed) {
            val c = runCatching { s.accept() }.getOrNull() ?: break
            if (!KeyLink.isHomeAddress(c.inetAddress) || peers.size >= MAX_PEERS) {
                Flight.log("phone keyboard: refused a connection from ${c.inetAddress.hostAddress}")
                runCatching { c.close() }
                continue
            }
            val p = Peer(c)
            peers += p
            Thread({ p.run() }, "keys-peer").apply { isDaemon = true }.start()
        }
    }

    private fun publish() {
        val (c, f, a) = synchronized(lock) { Triple(code, codeFor, codeAs) }
        val ready = peers.filter { it.ready }
        state.value = RemoteKeys.Ui(
            listening = server != null,
            address = if (server != null) addresses().firstOrNull()?.let { "$it:$port" }.orEmpty() else "",
            phones = ready.map { it.shownName() },
            guests = ready.filter { it.guest }.map { it.shownName() },
            code = c, codeFor = f, codeAs = a,
            older = ready.firstOrNull { it.app < appCode }?.ver,
            newer = ready.firstOrNull { it.app > appCode }?.ver,
        )
        RemoteKeys.ui.value = state.value
        runCatching { onLinked(ready.isNotEmpty()) }
    }

    /** How the touchpad's moves arrive (see [PadTiming]), written down when the finger rests. */
    private val pad = PadTiming()
    private val padRested = Runnable { pad.summary()?.let { Flight.log(it) } }

    /** How long typed keys waited for the TV's main thread (main thread only), summed up when the typing pauses. */
    private val delays = ArrayList<Long>()
    private val typingPaused = Runnable {
        if (delays.isEmpty()) return@Runnable
        val s = delays.sorted()
        Flight.log("phone keyboard: ${s.size} keys reached the page within ${s[(s.size - 1) / 2]} ms (half), " +
            "${s[((s.size - 1) * 0.9).toInt()]} ms (9 in 10), the slowest ${s.last()} ms")
        delays.clear()
    }

    private fun noteDelay(ms: Long) {
        if (delays.size < 400) delays += ms
        main.removeCallbacks(typingPaused)
        main.postDelayed(typingPaused, 3_000)
    }

    private inner class Peer(private val socket: Socket) {
        @Volatile var ready = false
        @Volatile var name = "טלפון"
        /** The phone's app version (0 and empty: before 1.9.4, which did not say). */
        @Volatile var app = 0
        @Volatile var ver = ""
        /** Which of us holds this phone ("a", "b"; empty until it says, or from a phone app before 1.9.9). */
        @Volatile var who = ""
        /** What the phone was let in as (2.0): of the home, or a guest (who may come again, or of this evening). */
        @Volatile var role = KeyLink.AS_HOME
        /** What it asks to be paired as, while the TV shows the code. */
        private var asking = KeyLink.AS_HOME
        /** One of the home's own phones, proved: it gets the home's state, its relay, and may hand over a login. */
        val member: Boolean get() = ready && !plain && role == KeyLink.AS_HOME
        val guest: Boolean get() = role != KeyLink.AS_HOME
        /** The contests last sent to this guest, day by day (sent again only when they change). */
        private val contestsSent = HashMap<String, String>()
        /** Touch mode: how wide the phone draws the pictures (0: it wants none), and the key they are sealed with (null: none for this phone). */
        @Volatile var mirrorWidth = 0
        /** The part of the page the phone shows, zoomed in (null: all of it). */
        @Volatile var mirrorCrop: KeyLink.Crop? = null
        @Volatile var frameKey: ByteArray? = null
        /** The picture sent and not yet said to be seen (-1: none), when it went, and whether this phone has any picture at all yet. */
        @Volatile var awaiting = -1
        @Volatile var sentAt = 0L
        @Volatile var hasPicture = false
        /** What the phone was last told when there was nothing to picture (so it is told once). */
        @Volatile var toldNone: String? = null
        /** When the phone last said anything (it pings every few seconds): a picture is sent again only to a phone that is there. */
        @Volatile var heardAt = SystemClock.uptimeMillis()
        /** New for each connection: the phone proves with it that it holds its pairing token, without sending the token. */
        private val nonce = RemoteStore.randomHex(16)
        /** The pairing token of this phone, once known; [plain] when it was sent as is (a phone app from before 1.9). */
        private var token: String? = null
        @Volatile var plain = false
            private set
        /** The key of the sign-in handoff under way (one use). */
        private var handoffKey: ByteArray? = null
        private var tries = 3
        private var second = 0L
        private var inSecond = 0
        private val out = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)

        /** The phone's holder by name once it said who (the TV shows "סטיב" rather than "Pixel 8"), else the phone's own name. */
        fun shownName(): String = who.takeIf { it.isNotEmpty() }?.let { w -> runCatching { book.couple() }.getOrNull()?.firstOrNull { it.who == w }?.name } ?: name

        /** Sending never happens on the main thread (Android forbids network there): one sender thread, in order. */
        fun send(m: Msg) {
            sender.execute { runCatching { synchronized(out) { out.write(KeyLink.encode(m)); out.write("\n"); out.flush() } } }
        }

        fun run() {
            try {
                socket.tcpNoDelay = true
                socket.soTimeout = 30_000                     // the phone says something at least every few seconds
                val input = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                val couch = runCatching { book.couch() }.getOrNull()
                send(Msg.Hi(store.tvId, tvName, nonce, appCode, appVer, couch?.id.orEmpty(), couch?.name.orEmpty()))
                while (true) {
                    val line = readLine(input) ?: break
                    heardAt = SystemClock.uptimeMillis()
                    val m = KeyLink.decode(line) ?: continue
                    if (!handle(m)) break
                }
            } catch (_: Exception) {
            } finally {
                close(bye = null)
            }
        }

        private fun handle(m: Msg): Boolean = when (m) {
            is Msg.Hello -> {
                name = m.name.ifBlank { "טלפון" }.take(40)
                app = m.app; ver = m.ver
                // What it comes as (2.0). A phone app from before 2.0 says nothing: it is of the home, as every paired phone was.
                val want = m.role.ifEmpty { KeyLink.AS_HOME }
                val proved = store.tokenProvedBy(m.proof, nonce, m.phone, evening())
                when {
                    // Paired already: let in as what it asks, unless that is more than it was paired as (a guest who now says they
                    // are of the home, a guest of an evening who would come again): then the code, read out again by the home.
                    proved != null && rank(want) <= rank(store.roleOf(proved)) -> {
                        token = proved; plain = false; frameKey = FrameCrypto.key(proved, nonce); role = want; welcome(); true
                    }
                    proved != null -> { token = proved; asking = want; askForCode() }
                    // A phone app from before 1.9 sends the token itself: it may still type, but not hand over a login.
                    store.phoneFor(m.token) != null && store.roleOf(m.token) == KeyLink.AS_HOME -> { token = m.token; plain = true; role = KeyLink.AS_HOME; welcome(); true }
                    else -> { asking = want; askForCode() }
                }
            }
            is Msg.LoginsKey -> { offerKey(m.key); true }
            is Msg.Logins -> { receiveLogins(m); true }
            is Msg.Pair -> checkCode(m.code)
            is Msg.Mirror -> {
                if (ready) {
                    val zoomOnly = m.on && mirrorWidth > 0
                    mirrorWidth = if (m.on) m.w.coerceIn(MIN_FRAME_WIDTH, KeyLink.MAX_FRAME_WIDTH) else 0
                    mirrorCrop = if (m.on) m.crop else null
                    // Zoomed in or out while showing: the next picture is of the new part, at once (the one on the way is let go).
                    awaiting = -1; toldNone = null
                    if (!zoomOnly) hasPicture = false else unchangedReset = true
                    main.post { frameTick() }
                }
                true
            }
            is Msg.FrameOk -> { if (m.n == awaiting) { awaiting = -1; main.post { frameTick() } }; true }
            is Msg.CoupleMsg -> {
                if (ready && guest) main.post { guestCouple(m) }
                else if (ready) {
                    if (m.who.isNotEmpty()) who = m.who
                    main.post {
                        // The newer of each of us is kept here, and every phone hears of a change (the other phone too).
                        if (runCatching { book.offer(m.people) }.getOrDefault(false)) {
                            val now = book.couple()
                            peers.forEach { if (it.ready) it.send(Msg.CoupleMsg("", now)) }
                            Flight.log("phone keyboard: ${shownName()} changed how one of us looks; every phone told")
                        }
                        publish()
                    }
                }
                true
            }
            is Msg.Share -> {
                // What a phone of the home knows (1.12): kept where newer, and passed on to the home's other phones connected.
                // (From a guest it is not looked at: nothing of another couch comes in, and nothing of this one goes out.)
                if (member) {
                    val changed = runCatching { book.takeShare(m.share) }.getOrDefault(false)
                    if (changed) {
                        peers.forEach { if (it !== this && it.member) it.send(m) }
                        peers.forEach { if (it.ready && it.guest) it.sendContests() }
                    }
                }
                true
            }
            is Msg.SoloSideMsg -> {
                // A guest's own side of a game played alone for this couch's contest (2.0): theirs only, whatever it says.
                if (ready && who.isNotEmpty()) main.post { guestSolo(m) }
                true
            }
            Msg.ShareAll -> {
                // The first time this phone and the TV meet since 1.12: every evening the TV keeps, then "that was all".
                if (member) {
                    val all = ShareBus.opening(book, all = true)
                    all.forEach { send(Msg.Share(it)) }
                    send(Msg.ShareAll)
                    Flight.log("sync: every evening kept was sent to ${shownName()} (${all.size} messages)")
                }
                true
            }
            is Msg.NoteMsg -> {
                // (A guest writes as the player they said they are; one who has not said writes nothing.)
                if (ready && (!guest || who.isNotEmpty())) main.post {
                    // From whoever holds this phone, whatever the note says.
                    val n = if (who.isNotEmpty()) m.note.copy(from = who) else m.note
                    val kept = runCatching { book.note(n) }.getOrDefault(false)
                    send(Msg.NoteOk(n.id))
                    if (kept) Flight.log("notes: one from ${shownName()} waits ${if (n.game != null) "for a game" else "on the home"}")
                }
                true
            }
            is Msg.Text, is Msg.Press, is Msg.Pointer, is Msg.Move, Msg.Click, is Msg.Drag, is Msg.Scroll, Msg.SaveResult, is Msg.Touch -> {
                if (ready && atHumanPace()) {
                    val at = SystemClock.uptimeMillis()
                    if (m is Msg.Move) pad.arrived(at, m.t)
                    main.post {
                        if (m is Msg.Text || m is Msg.Press) noteDelay(SystemClock.uptimeMillis() - at)
                        if (m is Msg.Move) {
                            pad.waited(SystemClock.uptimeMillis() - at)
                            main.removeCallbacks(padRested); main.postDelayed(padRested, PAD_REST_MS)
                        }
                        onInput(m)
                    }
                }
                true
            }
            Msg.Ping -> { send(Msg.Pong); true }
            is Msg.Bye -> false
            else -> true
        }

        private fun welcome() {
            ready = true
            send(Msg.Ok(screen, role))
            if (resultReady || touchGame) send(Msg.Screen(screen, resultReady, touchGame))
            // Both of us as the TV knows us: the phone keeps what is newer (its own word comes back from it, see Msg.CoupleMsg).
            // (Not to a phone app from before 1.9, which sends its token as is: it would not know what to do with it.)
            // A guest gets them too (2.0): the players to choose from, by name and piece, and nothing else of the home.
            if (!plain) runCatching { book.couple() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { send(Msg.CoupleMsg("", it)) }
            // The solo relay (1.11), sealed for this phone only: someone else on the home network sees that something went, not what.
            // Only to the home's own phones: a guest never holds the home's relay.
            val key = frameKey
            val hasCouch = runCatching { book.couch() }.getOrNull() != null
            if (member && key != null && hasCouch) runCatching { relay() }.getOrNull()?.let { c ->
                send(Msg.Relay(RelayCrypto.seal(key, KeyLink.RELAY_AAD, c.toJson().toByteArray())))
            }
            // Everything the TV keeps alike with the phones (1.12): two weeks of evenings, and the rest. A TV that is of no couch yet
            // (2.0: it is joining the one a phone began) says nothing of its own first: it takes the phone's.
            if (member && hasCouch) for (s in ShareBus.opening(book)) send(Msg.Share(s))
            if (guest) sendContests()
            Flight.log(
                "phone keyboard: $name connected${if (guest) " as a guest" else ""}" +
                    if (app != appCode) " (its app is ${if (app < appCode) "older" else "newer"}: ${ver.ifEmpty { "before 1.9.4" }}; this TV has $appVer)" else "",
            )
            publish()
        }

        private fun askForCode(): Boolean {
            val now = SystemClock.elapsedRealtime()
            synchronized(lock) {
                if (now < closedUntil) { send(Msg.Bye("wait")); return false }
                if (code == null || now - codeAt > CODE_LIFE_MS) { code = RemoteStore.newCode(); codeAt = now }
                codeFor = name
                codeAs = asking
            }
            send(Msg.NeedCode)
            Flight.log("phone keyboard: $name asks to pair as $asking; the TV shows the code")
            publish()
            return true
        }

        private fun checkCode(given: String): Boolean {
            val right = synchronized(lock) { code?.let { MessageDigest.isEqual(it.toByteArray(), given.trim().toByteArray()) } == true }
            if (right) {
                // (A phone that was paired as less, and asked for more: its old token goes, the new one says what it is now.)
                this.token?.let { old -> store.forgetPhone(old) }
                val token = RemoteStore.randomHex(32)
                store.addPhone(token, name, asking, evening())
                this.token = token; plain = false; role = asking
                frameKey = FrameCrypto.key(token, nonce)
                synchronized(lock) { code = null; codeFor = null; codeAs = null; failures = 0 }
                send(Msg.Paired(token))
                Flight.log("phone keyboard: $name paired as $asking")
                welcome()
                return true
            }
            tries--
            synchronized(lock) {
                failures++
                if (failures >= 6) { closedUntil = SystemClock.elapsedRealtime() + 5 * 60_000; code = null; codeFor = null; codeAs = null; failures = 0 }
            }
            send(Msg.Wrong(tries.coerceAtLeast(0)))
            publish()
            if (tries <= 0) { send(Msg.Bye("code")); return false }
            return true
        }

        /** The phone wants to hand over a login: the TV's own fresh key, signed with the pairing token, and the agreed key kept for one use. */
        private fun offerKey(phoneKey: String) {
            val t = token
            // (A guest's logins are theirs: a TV never takes one from a phone that is not of its home.)
            if (!ready || t == null || plain || guest) { send(Msg.LoginsNo(if (plain) "update-phone" else if (guest) "guest" else "pair")); return }
            val phonePub = LoginCrypto.decode(phoneKey) ?: run { send(Msg.LoginsNo("bad")); return }
            val pair = LoginCrypto.newKeyPair()
            val tvKey = LoginCrypto.encode(pair.public)
            handoffKey = LoginCrypto.sessionKey(pair.private, phonePub, t, phoneKey, tvKey)
            send(Msg.LoginsReady(tvKey, LoginCrypto.tvSignature(t, phoneKey, tvKey)))
        }

        /** The login itself: opened with the agreed key (anything changed on the way is refused), then put in the browser on the main thread. */
        private fun receiveLogins(m: Msg.Logins) {
            val key = handoffKey ?: run { send(Msg.LoginsNo("order")); return }
            handoffKey = null
            val plainText = LoginCrypto.open(key, m.iv, m.data) ?: run {
                Flight.log("logins: a handoff from $name could not be opened; refused")
                send(Msg.LoginsNo("bad")); return
            }
            main.post {
                val got = runCatching { onLogins(String(plainText, Charsets.UTF_8)) }.getOrNull()
                send(if (got != null) Msg.LoginsDone(got.first, got.second) else Msg.LoginsNo("site"))
            }
        }

        /** A person types at most a few dozen keys a second and the touchpad sends at most sixty moves; anything faster is dropped. */
        private fun atHumanPace(): Boolean {
            val now = SystemClock.elapsedRealtime() / 1000
            if (now != second) { second = now; inSecond = 0 }
            return ++inSecond <= 150
        }

        fun close(bye: String?) {
            if (!peers.remove(this)) return
            if (bye != null) send(Msg.Bye(bye))
            sender.execute { runCatching { socket.close() } }
            if (ready) {
                Flight.log("phone keyboard: $name left")
                main.post { onInput(Msg.Pointer(false)) }               // its paw goes away with it, and a finger that was down is lifted
                if (mirrorWidth > 0) { mirrorWidth = 0; main.post { frameTick() } }
            }
            // A phone that was being paired and went away: the TV stops showing the code (it comes back if the phone does).
            if (!ready) synchronized(lock) { if (peers.none { !it.ready }) { codeFor = null; codeAs = null; if (code != null) code = null } }
            publish()
        }

        /**
         * What a guest says of the players (2.0, main thread): who they are here, one of the couch's own players or someone new. A
         * guest may add themselves (a guest of this evening, or someone who comes often) and change what they added; nobody else.
         */
        private fun guestCouple(m: Msg.CoupleMsg) {
            val t = token ?: return
            val known = runCatching { book.couple() }.getOrDefault(emptyList()).associateBy { it.who }
            var made = store.madeBy(t)
            var changed = false
            for (p in m.people) {
                if (!p.other || p.removed) continue
                val mine = p.who in made
                if (!mine && (known.containsKey(p.who) || made.size >= MAX_GUEST_PEOPLE)) continue
                if (runCatching { book.offer(listOf(p)) }.getOrDefault(false)) changed = true
                if (!mine) { store.addMade(t, p.who); made = made + p.who }
            }
            val now = runCatching { book.couple() }.getOrDefault(emptyList())
            if (m.who.isNotEmpty() && now.any { it.who == m.who && !it.removed }) who = m.who
            if (changed) {
                peers.forEach { if (it.ready && !it.plain) it.send(Msg.CoupleMsg("", now)) }
                Flight.log("phone keyboard: a guest added themselves; every phone told")
            }
            sendContests()
            publish()
        }

        /** A guest's side of a game played alone (main thread): written as the player they said they are, then everyone is told. */
        private fun guestSolo(m: Msg.SoloSideMsg) {
            val side = SoloSide.fromJson(who, m.side) ?: return
            if (side.env) return
            val kept = runCatching { book.guestSide(m.day, m.game, side) }.getOrDefault(false)
            if (!kept) return
            send(Msg.SoloOk(m.day, m.game, side.t))
            Flight.log("solo: ${shownName()} handed in a side of a game played alone here")
            runCatching { onGuestSide(who) }
            peers.forEach { if (it.ready && it.guest) it.sendContests() }
        }

        /** To a guest: today's and yesterday's games played alone here, as the player they are may see them (only when changed). */
        fun sendContests() {
            if (!ready || !guest) return
            val today = java.time.LocalDate.now()
            for (day in listOf(CouchState.dayKey(today), CouchState.dayKey(today.minusDays(1)))) {
                val games = runCatching { book.contest(day, who) }.getOrNull() ?: continue
                val text = games.toString()
                synchronized(contestsSent) {
                    if (contestsSent[day] == text || (games.length() == 0 && day !in contestsSent)) return@synchronized
                    contestsSent[day] = text
                    send(Msg.Contest(day, games))
                }
            }
        }
    }

    private fun rank(role: String): Int = when (role) { KeyLink.AS_HOME -> 3; KeyLink.AS_GUEST -> 2; else -> 1 }

    // -----------------------------------------------------------------------------------------------------------------------------------
    // Touch mode: pictures of the game for the phones that show it (main thread, the pictures made on a thread of their own)
    // -----------------------------------------------------------------------------------------------------------------------------------

    private val encoder = Executors.newSingleThreadExecutor { r -> Thread(r, "frames").apply { isDaemon = true } }
    private val frameTickR = Runnable { frameTick() }
    private var frameN = 0
    private var capturing = false
    private var lastFrameAt = Long.MIN_VALUE / 2
    /** What the last picture cost the box (taking it, making it a JPEG, sealing it), in ms. */
    private var frameCost = 0L
    /** The last picture's bytes, and how many pictures in a row came out the same (a page standing still is pictured less often). */
    @Volatile private var lastJpeg: ByteArray? = null
    private var unchanged = 0
    /** A phone zoomed: the next picture differs whatever it shows. */
    @Volatile private var unchangedReset = false
    private var mirroringNow = false

    private fun frameTick() {
        main.removeCallbacks(frameTickR)
        val now = SystemClock.uptimeMillis()
        val watching = peers.filter { it.ready && it.mirrorWidth > 0 && it.frameKey != null }
        val on = watching.isNotEmpty()
        if (on != mirroringNow) {
            mirroringNow = on
            if (!on) { lastJpeg = null; unchanged = 0 }
            runCatching { onMirroring(on) }
            Flight.log(if (on) "touch mode: a phone shows the game" else "touch mode: no phone shows the game now")
        }
        if (!on) return
        if (capturing) {
            // A copy of the screen that never answered (it should not happen) does not stop the pictures for good.
            if (now - lastFrameAt < CAPTURE_STUCK_MS) return
            capturing = false
        }
        val source = frameSource()
        if (source == null) {
            for (p in watching) if (p.toldNone != KeyLink.NONE_HOME) {
                p.toldNone = KeyLink.NONE_HOME; p.awaiting = -1; p.hasPicture = false; p.send(Msg.FrameNone(KeyLink.NONE_HOME))
            }
            lastJpeg = null
            return
        }
        // One picture at a time to each phone: the next only once it said it saw the last, or the last was lost on the way.
        val due = watching.filter { it.awaiting < 0 || (now - it.sentAt > FRAME_LOST_MS && now - it.heardAt < HEARD_MS) }
        if (due.isEmpty()) { main.postDelayed(frameTickR, FRAME_LOST_MS); return }
        // The box spends at most about a third of its time on pictures (one that cost 60 ms waits 180 ms for the next), and a page
        // that stands still is pictured a couple of times a second only.
        val gap = if (unchanged >= 2 && due.all { it.hasPicture }) IDLE_FRAME_MS else maxOf(MIN_FRAME_MS, frameCost * 3)
        val wait = lastFrameAt + gap - now
        if (wait > 0) { main.postDelayed(frameTickR, wait); return }
        capturing = true
        lastFrameAt = now
        val n = ++frameN
        val width = due.maxOf { it.mirrorWidth }
        // The part of the page the phones show: all of it, unless every one of them is zoomed in (then the part that holds them all).
        val crop = due.map { it.mirrorCrop }.reduce { a, b -> KeyLink.Crop.union(a, b) }
        if (unchangedReset) { unchangedReset = false; unchanged = 0; lastJpeg = null }
        val asked = runCatching { source.capture(width, crop) { bmp -> encoder.execute { picture(n, bmp, due, now, crop) } } }
        if (asked.isFailure) {
            capturing = false
            main.postDelayed(frameTickR, CAPTURE_RETRY_MS)
        } else main.postDelayed(frameTickR, CAPTURE_STUCK_MS + 100)
    }

    /** On the pictures' thread: the JPEG, sealed for each phone that is due one (and only when it differs from the last). */
    private fun picture(n: Int, bmp: Bitmap?, due: List<Peer>, started: Long, crop: KeyLink.Crop?) {
        val jpeg = bmp?.let { b ->
            runCatching { ByteArrayOutputStream(64 * 1024).also { b.compress(Bitmap.CompressFormat.JPEG, FRAME_QUALITY, it) }.toByteArray() }.getOrNull()
        }
        val w = bmp?.width ?: 0
        val h = bmp?.height ?: 0
        val same = jpeg != null && lastJpeg?.contentEquals(jpeg) == true
        val at = SystemClock.uptimeMillis()
        for (p in due) {
            if (!p.ready || p.mirrorWidth <= 0) continue
            val key = p.frameKey ?: continue
            if (jpeg == null) {
                if (p.toldNone != KeyLink.NONE_CAPTURE) { p.toldNone = KeyLink.NONE_CAPTURE; p.send(Msg.FrameNone(KeyLink.NONE_CAPTURE)) }
                continue
            }
            if (same && p.hasPicture && p.awaiting < 0) continue
            val (iv, data) = FrameCrypto.seal(key, n, jpeg)
            p.awaiting = n; p.sentAt = at; p.hasPicture = true; p.toldNone = null
            p.send(Msg.Frame(n, w, h, iv, data, crop))
        }
        main.post {
            capturing = false
            frameCost = SystemClock.uptimeMillis() - started
            if (jpeg == null) { lastJpeg = null; main.postDelayed(frameTickR, CAPTURE_RETRY_MS); return@post }
            if (same) unchanged++ else { unchanged = 0; lastJpeg = jpeg }
            frameTick()
        }
    }

    private fun announce(port: Int) {
        val nsd = app.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        val info = NsdServiceInfo().apply {
            serviceName = "AlHasapa TV " + store.tvId.take(4)
            serviceType = KeyLink.SERVICE_TYPE
            this.port = port
            setAttribute("tv", store.tvId)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) { Flight.log("phone keyboard: announced on the home network") }
            override fun onRegistrationFailed(i: NsdServiceInfo, error: Int) { Flight.log("phone keyboard: could not announce itself (error $error)") }
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, error: Int) {}
        }
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener); registration = listener }
            .onFailure { Flight.log("phone keyboard: announcing failed: $it") }
    }

    private fun withdraw() {
        val l = registration ?: return
        registration = null
        runCatching { (app.getSystemService(Context.NSD_SERVICE) as? NsdManager)?.unregisterService(l) }
    }

    companion object {
        /** At most this many phones at once (2.0: an evening of friends, each on their own phone, and the home's own). */
        const val MAX_PEERS = 8

        /** A guest's phone adds at most this many people (itself, as a rule). */
        const val MAX_GUEST_PEOPLE = 2

        /** How long a pairing code stays the same. */
        const val CODE_LIFE_MS = 3 * 60_000L

        /** The finger has been off the touchpad this long: the stroke's numbers go to the report. */
        const val PAD_REST_MS = 3_000L

        /** Touch mode: the pictures' pace (at most about nine a second; a couple a second while the page stands still) and quality. */
        const val MIN_FRAME_MS = 110L
        const val IDLE_FRAME_MS = 400L
        const val FRAME_QUALITY = 60
        /** The narrowest picture asked for is made this wide all the same. */
        const val MIN_FRAME_WIDTH = 160
        /** A picture the phone has not said it saw within this long was lost: the next one goes (to a phone heard from lately). */
        const val FRAME_LOST_MS = 2_000L
        const val HEARD_MS = 6_000L
        /** The TV could not take a picture: it tries again after this long. */
        const val CAPTURE_RETRY_MS = 1_500L
        /** A copy of the screen not answered within this long is given up on. */
        const val CAPTURE_STUCK_MS = 3_000L

        /** The TV's addresses on the home network (shown in the information panel, typed on the phone if it cannot find the TV). */
        fun addresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>().map { it.hostAddress.orEmpty() }.filter { it.isNotEmpty() }
        }.getOrDefault(emptyList())

        /** One line from the other side, never longer than [max] ([KeyLink.MAX_LINE] from a phone). Null at the end of the stream. */
        fun readLine(r: Reader, max: Int = KeyLink.MAX_LINE): String? {
            val sb = StringBuilder()
            while (true) {
                val ch = r.read()
                if (ch < 0) return if (sb.isEmpty()) null else sb.toString()
                if (ch == '\n'.code) return sb.toString()
                if (sb.length >= max) throw IOException("line too long")
                sb.append(ch.toChar())
            }
        }
    }
}
