package app.couch.alhasapa.diag

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import androidx.core.content.ContextCompat
import app.couch.alhasapa.remote.RemoteKeys

/**
 * The TV box going to standby and waking up. Since the TV app was installed (November 2026) the box sometimes woke to a black screen
 * and had to be unplugged. The cause is not visible from inside the app (Android kept no record of the app ending: it was idle in the
 * background when the power was cut), but the app no longer takes part either way:
 *
 * - when the screen goes off, it closes the game (and any sign-in page), so no page is running while the box sleeps, puts itself
 *   behind the box's home screen, so the box wakes to its own home screen and not to a page of ours, and stops listening for the
 *   phone at once;
 * - every screen off, screen on, screensaver and shutdown is written down, so a report after a bad wake shows what the app saw.
 */
class Standby(
    private val screens: () -> List<Activity>,
    /** The hub: kept (it is where the app opens again), but sent behind the home screen. */
    private val isHub: (Activity) -> Boolean,
    private val stopKeys: () -> Unit = { RemoteKeys.stopNow() },
    private val back: (Activity) -> Unit = { it.moveTaskToBack(true) },
) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SCREEN_OFF -> {
                Flight.log("screen off (standby): the app closes its game and steps back")
                stepAside()
                Flight.flushNow()
            }
            Intent.ACTION_SCREEN_ON -> { Flight.log("screen on (the box woke up)"); Flight.flushNow() }
            Intent.ACTION_DREAMING_STARTED -> Flight.log("screensaver on")
            Intent.ACTION_DREAMING_STOPPED -> Flight.log("screensaver off")
            Intent.ACTION_SHUTDOWN -> { Flight.log("the box is shutting down"); Flight.flushNow() }
        }
    }

    /** Closes every screen but the hub, sends the hub behind the home screen, stops the phone link. */
    fun stepAside() {
        val list = screens()
        for (a in list) if (!isHub(a)) runCatching { a.finish() }
        list.firstOrNull(isHub)?.let { runCatching { back(it) } }
        runCatching { stopKeys() }
    }

    companion object {
        fun install(app: Application, screens: () -> List<Activity>, isHub: (Activity) -> Boolean): Standby {
            val receiver = Standby(screens, isHub)
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_DREAMING_STARTED); addAction(Intent.ACTION_DREAMING_STOPPED)
                addAction(Intent.ACTION_SHUTDOWN)
            }
            // System broadcasts only; not exported, so no other app can send them here.
            ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            runCatching { watchDisplay(app) }
            return receiver
        }

        /** Every change of the screen's state (on, off, dozing) or of the mode the box sends to the TV, written down once. */
        private fun watchDisplay(app: Application) {
            val dm = app.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            fun now(): String? = dm.getDisplay(Display.DEFAULT_DISPLAY)?.let { describe(it.mode.physicalWidth, it.mode.physicalHeight, it.mode.refreshRate, it.state) }
            var last = now()
            dm.registerDisplayListener(object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) {}
                override fun onDisplayRemoved(displayId: Int) {}
                override fun onDisplayChanged(displayId: Int) {
                    if (displayId != Display.DEFAULT_DISPLAY) return
                    val d = now() ?: return
                    if (d != last) { last = d; Flight.log("display: $d") }
                }
            }, Handler(Looper.getMainLooper()))
        }

        fun describe(w: Int, h: Int, hz: Float, state: Int): String {
            val s = when (state) {
                Display.STATE_ON -> "on"; Display.STATE_OFF -> "off"; Display.STATE_DOZE -> "dozing"
                Display.STATE_DOZE_SUSPEND -> "dozing (suspended)"; Display.STATE_ON_SUSPEND -> "on (suspended)"
                else -> "state $state"
            }
            return "${w}x$h at ${Math.round(hz)} Hz, $s"
        }
    }
}
