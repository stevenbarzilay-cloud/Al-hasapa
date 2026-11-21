// Real-Chromium check of the TV page-navigation script (version 2): the focus walks between the clickable things of a page the way the
// arrows say, skips what cannot be pressed, reaches the next thing below the fold, glides the page smoothly so the focused element stays
// comfortably in view, scrolls on when the next thing is far, reports where to tap, and does NOTHING while nothing is pressed.
// 1) ./gradlew :app:testPhoneDebugUnitTest --tests "app.couch.alhasapa.tv.*"   (writes app/build/display-scripts/tvnav.js)
// 2) node tools/tvnav_check.cjs app/build/display-scripts                       (needs the `playwright` package)
const { chromium } = require('playwright');
const http = require('http'), fs = require('fs'), path = require('path');
const script = fs.readFileSync(process.argv[2] + '/tvnav.js', 'utf8');
const ASSETS = path.join(__dirname, '..', 'app', 'src', 'main', 'assets');
let bad = 0;
const ok = (c, m) => { console.log((c ? 'ok   ' : 'FAIL ') + m); if (!c) bad++; };

// Counts animation frames asked for, before anything else runs, so "idle means idle" can be checked.
const RAF_COUNTER = () => { window.__raf = 0; const o = window.requestAnimationFrame.bind(window); window.requestAnimationFrame = (f) => { window.__raf++; return o(f); }; };

const PAGE = `<!doctype html><meta charset=utf-8><style>
body{margin:0;font:16px sans-serif}
.grid{display:grid;grid-template-columns:repeat(3,120px);gap:20px;padding:20px}
button,.card{width:120px;height:60px}
.card{background:#ddd;cursor:pointer}
.card span{cursor:pointer}
.tall{height:1400px}
</style>
<div class=grid>
  <button id=a>A</button><button id=b>B</button><button id=c>C</button>
  <button id=d>D</button><div class=card id=card onclick=""><span>pointer</span> <span>card</span></div><button id=f disabled>F-disabled</button>
  <button id=g style="display:none">G-hidden</button><button id=h>H</button><button id=i style="position:relative">I-covered</button>
</div>
<div id=cover style="position:absolute;left:160px;top:180px;width:130px;height:70px;background:#fff"></div>
<a id=link href="#x" style="display:block;margin:10px 20px">a link</a>
<div class=tall></div>
<button id=far style="margin:20px">far below</button>
<div class=tall></div>`;

// Like the hub: a fixed shell (logo and tabs never move) and an inner box that scrolls, right to left.
const HUBLIKE = `<!doctype html><html dir=rtl><meta charset=utf-8><style>
body{margin:0;font:16px sans-serif}
.app{position:fixed;inset:0;display:flex;flex-direction:column}
nav{display:flex;gap:8px;padding:8px;flex:none}
nav button{height:44px;width:90px}
.stage{flex:1;position:relative;min-height:0}
.scroller{position:absolute;inset:0;overflow:auto}
.row{display:flex;gap:12px;align-items:center;height:58px;border-bottom:1px solid #ccc;padding:0 12px}
.row b{flex:1}.row button{height:44px;width:80px}
</style><div class=app><nav><button id=t1>בית</button><button id=t2>ספרייה</button><button id=t3>הגדרות</button></nav>
<div class=stage><div class=scroller id=sc>${Array.from({ length: 30 }, (_, i) => `<div class=row><b>משחק ${i}</b><button id=tick${i}>✓</button><button id=play${i}>לשחק</button></div>`).join('')}</div></div></div>`;

// A long text between two buttons: the page must scroll on so the text can be read, not jump past it.
const GAP = `<!doctype html><meta charset=utf-8><body style="margin:0"><button id=top style="margin:20px;height:50px">top</button>
<div style="height:2600px;background:linear-gradient(#eee,#ccc)">text</div><button id=bottom style="margin:20px;height:50px">bottom</button>`;

