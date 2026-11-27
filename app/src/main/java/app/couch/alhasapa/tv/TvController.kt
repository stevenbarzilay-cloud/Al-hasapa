package app.couch.alhasapa.tv

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import app.couch.alhasapa.diag.Flight
import java.lang.ref.WeakReference
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** How the arrows act: they move a focus ring between buttons (the default, like Tab), or move a free paw (the mouse). */
enum class InputMode { NAV, PAW }

/** Where the focus ring is: in the page (the game, the hub), or on one of the app's own buttons. */
enum class Zone { PAGE, NATIVE }

/** A short message shown on screen when the way of controlling things changes or something is worth knowing. */
enum class TvHint { PAW_ON, NAV_ON, NO_TARGETS, TYPE, PHONE, PHONE_PAW, FRAME_HIDDEN, RESULT_READY, PLAYED_AUTO, PHONE_TOUCH }

/**
 * A screen of the app's own laid over the content (the arrows stay on the app's buttons while it is up): the information for fixing a
 * fault, and the TV's own settings (the picture's size, the size of the writing, the cursor).
 */
enum class TvPanel { INFO, SETTINGS }

/** The ids of a panel's own buttons begin with this: while a panel is up, the arrows move between those only. */
const val PANEL_PREFIX = "panel:"

/** The page, as one of the things the arrows can move to. */
const val PAGE_ID = "page"

/**
 * A screen that knows its own shape (the TV's home: rows of cards, most of them beyond the edge of the screen) moves the focus itself.
 * The controller's general rule (the nearest button that way, by where the buttons are on the screen) cannot do that: a card that is
 * scrolled out of sight has no place on the screen, so in 1.9.0 the arrows could not get past the fifth card of the shelf.
 */
interface ScreenNav {
    /**
     * The arrow [dir] was pressed with the focus on the button [focused] (null: on nothing yet). The id to move to, [STAY] to stay
     * put, or null when it is not this screen's business (the controller's general rule then decides).
     */
    fun move(focused: String?, dir: Dir): String?

    /** OK was pressed on [focused]. True when this screen acted on it (a card not on the screen right now has no button to press). */
    fun ok(focused: String?): Boolean = false

