package app.couch.alhasapa.diag

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * A tiny startup log that does not depend on Compose or on the WebView: every step is kept here, written to
 * Logcat (tag AlHasapaDiag) and mirrored on screen by [DiagOverlay]. When something is blank, this says why.
 */
object Diag {
    private const val TAG = "AlHasapaDiag"
    private const val MAX_LINES = 60
    private val t0 = SystemClock.elapsedRealtime()
    private val lines = ArrayDeque<String>()
    private val main = Handler(Looper.getMainLooper())

    /** Set by the overlay; called on the main thread whenever a line is added or a flag changes. */
    @Volatile var onChange: (() -> Unit)? = null

    /** Where the on-screen log ends (px from the top), so the blank check can ignore it. */
    @Volatile var overlayBottomPx: Int = 0

    /** True while the in-app splash is on screen (so a "blank" check does not mistake the splash for the hub). */
    @Volatile var splashShowing: Boolean = false

    /** True once the hub has been seen on screen (page ready and the window is not blank). The overlay then fades away. */
    @Volatile var hubVisible: Boolean = false
        private set

    /** True once something is wrong (blank, error). The overlay then stays up. */
    @Volatile var problem: Boolean = false
        private set

    fun log(message: String) {
        val line = "%5d %s".format(SystemClock.elapsedRealtime() - t0, message)
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
        Log.i(TAG, line)
        main.post { onChange?.invoke() }
    }

    fun markHubVisible() { hubVisible = true; log("OK: hub is visible"); }

    fun markProblem(why: String) { problem = true; log("PROBLEM: $why") }

    fun snapshot(lastN: Int = 18): String = synchronized(lines) { lines.toList().takeLast(lastN).joinToString("\n") }
}
