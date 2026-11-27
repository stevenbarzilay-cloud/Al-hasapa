// Real-Chromium check of the phone home's solo games (1.11): someone played a game alone today and the challenge shows, sealed (no
// result of theirs anywhere on the page); once the one holding the phone played it alone too, the reveal (both results, the crown),
// said once; the month's crowns on the stats page; nothing of it in a plain browser. Needs `playwright`.
//   node tools/hub_solo_check.cjs
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

// The app around the page, holding the state the native side keeps: Roni holds this phone; Steve played Bandle alone (3 of 6).
const MOCK = ({ catalog, roniToo, guest }) => {
  const pad = n => String(n).padStart(2, '0'), d = new Date(), day = d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate());
  const solo = { [day]: { bandle: { a: { v: 3, max: 6, kind: 'guesses', t: Date.now() - 60000 } } } };
  if (roniToo) solo[day].bandle.b = { v: 2, max: 6, kind: 'guesses', t: Date.now() - 1000 };
  // 2.0: a guest joined the contest too: still playing ('playing'), handed in ('in'), or left it ('out').
  if (guest === 'playing') solo[day].bandle.u1234567 = { p: true, t: Date.now() - 500 };
  if (guest === 'in') solo[day].bandle.u1234567 = { v: 1, max: 6, kind: 'guesses', t: Date.now() - 500 };
  if (guest === 'out') solo[day].bandle.u1234567 = { out: true, t: Date.now() - 400 };
  let rev = 1, st = { v: 1, names: { a: 'סטיב', b: 'רוני' }, prefs: {}, log: {}, solo, people: guest ? { u1234567: { name: 'דני', shape: 'star', color: 'green', at: 5 } } : {} };
  window.__calls = [];
  window.couchNative = {
    loadState() { return JSON.stringify({ rev, fresh: false, state: st }) }, saveState(j) { st = JSON.parse(j); rev++; return String(rev) },
    migrateState() { return '-1' }, loadCatalog() { return catalog }, play(id) { window.__calls.push('play:' + id) }, openAccounts() {},
    exportBackup() { return '{}' }, copyText() { return 'ok' }, importBackup() { return '{}' }, diagnostics() { return '' }, keyboardMode() { return 'yes' },
    me() { return 'b' }, openMe() {}, openNote() {}, notesWaiting() { return '0' },
    playSolo(id) { window.__calls.push('solo:' + id) }, notifications() { return 'no' }, allowNotifications() { window.__calls.push('notify') },
  };
};

