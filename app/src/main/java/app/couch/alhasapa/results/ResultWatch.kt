package app.couch.alhasapa.results

/**
 * The end of a game, seen from outside it (1.9.7, on the TV): most games end on a screen with their own "Share" or "Copy" button, which
 * is where the result can be had. The app looks every few seconds whether such a button is on the screen; when it is, the TV offers
 * "שמירת התוצאה" (in the game's dock, and on the phone that is its keyboard). Pressing it presses that very button of the game's, the
 * way a finger would (a real touch, so the game copies its result as it always does); the capture (CaptureScript) catches what it
 * copies and the result sheet opens with it.
 *
 * What it looks at, and all it looks at: the words on the page's buttons and links, to find one that says share or copy, and where it
 * is. It reads nothing else, sends nothing anywhere, and changes nothing on the page.
 */
object ResultWatch {
    /** Runs at document start, in every frame of a game's page. */
    const val INSTALL = """(function(){
  if(window.__couchResult) return;
  var RX=/^\s*(share|share it|share your results?|share results?|share your score|share score|copy|copy results?|copy score|copy to clipboard|שתפו|שתף|שיתוף|לשתף|העתק|העתיקו|העתקה|להעתיק)(\s|!|$)/i;
  function seen(e){
    var r=e.getBoundingClientRect(); if(r.width<24||r.height<16) return null;
    if(r.bottom<=0||r.right<=0||r.top>=innerHeight||r.left>=innerWidth) return null;
    var s=getComputedStyle(e); if(s.visibility==='hidden'||s.display==='none'||parseFloat(s.opacity)===0) return null;
    return r;
  }
  function find(){
    var all=document.querySelectorAll('button,[role="button"],a');
    for(var i=0;i<all.length&&i<800;i++){
      var e=all[i], t=(e.innerText||e.textContent||'').replace(/\s+/g,' ').trim();
      if(!t||t.length>40||!RX.test(t)) continue;
      var r=seen(e); if(r) return {e:e,r:r};
    }
    return null;
  }
  var W={
    ready:function(){try{return find()?1:0}catch(x){return 0}},
    at:function(){try{var f=find(); if(!f) return ''; var d=window.devicePixelRatio||1;
      return Math.round((f.r.left+f.r.width/2)*d)+','+Math.round((f.r.top+f.r.height/2)*d)}catch(x){return ''}}
  };
  try{Object.defineProperty(window,'__couchResult',{value:W})}catch(x){window.__couchResult=W}
})();"""

    /**
     * The end screen's short lines that speak of a result (tries, mistakes, hints, a time, a score out of something, a percentage of the
     * players), for a game that copies nothing (Haaretz's): read once, when "save the result" is pressed, from the page and the frames
     * of the same site inside it. Long lines (the article around a game) are left out. What is read is turned into numbers at once
     * (HebrewResult) and the lines themselves are not kept.
     */
    const val LINES = """(function(){try{
  var RX=/ניסיונ|נסיונ|ניחוש|טעות|טעויות|רמז|שחקנ|משתתפ|פותר|גולש|שניות|דקות|מתוך|פתרת|פתרנו|הצלחת|הצלחנו|נגמרו|%|[0-9]+ *\/ *[0-9]+|[0-9]{1,2}:[0-9]{2}/;
  var out=[];
  function take(doc,depth){
    var t=(doc.body&&doc.body.innerText)||'', ls=t.split(/\n+/);
    for(var i=0;i<ls.length&&out.length<24;i++){var l=ls[i].replace(/\s+/g,' ').trim(); if(l.length>1&&l.length<=120&&RX.test(l)) out.push(l)}
    if(depth<2){var fr=doc.querySelectorAll('iframe'); for(var j=0;j<fr.length;j++){try{var d=fr[j].contentDocument; if(d) take(d,depth+1)}catch(e){}}}
  }
  take(document,0);
  return out.join('\n');
}catch(e){return ''}})()"""

    /** What [LINES] answered (evaluateJavascript hands back a JSON string), as plain text. */
    fun lines(raw: String?): String = runCatching { org.json.JSONTokener(raw ?: "").nextValue() as? String }.getOrNull().orEmpty()

    /** 1 when a share or copy button is on the screen, 0 when not ("" when the script is not there). */
    const val READY = "(function(){try{return window.__couchResult?String(window.__couchResult.ready()):''}catch(e){return ''}})()"

    /** Where that button is, in the WebView's own pixels: "x,y", or "" when there is none. */
    const val AT = "(function(){try{return window.__couchResult?window.__couchResult.at():''}catch(e){return ''}})()"

    fun ready(raw: String?): Boolean = raw?.trim('"') == "1"

    fun at(raw: String?): Pair<Float, Float>? {
        val p = raw?.trim('"')?.split(',')?.mapNotNull { it.trim().toFloatOrNull() } ?: return null
        return if (p.size == 2 && p[0] >= 0f && p[1] >= 0f) p[0] to p[1] else null
    }

    /** The game was open this long before an end screen is believed (a share button in a page's header while playing is not one). */
    const val SETTLE_MS = 20_000L

    /** How often the TV looks. */
    const val LOOK_MS = 2_500L
}
