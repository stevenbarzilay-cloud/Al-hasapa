package app.couch.alhasapa.player

import app.couch.alhasapa.display.UiSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerBarsTest {
    private val all = BarItem.entries

    @Test
    fun aWideBarShowsEverything() {
        assertEquals(all, fitItems(all, 1700f, UiSize.MEDIUM))
    }

    @Test
    fun aNarrowerBarDropsTheLeastImportantFirstAndKeepsTheOrder() {
        val shown = fitItems(all, 900f, UiSize.MEDIUM)
        assertFalse("the pieces go first", BarItem.PIECES in shown)
        assertTrue(BarItem.MENU in shown && BarItem.PLAYED in shown && BarItem.RESULT in shown && BarItem.BACK in shown)
        assertEquals("items keep their order on the bar", shown, all.filter { it in shown })
    }

    @Test
    fun theMenuIsNeverDroppedHoweverNarrow() {
        assertEquals(listOf(BarItem.MENU), fitItems(all, 10f, UiSize.LARGE))
        assertTrue(BarItem.MENU in fitItems(all, 0f, UiSize.SMALL))
    }

    @Test
    fun largerBarsNeedMoreRoomPerItem() {
        assertTrue(fitItems(all, 800f, UiSize.SMALL).size >= fitItems(all, 800f, UiSize.MEDIUM).size)
        assertTrue(fitItems(all, 800f, UiSize.MEDIUM).size >= fitItems(all, 800f, UiSize.LARGE).size)
    }

    @Test
    fun barTextIsNeverUnderTwentySp() {
        for (s in UiSize.entries) assertTrue(barTextSp(s) >= 20)
    }

    @Test
    fun theSplitBarsEachKeepTheirOwnItems() {
        val top = listOf(BarItem.MENU, BarItem.BACK, BarItem.TITLE, BarItem.CLOCK, BarItem.ZOOM)
        assertEquals(top, fitItems(top, 860f, UiSize.MEDIUM))
    }
}
