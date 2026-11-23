package app.couch.alhasapa.remote

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import app.couch.alhasapa.diag.Flight

/**
 * While a phone is the TV's keyboard and touchpad, the TV's Wi-Fi is asked not to doze between packets.
 *
 * A device that is only listening lets its Wi-Fi radio sleep and collects what was sent to it a few times a second; the home's router
 * holds the packets back until then. For a film that is nothing. For a touchpad it is the paw moving in jumps, and typed letters
 * arriving in bunches: each move waits up to a tenth of a second or more for the TV to wake up and fetch it. The TV's home screen is
 * where it shows most (nothing is being downloaded there, so the radio sleeps the deepest), and that is where the paw felt slow in
 * 1.9.3.
 *
 * Android has a switch for exactly this, a "low latency" Wi-Fi lock: it holds only while the app is in front and the screen is on,
 * which is the only time a phone can be connected here. A box on a cable is not affected either way. A box that refuses is only
 * written down.
 */
object WifiLatency {
    private var lock: WifiManager.WifiLock? = null
    private var held = false

    @Synchronized
    fun hold(context: Context, on: Boolean) {
        if (on == held) return
        held = on
        runCatching {
            if (on) {
                val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
                val l = lock ?: wifi.createWifiLock(mode(), "couch:phone-keyboard").apply { setReferenceCounted(false) }.also { lock = it }
                l.acquire()
                Flight.log("wifi: asked to stay awake while a phone is connected (so the paw and the keys arrive at once)")
            } else {
                lock?.takeIf { it.isHeld }?.release()
            }
        }.onFailure { Flight.log("wifi: the low-delay lock was refused: $it") }
    }

    /** The low-latency lock from Android 10; before that, the lock that keeps the radio at full power. */
    @Suppress("DEPRECATION") // WIFI_MODE_FULL_HIGH_PERF is the only one of the two that Android 8 and 9 (older TV boxes) have
    @android.annotation.SuppressLint("ObsoleteSdkInt") // the TV build runs from Android 8; only the phone build starts at 10
    private fun mode(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
}
