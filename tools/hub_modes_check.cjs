// Real-Chromium check of the hub's switch between playing on the phone and keyboard mode (the phone as the TV's keyboard): it shows on the
// phone, not on the TV and not in a plain browser, fits a phone's width, and opens keyboard mode. Needs `playwright`.
//   node tools/hub_modes_check.cjs
const { chromium } = require('playwright');
const http = require('http'), fs = require('fs'), path = require('path');
const ROOT = path.join(__dirname, '..', 'app', 'src', 'main', 'assets');
const MIME = { '.html': 'text/html', '.json': 'application/json', '.webp': 'image/webp', '.woff2': 'font/woff2' };
let bad = 0;
const ok = (c, m) => { console.log((c ? 'ok   ' : 'FAIL ') + m); if (!c) bad++; };

const server = http.createServer((req, res) => {
  const f = path.join(ROOT, decodeURIComponent(req.url.split('?')[0]));
  if (!f.startsWith(ROOT) || !fs.existsSync(f) || fs.statSync(f).isDirectory()) { res.writeHead(404); return res.end(); }
  res.writeHead(200, { 'content-type': MIME[path.extname(f)] || 'application/octet-stream' }); fs.createReadStream(f).pipe(res);
});

const MOCK = ({ catalog, kb, people }) => {
  let rev = 0, st = null; window.__calls = [];
  window.couchNative = {
    loadState() { return JSON.stringify({ rev, fresh: st === null, state: st }) },
    saveState(json, base) { if (Number(base) !== rev) return '-1'; st = JSON.parse(json); rev++; return String(rev) },
    migrateState(json) { if (st !== null) return '-1'; st = JSON.parse(json); rev++; return String(rev) },
    loadCatalog() { return catalog }, play(id) { window.__calls.push('play:' + id) }, openAccounts() { window.__calls.push('accounts') },
    exportBackup() { return '{}' }, copyText() { return 'ok' }, importBackup() { return '{}' }, diagnostics() { return '' },
    keyboardMode() { return kb }, openKeyboardMode() { window.__calls.push('keyboard') },
  };
  // 1.10: who holds the phone, and the note button, beside our pieces.
  if (people) Object.assign(window.couchNative, {
    me() { return 'b' }, openMe() { window.__calls.push('me') }, openNote() { window.__calls.push('note') }, notesWaiting() { return '2' },
    // 2.0: the home couch by name, under the logo; a press opens "my couches".
    couchName() { return 'הספה של סטיב ורוני' }, openCouches() { window.__calls.push('couches') },
  });
};

