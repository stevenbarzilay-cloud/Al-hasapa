package app.couch.alhasapa.player

/**
 * Sound on the TV (1.9.4). Dialed's sound game was choppy on the TV box and could not be played; the same was to be expected of every
 * game whose sound the page makes itself (Bandle, Originle, Songless).
 *
 * What was found: such a page asks the browser for an "audio context" and gets, unless it says otherwise, the setting for the shortest
 * delay ("interactive"): the sound is made in small pieces at the last moment and handed to the box's low-delay output. Dialed's page
 * also keeps its main thread busy all the time with its moving picture (measured: a few frames a second at a sixth of a desktop's
 * speed). A phone or a desktop keeps up with that. A TV box does not always, and every piece that comes late is a gap in the sound.
 * A page's `<audio>` and `<video>` do not have this problem: the browser plays those with a longer buffer of its own.
 *
 * So, on a TV only, a page that does not ask for a longer buffer itself gets one, in one of two ways (see [Mode]); which one a site
 * gets is decided by what was measured there ([SoundPolicy]). The cost is a delay of a fraction of a second between the page deciding
 * to make a sound and the sound: nothing for listening games, and a TV adds as much by itself.
 *
 * The script also counts, for the report: what the page's sound engine was given, how long it ran, how many gaps the browser counted
 * (`playbackStats`, where the browser has it), and for `<audio>` and `<video>` how often they stopped to wait for data and how many
 * video frames were dropped. Numbers only; nothing of what the page shows, plays or holds.
 */
object SoundScript {
    enum class Mode(val id: String) {
        /** The browser's own setting for playing music: its robust output, the one `<audio>` uses. The first choice. */
        PLAYBACK("playback"),

        /** The longest buffer the browser gives a page (about a fifth of a second), for a site where gaps were still counted. */
        LONG("long"),

        /** The page's own choice, untouched (only counted). */
        PAGE("page");

        companion object {
            fun of(id: String?): Mode? = entries.firstOrNull { it.id == id }
        }
    }

    /** Runs at document start, before the page's own scripts (so the page's first audio context is already the mended one). */
    fun build(mode: Mode): String = SOURCE.replace("__MODE__", mode.id)

    /** The numbers so far, as one line of comma-separated fields (see [SoundProbe.parse]); empty when the script is not there. */
    const val TAKE = "(function(){try{return window.__couchSound?window.__couchSound.take():''}catch(e){return ''}})()"

    private val SOURCE = """
(function(){
  if(window.__couchSound) return;
  var MODE='__MODE__';
  var S={mode:MODE,ctx:[],media:[],waits:0,stalls:0};
  try{Object.defineProperty(window,'__couchSound',{value:S})}catch(e){window.__couchSound=S}
  var Real=window.AudioContext||window.webkitAudioContext;
  if(Real){
    var HINT=MODE==='long'?0.2:(MODE==='playback'?'playback':null);
    // A page that asks for a buffer at least as long as ours keeps its own wish.
    var keeps=function(h){
      if(HINT===null) return true;
      if(typeof h==='number') return h>=(MODE==='long'?0.2:0.1);
      return h==='playback'&&MODE==='playback';
    };
    var pick=function(o){
      if(o!==undefined&&o!==null&&typeof o!=='object') return o;
      if(keeps(o?o.latencyHint:undefined)) return o;
      var p={}; if(o) for(var k in o) p[k]=o[k];
      p.latencyHint=HINT; return p;
    };
    var note=function(c){if(S.ctx.length<8) S.ctx.push(c)};
    try{
      var Couch=class extends Real{constructor(o){var p=o;try{p=pick(o)}catch(e){}super(p);try{note(this)}catch(e){}}};
      try{Object.defineProperty(Couch,'name',{value:'AudioContext'})}catch(e){}
      if(window.AudioContext) window.AudioContext=Couch;
      if(window.webkitAudioContext) window.webkitAudioContext=Couch;
    }catch(e){}
  }
  // <audio> and <video>: how often they stopped to wait for data once they were playing.
  var seen=typeof WeakSet==='function'?new WeakSet():null;
  var track=function(el){
    try{
      if(!seen||seen.has(el)) return; seen.add(el);
      if(S.media.length>=16) S.media.shift();
      S.media.push(el);
      el.addEventListener('waiting',function(){if(el.currentTime>0.3&&!el.seeking) S.waits++});
      el.addEventListener('stalled',function(){if(el.currentTime>0.3) S.stalls++});
    }catch(e){}
  };
  try{
    var play=HTMLMediaElement.prototype.play;
    HTMLMediaElement.prototype.play=function(){track(this);return play.apply(this,arguments)};
  }catch(e){}
  var ms=function(v){return typeof v==='number'&&isFinite(v)?Math.round(v*1000):-1};
  S.take=function(){
    var best=null,bt=-1,running=0,i;
    for(i=0;i<S.ctx.length;i++){
      var c=S.ctx[i],st=null; try{st=c.playbackStats}catch(e){}
      var t=st?st.totalDuration:(c.state==='running'?c.currentTime:0);
      if(c.state==='running') running++;
      if(t>bt){bt=t;best=c}
    }
    var a=[S.ctx.length,running];
    if(best){
      var ps=null; try{ps=best.playbackStats}catch(e){}
      a.push(Math.round(best.sampleRate),ms(best.baseLatency),ms(best.outputLatency),ps?1:0,
        Math.round((ps?ps.totalDuration:best.currentTime)*10),ps?ps.underrunEvents:-1,ps?ms(ps.underrunDuration):-1,
        ps?ms(ps.averageLatency):-1,ps?ms(ps.maximumLatency):-1);
    }else a.push(0,-1,-1,0,0,-1,-1,-1,-1);
    try{var all=document.querySelectorAll('audio,video');for(i=0;i<all.length;i++) track(all[i])}catch(e){}
    var au=0,auP=0,vi=0,viP=0,drop=0,frames=0;
    for(i=0;i<S.media.length;i++){
      var el=S.media[i],on=!el.paused&&!el.ended;
      if(el.tagName==='VIDEO'){
        vi++; if(on) viP++;
        try{var q=el.getVideoPlaybackQuality();drop+=q.droppedVideoFrames;frames+=q.totalVideoFrames}catch(e){}
      }else{au++; if(on) auP++}
    }
    a.push(au,auP,vi,viP,S.waits,S.stalls,drop,frames);
    return MODE+','+a.join(',');
  };
})();
""".trimIndent()
}
