package app.couch.alhasapa.diag

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Plain Android view (no Compose, no WebView) laid over everything, so it can still be read when either of those
 * fails. Shown during startup of a debug build; it disappears a few seconds after the hub is confirmed visible and
 * stays up if anything went wrong.
 */
class DiagOverlay private constructor(private val view: TextView) {
    private val main = Handler(Looper.getMainLooper())
    private var hideAt: Runnable? = null

    private fun refresh() {
        view.text = Diag.snapshot()
        view.post { Diag.overlayBottomPx = if (view.visibility == View.VISIBLE) view.bottom else 0 }
        if (Diag.hubVisible && !Diag.problem && hideAt == null) {
            hideAt = Runnable { view.visibility = View.GONE; Diag.overlayBottomPx = 0 }.also { main.postDelayed(it, HIDE_AFTER_MS) }
        }
    }

    companion object {
        private const val HIDE_AFTER_MS = 6_000L

        fun install(activity: Activity): DiagOverlay {
            val tv = TextView(activity).apply {
                setTextColor(Color.parseColor("#F2EBDD"))
                setBackgroundColor(Color.parseColor("#E630232D"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f)
                typeface = Typeface.MONOSPACE
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                textDirection = View.TEXT_DIRECTION_LTR
                setPadding(16, 8, 16, 8)
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            ViewCompat.setOnApplyWindowInsetsListener(tv) { v, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                v.setPadding(16, bars.top + 8, 16, 8)
                insets
            }
            activity.addContentView(tv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
            val overlay = DiagOverlay(tv)
            Diag.onChange = { overlay.refresh() }
            overlay.refresh()
            return overlay
        }

        /**
         * Asks the system for the actual composited pixels of the window (WebView included) and returns the share of
         * them that differ from the cream background, ignoring the top [skipTopPx] (where the overlay itself sits).
         * Null if the capture is not possible.
         */
        suspend fun nonCreamShare(activity: Activity, skipTopPx: Int): Double? {
            val decor = activity.window?.decorView ?: return null
            if (decor.width <= 0 || decor.height <= 0) return null
            val scale = 6
            val bmp = Bitmap.createBitmap(decor.width / scale, decor.height / scale, Bitmap.Config.ARGB_8888)
            val ok = try {
                suspendCancellableCoroutine<Boolean> { cont ->
                    PixelCopy.request(activity.window, bmp, { result -> if (cont.isActive) cont.resume(result == PixelCopy.SUCCESS) }, Handler(Looper.getMainLooper()))
                }
            } catch (e: Throwable) {
                Diag.log("pixel capture failed: ${e.javaClass.simpleName}")
                false
            }
            if (!ok) return null
            val w = bmp.width
            val h = bmp.height
            val from = (skipTopPx / scale).coerceIn(0, h - 1)
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            var other = 0
            var total = 0
            for (y in from until h) for (x in 0 until w) {
                val c = px[y * w + x]
                val dr = kotlin.math.abs(Color.red(c) - 0xF2)
                val dg = kotlin.math.abs(Color.green(c) - 0xEB)
                val db = kotlin.math.abs(Color.blue(c) - 0xDD)
                if (dr > 10 || dg > 10 || db > 10) other++
                total++
            }
            return if (total == 0) null else other.toDouble() / total
        }
    }
}
