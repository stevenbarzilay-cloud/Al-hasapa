package app.couch.alhasapa.results

import java.net.URI
import org.json.JSONObject

/**
 * Catches the result a game offers when the person presses ITS OWN Share or Copy button, so it need not be pasted by hand.
 *
 * What it does, and all it does: wraps navigator.share, navigator.clipboard.writeText and .write (2.0.2: the text of a
 * ClipboardItem), and document.execCommand("copy"), and hears the copy event's own text (2.0.2: a game that sets what is copied in
 * the event); when one is used it tells the app the text, then calls straight through to the original, so the game behaves exactly as
 * before.
 * It reads nothing else: not the page, not the forms, not the storage. It runs only on the game's own sites (the origin
 * allowlist given to addDocumentStartJavaScript), and the app checks the origin again on every message.
 */
object CaptureScript {
    /** The name of the message port the page sees (`couchCapture.postMessage`). */
    const val PORT = "couchCapture"
    const val MAX_TEXT = 4_000

    val JS: String = """
(function(){
  var port=window.$PORT; if(!port||typeof port.postMessage!=="function") return;
  if(window.__couchCapture) return; window.__couchCapture=true;
  var last="",lastAt=0;
  function send(kind,text){
    try{
      if(typeof text!=="string") return;
      text=text.trim(); if(!text||text.length>$MAX_TEXT) return;
      var now=Date.now(); if(text===last&&now-lastAt<1500) return; last=text; lastAt=now;
      port.postMessage(JSON.stringify({k:kind,t:text}));
    }catch(e){}
  }
  try{
    if(typeof navigator.share==="function"){
      var os=navigator.share;
      navigator.share=function(d){
        try{ if(d&&typeof d==="object") send("share", typeof d.text==="string"&&d.text?d.text:(typeof d.title==="string"?d.title:"")); }catch(e){}
        return os.apply(navigator,arguments);
      };
    }
  }catch(e){}
  try{
    var cb=navigator.clipboard;
    if(cb&&typeof cb.writeText==="function"){
      var ow=cb.writeText;
      cb.writeText=function(t){ try{send("copy",t)}catch(e){} return ow.apply(cb,arguments); };
    }
  }catch(e){}
  try{
    var cw=navigator.clipboard;
    if(cw&&typeof cw.write==="function"){
      var ow2=cw.write;
      cw.write=function(items){
        try{
          var it=items&&items[0];
          if(it&&it.types&&typeof it.getType==="function"&&Array.prototype.indexOf.call(it.types,"text/plain")>=0){
            it.getType("text/plain").then(function(b){return b.text()}).then(function(t){send("copy",t)}).catch(function(){});
          }
        }catch(e){}
        return ow2.apply(cw,arguments);
      };
    }
  }catch(e){}
  try{
    /* After the game's own copy handlers (the window hears it last): what the game put in the event, if it did. */
    window.addEventListener("copy",function(e){
      try{ var d=e.clipboardData, t=d&&typeof d.getData==="function"?d.getData("text/plain"):""; if(t) send("copy",t); }catch(x){}
    },false);
  }catch(e){}
  try{
    var oe=document.execCommand;
    document.execCommand=function(cmd){
      var picked="";
      try{
        if(typeof cmd==="string"&&cmd.toLowerCase()==="copy"){
          picked=String(window.getSelection?window.getSelection():"");
          var ae=document.activeElement;
          if(!picked&&ae&&(ae.tagName==="TEXTAREA"||ae.tagName==="INPUT")&&typeof ae.value==="string"&&ae.selectionStart!=null) picked=ae.value.substring(ae.selectionStart,ae.selectionEnd);
        }
      }catch(e){}
      var r=oe.apply(document,arguments);
      if(picked) send("copy",picked);
      return r;
    };
  }catch(e){}
})();
""".trimIndent()
}

/**
 * The app-side check of a message from the page: it must come from an allowlisted origin over https, be small, be shaped
 * right, and not be a repeat. Nothing it returns is ever executed or shown as HTML; it is only offered as a result to read.
 */
class CaptureGate(private val allowedHosts: Set<String>, private val now: () -> Long = System::currentTimeMillis) {
    data class Captured(val kind: String, val text: String)

    private var lastText = ""
    private var lastAt = 0L

    /** A message from the page. [sourceOrigin] is what the WebView says the sender's origin is, not what the page claims. */
    fun accept(raw: String?, sourceOrigin: String?): Captured? {
        if (raw == null || raw.length > CaptureScript.MAX_TEXT + 100) return null
        if (!originAllowed(sourceOrigin)) return null
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val kind = o.optString("k")
        if (kind != "share" && kind != "copy") return null
        val text = (o.opt("t") as? String)?.trim() ?: return null
        return dedupe(Captured(kind, text))
    }

    /** Text that appeared on the clipboard while the player was in front (a second route for games that copy some other way). */
    fun acceptClipboard(text: String?): Captured? = dedupe(Captured("copy", text?.trim() ?: return null))

    private fun dedupe(c: Captured): Captured? {
        if (c.text.isEmpty() || c.text.length > CaptureScript.MAX_TEXT) return null
        val t = now()
        if (c.text == lastText && t - lastAt < REPEAT_MS) return null
        lastText = c.text; lastAt = t
        return c
    }

    private fun originAllowed(origin: String?): Boolean {
        if (origin == null) return false
        val u = runCatching { URI(origin) }.getOrNull() ?: return false
        if (u.scheme != "https") return false
        return app.couch.alhasapa.catalog.Catalog.hostAllowed(u.host?.lowercase(), allowedHosts)
    }

    companion object {
        const val REPEAT_MS = 3_000L
    }
}
