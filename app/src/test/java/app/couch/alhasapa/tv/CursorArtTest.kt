package app.couch.alhasapa.tv

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.couch.alhasapa.player.Clock
import app.couch.alhasapa.ui.theme.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The cursors: each points with a red tip at its top left, and lies below and to the right of it, away from what it points at. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37])
class CursorArtTest {
    private val size = 120
    private val sizeF = 120f

    private fun paint(shape: CursorShape, accent: Color = Color(0xFF44A3AB)): (Int, Int) -> Color {
        val bitmap = ImageBitmap(size, size)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(sizeF, sizeF)) {
            with(CursorArt) { cursor(shape, Offset.Zero, sizeF, accent, pressed = false) }
        }
        val map = bitmap.toPixelMap()
        return { x, y -> map[x, y] }
    }

    @Test
    fun everyCursorPointsWithARedTipAndLiesAwayFromIt() {
        val tip = (size * CursorArt.TIP).toInt()
        for (shape in CursorShape.entries) {
            val px = paint(shape)
            assertEquals("$shape: the tip is red", Palette.Red, px(tip, tip))
            // Its body: something is drawn in the lower right half, and nothing up and to the left of the tip's dot.
            var body = 0; var behind = 0
            for (y in 0 until size) for (x in 0 until size) {
                val drawn = px(x, y).alpha > 0.5f
                if (drawn && x + y > size) body++
                if (drawn && x + y < tip - 8) behind++
            }
            assertTrue("$shape has a body down and to the right: $body", body > 200)
            assertEquals("$shape: nothing above and left of where it points", 0, behind)
        }
    }

    @Test
    fun thePlainArrowWearsTheInterfacesColour() {
        val teal = Color(0xFF44A3AB)
        assertEquals(teal, paint(CursorShape.ARROW, teal)(26, 50))
        val pink = Color(0xFFE8A5B5)
        assertEquals(pink, paint(CursorShape.ARROW, pink)(26, 50))
    }

    @Test
    fun theChoicesAreKeptByNameAndUnknownOnesFallBack() {
        for (s in CursorShape.entries) assertEquals(s, CursorShape.of(s.id))
        for (s in CursorSize.entries) assertEquals(s, CursorSize.of(s.id))
        assertEquals(CursorShape.PAW, CursorShape.of("dragon")); assertEquals(CursorSize.MEDIUM, CursorSize.of(null))
        assertTrue("the paw is smaller than it was (about 40 dp)", CursorSize.MEDIUM.dp < 36f)
    }

    @Test
    fun aSettingStepsInWholeHundredthsAndStaysInItsRange() {
        assertEquals(0.99f, step(1f, -0.01f, MIN_PICTURE, 1f), 0f)
        assertEquals(1f, step(1f, +0.01f, MIN_PICTURE, 1f), 0f)
        var v = 1f; repeat(40) { v = step(v, -0.01f, MIN_PICTURE, 1f) }
        assertEquals(MIN_PICTURE, v, 0f)
        assertEquals(1.25f, step(1.2f, +0.05f, MIN_UI, MAX_UI), 0f)
        assertEquals(MAX_UI, step(1.25f, +0.05f, MIN_UI, MAX_UI), 0f)
    }

    @Test
    fun theClockReadsTheTimeOfDayAndWakesWhenTheMinuteChanges() {
        assertTrue(Regex("^[0-2][0-9]:[0-5][0-9]$").matches(Clock.now()))
        assertEquals(60_020L, Clock.untilNextMinute(120_000L))
        assertEquals(21L, Clock.untilNextMinute(179_999L))
        assertEquals(30_020L, Clock.untilNextMinute(90_000L))
    }
}
