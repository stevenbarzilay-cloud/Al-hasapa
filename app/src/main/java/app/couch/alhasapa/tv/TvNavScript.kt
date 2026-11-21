package app.couch.alhasapa.tv

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.webkit.WebView
import org.json.JSONObject
import org.json.JSONTokener

/** What the page said when asked to move the focus. */
sealed interface PageResult {
    /** There is nothing to focus on the page. */
    data object None : PageResult

    /** The focus ring moved to another element. */
    data object Moved : PageResult

    /** There was nothing that way, so the page scrolled (and the ring moved to what came into view). */
    data object Scrolled : PageResult

    /** Nothing that way and the page cannot scroll that way: the focus should leave the page. */
    data object Edge : PageResult

    /** Where to tap to press the focused element, in the WebView's own pixels. */
    data class Tap(val x: Float, val y: Float, val editable: Boolean, val password: Boolean) : PageResult
}

/** The page as a thing the arrows can walk through. Replaceable in tests. */
interface PageNav {
    fun enter(dir: Dir?, cb: (PageResult) -> Unit)
    fun move(dir: Dir, cb: (PageResult) -> Unit)
    fun activate(cb: (PageResult) -> Unit)
    fun blur()
    fun tap(x: Float, y: Float)
}

/**
 * Moves a focus ring between the buttons, links and fields of a page, in the direction of the arrow pressed, and scrolls when there
 * is nothing further. The page is only asked where its clickable things are (their positions on screen), never what they say, and the
 * press itself is a real tap that the app makes at the element's centre, so every game gets the touch events it expects.
 *
 * Things it will not land on: hidden, disabled, covered by something else, or too small to press. A parent that contains another
 * target gives way to it (the leaf is what you mean).
 */
