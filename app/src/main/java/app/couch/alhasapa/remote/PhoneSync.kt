package app.couch.alhasapa.remote

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.couch.alhasapa.data.Couple
import app.couch.alhasapa.diag.Flight

/**
 * What the phone holds for the TV gets there by itself (1.10): a note written away from it, a name, a colour, a user added or removed.
 * When the app comes to the front with any of that waiting, and the phone has found the TV before, it connects for a moment, hands it
 * over (the couple and the notes go as a connection opens, see KeyClient) and lets go: keyboard mode is not needed for it. If the TV is
 * not there, it waits for the next time.
 */
class PhoneSync(
    private val context: Context,
    private val newClient: () -> KeyClient = { KeyClient(context) },
    /** The newest change to how any of us looks, here (0 when nothing is known). */
    private val newest: () -> Long = {
        (context.applicationContext as? app.couch.alhasapa.CouchApp)?.repo?.snapshot()?.let { s -> Couple.all(s).maxOfOrNull { it.at } } ?: 0L
    },
    /** Everything was handed over (the home redraws: no notes wait now, and the TV may have told of someone new). */
    private val onDone: () -> Unit = {},
) {
    private var client: KeyClient? = null
    private val main = Handler(Looper.getMainLooper())
    private var since = 0L
    private var connectedAt = 0L
    private var target = 0L

    /** Whether anything waits for the TV. */
    fun waiting(): Boolean {
        val store = RemoteStore(context)
        return store.pendingNotes().isNotEmpty() || newest() > store.syncedAt
    }

    fun start() {
        if (client != null) return
        val store = RemoteStore(context)
        if (!waiting() || store.lastAddress == null) return       // nothing to hand over, or no TV was ever found from here
        if (Build.VERSION.SDK_INT >= 37 && context.checkSelfPermission(LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED) return
        target = newest()
        val c = newClient()
        client = c
        since = SystemClock.uptimeMillis(); connectedAt = 0L
        c.start()
        main.postDelayed(check, LOOK_MS)
        Flight.log("sync: notes or changes wait for the TV; connecting for a moment")
    }

    fun stop() {
        main.removeCallbacks(check)
        client?.stop()
        client = null
    }

    private val check = object : Runnable {
        override fun run() {
            val c = client ?: return
            val now = SystemClock.uptimeMillis()
            val st = c.state.value
            if (st is KeyClient.State.Connected) {
                if (connectedAt == 0L) connectedAt = now
                if (c.notesWaiting.value == 0 && now - connectedAt >= SETTLE_MS) {
                    RemoteStore(context).syncedAt = target
                    Flight.log("sync: handed over to the TV")
                    stop(); onDone(); return
                }
            }
            val giveUp = st is KeyClient.State.NeedCode || st is KeyClient.State.Wait || st is KeyClient.State.NotFound || now - since > LIMIT_MS
            if (giveUp) { Flight.log("sync: the TV was not reached; it waits for the next time"); stop(); return }
            main.postDelayed(this, LOOK_MS)
        }
    }

    companion object {
        const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
        /** Connected this long (the couple goes at once, the notes are said to be kept), it lets go. */
        const val SETTLE_MS = 1_500L
        const val LIMIT_MS = 20_000L
        private const val LOOK_MS = 400L
    }
}
