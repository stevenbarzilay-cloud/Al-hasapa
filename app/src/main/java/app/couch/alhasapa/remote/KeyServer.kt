package app.couch.alhasapa.remote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.couch.alhasapa.diag.Flight
import app.couch.alhasapa.remote.KeyLink.Msg
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
 * four phones at once, and at a human pace. Six wrong codes in a row close pairing for five minutes. Nothing is ever sent back but the name
 * of the screen.
 */
class KeyServer(
    context: Context,
    private val store: RemoteStore = RemoteStore(context),
    private val onInput: (Msg) -> Unit = { RemoteKeys.deliver(it) },
    private val preferredPort: Int = KeyLink.PORT,
    private val advertise: Boolean = true,
    private val tvName: String = Build.MODEL.orEmpty().ifBlank { "TV" },
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
            peers.forEach { if (it.ready) it.send(Msg.Screen(value)) }
        }

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
        state.value = RemoteKeys.Ui(
            listening = server != null,
            address = if (server != null) addresses().firstOrNull()?.let { "$it:$port" }.orEmpty() else "",
            phones = peers.filter { it.ready }.map { it.name },
            code = c, codeFor = f,
        )
        RemoteKeys.ui.value = state.value
    }

    private inner class Peer(private val socket: Socket) {
        @Volatile var ready = false
        @Volatile var name = "טלפון"
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
                send(Msg.Hi(store.tvId, tvName))
                while (true) {
                    val line = readLine(input) ?: break
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
                if (store.phoneFor(m.token) != null) { welcome(); true } else askForCode()
            }
            is Msg.Pair -> checkCode(m.code)
            is Msg.Text, is Msg.Press, is Msg.Pointer, is Msg.Move, Msg.Click, is Msg.Drag, is Msg.Scroll -> {
                if (ready && atHumanPace()) main.post { onInput(m) }
                true
            }
            Msg.Ping -> { send(Msg.Pong); true }
            is Msg.Bye -> false
            else -> true
        }

        private fun welcome() {
            ready = true
            send(Msg.Ok(screen))
            Flight.log("phone keyboard: $name connected")
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
            }
            // A phone that was being paired and went away: the TV stops showing the code (it comes back if the phone does).
            if (!ready) synchronized(lock) { if (peers.none { !it.ready }) { codeFor = null; if (code != null) code = null } }
            publish()
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

        /** The TV's addresses on the home network (shown in the information panel, typed on the phone if it cannot find the TV). */
        fun addresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>().map { it.hostAddress.orEmpty() }.filter { it.isNotEmpty() }
        }.getOrDefault(emptyList())

        /** One line from the phone, never longer than [KeyLink.MAX_LINE]. Null at the end of the stream. */
        fun readLine(r: Reader): String? {
            val sb = StringBuilder()
            while (true) {
                val ch = r.read()
                if (ch < 0) return if (sb.isEmpty()) null else sb.toString()
                if (ch == '\n'.code) return sb.toString()
                if (sb.length >= KeyLink.MAX_LINE) throw IOException("line too long")
                sb.append(ch.toChar())
            }
        }
    }
}
