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
    fun okInThePageTapsTheFocusedElementAndAFieldIsOnlyChosenNotTypedInto() {
        val (c, page) = screen()
        press(c, KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf("activate", "tap 50,60"), page.calls); assertFalse(c.keyboardOpen)
        page.calls.clear(); page.onActivate = PageResult.Tap(1f, 2f, editable = true, password = true)
        press(c, KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf("activate", "tap 1,2"), page.calls)
        assertFalse("the keyboard opens from its button only", c.keyboardOpen)
        assertEquals("instead the person is told where the keyboard is", TvHint.TYPE, c.hint)
    }

    @Test
    fun thePageThatDoesNotAnswerCannotFreezeTheRemote() {
        val page = object : PageNav {
            val pending = ArrayList<(PageResult) -> Unit>()
            override fun enter(dir: Dir?, cb: (PageResult) -> Unit) { pending += cb }
            override fun move(dir: Dir, cb: (PageResult) -> Unit) { pending += cb }
            override fun activate(cb: (PageResult) -> Unit) {}
            override fun blur() {}
            override fun tap(x: Float, y: Float) {}
        }
        val c = TvController(true, 1f); c.resize(1000f, 600f); c.pageNav = page; c.pageBox = Box(100f, 70f, 900f, 530f)
        c.register("dock", Box(0f, 0f, 80f, 600f)) {}
        press(c, KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(1, page.pending.size)
        idle(TvController.REPEAT_MS + 10)
        press(c, KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals("while one question is out, the next press waits", 1, page.pending.size)
        idle(TvController.PAGE_TIMEOUT_MS + 50)
        press(c, KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals("after the time is up the remote asks again", 2, page.pending.size)
        // The first, late answer is stale and changes nothing; the current one counts.
        page.pending[0](PageResult.Edge)
        assertEquals(Zone.PAGE, c.zone)
        page.pending[1](PageResult.Edge)
        assertEquals("the current answer is acted on", Zone.NATIVE, c.zone); assertEquals("dock", c.focusedId)
    }

    @Test
    fun aMistakeWhileHandlingAKeyNeverTakesTheAppDown() {
        val c = TvController(true, 1f); c.resize(1000f, 600f); c.pageBox = Box(100f, 70f, 900f, 530f)
        c.pageNav = object : PageNav {
            override fun enter(dir: Dir?, cb: (PageResult) -> Unit) = error("boom")
            override fun move(dir: Dir, cb: (PageResult) -> Unit) = error("boom")
            override fun activate(cb: (PageResult) -> Unit) = error("boom")
            override fun blur() {}
            override fun tap(x: Float, y: Float) {}
        }
        c.handleKey(key(KeyEvent.KEYCODE_DPAD_DOWN))
        assertFalse("OK fails too, and the key goes on to the system", c.handleKey(key(KeyEvent.KEYCODE_DPAD_CENTER)))
        // ...and the remote still works afterwards.
        c.pageNav = FakePage()
        assertTrue(c.handleKey(key(KeyEvent.KEYCODE_DPAD_DOWN)))
    }

    @Test
    fun theInformationPanelTakesTheArrowsAndBackClosesIt() {
        val (c, page) = screen()
        var scrolled = 0
        c.openPanel(TvPanel.INFO); c.textScroller = { scrolled += it }
        assertEquals(TvPanel.INFO, c.panel); assertTrue(page.calls.contains("blur"))
        page.calls.clear()
        press(c, KeyEvent.KEYCODE_DPAD_DOWN); press(c, KeyEvent.KEYCODE_DPAD_DOWN); press(c, KeyEvent.KEYCODE_DPAD_UP)
        assertEquals("Up and Down scroll the text", 1, scrolled); assertTrue("the page is not walked", page.calls.isEmpty())
        press(c, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("Left and Right go to the app's buttons, never into the page", Zone.NATIVE, c.zone)
        assertTrue(c.handleKey(key(KeyEvent.KEYCODE_BACK))); assertTrue(c.handleKey(key(KeyEvent.KEYCODE_BACK, KeyEvent.ACTION_UP)))
        assertNull(c.panel); assertEquals("closed, the focus is back on the page", Zone.PAGE, c.zone)
        // Opened again, its close button has the focus at once: one OK closes it.
        c.openPanel(TvPanel.INFO); c.register(TvController.PANEL_CLOSE_ID, Box(500f, 20f, 600f, 70f)) { c.closePanel() }
        assertTrue(c.isFocused(TvController.PANEL_CLOSE_ID))
        press(c, KeyEvent.KEYCODE_DPAD_CENTER)
        assertNull(c.panel)
    }

    @Test
    fun holdingAnArrowWalksOnAtASteadyPace() {
        val (c, page) = screen()
        c.handleKey(key(KeyEvent.KEYCODE_DPAD_DOWN))
        for (i in 1..5) c.handleKey(key(KeyEvent.KEYCODE_DPAD_DOWN, repeat = i))   // repeats arriving faster than a step
        assertEquals("the repeats within one step are let go", 1, page.calls.count { it == "move DOWN" })
        idle(TvController.REPEAT_MS + 10)
        c.handleKey(key(KeyEvent.KEYCODE_DPAD_DOWN, repeat = 6))
        assertEquals(2, page.calls.count { it == "move DOWN" })
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
    fun theKeyboardOpensOnlyFromItsButtonAndBackClosesIt() {
        val (c, page) = screen()
        assertFalse("Play/Pause is the page's (a video's), it does not open the keyboard", c.handleKey(key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)))
        assertFalse(c.keyboardOpen)
        c.keyboardButton(); assertTrue(c.keyboardOpen)
        assertTrue(c.handleKey(key(KeyEvent.KEYCODE_BACK))); assertTrue("Back closes on release", c.keyboardOpen)
        assertTrue(c.handleKey(key(KeyEvent.KEYCODE_BACK, KeyEvent.ACTION_UP))); assertFalse(c.keyboardOpen)
        c.keyboardButton(); c.keyboardButton(); assertFalse("the button closes it again", c.keyboardOpen)
        assertTrue(page.calls.isEmpty() || page.calls.all { it.startsWith("field") || it == "blur" })
    }

    @Test
    fun theKeyboardButtonAsksThePageAboutTheFieldSoAPasswordShowsAsDots() {
        val page = object : PageNav by FakePage() {
            override fun field(cb: (editable: Boolean, password: Boolean) -> Unit) = cb(true, true)
        }
        val c = TvController(true, 1f); c.pageNav = page; c.pageBox = Box(0f, 0f, 10f, 10f); c.typer = {}
        c.keyboardButton()
        assertTrue(c.keyboardOpen); assertTrue(c.password)
        c.press(KbKey.Ch("a")); assertEquals("•", c.preview)
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

    // ---- the phone's touchpad ----

    @Test
    fun thePhonesTouchpadDrivesThePawAndPutsItAwayAgain() {
        val (c, page) = screen(); val seen = ArrayList<String>()
        c.onInjected = { a, x, y -> seen += "${MotionEvent.actionToString(a)} ${x.toInt()},${y.toInt()}" }
        assertEquals(InputMode.NAV, c.mode)
        c.remotePointer(true)
        assertEquals("the pad came out: the paw shows", InputMode.PAW, c.mode); assertEquals(TvHint.PHONE_PAW, c.hint)
        val x0 = c.cursorX; val y0 = c.cursorY
        c.remoteMove(10f, -5f)
        assertEquals("a dp of the pad is a few of the TV's pixels", x0 + 10f * (1000f / 640f), c.cursorX, 0.01f)
        assertEquals(y0 - 5f * (1000f / 640f), c.cursorY, 0.01f)
        assertTrue("moving is not touching", seen.isEmpty())
        c.remoteClick(); idle(TvController.TAP_MS + 20)
        assertEquals("a short touch on the pad taps where the paw is", listOf("ACTION_DOWN", "ACTION_UP"), seen.map { it.substringBefore(' ') })
        seen.clear()
        c.remoteDrag(true); c.remoteMove(20f, 0f); c.remoteDrag(false)
        assertEquals(listOf("ACTION_DOWN", "ACTION_MOVE", "ACTION_UP"), seen.map { it.substringBefore(' ') })
        var scrolled = 0f; c.onScrolled = { scrolled += it }
        c.remoteScroll(-30f); assertEquals(-30f, scrolled, 0f)
        c.remotePointer(false)
        assertEquals("the pad went back: navigating as before", InputMode.NAV, c.mode)
        assertTrue("and the page has the focus again", page.calls.contains("enter null"))
    }

    @Test
    fun aPawTheRemoteTurnedOnStaysWhenThePhonesPadGoesAway() {
        val (c, _) = screen()
        c.togglePaw()
        c.remotePointer(true); c.remotePointer(false)
        assertEquals("it was not the phone's to turn off", InputMode.PAW, c.mode)
    }

    @Test
    fun aFingerLeftDownIsLiftedWhenThePhoneGoes() {
        val (c, _) = screen(); val seen = ArrayList<Int>(); c.onInjected = { a, _, _ -> seen += a }
        c.remoteDrag(true); assertTrue(c.dragging)
        c.remotePointer(false)
        assertFalse(c.dragging); assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), seen)
    }

    @Test
    fun whenThePhoneStartsTypingTheHighlightedFieldGetsTheCursorFirst() {
        var asked = 0
        val page = object : PageNav by FakePage() { override fun field(cb: (editable: Boolean, password: Boolean) -> Unit) { asked++; cb(true, false) } }
        val c = TvController(true, 1f); c.pageNav = page; val typed = ArrayList<Delivery>(); c.typer = { typed += it }
        c.remoteInput(listOf(Delivery.Text("a"))); c.remoteInput(listOf(Delivery.Text("b")))
        assertEquals("once, when typing begins", 1, asked)
        assertEquals(listOf<Delivery>(Delivery.Text("a"), Delivery.Text("b")), typed)
        idle(3_100)
        c.remoteInput(listOf(Delivery.Text("c")))
        assertEquals("and again after a pause", 2, asked)
    }
}
