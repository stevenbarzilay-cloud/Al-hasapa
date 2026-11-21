package app.couch.alhasapa.display

import org.json.JSONObject

/**
 * The script that makes a page believe it is on the screen the profile describes. It runs at document start (before the page's
 * own scripts), and again whenever the profile changes. Idempotent: running it twice is the same as once.
 *
 * What it does, and all it does:
 *  - sets the viewport (`width=` the virtual width, scale fixed), so a page designed for a phone lays out as on a phone;
 *  - declares the page light-only (the TV reads best that way);
 *  - adds the profile's CSS (hide cookie banners, "get the app" bars).
 * It never reads the page, never touches the game, and never sends anything anywhere.
 */
object DisplayScript {
    fun build(profile: DisplayProfile, layout: DisplayLayout): String {
        val content = if (layout.virtualWidth != null && layout.scale != null) {
            // Fixed scale: the page may not zoom or re-fit itself, it was sized for this box.
            val s = "%.4f".format(java.util.Locale.ROOT, layout.scale)
            "width=${layout.virtualWidth},initial-scale=$s,minimum-scale=$s,maximum-scale=$s,user-scalable=no"
        } else null
        val css = buildString {
            if (!profile.dark) append(":root{color-scheme:light only}")
            if (profile.injectCss) append(profile.css)
        }
        return """
(function(){
  var VP=${quote(content)}, CSS=${quote(css)}, LIGHT=${!profile.dark};
  function head(){return document.head||document.documentElement}
  function viewport(){
    if(VP===null) return;
    var m=document.querySelector('meta[name="viewport"]');
    if(!m){m=document.createElement('meta');m.setAttribute('name','viewport');var h=head();if(!h)return;h.appendChild(m)}
    if(m.getAttribute('content')!==VP) m.setAttribute('content',VP);
  }
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
  function apply(){viewport();light();style()}
  apply();
  if(window.__couchDisplayObs){window.__couchDisplayObs.disconnect()}
  var obs=new MutationObserver(apply);
  window.__couchDisplayObs=obs;
  obs.observe(document,{childList:true,subtree:true});
  // The page is allowed to settle; after that the observer is no longer needed (a later viewport change is the page's own).
  window.addEventListener('load',function(){setTimeout(function(){obs.disconnect()},3000)},{once:true});
})();
""".trimIndent()
    }

    /** A JS string literal (or null) for a Kotlin string: JSON quoting, with the characters that could end a script block escaped. */
    private fun quote(s: String?): String =
        if (s == null) "null" else JSONObject.quote(s).replace("</", "<\\/").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
}
