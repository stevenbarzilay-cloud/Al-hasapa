package app.couch.alhasapa.remote

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import app.couch.alhasapa.diag.Flight
import app.couch.alhasapa.tv.TvController
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The TV app's end of the phone-as-keyboard link, app-wide: the screen in front (whose page gets what the phone types), the server's life
 * (on while one of the app's screens shows, off a few seconds after they all left), and what the TV shows about it.
 */
object RemoteKeys {
    /** What the TV shows: the pairing code while a phone is being paired, and the phones that are connected. */
    data class Ui(
        val listening: Boolean = false,
        val address: String = "",
        val phones: List<String> = emptyList(),
        val code: String? = null,
        val codeFor: String? = null,
    )

    val ui = MutableStateFlow(Ui())

    /** The TV screen in front (set by its host when it resumes). */
    @Volatile var front: TvController? = null
        private set

    // It holds the application's context only, which lives exactly as long as the process: not a leak.
    @SuppressLint("StaticFieldLeak")
    @Volatile var server: KeyServer? = null
        private set

    private val control = Executors.newSingleThreadExecutor { r -> Thread(r, "keys-control").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private val stopLater = Runnable { control.execute { server?.stop() } }

    /** A screen came to the front: what the phone types goes to its page, and the phone is told which screen it is. */
    fun attach(c: TvController) {
        front = c
        server?.screen = c.screenTitle
    }

    fun detach(c: TvController) { if (front === c) front = null }

    /** The app's screens are showing ([visible]) or have all left. On a TV only. */
    fun foreground(context: Context, visible: Boolean) {
        main.removeCallbacks(stopLater)
        if (visible) {
            val app = context.applicationContext
            control.execute {
                val s = server ?: KeyServer(app).also { server = it }
                s.start()
            }
        } else {
            // A few seconds' grace: going from one screen to the next is not leaving.
            main.postDelayed(stopLater, 4_000)
        }
    }

    /** What a paired phone typed, on the main thread: into the page of the screen in front. */
    fun deliver(m: KeyLink.Msg) {
        val c = front
        if (c == null) { if (m !is KeyLink.Msg.Pointer) Flight.log("phone keyboard: input while no screen is in front"); return }
        when (m) {
            is KeyLink.Msg.Text -> c.remoteInput(TypedKeys.deliveries(m.s))
            is KeyLink.Msg.Press -> c.remoteInput(listOf(TypedKeys.forKey(m.key, m.action)))
            is KeyLink.Msg.Pointer -> c.remotePointer(m.on)
            is KeyLink.Msg.Move -> c.remoteMove(m.dx, m.dy)
            KeyLink.Msg.Click -> c.remoteClick()
            is KeyLink.Msg.Drag -> c.remoteDrag(m.on)
            is KeyLink.Msg.Scroll -> c.remoteScroll(m.dy)
            else -> {}
        }
    }
}
