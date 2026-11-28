// Real-Chromium check of Enter typed for a game on the TV (2.0.2): a game button that kept the page's focus must not take the Enter,
// a field keeps its own, a key the page never saw is said once from within, and one press is never two Enters.
// 1) ./gradlew :app:testPhoneDebugUnitTest --tests "app.couch.alhasapa.tv.EnterKeyTest"   (writes app/build/display-scripts/enter-*.js)
// 2) node tools/enter_check.cjs app/build/display-scripts                                   (needs the `playwright` package)
const { chromium } = require('playwright');
const fs = require('fs');
const BEFORE = fs.readFileSync(process.argv[2] + '/enter-before.js', 'utf8');
const AFTER = fs.readFileSync(process.argv[2] + '/enter-after.js', 'utf8');
let bad = 0;
const ok = (c, m) => { console.log((c ? 'ok   ' : 'FAIL ') + m); if (!c) bad++; };

// A game like Wordle: letters and Enter from the keyboard on the window; its own on-screen keys are buttons (a press leaves the focus
// on them); an Enter while a button has the focus is left to the button (the browser presses it), as such games do.
const PAGE = `<!doctype html><meta charset=utf-8>
<div id=row></div>
<div id=keys><button id=ka data-k=a>A</button><button id=kb data-k=b>B</button><button id=kenter data-k=enter>ENTER</button></div>
<form id=f onsubmit="window.__searched++;return false"><input id=q></form>
<iframe id=fr srcdoc="<button id=inner>x</button>"></iframe>
<script>
  window.__typed = ''; window.__submits = 0; window.__searched = 0; window.__untrusted = 0;
  document.getElementById('keys').addEventListener('click', function(e){
    var k = e.target.getAttribute('data-k'); if (!k) return;
    if (k === 'enter') window.__submits++; else window.__typed += k;
  });
  window.addEventListener('keydown', function(e){
    if (e.target && e.target.tagName === 'INPUT') return;
    if (e.key === 'Enter') {
      if (document.activeElement && document.activeElement.tagName === 'BUTTON') return;   // the button's own
      window.__submits++; if (!e.isTrusted) window.__untrusted++;
    } else if (/^[a-z]$/.test(e.key)) window.__typed += e.key;
  });
</script>`;

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage();
  await page.route('**/*', r => r.fulfill({ contentType: 'text/html', body: PAGE }));
  await page.goto('https://www.nytimes.com/games/wordle');
  const run = (js) => page.evaluate(js);
  const state = () => page.evaluate(() => ({ typed: window.__typed, submits: window.__submits, searched: window.__searched, untrusted: window.__untrusted, focus: document.activeElement && document.activeElement.id }));
  const reset = () => page.evaluate(() => { window.__typed = ''; window.__submits = 0; window.__searched = 0; window.__untrusted = 0; delete window.__couchEnter; });
  const wait = (ms) => page.waitForTimeout(ms);

  // The problem: a letter pressed on the game's own keys keeps the focus, and Enter then goes to that button (it types the letter again).
  await page.click('#ka'); await page.keyboard.press('Enter');
  let s = await state();
  ok(s.submits === 0 && s.typed === 'aa', 'without the fix: Enter pressed the focused key again and nothing was sent (' + JSON.stringify(s) + ')');

  // With it: the focus is taken off the button first, and the real Enter reaches the game, once.
  await reset(); await page.click('#ka');
  ok((await run(BEFORE)) === 'moved BUTTON', 'before: the focus was on a button of the game, and was moved off it');
  ok((await state()).focus === '', 'nothing of the game holds the focus now');
  await page.keyboard.press('Enter');
  await wait(350);
  ok((await run(AFTER)) === 'seen', 'after: the page saw the real key, nothing more is said');
  s = await state();
  ok(s.submits === 1 && s.typed === 'a' && s.untrusted === 0, 'the word was sent once, and the key was not pressed again (' + JSON.stringify(s) + ')');

  // The real key never reached the page: said once from within it.
  await reset(); await page.click('#kb');
  await run(BEFORE);
  await wait(350);
  ok((await run(AFTER)) === 'sent', 'after: nothing came, so Enter is said from within the page');
  s = await state();
  ok(s.submits === 1 && s.untrusted === 1 && s.typed === 'b', 'and the game took it, once (' + JSON.stringify(s) + ')');
  // ...and the real one, coming late, is held back: one press, one Enter.
  await page.keyboard.press('Enter');
  s = await state();
  ok(s.submits === 1, 'a late real key after that is held back (' + JSON.stringify(s) + ')');
  await page.keyboard.press('Enter');
  ok((await state()).submits === 2, 'and the next press is an Enter again');

  // A field keeps its focus and its Enter (a search sent).
  await reset(); await page.click('#q'); await page.keyboard.type('hello');
  ok((await run(BEFORE)) === 'INPUT', 'before: a field has the focus, and keeps it');
  ok((await state()).focus === 'q', 'the field still has it');
  await page.keyboard.press('Enter'); await wait(350);
  ok((await run(AFTER)) === 'seen', 'after: the field had the key');
  s = await state();
  ok(s.searched === 1 && s.submits === 0, 'the form was sent, the game got nothing (' + JSON.stringify(s) + ')');

  // A frame of the page has the focus: left alone (its own page has the key), nothing is watched.
  await reset(); await page.focus('#fr');
  await page.evaluate(() => document.getElementById('fr').focus());
  ok((await run(BEFORE)) === 'IFRAME', 'before: a frame has the focus, nothing is changed');
  ok((await run(AFTER)) === '', 'after: nothing to say');

  // Nothing focused at all: the key simply goes, and nothing is moved.
  await reset(); await page.evaluate(() => document.activeElement && document.activeElement.blur && document.activeElement.blur());
  ok((await run(BEFORE)) === 'BODY', 'before: the page itself has the focus, nothing to move');
  await page.keyboard.press('Enter'); await wait(350);
  ok((await run(AFTER)) === 'seen', 'after: seen');
  ok((await state()).submits === 1, 'sent once');

  // The watch ends by itself: an Enter long after is the game's as always, and never held back.
  await reset(); await run(BEFORE); await wait(350); await run(AFTER);
  await wait(3100);
  await page.keyboard.press('Enter');
  ok((await state()).submits === 2, 'three seconds on, the watch is gone and Enter works as always');

  // Never throws into the page, even with the page's KeyboardEvent taken away.
  const p2 = await browser.newPage();
  await p2.route('**/*', r => r.fulfill({ contentType: 'text/html', body: '<!doctype html><button id=b>b</button><script>window.KeyboardEvent=undefined</script>' }));
  await p2.goto('https://www.nytimes.com/games/wordle');
  await p2.click('#b');
  const b2 = await p2.evaluate(BEFORE); const a2 = await p2.evaluate(AFTER);
  ok(b2 === 'moved BUTTON' && a2 === '', 'a page without KeyboardEvent: no error, just no Enter from within (' + b2 + ' / ' + a2 + ')');

  await browser.close();
  console.log(bad ? `\n${bad} FAILED` : '\nall passed');
  process.exit(bad ? 1 : 0);
})();
