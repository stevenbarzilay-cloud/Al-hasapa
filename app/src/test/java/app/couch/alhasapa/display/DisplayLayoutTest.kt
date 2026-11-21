package app.couch.alhasapa.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Pixel 8 held sideways: 914 x 411 dp. With the 2.5% TV margin the usable area is about 868 x 390 dp. */
class DisplayLayoutTest {
    private val w = 868f
    private val h = 390f

    @Test
    fun nativeLeavesThePageAlone() {
        val l = DisplayLayout.compute(DisplayProfile(), w, h)
        assertEquals(w, l.webWidth, 0.01f); assertEquals(h, l.webHeight, 0.01f)
        assertNull(l.virtualWidth); assertNull(l.scale); assertFalse(l.hasPanels)
    }

    @Test
    fun portraitFitGivesTheHaaretzChainAFullPhoneViewportAndPanelsAtItsSides() {
        // The bug in the screenshots: in landscape the chain's board fell off the screen because the page was only ~327 CSS px tall.
        // Portrait-fit tells it the screen is 430 x 800 instead, scaled down to the screen's height.
        val l = DisplayLayout.compute(DisplayProfile(mode = DisplayMode.PORTRAIT_FIT), w, h)
        assertEquals("fills the height", h, l.webHeight, 0.01f)
        assertEquals("keeps the phone's aspect", 430f / 800f, l.webWidth / l.webHeight, 0.001f)
        assertEquals(430, l.virtualWidth)
        assertEquals("the page sees a 430 x 800 screen", 800f, l.virtualHeight!!, 0.5f)
        assertEquals(l.webWidth / 430f, l.scale!!, 0.0001f)
        assertTrue("the chain needs ~775 CSS px of height, it now has 800", l.virtualHeight!! >= 775f)
        assertTrue("and room is left for two panels", l.hasPanels)
        assertEquals((w - l.webWidth) / 2f, l.panelWidth, 0.01f)
    }

    @Test
    fun panelsDisappearWhenTheyWouldBeTooNarrowToReadFromTheCouch() {
        val narrow = DisplayLayout.compute(DisplayProfile(mode = DisplayMode.PORTRAIT_FIT), 400f, 390f)
        assertFalse(narrow.hasPanels)
        assertTrue(narrow.webWidth <= 400f)
    }

    @Test
    fun theBoxNeverOverflowsTheScreen() {
        for ((aw, ah) in listOf(868f to 390f, 400f to 800f, 300f to 900f, 1000f to 200f)) {
            val l = DisplayLayout.compute(DisplayProfile(mode = DisplayMode.PORTRAIT_FIT), aw, ah)
            assertTrue("$aw x $ah: ${l.webWidth} x ${l.webHeight}", l.webWidth <= aw + 0.01f && l.webHeight <= ah + 0.01f)
        }
    }

    @Test
    fun zoomShrinksTheVirtualViewportSoThingsGetBigger() {
        val base = DisplayLayout.compute(DisplayProfile(mode = DisplayMode.PORTRAIT_FIT), w, h)
        val z = DisplayLayout.compute(DisplayProfile(mode = DisplayMode.PORTRAIT_FIT, zoom = 125), w, h)
        assertEquals("the box stays the same size", base.webWidth, z.webWidth, 0.01f)
        assertEquals(344, z.virtualWidth)                       // 430 / 1.25
        assertTrue(z.scale!! > base.scale!!)
        assertEquals(base.scale!! * 1.25f, z.scale!!, 0.001f)
        assertTrue(z.virtualHeight!! < base.virtualHeight!!)
    }

    @Test
    fun wideTellsThePageItIsADesktopWindowAndFillsTheScreen() {
        val l = DisplayLayout.compute(DisplayProfile(mode = DisplayMode.WIDE, vw = 1280), w, h)
        assertEquals(w, l.webWidth, 0.01f); assertEquals(h, l.webHeight, 0.01f); assertFalse(l.hasPanels)
        assertEquals(1280, l.virtualWidth)
        assertEquals(w / 1280f, l.scale!!, 0.0001f)
        assertEquals("a desktop-shaped viewport: 1280 x ~575", h / (w / 1280f), l.virtualHeight!!, 0.5f)
    }

