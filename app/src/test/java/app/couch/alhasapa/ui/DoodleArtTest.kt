package app.couch.alhasapa.ui

import androidx.compose.ui.graphics.Color
import app.couch.alhasapa.data.Ink
import app.couch.alhasapa.data.PiecesConfig
import app.couch.alhasapa.ui.theme.Palette
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The pens of a note's drawing (1.12): every colour has its paint, the same as the pieces' own. */
@RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37])
class DoodleArtTest {
    private val pieces = PiecesConfig.parse(File("src/main/assets/hub/pieces.json").readText())

    @Test
    fun everyPenHasItsColourAndTheNamedOnesAreThePiecesOwn() {
        val own = Color(0xFF123456)
        for (i in Ink.COLORS.indices) assertNotNull(DoodleArt.color(Ink.of(i, 1), own))
        assertEquals(own, DoodleArt.color(Ink.DEFAULT, own))
        assertEquals(Palette.Ink, DoodleArt.color(Ink.of(Ink.COLORS.indexOf("ink"), 0), own))
        assertEquals("the eraser paints in the paper's colour", DoodleArt.PAPER, DoodleArt.color(Ink.of(Ink.PAPER, 2), own))
        for (name in Ink.COLORS.filter { it !in setOf("own", "ink", "paper") }) {
            assertTrue("$name is one of the pieces' colours", name in pieces.colorIds)
            assertEquals(name, pieces.style("circle", name).base, DoodleArt.named(name))
        }
    }

    @Test
    fun aStrokeIsOneSmoothLineThroughItsPoints() {
        val xs = floatArrayOf(0f, 10f, 20f, 30f); val ys = floatArrayOf(0f, 10f, 0f, 10f)
        val p = DoodleArt.path(4, { xs[it] }, { ys[it] })
        val b = p.getBounds()
        assertEquals(0f, b.left, 0.01f); assertEquals(30f, b.right, 0.01f)
        assertTrue("it bends towards its points without overshooting them", b.top >= -0.01f && b.bottom <= 10.01f)
        assertTrue(DoodleArt.path(0, { 0f }, { 0f }).isEmpty)
    }
}
