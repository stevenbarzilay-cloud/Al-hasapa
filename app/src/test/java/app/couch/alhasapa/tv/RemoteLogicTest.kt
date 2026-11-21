package app.couch.alhasapa.tv

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class Rec : TouchSink {
    val log = ArrayList<String>()
    override fun tap(x: Float, y: Float) { log += "tap ${x.toInt()},${y.toInt()}" }
    override fun down(x: Float, y: Float) { log += "down ${x.toInt()},${y.toInt()}" }
    override fun move(x: Float, y: Float) { log += "move ${x.toInt()},${y.toInt()}" }
    override fun up(x: Float, y: Float) { log += "up ${x.toInt()},${y.toInt()}" }
}

class RemoteLogicTest {
    private fun cursor(w: Float = 1000f, h: Float = 600f, density: Float = 1f) = CursorController(density).apply { resize(w, h) }

    // ---- the cursor ----

    @Test
    fun theCursorStartsInTheMiddleAndStaysOnTheScreen() {
        val c = cursor(); val r = Rec()
        assertEquals(500f, c.x, 0f); assertEquals(300f, c.y, 0f)
        c.keyDown(Dir.LEFT, r)
        repeat(400) { c.tick(16, r) }                 // held for ages
        assertEquals(0f, c.x, 0f)
        c.keyUp(Dir.LEFT); c.keyDown(Dir.DOWN, r); repeat(400) { c.tick(16, r) }
        assertEquals(600f, c.y, 0f)
    }

    @Test
    fun aShortTapOnAnArrowStillMovesALittle() {
        val c = cursor(density = 2f); val r = Rec()
        assertTrue(c.keyDown(Dir.RIGHT, r)); c.keyUp(Dir.RIGHT)
        assertEquals("10 dp at density 2", 520f, c.x, 0.01f)
    }

    @Test
    fun theLongerAnArrowIsHeldTheFasterItGoes() {
        val c = cursor(w = 1_000_000f); val r = Rec()
        c.keyDown(Dir.RIGHT, r)
        val x0 = c.x; repeat(10) { c.tick(16, r) }; val early = c.x - x0
        repeat(100) { c.tick(16, r) }
        val x1 = c.x; repeat(10) { c.tick(16, r) }; val late = c.x - x1
        assertTrue("early $early late $late", late > early * 3)
        assertEquals(160f, c.speedDpPerSec(0), 0f); assertEquals(1060f, c.speedDpPerSec(10_000), 0f)
    }

    @Test
    fun releasingAnArrowStopsTheCursorAndRestartsTheRamp() {
        val c = cursor(); val r = Rec()
        c.keyDown(Dir.UP, r); repeat(60) { c.tick(16, r) }; c.keyUp(Dir.UP)
        assertFalse(c.moving); assertFalse(c.tick(16, r))
        c.keyDown(Dir.UP, r)
        assertEquals("back to the slow start", 160f, c.speedDpPerSec(), 0f)
    }

    @Test
    fun diagonalsAreNotFasterThanStraightLines() {
        val a = cursor(); val b = cursor(); val r = Rec()
        a.keyDown(Dir.RIGHT, r); a.tick(100, r)
        b.keyDown(Dir.RIGHT, r); b.keyDown(Dir.DOWN, r); b.tick(100, r)
        val straight = a.x - 500f
        val diag = Math.hypot((b.x - 500f).toDouble(), (b.y - 300.0)).toFloat()
        assertEquals(straight, diag, 15f)
    }

    @Test
    fun aShortOkIsATapAtTheCursor() {
        val c = cursor(); val r = Rec()
        c.okDown(0, r); c.okUp(r)
        assertEquals(listOf("tap 500,300"), r.log)
    }

    @Test
    fun holdingOkStartsADragThatFollowsTheArrowsAndOkAgainDropsIt() {
        val c = cursor(); val r = Rec()
        c.okDown(0, r); c.okDown(1, r); c.okDown(2, r); c.okUp(r)            // a long press, once
        assertTrue(c.dragging); assertEquals(listOf("down 500,300"), r.log)
        c.keyDown(Dir.DOWN, r); c.tick(100, r); c.keyUp(Dir.DOWN)
        assertTrue("moves while dragging", r.log.any { it.startsWith("move") })
        c.okDown(0, r); c.okDown(1, r); c.okUp(r)
        assertFalse(c.dragging); assertTrue(r.log.last().startsWith("up"))
        assertEquals("no stray tap from the long presses", 0, r.log.count { it.startsWith("tap") })
    }

    @Test
    fun cancelLiftsAFingerThatIsDown() {
        val c = cursor(); val r = Rec()
        c.okDown(0, r); c.okDown(1, r); c.okUp(r)
        c.cancel(r)
        assertFalse(c.dragging); assertEquals("up 500,300", r.log.last())
    }

    // ---- the keyboard ----

    @Test
    fun everyLayoutHasTheKeysATypingGameNeedsInFourRows() {
        for (l in KbLayout.entries) assertEquals("$l: the keyboard stays small", 4, KbLayouts.rows(l).size)
        val en = KbLayouts.rows(KbLayout.ENGLISH).flatten()
        for (c in 'a'..'z') assertTrue("$c", en.any { it is KbKey.Ch && it.lower == c.toString() })
        assertTrue(en.contains(KbKey.Backspace)); assertTrue(en.contains(KbKey.Enter)); assertTrue(en.contains(KbKey.Space)); assertTrue(en.contains(KbKey.Shift))
        val sym = KbLayouts.rows(KbLayout.SYMBOLS).flatten()
        for (c in '0'..'9') assertTrue("$c is one switch away", sym.any { it is KbKey.Ch && it.lower == c.toString() })
        val he = KbLayouts.rows(KbLayout.HEBREW).flatten().filterIsInstance<KbKey.Ch>().map { it.lower }.toSet()
        for (c in "אבגדהוזחטיכלמנסעפצקרשתךםןףץ") assertTrue("$c", c.toString() in he)
        for (l in KbLayout.entries) {
            val others = KbLayout.entries.filter { it != l }
            val switches = KbLayouts.rows(l).flatten().filterIsInstance<KbKey.Switch>().map { it.to }
            assertEquals("$l reaches the other layouts", others.toSet(), switches.toSet())
        }
    }

