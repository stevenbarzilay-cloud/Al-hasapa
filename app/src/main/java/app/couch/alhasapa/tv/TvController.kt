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

/** How the arrows act: they move a focus ring between buttons (the default, like Tab), or move a free paw (the mouse). */
enum class InputMode { NAV, PAW }

/** Where the focus ring is: in the page (the game, the hub), or on one of the app's own buttons. */
enum class Zone { PAGE, NATIVE }

/** A short message shown on screen when the way of controlling things changes or something is worth knowing. */
enum class TvHint { PAW_ON, NAV_ON, NO_TARGETS }

/** The page, as one of the things the arrows can move to. */
const val PAGE_ID = "page"

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

    /** Navigate (default) or paw. Switched by the paw button, which is always on screen. */
    var mode by mutableStateOf(InputMode.NAV); private set
    var zone by mutableStateOf(Zone.PAGE); private set
    var focusedId by mutableStateOf<String?>(null); private set
    var hint by mutableStateOf<TvHint?>(null); private set

    /** The name of the dock button the focus is on, shown at the foot of the dock. */
    var focusLabel by mutableStateOf<String?>(null)

    /** The dock in its narrow form (icons only) or its full one (the evening's numbers and every control). Kept across starts. */
    var slim by mutableStateOf(false)
    var onSlimChanged: (Boolean) -> Unit = {}

    fun toggleSlim() { slim = !slim; onSlimChanged(slim) }

    /** What a screen adds to the dock (the player puts the clock, the score and the game's controls there). */
    var dockExtras by mutableStateOf<(@androidx.compose.runtime.Composable androidx.compose.foundation.layout.ColumnScope.(slim: Boolean) -> Unit)?>(null)

    /** True when the remote's focus ring is on the app's button with this [id]. */
    fun isFocused(id: String): Boolean = mode == InputMode.NAV && zone == Zone.NATIVE && focusedId == id

    /** The app's own buttons, registered by their position on screen (see [tvTarget]); and the page's area. */
    private val targets = LinkedHashMap<String, NavTarget>()
    private val actions = HashMap<String, () -> Unit>()
    var pageBox: Box? = null
    var pageNav: PageNav = PageNavOverride ?: WebPageNav { TvTarget.get() }
    private var busy = false

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

    private val hintClear = Runnable { hint = null }

    private fun say(h: TvHint) { hint = h; handler.removeCallbacks(hintClear); handler.postDelayed(hintClear, HINT_MS) }

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

    /** Called from the window before the key goes anywhere else. Returns true when the key was used. */
    fun handleKey(e: KeyEvent): Boolean {
        if (!enabled) return false
        if (keyboardOpen) return handleKeyboardKey(e)
        if (e.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) openKeyboard()
            return true
        }
        return if (mode == InputMode.PAW) handlePawKey(e) else handleNavKey(e)
    }

    private fun dirOf(code: Int): Dir? = when (code) {
        KeyEvent.KEYCODE_DPAD_LEFT -> Dir.LEFT
        KeyEvent.KEYCODE_DPAD_UP -> Dir.UP
        KeyEvent.KEYCODE_DPAD_RIGHT -> Dir.RIGHT
        KeyEvent.KEYCODE_DPAD_DOWN -> Dir.DOWN
        else -> null
    }

    private fun isOk(code: Int) = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER

    // ---- navigate: the arrows walk a focus ring between buttons, OK presses ----

    private fun handleNavKey(e: KeyEvent): Boolean {
        val dir = dirOf(e.keyCode)
        if (dir != null) { if (e.action == KeyEvent.ACTION_DOWN) navigate(dir); return true }
        if (isOk(e.keyCode)) { if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) activate(); return true }
        return false
    }

    /** Move the focus one step that way: through the app's buttons, into the page, and inside the page (which scrolls at its end). */
    fun navigate(dir: Dir) {
        if (zone == Zone.PAGE && pageBox != null) {
            if (busy) return
            busy = true
            pageNav.move(dir) { r ->
                busy = false
                when (r) {
                    PageResult.Moved, PageResult.Scrolled -> {}
                    PageResult.Edge -> leavePage(dir)
                    PageResult.None -> { leavePage(dir); if (hint == null) say(TvHint.NO_TARGETS) }
                    is PageResult.Tap -> {}
                }
            }
            return
        }
        val from = focusedId?.let { targets[it]?.box }
        if (from == null) {
            // Nothing is focused yet: begin with the first button (top left), or the page if there are none.
            val first = targets.values.minWithOrNull(compareBy<NavTarget> { it.box.t }.thenBy { it.box.l })
            if (first != null) { zone = Zone.NATIVE; focusedId = first.id } else if (pageBox != null) enterPage(dir)
            return
        }
        val candidates = targets.values.toList() + listOfNotNull(pageBox?.let { NavTarget(PAGE_ID, it) })
        val next = Spatial.next(from, candidates, dir, exclude = focusedId) ?: return
        if (next.id == PAGE_ID) enterPage(dir) else { zone = Zone.NATIVE; focusedId = next.id }
    }

    private fun enterPage(dir: Dir?) {
        zone = Zone.PAGE; focusedId = null
        busy = true
        pageNav.enter(dir) { r -> busy = false; if (r == PageResult.None) say(TvHint.NO_TARGETS) }
    }

    /** The page has nothing further that way: the focus goes to the nearest of the app's buttons, if there is one. */
    private fun leavePage(dir: Dir) {
        val from = pageBox ?: return
        val next = Spatial.next(from, targets.values.toList(), dir) ?: return
        pageNav.blur()
        zone = Zone.NATIVE; focusedId = next.id
    }

    /** OK: press what is focused. */
    fun activate() {
        if (zone == Zone.NATIVE) { focusedId?.let { actions[it]?.invoke() }; return }
        if (pageBox == null) {
            // A screen with no page (the accounts list, the crash report): the first OK lands on the first button, the next one presses.
            targets.values.minWithOrNull(compareBy<NavTarget> { it.box.t }.thenBy { it.box.l })?.let { zone = Zone.NATIVE; focusedId = it.id }
            return
        }
        pageNav.activate { r ->
            when (r) {
                is PageResult.Tap -> { pageNav.tap(r.x, r.y); if (r.editable) openKeyboard(r.password) }
                else -> say(TvHint.NO_TARGETS)
            }
        }
    }

    /** Switch between navigating and the paw. Both are always reachable: the paw button is on screen in every screen. */
    fun togglePaw() {
        if (!enabled) return
        if (mode == InputMode.NAV) {
            mode = InputMode.PAW
            pageNav.blur()
            val box = focusedId?.let { targets[it]?.box }
            if (box != null) cursor.jumpTo(box.cx, box.cy)
            cursorVisible = true; sync()
            say(TvHint.PAW_ON)
        } else {
            cursor.cancel(sink); handler.removeCallbacks(ticker); sync()
            mode = InputMode.NAV
            if (zone == Zone.PAGE && pageBox != null) enterPage(null)
            say(TvHint.NAV_ON)
        }
    }

    // ---- the app's buttons register where they are, so the arrows can find them ----

    fun register(id: String, box: Box, onClick: () -> Unit) { targets[id] = NavTarget(id, box); actions[id] = onClick }

    fun unregister(id: String) {
        targets.remove(id); actions.remove(id)
        if (focusedId == id) { focusedId = null; if (pageBox != null) zone = Zone.PAGE }
    }

    // ---- the paw: arrows move a free cursor, OK taps, holding OK drags ----

    private fun handlePawKey(e: KeyEvent): Boolean {
        val dir = dirOf(e.keyCode)
        if (dir != null) {
            cursorVisible = true
            if (e.action == KeyEvent.ACTION_DOWN) {
                if (cursor.keyDown(dir, sink)) handler.post(ticker)
                sync()
            } else if (e.action == KeyEvent.ACTION_UP) cursor.keyUp(dir)
            return true
        }
        if (isOk(e.keyCode)) {
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
        const val HINT_MS = 3_500L

        /** Tests put a fake page here before the screen is built. */
        var PageNavOverride: PageNav? = null
    }
}