const server = http.createServer((req, res) => {
  const f = path.join(ASSETS, decodeURIComponent(req.url.split('?')[0]));
  if (!f.startsWith(ASSETS) || !fs.existsSync(f) || fs.statSync(f).isDirectory()) { res.writeHead(404); return res.end(); }
  const mime = { '.html': 'text/html', '.json': 'application/json', '.webp': 'image/webp', '.woff2': 'font/woff2' }[path.extname(f)] || 'application/octet-stream';
  res.writeHead(200, { 'content-type': mime }); fs.createReadStream(f).pipe(res);
});

server.listen(0, '127.0.0.1', async () => {
  const port = server.address().port;
  const browser = await chromium.launch();
  const ctx = await browser.newContext({ viewport: { width: 520, height: 420 }, deviceScaleFactor: 2 });
  await ctx.addInitScript(RAF_COUNTER);
  const page = await ctx.newPage();
  const call = async (expr) => JSON.parse(await page.evaluate(`window.__tvnav.${expr}`));
  const settle = () => page.waitForTimeout(450);
  const focused = () => page.evaluate(() => { const f = document.querySelectorAll('[data-couch-focus]'); return f.length === 1 ? (f[0].id || f[0].className || f[0].tagName) : (f.length ? 'MANY:' + f.length : null); });
  const load = async (html) => { await page.setContent(html); await page.evaluate(script); };

  // ---------- a page that scrolls itself ----------
  await load(PAGE);
  ok((await call("enter('down')")).k === 'moved' && await focused() === 'a', 'entering from above lands on the first button, top left');
  ok((await call("move('right')")).k === 'moved' && await focused() === 'b', 'Right: the next button');
  ok((await call("move('right')")).k === 'moved' && await focused() === 'c', 'Right again');
  ok((await call("move('left')")).k === 'moved' && await focused() === 'b', 'Left walks back');
  ok((await call("move('down')")).k === 'moved' && await focused() === 'card', 'Down onto a div that only has a pointer cursor: the whole card, not one of the words in it');
  const side = await call("move('right')");
  ok(await focused() === 'card' && side.k === 'edge', 'Right from the card: F on its row is disabled, and sideways never jumps to another row, so it stays (edge)');
  await call("move('up')");
  ok(await focused() === 'b', 'Up from the card: the button above it');
  await call('blur()'); await call("enter('down')");
  ok(await focused() === 'b', 'coming back into the page returns to where the focus was');
  await call('blur()');
  ok(await focused() === null, 'blur takes the colour off');
  await call("move('down')");
  ok(await focused() === 'a', 'with nothing focused an arrow starts on screen, in reading order (A)');
  ok((await call("move('down')")).k === 'moved' && await focused() === 'd', 'the first column: A, then D');
  ok((await call("move('down')")).k === 'moved' && await focused() === 'h', 'Down skips the hidden G: D leads to H');
  await call("move('right')");
  ok(await focused() !== 'i', 'I is covered by another element, so it is never landed on');
  ok(await page.evaluate(() => getComputedStyle(document.querySelector('[data-couch-focus]')).boxShadow.includes('217, 78, 56')), 'the focused element itself is tinted (an inset fill in its own shape), no box drawn around it');

  await settle();
  const h = await page.evaluate(() => { const e = document.querySelector('[data-couch-focus]'); const r = e.getBoundingClientRect(); return { x: (r.left + r.right) / 2, y: (r.top + r.bottom) / 2 }; });
  const act = await call('activate()');
  ok(act.k === 'tap' && Math.abs(act.x - h.x * 2) < 4 && Math.abs(act.y - h.y * 2) < 4, `activate reports the centre in device pixels (${Math.round(act.x)},${Math.round(act.y)})`);
  ok(act.ed === false && act.pw === false, 'a button is not an editable field');

  // Down past the visible area: the link, then the far button (it is beyond a screen away, so the page scrolls on first), then the edge.
  await call("enter('down')");
  let guard = 0, last, kinds = [], farSeen = null;
  do {
    last = await call("move('down')"); kinds.push(last.k); guard++; await page.waitForTimeout(120);
    if (farSeen === null && await focused() === 'far') { await settle(); farSeen = await page.evaluate(() => { const r = document.getElementById('far').getBoundingClientRect(); return r.top >= 0 && r.bottom <= innerHeight; }); }
  } while (last.k !== 'edge' && guard < 40);
  ok(last.k === 'edge', `at the very bottom, with nothing further, it says edge (after ${guard} presses: ${kinds.join(',')})`);
  ok(kinds.includes('scrolled'), 'on the way the page scrolled on where the next thing was far away');
  await settle();
  ok(await page.evaluate(() => scrollY) > 1000, 'the page scrolled down on the way');
  ok(await focused() === 'far', 'and the last thing focused is the far button');
  ok(farSeen === true, 'which was glided fully on screen when the focus reached it');
  guard = 0; do { last = await call("move('up')"); guard++; await page.waitForTimeout(120); } while (last.k !== 'edge' && guard < 50);
  await settle();
  ok(last.k === 'edge' && await page.evaluate(() => scrollY) < 2, 'Up all the way: back to the top, then edge');

  // Smoothness: one step down glides over several frames instead of jumping.
  await page.evaluate(() => scrollTo(0, 0)); await settle();
  await call('blur()'); await call("enter('down')");
  const samples = await page.evaluate(async () => {
    const a = []; for (let i = 0; i < 9; i++) { window.__tvnav.move('down'); }
    for (let i = 0; i < 20; i++) { await new Promise(r => requestAnimationFrame(r)); a.push(Math.round(scrollY)); } return a;
  });
  const distinct = new Set(samples).size;
  ok(distinct >= 5 && samples[samples.length - 1] > samples[0], `a scroll is a glide over many frames (${distinct} different positions: ${samples.slice(0, 10).join(' ')} ...)`);

  // Idle means idle: once the glide has ended, no frame is asked for at all.
  await settle(); await page.waitForTimeout(400);
  const r0 = await page.evaluate(() => window.__raf); await page.waitForTimeout(1200); const r1 = await page.evaluate(() => window.__raf);
  ok(r1 === r0, `nothing runs while nothing is pressed (${r1 - r0} animation frames in 1.2 s of rest)`);

  // The list of what can be pressed is kept while the page only scrolls (no work on scroll), and made again when the page changes.
  await call("move('up')"); await settle();
  const s1 = await call('stats()'); await page.evaluate(() => scrollBy(0, 300)); await page.waitForTimeout(50); await call("move('up')");
  const s2 = await call('stats()');
  ok(s1.at === s2.at, 'scrolling does not make it look for buttons again');
  await page.evaluate(() => { const b = document.createElement('button'); b.textContent = 'new'; document.body.prepend(b); }); await page.waitForTimeout(30);
  await call("move('down')"); const s3 = await call('stats()');
  ok(s3.at !== s2.at, 'a change to the page does');

  // ---------- like the hub: a fixed shell with an inner box that scrolls, right to left ----------
  await load(HUBLIKE);
  await call("enter('down')");
  ok(['t1', 't2', 't3'].includes(await focused()), 'the hub-like page starts on its tabs');
  await call("move('down')"); await settle();
  ok(/^(tick|play)0$/.test(await focused()), `Down from the tabs goes into the list (${await focused()})`);
  let moves = 0, scrolled = 0, ids = [], places = [], glides = [], prev = 0;
  for (let i = 0; i < 12; i++) {
    const r = await call("move('down')"); if (r.k === 'moved') moves++; if (r.k === 'scrolled') scrolled++;
    await settle();
    const m = await page.evaluate(() => { const e = document.querySelector('[data-couch-focus]'), sc = document.getElementById('sc'); const a = e.getBoundingClientRect(), b = sc.getBoundingClientRect(); return { mid: (a.top + a.bottom) / 2 - b.top, h: b.height, id: e.id, s: sc.scrollTop }; });
    ids.push(m.id); places.push(Math.round(m.mid / m.h * 100)); glides.push(Math.round(m.s - prev)); prev = m.s;
  }
  ok(moves === 12 && scrolled === 0, `Down walks the list row by row, the next row below the fold included (${ids.join(' ')})`);
  ok(ids.every((id, k) => id.replace(/\D/g, '') === String(k + 1)), 'one row per press, in order');
  ok(await page.evaluate(() => document.getElementById('sc').scrollTop) > 200, 'the inner box scrolled (the fixed shell did not move)');
  ok(places.slice(4).every(p => p >= 33 && p <= 57), `once the list moves, the focus stays near the middle of the box (${places.join('% ')}%)`);
  ok(glides.slice(4).every(s => s >= 50 && s <= 66), `and the list glides one row per press (${glides.join(' ')} px)`);
  await call("move('right')"); await settle();
  const sideways = await focused();
  ok(/^(tick|play)12$/.test(sideways), `Right stays on the same row (${sideways})`);
  for (let i = 0; i < 25; i++) { await call("move('down')"); await page.waitForTimeout(60); }
  await settle();
  ok((await call("move('down')")).k === 'edge', 'past the last row: edge');
  for (let i = 0; i < 40; i++) { const r = await call("move('up')"); await page.waitForTimeout(60); if (['t1', 't2', 't3'].includes(await focused())) break; }
  await settle();
  ok(['t1', 't2', 't3'].includes(await focused()) && await page.evaluate(() => document.getElementById('sc').scrollTop) < 2, 'Up all the way: back on the tabs, the list at its top');

  // ---------- a long text between two buttons ----------
  await load(GAP);
  await call("enter('down')");
  const steps = [];
  for (let i = 0; i < 20; i++) { const r = await call("move('down')"); steps.push(r.k); await settle(); if (await focused() === 'bottom') break; }
  ok(steps.filter(k => k === 'scrolled').length >= 3 && await focused() === 'bottom', `across a long text the page scrolls on, about half a screen a press, then lands on the next button (${steps.join(',')})`);

  // ---------- fields ----------
  await load('<!doctype html><input id=u type=text style="margin:20px"><input id=p type=password style="margin:20px;display:block"><input id=cb type=checkbox style="margin:20px;width:30px;height:30px">');
  await call("enter('down')");
  const t1 = await call('activate()'); ok(t1.ed === true && t1.pw === false, 'a text field is editable');
  const f1 = await call('field()');
  ok(f1.ed === true && await page.evaluate(() => document.activeElement.id) === 'u', 'the keyboard button asks for the field: the one the focus is on gets the cursor');
  await call("move('down')");
  const t2 = await call('activate()'); ok(t2.ed === true && t2.pw === true, 'a password field says so');
  await call("move('down')");
  const t3 = await call('activate()'); ok(t3.ed === false, 'a checkbox is not a text field');

  // ---------- nothing at all ----------
  await load('<!doctype html><canvas width=300 height=300></canvas>');
  ok((await call("enter('down')")).k === 'none' && (await call("move('down')")).k === 'none', 'a page with nothing to press says none (the cue to offer the paw)');

  // ---------- heavy page, slow CPU (a TV box) ----------
  await page.setContent(`<!doctype html><body>${Array.from({ length: 240 }, (_, i) => `<div style="padding:4px">${'<span>x</span>'.repeat(8)}<button>b${i}</button><span style="cursor:pointer">p${i}</span></div>`).join('')}</body>`);
  await page.evaluate(script);
  const cdp = await ctx.newCDPSession(page); await cdp.send('Emulation.setCPUThrottlingRate', { rate: 6 });
  const tFirst = await page.evaluate(() => { const t = performance.now(); window.__tvnav.enter('down'); return performance.now() - t; });
  const tNext = await page.evaluate(() => { const t = performance.now(); for (let i = 0; i < 10; i++) window.__tvnav.move('down'); return (performance.now() - t) / 10; });
  await cdp.send('Emulation.setCPUThrottlingRate', { rate: 1 });
  ok(tFirst < 400 && tNext < 40, `fast enough with the CPU 6x slower: first look ${Math.round(tFirst)} ms, each press after ${tNext.toFixed(1)} ms`);

  // ---------- the real hub, in its TV mode ----------
  const hub = await ctx.newPage(); const errs = []; hub.on('pageerror', e => errs.push(String(e)));
  await hub.setViewportSize({ width: 880, height: 540 });
  await hub.goto(`http://127.0.0.1:${port}/hub/index.html?tv=1`); await hub.waitForTimeout(900);
  await hub.evaluate(script);
  const hcall = async (expr) => JSON.parse(await hub.evaluate(`window.__tvnav.${expr}`));
  await hcall("enter('down')");
  const look = () => hub.evaluate(() => { const f = document.querySelectorAll('[data-couch-focus]'); if (f.length !== 1) return { n: f.length }; const s = getComputedStyle(f[0]); return { n: 1, bg: s.backgroundColor, ring: s.outlineStyle !== 'none' && s.outlineWidth !== '0px', shadow: s.boxShadow, tag: f[0].tagName, cls: f[0].className }; });
  const l0 = await look();
  ok(l0.n === 1 && l0.bg === 'rgb(217, 78, 56)' && !l0.ring, `in the hub the focused button itself turns red, no ring around it (${l0.tag}.${l0.cls})`);
  const sc0 = await hub.evaluate(() => document.getElementById('scroller').scrollTop);
  for (let i = 0; i < 14; i++) { await hcall("move('down')"); await hub.waitForTimeout(140); }
  await hub.waitForTimeout(450);
  const sc1 = await hub.evaluate(() => document.getElementById('scroller').scrollTop);
  ok(sc1 > sc0 + 100, `walking down the hub glides its list (scrolled ${Math.round(sc1 - sc0)} px)`);
  const l1 = await look();
  ok(l1.n === 1 && l1.bg === 'rgb(217, 78, 56)', 'exactly one thing is focused, and it is coloured');
  const inBox = await hub.evaluate(() => { const e = document.querySelector('[data-couch-focus]'), b = document.getElementById('scroller').getBoundingClientRect(), r = e.getBoundingClientRect(); return r.top >= b.top - 1 && r.bottom <= b.bottom + 1; });
  ok(inBox, 'and it is in view');
  await hub.waitForTimeout(500);
  const hr0 = await hub.evaluate(() => window.__raf); await hub.waitForTimeout(1500); const hr1 = await hub.evaluate(() => window.__raf);
  ok(hr1 - hr0 <= 1, `the hub at rest asks for no frames (${hr1 - hr0} in 1.5 s)`);
  for (let i = 0; i < 40; i++) { await hcall("move('up')"); await hub.waitForTimeout(40); }
  await hub.waitForTimeout(500);
  const top = await hub.evaluate(() => { const e = document.querySelector('[data-couch-focus]'); return e && (e.closest('nav,header,.navbar,.mast') ? 'shell' : 'content'); });
  ok(top === 'shell', 'Up all the way reaches the tabs at the top');
  ok(errs.length === 0, 'no JavaScript errors in the hub' + (errs.length ? ': ' + errs.join(' | ') : ''));

  // It reads where things are and nothing else: no text, no values.
  ok(!/innerText|\.value\b|outerHTML|innerHTML/.test(script) && (script.match(/textContent/g) || []).length === 1 && /s\.textContent='\[/.test(script),
    'it never reads text or values (textContent is only written, for its own style)');

  await browser.close(); server.close();
  console.log(bad ? `\n${bad} FAILED` : '\nall passed');
  process.exit(bad ? 1 : 0);
});
