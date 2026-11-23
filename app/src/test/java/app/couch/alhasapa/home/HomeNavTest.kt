package app.couch.alhasapa.home

import app.couch.alhasapa.tv.Dir
import app.couch.alhasapa.tv.ScreenNav
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The remote on the TV's home moves by rows and places, right to left, and every row keeps its place. */
class HomeNavTest {
    private val nav = HomeNav().apply {
        rows = listOf(listOf("a1", "a2", "a3", "a4"), listOf("b1", "b2"), listOf("p1", "p2", "p3"))
    }

    @Test
    fun leftGoesOnAlongTheRowAndRightComesBackAndTheEndsStay() {
        assertEquals("a2", nav.move("a1", Dir.LEFT))
        assertEquals("a3", nav.move("a2", Dir.LEFT))
        assertEquals("a4", nav.move("a3", Dir.LEFT))
        assertEquals("the row's end: nowhere further", ScreenNav.STAY, nav.move("a4", Dir.LEFT))
        assertEquals("a3", nav.move("a4", Dir.RIGHT))
        assertEquals("the row's start: nothing before it", ScreenNav.STAY, nav.move("a1", Dir.RIGHT))
    }

    @Test
    fun upAndDownChangeRowsAndEachRowKeepsItsPlace() {
        assertEquals("a3", nav.move("a2", Dir.LEFT))
        assertEquals("the next row begins at its first", "b1", nav.move("a3", Dir.DOWN))
        assertEquals("b2", nav.move("b1", Dir.LEFT))
        assertEquals("back up: where the shelf was left", "a3", nav.move("b2", Dir.UP))
        assertEquals("and down again: where that row was left", "b2", nav.move("a3", Dir.DOWN))
        assertEquals("p1", nav.move("b2", Dir.DOWN))
        assertEquals(ScreenNav.STAY, nav.move("p1", Dir.DOWN))
        assertEquals(ScreenNav.STAY, nav.move("a1", Dir.UP))
    }

    @Test
    fun anEmptyRowIsSteppedOver() {
        nav.rows = listOf(listOf("a1"), emptyList(), listOf("p1"))
        assertEquals("p1", nav.move("a1", Dir.DOWN))
        assertEquals("a1", nav.move("p1", Dir.UP))
    }

    @Test
    fun aFocusThatIsNowhereComesHome() {
        assertEquals("the first button, the first time", "a1", nav.move(null, Dir.DOWN))
        nav.note("b2")
        assertEquals("then where it last was", "b2", nav.move("some-dock-button", Dir.RIGHT))
        nav.rows = listOf(listOf("a1"), emptyList(), listOf("p1"))          // b2 is gone
        assertEquals("a1", nav.home())
        assertNull(HomeNav().home())
    }

    @Test
    fun aRowThatWasReorderedIsWalkedInItsNewOrder() {
        nav.note("a2")
        nav.rows = listOf(listOf("a2", "a3", "a4", "a1"), listOf("b1", "b2"), listOf("p1"))     // a1 was played: it went last
        assertEquals("a3", nav.move("a2", Dir.LEFT))
        assertEquals(0 to 3, nav.position("a1"))
    }

    @Test
    fun okActsOnTheHomesOwnButtonsOnly() {
        val pressed = ArrayList<String>()
        nav.onOk = { pressed += it }
        assertTrue(nav.ok("b2")); assertFalse(nav.ok("panel-close")); assertFalse(nav.ok(null))
        assertEquals(listOf("b2"), pressed)
    }

    @Test
    fun leftToRightItIsMirrored() {
        val ltr = HomeNav(rtl = false).apply { rows = listOf(listOf("x1", "x2")) }
        assertEquals("x2", ltr.move("x1", Dir.RIGHT))
        assertEquals(ScreenNav.STAY, ltr.move("x1", Dir.LEFT))
    }
}
