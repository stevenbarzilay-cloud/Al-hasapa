// Real-Chromium check of the hub's page on the TV ("הגדרות ועוד") walked with the remote, through the TV's own navigation script (1.12):
// the tabs are reached from the header and from the list; Left and Right walk along them; down the list and all the way back up, the
// list glides back to its top (the picture shows again) before the focus leaves it for the tabs; and the phone's own buttons (who holds
// it, the notes) are not on the TV. Needs `playwright`.
// 1) ./gradlew :app:testPhoneDebugUnitTest --tests "app.couch.alhasapa.tv.*"   (writes app/build/display-scripts/tvnav.js)
// 2) node tools/hub_tvnav_check.cjs app/build/display-scripts
const { chromium } = require('playwright');
const http = require('http'), fs = require('fs'), path = require('path');
const ROOT = path.join(__dirname, '..', 'app', 'src', 'main', 'assets');
const NAV = fs.readFileSync(path.join(process.argv[2], 'tvnav.js'), 'utf8');
const MIME = { '.html': 'text/html', '.json': 'application/json', '.webp': 'image/webp', '.woff2': 'font/woff2' };
let bad = 0;
const ok = (c, m) => { console.log((c ? 'ok   ' : 'FAIL ') + m); if (!c) bad++; };
const server = http.createServer((req, res) => {
  const f = path.join(ROOT, decodeURIComponent(req.url.split('?')[0]));
  if (!f.startsWith(ROOT) || !fs.existsSync(f) || fs.statSync(f).isDirectory()) { res.writeHead(404); return res.end(); }
  res.writeHead(200, { 'content-type': MIME[path.extname(f)] || 'application/octet-stream' }); fs.createReadStream(f).pipe(res);
});
server.listen(0, '127.0.0.1', async () => {
  const port = server.address().port;
  const catalog = fs.readFileSync(path.join(ROOT, 'games.json'), 'utf8');
  const browser = await chromium.launch();
  const ctx = await browser.newContext({ viewport: { width: 960, height: 540 }, locale: 'he-IL' });
  await ctx.addInitScript(({ catalog }) => {
    let rev = 1, st = { v: 1, names: { a: 'סטיב', b: 'רוני' }, prefs: {}, log: {} };
    window.couchNative = { loadState() { return JSON.stringify({ rev, fresh: false, state: st }) }, saveState(j) { st = JSON.parse(j); rev++; return String(rev) },
      migrateState() { return '-1' }, loadCatalog() { return catalog }, play() {}, openAccounts() {}, exportBackup() { return '{}' }, copyText() { return 'ok' },
      importBackup() { return '{}' }, diagnostics() { return '' }, keyboardMode() { return 'no' }, me() { return '' }, openMe() {}, openNote() {}, notesWaiting() { return '0' } };
  }, { catalog });
  const page = await ctx.newPage(); const errs = []; page.on('pageerror', e => errs.push(String(e)));
  await page.goto(`http://127.0.0.1:${port}/hub/index.html?tv=1`); await page.waitForTimeout(1000);
  await page.evaluate(NAV);
  const at = () => page.evaluate(() => { const e = document.querySelector('[data-couch-focus]'), sc = document.getElementById('scroller');
    return { cls: e ? e.className : '', tab: !!(e && e.classList.contains('tab')), inList: !!(e && sc.contains(e)), scroll: Math.round(sc.scrollTop) }; });
  const press = async d => { const r = JSON.parse(await page.evaluate(d => window.__tvnav.move(d), d)); await page.waitForTimeout(420); return r.k; };

  ok(await page.evaluate(() => ['me', 'noteBtn'].every(id => getComputedStyle(document.getElementById(id)).display === 'none')), "the phone's own buttons are not on the TV");
  await page.evaluate(() => window.__tvnav.enter('down'));
  await press('down');
  ok((await at()).tab, 'down from the header: the tabs');
  const tabs = []; for (let i = 0; i < 4; i++) { tabs.push(await page.evaluate(() => document.querySelector('[data-couch-focus]').textContent.trim())); await press('right'); }
  for (let i = 0; i < 4; i++) { await press('left'); tabs.push(await page.evaluate(() => document.querySelector('[data-couch-focus]').textContent.trim())); }
  ok(new Set(tabs).size >= 4, `Left and Right walk along the tabs (${[...new Set(tabs)].join(', ')})`);
  for (let i = 0; i < 9; i++) await press('down');
  const deep = await at();
  ok(deep.inList && deep.scroll > 300, `down into the list, the list glides (${deep.scroll} px)`);
  let s, reachedTop = false, steps = 0;
  do { await press('up'); s = await at(); steps++; if (s.inList && s.scroll === 0) reachedTop = true; } while (!s.tab && steps < 20);
  ok(reachedTop, 'all the way up, the list is back at its top (the picture shows) before the focus leaves it');
  ok(s.tab && s.scroll === 0, `then the tabs, with the list at its top (${JSON.stringify(s)})`);
  ok(errs.length === 0, 'no JavaScript errors' + (errs.length ? ': ' + errs.join(' | ') : ''));
  // 1.12: the TV's page draws its pictures in the pieces' colours too, once, onto a canvas (no blend layers on the box's screen).
  {
    const c2 = await browser.newContext({ viewport: { width: 960, height: 540 }, locale: 'he-IL' });
    await c2.addInitScript(({ catalog }) => {
      let st = { v: 1, names: { a: 'סטיב', b: 'רוני' }, prefs: {}, log: {}, pieces: { a: { shape: 'heart', color: 'green' }, b: { shape: 'star', color: 'blue' } } };
      window.couchNative = { loadState() { return JSON.stringify({ rev: 1, fresh: false, state: st }) }, saveState() { return '2' }, migrateState() { return '-1' },
        loadCatalog() { return catalog }, play() {}, openAccounts() {}, exportBackup() { return '{}' }, copyText() { return 'ok' }, importBackup() { return '{}' },
        diagnostics() { return '' }, keyboardMode() { return 'no' } };
    }, { catalog });
    const p2 = await c2.newPage(); const e2 = []; p2.on('pageerror', e => e2.push(String(e)));
    await p2.goto(`http://127.0.0.1:${port}/hub/index.html?tv=1`); await p2.waitForTimeout(2500);
    const pic = await p2.evaluate(() => { const i = document.querySelector('.ill-couple img'); const t = document.querySelector('.ill-couple .tint');
      return { src: i ? i.getAttribute('src').slice(0, 22) : '', layers: t ? getComputedStyle(t).display : '' }; });
    ok(pic.src.startsWith('data:image/'), `the picture is drawn in the pieces' colours (${pic.src})`);
    ok(pic.layers === 'none', 'without blend layers over it');
    // The colours really moved: a pixel of the shirt is no longer the drawing's red.
    const hue = await p2.evaluate(() => new Promise(done => { const i = new Image(); i.onload = () => { const c = document.createElement('canvas'); c.width = i.naturalWidth; c.height = i.naturalHeight;
      const g = c.getContext('2d'); g.drawImage(i, 0, 0); const o = new Image(); o.onload = () => { const c2 = document.createElement('canvas'); c2.width = o.naturalWidth; c2.height = o.naturalHeight;
        const g2 = c2.getContext('2d'); g2.drawImage(o, 0, 0); let diff = 0; const a = g.getImageData(0, 0, c.width, c.height).data, b = g2.getImageData(0, 0, c.width, c.height).data;
        for (let k = 0; k < a.length; k += 16) diff += Math.abs(a[k] - b[k]) + Math.abs(a[k + 1] - b[k + 1]) + Math.abs(a[k + 2] - b[k + 2]); done(diff); }; o.src = 'img/couple.webp'; }; i.src = document.querySelector('.ill-couple img').src; }));
    ok(hue > 1e5, `and its colours differ from the drawing's (${hue})`);
    ok(e2.length === 0, 'no JavaScript errors' + (e2.length ? ': ' + e2.join(' | ') : ''));
    await c2.close();
  }
  await browser.close(); server.close();
  console.log(bad ? `\n${bad} FAILED` : '\nall passed');
  process.exit(bad ? 1 : 0);
});