    @Test
    fun wideUsesADesktopWidthEvenIfTheProfileStillCarriesAPhoneWidth() {
        val l = DisplayLayout.compute(DisplayProfile(mode = DisplayMode.WIDE, vw = 430), w, h)
        assertEquals(1280, l.virtualWidth)
    }

    @Test
    fun theTvSafeMarginIsAPercentageOfEachSide() {
        val (dx, dy) = DisplayLayout.safeInsets(DisplayProfile(safeArea = 2.5), 914f, 411f)
        assertEquals(22.85f, dx, 0.01f); assertEquals(10.275f, dy, 0.01f)
        assertNotNull(dx)
    }

    @Test
    fun zoomUpToTheReflowLimitRelayoutsThePageAndNeverCropsIt() {
        val l = DisplayLayout.compute(DisplayProfile(mode = DisplayMode.PORTRAIT_FIT, zoom = 140), w, h)
        assertEquals("430 / 1.4", 307, l.virtualWidth); assertEquals(1f, l.magnify, 0f)
    }

    @Test
    fun zoomBeyondTheLimitMagnifiesThePictureInsteadOfSqueezingThePageFurther() {
        // A page cannot be asked to fit a screen narrower than the smallest phones, so the rest of the zoom is magnification.
        val l = DisplayLayout.compute(DisplayProfile(mode = DisplayMode.PORTRAIT_FIT, zoom = 210), w, h)
        assertEquals("the page is re-laid out only as far as 140%", 307, l.virtualWidth)
        assertEquals(1.5f, l.magnify, 0.0001f)
        val wide = DisplayLayout.compute(DisplayProfile(mode = DisplayMode.WIDE, zoom = 280), w, h)
        assertEquals(1280 * 100 / 140, wide.virtualWidth); assertEquals(2f, wide.magnify, 0.0001f)
        assertEquals("native has no zoom", 1f, DisplayLayout.compute(DisplayProfile(zoom = 250), w, h).magnify, 0f)
    }

    @Test
    fun panelsShrinkToTheEssentialsBeforeTheyDisappear() {
        // The box is as tall as the screen allows, so what is left of the width is what the panels get.
        val roomy = DisplayLayout.compute(DisplayProfile(mode = DisplayMode.PORTRAIT_FIT), 868f, 390f)
        assertTrue(roomy.hasPanels); assertFalse("329 dp each: the full panels", roomy.compactPanels)
        val tight = DisplayLayout.compute(DisplayProfile(mode = DisplayMode.PORTRAIT_FIT), 560f, 390f)
        assertTrue(tight.hasPanels); assertTrue("175 dp each: essentials only", tight.compactPanels)
        assertEquals(DisplayLayout.FULL_PANEL_DP > tight.panelWidth, true)
        val none = DisplayLayout.compute(DisplayProfile(mode = DisplayMode.PORTRAIT_FIT), 480f, 390f)
        assertFalse("under 150 dp each: no panels", none.hasPanels); assertFalse(none.compactPanels)
    }

    @Test
    fun panelsCanBeSwitchedOffAndTheGameIsNoSmallerForIt() {
        val on = DisplayLayout.compute(DisplayProfile(mode = DisplayMode.PORTRAIT_FIT), w, h)
        val off = DisplayLayout.compute(DisplayProfile(mode = DisplayMode.PORTRAIT_FIT, panels = false), w, h)
        assertTrue(on.hasPanels); assertFalse(off.hasPanels)
        assertEquals("the game's size depends on the screen's height, not on the panels", on.webWidth, off.webWidth, 0.01f)
    }
}
