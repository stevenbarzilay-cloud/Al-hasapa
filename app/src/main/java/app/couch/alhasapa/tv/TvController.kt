package app.couch.alhasapa.tv

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The TV's remote control, as one object per screen: the cursor (a virtual finger), the on-screen keyboard and what to do with
 * each key. Inert (every key passes through) when [enabled] is false, which is always the case on a phone.
 *
 * The cursor works by handing real touch events to the window, so it reaches everything the same way a finger would: the game
 * in its WebView, the hub, the menus. While the keyboard is open the arrows move its highlight instead.
 */
class TvController(val enabled: Boolean, density: Float) {
    private val handler = Handler(Looper.getMainLooper())
    val cursor = CursorController(density)

    var cursorX by mutableFloatStateOf(0f); private set
    var cursorY by mutableFloatStateOf(0f); private set
    var cursorVisible by mutableStateOf(false); private set
    var dragging by mutableStateOf(false); private set

    /** false = plain focus navigation (the arrows move the focus), for screens where that is easier than a cursor. */
    var cursorMode by mutableStateOf(true)

    var keyboardOpen by mutableStateOf(false); private set
    var layout by mutableStateOf(KbLayout.ENGLISH); private set
    var shift by mutableStateOf(false); private set
    var password by mutableStateOf(false); private set
    var preview by mutableStateOf(""); private set
    var highlightRow by mutableStateOf(0); private set
    var highlightCol by mutableStateOf(0); private set
    private val nav = KeyboardNav()

    /** The window's decor view: where touches are injected. Set by the host. */
    var decor: View? = null

    /** Delivers a typed key to the page. Replaceable in tests. */
    var typer: (Delivery) -> Unit = { WebTyper.deliver(TvTarget.get(), it) }

    /** Every injected touch, for tests. */
    var onInjected: (action: Int, x: Float, y: Float) -> Unit = { _, _, _ -> }

    private var downTime = 0L

    private val sink = object : TouchSink {
        override fun tap(x: Float, y: Float) { down(x, y); handler.postDelayed({ send(MotionEvent.ACTION_UP, x, y) }, TAP_MS) }
        override fun down(x: Float, y: Float) { downTime = SystemClock.uptimeMillis(); send(MotionEvent.ACTION_DOWN, x, y) }
        override fun move(x: Float, y: Float) = send(MotionEvent.ACTION_MOVE, x, y)
        override fun up(x: Float, y: Float) = send(MotionEvent.ACTION_UP, x, y)
    }

    private fun send(action: Int, x: Float, y: Float) {
        onInjected(action, x, y)
        val d = decor ?: return
        val ev = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        ev.source = InputDevice.SOURCE_TOUCHSCREEN
        d.dispatchTouchEvent(ev)
        ev.recycle()
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!cursor.moving) return
            cursor.tick(CursorController.TICK_MS, sink)
            sync()
            handler.postDelayed(this, CursorController.TICK_MS)
        }
    }

    private fun sync() { cursorX = cursor.x; cursorY = cursor.y; dragging = cursor.dragging }

    fun resize(w: Float, h: Float) { cursor.resize(w, h); sync() }

    /** Puts the cursor somewhere (the arrows get there by themselves; this is for tests and for "jump to the keyboard"). */
    fun placeCursor(x: Float, y: Float) { cursor.jumpTo(x, y); cursorVisible = true; sync() }

    /** Called from the activity before the key goes anywhere else. Returns true when the key was used. */
    fun handleKey(e: KeyEvent): Boolean {
        if (!enabled) return false
        if (keyboardOpen) return handleKeyboardKey(e)
        if (e.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) openKeyboard()
            return true
        }
        if (!cursorMode) return false
        val dir = when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> Dir.LEFT
            KeyEvent.KEYCODE_DPAD_UP -> Dir.UP
            KeyEvent.KEYCODE_DPAD_RIGHT -> Dir.RIGHT
            KeyEvent.KEYCODE_DPAD_DOWN -> Dir.DOWN
            else -> null
        }
        if (dir != null) {
            cursorVisible = true
            if (e.action == KeyEvent.ACTION_DOWN) {
                if (cursor.keyDown(dir, sink)) handler.post(ticker)
                sync()
            } else if (e.action == KeyEvent.ACTION_UP) cursor.keyUp(dir)
            return true
        }
        if (e.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || e.keyCode == KeyEvent.KEYCODE_ENTER || e.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            cursorVisible = true
            if (e.action == KeyEvent.ACTION_DOWN) cursor.okDown(e.repeatCount, sink)
            else if (e.action == KeyEvent.ACTION_UP) cursor.okUp(sink)
            sync()
            return true
        }
        return false
    }

    private fun handleKeyboardKey(e: KeyEvent): Boolean {
        val dir = when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> Dir.LEFT
            KeyEvent.KEYCODE_DPAD_UP -> Dir.UP
            KeyEvent.KEYCODE_DPAD_RIGHT -> Dir.RIGHT
            KeyEvent.KEYCODE_DPAD_DOWN -> Dir.DOWN
            else -> null
        }
        when {
            dir != null -> { if (e.action == KeyEvent.ACTION_DOWN) { nav.move(dir); syncNav() }; return true }
            e.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || e.keyCode == KeyEvent.KEYCODE_ENTER || e.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) press(nav.key)
                return true
            }
            e.keyCode == KeyEvent.KEYCODE_BACK -> { if (e.action == KeyEvent.ACTION_UP) closeKeyboard(); return true }
            e.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) closeKeyboard(); return true }
        }
        return false
    }

    fun openKeyboard(passwordField: Boolean = false) {
        if (!enabled) return
        cursor.cancel(sink); handler.removeCallbacks(ticker); sync()
        password = passwordField
        if (!keyboardOpen) { preview = ""; layout = KbLayout.ENGLISH; shift = false; nav.setRows(KbLayouts.rows(layout)); nav.reset(); syncNav() }
        keyboardOpen = true
    }

    fun closeKeyboard() { keyboardOpen = false; password = false }

    /** A key on the keyboard was pressed (by OK on the highlight, or by a tap). */
    fun press(key: KbKey) {
        when (key) {
            KbKey.Shift -> shift = !shift
            KbKey.Close -> closeKeyboard()
            is KbKey.Switch -> { layout = key.to; shift = false; nav.setRows(KbLayouts.rows(layout)); syncNav() }
            else -> {
                val d = KbLayouts.delivery(key, shift) ?: return
                typer(d)
                preview = when (key) {
                    KbKey.Backspace -> preview.dropLast(1)
                    KbKey.Enter -> ""
                    KbKey.Space -> preview + " "
                    is KbKey.Ch -> preview + (if (password) "•" else if (shift) key.upper else key.lower)
                    else -> preview
                }
                if (shift && key is KbKey.Ch) shift = false          // one capital at a time
            }
        }
    }

    /** Pointing at a key with the cursor (or a finger) highlights it too. */
    fun highlight(row: Int, col: Int) { nav.moveTo(row, col); syncNav() }

    private fun syncNav() { highlightRow = nav.row; highlightCol = nav.col }

    /** The app went to the background or closed: nothing may stay pressed. */
    fun release() {
        cursor.cancel(sink); handler.removeCallbacks(ticker); sync()
    }

    companion object {
        /** How long a tap keeps the finger down, like a real tap. */
        const val TAP_MS = 50L
    }
}
