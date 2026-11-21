package app.couch.alhasapa.tv

import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import java.lang.ref.WeakReference
import org.json.JSONObject

/**
 * The WebView every page runs in. On a TV with its own keyboard the system keyboard must not pop up over it, so when a page
 * asks for one (a text field took focus) this tells the app (which slides its own keyboard up) and declines the system one.
 */
class CouchWebView(context: Context) : WebView(context) {
    /** True on a TV: the app's keyboard types, not the system's. */
    var ownKeyboard: Boolean = false

    /** A text field took focus; [password] is true for a password field. */
    var onEditable: ((password: Boolean) -> Unit)? = null

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        if (!ownKeyboard) return super.onCreateInputConnection(outAttrs)
        super.onCreateInputConnection(outAttrs)             // fills in what kind of field it is
        val variation = outAttrs.inputType and InputType.TYPE_MASK_VARIATION
        val pw = variation == InputType.TYPE_TEXT_VARIATION_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        onEditable?.invoke(pw)
        post { (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(windowToken, 0) }
        return null
    }
}

/** The WebView the keyboard types into: the one the person is looking at (the hub, or the game). */
object TvTarget {
    private var ref: WeakReference<WebView>? = null

    fun set(web: WebView) { ref = WeakReference(web) }
    fun get(): WebView? = ref?.get()
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
