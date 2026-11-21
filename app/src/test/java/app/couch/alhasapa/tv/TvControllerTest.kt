package app.couch.alhasapa.tv

import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [37])
class TvControllerTest {
    private fun key(code: Int, action: Int = KeyEvent.ACTION_DOWN, repeat: Int = 0) = KeyEvent(0, 0, action, code, repeat)
    private fun controller(enabled: Boolean = true) = TvController(enabled, 1f).apply { resize(1000f, 600f) }
    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(ms))

    @Test
    fun onAPhoneEveryKeyPassesThrough() {
        val c = controller(enabled = false)
        for (code in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
            assertFalse(code.toString(), c.handleKey(key(code)))
        c.openKeyboard(); assertFalse("no keyboard either", c.keyboardOpen)
    }

    @Test
    fun theArrowsMoveTheCursorAndOkTapsWhereItIs() {
        val c = controller(); val seen = ArrayList<Triple<Int, Int, Int>>()
        c.onInjected = { a, x, y -> seen += Triple(a, x.toInt(), y.toInt()) }
        assertTrue(c.handleKey(key(KeyEvent.KEYCODE_DPAD_RIGHT)))
        idle(200)
        assertTrue(c.handleKey(key(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_UP)))
        assertTrue("moved right of the middle", c.cursorX > 500f); assertEquals(300f, c.cursorY, 0f)
        val x = c.cursorX.toInt()
        assertTrue(c.handleKey(key(KeyEvent.KEYCODE_DPAD_CENTER))); assertTrue(c.handleKey(key(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_UP)))
        idle(TvController.TAP_MS + 20)
        assertEquals(listOf(Triple(MotionEvent.ACTION_DOWN, x, 300), Triple(MotionEvent.ACTION_UP, x, 300)), seen)
    }

    @Test
    fun theCursorStopsWhenTheArrowIsReleased() {
        val c = controller()
        c.handleKey(key(KeyEvent.KEYCODE_DPAD_DOWN)); idle(100); c.handleKey(key(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.ACTION_UP))
        val y = c.cursorY; idle(500)
        assertEquals(y, c.cursorY, 0f)
    }

    @Test
    fun otherKeysAreLeftToTheSystem() {
        val c = controller()
        for (code in listOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_A))
            assertFalse(code.toString(), c.handleKey(key(code)))
    }

    @Test
    fun withTheCursorOffTheArrowsAreLeftForPlainFocusNavigation() {
        val c = controller(); c.cursorMode = false
        assertFalse(c.handleKey(key(KeyEvent.KEYCODE_DPAD_LEFT))); assertFalse(c.handleKey(key(KeyEvent.KEYCODE_DPAD_CENTER)))
    }

    @Test
    fun playPauseSlidesTheKeyboardUpAndBackOrPlayPauseAgainClosesIt() {
        val c = controller()
        assertTrue(c.handleKey(key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))); assertTrue(c.keyboardOpen)
        assertTrue(c.handleKey(key(KeyEvent.KEYCODE_BACK))); assertTrue("Back closes on release", c.keyboardOpen.also { })
        assertTrue(c.handleKey(key(KeyEvent.KEYCODE_BACK, KeyEvent.ACTION_UP))); assertFalse(c.keyboardOpen)
        c.openKeyboard(); c.handleKey(key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)); assertFalse(c.keyboardOpen)
    }

    @Test
    fun withTheKeyboardUpTheArrowsAndOkTypeAndTheCursorSleeps() {
        val c = controller(); val typed = ArrayList<Delivery>(); c.typer = { typed += it }
        c.openKeyboard()
        // The highlight starts on the "q" row's first key; move to "w" and press.
        c.handleKey(key(KeyEvent.KEYCODE_DPAD_RIGHT)); c.handleKey(key(KeyEvent.KEYCODE_DPAD_CENTER)); c.handleKey(key(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_UP))
        assertEquals(listOf<Delivery>(Delivery.Code(KeyEvent.KEYCODE_W)), typed)
        assertEquals("w", c.preview)
        assertEquals("the cursor did not move", 500f, c.cursorX, 0f)
    }

    @Test
    fun shiftMakesOneCapitalAndBackspaceDeletes() {
        val c = controller(); val typed = ArrayList<Delivery>(); c.typer = { typed += it }
        c.openKeyboard()
        c.press(KbKey.Shift); c.press(KbKey.Ch("a")); c.press(KbKey.Ch("b")); c.press(KbKey.Backspace)
        assertEquals(listOf<Delivery>(Delivery.Code(KeyEvent.KEYCODE_A, shift = true), Delivery.Code(KeyEvent.KEYCODE_B), Delivery.Code(KeyEvent.KEYCODE_DEL)), typed)
        assertEquals("A", c.preview); assertFalse(c.shift)
    }

    @Test
    fun hebrewGoesInAsTextAndSwitchingLayoutsWorks() {
        val c = controller(); val typed = ArrayList<Delivery>(); c.typer = { typed += it }
        c.openKeyboard()
        c.press(KbKey.Switch(KbLayout.HEBREW, "עב")); assertEquals(KbLayout.HEBREW, c.layout)
        c.press(KbKey.Ch("ש")); c.press(KbKey.Space)
        assertEquals(listOf<Delivery>(Delivery.Text("ש"), Delivery.Code(KeyEvent.KEYCODE_SPACE)), typed)
        assertEquals("ש ", c.preview)
    }

    @Test
    fun aPasswordIsNeverShownInThePreview() {
        val c = controller(); c.typer = {}
        c.openKeyboard(passwordField = true)
        c.press(KbKey.Ch("s")); c.press(KbKey.Ch("1"))
        assertEquals("••", c.preview); assertTrue(c.password)
        c.closeKeyboard(); assertFalse(c.password)
    }

    @Test
    fun openingTheKeyboardLiftsAFingerThatWasDown() {
        val c = controller(); val seen = ArrayList<Int>(); c.onInjected = { a, _, _ -> seen += a }
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
