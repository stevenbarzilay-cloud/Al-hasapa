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

    /** The focus moved to another element. */
    data object Moved : PageResult

    /** There was nothing that way within reach, so the page scrolled on (the focus stays, or comes back with the next arrow). */
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

    /** The field the keyboard is about to type into (the focused one, or the one the focus is on): is there one, and is it a password? */
    fun field(cb: (editable: Boolean, password: Boolean) -> Unit) = cb(false, false)
}

/**
 * Moves the focus between the buttons, links and fields of a page, in the direction of the arrow pressed, like a TV app moves between
 * its tiles: the element itself takes the colour (an attribute the page's own styles or ours paint, no box drawn around it), and the
 * page glides so the focused element stays comfortably in view (towards the middle, never at the very edge). The next element below the
 * fold can be reached; when the next one is far away the page scrolls on first, so what lies between can be read.
 *
 * The page is only asked where its clickable things are, never what they say, and the press itself is a real tap that the app makes at
 * the element's centre, so every game gets the touch events it expects.
 *
 * Kept light for a weak TV box: nothing runs while nothing is pressed (no loop per frame, no work on scroll), the list of clickable
 * things is kept until the page changes, and only the few best candidates are examined closely.
 *
 * Written for old WebViews too (no newer syntax), and without a dollar sign (this is a Kotlin template).
 */
object TvNavScript {
    /** Bumped whenever the script changes, so a page that still has an older one gets the new one. */
    const val VERSION = 2

