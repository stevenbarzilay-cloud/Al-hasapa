package app.couch.alhasapa.remote

import android.content.Context
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The phone's keyboard as it really types (composing words, correcting, deleting, the send key), turned into exact keystrokes. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [37])
class CaptureViewTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val sent = ArrayList<String>()
    private var line = ""
    private val view = CaptureView(context).apply {
        onEdit = { e -> repeat(e.back) { sent += "⌫" }; if (e.text.isNotEmpty()) sent += e.text }
        onEnter = { sent += "⏎" }
        onBackspace = { sent += "⌫!" }
        onLine = { line = it }
    }
    private val ic = view.onCreateInputConnection(EditorInfo())

    @Test
    fun aKeyboardThatComposesWordsSendsEachLetterOnce() {
        ic.setComposingText("o", 1); ic.setComposingText("oc", 1); ic.setComposingText("oct", 1)
        ic.commitText("oct ", 1)
        assertEquals(listOf("o", "c", "t", " "), sent); assertEquals("oct ", line)
    }

    @Test
    fun aCorrectionIsOneChangeNotADeleteAndARetype() {
        ic.commitText("teh ", 1); sent.clear()
        ic.beginBatchEdit(); ic.deleteSurroundingText(4, 0); ic.commitText("the ", 1); ic.endBatchEdit()
        assertEquals(listOf("⌫", "⌫", "⌫", "he "), sent); assertEquals("the ", line)
    }

    @Test
    fun deletingPastWhatWasTypedHereStillDeletesOnTheTv() {
        ic.commitText("ab", 1)
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
        ic.deleteSurroundingText(1, 0)
        ic.deleteSurroundingText(1, 0)                   // nothing of ours left: the TV's field may still hold text
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
        assertEquals(listOf("a", "b", "⌫", "⌫", "⌫!", "⌫!"), sent.flatMap { if (it == "ab") listOf("a", "b") else listOf(it) })
    }

    @Test
    fun theSendKeyAndANewLineAreEnterAndStartAFreshLine() {
        ic.commitText("octopus", 1)
        ic.performEditorAction(EditorInfo.IME_ACTION_SEND)
        assertEquals(listOf("octopus", "⏎"), sent); assertEquals("", line)
        sent.clear()
        ic.commitText("שלום\n", 1)
        assertEquals(listOf("שלום", "⏎"), sent)
    }

    @Test
    fun anEmojiIsRubbedOutWhole() {
        ic.commitText("a😀", 1); sent.clear()
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
        assertEquals(listOf("⌫"), sent); assertEquals("a", line)
    }

    @Test
    fun theScreensOwnKeysKeepTheCopyRight() {
        ic.commitText("abc", 1); sent.clear()
        view.backspace(); assertEquals(listOf("⌫"), sent); assertEquals("ab", line)
        view.newLine(); assertEquals("ab is still on the TV, but a new line starts here", "", line)
        view.backspace(); assertEquals(listOf("⌫", "⌫!"), sent)
        view.pressEnter(); assertTrue(sent.last() == "⏎")
    }

    @Test
    fun itAsksTheKeyboardForPlainTextWithoutSuggestionsAndASendKey() {
        val info = EditorInfo(); view.onCreateInputConnection(info)
        assertTrue(info.inputType and android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0)
        assertEquals(EditorInfo.IME_ACTION_SEND, info.imeOptions and EditorInfo.IME_MASK_ACTION)
        assertTrue(view.onCheckIsTextEditor())
    }
}
