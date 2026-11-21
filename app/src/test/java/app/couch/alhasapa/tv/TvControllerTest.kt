package app.couch.alhasapa.tv

import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

private class FakePage : PageNav {
    val calls = ArrayList<String>()
    var onMove: PageResult = PageResult.Moved
    var onEnter: PageResult = PageResult.Moved
    var onActivate: PageResult = PageResult.Tap(50f, 60f, false, false)
    override fun enter(dir: Dir?, cb: (PageResult) -> Unit) { calls += "enter $dir"; cb(onEnter) }
    override fun move(dir: Dir, cb: (PageResult) -> Unit) { calls += "move $dir"; cb(onMove) }
    override fun activate(cb: (PageResult) -> Unit) { calls += "activate"; cb(onActivate) }
    override fun blur() { calls += "blur" }
    override fun tap(x: Float, y: Float) { calls += "tap ${x.toInt()},${y.toInt()}" }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [37])
class TvControllerTest {
    private fun key(code: Int, action: Int = KeyEvent.ACTION_DOWN, repeat: Int = 0) = KeyEvent(0, 0, action, code, repeat)
    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(ms))
    private fun press(c: TvController, code: Int) { c.handleKey(key(code)); c.handleKey(key(code, KeyEvent.ACTION_UP)) }

    // A screen like the player's: a bar above, a bar below, the page between them.
    private val bar = Box(0f, 0f, 1000f, 60f)
    private fun screen(page: FakePage = FakePage(), enabled: Boolean = true): Pair<TvController, FakePage> {
        val c = TvController(enabled, 1f)
        c.resize(1000f, 600f); c.pageNav = page; c.pageBox = Box(100f, 70f, 900f, 530f)
        c.register("menu", Box(900f, 0f, 960f, 60f)) { page.calls += "menu pressed" }
        c.register("back", Box(800f, 0f, 880f, 60f)) { page.calls += "back pressed" }
        c.register("played", Box(400f, 540f, 520f, 600f)) { page.calls += "played pressed" }
        return c to page
    }

    @Test
    fun onAPhoneEveryKeyPassesThrough() {
        val (c, page) = screen(enabled = false)
        for (code in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
            assertFalse(code.toString(), c.handleKey(key(code)))
        c.openKeyboard(); assertFalse("no keyboard either", c.keyboardOpen); assertTrue(page.calls.isEmpty())
    }

    // ---- navigating (the default) ----

    @Test
    fun theArrowsWalkTheFocusInsideThePageFirst() {
        val (c, page) = screen()
        assertEquals(InputMode.NAV, c.mode); assertEquals(Zone.PAGE, c.zone)
        assertTrue(c.handleKey(key(KeyEvent.KEYCODE_DPAD_DOWN))); assertTrue(c.handleKey(key(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.ACTION_UP)))
        assertEquals(listOf("move DOWN"), page.calls); assertEquals(Zone.PAGE, c.zone)
    }

    @Test
    fun scrollingKeepsTheFocusInThePage() {
        val (c, page) = screen(); page.onMove = PageResult.Scrolled
        press(c, KeyEvent.KEYCODE_DPAD_DOWN); press(c, KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(Zone.PAGE, c.zone); assertEquals(listOf("move DOWN", "move DOWN"), page.calls)
    }

    @Test
    fun atTheEdgeOfThePageTheFocusLeavesForTheNearestButtonThatWay() {
        val (c, page) = screen(); page.onMove = PageResult.Edge
        press(c, KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(Zone.NATIVE, c.zone); assertEquals("played", c.focusedId); assertTrue(page.calls.contains("blur"))
        press(c, KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue("OK presses the focused button", page.calls.contains("played pressed"))
    }

    @Test
    fun atTheEdgeWithNothingThatWayTheFocusStays() {
        val (c, page) = screen(); page.onMove = PageResult.Edge
        c.unregister("menu"); c.unregister("back"); c.unregister("played")          // a screen with no buttons at all
        press(c, KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(Zone.PAGE, c.zone); assertNull(c.focusedId)
    }

    @Test
    fun fromAButtonTheArrowsReachItsNeighboursAndThePage() {
        val (c, page) = screen(); page.onMove = PageResult.Edge
        press(c, KeyEvent.KEYCODE_DPAD_UP)
        assertEquals("the bar button most in line with the page", "back", c.focusedId.also { })
        press(c, KeyEvent.KEYCODE_DPAD_RIGHT); assertEquals("menu", c.focusedId)
        press(c, KeyEvent.KEYCODE_DPAD_LEFT); assertEquals("back", c.focusedId)
        page.calls.clear()
        press(c, KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(Zone.PAGE, c.zone); assertNull(c.focusedId)
        assertEquals("into the page from the top", listOf("enter DOWN"), page.calls)
    }

    @Test
    fun okInThePageTapsTheFocusedElementAndOpensTheKeyboardForAField() {
        val (c, page) = screen()
        press(c, KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf("activate", "tap 50,60"), page.calls); assertFalse(c.keyboardOpen)
        page.calls.clear(); page.onActivate = PageResult.Tap(1f, 2f, editable = true, password = true)
        press(c, KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue(c.keyboardOpen); assertTrue("a password field masks the preview", c.password)
    }

    @Test
    fun aPageWithNothingToPressSaysSoAndLetsTheFocusLeave() {
        val (c, page) = screen(); page.onMove = PageResult.None
        press(c, KeyEvent.KEYCODE_DPAD_UP)
        assertEquals(TvHint.NO_TARGETS, c.hint); assertEquals("it still reaches the bar so the app is never a dead end", Zone.NATIVE, c.zone)
    }

    @Test
    fun nothingFocusedYetStartsOnTheFirstButtonWhenThereIsNoPage() {
        val c = TvController(true, 1f); c.resize(1000f, 600f)
        c.register("b", Box(500f, 400f, 600f, 460f)) {}; c.register("a", Box(100f, 20f, 200f, 80f)) {}
        press(c, KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("a", c.focusedId)
    }

    @Test
    fun aButtonThatDisappearsReleasesTheFocus() {
        val (c, page) = screen(); page.onMove = PageResult.Edge
        press(c, KeyEvent.KEYCODE_DPAD_DOWN); assertEquals("played", c.focusedId)
        c.unregister("played")
        assertNull(c.focusedId); assertEquals(Zone.PAGE, c.zone)
    }

    // ---- the paw ----

    @Test
    fun theDockButtonSwitchesToThePawAndBackAndSaysWhichIsWhich() {
        val (c, page) = screen()
        c.togglePaw()
        assertEquals(InputMode.PAW, c.mode); assertEquals(TvHint.PAW_ON, c.hint); assertTrue(page.calls.contains("blur"))
        c.togglePaw()
        assertEquals(InputMode.NAV, c.mode); assertEquals(TvHint.NAV_ON, c.hint)
        assertEquals("back in navigate mode the ring reappears on the page", "enter null", page.calls.last())
    }

    @Test
    fun theArrowsMoveThePawAndOkTapsWhereItIs() {
        val (c, page) = screen(); val seen = ArrayList<Triple<Int, Int, Int>>()
        c.onInjected = { a, x, y -> seen += Triple(a, x.toInt(), y.toInt()) }
        c.togglePaw(); page.calls.clear()
        assertTrue(c.handleKey(key(KeyEvent.KEYCODE_DPAD_RIGHT)))
        idle(200)
        assertTrue(c.handleKey(key(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_UP)))
        assertTrue("moved right of the middle", c.cursorX > 500f); assertEquals(300f, c.cursorY, 0f)
        val x = c.cursorX.toInt()
        c.handleKey(key(KeyEvent.KEYCODE_DPAD_CENTER)); c.handleKey(key(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_UP))
        idle(TvController.TAP_MS + 20)
        assertEquals(listOf(Triple(MotionEvent.ACTION_DOWN, x, 300), Triple(MotionEvent.ACTION_UP, x, 300)), seen)
        assertTrue("the page's own navigation was not asked", page.calls.isEmpty())
    }

    @Test
    fun thePawStartsWhereTheFocusWas() {
        val (c, page) = screen(); page.onMove = PageResult.Edge
        press(c, KeyEvent.KEYCODE_DPAD_UP)                                  // back button, centre (840, 30)
        c.togglePaw()
        assertEquals(840f, c.cursorX, 0.5f); assertEquals(30f, c.cursorY, 0.5f)
    }

    @Test
    fun theCursorStopsWhenTheArrowIsReleased() {
        val (c, _) = screen(); c.togglePaw()
        c.handleKey(key(KeyEvent.KEYCODE_DPAD_DOWN)); idle(100); c.handleKey(key(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.ACTION_UP))
        val y = c.cursorY; idle(500)
        assertEquals(y, c.cursorY, 0f)
    }

    @Test
    fun otherKeysAreLeftToTheSystemInBothModes() {
        val (c, _) = screen()
        for (code in listOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_A)) assertFalse(code.toString(), c.handleKey(key(code)))
        c.togglePaw()
        for (code in listOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_A)) assertFalse(code.toString(), c.handleKey(key(code)))
    }

    // ---- the keyboard ----

    @Test
    fun playPauseSlidesTheKeyboardUpAndBackOrPlayPauseAgainClosesIt() {
        val (c, _) = screen()
        assertTrue(c.handleKey(key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))); assertTrue(c.keyboardOpen)
        assertTrue(c.handleKey(key(KeyEvent.KEYCODE_BACK))); assertTrue("Back closes on release", c.keyboardOpen)
        assertTrue(c.handleKey(key(KeyEvent.KEYCODE_BACK, KeyEvent.ACTION_UP))); assertFalse(c.keyboardOpen)
        c.openKeyboard(); c.handleKey(key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)); assertFalse(c.keyboardOpen)
    }

    @Test
    fun withTheKeyboardUpTheArrowsAndOkTypeAndThePageIsLeftAlone() {
        val (c, page) = screen(); val typed = ArrayList<Delivery>(); c.typer = { typed += it }
        c.openKeyboard()
        c.handleKey(key(KeyEvent.KEYCODE_DPAD_RIGHT)); c.handleKey(key(KeyEvent.KEYCODE_DPAD_CENTER)); c.handleKey(key(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_UP))
        assertEquals(listOf<Delivery>(Delivery.Code(KeyEvent.KEYCODE_W)), typed)
        assertEquals("w", c.preview); assertTrue(page.calls.isEmpty())
    }

    @Test
    fun shiftMakesOneCapitalAndBackspaceDeletes() {
        val (c, _) = screen(); val typed = ArrayList<Delivery>(); c.typer = { typed += it }
        c.openKeyboard()
        c.press(KbKey.Shift); c.press(KbKey.Ch("a")); c.press(KbKey.Ch("b")); c.press(KbKey.Backspace)
        assertEquals(listOf<Delivery>(Delivery.Code(KeyEvent.KEYCODE_A, shift = true), Delivery.Code(KeyEvent.KEYCODE_B), Delivery.Code(KeyEvent.KEYCODE_DEL)), typed)
        assertEquals("A", c.preview); assertFalse(c.shift)
    }

    @Test
    fun hebrewGoesInAsTextAndSwitchingLayoutsWorks() {
        val (c, _) = screen(); val typed = ArrayList<Delivery>(); c.typer = { typed += it }
        c.openKeyboard()
        c.press(KbKey.Switch(KbLayout.HEBREW, "עב")); assertEquals(KbLayout.HEBREW, c.layout)
        c.press(KbKey.Ch("ש")); c.press(KbKey.Space)
        assertEquals(listOf<Delivery>(Delivery.Text("ש"), Delivery.Code(KeyEvent.KEYCODE_SPACE)), typed)
        assertEquals("ש ", c.preview)
    }

    @Test
    fun aPasswordIsNeverShownInThePreview() {
        val (c, _) = screen(); c.typer = {}
        c.openKeyboard(passwordField = true)
        c.press(KbKey.Ch("s")); c.press(KbKey.Ch("1"))
        assertEquals("••", c.preview); assertTrue(c.password)
        c.closeKeyboard(); assertFalse(c.password)
    }

    @Test
    fun openingTheKeyboardLiftsAFingerThatWasDown() {
        val (c, _) = screen(); val seen = ArrayList<Int>(); c.onInjected = { a, _, _ -> seen += a }
        c.togglePaw()
        c.handleKey(key(KeyEvent.KEYCODE_DPAD_CENTER)); c.handleKey(key(KeyEvent.KEYCODE_DPAD_CENTER, repeat = 1)); c.handleKey(key(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_UP))
        assertTrue(c.dragging)
        c.openKeyboard()
        assertFalse(c.dragging); assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), seen)
    }

    @Test
    fun theScriptThatTypesTextIsSafeToEmbed() {
        val js = WebTyper.insertScript("</script> \"'")
        assertFalse(js.contains("</script>")); assertFalse(js.contains(" "))
        assertTrue(js.contains("insertText"))
        WebTyper.deliver(null, Delivery.Text("x"))                // no page, no crash
    }
}
