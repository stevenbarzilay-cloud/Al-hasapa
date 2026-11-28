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
        val l = DisplayLayout.compute(DisplayProfile(ui = UiPlace.MINIMAL), w, h)
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

    /**
     * 2.0.2: a phone in touch mode held upright. Whatever the game's own shape on the TV (as it is, wide, phone-shaped), it is shown
     * shaped like that phone meanwhile, so its picture fills the phone's screen; a phone width of its own is kept.
     */
    @Test
    fun anUprightPhoneMakesTheGameShapedLikeIt() {
        val tvW = 1872f; val tvH = 1026f                                        // a TV's 1920 x 1080 inside its margin
        for (p in listOf(DisplayProfile(), DisplayProfile(mode = DisplayMode.WIDE, vw = 1280), DisplayProfile(mode = DisplayMode.PORTRAIT_FIT))) {
            val shaped = DisplayLayout.phoneShaped(p, 0.48f)
            assertEquals(DisplayMode.PORTRAIT_FIT, shaped.mode)
            assertEquals(430, shaped.vw)
            val l = DisplayLayout.compute(shaped.copy(ui = UiPlace.MINIMAL), tvW, tvH)
            assertEquals("as tall as the TV", tvH, l.webHeight, 0.01f)
            assertEquals("the phone's shape (${p.mode})", 0.48f, l.webWidth / l.webHeight, 0.003f)
            assertEquals("a phone's width for the page", 430, l.virtualWidth)
        }
        // A narrower phone width the person chose is kept; what else they chose stays.
        val own = DisplayLayout.phoneShaped(DisplayProfile(mode = DisplayMode.PORTRAIT_FIT, vw = 390, vh = 844, zoom = 120, dark = true), 0.5f)
        assertEquals(390, own.vw); assertEquals(780, own.vh); assertEquals(120, own.zoom); assertTrue(own.dark)
        // Never wider than square, never a sliver.
        assertEquals(430, DisplayLayout.phoneShaped(DisplayProfile(), 3f).vh)
        assertEquals(1433, DisplayLayout.phoneShaped(DisplayProfile(), 0.01f).vh)
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
        val l = DisplayLayout.compute(DisplayProfile(mode = DisplayMode.WIDE, vw = 1280, ui = UiPlace.MINIMAL), w, h)
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
    fun theUiPlacementDefaultsByModeAndNeverLeavesTheScreenWithoutOne() {
        assertEquals(UiPlace.SIDES, DisplayProfile(mode = DisplayMode.PORTRAIT_FIT).place)
        assertEquals("wide has no room at the sides: a bar", UiPlace.TOP, DisplayProfile(mode = DisplayMode.WIDE).place)
        assertEquals(UiPlace.TOP, DisplayProfile().place)
        assertEquals("sides is only for a phone-shaped game", UiPlace.TOP, DisplayProfile(mode = DisplayMode.WIDE, ui = UiPlace.SIDES).place)
        assertEquals(UiSize.MEDIUM, DisplayProfile(mode = DisplayMode.PORTRAIT_FIT).size)
        assertEquals("bars cost the game height, so they start small", UiSize.SMALL, DisplayProfile(mode = DisplayMode.WIDE).size)
    }

    @Test
    fun barsTakeTheirHeightFromTheGameAndPanelsAndTheMinimalUiTakeNone() {
        val plain = DisplayLayout.compute(DisplayProfile(mode = DisplayMode.WIDE, ui = UiPlace.MINIMAL), w, h)
        assertEquals(h, plain.webHeight, 0.01f); assertEquals(0f, plain.topBar, 0f); assertEquals(UiPlace.MINIMAL, plain.place)
        val top = DisplayLayout.compute(DisplayProfile(mode = DisplayMode.WIDE), w, h)
        assertEquals(48f, top.topBar, 0f); assertEquals(h - 48f, top.webHeight, 0.01f); assertEquals(0f, top.bottomBar, 0f)
        val bottom = DisplayLayout.compute(DisplayProfile(mode = DisplayMode.WIDE, ui = UiPlace.BOTTOM, uiSize = UiSize.LARGE), w, h)
        assertEquals(76f, bottom.bottomBar, 0f); assertEquals(h - 76f, bottom.webHeight, 0.01f)
        val both = DisplayLayout.compute(DisplayProfile(mode = DisplayMode.WIDE, ui = UiPlace.BOTH, uiSize = UiSize.MEDIUM), w, h)
        assertEquals(h - 120f, both.webHeight, 0.01f)
        assertEquals("wide keeps its width-bound scale, so the page just sees a little less height", top.scale, plain.scale)
        assertTrue(top.virtualHeight!! < plain.virtualHeight!!)
    }

    @Test
    fun aPhoneShapedGameWithBarsIsShorterAndHasNoPanels() {
        val sides = DisplayLayout.compute(DisplayProfile(mode = DisplayMode.PORTRAIT_FIT), w, h)
        val bar = DisplayLayout.compute(DisplayProfile(mode = DisplayMode.PORTRAIT_FIT, ui = UiPlace.TOP), w, h)
        assertTrue(sides.hasPanels); assertFalse(bar.hasPanels)
        assertEquals(h - 48f, bar.webHeight, 0.01f)
        assertTrue("the bar costs the game some size", bar.webWidth < sides.webWidth)
        val minimal = DisplayLayout.compute(DisplayProfile(mode = DisplayMode.PORTRAIT_FIT, ui = UiPlace.MINIMAL), w, h)
        assertFalse(minimal.hasPanels); assertEquals("no size lost", sides.webWidth, minimal.webWidth, 0.01f)
    }

    @Test
    fun panelsThatDoNotFitBecomeATopBarInsteadOfNothing() {
        val tight = DisplayLayout.compute(DisplayProfile(mode = DisplayMode.PORTRAIT_FIT), 400f, 390f)
        assertFalse(tight.hasPanels)
        assertEquals(UiPlace.TOP, tight.place); assertEquals(48f, tight.topBar, 0f)
    }

    @Test
    fun smallUiMeansCompactPanels() {
        val l = DisplayLayout.compute(DisplayProfile(mode = DisplayMode.PORTRAIT_FIT, uiSize = UiSize.SMALL), w, h)
        assertTrue(l.hasPanels); assertTrue(l.compactPanels)
        assertFalse(DisplayLayout.compute(DisplayProfile(mode = DisplayMode.PORTRAIT_FIT, uiSize = UiSize.LARGE), w, h).compactPanels)
    }
}
