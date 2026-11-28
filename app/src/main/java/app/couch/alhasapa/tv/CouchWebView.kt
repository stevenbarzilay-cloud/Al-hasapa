package app.couch.alhasapa.tv

import android.content.Context
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import org.json.JSONObject

/**
 * The WebView every page runs in. On a TV the system keyboard must never pop up over the game, and the app's own keyboard opens only
 * from its button: so when the system asks for a way to type into the page, it gets none.
 *
 * (The system asks far more often than a field taking focus: every time the window gets focus back, when the app starts and when a
 * game is closed. Opening the keyboard on that request is what made it appear "for no reason".)
 */
class CouchWebView(context: Context) : WebView(context) {
    /** True on a TV: the app's keyboard types, not the system's. */
    var ownKeyboard: Boolean = false

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        if (!ownKeyboard) return super.onCreateInputConnection(outAttrs)
        // Asked of the page only to keep its own state right; nothing is opened, and the system keyboard is sent away if it came.
        val connection = super.onCreateInputConnection(outAttrs)
        if (connection != null) {
            post { (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(windowToken, 0) }
        }
        return null
    }

    /** Not a text editor as far as the system is concerned: it then has no reason to show its keyboard for this view. */
    override fun onCheckIsTextEditor(): Boolean = if (ownKeyboard) false else super.onCheckIsTextEditor()
}

/** Delivers a typed key to a page. Letters and digits are real key presses, which is what games that listen for keys expect. */
object WebTyper {
    /** Keys typed while an Enter waits a moment for the page (see [enter]): they follow it, in the order they were typed. */
    private var queued: ArrayDeque<Pair<WebView, Delivery>>? = null

    /**
     * The app's own clock for the Enter's moments, not the page's view: a view that has left the screen (the game closed just then)
     * runs nothing posted to it, and the keys would wait for good.
     */
    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    /** The longest an Enter waits for the page before it goes all the same (a page that is busy, or hung, holds back no key). */
    const val ENTER_WAIT_MS = 400L

    fun deliver(web: WebView?, d: Delivery) {
        web ?: return
        queued?.let { it.addLast(web to d); return }
        when (d) {
            // Enter goes to the game, not to a button of the game's that kept the focus (see EnterKey).
            is Delivery.Code -> if (d.keyCode == KeyEvent.KEYCODE_ENTER && !d.shift) enter(web)
            else press(web, d.keyCode, if (d.shift) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0)
            // No key code for it (Hebrew, symbols): insert it into the focused field the way a paste would.
            is Delivery.Text -> web.evaluateJavascript(insertScript(d.text), null)
            is Delivery.Hold -> {
                val t = android.os.SystemClock.uptimeMillis()
                web.dispatchKeyEvent(KeyEvent(t, t, if (d.down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, d.keyCode, 0, 0))
            }
        }
    }

    private fun press(web: WebView, code: Int, meta: Int) {
        val t = android.os.SystemClock.uptimeMillis()
        web.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0, meta))
        web.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0, meta))
    }

    /**
     * Enter (2.0.2): the focus off a game's button first, then the real key; said from within the page if it never reached it. What is
     * typed meanwhile waits for it, so a letter typed right after Enter never overtakes it.
     */
    private fun enter(web: WebView) {
        val q = ArrayDeque<Pair<WebView, Delivery>>()
        queued = q
        var went = false
        fun go(before: String) {
            if (went) return
            went = true
            press(web, KeyEvent.KEYCODE_ENTER, 0)
            if (queued === q) queued = null
            // In order; another Enter among them waits in its turn, and what follows it waits for it.
            while (q.isNotEmpty()) { val (w, next) = q.removeFirst(); deliver(w, next) }
            if (before == "IFRAME" || before.isEmpty()) return
            main.postDelayed({
                runCatching {
                    web.evaluateJavascript(EnterKey.AFTER) { r ->
                        val after = EnterKey.answer(r)
                        if (before.startsWith("moved ") || after == "sent") {
                            app.couch.alhasapa.diag.Flight.log(
                                "typing: Enter" + (if (before.startsWith("moved ")) "; the page's focus was on a ${before.removePrefix("moved ")} of the game's, moved off it" else "") +
                                    (if (after == "sent") "; it did not reach the page, so it was said from within the page" else "; the page had it"),
                            )
                        }
                    }
                }
            }, EnterKey.CHECK_MS)
        }
        val asked = runCatching { web.evaluateJavascript(EnterKey.BEFORE) { raw -> go(EnterKey.answer(raw)) } }
        if (asked.isFailure) go("") else main.postDelayed({ go("") }, ENTER_WAIT_MS)
    }

    fun insertScript(text: String): String =
        "(function(t){try{document.execCommand('insertText',false,t)}catch(e){}})(" +
            JSONObject.quote(text).replace("</", "<\\/").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029") + ")"
}
