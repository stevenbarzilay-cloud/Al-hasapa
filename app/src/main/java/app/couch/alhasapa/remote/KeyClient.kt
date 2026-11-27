package app.couch.alhasapa.remote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import app.couch.alhasapa.diag.Flight
import app.couch.alhasapa.remote.KeyLink.Msg
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The phone's side of the phone-as-keyboard link. Finds the TV (where it was last time, or by asking the home network), connects, pairs
 * once with the code the TV shows, and then sends what is typed, as it is typed. If the connection drops it comes back by itself.
 *
 * Which TV (2.0): only the ones named by its [Target]: the home couch's own, or the one of the friend's couch being visited. Another
 * TV that happens to be on the same network is never connected to, however it was found: every TV says who it is first, and one that
 * is not meant is let go of before the phone has said anything of itself.
 *
 * All the waiting happens on its own thread; [send] and the rest can be called from the screen at any time.
 */
class KeyClient(
    context: Context,
    private val store: RemoteStore = RemoteStore(context),
    private val phoneName: String = Build.MODEL.orEmpty().ifBlank { "טלפון" },
    /** In the tests: where the TV is. */
    private val find: ((timeoutMs: Long) -> String?)? = null,
    /** Told to the TV and compared with what the TV tells (see [KeyLink.TOGETHER_SINCE] and [Versions]), and this app's version by name. */
    private val appCode: Int = KeyLink.TOGETHER_SINCE,
    private val appVer: String = app.couch.alhasapa.BuildConfig.VERSION_NAME,
    /** Both of us as this phone knows us (1.9.9): sent to the TV, and the TV's newer word kept. */
    private val book: CoupleBook = RepoCoupleBook { (context.applicationContext as? app.couch.alhasapa.CouchApp)?.repo },
    /** Which TVs to connect to, and as what (2.0). Null: none (the home couch has no TV yet, and no visit is on). */
    target: Target? = Target.ANY_HOME,
    /** What the phone keeps of friends' couches (2.0): their players as their TVs name them, and the games played alone there. */
    private val couches: CouchStore = CouchStore(context),
    private val guestSolo: GuestSolo = GuestSolo(context),
    /** Where the TVs of [Target.tvs] are on this network, the likeliest first (asked when they are not where they were last). */
    private val finder: (tvs: Set<String>, timeoutMs: Long) -> List<String> = { tvs, ms ->
        if (find != null) listOfNotNull(find.invoke(ms)) else NsdFinder(context).find(tvs, ms)
    },
) {
    /**
     * Which TVs this client may connect to, and what the phone comes as ([role], see [KeyLink.ROLES]; [ASK]: only to see which couch a
     * TV is of, for a phone from before 2.0 that cannot tell which of its TVs is its home's). [address]: where a TV chosen by hand is.
     * No [tvs] at all: any TV (in the tests only; the app always names them).
     */
    data class Target(val tvs: Set<String>, val role: String = KeyLink.AS_HOME, val address: String? = null) {
        companion object {
            const val ASK = "ask"
            val ANY_HOME = Target(emptySet(), KeyLink.AS_HOME)
        }
    }

    /** A TV that said who it is: its id and name, and the couch it is of (2.0; null from a TV with none yet, or an app before 2.0). */
    data class Tv(val id: String, val name: String, val couch: app.couch.alhasapa.data.CouchRef?)

    sealed interface State {
        /** Looking for the TV on the home network. */
        data object Looking : State
        /** The TV was not found ([reason]: [NO_TV], [NO_PERMISSION]). */
        data class NotFound(val reason: String) : State
        data class Connecting(val where: String) : State
        /** The TV shows four digits; [left] tries remain after a wrong one. */
        data class NeedCode(val tv: String, val left: Int? = null) : State
        /**
         * [result]: a game's end screen is up on the TV, and the phone offers to save the result. [touch]: the game on the TV is played
         * by dragging, and the phone turns to touch mode by itself (1.9.8). [couch]: the couch the TV is of, and [role] what the phone
         * was let in as (2.0).
         */
        data class Connected(
            val tv: String, val screen: String, val result: Boolean = false, val touch: Boolean = false,
            val couch: app.couch.alhasapa.data.CouchRef? = null, val role: String = KeyLink.AS_HOME,
        ) : State
        /** The TV closed pairing for a while (too many wrong codes). */
        data object Wait : State
        /** There is no TV to connect to (2.0): the home couch has none yet, and no visit is on. */
        data object NoTv : State
        /** The TV meant to be the home's is of another couch now ([tv]): nothing was said to it. */
        data class OtherCouch(val tv: Tv) : State
        /** A phone from before 2.0, paired with several TVs: is [tv] its home's? Asked once; nothing is said to the TV meanwhile. */
        data class AskHome(val tv: Tv) : State
    }

    private val _state = MutableStateFlow<State>(State.Looking)
    val state: StateFlow<State> get() = _state

    /** Which TVs, and as what (see [Target]); changed with [retarget]. */
    @Volatile var target: Target? = target
        private set

    /** Another TV, or none, from now on (a visit began or ended, the home's TV was chosen): what is connected is let go of at once. */
    fun retarget(t: Target?) {
        if (t == target) return
        target = t
        manual = null
        closeSocket(); wake.offer(Unit)
    }

    private val _tv = MutableStateFlow<Tv?>(null)
    /** The TV in front of this client: the last one that said who it is and was one of the meant ones. */
    val tv: StateFlow<Tv?> get() = _tv

    /** What the phone was let in as on this connection ([KeyLink.AS_HOME] until the TV says otherwise). */
    @Volatile private var role = KeyLink.AS_HOME
    private val isGuest: Boolean get() = role != KeyLink.AS_HOME
    /** The couch of the TV connected to (2.0), when it said. */
    @Volatile private var tvCouch: app.couch.alhasapa.data.CouchRef? = null
    /** A phone with no couch of its own yet takes the TV's first (it joins it), and only then says what it keeps. */
    @Volatile private var adopting = false

    /** A site's login on its way to the TV (see [sendLogins]). */
    sealed interface Handoff {
        val site: String
        data class Sending(override val site: String) : Handoff
        data class Done(override val site: String, val n: Int) : Handoff
        /** [why]: "update-tv" (the TV app is too old to answer), "update-phone", "signature", "not-connected", or the TV's own reason. */
        data class Failed(override val site: String, val why: String) : Handoff
    }

    private val _handoff = MutableStateFlow<Handoff?>(null)
    val handoff: StateFlow<Handoff?> get() = _handoff

    /**
     * How the TV's app compares with this phone's, once the TV has said hello: one of the two was left behind on an old version. (The
     * two are always built together; in November 2026 the TV was updated three times while the phone stayed on a version that crashed.)
     */
    sealed interface Versions {
        /** The TV has a newer app ([ver]): this phone should be updated. */
        data class TvNewer(val ver: String) : Versions
        /** The TV's app is older than this phone's (an app from before 1.9.4 does not say which). */
        data object TvOlder : Versions
    }

    private val _versions = MutableStateFlow<Versions?>(null)
    val versions: StateFlow<Versions?> get() = _versions

    /** Touch mode: a picture of the TV's game, opened ([jpeg] as the TV made it), [w] by [h] pixels, of the part [crop] of the page (null: all). */
    class MirrorFrame(val n: Int, val w: Int, val h: Int, val jpeg: ByteArray, val crop: KeyLink.Crop? = null)

    private val _frame = MutableStateFlow<MirrorFrame?>(null)
    /** The latest picture of the TV's game while touch mode is on the screen (null: none yet, or touch mode is off). */
    val frame: StateFlow<MirrorFrame?> get() = _frame

    private val _frameNone = MutableStateFlow<String?>(null)
    /** Why there is no picture ([KeyLink.NONE_HOME], [KeyLink.NONE_CAPTURE]), when the TV said so. */
    val frameNone: StateFlow<String?> get() = _frameNone

    /** Touch mode is on the screen, drawing pictures this wide (0: it is not), of this part of the page. Asked for again whenever the connection comes back. */
    @Volatile private var mirrorWidth = 0
    @Volatile private var mirrorCrop: KeyLink.Crop? = null
    /** The connection's picture key (see [FrameCrypto]): from the pairing token and the TV's nonce of this connection. */
    @Volatile private var frameKey: ByteArray? = null
    private val appContext: Context = context.applicationContext

    /**
     * The solo relay from the TV (1.11), sealed under this connection's key: kept, and the phone starts looking at the relay by itself.
     * Anything that does not open is dropped.
     */
    private fun takeRelay(sealed: String) {
        // (Never a friend's: a visit does not replace the home's own channel.)
        if (isGuest) return
        val key = frameKey ?: return
        val c = RelayCrypto.open(key, KeyLink.RELAY_AAD, sealed)?.let { RelayConfig.parse(String(it, Charsets.UTF_8)) }
        if (c == null) { Flight.log("relay: what the TV sent could not be opened; not kept"); return }
        val rs = RelayStore(appContext)
        if (rs.config == c) return
        rs.config = c
        Flight.log("relay: the TV gave this phone the relay")
        SoloJob.schedule(appContext)
    }
    @Volatile private var nonce = ""
    @Volatile private var badFrames = 0

    /** Touch mode came on the screen ([on], drawing pictures [width] pixels wide, of the part [crop] of the page when zoomed in) or left it. */
    fun mirror(on: Boolean, width: Int, crop: KeyLink.Crop? = null) {
        mirrorWidth = if (on) width.coerceAtLeast(1) else 0
        mirrorCrop = if (on) crop else null
        if (!on) { _frame.value = null; _frameNone.value = null }
        send(Msg.Mirror(on, mirrorWidth, mirrorCrop))
    }

    /** Picture [n] is on the screen: the TV may send the next. */
    fun frameShown(n: Int) = send(Msg.FrameOk(n))

    private val _notesWaiting = MutableStateFlow(store.pendingNotes().size)
    /** How many notes written here wait for the TV (sent at the next connection). */
    val notesWaiting: StateFlow<Int> get() = _notesWaiting

    /** Both of us as this phone knows us, and whose phone it is, to the TV (after a change made here; on connecting it goes by itself). */
    fun sendCouple() {
        if (isGuest) { sendGuest(null); return }
        val people = runCatching { book.couple() }.getOrDefault(emptyList())
        if (people.isNotEmpty()) send(Msg.CoupleMsg(store.me.orEmpty(), people))
    }

    /**
     * As a guest (2.0): who the phone's holder is at this couch, and, when they are new there, themselves ([added]: a guest of the
     * evening, or someone who comes often). Nothing of the home's players is said to a friend's TV.
     */
    fun sendGuest(added: app.couch.alhasapa.data.Person?) {
        val c = tvCouch ?: return
        val saved = couches.saved(c.id)
        val me = added?.who ?: couches.visit?.takeIf { it.couch == c.id }?.me ?: saved?.me ?: return
        // Themselves, when they are new there, until that couch's TV names them among its players.
        val made = added ?: saved?.made?.takeIf { p -> p.who == me && couches.people(c.id).none { it.who == p.who } }
        send(Msg.CoupleMsg(me, listOfNotNull(made)))
    }

    /** A note for the other one: kept here until the TV says it has it. [couch]: the couch it is for (2.0; null: the home's). */
    fun sendNote(n: app.couch.alhasapa.data.Note, couch: String? = null) {
        val to = couch ?: runCatching { book.couch() }.getOrNull()?.id
        store.keepNote(n, to)
        _notesWaiting.value = store.pendingNotes().size
        if (notesGoHere()) send(Msg.NoteMsg(n))
    }

    /** The couch the notes waiting on this phone may go to now: the one the connected TV is of. */
    private fun notesFor(): List<app.couch.alhasapa.data.Note> {
        val home = runCatching { book.couch() }.getOrNull()?.id
        val here = if (isGuest) tvCouch?.id else home
        return store.pendingNotes(here, home)
    }

    private fun notesGoHere(): Boolean = _state.value is State.Connected

    /**
     * The games the phone's holder played alone for this couch's contest and its TV does not keep yet (2.0): sent now. Each goes as
     * it was written; the TV keeps the newest of a side once, so one sent twice counts once.
     */
    fun flushGuest() {
        val c = tvCouch ?: return
        if (_state.value !is State.Connected) return
        for (e in guestSolo.pending(c.id)) write(Msg.SoloSideMsg(e.day, e.game, e.side.toJson()))
    }

    private class Pending(val site: String, val payload: String, val pair: java.security.KeyPair, val key: String)
    @Volatile private var pending: Pending? = null

    private val running = AtomicBoolean(false)
    private val wake = LinkedBlockingQueue<Unit>()
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "keys-out").apply { isDaemon = true } }
    private var pinger: ScheduledExecutorService? = null
    @Volatile private var socket: Socket? = null
    @Volatile private var manual: String? = null
    @Volatile private var tvId = ""
    @Volatile private var tvName = ""

    /** Which start this is: a loop of an earlier one (stopped, and not yet gone when the next began) ends when it sees a newer. */
    private val round = java.util.concurrent.atomic.AtomicInteger()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        ShareBus.add(pushShare)
        val mine = round.incrementAndGet()
        Thread({ loop(mine) }, "keys-link").apply { isDaemon = true }.start()
    }

    private fun alive(mine: Int): Boolean = running.get() && round.get() == mine

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        ShareBus.remove(pushShare)
        closeSocket()
        wake.offer(Unit)
    }

    /** A change made on this phone (1.12): its recent share goes to the TV, when connected (to the home's own TV only, 2.0). */
    private val pushShare: () -> Unit = {
        // Made ready on the thread that writes to the TV, not on the one that made the change (the main one, as a rule).
        if (_state.value is State.Connected && !isGuest && !adopting && !book.fresh()) writer.execute { runCatching { ShareBus.recent(book).forEach { write(Msg.Share(it)) } } }
    }

    /** How many times the TV brought something new (1.12): the screens showing the state read it again. */
    private val _shared = MutableStateFlow(0)
    val shared: StateFlow<Int> get() = _shared

    /** Every evening the TV keeps was asked for and is still on its way (the first time the two meet since 1.12). */
    @Volatile var awaitingAll = false
        private set

    /** When this phone last sent the TV its share (as a connection opened). */
    @Volatile var sharedAt = 0L
        private set

    /** Look again now (after "not found"). */
    fun retry() { wake.offer(Unit) }

    /** The TV's address typed by hand ("192.168.1.23" or "192.168.1.23:47123"), when the home network does not announce it. */
    fun useAddress(address: String) {
        manual = address.trim().ifEmpty { null }?.let { if (':' in it) it else "$it:${KeyLink.PORT}" }
        closeSocket(); wake.offer(Unit)
    }

    /** Asking for the local-network permission was refused: nothing can be found without it. */
    fun noPermission() { _state.value = State.NotFound(NO_PERMISSION) }

    fun sendCode(code: String) = write(Msg.Pair(code))

    /**
     * Hands a site's login ([payload], from [LoginHandoff.payload]) to the TV: a fresh key, the TV's signed answer, then the login
     * encrypted for that TV only. [handoff] tells how it went; a TV that does not answer within a few seconds has an app too old for it.
     */
    fun sendLogins(site: String, payload: String) {
        if (_state.value !is State.Connected) { _handoff.value = Handoff.Failed(site, "not-connected"); return }
        // A login goes to the home's own TV only (2.0): never to a friend's, whoever asks.
        if (isGuest) { _handoff.value = Handoff.Failed(site, "guest"); return }
        val pair = LoginCrypto.newKeyPair()
        val p = Pending(site, payload, pair, LoginCrypto.encode(pair.public))
        pending = p
        _handoff.value = Handoff.Sending(site)
        write(Msg.LoginsKey(p.key))
        // The connection's own timer (it lives as long as the connection): a TV that says nothing has an app from before 1.9.
        pinger?.schedule({
            if (pending === p) { pending = null; _handoff.value = Handoff.Failed(site, "update-tv") }
        }, 8, TimeUnit.SECONDS)
    }

    /** A handoff that could not even start (the phone is not signed in to the site): said like any other outcome. */
    fun loginsFailed(site: String, why: String) { _handoff.value = Handoff.Failed(site, why) }

    /** The TV's answer to [sendLogins]: its key, signed with the pairing token. Checked, then the login goes, encrypted. */
    private fun finishLogins(m: Msg.LoginsReady) {
        val p = pending ?: return
        val token = store.tokenFor(tvId)
        val tvPub = LoginCrypto.decode(m.key)
        if (token.isEmpty() || tvPub == null || !LoginCrypto.same(LoginCrypto.tvSignature(token, p.key, m.key), m.sig)) {
            pending = null
            _handoff.value = Handoff.Failed(p.site, "signature")
            Flight.log("logins: the TV's answer was not signed with our pairing; nothing was sent")
            return
        }
        val key = LoginCrypto.sessionKey(p.pair.private, tvPub, token, p.key, m.key)
        val (iv, data) = LoginCrypto.seal(key, p.payload.toByteArray(Charsets.UTF_8))
        write(Msg.Logins(iv, data))
    }

    /** Text or a key, sent at once; dropped while not connected. */
    fun send(m: Msg) { if (_state.value is State.Connected) write(m) }

    private fun write(m: Msg) {
        val s = socket ?: return
        writer.execute {
            runCatching { val o = s.getOutputStream(); o.write((KeyLink.encode(m) + "\n").toByteArray(Charsets.UTF_8)); o.flush() }
                .onFailure { closeSocket() }
        }
    }

    private fun closeSocket() {
        val s = socket ?: return
        socket = null
        writer.execute { runCatching { s.close() } }
    }

    private enum class Met { NOBODY, ANOTHER, OURS }

    private fun loop(mine: Int) {
        var pause = 0L
        while (alive(mine)) {
            if (pause > 0) { wake.poll(pause, TimeUnit.MILLISECONDS); if (!alive(mine)) break }
            val t = target
            if (t == null) {
                // No TV is meant: nothing is looked for, whatever is on this network.
                _state.value = State.NoTv; _tv.value = null
                wake.clear(); wake.poll(60_000, TimeUnit.MILLISECONDS)
                pause = 0
                continue
            }
            // Where a meant TV was last time (or where the person said it is), then the home network.
            val tried = HashSet<String>()
            var met = Met.NOBODY
            val known = listOfNotNull(manual, t.address) + t.tvs.mapNotNull { store.addressOf(it) } +
                (if (t.role == KeyLink.AS_HOME) listOfNotNull(store.lastAddress) else emptyList())
            for (a in known) {
                if (!alive(mine) || target != t || met == Met.OURS) break
                if (tried.add(a)) met = maxOf(met, session(a, t, mine))
            }
            if (met != Met.OURS && alive(mine) && target == t && manual == null) {
                _state.value = State.Looking
                val found = finder(t.tvs, 8_000).filter { it !in tried }
                for (a in found) {
                    if (!alive(mine) || target != t || met == Met.OURS) break
                    if (tried.add(a)) met = maxOf(met, session(a, t, mine))
                }
                if (met != Met.OURS && alive(mine) && target == t && _state.value !is State.OtherCouch && _state.value !is State.AskHome) {
                    _state.value = State.NotFound(NO_TV)
                    wake.clear(); wake.poll(20_000, TimeUnit.MILLISECONDS)      // a press of "look again", or by itself in a while
                    pause = 0
                    continue
                }
            }
            if (_state.value is State.OtherCouch || _state.value is State.AskHome) {
                // Until the person answers (or a while passed): nothing more is tried.
                wake.clear(); wake.poll(60_000, TimeUnit.MILLISECONDS)
                pause = 0
                continue
            }
            pause = if (met == Met.OURS) 800 else 2_500                         // dropped: back quickly; refused: a little later
        }
        if (round.get() == mine) _state.value = State.Looking
    }

    /** One connection, from the greeting to its end: who was there (nobody, a TV that is not meant, or one that is). */
    private fun session(address: String, t: Target, mine: Int): Met {
        val host = address.substringBeforeLast(':')
        val port = address.substringAfterLast(':').toIntOrNull() ?: KeyLink.PORT
        _state.value = State.Connecting(address)
        val s = Socket()
        var met = Met.NOBODY
        try {
            s.connect(InetSocketAddress(host, port), 2_500)
            s.tcpNoDelay = true
            s.soTimeout = 15_000
            socket = s
            val input = s.getInputStream().bufferedReader(Charsets.UTF_8)
            while (alive(mine) && target == t) {
                // A line from the TV may hold a picture of the game (touch mode): larger than anything the phone sends.
                val line = KeyServer.readLine(input, KeyLink.MAX_FRAME_LINE) ?: break
                val m = KeyLink.decode(line, KeyLink.MAX_FRAME_LINE) ?: continue
                if (met == Met.NOBODY && m !is Msg.Hi) break                    // not a TV of ours at all
                when (m) {
                    is Msg.Hi -> {
                        // Who it is, before the phone says anything of itself: a TV that is not meant is left at once.
                        if (t.tvs.isNotEmpty() && m.tv !in t.tvs) {
                            met = Met.ANOTHER
                            if (t.tvs.any { store.addressOf(it) == address }) Flight.log("phone keyboard: another TV is at $address now; left alone")
                            break
                        }
                        met = Met.OURS
                        val couch = m.couch.takeIf { it.isNotEmpty() }?.let { app.couch.alhasapa.data.CouchRef(it, m.couchName.ifEmpty { m.name }) }
                        val seen = Tv(m.tv, m.name, couch)
                        tvId = m.tv; tvName = m.name; tvCouch = couch
                        nonce = m.nonce
                        _tv.value = seen
                        if (m.tv.isNotEmpty()) store.setAddress(m.tv, address)
                        _versions.value = when { m.app > appCode -> Versions.TvNewer(m.ver); m.app < appCode -> Versions.TvOlder; else -> null }
                        // A phone from before 2.0 that knew several TVs: is this one its home's? Nothing is said until it is answered.
                        if (t.role == Target.ASK) { _state.value = State.AskHome(seen); break }
                        // The home's TV turned out to be of another couch (it was set up anew, say): nothing of the home goes to it.
                        val mine = runCatching { book.couch() }.getOrNull()
                        if (t.role == KeyLink.AS_HOME && mine != null && couch != null && couch.id != mine.id) {
                            Flight.log("phone keyboard: the TV at $address is of another couch; nothing was said to it")
                            _state.value = State.OtherCouch(seen)
                            break
                        }
                        val token = store.tokenFor(m.tv)
                        frameKey = if (token.isNotEmpty() && m.nonce.isNotEmpty()) FrameCrypto.key(token, m.nonce) else null
                        // From 1.9 the TV sends a nonce, and the phone proves it holds the token without sending it. An older TV
                        // gets the token as before.
                        val hello = if (m.nonce.isNotEmpty() && token.isNotEmpty()) {
                            Msg.Hello(store.phoneId, phoneName, "", LoginCrypto.proof(token, m.nonce, store.phoneId), appCode, appVer, t.role)
                        } else Msg.Hello(store.phoneId, phoneName, token, "", appCode, appVer, t.role)
                        write(hello)
                    }
                    is Msg.LoginsReady -> finishLogins(m)
                    is Msg.LoginsDone -> { pending = null; _handoff.value = Handoff.Done(m.site, m.n) }
                    is Msg.LoginsNo -> { val p = pending; pending = null; _handoff.value = Handoff.Failed(p?.site.orEmpty(), m.why) }
                    Msg.NeedCode -> _state.value = State.NeedCode(tvName)
                    is Msg.Wrong -> _state.value = State.NeedCode(tvName, m.left)
                    is Msg.Paired -> {
                        store.saveToken(tvId, m.token)
                        if (nonce.isNotEmpty()) frameKey = FrameCrypto.key(m.token, nonce)
                    }
                    is Msg.Ok -> {
                        // What the phone was let in as: what it asked for (a TV app from before 2.0 says nothing, and knows only homes).
                        role = m.role.ifEmpty { if (t.role in KeyLink.ROLES) t.role else KeyLink.AS_HOME }
                        _state.value = State.Connected(tvName, m.screen, couch = tvCouch, role = role)
                        if (!isGuest) store.lastAddress = address
                        Flight.log("phone keyboard: connected to the TV at $address${if (isGuest) ", as a guest" else ""}")
                        startPinging()
                        // Touch mode was on the screen when the connection dropped: the pictures are asked for again.
                        if (mirrorWidth > 0) write(Msg.Mirror(true, mirrorWidth, mirrorCrop))
                        // A guest: who they are here first (a note or a game of theirs is taken as that player's).
                        if (isGuest) sendGuest(null)
                        // The notes written for this couch that its TV does not keep yet.
                        for (n in notesFor()) write(Msg.NoteMsg(n))
                        if (isGuest) {
                            // The games they played alone for this couch. Nothing of the home.
                            flushGuest()
                        } else if (runCatching { book.fresh() }.getOrDefault(false) || (runCatching { book.couch() }.getOrNull() == null && tvCouch != null)) {
                            // A phone with no couch yet (it joins this one, or is from before 2.0 and learns which its home is):
                            // everything the TV keeps first, and only then what the phone keeps.
                            adopting = true; awaitingAll = true
                            write(Msg.ShareAll)
                        } else {
                            // A TV with no couch yet (it joins this phone's): the couch's relay first, if the couch has one
                            // already, so the TV takes it rather than make another.
                            if (tvCouch == null) frameKey?.let { key ->
                                RelayStore(appContext).config?.let { c -> write(Msg.Relay(RelayCrypto.seal(key, KeyLink.RELAY_AAD, c.toJson().toByteArray()))) }
                            }
                            // Both of us as this phone knows us.
                            sendCouple()
                            // Everything this phone keeps alike with the TV (1.12): two weeks of evenings, and the rest. The first time
                            // the two meet, every evening kept, both ways, so the months before are alike on both too.
                            val all = tvId.isNotEmpty() && !store.sharedAll(tvId)
                            for (sh in ShareBus.opening(book, all)) write(Msg.Share(sh))
                            if (all) { awaitingAll = true; write(Msg.ShareAll) }
                        }
                        sharedAt = System.currentTimeMillis()
                    }
                    is Msg.Screen -> (_state.value as? State.Connected)?.let { _state.value = it.copy(screen = m.screen, result = m.result, touch = m.touch) }
                    is Msg.Frame -> {
                        val key = frameKey
                        val jpeg = key?.let { FrameCrypto.open(it, m.n, m.iv, m.data) }
                        if (jpeg == null) {
                            // Not sealed for this phone (or changed on the way): never shown. Said once in the report.
                            if (badFrames++ == 0) Flight.log("touch mode: a picture from the TV could not be opened; not shown")
                        } else if (mirrorWidth > 0) {
                            _frameNone.value = null
                            _frame.value = MirrorFrame(m.n, m.w, m.h, jpeg, m.crop)
                        }
                    }
                    is Msg.FrameNone -> if (mirrorWidth > 0) { _frame.value = null; _frameNone.value = m.why }
                    is Msg.CoupleMsg -> {
                        // A friend's players are kept apart, for the choice of who the phone's holder is there (2.0): they never
                        // become players of the home.
                        if (isGuest) { tvCouch?.let { c -> couches.setPeople(c.id, m.people) }; _shared.value = _shared.value + 1 }
                        else runCatching { book.offer(m.people) }
                    }
                    is Msg.NoteOk -> { store.noteKept(m.id); _notesWaiting.value = store.pendingNotes().size }
                    is Msg.Relay -> takeRelay(m.sealed)
                    is Msg.Share -> { if (!isGuest && runCatching { book.takeShare(m.share) }.getOrDefault(false)) _shared.value = _shared.value + 1 }
                    Msg.ShareAll -> {
                        awaitingAll = false
                        _shared.value = _shared.value + 1
                        if (tvId.isNotEmpty()) store.setSharedAll(tvId)
                        Flight.log("sync: every evening kept is alike on the TV and this phone now")
                        if (adopting) {
                            adopting = false
                            sendCouple()
                            for (sh in ShareBus.opening(book, all = true)) write(Msg.Share(sh))
                        }
                    }
                    is Msg.SoloOk -> tvCouch?.let { c -> guestSolo.markSent(c.id, m.day, m.game, m.t) }
                    is Msg.Contest -> if (isGuest) tvCouch?.let { c -> guestSolo.setContest(c.id, m.day, m.games) }
                    is Msg.Bye -> { if (m.why == "wait") _state.value = State.Wait; break }
                    else -> {}
                }
            }
        } catch (e: Exception) {
            if (met == Met.NOBODY) Flight.log("phone keyboard: no TV at $address (${e.javaClass.simpleName})")
        } finally {
            stopPinging()
            if (socket === s) socket = null
            runCatching { s.close() }
            adopting = false; awaitingAll = false
            // A login that was on its way when the connection dropped did not arrive.
            pending?.let { p -> pending = null; _handoff.value = Handoff.Failed(p.site, "not-connected") }
        }
        if (met == Met.OURS && _state.value is State.Connected) _state.value = State.Connecting(address)
        return met
    }

    private fun startPinging() {
        stopPinging()
        pinger = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "keys-ping").apply { isDaemon = true } }.also {
            it.scheduleWithFixedDelay({ write(Msg.Ping) }, 4, 4, TimeUnit.SECONDS)
        }
    }

    private fun stopPinging() { pinger?.shutdownNow(); pinger = null }

    companion object {
        const val NO_TV = "no-tv"
        const val NO_PERMISSION = "no-permission"
    }
}