object TvNavScript {
    val JS: String = """
(function(){
  if(window.__tvnav) return;
  var SEL='a[href],button,input:not([type=hidden]),select,textarea,summary,[tabindex]:not([tabindex="-1"]),[role=button],[role=link],[role=tab],[role=menuitem],[role=checkbox],[role=radio],[role=switch],[contenteditable=""],[contenteditable=true],[onclick]';
  var cur=null, ring=null, dirty=true, cache=[], light=false, looping=false;
  function j(o){return JSON.stringify(o)}
  function R(e){var r=e.getBoundingClientRect();return {l:r.left,t:r.top,r:r.right,b:r.bottom,cx:(r.left+r.right)/2,cy:(r.top+r.bottom)/2}}
  function inView(r){return r.r>0&&r.b>0&&r.l<innerWidth&&r.t<innerHeight}
  function usable(e){
    if(e.disabled||e.getAttribute('aria-disabled')==='true') return false;
    var s=getComputedStyle(e);
    if(s.visibility==='hidden'||s.display==='none'||s.pointerEvents==='none'||+s.opacity===0) return false;
    var r=e.getBoundingClientRect();
    if(r.width<10||r.height<10||!inView(R(e))) return false;
    var x=Math.min(Math.max(r.left+r.width/2,1),innerWidth-1), y=Math.min(Math.max(r.top+r.height/2,1),innerHeight-1);
    var top=document.elementFromPoint(x,y);
    return !!top&&(top===e||e.contains(top)||top.contains(e));
  }
  function collect(){
    if(!dirty&&cache.length) return cache;
    var t0=performance.now(), seen=new Set(), out=[];
    function add(e){ if(seen.has(e)) return; seen.add(e); if(usable(e)) out.push(e); }
    document.querySelectorAll(SEL).forEach(add);
    if(!light&&document.body){
      var all=document.body.getElementsByTagName('*'), n=Math.min(all.length,6000);
      for(var i=0;i<n;i++){
        var e=all[i]; if(seen.has(e)) continue;
        var r=e.getBoundingClientRect(); if(r.width<10||r.height<10||!inView(R(e))) continue;
        if(getComputedStyle(e).cursor==='pointer') add(e);
      }
    }
    if(performance.now()-t0>150) light=true;       // a slow page: from now on only the obvious things
    cache=out.filter(function(e){ return !out.some(function(o){ return o!==e&&e.contains(o); }); });
    dirty=false; return cache;
  }
  function ensureRing(){
    if(ring&&ring.isConnected) return ring;
    ring=document.createElement('div'); ring.id='couch-tv-ring';
    ring.style.cssText='position:fixed;z-index:2147483647;pointer-events:none;border:4px solid #D94E38;border-radius:12px;box-shadow:0 0 0 3px #F2EBDD,0 0 14px 6px rgba(217,78,56,.45);box-sizing:border-box;left:0;top:0;width:0;height:0;display:none';
    (document.body||document.documentElement).appendChild(ring); return ring;
  }
  function place(){
    if(!cur||!cur.isConnected){ if(ring) ring.style.display='none'; return; }
    var r=cur.getBoundingClientRect(), g=ensureRing();
    g.style.display='block'; g.style.left=(r.left-5)+'px'; g.style.top=(r.top-5)+'px'; g.style.width=(r.width+10)+'px'; g.style.height=(r.height+10)+'px';
  }
  function loop(){ if(!cur){ looping=false; return; } place(); requestAnimationFrame(loop); }
  function setCur(e){
    cur=e; var r=e.getBoundingClientRect();
    if(r.top<0||r.left<0||r.bottom>innerHeight||r.right>innerWidth){ try{ e.scrollIntoView({block:'nearest',inline:'nearest'}); }catch(x){} }
    place(); if(!looping){ looping=true; requestAnimationFrame(loop); }
  }
  var rtl=false; try{ rtl=getComputedStyle(document.documentElement).direction==='rtl'; }catch(x){}
  function entry(ts,dir){
    var best=null,bs=1e18;
    ts.forEach(function(e){
      var r=R(e), s;
      if(dir==='down'||!dir) s=r.t*10+(rtl?-r.r:r.l)*0.01;
      else if(dir==='up') s=-r.b*10+(rtl?-r.r:r.l)*0.01;
      else if(dir==='right') s=r.l*10+r.t*0.01;
      else s=-r.r*10+r.t*0.01;
      if(s<bs){bs=s;best=e}
    });
    return best;
  }
  function lies(a,b,d){ return d==='right'?b.cx>a.cx+1:d==='left'?b.cx<a.cx-1:d==='down'?b.cy>a.cy+1:b.cy<a.cy-1; }
  function overlap(a,b,d){ return (d==='left'||d==='right')?Math.max(0,Math.min(a.b,b.b)-Math.max(a.t,b.t)):Math.max(0,Math.min(a.r,b.r)-Math.max(a.l,b.l)); }
  function clear(a,b,d){ return d==='right'?b.l>=a.r:d==='left'?b.r<=a.l:d==='down'?b.t>=a.b:b.b<=a.t; }
  function score(a,b,d){
    var along=d==='right'?Math.max(0,b.l-a.r):d==='left'?Math.max(0,a.l-b.r):d==='down'?Math.max(0,b.t-a.b):Math.max(0,a.t-b.b);
    var off=(d==='left'||d==='right')?Math.abs(b.cy-a.cy):Math.abs(b.cx-a.cx);
    var ca=(d==='left'||d==='right')?Math.abs(b.cx-a.cx):Math.abs(b.cy-a.cy);
    return along+0.35*ca+2*off;
  }
  function nearest(ts,from,d){
    var fr=R(from), c=[];
    ts.forEach(function(e){ if(e===from) return; var r=R(e); if(lies(fr,r,d)) c.push({e:e,r:r}); });
    if(!c.length) return null;
    var lined=c.filter(function(x){return overlap(fr,x.r,d)>0}), pool=lined.length?lined:c;
    var beyond=pool.filter(function(x){return clear(fr,x.r,d)}); if(beyond.length) pool=beyond;
    var best=null,bs=1e18; pool.forEach(function(x){ var s=score(fr,x.r,d); if(s<bs){bs=s;best=x.e} }); return best;
  }
  function scrollable(e,d){
    for(var n=e;n&&n!==document.documentElement;n=n.parentElement){
      var s=getComputedStyle(n), vert=(d==='up'||d==='down');
      var ov=vert?s.overflowY:s.overflowX; if(ov!=='auto'&&ov!=='scroll') continue;
      if(vert?n.scrollHeight>n.clientHeight+2:n.scrollWidth>n.clientWidth+2) return n;
    }
    return null;
  }
  function doScroll(d){
    var dx=d==='right'?1:d==='left'?-1:0, dy=d==='down'?1:d==='up'?-1:0;
    var holder=cur?scrollable(cur,d):null, se=holder||document.scrollingElement||document.documentElement;
    var b0=se.scrollLeft, b1=se.scrollTop;
    var h=holder?holder.clientHeight:innerHeight, w=holder?holder.clientWidth:innerWidth;
    se.scrollBy?se.scrollBy(dx*w*0.6,dy*h*0.6):window.scrollBy(dx*w*0.6,dy*h*0.6);
    if(se.scrollLeft!==b0||se.scrollTop!==b1){ dirty=true; return true; }
    if(holder){ return false; }
    return false;
  }
  addEventListener('scroll',function(){dirty=true},true);
  addEventListener('resize',function(){dirty=true});
  try{ new MutationObserver(function(){dirty=true}).observe(document.documentElement,{subtree:true,childList:true,attributes:true,attributeFilter:['class','style','hidden','disabled']}); }catch(x){}
  window.__tvnav={
    enter:function(dir){ dirty=true; var ts=collect(); if(!ts.length) return j({k:'none'}); setCur(entry(ts,dir)); return j({k:'moved'}); },
    move:function(dir){
      var ts=collect();
      if(!ts.length){
        if(doScroll(dir)){ dirty=true; return j({k:'scrolled'}); }
        var se=document.scrollingElement||document.documentElement;
        return j({k:(se.scrollHeight>se.clientHeight+2||se.scrollWidth>se.clientWidth+2)?'edge':'none'});
      }
      if(!cur||!cur.isConnected||!inView(R(cur))||ts.indexOf(cur)<0){
        if(cur&&cur.isConnected&&inView(R(cur))){ var near=nearest(ts.concat([cur]),cur,dir); if(near){ setCur(near); return j({k:'moved'}); } }
        else { setCur(entry(ts,dir)); return j({k:'moved'}); }
      }
      var pick=nearest(ts,cur,dir);
      if(pick){ setCur(pick); return j({k:'moved'}); }
      if(doScroll(dir)){ dirty=true; var t2=collect(); if(t2.length){ var p2=nearest(t2.concat(cur&&t2.indexOf(cur)<0?[]:[]),cur&&t2.indexOf(cur)>=0?cur:null,dir)||entry(t2,dir); setCur(p2||entry(t2,dir)); } return j({k:'scrolled'}); }
      return j({k:'edge'});
    },
    activate:function(){
      if(!cur||!cur.isConnected) return j({k:'none'});
      var r=cur.getBoundingClientRect(), d=window.devicePixelRatio||1;
      var x=Math.min(Math.max(r.left+r.width/2,1),innerWidth-1), y=Math.min(Math.max(r.top+r.height/2,1),innerHeight-1);
      var ed=/^(INPUT|TEXTAREA|SELECT)${'$'}/.test(cur.tagName)||cur.isContentEditable;
      return j({k:'tap',x:x*d,y:y*d,ed:!!ed,pw:cur.type==='password'});
    },
    blur:function(){ cur=null; if(ring) ring.style.display='none'; return j({k:'ok'}); }
  };
})();
""".trimIndent()
}

