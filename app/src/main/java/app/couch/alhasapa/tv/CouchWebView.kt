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
    fun deliver(web: WebView?, d: Delivery) {
        web ?: return
        when (d) {
            is Delivery.Code -> {
                val t = android.os.SystemClock.uptimeMillis()
                val meta = if (d.shift) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0
                web.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, d.keyCode, 0, meta))
                web.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, d.keyCode, 0, meta))
            }
            // No key code for it (Hebrew, symbols): insert it into the focused field the way a paste would.
            is Delivery.Text -> web.evaluateJavascript(insertScript(d.text), null)
        }
    }

    fun insertScript(text: String): String =
        "(function(t){try{document.execCommand('insertText',false,t)}catch(e){}})(" +
            JSONObject.quote(text).replace("</", "<\\/").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029") + ")"
}
