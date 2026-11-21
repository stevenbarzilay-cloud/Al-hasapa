package app.couch.alhasapa.display

import org.json.JSONObject

/**
 * The small script a display profile injects: light mode and the profile's banner CSS. It runs at document start (before the
 * page's own scripts), and again whenever the profile changes. Idempotent: running it twice is the same as once.
 *
 * What it does, and all it does:
 *  - declares the page light-only (the TV reads best that way);
 *  - adds the profile's CSS (hide cookie banners, "get the app" bars).
 * It does NOT size the page. In the first 1.0.0 it also set the viewport meta to make the page lay out at a phone's width; on the
 * real WebView that was ignored and the page laid out at the narrow box's width, cutting the game off. The page is now sized the
 * way a real phone does it: the WebView itself is that big (see ScaledFrame) and is shrunk to fit.
 * It never reads the page, never touches the game, and never sends anything anywhere.
 */
object DisplayScript {
    fun build(profile: DisplayProfile): String {
        val css = buildString {
            if (!profile.dark) append(":root{color-scheme:light only}")
            if (profile.injectCss) append(profile.css)
        }
        return """
(function(){
  var CSS=${quote(css)}, LIGHT=${!profile.dark};
  function head(){return document.head||document.documentElement}
  function light(){
    if(!LIGHT) return;
    var m=document.querySelector('meta[name="color-scheme"]');
    if(!m){m=document.createElement('meta');m.setAttribute('name','color-scheme');var h=head();if(!h)return;h.appendChild(m)}
    if(m.getAttribute('content')!=='light') m.setAttribute('content','light');
  }
  function style(){
    var s=document.getElementById('couch-display-css');
    if(!CSS){if(s)s.remove();return}
    if(!s){s=document.createElement('style');s.id='couch-display-css';var h=head();if(!h)return;h.appendChild(s)}
    if(s.textContent!==CSS) s.textContent=CSS;
  }
  function apply(){light();style()}
  apply();
  if(window.__couchDisplayObs){window.__couchDisplayObs.disconnect()}
  var obs=new MutationObserver(apply);
  window.__couchDisplayObs=obs;
  obs.observe(document,{childList:true,subtree:true});
  // The page is allowed to settle; after that the observer is no longer needed.
  window.addEventListener('load',function(){setTimeout(function(){obs.disconnect()},3000)},{once:true});
})();
""".trimIndent()
    }

    /** A JS string literal (or null) for a Kotlin string: JSON quoting, with the characters that could end a script block escaped. */
    private fun quote(s: String?): String =
        if (s == null) "null" else JSONObject.quote(s).replace("</", "<\\/").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
}
