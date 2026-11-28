package app.couch.alhasapa.tv

/**
 * Enter, typed for a game on the TV (2.0.2: Wordle did not take it from our keyboards, letters it did). A game listens for keys on the
 * page; but the on-screen key of the game's own that was pressed last (by the paw, or a finger in touch mode) may still hold the
 * page's focus, and Enter then belongs to that button: the browser presses the button, and a game that leaves an Enter on a button to
 * the button does nothing more. So before the real key goes, the focus is taken off a button or a link (never off a field, where
 * Enter is the field's own: a form sent, a search made), and the key comes to the page itself.
 *
 * And should the key still not reach the page at all ([AFTER]: nothing saw it), it is said to the page from within it, once; a real
 * one that comes late after that is held back, so one press is one Enter.
 *
 * What these look at, and all they look at: what kind of element has the focus (its tag name), never what anything says.
 * Written for old WebViews (no newer syntax), and without a dollar sign (Kotlin templates).
 */
object EnterKey {
    /** Before the key: the focus off a button or a link; a watch for the key. Answers the focused element's tag ("moved " first when moved). */
    const val BEFORE = """(function(){try{
  var a=document.activeElement,t=(a&&a.tagName)||'';
  if(t==='IFRAME') return 'IFRAME';
  var field=!!a&&(a.isContentEditable||t==='INPUT'||t==='TEXTAREA'||t==='SELECT');
  var moved=false;
  if(a&&!field&&a!==document.body&&a!==document.documentElement&&typeof a.blur==='function'){try{a.blur();moved=true}catch(x){}}
  var s={seen:false,sent:false};
  var h=function(e){
    if(!(e.key==='Enter'||e.keyCode===13)) return;
    if(s.sent){ if(e.isTrusted){ try{e.preventDefault();e.stopImmediatePropagation()}catch(x){} window.removeEventListener('keydown',h,true) } return }
    s.seen=true; window.removeEventListener('keydown',h,true);
  };
  window.__couchEnter=s;
  window.addEventListener('keydown',h,true);
  setTimeout(function(){window.removeEventListener('keydown',h,true)},3000);
  return (moved?'moved ':'')+t;
}catch(e){return ''}})()"""

    /** A moment after the key: "seen" when the page had it; else it is said to the page once, from within ("sent"). */
    const val AFTER = """(function(){try{
  var s=window.__couchEnter; if(!s) return '';
  if(s.seen) return 'seen';
  s.sent=true;
  var t=document.activeElement||document.body;
  var mk=function(n,c){return new KeyboardEvent(n,{key:'Enter',code:'Enter',keyCode:13,which:13,charCode:c,bubbles:true,cancelable:true})};
  t.dispatchEvent(mk('keydown',0)); t.dispatchEvent(mk('keypress',13)); t.dispatchEvent(mk('keyup',0));
  return 'sent';
}catch(e){return ''}})()"""

    /** How long the page is given to see the real key before it is said from within. */
    const val CHECK_MS = 350L

    /** What evaluateJavascript hands back (a JSON string), as plain text. */
    fun answer(raw: String?): String = runCatching { org.json.JSONTokener(raw ?: "").nextValue() as? String }.getOrNull().orEmpty()
}