    val JS: String = """
(function(){
  if(window.__tvnav&&window.__tvnav.v===2&&window.__tvnav.doc===document) return;
  var ATTR='data-couch-focus';
  var SEL='a[href],button,input:not([type=hidden]),select,textarea,summary,[tabindex]:not([tabindex="-1"]),[role=button],[role=link],[role=tab],[role=menuitem],[role=option],[role=checkbox],[role=radio],[role=switch],[contenteditable=""],[contenteditable=true],[onclick]';
  var NOTEXT={checkbox:1,radio:1,button:1,submit:1,reset:1,range:1,color:1,file:1,image:1,hidden:1};
  var cur=null,last=null,list=null,listAt=0,light=false,observer=null,observed=null,anims=[],collectMs=0,rtlModel=null;
  function now(){return (window.performance&&performance.now)?performance.now():Date.now()}
  function j(o){return JSON.stringify(o)}
  function css(){
    if(document.getElementById('couch-tv-style')) return;
    var s=document.createElement('style'); s.id='couch-tv-style';
    s.textContent='['+ATTR+']{outline:none!important;box-shadow:inset 0 0 0 3px rgba(217,78,56,.95),inset 0 0 0 999px rgba(217,78,56,.24)!important}'+
      'img['+ATTR+'],video['+ATTR+'],canvas['+ATTR+'],iframe['+ATTR+'],svg['+ATTR+']{outline:4px solid rgba(217,78,56,.95)!important;outline-offset:-4px!important}';
    (document.head||document.documentElement).appendChild(s);
  }
  /* The list is made again when the page changes. A page that replaced its whole document gets watched afresh. */
  function watch(){
    var de=document.documentElement;
    if(!window.MutationObserver||(observer&&observed===de)) return;
    try{
      if(observer) observer.disconnect();
      list=null; observed=de; anims=[]; cur=null; last=null;          /* a new document: nothing of the old one counts */
      observer=new MutationObserver(function(){list=null});
      observer.observe(de,{subtree:true,childList:true,attributes:true,
        attributeFilter:['class','hidden','disabled','href','role','tabindex','aria-hidden','aria-disabled','contenteditable','onclick']});
    }catch(x){}
  }
  function R(e){var r=e.getBoundingClientRect();return {l:r.left,t:r.top,r:r.right,b:r.bottom,w:r.width,h:r.height,cx:r.left+r.width/2,cy:r.top+r.height/2}}
  function inView(r){return r.r>0&&r.b>0&&r.l<innerWidth&&r.t<innerHeight}
  /* Where an element will be once the glides under way have ended (a key pressed quickly again must not judge by a half-way picture). */
  function pinned(e){ for(var p=e;p;p=p.offsetParent){ if(getComputedStyle(p).position==='fixed') return true; } return false; }
  function F(e){
    var r=R(e);
    for(var i=0;i<anims.length;i++){
      var a=anims[i]; if(!a.run) continue;
      if(a.n===null?pinned(e):!a.n.contains(e)) continue;          /* what is pinned in place does not move with the page */
      var dx=a.tx-pos(a.n,'x'),dy=a.ty-pos(a.n,'y');
      r={l:r.l-dx,t:r.t-dy,r:r.r-dx,b:r.b-dy,w:r.w,h:r.h,cx:r.cx-dx,cy:r.cy-dy};
    }
    return r;
  }

  /* ---- what can be pressed: the real controls, and areas that show a pointer; the innermost one of each nest ---- */
  function collect(){
    var t=now();
    if(list&&t-listAt<4000&&(!list.length||list[0].isConnected)) return list;
    var cand=[],seen=new Set(),q=document.querySelectorAll(SEL),i;
    for(i=0;i<q.length;i++){cand.push(q[i]);seen.add(q[i])}
    if(!light&&document.body){
      var all=document.body.getElementsByTagName('*'),n=Math.min(all.length,4000);
      for(i=0;i<n;i++){
        var e=all[i]; if(seen.has(e)) continue;
        if(getComputedStyle(e).cursor!=='pointer') continue;
        var p=e.parentElement; if(p&&getComputedStyle(p).cursor==='pointer') continue;   /* only where the pointer begins */
        cand.push(e);
      }
    }
    var inner=new Set();
    for(i=0;i<cand.length;i++){
      for(var up=cand[i].parentElement;up&&up!==document.body&&up!==document.documentElement;up=up.parentElement){ if(inner.has(up)) break; inner.add(up); }
    }
    var out=[]; for(i=0;i<cand.length;i++){ if(!inner.has(cand[i])) out.push(cand[i]); }
    collectMs=now()-t; if(collectMs>120) light=true;            /* a heavy page: from now on only the real controls */
    list=out; listAt=now(); return list;
  }
  function shown(e){
    if(!e||!e.isConnected) return false;
    if(e.disabled||e.getAttribute('aria-disabled')==='true'||e.getAttribute('aria-hidden')==='true') return false;
    var r=e.getBoundingClientRect(); if(r.width<8||r.height<8) return false;
    var s=getComputedStyle(e);
    return s.visibility==='visible'&&s.display!=='none'&&s.pointerEvents!=='none'&&+s.opacity>0.05;
  }
  function onTop(e,r){
    var x=Math.min(Math.max(r.cx,1),innerWidth-1),y=Math.min(Math.max(r.cy,1),innerHeight-1),top=document.elementFromPoint(x,y);
    return !!top&&(top===e||e.contains(top)||top.contains(e));
  }
  /* Off screen it must be something that scrolls into view: inside a scrolling box that is itself on screen, or in the page itself; not cut
     off by a box that does not scroll, and not in something pinned in place. */
  function reachable(e,r){
    if(inView(r)) return onTop(e,r);
    for(var n=e.parentElement;n&&n!==document.body&&n!==document.documentElement;n=n.parentElement){
      var s=getComputedStyle(n);
      var sy=(s.overflowY==='auto'||s.overflowY==='scroll')&&n.scrollHeight>n.clientHeight+1;
      var sx=(s.overflowX==='auto'||s.overflowX==='scroll')&&n.scrollWidth>n.clientWidth+1;
      if(sy||sx) return inView(R(n));
      if(s.position==='fixed') return false;
      if(s.overflowX!=='visible'||s.overflowY!=='visible'){ var c=R(n); if(r.b<=c.t||r.t>=c.b||r.r<=c.l||r.l>=c.r) return false; }
    }
    return r.r>-2&&r.l<innerWidth+2;                               /* the page scrolls up and down; sideways it rarely does */
  }
  function usable(e,r){return shown(e)&&reachable(e,r)}

  /* ---- scrolling: the box that scrolls each way, and a gentle animation that can be steered while it runs ---- */
  function scrollerOf(e,ax){
    for(var n=e.parentElement;n&&n!==document.body&&n!==document.documentElement;n=n.parentElement){
      var s=getComputedStyle(n),ov=ax==='y'?s.overflowY:s.overflowX;
      if((ov==='auto'||ov==='scroll')&&(ax==='y'?n.scrollHeight>n.clientHeight+1:n.scrollWidth>n.clientWidth+1)) return n;
    }
    return null;
  }
  function root(){return document.scrollingElement||document.documentElement}
  function view(sc){
    if(!sc) return {l:0,t:0,r:innerWidth,b:innerHeight,w:innerWidth,h:innerHeight};
    var b=sc.getBoundingClientRect(),l=Math.max(0,b.left+sc.clientLeft),t=Math.max(0,b.top+sc.clientTop);
    var r=Math.min(innerWidth,b.left+sc.clientLeft+sc.clientWidth),bt=Math.min(innerHeight,b.top+sc.clientTop+sc.clientHeight);
    return {l:l,t:t,r:r,b:bt,w:Math.max(1,r-l),h:Math.max(1,bt-t)};
  }
  function model(){
    if(rtlModel) return rtlModel;
    try{
      var d=document.createElement('div'),c=document.createElement('div');
      d.style.cssText='direction:rtl;overflow:scroll;width:10px;height:10px;position:absolute;top:-99px;left:0;visibility:hidden';
      c.style.cssText='width:40px;height:1px'; d.appendChild(c); document.body.appendChild(d);
      if(d.scrollLeft>0) rtlModel='pos'; else { d.scrollLeft=-5; rtlModel=d.scrollLeft<0?'neg':'pos'; }
      document.body.removeChild(d);
    }catch(x){ rtlModel='neg'; }
    return rtlModel;
  }
  function pos(sc,ax){ if(sc) return ax==='x'?sc.scrollLeft:sc.scrollTop; return ax==='x'?(window.pageXOffset||0):(window.pageYOffset||0); }
  function range(sc,ax){
    var el=sc||root(),max=ax==='x'?el.scrollWidth-(sc?sc.clientWidth:innerWidth):el.scrollHeight-(sc?sc.clientHeight:innerHeight);
    if(max<=0) return [0,0];
    if(ax==='y') return [0,max];
    var rtl=false; try{ rtl=getComputedStyle(sc||document.documentElement).direction==='rtl'; }catch(x){}
    return (rtl&&model()==='neg')?[-max,0]:[0,max];
  }
  /* Each frame's position is set at once, even on a page that asks for smooth scrolling (that would smooth our steps again and stall). */
  var opts='scrollBehavior' in document.documentElement.style;
  function setPos(sc,x,y){
    if(opts){ (sc||window).scrollTo({left:x,top:y,behavior:'instant'}); return; }
    if(sc){ sc.scrollLeft=x; sc.scrollTop=y; } else window.scrollTo(x,y);
  }
  function animOf(sc){ for(var i=0;i<anims.length;i++) if(anims[i].n===sc) return anims[i]; return null; }
  function target(sc,ax){ var a=animOf(sc); return (a&&a.run)?(ax==='x'?a.tx:a.ty):pos(sc,ax); }
  function glide(sc,dx,dy){
    var a=animOf(sc); if(!a){ a={n:sc,run:false,tx:0,ty:0,t:0}; anims.push(a); }
    if(!a.run){ a.tx=pos(sc,'x'); a.ty=pos(sc,'y'); }
    var rx=range(sc,'x'),ry=range(sc,'y');
    a.tx=Math.max(rx[0],Math.min(rx[1],a.tx+dx)); a.ty=Math.max(ry[0],Math.min(ry[1],a.ty+dy));
    if(!a.run){ a.run=true; a.t=now(); a.idle=0; a.frames=0; requestAnimationFrame(function(){ step(a); }); }
  }
  function stepOf(cur,to,k){ var d=to-cur; if(Math.abs(d)<=2) return d; var s=d*k; return Math.abs(s)<1?(s<0?-1:1):s; }
  function step(a){
    if(!a.run) return;
    var t=now(),dt=Math.min(64,Math.max(1,t-a.t)); a.t=t;
    var k=1-Math.pow(0.78,dt/16.7),x=pos(a.n,'x'),y=pos(a.n,'y');
    setPos(a.n,x+stepOf(x,a.tx,k),y+stepOf(y,a.ty,k));
    var gx=pos(a.n,'x'),gy=pos(a.n,'y');
    var there=Math.abs(gx-a.tx)<1&&Math.abs(gy-a.ty)<1;
    /* the box did not move this frame: its end, a page that holds it, or rounding. Three such frames and the glide is over. */
    a.idle=(Math.abs(gx-x)<0.25&&Math.abs(gy-y)<0.25)?(a.idle||0)+1:0;
    a.frames=(a.frames||0)+1;
    if(there||a.idle>=3||a.frames>180){ a.run=false; a.idle=0; a.frames=0; return; }
    requestAnimationFrame(function(){ step(a); });
  }
  function finish(){ for(var i=0;i<anims.length;i++){ var a=anims[i]; if(a.run){ setPos(a.n,a.tx,a.ty); a.run=false; } } }
  function canScroll(sc,ax,sign){ var p=target(sc,ax),rg=range(sc,ax); return sign>0?p<rg[1]-1:p>rg[0]+1; }
  /* Keep the focused element in view. In a long list (the hub's games) the focus stays a little above the middle and the list glides one
     step at a time under it, the way a TV app's lists move; on a page that is only a little taller than the screen, the page moves only
     when the focus comes near an edge. */
  function reveal(e){
    var axes=['y','x'];
    for(var i=0;i<2;i++){
      var ax=axes[i],sc=scrollerOf(e,ax),v=view(sc),r=R(e),pend=target(sc,ax)-pos(sc,ax);
      var lo=(ax==='y'?r.t:r.l)-pend,hi=(ax==='y'?r.b:r.r)-pend,size=hi-lo,mid=(lo+hi)/2;
      var vlo=ax==='y'?v.t:v.l,vsize=ax==='y'?v.h:v.w,rg=range(sc,ax),d=0;
      if(ax==='y'&&(rg[1]-rg[0])>vsize*0.6&&size<vsize*0.6){
        var want=vlo+vsize*0.45; if(Math.abs(mid-want)>vsize*0.1) d=mid-want;
      } else {
        var m=Math.min(vsize*(ax==='y'?0.2:0.12),ax==='y'?140:90);
        if(lo<vlo+m||hi>vlo+vsize-m) d=size>vsize-2*m?(lo-(vlo+m)):(mid-(vlo+vsize/2));
      }
      if(d){ if(ax==='y') glide(sc,0,d); else glide(sc,d,0); }
    }
  }
  /* Nothing to go to that way within reach: show more of the page that way, about half a screen. */
  function moreContent(from,d){
    var ax=(d==='up'||d==='down')?'y':'x',sign=(d==='down'||d==='right')?1:-1;
    var start=from;
    if(!start){ var mid=document.elementFromPoint(innerWidth/2,innerHeight/2); start=mid||document.body; }
    var sc=start?scrollerOf(start,ax):null;
    for(var guard=0;guard<12;guard++){
      if(canScroll(sc,ax,sign)){ var v=view(sc),amt=sign*(ax==='y'?v.h:v.w)*0.45; if(ax==='y') glide(sc,0,amt); else glide(sc,amt,0); return true; }
      if(!sc) return false;
      sc=scrollerOf(sc,ax);
    }
    return false;
  }

  /* ---- choosing ---- */
  function lies(a,b,d){return d==='right'?b.cx>a.cx+1:d==='left'?b.cx<a.cx-1:d==='down'?b.cy>a.cy+1:b.cy<a.cy-1}
  function overlap(a,b,d){return (d==='left'||d==='right')?Math.max(0,Math.min(a.b,b.b)-Math.max(a.t,b.t)):Math.max(0,Math.min(a.r,b.r)-Math.max(a.l,b.l))}
  function clear(a,b,d){return d==='right'?b.l>=a.r-1:d==='left'?b.r<=a.l+1:d==='down'?b.t>=a.b-1:b.b<=a.t+1}
  function gap(a,b,d){return d==='right'?Math.max(0,b.l-a.r):d==='left'?Math.max(0,a.l-b.r):d==='down'?Math.max(0,b.t-a.b):Math.max(0,a.t-b.b)}
  function score(a,b,d){
    var off=(d==='left'||d==='right')?Math.abs(b.cy-a.cy):Math.abs(b.cx-a.cx);
    var ca=(d==='left'||d==='right')?Math.abs(b.cx-a.cx):Math.abs(b.cy-a.cy);
    return gap(a,b,d)+0.35*ca+2*off;
  }
  /* How far away the next element may be and still be gone to directly: about a screen of the box that scrolls. */
  function reach(from,d){ var ax=(d==='up'||d==='down')?'y':'x',v=view(scrollerOf(from,ax)); return (ax==='y'?v.h:v.w)*1.1; }
  function nearest(ts,from,d){
    var fr=F(from),lim=reach(from,d),c=[],i;
    for(i=0;i<ts.length;i++){
      var e=ts[i]; if(e===from) continue;
      var r=F(e); if(r.w<8||r.h<8||!lies(fr,r,d)||gap(fr,r,d)>lim) continue;
      /* sideways means along the same line: nothing level with the focus that way, and the focus does not jump up or down to find something */
      if((d==='left'||d==='right')&&overlap(fr,r,d)<=0&&Math.abs(r.cy-fr.cy)>Math.max(fr.h,r.h)*0.75) continue;
      var tier=(overlap(fr,r,d)>0?0:2)+(clear(fr,r,d)?0:1);
      c.push({e:e,k:tier*1e7+score(fr,r,d)});
    }
    c.sort(function(x,y){return x.k-y.k});
    for(i=0;i<c.length&&i<24;i++){ if(usable(c[i].e,R(c[i].e))) return c[i].e; }
    return null;
  }
  /* Where to begin when nothing is focused: the first thing on screen in reading order for the way the arrow points. */
  function entry(ts,d){
    var c=[],rtl=false,i; try{ rtl=getComputedStyle(document.documentElement).direction==='rtl'; }catch(x){}
    for(i=0;i<ts.length;i++){
      var r=F(ts[i]); if(!inView(r)||r.w<8||r.h<8) continue;
      var s;
      if(d==='up') s=-r.b*10+(rtl?-r.r:r.l)*0.01;
      else if(d==='right') s=r.l*10+r.t*0.01;
      else if(d==='left') s=-r.r*10+r.t*0.01;
      else s=r.t*10+(rtl?-r.r:r.l)*0.01;
      c.push({e:ts[i],r:r,s:s});
    }
    c.sort(function(x,y){return x.s-y.s});
    for(i=0;i<c.length&&i<40;i++){ if(usable(c[i].e,R(c[i].e))) return c[i].e; }
    return null;
  }
  function setCur(e){
    if(cur&&cur!==e){ try{ cur.removeAttribute(ATTR); }catch(x){} }
    cur=e; last=e;
    try{ e.setAttribute(ATTR,''); }catch(x){}
    reveal(e);
  }
  function editable(e){
    if(!e||!e.tagName) return false;
    if(e.isContentEditable||e.tagName==='TEXTAREA') return true;
    return e.tagName==='INPUT'&&!NOTEXT[(e.type||'').toLowerCase()];
  }
  function pageScrolls(){ var rg=range(null,'y'); return rg[1]>rg[0]; }

  window.__tvnav={
    v:2,doc:document,
    enter:function(d){
      css(); watch();
      var ts=collect();
      if(last&&last.isConnected&&shown(last)&&inView(F(last))){ setCur(last); return j({k:'moved'}); }
      var e=ts.length?entry(ts,d):null;
      if(e){ setCur(e); return j({k:'moved'}); }
      return j({k:ts.length||pageScrolls()?'edge':'none'});
    },
    move:function(d){
      css(); watch();
      var ts=collect();
      if(cur&&!cur.isConnected) cur=null;
      if(last&&!last.isConnected) last=null;
      if(cur&&!shown(cur)){ try{ cur.removeAttribute(ATTR); }catch(x){} cur=null; }
      if(!cur){
        var e0=ts.length?entry(ts,d):null;
        if(e0){ setCur(e0); return j({k:'moved'}); }
        if(moreContent(null,d)) return j({k:'scrolled'});
        return j({k:ts.length||pageScrolls()?'edge':'none'});
      }
      /* the page scrolled on and left the focus behind (a long text, or the page scrolled itself): begin from what is on screen */
      if(!inView(F(cur))){
        var e1=entry(ts,d); if(e1){ setCur(e1); return j({k:'moved'}); }
        if(moreContent(cur,d)) return j({k:'scrolled'});
        return j({k:'edge'});
      }
      var pick=nearest(ts,cur,d);
      if(pick){ setCur(pick); return j({k:'moved'}); }
      if(moreContent(cur,d)) return j({k:'scrolled'});
      return j({k:'edge'});
    },
    activate:function(){
      finish();                                     /* a glide still under way ends where it was going, so the tap lands on the element */
      if(!cur||!cur.isConnected) return j({k:'none'});
      var r=cur.getBoundingClientRect(),dpr=window.devicePixelRatio||1;
      var x=Math.min(Math.max(r.left+r.width/2,1),innerWidth-1),y=Math.min(Math.max(r.top+r.height/2,1),innerHeight-1);
      return j({k:'tap',x:x*dpr,y:y*dpr,ed:editable(cur),pw:(cur.type||'')==='password'});
    },
    field:function(){
      var a=document.activeElement;
      if(!editable(a)){ var f=editable(cur)?cur:(editable(last)&&last.isConnected?last:null); if(f){ try{ f.focus(); }catch(x){} a=document.activeElement; } }
      return j({k:'field',ed:editable(a),pw:!!a&&(a.type||'')==='password'});
    },
    blur:function(){ if(cur){ try{ cur.removeAttribute(ATTR); }catch(x){} } cur=null; return j({k:'ok'}); },
    stats:function(){ return j({k:'stats',n:list?list.length:-1,ms:Math.round(collectMs),light:light,at:listAt,running:anims.filter(function(a){return a.run}).length}); }
  };
})();
""".trimIndent()
}

