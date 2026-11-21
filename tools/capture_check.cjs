// Real-Chromium check of the result-capture script: it must report what the game's own Share / Copy hands over, call straight
// through to the original, stay silent about anything else, and never throw into the page.
// 1) ./gradlew :app:testDebugUnitTest --tests "app.couch.alhasapa.results.*"   (writes app/build/display-scripts/capture.js)
// 2) node tools/capture_check.cjs app/build/display-scripts                      (needs the `playwright` package)
const { chromium } = require('playwright');
const fs = require('fs');
const script = fs.readFileSync(process.argv[2] + '/capture.js', 'utf8');
let bad = 0;
const ok = (c, m) => { console.log((c ? 'ok   ' : 'FAIL ') + m); if (!c) bad++; };

const PAGE = '<!doctype html><meta charset=utf-8><textarea id=t>Connections\nPuzzle #512</textarea><input id=i value="Strands #270"><p id=p>Queens #612 | 1:24</p>';
// The page's own originals, installed BEFORE the script runs, and a mock of the message port the app provides.
const PRE = `
  window.__calls = {share: 0, write: 0, exec: 0}; window.__msgs = [];
  window.couchCapture = { postMessage: m => window.__msgs.push(m) };
  navigator.share = function(d){ window.__calls.share++; return Promise.resolve('shared') };
  Object.defineProperty(navigator, 'clipboard', { value: { writeText: function(t){ window.__calls.write++; return Promise.resolve('written') } }, configurable: true });
  document.execCommand = function(c){ window.__calls.exec++; return true };
`;

(async () => {
  const browser = await chromium.launch();
  const ctx = await browser.newContext();
  const page = await ctx.newPage();
  await page.route('**/*', r => r.fulfill({ contentType: 'text/html', body: PAGE }));
  await page.addInitScript({ content: PRE });
  await page.addInitScript({ content: script });
  await page.goto('https://www.nytimes.com/games/wordle');
  const run = (f) => page.evaluate(f);
  const msgs = async () => (await run(() => window.__msgs)).map(JSON.parse);

  const r1 = await run(() => navigator.share({ title: 'Wordle', text: 'Wordle 1,618 3/6\n⬛🟩', url: 'https://x' }));
  ok(r1 === 'shared', 'share: the game\'s own share still runs and returns what it returned');
  let m = await msgs();
  ok(m.length === 1 && m[0].k === 'share' && m[0].t === 'Wordle 1,618 3/6\n⬛🟩', 'share: the text (not the url) is reported');

  await page.waitForTimeout(1600);
  const r2 = await run(() => navigator.clipboard.writeText('Connections\nPuzzle #512\n🟨🟨🟨🟨'));
  ok(r2 === 'written', 'writeText: the original still runs');
  m = await msgs(); ok(m.length === 2 && m[1].k === 'copy' && m[1].t.startsWith('Connections'), 'writeText: reported as a copy');

  await run(() => navigator.clipboard.writeText('Connections\nPuzzle #512\n🟨🟨🟨🟨'));
  m = await msgs(); ok(m.length === 2, 'the same text again within 1.5 s is reported once');

  await run(() => { const t = document.getElementById('t'); t.focus(); t.setSelectionRange(0, t.value.length); return document.execCommand('copy'); });
  m = await msgs(); ok(m.length === 3 && m[2].t.startsWith('Connections') , 'execCommand copy from a textarea: its selection is reported');
  ok((await run(() => window.__calls.exec)) === 1, 'execCommand: the original ran once');

  await run(() => { const r = document.createRange(); r.selectNodeContents(document.getElementById('p')); const s = getSelection(); s.removeAllRanges(); s.addRange(r); document.execCommand('copy'); });
  m = await msgs(); ok(m.length === 4 && m[3].t === 'Queens #612 | 1:24', 'execCommand copy of a page selection: reported');

  const before = (await msgs()).length;
  await run(() => { document.execCommand('bold'); navigator.clipboard.writeText('   '); navigator.clipboard.writeText('x'.repeat(5000)); navigator.share({}); navigator.share(); });
  ok((await msgs()).length === before, 'other commands, blank text, huge text and empty shares are not reported');
  ok((await run(() => window.__calls.exec)) === 3, 'execCommand("bold") still went through to the original');

  // Never throws into the page: a port that throws, and a page without the APIs.
  const p2 = await ctx.newPage();
  await p2.route('**/*', r => r.fulfill({ contentType: 'text/html', body: PAGE }));
  await p2.addInitScript({ content: 'window.couchCapture={postMessage(){throw new Error("boom")}}; navigator.share=()=>Promise.resolve(1);' });
  await p2.addInitScript({ content: script });
  await p2.goto('https://www.nytimes.com/games/wordle');
  ok(await p2.evaluate(async () => { try { await navigator.share({ text: 'hi there' }); return true } catch (e) { return false } }), 'a throwing port never breaks the game');

  const p3 = await ctx.newPage();
  await p3.route('**/*', r => r.fulfill({ contentType: 'text/html', body: PAGE }));
  await p3.addInitScript({ content: script });      // no port at all (not an allowlisted origin): it must do nothing
  await p3.goto('https://elsewhere.example/');
  ok(await p3.evaluate(() => !window.__couchCapture), 'no port, no hook: nothing is wrapped');

  await browser.close();
  console.log(bad ? `\n${bad} FAILED` : '\nall passed');
  process.exit(bad ? 1 : 0);
})();
