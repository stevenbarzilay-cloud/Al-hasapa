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
 *
 * One more thing, on a TV only ([build] with tv = true) and only for a site whose profile names it ([DisplayProfile.tvStorage]): it
 * switches on the site's own display preference when the people have not chosen either way. That is Krillion's "Low-power mode"
 * (the game's own setting, "Lighter graphics and fewer background animations"): Krillion draws two full-screen canvases sixty times a
 * second, which a TV box cannot keep up with (it froze the picture on one, and at its full graphics a key took too long to show for a
 * game with 25 seconds a question). Only that named key is looked at, and it is set only when it is not there: a choice made in the
 * game's own settings stays.
 *
 * And, on a TV only, for a site whose profile asks for it ([DisplayProfile.tvLight]): the site's blurs and frosted glass are taken out of
 * its style sheets as they arrive, and out of the inline styles its scripts animate. Dialed blurs dozens of things, one by 70 px over an
 * area larger than the screen, and frosts the glass behind its labels; the box's graphics chip could not draw that, the picture froze
 * and the app closed the game. Only those two effects go (colours, sizes, motion and the game stay), and never a blur that keeps
 * something locked out of sight.
 */
object DisplayScript {
    fun build(profile: DisplayProfile, tv: Boolean = false): String {
        val css = buildString {
            if (!profile.dark) append(":root{color-scheme:light only}")
            if (profile.injectCss) append(profile.css)
        }
        val prefs = if (tv && profile.tvStorage.isNotEmpty()) {
            val pairs = JSONObject().apply { profile.tvStorage.forEach { (k, v) -> put(k, v) } }.toString()
            "  try{var P=${quote(pairs)};P=JSON.parse(P);for(var k in P){if(window.localStorage.getItem(k)===null)window.localStorage.setItem(k,P[k])}}catch(e){}\n"
        } else ""
        val lightFx = if (tv && profile.tvLight) LIGHT_FX else ""
        return """
(function(){
$prefs$lightFx  var CSS=${quote(css)}, LIGHT=${!profile.dark};
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
  // The page is allowed to settle; after that the observer is no longer needed. (Run on a page that has already loaded, there is no
  // "load" left to wait for: until 1.9.3 the observer then stayed for as long as the page did, called for every change in it.)
  var done=function(){setTimeout(function(){obs.disconnect()},3000)};
  if(document.readyState==='complete') done(); else window.addEventListener('load',done,{once:true});
})();
""".trimIndent()
    }

    /** The blurs and frosted glass out (see the class comment). Idempotent: a second run only sweeps the sheets again. */
    internal const val LIGHT_FX = """  (function(){
    if(window.__couchLight){window.__couchLight();return}
    var B=/blur\([^)]*\)/g, KEEP=/lock|premium|paywall|subscri/i, n=0;
    // Frosted glass, and the filters of the drawn-on bits before and after elements (glows), by a rule of our own that wins.
    var RULE='*,*::before,*::after{backdrop-filter:none!important;-webkit-backdrop-filter:none!important}*::before,*::after{filter:none!important}';
    function own(){if(document.getElementById('couch-light'))return;var h=document.head||document.documentElement;if(!h)return;var st=document.createElement('style');st.id='couch-light';st.textContent=RULE;h.appendChild(st)}
    function cls(el){var c=el.className;return c&&c.baseVal!==undefined?c.baseVal:(c||'')}
    function keep(el){return KEEP.test(cls(el))||(el.parentElement&&KEEP.test(cls(el.parentElement)))}
    // A style rule the page's own sheet allows us to read (a sheet of the same site): its blurs go at the source.
    function fix(st,sel){
      if(!st||(sel&&KEEP.test(sel)))return;
      var f=st.getPropertyValue('filter');
      if(f&&f.indexOf('blur(')>=0){var r=f.replace(B,'').trim();st.setProperty('filter',r||'none',st.getPropertyPriority('filter'));n++}
      var w=st.getPropertyValue('will-change');if(w&&w.indexOf('filter')>=0)st.setProperty('will-change','auto');
    }
    function walk(rules,sel){for(var i=0;i<rules.length;i++){var r=rules[i],s=r.selectorText||r.name||sel||'';if(r.style)fix(r.style,s);if(r.cssRules)walk(r.cssRules,s)}}
    function sheets(){var ss=document.styleSheets;for(var i=0;i<ss.length;i++){var sh=ss[i];if(sh.__couchLight)continue;try{var rs=sh.cssRules;if(!rs)continue;walk(rs,'');sh.__couchLight=1}catch(e){sh.__couchLight=1}}}
    // Any element drawn blurred, whatever made it so (another site's sheet, an animation, a script): its blur off, on the element
    // itself, above the sheets and the animations; any other filter it has stays.
    function el(e){
      if(!e||e.nodeType!==1||keep(e))return;
      var f=getComputedStyle(e).filter;
      if(f&&f.indexOf('blur(')>=0){var r=f.replace(B,'').trim();e.style.setProperty('filter',r||'none','important');n++}
    }
    function scan(root){var all=(root||document).getElementsByTagName('*');for(var i=0;i<all.length&&i<3000;i++)el(all[i])}
    var pend=[],t=0;
    function later(x){if(x)pend.push(x);if(!t)t=setTimeout(function(){t=0;own();sheets();var p=pend;pend=[];if(p.length>40)scan();else for(var i=0;i<p.length;i++){el(p[i]);if(p[i].getElementsByTagName)scan(p[i])}},120)}
    window.__couchLight=function(){own();sheets();scan()};
    window.__couchLightCount=function(){return n};
    own();
    new MutationObserver(function(ms){
      for(var i=0;i<ms.length;i++){var m=ms[i];
        if(m.type==='attributes'){var e=m.target;if(m.attributeName==='style'&&e.style&&e.style.filter&&e.style.filter.indexOf('blur(')>=0&&e.style.getPropertyPriority('filter')!=='important'&&!keep(e)){var r=e.style.filter.replace(B,'').trim();e.style.setProperty('filter',r||'none','important');n++}else later(e)}
        else for(var j=0;j<m.addedNodes.length;j++)later(m.addedNodes[j])}
    }).observe(document,{childList:true,subtree:true,attributes:true,attributeFilter:['style','class']});
    // An animation that blurs is caught as it starts.
    document.addEventListener('animationstart',function(e){var x=e.target;requestAnimationFrame(function(){el(x)})},true);
    document.addEventListener('transitionrun',function(e){if(e.propertyName==='filter'){var x=e.target;requestAnimationFrame(function(){el(x)})}},true);
    document.addEventListener('DOMContentLoaded',function(){own();sheets();scan()});
    window.addEventListener('load',function(){sheets();scan()});
  })();
"""

    /** A JS string literal (or null) for a Kotlin string: JSON quoting, with the characters that could end a script block escaped. */
    private fun quote(s: String?): String =
        if (s == null) "null" else JSONObject.quote(s).replace("</", "<\\/").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
}
