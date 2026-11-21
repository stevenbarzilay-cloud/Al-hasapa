package app.couch.alhasapa.display

import android.content.Context
import android.view.View
import android.view.View.MeasureSpec
import android.widget.FrameLayout
import kotlin.math.roundToInt

/**
 * A fixed-size window onto one child (the WebView) that is laid out at a "virtual" size and shrunk to fit, the way a phone-sized
 * screen is shown on a smaller area. This is how portrait-fit and wide work.
 *
 * Why not ask the page for a different viewport (a meta tag)? That was the first version, and on the real WebView it was
 * ignored: the page laid out at the narrow box's width and the game was cut off. Here nothing is negotiated with the page: the
 * WebView really is 430 dp wide (or 1280 dp), so the page really lays out at 430 (or 1280) CSS px and `100vh` really is the
 * virtual height, exactly as on a phone or a desktop window. The view framework then shrinks the finished picture and maps
 * touches back through the same scale.
 *
 * [virtualWidthDp] null means no shrinking: the child fills the frame. The virtual height follows from the frame's aspect.
 */
class ScaledFrame(context: Context) : FrameLayout(context) {
    var virtualWidthDp: Int? = null
        set(value) {
            if (field != value) { field = value; requestLayout() }
        }

    /** Zoom of the finished picture beyond the fit (1 = none). Above 1 the edges are cropped and [focusX]/[focusY] pick the part kept. */
    var magnify: Float = 1f
        set(value) {
            val v = value.coerceAtLeast(1f)
            if (field != v) { field = v; requestLayout() }
        }

    /** Where in the page (0..1 across, 0..1 down) the magnified view is centred. */
    fun setFocus(x: Float, y: Float) {
        val fx = x.coerceIn(0f, 1f)
        val fy = y.coerceIn(0f, 1f)
        if (fx != focusX || fy != focusY) { focusX = fx; focusY = fy; requestLayout() }
    }

    private var focusX = 0.5f
    private var focusY = 0.5f

    /** The scale used by the last measure (1 when not shrinking), before [magnify]. */
    var scale: Float = 1f
        private set

    /** The size the child was laid out at, in px (what the page believes the screen is, times the density). */
    val virtualSizePx: Pair<Int, Int> get() = (getChildAt(0)?.measuredWidth ?: 0) to (getChildAt(0)?.measuredHeight ?: 0)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val child = getChildAt(0) ?: return
        val vw = virtualWidthDp
        if (vw == null || w <= 0 || h <= 0) {
            scale = 1f
            child.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
            return
        }
        val vwPx = (vw * resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
        val s = w.toFloat() / vwPx
        val vhPx = (h / s).roundToInt().coerceAtLeast(1)
        scale = s
        child.measure(MeasureSpec.makeMeasureSpec(vwPx, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(vhPx, MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val child = getChildAt(0) ?: return
        child.layout(0, 0, child.measuredWidth, child.measuredHeight)
        applyScale(child)
    }

    private fun applyScale(child: View) {
        val s = scale * magnify
        val contentW = child.measuredWidth * s
        val contentH = child.measuredHeight * s
        // Magnified: keep the focus point at the middle of the box, but never slide past the page's own edges.
        val tx = if (magnify <= 1f || contentW <= width) 0f else (width / 2f - focusX * contentW).coerceIn(width - contentW, 0f)
        val ty = if (magnify <= 1f || contentH <= height) 0f else (height / 2f - focusY * contentH).coerceIn(height - contentH, 0f)
        child.pivotX = 0f
        child.pivotY = 0f
        child.scaleX = s
        child.scaleY = s
        child.translationX = tx
        child.translationY = ty
    }
}
