// Real-Chromium check of the TV page-navigation script: the focus ring walks between the clickable things of a page the way the
// arrows say, skips what cannot be pressed, scrolls when there is nothing further, and reports where to tap.
// 1) ./gradlew :app:testPhoneDebugUnitTest --tests "app.couch.alhasapa.tv.*"   (writes app/build/display-scripts/tvnav.js)
// 2) node tools/tvnav_check.cjs app/build/display-scripts                       (needs the `playwright` package)
const { chromium } = require('playwright');
const fs = require('fs');
const script = fs.readFileSync(process.argv[2] + '/tvnav.js', 'utf8');
let bad = 0;
const ok = (c, m) => { console.log((c ? 'ok   ' : 'FAIL ') + m); if (!c) bad++; };

const PAGE = `<!doctype html><meta charset=utf-8><style>
body{margin:0;font:16px sans-serif}
.grid{display:grid;grid-template-columns:repeat(3,120px);gap:20px;padding:20px}
button,.card{width:120px;height:60px}
.card{background:#ddd;cursor:pointer}
.tall{height:1400px}
</style>
<div class=grid>
  <button id=a>A</button><button id=b>B</button><button id=c>C</button>
  <button id=d>D</button><div class=card id=card onclick="">pointer</div><button id=f disabled>F-disabled</button>
  <button id=g style="display:none">G-hidden</button><button id=h>H</button><button id=i style="position:relative">I-covered</button>
</div>
<div id=cover style="position:absolute;left:160px;top:180px;width:130px;height:70px;background:#fff"></div>
<a id=link href="#x" style="display:block;margin:10px 20px">a link</a>
<div class=tall></div>
<button id=far style="margin:20px">far below</button>
<div class=tall></div>`;

(async () => {
  const browser = await chromium.launch();
  const ctx = await browser.newContext({ viewport: { width: 520, height: 420 }, deviceScaleFactor: 2 });
  const page = await ctx.newPage();
  await page.setContent(PAGE);
  await page.evaluate(script);
  const call = async (expr) => JSON.parse(await page.evaluate(`window.__tvnav.${expr}`));
  const cur = () => page.evaluate(() => { const r = document.getElementById('couch-tv-ring'); if (!r || r.style.display === 'none') return null; const b = r.getBoundingClientRect(); const e = document.elementFromPoint(b.left + b.width / 2, b.top + b.height / 2); return e ? (e.id || e.className || e.tagName) : null; });
  const focused = async () => { await page.waitForTimeout(60); return page.evaluate(() => { const r = document.getElementById('couch-tv-ring'); if (!r || r.style.display === 'none') return null; const b = r.getBoundingClientRect(); const cx = b.left + b.width / 2, cy = b.top + b.height / 2; return [...document.querySelectorAll('button,.card,a')].find(e => { const q = e.getBoundingClientRect(); return Math.abs((q.left + q.right) / 2 - cx) < 3 && Math.abs((q.top + q.bottom) / 2 - cy) < 3; })?.id || null; }); };

  ok((await call("enter('down')")).k === 'moved' && await focused() === 'a', 'entering from above lands on the first button, top left');
  ok((await call("move('right')")).k === 'moved' && await focused() === 'b', 'Right: the next button');
  ok((await call("move('right')")).k === 'moved' && await focused() === 'c', 'Right again');
  ok((await call("move('left')")).k === 'moved' && await focused() === 'b', 'Left walks back');
  ok((await call("move('down')")).k === 'moved' && await focused() === 'card', 'Down onto a div that only has a pointer cursor');
  await call("move('right')");
  ok(await focused() === 'c', 'Right from the card: F is disabled, so the nearest enabled thing that way is C');
  await call("enter('down')"); await call("move('down')");
  ok(await focused() === 'd', 'the first column: A, then D');
  ok((await call("move('down')")).k === 'moved' && await focused() === 'h', 'Down skips the hidden G: D leads to H');
  const st = await call("move('right')");
  ok(await focused() !== 'i', 'I is covered by another element, so it is never landed on (' + st.k + ')');
  await call("enter('down')"); await call("move('down')"); await call("move('down')");
  ok(await focused() === 'h', 'back on H');

  const h = await page.evaluate(() => { const r = document.getElementById('h').getBoundingClientRect(); return { x: (r.left + r.right) / 2, y: (r.top + r.bottom) / 2 }; });
  const act2 = await call("activate()");
  ok(act2.k === 'tap' && Math.abs(act2.x - h.x * 2) < 4 && Math.abs(act2.y - h.y * 2) < 4, `activate reports the centre in device pixels (${Math.round(act2.x)},${Math.round(act2.y)})`);
  ok(act2.ed === false && act2.pw === false, 'a button is not an editable field');

  // Down past the visible area: the link, then scrolling, until the far button, then the edge.
  let guard = 0, last;
  do { last = await call("move('down')"); guard++; } while (last.k !== 'edge' && guard < 30);
  ok(last.k === 'edge', `at the very bottom, with nothing further, it says edge (after ${guard} presses)`);
  ok(await page.evaluate(() => scrollY) > 500, 'the page scrolled down on the way');
  ok(await focused() === 'far', 'and the last thing focused is the far button');
  guard = 0; do { last = await call("move('up')"); guard++; } while (last.k !== 'edge' && guard < 40);
  ok(last.k === 'edge' && await page.evaluate(() => scrollY) === 0, 'Up all the way: back to the top, then edge');

  await call("blur()");
  ok((await cur()) === null, 'blur hides the ring');

  // An empty page: nothing to focus.
  await page.setContent('<!doctype html><canvas width=300 height=300></canvas>');
  await page.evaluate(script);
  ok((await call("enter('down')")).k === 'none' && (await call("move('down')")).k === 'none', 'a page with nothing to press says none (the cue to offer the paw)');

  // Editable fields say so, and passwords too.
  await page.setContent('<!doctype html><input id=u type=text style="margin:20px"><input id=p type=password style="margin:20px;display:block">');
  await page.evaluate(script);
  await call("enter('down')");
  const t1 = await call("activate()"); ok(t1.ed === true && t1.pw === false, 'a text field is editable');
  await call("move('down')");
  const t2 = await call("activate()"); ok(t2.ed === true && t2.pw === true, 'a password field says so');

  // The script touches the page only with its ring, and only reads positions.
  ok(!script.includes('innerText') && !script.includes('textContent') && !script.includes('.value'), 'it never reads text or values');

  await browser.close();
  console.log(bad ? `\n${bad} FAILED` : '\nall passed');
  process.exit(bad ? 1 : 0);
})();
