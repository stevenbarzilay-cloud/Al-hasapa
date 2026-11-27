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
 * All the waiting happens on its own thread; [send] and the rest can be called from the screen at any time.
 */
class KeyClient(
    context: Context,
    private val store: RemoteStore = RemoteStore(context),
    private val phoneName: String = Build.MODEL.orEmpty().ifBlank { "טלפון" },
    private val find: (timeoutMs: Long) -> String? = { NsdFinder(context).find(it) },
    /** Told to the TV and compared with what the TV tells (see [KeyLink.TOGETHER_SINCE] and [Versions]), and this app's version by name. */
    private val appCode: Int = KeyLink.TOGETHER_SINCE,
    private val appVer: String = app.couch.alhasapa.BuildConfig.VERSION_NAME,
    /** Both of us as this phone knows us (1.9.9): sent to the TV, and the TV's newer word kept. */
    private val book: CoupleBook = RepoCoupleBook { (context.applicationContext as? app.couch.alhasapa.CouchApp)?.repo },
) {
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
         * by dragging, and the phone turns to touch mode by itself (1.9.8).
         */
        data class Connected(val tv: String, val screen: String, val result: Boolean = false, val touch: Boolean = false) : State
        /** The TV closed pairing for a while (too many wrong codes). */
        data object Wait : State
    }

    private val _state = MutableStateFlow<State>(State.Looking)
    val state: StateFlow<State> get() = _state

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

    /** Touch mode: a picture of the TV's game, opened ([jpeg] as the TV made it), [w] by [h] pixels. */
    class MirrorFrame(val n: Int, val w: Int, val h: Int, val jpeg: ByteArray)

    private val _frame = MutableStateFlow<MirrorFrame?>(null)
    /** The latest picture of the TV's game while touch mode is on the screen (null: none yet, or touch mode is off). */
    val frame: StateFlow<MirrorFrame?> get() = _frame

    private val _frameNone = MutableStateFlow<String?>(null)
    /** Why there is no picture ([KeyLink.NONE_HOME], [KeyLink.NONE_CAPTURE]), when the TV said so. */
    val frameNone: StateFlow<String?> get() = _frameNone

    /** Touch mode is on the screen, drawing pictures this wide (0: it is not). Asked for again whenever the connection comes back. */
    @Volatile private var mirrorWidth = 0
    /** The connection's picture key (see [FrameCrypto]): from the pairing token and the TV's nonce of this connection. */
    @Volatile private var frameKey: ByteArray? = null
    @Volatile private var nonce = ""
    @Volatile private var badFrames = 0

    /** Touch mode came on the screen ([on], drawing pictures [width] pixels wide) or left it. */
    fun mirror(on: Boolean, width: Int) {
        mirrorWidth = if (on) width.coerceAtLeast(1) else 0
        if (!on) { _frame.value = null; _frameNone.value = null }
        send(Msg.Mirror(on, mirrorWidth))
    }

    /** Picture [n] is on the screen: the TV may send the next. */
    fun frameShown(n: Int) = send(Msg.FrameOk(n))

    private val _notesWaiting = MutableStateFlow(store.pendingNotes().size)
    /** How many notes written here wait for the TV (sent at the next connection). */
    val notesWaiting: StateFlow<Int> get() = _notesWaiting

    /** Both of us as this phone knows us, and whose phone it is, to the TV (after a change made here; on connecting it goes by itself). */
    fun sendCouple() {
        val people = runCatching { book.couple() }.getOrDefault(emptyList())
        if (people.isNotEmpty()) send(Msg.CoupleMsg(store.me.orEmpty(), people))
    }

    /** A note for the other one: kept here until the TV says it has it. */
    fun sendNote(n: app.couch.alhasapa.data.Note) {
        store.keepNote(n)
        _notesWaiting.value = store.pendingNotes().size
        send(Msg.NoteMsg(n))
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

    fun start() {
        if (!running.compareAndSet(false, true)) return
        Thread({ loop() }, "keys-link").apply { isDaemon = true }.start()
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        closeSocket()
        wake.offer(Unit)
    }

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

    private fun loop() {
        var pause = 0L
        while (running.get()) {
            if (pause > 0) { wake.poll(pause, TimeUnit.MILLISECONDS); if (!running.get()) break }
            val fromBefore = manual ?: store.lastAddress
            var address = fromBefore
            var connected = address != null && session(address)
            if (!connected && running.get() && manual == null) {
                // Not where it was last time (or never found yet): ask the home network.
                _state.value = State.Looking
                address = find(8_000)
                if (address == null) {
                    _state.value = State.NotFound(NO_TV)
                    wake.clear(); wake.poll(20_000, TimeUnit.MILLISECONDS)      // a press of "look again", or by itself in a while
                    pause = 0
                    continue
                }
                connected = session(address)
            }
            pause = if (connected) 800 else 2_500                              // dropped: back quickly; refused: a little later
        }
        _state.value = State.Looking
    }

    /** One connection, from the greeting to its end. True when it got as far as the TV answering. */
    private fun session(address: String): Boolean {
        val host = address.substringBeforeLast(':')
        val port = address.substringAfterLast(':').toIntOrNull() ?: KeyLink.PORT
        _state.value = State.Connecting(address)
        val s = Socket()
        var answered = false
        try {
            s.connect(InetSocketAddress(host, port), 2_500)
            s.tcpNoDelay = true
            s.soTimeout = 15_000
            socket = s
            val input = s.getInputStream().bufferedReader(Charsets.UTF_8)
            while (running.get()) {
                // A line from the TV may hold a picture of the game (touch mode): larger than anything the phone sends.
                val line = KeyServer.readLine(input, KeyLink.MAX_FRAME_LINE) ?: break
                val m = KeyLink.decode(line, KeyLink.MAX_FRAME_LINE) ?: continue
                answered = true
                when (m) {
                    is Msg.Hi -> {
                        tvId = m.tv; tvName = m.name
                        nonce = m.nonce
                        _versions.value = when { m.app > appCode -> Versions.TvNewer(m.ver); m.app < appCode -> Versions.TvOlder; else -> null }
                        val token = store.tokenFor(m.tv)
                        frameKey = if (token.isNotEmpty() && m.nonce.isNotEmpty()) FrameCrypto.key(token, m.nonce) else null
                        // From 1.9 the TV sends a nonce, and the phone proves it holds the token without sending it. An older TV
                        // gets the token as before.
                        val hello = if (m.nonce.isNotEmpty() && token.isNotEmpty()) {
                            Msg.Hello(store.phoneId, phoneName, "", LoginCrypto.proof(token, m.nonce, store.phoneId), appCode, appVer)
                        } else Msg.Hello(store.phoneId, phoneName, token, "", appCode, appVer)
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
                        _state.value = State.Connected(tvName, m.screen)
                        store.lastAddress = address
                        Flight.log("phone keyboard: connected to the TV at $address")
                        startPinging()
                        // Touch mode was on the screen when the connection dropped: the pictures are asked for again.
                        if (mirrorWidth > 0) write(Msg.Mirror(true, mirrorWidth))
                        // Both of us as this phone knows us, and the notes still waiting for the TV.
                        sendCouple()
                        for (n in store.pendingNotes()) write(Msg.NoteMsg(n))
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
                            _frame.value = MirrorFrame(m.n, m.w, m.h, jpeg)
                        }
                    }
                    is Msg.FrameNone -> if (mirrorWidth > 0) { _frame.value = null; _frameNone.value = m.why }
                    is Msg.CoupleMsg -> runCatching { book.offer(m.people) }
                    is Msg.NoteOk -> { store.noteKept(m.id); _notesWaiting.value = store.pendingNotes().size }
                    is Msg.Bye -> { if (m.why == "wait") _state.value = State.Wait; break }
                    else -> {}
                }
            }
        } catch (e: Exception) {
            if (!answered) Flight.log("phone keyboard: no TV at $address (${e.javaClass.simpleName})")
        } finally {
            stopPinging()
            if (socket === s) socket = null
            runCatching { s.close() }
            // A login that was on its way when the connection dropped did not arrive.
            pending?.let { p -> pending = null; _handoff.value = Handoff.Failed(p.site, "not-connected") }
        }
        if (answered && _state.value is State.Connected) _state.value = State.Connecting(address)
        return answered
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

/** Asks the home network (DNS-SD) where the TV's keyboard link is. */
class NsdFinder(context: Context) {
    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager

    /** "host:port" of the first TV found within [timeoutMs], or null. */
    @Suppress("DEPRECATION") // resolveService and host: the replacements need Android 14; these work on every version the phone app runs on
    fun find(timeoutMs: Long): String? {
        val nsd = nsd ?: return null
        val found = LinkedBlockingQueue<String>()
        val resolving = AtomicBoolean(false)
        val discovery = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                if (!info.serviceType.contains("_couchkeys") || !resolving.compareAndSet(false, true)) return
                runCatching {
                    nsd.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onServiceResolved(r: NsdServiceInfo) {
                            r.host?.hostAddress?.let { found.offer((if (':' in it) "[$it]" else it) + ":" + r.port) }
                            resolving.set(false)
                        }
                        override fun onResolveFailed(r: NsdServiceInfo, error: Int) { resolving.set(false) }
                    })
                }.onFailure { resolving.set(false) }
            }
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, error: Int) { found.offer("") }
            override fun onStopDiscoveryFailed(type: String, error: Int) {}
        }
        if (runCatching { nsd.discoverServices(KeyLink.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery) }.isFailure) return null
        val r = found.poll(timeoutMs, TimeUnit.MILLISECONDS)
        runCatching { nsd.stopServiceDiscovery(discovery) }
        return r?.takeIf { it.isNotEmpty() }
    }
}
