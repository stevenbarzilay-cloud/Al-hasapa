package app.couch.alhasapa.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpatialTest {
    private fun t(id: String, l: Float, top: Float, r: Float, b: Float) = NavTarget(id, Box(l, top, r, b))

    // A top bar with three buttons, a page in the middle, two panel buttons on the left, a bottom bar with two.
    private val menu = t("menu", 800f, 0f, 860f, 60f)
    private val back = t("back", 700f, 0f, 780f, 60f)
    private val title = t("zoom", 100f, 0f, 400f, 60f)
    private val page = t("page", 250f, 70f, 650f, 470f)
    private val p1 = t("panel1", 0f, 100f, 200f, 160f)
    private val p2 = t("panel2", 0f, 180f, 200f, 240f)
    private val b1 = t("b1", 300f, 480f, 400f, 540f)
    private val b2 = t("b2", 450f, 480f, 560f, 540f)
    private val all = listOf(menu, back, title, page, p1, p2, b1, b2)

    private fun go(from: NavTarget, dir: Dir) = Spatial.next(from.box, all, dir, exclude = from.id)?.id

    @Test
    fun sideBySideButtonsAreReachedWithLeftAndRight() {
        assertEquals("back", go(menu, Dir.LEFT)); assertEquals("menu", go(back, Dir.RIGHT))
        assertEquals("b2", go(b1, Dir.RIGHT)); assertEquals("b1", go(b2, Dir.LEFT))
    }

    @Test
    fun aColumnIsWalkedWithUpAndDown() {
        assertEquals("panel2", go(p1, Dir.DOWN)); assertEquals("panel1", go(p2, Dir.UP))
    }

    @Test
    fun theBarsLeadIntoThePageAndBackOut() {
        assertEquals("page", go(back, Dir.DOWN)); assertEquals("page", go(b1, Dir.UP))
        assertEquals("page", go(p1, Dir.RIGHT))
    }

    @Test
    fun theColumnBeatsThePageWhenGoingDownInsideAPanel() {
        // From the first panel button, Down is the second panel button, even though the page is "below" its centre too.
        assertEquals("panel2", go(p1, Dir.DOWN))
    }

    @Test
    fun theEdgeOfTheColumnFallsBackToWhateverIsThatWay() {
        // Nothing below panel2 lines up with it; the bottom bar is the next thing down.
        assertEquals("b1", go(p2, Dir.DOWN))
    }

    @Test
    fun fromThePageTheNeighbourThatIsMostInLineWins() {
        val fromPage = Spatial.next(page.box, listOf(menu, back, title, p1, b1, b2), Dir.UP)
        assertEquals("the bar button most in line with the page's middle", "zoom", fromPage?.id)
        assertEquals("the bottom button most in line with the page", "b2", Spatial.next(page.box, listOf(p1, b1, b2), Dir.DOWN)?.id)
        assertEquals("panel2", Spatial.next(page.box, listOf(p1, p2), Dir.LEFT)?.id)
    }

    @Test
    fun nothingThatWayMeansNull() {
        assertNull(go(menu, Dir.RIGHT)); assertNull(go(menu, Dir.UP)); assertNull(go(b2, Dir.DOWN))
    }

    @Test
    fun theTargetItselfIsNeverTheAnswer() {
        assertNull(Spatial.next(Box(0f, 0f, 10f, 10f), listOf(NavTarget("x", Box(0f, 0f, 10f, 10f))), Dir.RIGHT, exclude = "x"))
    }
}