/** [PageNav] over a WebView. */
class WebPageNav(private val web: () -> WebView?) : PageNav {
    private val handler = Handler(Looper.getMainLooper())

    private fun call(expr: String, cb: (PageResult) -> Unit) {
        val w = web() ?: return cb(PageResult.None)
        w.evaluateJavascript("(function(){" + TvNavScript.JS + ";return window.__tvnav." + expr + "})()") { raw -> cb(parse(raw)) }
    }

    override fun enter(dir: Dir?, cb: (PageResult) -> Unit) = call("enter(" + (dir?.let { "'" + it.name.lowercase() + "'" } ?: "null") + ")", cb)
    override fun move(dir: Dir, cb: (PageResult) -> Unit) = call("move('" + dir.name.lowercase() + "')", cb)
    override fun activate(cb: (PageResult) -> Unit) = call("activate()", cb)
    override fun blur() { web()?.evaluateJavascript("(function(){return window.__tvnav&&window.__tvnav.blur()})()", null) }

    /** A real tap inside the WebView, in its own pixels (no matter how the WebView is scaled or moved on screen). */
    override fun tap(x: Float, y: Float) {
        val w = web() ?: return
        val t = SystemClock.uptimeMillis()
        fun send(action: Int, at: Long) {
            val ev = MotionEvent.obtain(t, at, action, x, y, 0)
            ev.source = InputDevice.SOURCE_TOUCHSCREEN
            w.dispatchTouchEvent(ev); ev.recycle()
        }
        send(MotionEvent.ACTION_DOWN, t)
        handler.postDelayed({ send(MotionEvent.ACTION_UP, SystemClock.uptimeMillis()) }, 50)
    }

    companion object {
        fun parse(raw: String?): PageResult {
            val s = runCatching { JSONTokener(raw ?: return PageResult.None).nextValue() as? String }.getOrNull() ?: return PageResult.None
            val o = runCatching { JSONObject(s) }.getOrNull() ?: return PageResult.None
            return when (o.optString("k")) {
                "moved" -> PageResult.Moved
                "scrolled" -> PageResult.Scrolled
                "edge" -> PageResult.Edge
                "tap" -> PageResult.Tap(o.optDouble("x").toFloat(), o.optDouble("y").toFloat(), o.optBoolean("ed"), o.optBoolean("pw"))
                else -> PageResult.None
            }
        }
    }
}
