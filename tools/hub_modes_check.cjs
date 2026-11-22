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

const MOCK = ({ catalog, kb }) => {
  let rev = 0, st = null; window.__calls = [];
  window.couchNative = {
    loadState() { return JSON.stringify({ rev, fresh: st === null, state: st }) },
    saveState(json, base) { if (Number(base) !== rev) return '-1'; st = JSON.parse(json); rev++; return String(rev) },
    migrateState(json) { if (st !== null) return '-1'; st = JSON.parse(json); rev++; return String(rev) },
    loadCatalog() { return catalog }, play(id) { window.__calls.push('play:' + id) }, openAccounts() { window.__calls.push('accounts') },
    exportBackup() { return '{}' }, copyText() { return 'ok' }, importBackup() { return '{}' }, diagnostics() { return '' },
    keyboardMode() { return kb }, openKeyboardMode() { window.__calls.push('keyboard') },
  };
};

server.listen(0, '127.0.0.1', async () => {
  const port = server.address().port;
  const catalog = fs.readFileSync(path.join(ROOT, 'games.json'), 'utf8');
  const browser = await chromium.launch();
  const open = async (kb, query, size) => {
    const ctx = await browser.newContext({ viewport: size, locale: 'he-IL', deviceScaleFactor: 2 });
    if (kb !== null) await ctx.addInitScript(MOCK, { catalog, kb });
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
    ok(errs.length === 0, 'no JavaScript errors' + (errs.length ? ': ' + errs.join(' | ') : ''));
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
