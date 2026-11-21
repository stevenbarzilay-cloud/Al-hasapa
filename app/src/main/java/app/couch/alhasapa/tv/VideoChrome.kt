package app.couch.alhasapa.tv

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.widget.FrameLayout

/**
 * Full-screen video for a page: when a game's video goes full screen the WebView hands over a view, which is shown over the
 * whole window until the page (or Back) leaves full screen. Without this a video's full-screen button does nothing.
 */
class VideoChrome(private val activity: Activity) : WebChromeClient() {
    private var shown: View? = null
    private var callback: CustomViewCallback? = null

    val isShowing: Boolean get() = shown != null

    override fun onShowCustomView(view: View, callback: CustomViewCallback) {
        if (shown != null) { callback.onCustomViewHidden(); return }
        shown = view
        this.callback = callback
        (activity.window.decorView as ViewGroup).addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    override fun onHideCustomView() {
        val v = shown ?: return
        (v.parent as? ViewGroup)?.removeView(v)
        shown = null
        callback?.onCustomViewHidden()
        callback = null
    }
}
