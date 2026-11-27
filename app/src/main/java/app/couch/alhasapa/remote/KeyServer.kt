package app.couch.alhasapa.remote

import android.content.Context
import android.graphics.Bitmap
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
        if (advertise) announce(s.localPort)
        Flight.log("phone keyboard: waiting on ${addresses().joinToString()} port ${s.localPort}")
        publish()
    }

    fun stop() {
        val s = server ?: return
        server = null
        peers.forEach { it.close(bye = "the TV app went to the background") }
        runCatching { s.close() }
        withdraw()
        synchronized(lock) { code = null; codeFor = null }
        publish()
        Flight.log("phone keyboard: stopped")
    }

    private fun accept(s: ServerSocket) {
        while (!s.isClosed) {
            val c = runCatching { s.accept() }.getOrNull() ?: break
            if (!KeyLink.isHomeAddress(c.inetAddress) || peers.size >= 4) {
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
        val (c, f) = synchronized(lock) { code to codeFor }
        val ready = peers.filter { it.ready }
        state.value = RemoteKeys.Ui(
            listening = server != null,
            address = if (server != null) addresses().firstOrNull()?.let { "$it:$port" }.orEmpty() else "",
            phones = ready.map { it.name },
            code = c, codeFor = f,
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
        /** Touch mode: how wide the phone draws the pictures (0: it wants none), and the key they are sealed with (null: none for this phone). */
        @Volatile var mirrorWidth = 0
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
        private var plain = false
        /** The key of the sign-in handoff under way (one use). */
        private var handoffKey: ByteArray? = null
        private var tries = 3
        private var second = 0L
        private var inSecond = 0
        private val out = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)

        /** Sending never happens on the main thread (Android forbids network there): one sender thread, in order. */
        fun send(m: Msg) {
            sender.execute { runCatching { synchronized(out) { out.write(KeyLink.encode(m)); out.write("\n"); out.flush() } } }
        }

        fun run() {
            try {
                socket.tcpNoDelay = true
                socket.soTimeout = 30_000                     // the phone says something at least every few seconds
                val input = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                send(Msg.Hi(store.tvId, tvName, nonce, appCode, appVer))
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
                val proved = store.tokenProvedBy(m.proof, nonce, m.phone)
                when {
                    proved != null -> { token = proved; plain = false; frameKey = FrameCrypto.key(proved, nonce); welcome(); true }
                    // A phone app from before 1.9 sends the token itself: it may still type, but not hand over a login.
                    store.phoneFor(m.token) != null -> { token = m.token; plain = true; welcome(); true }
                    else -> askForCode()
                }
            }
            is Msg.LoginsKey -> { offerKey(m.key); true }
            is Msg.Logins -> { receiveLogins(m); true }
            is Msg.Pair -> checkCode(m.code)
            is Msg.Mirror -> {
                if (ready) {
                    mirrorWidth = if (m.on) m.w.coerceIn(MIN_FRAME_WIDTH, KeyLink.MAX_FRAME_WIDTH) else 0
                    awaiting = -1; hasPicture = false; toldNone = null
                    main.post { frameTick() }
                }
                true
            }
            is Msg.FrameOk -> { if (m.n == awaiting) { awaiting = -1; main.post { frameTick() } }; true }
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
            send(Msg.Ok(screen))
            if (resultReady || touchGame) send(Msg.Screen(screen, resultReady, touchGame))
            Flight.log(
                "phone keyboard: $name connected" +
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
            }
            send(Msg.NeedCode)
            Flight.log("phone keyboard: $name asks to pair; the TV shows the code")
            publish()
            return true
        }

        private fun checkCode(given: String): Boolean {
            val right = synchronized(lock) { code?.let { MessageDigest.isEqual(it.toByteArray(), given.trim().toByteArray()) } == true }
            if (right) {
                val token = RemoteStore.randomHex(32)
                store.addPhone(token, name)
                this.token = token; plain = false
                frameKey = FrameCrypto.key(token, nonce)
                synchronized(lock) { code = null; codeFor = null; failures = 0 }
                send(Msg.Paired(token))
                Flight.log("phone keyboard: $name paired")
                welcome()
                return true
            }
            tries--
            synchronized(lock) {
                failures++
                if (failures >= 6) { closedUntil = SystemClock.elapsedRealtime() + 5 * 60_000; code = null; codeFor = null; failures = 0 }
            }
            send(Msg.Wrong(tries.coerceAtLeast(0)))
            publish()
            if (tries <= 0) { send(Msg.Bye("code")); return false }
            return true
        }

        /** The phone wants to hand over a login: the TV's own fresh key, signed with the pairing token, and the agreed key kept for one use. */
        private fun offerKey(phoneKey: String) {
            val t = token
            if (!ready || t == null || plain) { send(Msg.LoginsNo(if (plain) "update-phone" else "pair")); return }
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
            if (!ready) synchronized(lock) { if (peers.none { !it.ready }) { codeFor = null; if (code != null) code = null } }
            publish()
        }
    }

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
        val asked = runCatching { source.capture(width) { bmp -> encoder.execute { picture(n, bmp, due, now) } } }
        if (asked.isFailure) {
            capturing = false
            main.postDelayed(frameTickR, CAPTURE_RETRY_MS)
        } else main.postDelayed(frameTickR, CAPTURE_STUCK_MS + 100)
    }

    /** On the pictures' thread: the JPEG, sealed for each phone that is due one (and only when it differs from the last). */
    private fun picture(n: Int, bmp: Bitmap?, due: List<Peer>, started: Long) {
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
            p.send(Msg.Frame(n, w, h, iv, data))
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