    companion object {
        const val STAY = "\u0000stay"
    }
}

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

    // ---- how the TV's screens are shown (the TV's settings: see TvSettingsPanel; kept across starts by the host) ----

    /**
     * How much of the screen the picture takes, 0.85 to 1. A TV that crops the edges of the picture (most do, a little, unless set
     * otherwise) cuts off what is drawn there; the whole picture, writing and all, is then made this much smaller, with an empty
     * margin around it, until its corners show.
     */
    var pictureScale by mutableFloatStateOf(1f)

    /** How large the writing and the buttons are drawn, 0.85 to 1.25 (1: as designed). */
    var uiScale by mutableFloatStateOf(1f)

    var cursorShape by mutableStateOf(CursorShape.PAW)
    var cursorSize by mutableStateOf(CursorSize.MEDIUM)

    /** The colour of the interface (the first of us's colour), which the plain arrow cursor wears. */
    var accent by mutableStateOf(app.couch.alhasapa.ui.theme.Palette.Red)

    /** One of the settings above changed: the host keeps it. */
    var onLookChanged: () -> Unit = {}

    /** Where this screen's area begins in the window (it is smaller than the window when [pictureScale] is below 1), in px. */
    var originX = 0f
    var originY = 0f

    /** A game asked for the whole screen: the dock is put away (Back brings it back). */
    var frameHidden by mutableStateOf(false)
        private set

    // ---- the end of a game (see ResultWatch) ----

    /** The game's end screen is up (its own share or copy button is on the screen): the TV offers to save the result. */
    var resultReady by mutableStateOf(false)
        private set

    /** What "save the result" does on this screen (the player sets it): from the dock's button, or from the phone. */
    var onSaveResult: (() -> Unit)? = null

    /**
     * The game's end screen came up ([ready]) or went. When it comes: the game's frame is shown if it was put away (only for now), and
     * the focus goes to "save the result", so one OK saves it; with the paw in use, only a word at the top says so.
     */
    fun resultScreen(ready: Boolean, saveId: String?) {
        if (resultReady == ready) return
        resultReady = ready
        onResultReady(ready)
        if (!ready) return
        Flight.log("tv: the game's end screen is up; saving the result is offered")
        if (frameHidden) frameHidden = false
        if (mode == InputMode.NAV && panel == null && modalPrefix == null && saveId != null) { pageNav.blur(); zone = Zone.NATIVE; focusedId = saveId }
        say(TvHint.RESULT_READY)
    }

    /** Told when [resultReady] changes (the phone that is the keyboard offers to save the result too). */
    var onResultReady: (Boolean) -> Unit = {}

    /** The game was marked as played by itself (after a while in it). */
    fun playedByItself() = say(TvHint.PLAYED_AUTO)

    // -----------------------------------------------------------------------------------------------------------------------------------
    // Touch mode (1.9.8): a phone pictures the game, and a finger on that picture is a finger on the game here
    // -----------------------------------------------------------------------------------------------------------------------------------

    /** The game in front is played by dragging: the phones turn to touch mode by themselves. Set by the game's screen. */
    var touchGame = false

    /** Pictures of the game for the phones in touch mode: set by the game's screen on a TV (none on the home). */
    var frameSource: app.couch.alhasapa.remote.FrameSource? = null

    /** A phone shows the game in touch mode: the paw stays away meanwhile (the phone's finger points instead). */
    var phoneTouch by mutableStateOf(false)
        private set

    /** Where the phone's finger is while it is down, in this screen's coordinates (a ring is drawn there); null while it is up. */
    var phoneFinger by mutableStateOf<androidx.compose.ui.geometry.Offset?>(null)
        private set
    private var phoneDownAt = 0L
    private var phoneLastX = 0f
    private var phoneLastY = 0f

    /** A phone began showing the game in touch mode, or the last one stopped. */
    fun phoneTouching(on: Boolean) {
        if (phoneTouch == on) return
        phoneTouch = on
        if (on) say(TvHint.PHONE_TOUCH) else endPhoneTouch()
    }

    /**
     * A finger on the phone's picture of the game, at a share [x], [y] of the page's width and height: the same finger on the page here,
     * a touch of the screen's own kind (a finger's), so a drag is a drag and a swipe scrolls. A move or a lift whose touch never came
     * down here (it was lost, or the page went) is dropped.
     */
    fun remoteTouch(a: app.couch.alhasapa.remote.KeyLink.TouchAction, x: Float, y: Float) {
        val box = pageBox
        if (box == null) { endPhoneTouch(); return }
        val wx = box.l + x.coerceIn(0f, 1f) * (box.r - box.l)
        val wy = box.t + y.coerceIn(0f, 1f) * (box.b - box.t)
        val down = phoneFinger != null
        when (a) {
            app.couch.alhasapa.remote.KeyLink.TouchAction.DOWN -> {
                if (down) injectPhone(MotionEvent.ACTION_CANCEL, phoneLastX, phoneLastY)
                phoneDownAt = SystemClock.uptimeMillis()
                injectPhone(MotionEvent.ACTION_DOWN, wx, wy)
            }
            app.couch.alhasapa.remote.KeyLink.TouchAction.MOVE -> { if (!down) return; injectPhone(MotionEvent.ACTION_MOVE, wx, wy) }
            app.couch.alhasapa.remote.KeyLink.TouchAction.UP -> { if (!down) return; injectPhone(MotionEvent.ACTION_UP, wx, wy) }
            app.couch.alhasapa.remote.KeyLink.TouchAction.CANCEL -> { if (!down) return; injectPhone(MotionEvent.ACTION_CANCEL, wx, wy) }
        }
        val lifted = a == app.couch.alhasapa.remote.KeyLink.TouchAction.UP || a == app.couch.alhasapa.remote.KeyLink.TouchAction.CANCEL
        phoneFinger = if (lifted) null else androidx.compose.ui.geometry.Offset(wx - originX, wy - originY)
    }

    /** The phone's finger is lifted here if it was down (the phone went away, or the page did). */
    private fun endPhoneTouch() {
        if (phoneFinger == null) return
        injectPhone(MotionEvent.ACTION_CANCEL, phoneLastX, phoneLastY)
        phoneFinger = null
    }

    /** At [wx], [wy] in the window's own coordinates. */
    private fun injectPhone(action: Int, wx: Float, wy: Float) {
        phoneLastX = wx; phoneLastY = wy
        onInjected(action, wx - originX, wy - originY)
        val d = decor ?: return
        val ev = Touches.finger(phoneDownAt, SystemClock.uptimeMillis(), action, wx, wy)
        runCatching { d.dispatchTouchEvent(ev) }
        ev.recycle()
    }

    /**
     * A sheet of the app's own over the screen (the result sheet): while it is up, the arrows move only between the buttons whose ids
     * begin with this, and what the phone types goes to [textSink] instead of the page.
     */
    var modalPrefix: String? by mutableStateOf(null)
    var textSink: ((Delivery) -> Unit)? = null

    fun hideFrame(hidden: Boolean) {
        if (frameHidden == hidden) return
        frameHidden = hidden
        if (hidden) { if (zone == Zone.NATIVE && pageBox != null) { zone = Zone.PAGE; focusedId = null }; say(TvHint.FRAME_HIDDEN) }
        Flight.log("tv: the game's frame ${if (hidden) "hidden" else "shown"}")
    }

    /** The app's own buttons, registered by their position on screen (see [tvTarget]); and the page's area. */
    private val targets = LinkedHashMap<String, NavTarget>()
    private val actions = HashMap<String, () -> Unit>()
    var pageBox: Box? = null

    /** The screen's own way of moving the focus (the TV's home), asked before the general rule. Not while a panel lies over the screen. */
    var screenNav: ScreenNav? = null

    /** A screen that needs no dock (the TV's home: nothing there to point at or type into) hides it and takes the whole width. */
    var dockHidden by mutableStateOf(false)

    /** A screen with a page is about to show (the web hub, opened from the home): the arrows begin in the page, as when the app starts. */
    fun pageFirst() { zone = Zone.PAGE; focusedId = null }

    /**
     * The WebView of this screen (the hub, or the game): the page the arrows walk and the keyboard types into. Each screen has its own;
     * a screen that closes takes its WebView with it ([releaseWeb]), so nothing ever talks to a WebView that is gone.
     */
    private var webRef: WeakReference<WebView>? = null
    var web: WebView?
        get() = webRef?.get()
        set(value) { webRef = value?.let { WeakReference(it) } }

    fun releaseWeb(view: WebView) { if (webRef?.get() === view) webRef = null }

    var pageNav: PageNav = PageNavOverride ?: WebPageNav(web = { web })

    /** A question to the page is on its way. Never for long: an answer that does not come in [PAGE_TIMEOUT_MS] is given up on. */
    private var busy = false
    private var callSeq = 0
    private var lastMoveAt = 0L
    private val giveUp = Runnable {
        if (busy) { busy = false; Flight.log("tv: the page did not answer in ${PAGE_TIMEOUT_MS} ms") }
    }

    /** Asks the page [ask], at most one question at a time, and hands the answer to [done] unless it came too late. */
    private fun askPage(ask: ((PageResult) -> Unit) -> Unit, done: (PageResult) -> Unit) {
        val seq = ++callSeq
        busy = true
        handler.removeCallbacks(giveUp)
        handler.postDelayed(giveUp, PAGE_TIMEOUT_MS)
        try {
            ask { r ->
                if (seq != callSeq) return@ask                  // a newer question was asked since; this answer is stale
                busy = false
                handler.removeCallbacks(giveUp)
                done(r)
            }
        } catch (t: Throwable) {
            busy = false; handler.removeCallbacks(giveUp)
            Flight.log("tv: asking the page failed: $t")
        }
    }

    /** The information panel (or another of the app's own screens) over the content. */
    var panel by mutableStateOf<TvPanel?>(null); private set

    /** A screen with a long text (the information panel, the crash report) scrolls it with Up and Down: by a page, either way. */
    var textScroller: ((direction: Int) -> Unit)? = null

    fun openPanel(p: TvPanel) {
        if (!enabled) return
        if (keyboardOpen) closeKeyboard()
        pageNav.blur()
        // The information: one OK closes it again. The settings: the focus begins on the first of them.
        panel = p; zone = Zone.NATIVE; focusedId = if (p == TvPanel.SETTINGS) SETTINGS_FIRST_ID else PANEL_CLOSE_ID
        Flight.log("tv: panel $p open")
    }

    /** The name of this screen, shown on the phone when it is the TV's keyboard ("עמוד הבית", the game's name). */
    var screenTitle: String = ""

    /**
     * What a phone typed (see remote/RemoteKeys), into this screen's page: the WebView takes the keys the same way as from the app's own
     * keyboard, so a game that listens for keys gets them and a field gets its text.
     */
    fun remoteInput(list: List<Delivery>) {
        if (!enabled) return
        // A sheet of the app's own is up (the result's): the keys are for it.
        textSink?.let { sink -> list.forEach(sink); return }
        web?.let { w -> if (!w.hasFocus()) w.requestFocus() }
        // Typing begins (after a pause): if no field has the cursor, the one the highlight is on gets it, so the words land there.
        val now = SystemClock.uptimeMillis()
        if (now - lastRemoteInput > 3_000) {
            runCatching { pageNav.field { _, _ -> } }
            app.couch.alhasapa.diag.Flight.log("phone keyboard: typing began")
            // How fast the page shows each key (times only): measured in the page, summed up when the typing pauses.
            web?.let { w -> runCatching { w.evaluateJavascript(TypingProbe.INSTALL, null) } }
        }
        lastRemoteInput = now
        handler.removeCallbacks(typingPaused)
        handler.postDelayed(typingPaused, 3_000)
        for (d in list) typer(d)
    }

    /** The typing paused: how fast the page showed the keys, to the flight recorder. */
    private val typingPaused = Runnable {
        val w = web ?: return@Runnable
        val host = runCatching { android.net.Uri.parse(w.url).host }.getOrNull() ?: "the page"
        runCatching {
            w.evaluateJavascript(TypingProbe.TAKE) { raw ->
                TypingProbe.summary(TypingProbe.times(raw))?.let { app.couch.alhasapa.diag.Flight.log("typing on $host: $it") }
            }
        }
    }

    private var lastRemoteInput = Long.MIN_VALUE / 2

    // ---- the phone's touchpad drives the same paw ----

    /** The paw was turned on by the phone's touchpad (so the touchpad going away turns it off again). */
    private var phonePaw = false

    /** How many of this screen's pixels one dp of the phone's touchpad moves the paw: a full swipe across the pad is about half the TV. */
    private val padScale: Float get() = (cursor.width / 640f).coerceAtLeast(1f)

    /** The touchpad came out ([on]): the paw shows (it is the same paw as the remote's). It went back: navigation as before. */
    fun remotePointer(on: Boolean) {
        if (!enabled) return
        if (on) {
            if (keyboardOpen) closeKeyboard()
            if (mode != InputMode.PAW) { togglePaw(); phonePaw = true }
            cursorVisible = true; sync()
            say(TvHint.PHONE_PAW)
        } else {
            cursor.press(false, sink); sync()
            if (phonePaw && mode == InputMode.PAW) togglePaw()
            phonePaw = false
            endPhoneTouch()
        }
    }

    fun remoteMove(dxDp: Float, dyDp: Float) {
        if (!enabled) return
        if (mode != InputMode.PAW) remotePointer(true)
        cursor.moveBy(dxDp * padScale, dyDp * padScale, sink)
        cursorVisible = true; sync()
    }

    fun remoteClick() {
        if (!enabled) return
        if (mode != InputMode.PAW) remotePointer(true)
        cursor.tapHere(sink); sync()
    }

    fun remoteDrag(down: Boolean) {
        if (!enabled) return
        if (down && mode != InputMode.PAW) remotePointer(true)
        cursor.press(down, sink); sync()
    }

    /** Two fingers on the touchpad: the page under the paw scrolls, as with a mouse's wheel (fingers down, the page comes down). */
    fun remoteScroll(dyDp: Float) {
        if (!enabled) return
        onScrolled(dyDp)
        scrollAt(cursor.x, cursor.y, dyDp * SCROLL_GAIN * cursor.density)
    }

    /**
     * Scrolls what is under ([x], [y]) of this screen by [px] (positive: the content comes down), with a mouse's wheel.
     *
     * On the page the wheel is given to the page itself, at the point under the paw. Until 1.9.5 it was given to the window, which
     * hands it on through the app's own layers; on the way it did not always reach the page, and the page did not scroll.
     */
    private fun scrollAt(x: Float, y: Float, px: Float) {
        val wx = x + originX
        val wy = y + originY
        val notches = px / scrollFactor()
        val t = SystemClock.uptimeMillis()
        val frame = web?.parent as? View
        if (frame != null && frame.isAttachedToWindow) {
            val at = IntArray(2).also { frame.getLocationInWindow(it) }
            if (wx >= at[0] && wx < at[0] + frame.width && wy >= at[1] && wy < at[1] + frame.height) {
                val ev = Touches.wheel(t, wx - at[0], wy - at[1], notches)
                frame.dispatchGenericMotionEvent(ev)
                ev.recycle()
                return
            }
        }
        val d = decor ?: return
        val ev = Touches.wheel(t, wx, wy, notches)
        d.dispatchGenericMotionEvent(ev)
        ev.recycle()
    }

    /** How far one notch of a wheel scrolls on this device, in px. */
    private fun scrollFactor(): Float =
        decor?.let { runCatching { android.view.ViewConfiguration.get(it.context).scaledVerticalScrollFactor }.getOrNull() }?.takeIf { it > 0f } ?: (64f * cursor.density)

    /** Every scroll from the touchpad, for tests. */
    var onScrolled: (dyDp: Float) -> Unit = {}

    /** Shows [h] for a few seconds (from outside the remote too: a phone that just connected as the keyboard). */
    fun notice(h: TvHint) { if (enabled) say(h) }

    /** Puts the focus on the app's button [id] (a screen's main button, so a single OK presses it). */
    fun focus(id: String) {
        if (!enabled) return
        // While a sheet or a card of the app's own is up, nothing behind it takes the focus (the home, which keeps a focus of its own,
        // would otherwise pull it back to a game).
        modalPrefix?.let { if (!id.startsWith(it)) return }
        zone = Zone.NATIVE; focusedId = id
    }

    /** A sheet or a card is put away: the focus on its buttons is let go, and the screen behind takes it back. */
    fun endModal() {
        val m = modalPrefix ?: return
        modalPrefix = null
        if (focusedId?.startsWith(m) == true) focusedId = null
    }

    fun closePanel() {
        panel = null; textScroller = null; focusedId = null
        if (pageBox != null) zone = Zone.PAGE
    }

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
    var typer: (Delivery) -> Unit = { WebTyper.deliver(web, it) }

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
        // The cursor lives in this screen's area; the window's own coordinates begin [originX], [originY] further up and left.
        val ev = Touches.finger(downTime, SystemClock.uptimeMillis(), action, x + originX, y + originY)
        d.dispatchTouchEvent(ev)
        ev.recycle()
    }

    private val hintClear = Runnable { hint = null }

    private fun say(h: TvHint) { hint = h; handler.removeCallbacks(hintClear); handler.postDelayed(hintClear, HINT_MS) }

    private val ticker = object : Runnable {
        override fun run() {
            if (!cursor.moving) return
            cursor.tick(CursorController.TICK_MS, sink)
            // The paw held against the top or the bottom of the screen: what is under it scrolls on, faster the longer it is held.
            if (cursor.pushing != 0f) scrollAt(cursor.x, cursor.y, -cursor.pushing)
            sync()
            handler.postDelayed(this, CursorController.TICK_MS)
        }
    }

    private fun sync() { cursorX = cursor.x; cursorY = cursor.y; dragging = cursor.dragging }

    fun resize(w: Float, h: Float) { cursor.resize(w, h); sync() }

    /** Puts the cursor somewhere (the arrows get there by themselves; this is for tests and for "jump to the keyboard"). */
    fun placeCursor(x: Float, y: Float) { cursor.jumpTo(x, y); cursorVisible = true; sync() }

    /**
     * Called from the window before the key goes anywhere else. Returns true when the key was used. A mistake in here must never take the
     * app down with it: it is written down and the key goes on to the system as if the remote were not there.
     */
    fun handleKey(e: KeyEvent): Boolean {
        if (!enabled) return false
        return try {
            when {
                keyboardOpen -> handleKeyboardKey(e)
                panel != null -> handlePanelKey(e)
                mode == InputMode.PAW -> handlePawKey(e)
                else -> handleNavKey(e)
            }
        } catch (t: Throwable) {
            Flight.log("tv: key ${e.keyCode} failed: $t")
            busy = false
            false
        }
    }

    /**
     * With a panel up: Back closes it. The information: Up and Down scroll it, Left and Right and OK work its buttons. The settings:
     * the arrows move between its buttons and OK presses.
     */
    private fun handlePanelKey(e: KeyEvent): Boolean {
        val dir = dirOf(e.keyCode)
        if (e.keyCode == KeyEvent.KEYCODE_BACK) { if (e.action == KeyEvent.ACTION_UP) closePanel(); return true }
        if (panel == TvPanel.INFO && (dir == Dir.UP || dir == Dir.DOWN)) { if (e.action == KeyEvent.ACTION_DOWN) textScroller?.invoke(if (dir == Dir.DOWN) 1 else -1); return true }
        return handleNavKey(e)
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
        if (dir != null) {
            if (e.action != KeyEvent.ACTION_DOWN) return true
            // A screen of long text with no page (the crash report): Up and Down read on.
            val scroller = textScroller
            if (scroller != null && (dir == Dir.UP || dir == Dir.DOWN) && (pageBox == null || panel != null)) { scroller(if (dir == Dir.DOWN) 1 else -1); return true }
            // Holding an arrow walks on at a steady pace (each step scrolls smoothly), never faster than the page can follow.
            val now = SystemClock.uptimeMillis()
            if (e.repeatCount > 0 && now - lastMoveAt < REPEAT_MS) return true
            lastMoveAt = now
            navigate(dir)
            return true
        }
        if (isOk(e.keyCode)) { if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) activate(); return true }
        return false
    }

    /** Move the focus one step that way: through the app's buttons, into the page, and inside the page (which scrolls at its end). */
    fun navigate(dir: Dir) {
        val page = if (panel == null && modalPrefix == null) pageBox else null
        if (zone == Zone.PAGE && page != null) {
            if (busy) return
            askPage({ cb -> pageNav.move(dir, cb) }) { r ->
                when (r) {
                    PageResult.Moved, PageResult.Scrolled -> {}
                    PageResult.Edge -> leavePage(dir)
                    PageResult.None -> { leavePage(dir); if (hint == null) say(TvHint.NO_TARGETS) }
                    is PageResult.Tap -> {}
                }
            }
            return
        }
        // A panel or a sheet: the arrows stay among its own buttons, nothing behind it can be reached. (The information has only one.)
        val modal = modalPrefix
        if (panel != null || modal != null) {
            val from = focusedId?.let { targets[it]?.box }
            val own = onScreen().filter { if (panel != null) it.id.startsWith(PANEL_PREFIX) || it.id == PANEL_CLOSE_ID else it.id.startsWith(modal!!) }
            val next = if (from == null) own.minWithOrNull(compareBy<NavTarget> { it.box.t }.thenBy { -it.box.r }) else Spatial.next(from, own, dir, exclude = focusedId)
            if (next != null) { zone = Zone.NATIVE; focusedId = next.id }
            return
        }
        screenNav?.let { nav ->
            when (val next = nav.move(focusedId.takeIf { zone == Zone.NATIVE }, dir)) {
                null -> {}                                                  // not the screen's business: the general rule
                ScreenNav.STAY -> return
                else -> { zone = Zone.NATIVE; focusedId = next; return }
            }
        }
        val from = focusedId?.let { targets[it]?.box }
        if (from == null) {
            // Nothing is focused yet: begin with the first button (top left), or the page if there are none.
            val first = onScreen().minWithOrNull(compareBy<NavTarget> { it.box.t }.thenBy { it.box.l })
            if (first != null) { zone = Zone.NATIVE; focusedId = first.id } else if (page != null) enterPage(dir)
            return
        }
        val candidates = onScreen() + listOfNotNull(page?.let { NavTarget(PAGE_ID, it) })
        val next = Spatial.next(from, candidates, dir, exclude = focusedId) ?: return
        if (next.id == PAGE_ID) enterPage(dir) else { zone = Zone.NATIVE; focusedId = next.id }
    }

    private fun enterPage(dir: Dir?) {
        zone = Zone.PAGE; focusedId = null
        askPage({ cb -> pageNav.enter(dir, cb) }) { r -> if (r == PageResult.None) say(TvHint.NO_TARGETS) }
    }

    /** The page has nothing further that way: the focus goes to the nearest of the app's buttons, if there is one. */
    private fun leavePage(dir: Dir) {
        val from = pageBox ?: return
        val next = Spatial.next(from, onScreen(), dir) ?: return
        pageNav.blur()
        zone = Zone.NATIVE; focusedId = next.id
    }

    /** OK: press what is focused. */
    fun activate() {
        if (zone == Zone.NATIVE) {
            if (panel == null && modalPrefix == null && screenNav?.ok(focusedId) == true) return
            focusedId?.let { actions[it]?.invoke() }
            return
        }
        if (pageBox == null || panel != null) {
            // A screen with no page (the accounts list, the crash report): the first OK lands on the first button, the next one presses.
            onScreen().minWithOrNull(compareBy<NavTarget> { it.box.t }.thenBy { it.box.l })?.let { zone = Zone.NATIVE; focusedId = it.id }
            return
        }
        pageNav.activate { r ->
            when (r) {
                // A field is only chosen: the keyboard opens from its own button, never by itself (it covered the game when it did).
                is PageResult.Tap -> { pageNav.tap(r.x, r.y); if (r.editable) say(TvHint.TYPE) }
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
            if (box != null) cursor.jumpTo(box.cx - originX, box.cy - originY)
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

    /** The buttons that have a place on the screen: one scrolled out of sight reports an empty box, which is nowhere to go. */
    private fun onScreen(): List<NavTarget> = targets.values.filter { it.box.w > 0f && it.box.h > 0f }

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
        }
        return false
    }

    /**
     * The keyboard button: the only way the keyboard opens. The page is asked which field is being typed into (the one the focus is on,
     * if none has the cursor yet) so a password shows as dots; the WebView takes the keys.
     */
    fun keyboardButton() {
        if (!enabled) return
        if (keyboardOpen) { closeKeyboard(); return }
        openKeyboard()
        web?.let { w -> if (!w.hasFocus()) w.requestFocus() }
        runCatching { pageNav.field { _, pw -> if (keyboardOpen) password = pw } }
        Flight.log("tv: keyboard opened")
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
        busy = false; handler.removeCallbacks(giveUp)
    }

    companion object {
        /** How long a tap keeps the finger down, like a real tap. */
        const val TAP_MS = 50L
        const val HINT_MS = 3_500L

        /** The longest the remote waits for the page to answer before it moves on (a busy or frozen page must not freeze the remote). */
        const val PAGE_TIMEOUT_MS = 1_200L

        /** While an arrow is held: one step this often. */
        const val REPEAT_MS = 110L

        /** The information panel's close button. */
        const val PANEL_CLOSE_ID = "panel-close"

        /** The settings' first button, where the focus begins when they open. */
        const val SETTINGS_FIRST_ID = PANEL_PREFIX + "picture-smaller"

        /** One dp of finger on the phone's touchpad scrolls the page by this many dp. */
        const val SCROLL_GAIN = 1.5f


        /** Tests put a fake page here before the screen is built. */
        var PageNavOverride: PageNav? = null
    }
}
