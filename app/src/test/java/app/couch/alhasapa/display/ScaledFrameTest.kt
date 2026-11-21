package app.couch.alhasapa.display

import android.content.Context
import android.view.View
import android.view.View.MeasureSpec
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The frame's arithmetic: the child is laid out at the virtual size, then shrunk so it exactly fills the box. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [37], qualifiers = "mdpi")
class ScaledFrameTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun frame(w: Int, h: Int, virtualDp: Int?): Pair<ScaledFrame, View> {
        val child = View(context)
        val f = ScaledFrame(context).apply { addView(child); virtualWidthDp = virtualDp }
        f.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        f.layout(0, 0, w, h)
        return f to child
    }

    @Test
    fun portraitFitLaysThePageOutAtPhoneSizeAndShrinksItIntoTheBox() {
        // The Pixel 8 sideways with the TV margin: a 210 x 390 box for a 430-wide phone page.
        val (f, c) = frame(210, 390, 430)
        assertEquals(430, c.measuredWidth)
        assertEquals("the page's height follows the box's aspect: 390 / (210/430)", 799, c.measuredHeight)
        assertEquals(210f / 430f, c.scaleX, 0.0001f); assertEquals(c.scaleX, c.scaleY, 0f)
        assertEquals("shrunk from the top-left corner, so it lands exactly in the box", 0f, c.pivotX, 0f)
        assertEquals(210f, c.measuredWidth * c.scaleX, 0.5f); assertEquals(390f, c.measuredHeight * c.scaleY, 1f)
        assertEquals(210, f.measuredWidth); assertEquals(390, f.measuredHeight)
    }

    @Test
    fun wideLaysThePageOutAsADesktopWindow() {
        val (_, c) = frame(868, 390, 1280)
        assertEquals(1280, c.measuredWidth)
        assertEquals(575, c.measuredHeight)
        assertEquals(868f / 1280f, c.scaleX, 0.0001f)
    }

    @Test
    fun withoutAVirtualWidthTheChildJustFillsTheFrame() {
        val (_, c) = frame(868, 390, null)
        assertEquals(868, c.measuredWidth); assertEquals(390, c.measuredHeight); assertEquals(1f, c.scaleX, 0f)
    }

    @Test
    fun theVirtualWidthIsInDpSoADenserScreenGetsMorePixelsNotAWiderPage() {
        val child = View(context)
        val f = ScaledFrame(context).apply { addView(child); virtualWidthDp = 430 }
        context.resources.displayMetrics.density = 2.625f
        f.measure(MeasureSpec.makeMeasureSpec(551, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(1024, MeasureSpec.EXACTLY))
        assertEquals("430 dp is 1129 px here, and still 430 css px to the page", 1129, child.measuredWidth)
        assertEquals(551f / 1129f, f.scale, 0.0001f)
    }

    @Test
    fun anEmptyBoxDoesNotDivideByZero() {
        val (f, c) = frame(0, 0, 430)
        assertEquals(1f, f.scale, 0f); assertEquals(0, c.measuredWidth)
    }

    private fun magnified(w: Int, h: Int, mag: Float, fx: Float, fy: Float): Pair<ScaledFrame, View> {
        val child = View(context)
        val f = ScaledFrame(context).apply { addView(child); virtualWidthDp = 430; magnify = mag; setFocus(fx, fy) }
        f.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        f.layout(0, 0, w, h)
        return f to child
    }

    @Test
    fun magnifyingGrowsThePictureAndCentresTheFocus() {
        val (_, c) = magnified(210, 390, 2f, 0.5f, 0.5f)
        assertEquals("fit scale x magnify", 2f * 210f / 430f, c.scaleX, 0.0001f)
        // The page's centre lands on the box's centre.
        assertEquals(105f, 0.5f * c.measuredWidth * c.scaleX + c.translationX, 0.5f)
        assertEquals(195f, 0.5f * c.measuredHeight * c.scaleY + c.translationY, 1f)
    }

    @Test
    fun theViewNeverSlidesPastThePagesEdges() {
        val (_, c) = magnified(210, 390, 2f, 0f, 0f)
        assertEquals("focus at the top-left: the page's corner is the box's corner", 0f, c.translationX, 0f); assertEquals(0f, c.translationY, 0f)
        val (_, d) = magnified(210, 390, 2f, 1f, 1f)
        assertEquals(210f, d.measuredWidth * d.scaleX + d.translationX, 0.5f)
        assertEquals(390f, d.measuredHeight * d.scaleY + d.translationY, 1f)
    }

    @Test
    fun noMagnificationMeansNoTranslation() {
        val (_, c) = magnified(210, 390, 1f, 0.9f, 0.9f)
        assertEquals(0f, c.translationX, 0f); assertEquals(0f, c.translationY, 0f)
    }
}