/** A TV found on this network (2.0): where it is, and its id when the network said it. */
data class FoundTv(val address: String, val tv: String)

/** Asks the home network (DNS-SD) where the TVs' keyboard links are. */
class NsdFinder(context: Context) {
    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager

    /**
     * Where the TVs of [tvs] are, found within [timeoutMs] (it stops as soon as one of them is). A TV whose id the network does not
     * say is given too (after those it does), to be asked who it is; with no [tvs], every TV found.
     */
    fun find(tvs: Set<String>, timeoutMs: Long): List<String> {
        val all = scan(timeoutMs) { f -> tvs.isNotEmpty() && f.tv in tvs }
        val meant = all.filter { it.tv in tvs }.map { it.address }
        return if (tvs.isEmpty()) all.map { it.address } else meant + all.filter { it.tv.isEmpty() }.map { it.address }
    }

    /** Every TV found within [timeoutMs], or until [enough] says one of them is the one looked for. */
    @Suppress("DEPRECATION") // resolveService and host: the replacements need Android 14; these work on every version the phone app runs on
    fun scan(timeoutMs: Long, enough: (FoundTv) -> Boolean = { false }): List<FoundTv> {
        val nsd = nsd ?: return emptyList()
        val found = LinkedBlockingQueue<FoundTv>()
        val waiting = LinkedBlockingQueue<NsdServiceInfo>()
        val resolving = AtomicBoolean(false)
        fun next() {
            if (!resolving.compareAndSet(false, true)) return
            val info = waiting.poll() ?: run { resolving.set(false); return }
            runCatching {
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onServiceResolved(r: NsdServiceInfo) {
                        val tv = runCatching { r.attributes["tv"]?.let { String(it, Charsets.UTF_8) } }.getOrNull().orEmpty()
                        r.host?.hostAddress?.let { found.offer(FoundTv((if (':' in it) "[$it]" else it) + ":" + r.port, tv)) }
                        resolving.set(false); next()
                    }
                    override fun onResolveFailed(r: NsdServiceInfo, error: Int) { resolving.set(false); next() }
                })
            }.onFailure { resolving.set(false) }
        }
        val discovery = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                if (!info.serviceType.contains("_couchkeys")) return
                waiting.offer(info); next()
            }
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, error: Int) { found.offer(FoundTv("", "")) }
            override fun onStopDiscoveryFailed(type: String, error: Int) {}
        }
        if (runCatching { nsd.discoverServices(KeyLink.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery) }.isFailure) return emptyList()
        val out = LinkedHashMap<String, FoundTv>()
        val until = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = until - System.currentTimeMillis()
            if (left <= 0) break
            val f = found.poll(left, TimeUnit.MILLISECONDS) ?: break
            if (f.address.isEmpty()) break
            out[f.address] = f
            if (enough(f)) break
        }
        runCatching { nsd.stopServiceDiscovery(discovery) }
        return out.values.toList()
    }
}

/** Asks a TV who it is, and nothing more (2.0): its id and name, and the couch it is of. The TV is told nothing of the phone. */
object TvPeek {
    /** Null when nothing of ours answers at [address] within [timeoutMs]. */
    fun ask(address: String, timeoutMs: Int = 2_500): KeyClient.Tv? = runCatching {
        val host = address.substringBeforeLast(':')
        val port = address.substringAfterLast(':').toIntOrNull() ?: KeyLink.PORT
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), timeoutMs)
            s.soTimeout = timeoutMs
            val line = KeyServer.readLine(s.getInputStream().bufferedReader(Charsets.UTF_8)) ?: return null
            val hi = KeyLink.decode(line) as? Msg.Hi ?: return null
            KeyClient.Tv(hi.tv, hi.name, hi.couch.takeIf { it.isNotEmpty() }?.let { app.couch.alhasapa.data.CouchRef(it, hi.couchName.ifEmpty { hi.name }) })
        }
    }.getOrNull()
}
