package app.couch.alhasapa.player

import android.webkit.WebView
import app.couch.alhasapa.diag.Flight
import java.util.WeakHashMap

/**
 * What kind of page a game is, in numbers only, written to the flight recorder a few seconds after it loads on the TV: how many
 * videos (and how many are playing), canvases and embedded frames it has. A report about a page that held the drawing stuck then
 * says whether video or a canvas was in it. Nothing else in the page is read.
 */
object PageProbe {
    internal const val JS = "(function(){try{var v=document.querySelectorAll('video'),p=0;for(var i=0;i<v.length;i++){if(!v[i].paused)p++;}" +
        "return v.length+','+p+','+document.querySelectorAll('canvas').length+','+document.querySelectorAll('iframe').length;}catch(e){return '';}})()"
    private const val DELAY_MS = 2_500L
    private val pending = WeakHashMap<WebView, Runnable>()

    /** Once per page: a second "loaded" within the delay (redirects, frames) replaces the first. */
    fun later(view: WebView) {
        pending.remove(view)?.let { view.removeCallbacks(it) }
        val r = Runnable {
            pending.remove(view)
            if (view.isAttachedToWindow) runCatching { view.evaluateJavascript(JS) { raw -> describe(raw)?.let { Flight.log("player: the page has $it") } } }
        }
        pending[view] = r
        view.postDelayed(r, DELAY_MS)
    }

    /** "\"2,1,3,0\"" (as evaluateJavascript returns it) -> "2 videos (1 playing), 3 canvases, no frames"; null for anything else. */
    fun describe(raw: String?): String? {
        val n = raw?.trim('"')?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.size == 4 } ?: return null
        fun count(k: Int, one: String, many: String) = when (k) { 0 -> "no $many"; 1 -> "1 $one"; else -> "$k $many" }
        val videos = count(n[0], "video", "videos") + if (n[0] > 0) " (${n[1]} playing)" else ""
        return "$videos, ${count(n[2], "canvas", "canvases")}, ${count(n[3], "frame", "frames")}"
    }
}