server.listen(0, '127.0.0.1', async () => {
  const port = server.address().port;
  const catalog = fs.readFileSync(path.join(ROOT, 'games.json'), 'utf8');
  const browser = await chromium.launch();
  const open = async (kb, query, size, people = false) => {
    const ctx = await browser.newContext({ viewport: size, locale: 'he-IL', deviceScaleFactor: 2 });
    if (kb !== null) await ctx.addInitScript(MOCK, { catalog, kb, people });
    const page = await ctx.newPage(); const errs = []; page.on('pageerror', e => errs.push(String(e)));
    await page.goto(`http://127.0.0.1:${port}/hub/index.html${query}`); await page.waitForTimeout(700);
    return { page, errs, ctx };
  };

  // The phone, upright and sideways.
  for (const size of [{ width: 411, height: 914 }, { width: 914, height: 411 }, { width: 360, height: 760 }]) {
    const { page, errs, ctx } = await open('yes', '', size);
    const m = await page.evaluate(() => { const e = document.getElementById('modes'); const r = e.getBoundingClientRect(); return { shown: !e.hidden && r.width > 0, l: r.left, r: r.right, w: innerWidth, sw: document.documentElement.scrollWidth }; });
    ok(m.shown, `${size.width}x${size.height}: the phone's hub shows the switch`);
    ok(m.l >= 0 && m.r <= m.w && m.sw <= m.w, `${size.width}x${size.height}: it fits the width (no sideways scroll)`);
    const sel = await page.evaluate(() => [...document.querySelectorAll('#modes .mode')].map(b => b.getAttribute('aria-selected')));
    ok(sel[0] === 'true' && sel[1] === 'false', 'playing on the phone is the mode it is in');
    await page.click('#modes [data-act="kbmode"]'); await page.waitForTimeout(100);
    ok((await page.evaluate(() => window.__calls)).includes('keyboard'), 'the keyboard side opens keyboard mode');
    ok(await page.evaluate(() => ['me', 'noteBtn', 'couchBtn'].every(id => getComputedStyle(document.getElementById(id)).display === 'none')),
      'without who-holds-it in the app, no empty buttons in the header');
    ok(errs.length === 0, 'no JavaScript errors' + (errs.length ? ': ' + errs.join(' | ') : ''));
    await ctx.close();
  }

  // 1.10: who holds the phone and the note button beside our pieces; everything in the header fits, nothing overlaps, the logo keeps its shape.
  for (const size of [{ width: 360, height: 760 }, { width: 393, height: 873 }, { width: 411, height: 914 }, { width: 914, height: 411 }]) {
    const { page, errs, ctx } = await open('yes', '', size, true);
    const m = await page.evaluate(() => {
      const box = s => { const r = document.querySelector(s).getBoundingClientRect(); return { l: r.left, r: r.right, t: r.top, b: r.bottom, w: r.width, h: r.height }; };
      const parts = { modes: box('#modes'), who: box('#who'), me: box('#me'), note: box('#noteBtn'), logo: box('.logo img'), couch: box('#couchBtn') };
      return { parts, w: innerWidth, sw: document.documentElement.scrollWidth, mast: box('.mast').h,
        me: document.getElementById('me').innerHTML, note: document.getElementById('noteBtn').textContent,
        couch: document.getElementById('couchBtn').textContent, couchLabel: document.getElementById('couchBtn').getAttribute('aria-label') };
    });
    const tag = `${size.width}x${size.height}`, P = Object.entries(m.parts);
    ok(P.every(([, b]) => b.w > 0 && b.l >= 0 && b.r <= m.w) && m.sw <= m.w, `${tag}: logo, switch, pieces, who holds it and the note all fit the width`);
    const over = []; for (let i = 0; i < P.length; i++) for (let j = i + 1; j < P.length; j++) {
      const [na, a] = P[i], [nb, b] = P[j]; if (a.l < b.r - 1 && b.l < a.r - 1 && a.t < b.b - 1 && b.t < a.b - 1) over.push(na + '/' + nb); }
    ok(over.length === 0, `${tag}: nothing in the header overlaps` + (over.length ? ': ' + over.join(', ') : ''));
    ok(Math.abs(m.parts.logo.w / m.parts.logo.h - 900 / 444) < 0.08, `${tag}: the logo keeps its shape (${(m.parts.logo.w / m.parts.logo.h).toFixed(2)})`);
    // (2.0: with the home couch's name in it, a slim line more on a phone held upright.)
    ok(m.mast <= 140, `${tag}: the header stays low (${Math.round(m.mast)}px)`);
    ok(m.me.includes('class="user"') && m.me.includes('<svg') && m.note.includes('2'), `${tag}: a user's mark beside the piece, and the notes waiting`);
    // 2.0: the home couch is named, in words and with its mark, and its line opens "my couches".
    ok(m.couch.includes('הספה של סטיב ורוני') && m.couch.includes('🏠') && m.couchLabel.startsWith('ספת הבית שלי: הספה של סטיב ורוני'), `${tag}: the home couch is named under the logo`);
    await page.click('#me'); await page.click('#noteBtn'); await page.click('#couchBtn'); await page.click('#modes [data-act="kbmode"]'); await page.waitForTimeout(100);
    const calls = await page.evaluate(() => window.__calls);
    ok(['me', 'note', 'keyboard', 'couches'].every(c => calls.includes(c)), `${tag}: who holds it, the note, the couch and the switch each open their own`);
    ok(errs.length === 0, 'no JavaScript errors' + (errs.length ? ': ' + errs.join(' | ') : ''));
    if (process.argv[2]) await page.screenshot({ path: path.join(process.argv[2], `hub-header-${tag}.png`), clip: { x: 0, y: 0, width: size.width, height: Math.min(size.height, 300) } });
    await ctx.close();
  }

  // The TV, and a plain browser: no switch.
  {
    const { page, ctx } = await open('no', '?tv=1', { width: 880, height: 540 });
    ok(await page.evaluate(() => document.getElementById('modes').hidden), 'not on the TV (it is the one being typed into)');
    await ctx.close();
  }
  {
    const { page, ctx } = await open(null, '', { width: 411, height: 914 });
    ok(await page.evaluate(() => document.getElementById('modes').hidden), 'not in a plain browser (no app around the page)');
    await ctx.close();
  }

  await browser.close(); server.close();
  console.log(bad ? `\n${bad} FAILED` : '\nall passed');
  process.exit(bad ? 1 : 0);
});
