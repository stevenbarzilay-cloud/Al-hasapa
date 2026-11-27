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
    /** A connection to the home's own TVs, as one of the home's phones (2.0): never to any other TV. */
    private val newClient: () -> KeyClient = { KeyClient(context, target = KeyClient.Target(CouchStore(context).homeTvs, KeyLink.AS_HOME)) },
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

    /** Whether anything waits for the TV (or the TV has something for the phone: the solo relay, 1.11). */
    fun waiting(): Boolean {
        val store = RemoteStore(context)
        val home = (context.applicationContext as? app.couch.alhasapa.CouchApp)?.repo?.peek { app.couch.alhasapa.data.Couches.of(it)?.id }
        return store.pendingNotes(home, home).isNotEmpty() || newest() > store.syncedAt || needsRelay() || shareDue(store)
    }

    /** The evenings the TV kept since the phone last heard (1.12): asked for each time the app comes to the front, at most this often. */
    private fun shareDue(store: RemoteStore): Boolean = System.currentTimeMillis() - store.sharedAt > SHARE_EVERY_MS

    private fun needsRelay(): Boolean {
        val r = RelayStore(context)
        return r.config == null && System.currentTimeMillis() - r.askedAt > RELAY_ASK_MS
    }

    fun start() {
        if (client != null) return
        val couches = CouchStore(context)
        // Nothing to hand over; or the home has no TV yet; or the phone is at a friend's this evening, or holds a connection of its
        // own already (2.0): the home's TV is looked for when the phone is at home again.
        if (!waiting() || couches.homeTvs.isEmpty() || couches.visitNow() != null || Links.open.value) return
        if (Build.VERSION.SDK_INT >= 37 && context.checkSelfPermission(LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED) return
        target = newest()
        if (needsRelay()) RelayStore(context).askedAt = System.currentTimeMillis()
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
                // (Only the notes written for the home wait for this TV; one for a friend's couch waits for that couch's.)
                val home = (context.applicationContext as? app.couch.alhasapa.CouchApp)?.repo?.peek { app.couch.alhasapa.data.Couches.of(it)?.id }
                if (RemoteStore(context).pendingNotes(home, home).isEmpty() && !c.awaitingAll && now - connectedAt >= SETTLE_MS) {
                    RemoteStore(context).syncedAt = target
                    RemoteStore(context).sharedAt = System.currentTimeMillis()
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
        /** A phone without the solo relay asks the TV for it at most this often. */
        const val RELAY_ASK_MS = 24 * 60 * 60 * 1000L
        /** The phone asks the TV for what it kept at most this often (each time the app comes to the front, otherwise). */
        const val SHARE_EVERY_MS = 2 * 60 * 1000L
        private const val LOOK_MS = 400L
    }
}