    @Test
    fun lettersAndDigitsAreRealKeyPressesEverythingElseIsText() {
        assertEquals(Delivery.Code(KeyEvent.KEYCODE_A), KbLayouts.delivery(KbKey.Ch("a"), false))
        assertEquals(Delivery.Code(KeyEvent.KEYCODE_Z, shift = true), KbLayouts.delivery(KbKey.Ch("z"), true))
        assertEquals(Delivery.Code(KeyEvent.KEYCODE_7), KbLayouts.delivery(KbKey.Ch("7"), false))
        assertEquals(Delivery.Text("ש"), KbLayouts.delivery(KbKey.Ch("ש"), false))
        assertEquals(Delivery.Text("@"), KbLayouts.delivery(KbKey.Ch("@"), false))
        assertEquals(Delivery.Code(KeyEvent.KEYCODE_DEL), KbLayouts.delivery(KbKey.Backspace, false))
        assertEquals(Delivery.Code(KeyEvent.KEYCODE_ENTER), KbLayouts.delivery(KbKey.Enter, false))
        assertEquals(Delivery.Code(KeyEvent.KEYCODE_SPACE), KbLayouts.delivery(KbKey.Space, false))
        assertNull(KbLayouts.delivery(KbKey.Shift, false)); assertNull(KbLayouts.delivery(KbKey.Close, false))
    }

    @Test
    fun theHighlightMovesWithTheArrowsAndKeepsItsPlaceAlongShorterRows() {
        val n = KeyboardNav(); n.reset()
        assertEquals(0 to 0, n.row to n.col)
        n.move(Dir.RIGHT); n.move(Dir.RIGHT); assertEquals(2, n.col)
        n.move(Dir.DOWN); assertEquals(1, n.row)
        repeat(30) { n.move(Dir.RIGHT) }; assertEquals("stops at the end of the row", 9, n.col)
        n.move(Dir.DOWN); n.move(Dir.DOWN); assertEquals(3, n.row)
        n.move(Dir.DOWN); assertEquals("stops at the bottom", 3, n.row)
        repeat(10) { n.move(Dir.UP) }; assertEquals(0, n.row)
        assertTrue(n.col < KbLayouts.rows(KbLayout.ENGLISH)[0].size)
    }

    /** Written for tools/tvnav_check.cjs, which runs the real script in Chromium. */
    @Test
    fun dumpTvNavScriptForTheBrowserCheck() {
        val dir = java.io.File("build/display-scripts").apply { mkdirs() }
        java.io.File(dir, "tvnav.js").writeText(TvNavScript.JS)
        assertTrue(TvNavScript.JS.contains("__tvnav"))
    }

    @Test
    fun theNavigationScriptNeverReadsWhatThePageSaysOrSendsAnything() {
        for (banned in listOf("innerText", "innerHTML", "outerHTML", ".value", "cookie", "localStorage", "sessionStorage", "fetch(", "XMLHttpRequest", "sendBeacon", "WebSocket", "eval(", "postMessage"))
            assertFalse("must not use $banned", TvNavScript.JS.contains(banned))
        // textContent only once, and only to write its own style sheet.
        assertEquals(1, Regex("textContent").findAll(TvNavScript.JS).count())
        assertTrue(TvNavScript.JS.contains("s.textContent='["))
    }

    @Test
    fun theNavigationScriptHasNoFrameLoopAndNoTemplateCharacters() {
        // The old version redrew a ring every frame for as long as it showed, which kept a weak box busy all the time.
        assertFalse("no ring element any more", TvNavScript.JS.contains("couch-tv-ring"))
        assertFalse("no loop that places a ring", TvNavScript.JS.contains("function loop"))
        assertTrue("the glide's frames end by themselves", TvNavScript.JS.contains("a.idle>=3"))
        assertFalse(TvNavScript.JS.contains("$"))
        assertTrue("it says which version it is, so an older copy on a page is replaced", TvNavScript.JS.contains("v:${TvNavScript.VERSION},doc:document"))
    }

    @Test
    fun pageAnswersAreParsedAndAnythingElseIsNothing() {
        fun p(json: String) = WebPageNav.parse("\"" + json.replace("\"", "\\\"") + "\"")
        assertEquals(PageResult.Moved, p("{\"k\":\"moved\"}")); assertEquals(PageResult.Edge, p("{\"k\":\"edge\"}"))
        assertEquals(PageResult.Scrolled, p("{\"k\":\"scrolled\"}")); assertEquals(PageResult.None, p("{\"k\":\"none\"}"))
        assertEquals(PageResult.Tap(10f, 20f, true, false), p("{\"k\":\"tap\",\"x\":10,\"y\":20,\"ed\":true,\"pw\":false}"))
        assertEquals(PageResult.None, WebPageNav.parse(null)); assertEquals(PageResult.None, WebPageNav.parse("null")); assertEquals(PageResult.None, WebPageNav.parse("garbage"))
    }
}