server.listen(0, '127.0.0.1', async () => {
  const port = server.address().port;
  const catalog = fs.readFileSync(path.join(ROOT, 'games.json'), 'utf8');
  const browser = await chromium.launch();
  const open = async (opts, size = { width: 393, height: 873 }) => {
    const ctx = await browser.newContext({ viewport: size, locale: 'he-IL', deviceScaleFactor: 1 });
    if (opts) await ctx.addInitScript(MOCK, { catalog, ...opts });
    const page = await ctx.newPage(); const errs = []; page.on('pageerror', e => errs.push(String(e)));
    await page.goto(`http://127.0.0.1:${port}/hub/index.html`); await page.waitForTimeout(700);
    return { page, errs, ctx };
  };
  const bandleRow = page => page.evaluate(() => { const r = document.querySelector('.row[data-id="bandle"] .extra'); return r ? r.textContent : null; });

  // Steve played it alone; Roni has not: the challenge, sealed.
  {
    const { page, errs, ctx } = await open({ roniToo: false }, { width: 360, height: 760 });
    const news = await page.evaluate(() => { const n = document.querySelector('.solo-news'); return n ? n.textContent : ''; });
    ok(news.includes('סטיב כבר שיחק היום ב-Bandle. מה איתך?'), `the challenge is on the home (${news.trim().slice(0, 80)})`);
    ok(!(await page.evaluate(() => document.body.innerText)).includes('3/6'), "sealed: Steve's result is nowhere on the page");
    const row = await bandleRow(page);
    ok(row !== null && row.includes('שיחק לבד') && row.includes('✉️'), `the game's row says Steve played it alone, in an envelope (${row})`);
    ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'the news fits a narrow phone (no sideways scroll)');
    await page.click('.solo-card [data-act="solo-play"]'); await page.click('.solo-bell');
    const calls = await page.evaluate(() => window.__calls);
    ok(calls.includes('solo:bandle'), 'taking the challenge plays Bandle alone, no question first');
    ok(calls.includes('notify'), 'the bell asks whether the app may say it with the app closed');
    ok(errs.length === 0, 'no JavaScript errors' + (errs.length ? ': ' + errs.join(' | ') : ''));
    await ctx.close();
  }

  // Roni played it alone too (2 of 6): the reveal, once; the crown is Roni's.
  {
    const { page, errs, ctx } = await open({ roniToo: true });
    const card = await page.evaluate(() => { const c = document.querySelector('.solo-card.reveal'); return c ? c.textContent : ''; });
    ok(card.includes('חשיפה ב-Bandle!'), `the reveal waits on the home (${card.trim().slice(0, 60)})`);
    await page.click('.solo-card.reveal [data-act="solo-reveal"]'); await page.waitForTimeout(1800);
    const sheet = await page.evaluate(() => ({ rows: [...document.querySelectorAll('#sheet .rv')].map(r => r.textContent), verdict: (document.querySelector('#sheet .verdict') || {}).textContent || '' }));
    ok(sheet.rows.length === 2 && sheet.rows.some(r => r.includes('3/6')) && sheet.rows.some(r => r.includes('2/6') && r.includes('👑')), `both results, the crown on Roni's (${JSON.stringify(sheet.rows)})`);
    ok(sheet.verdict.includes('הכתר שלך'), `and it says so (${sheet.verdict})`);
    await page.click('#sheet [data-act="close"]'); await page.waitForTimeout(200);
    ok(await page.evaluate(() => !document.querySelector('.solo-card.reveal')), 'said once: the reveal is gone from the home');
    const row = await bandleRow(page);
    ok(row !== null && row.includes('3/6') && row.includes('2/6') && row.includes('👑'), `the row keeps both results and the crown (${row})`);
    await page.click('.tab[data-view="stats"]'); await page.waitForTimeout(200);
    const stats = await page.evaluate(() => [...document.querySelectorAll('.panel')].map(p => p.textContent).find(t => t.includes('לבד · ראש בראש')) || '');
    ok(stats.includes('Bandle') && /רוני/.test(stats), `the stats page has the month's crowns (${stats.replace(/\s+/g, ' ').slice(0, 90)})`);
    // A second visit: nothing to reveal again (kept on this phone).
    await page.reload(); await page.waitForTimeout(700);
    ok(await page.evaluate(() => !document.querySelector('.solo-card.reveal')), 'not again after the page comes back');
    ok(errs.length === 0, 'no JavaScript errors' + (errs.length ? ': ' + errs.join(' | ') : ''));
    await ctx.close();
  }

  // A plain browser: nothing of it.
  {
    const { page, ctx } = await open(null);
    ok(await page.evaluate(() => !document.querySelector('.solo-news')), 'not in a plain browser');
    await ctx.close();
  }

  // 2.0, a contest of three: while the guest who joined is still playing nobody is crowned; once they hand in (or leave), someone is.
  {
    const waiting = await open({ roniToo: true, guest: 'playing' });
    ok(await waiting.page.evaluate(() => !document.querySelector('.solo-card.reveal')), 'three joined, one still playing: no reveal yet');
    const row = await bandleRow(waiting.page);
    ok(row !== null && !row.includes('👑'), `and no crown on the game's row (${row})`);
    await waiting.ctx.close();
    const done = await open({ roniToo: true, guest: 'in' });
    const card = await done.page.evaluate(() => { const c = document.querySelector('.solo-card.reveal'); return c ? c.textContent : ''; });
    ok(card.includes('חשיפה ב-Bandle!'), 'all three handed in: the reveal');
    await done.page.click('.solo-card.reveal [data-act="solo-reveal"]'); await done.page.waitForTimeout(1800);
    const rows = await done.page.evaluate(() => [...document.querySelectorAll('#sheet .rv')].map(r => r.textContent));
    ok(rows.length === 3 && rows.some(r => r.includes('דני') && r.includes('1/6') && r.includes('👑')), `three results, the crown on the guest's (${JSON.stringify(rows)})`);
    ok(done.errs.length === 0, 'no JavaScript errors' + (done.errs.length ? ': ' + done.errs.join(' | ') : ''));
    await done.ctx.close();
    const left = await open({ roniToo: true, guest: 'out' });
    const card2 = await left.page.evaluate(() => { const c = document.querySelector('.solo-card.reveal'); return c ? c.textContent : ''; });
    ok(card2.includes('חשיפה ב-Bandle!'), 'the guest left the contest: the two who handed in are not kept waiting');
    await left.ctx.close();
  }

  await browser.close(); server.close();
  console.log(bad ? `\n${bad} FAILED` : '\nall passed');
  process.exit(bad ? 1 : 0);
});