/** [PageNav] over a WebView. */
class WebPageNav(private val web: () -> WebView?) : PageNav {
    private val handler = Handler(Looper.getMainLooper())

    /**
     * Runs `window.__tvnav.<expr>`. The script itself is sent only when the page does not have it yet (a new page, or one with an older
     * version), so a key press costs a few bytes, not the whole script.
     */
    private fun call(expr: String, cb: (String?) -> Unit) {
        val w = web() ?: return cb(null)
        val quick = "(function(){var n=window.__tvnav;if(!n||n.v!==${TvNavScript.VERSION}||n.doc!==document)return 'missing';return n.$expr})()"
        w.evaluateJavascript(quick) { raw ->
            if (raw == "\"missing\"") {
                val again = web() ?: return@evaluateJavascript cb(null)
                again.evaluateJavascript("(function(){" + TvNavScript.JS + ";return window.__tvnav.$expr})()") { cb(it) }
            } else cb(raw)
        }
    }

    override fun enter(dir: Dir?, cb: (PageResult) -> Unit) = call("enter(" + (dir?.let { "'" + it.name.lowercase() + "'" } ?: "null") + ")") { cb(parse(it)) }
    override fun move(dir: Dir, cb: (PageResult) -> Unit) = call("move('" + dir.name.lowercase() + "')") { cb(parse(it)) }
    override fun activate(cb: (PageResult) -> Unit) = call("activate()") { cb(parse(it)) }
    override fun blur() { web()?.evaluateJavascript("(function(){return window.__tvnav&&window.__tvnav.blur&&window.__tvnav.blur()})()", null) }

    override fun field(cb: (editable: Boolean, password: Boolean) -> Unit) = call("field()") { raw ->
        val o = obj(raw)
        cb(o?.optBoolean("ed") == true, o?.optBoolean("pw") == true)
    }

    /** A real tap inside the WebView, in its own pixels (no matter how the WebView is scaled or moved on screen). */
    override fun tap(x: Float, y: Float) {
        val w = web() ?: return
        val t = SystemClock.uptimeMillis()
        fun send(action: Int, at: Long) {
            val ev = MotionEvent.obtain(t, at, action, x, y, 0)
            ev.source = InputDevice.SOURCE_TOUCHSCREEN
            runCatching { w.dispatchTouchEvent(ev) }; ev.recycle()
        }
        send(MotionEvent.ACTION_DOWN, t)
        handler.postDelayed({ send(MotionEvent.ACTION_UP, SystemClock.uptimeMillis()) }, 50)
    }

    companion object {
        private fun obj(raw: String?): JSONObject? {
            val s = runCatching { JSONTokener(raw ?: return null).nextValue() as? String }.getOrNull() ?: return null
            return runCatching { JSONObject(s) }.getOrNull()
        }

        fun parse(raw: String?): PageResult {
            val o = obj(raw) ?: return PageResult.None
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
