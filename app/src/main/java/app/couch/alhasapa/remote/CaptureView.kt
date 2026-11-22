package app.couch.alhasapa.remote

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager

/**
 * What the phone's own keyboard (Gboard, in Hebrew or English) types into, in keyboard mode. It shows nothing itself: it keeps a copy of
 * the line being typed and, after every change the keyboard makes, reports only the difference ([onEdit]: so many characters rubbed out,
 * then this text), so suggestions, corrections, completed words and emoji all reach the TV exactly. Enter ([onEnter]) ends the line and
 * starts a fresh one. A delete with nothing of ours left to delete is still a Backspace on the TV ([onBackspace]): its field may hold
 * text from before.
 */
class CaptureView(context: Context) : View(context) {
    var onEdit: (TextMirror.Edit) -> Unit = {}
    var onEnter: () -> Unit = {}
    var onBackspace: () -> Unit = {}

    /** The line as it stands (shown on the phone as the echo of what was typed). */
    var onLine: (String) -> Unit = {}

    private val line = SpannableStringBuilder()
    private var sent = ""
    private val imm get() = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // Plain text, no suggestions or learning (a game's answers are not words to remember), one line with a "send" key.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        outAttrs.initialSelStart = line.length
        outAttrs.initialSelEnd = line.length
        return Connection()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) performClick()
        return true
    }

    /** A touch, or TalkBack's double tap: brings the phone's keyboard back. */
    override fun performClick(): Boolean {
        super.performClick()
        showKeyboard()
        return true
    }

    /** Brings the phone's keyboard up (and keeps the focus here, where it types). */
    fun showKeyboard() {
        requestFocus()
        imm?.showSoftInput(this, 0)
    }

    private var batch = 0

    private inner class Connection : BaseInputConnection(this@CaptureView, true) {
        override fun getEditable(): Editable = line

        // A keyboard often makes several changes as one (a correction: delete a word, put another): they are sent as one difference.
        override fun beginBatchEdit(): Boolean { batch++; return true }
        override fun endBatchEdit(): Boolean { batch = (batch - 1).coerceAtLeast(0); if (batch == 0) syncNow(); return batch > 0 }

        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            val t = text.toString()
            val nl = t.indexOf('\n')
            if (nl >= 0) {
                if (nl > 0) super.commitText(t.substring(0, nl), newCursorPosition)
                syncNow(); enter()
                return true
            }
            return super.commitText(text, newCursorPosition).also { syncIfIdle() }
        }

        override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean =
            super.setComposingText(text, newCursorPosition).also { syncIfIdle() }

        override fun finishComposingText(): Boolean = super.finishComposingText().also { syncIfIdle() }

        override fun setComposingRegion(start: Int, end: Int): Boolean = super.setComposingRegion(start, end).also { syncIfIdle() }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            if (beforeLength > 0 && Selection.getSelectionStart(line) <= 0) { onBackspace(); return true }
            return super.deleteSurroundingText(beforeLength, afterLength).also { syncIfIdle() }
        }

        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
            if (beforeLength > 0 && Selection.getSelectionStart(line) <= 0) { onBackspace(); return true }
            return super.deleteSurroundingTextInCodePoints(beforeLength, afterLength).also { syncIfIdle() }
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action != KeyEvent.ACTION_DOWN) return true
            when (event.keyCode) {
                KeyEvent.KEYCODE_DEL -> {
                    val end = Selection.getSelectionEnd(line)
                    if (end <= 0) onBackspace() else {
                        val start = Character.offsetByCodePoints(line, end, -1).coerceAtLeast(0)
                        line.delete(start, end); syncNow()
                    }
                }
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { syncNow(); enter() }
                else -> {
                    val c = event.unicodeChar
                    if (c != 0) commitText(String(Character.toChars(c)), 1)
                }
            }
            return true
        }

        override fun performEditorAction(actionCode: Int): Boolean { syncNow(); enter(); return true }
    }

    /** The screen's own Enter (the big key with the paw): the same as the keyboard's. */
    fun pressEnter() { syncNow(); enter() }

    /** The screen's own Backspace: rubs out the last character here too, so the copy stays the same as the TV's field. */
    fun backspace() {
        val end = line.length
        if (end == 0) { onBackspace(); return }
        line.delete(Character.offsetByCodePoints(line, end, -1).coerceAtLeast(0), end)
        syncNow()
        imm?.restartInput(this)
    }

    /**
     * Tab, Esc or an arrow moved the TV elsewhere (another field, or the caret): what is typed next starts a fresh line here, and a delete
     * with nothing of the new line left is a plain Backspace on the TV.
     */
    fun newLine() {
        line.clear(); sent = ""
        onLine("")
        imm?.restartInput(this)
    }

    private fun syncIfIdle() { if (batch == 0) syncNow() }

    private fun syncNow() {
        val now = line.toString()
        val e = TextMirror.diff(sent, now)
        sent = now
        if (!e.isEmpty) onEdit(e)
        onLine(now)
        // A very long line is cut to its end (the TV keeps all of it; deleting past the cut still deletes on the TV).
        if (line.length > 400) { line.delete(0, line.length - 100); sent = line.toString(); imm?.restartInput(this) }
    }

    private fun enter() {
        onEnter()
        line.clear(); sent = ""
        onLine("")
        imm?.restartInput(this)
    }
}
