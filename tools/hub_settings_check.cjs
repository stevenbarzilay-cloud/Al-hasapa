// Real-Chromium check of the hub's settings tab against a mock of the native bridge: editing every game's link, adding one game,
// adding several, deleting, and the diagnostics. Needs the `playwright` package.   node tools/hub_settings_check.cjs
const { chromium } = require('playwright');
const http = require('http'), fs = require('fs'), path = require('path');
const ROOT = path.join(__dirname, '..', 'app', 'src', 'main', 'assets');
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.json': 'application/json', '.webp': 'image/webp', '.ttf': 'font/ttf', '.woff2': 'font/woff2' };
let bad = 0;
const ok = (c, m) => { console.log((c ? 'ok   ' : 'FAIL ') + m); if (!c) bad++; };

const server = http.createServer((req, res) => {
  const f = path.join(ROOT, decodeURIComponent(req.url.split('?')[0]));
  if (!f.startsWith(ROOT) || !fs.existsSync(f) || fs.statSync(f).isDirectory()) { res.writeHead(404); return res.end(); }
  res.writeHead(200, { 'content-type': MIME[path.extname(f)] || 'application/octet-stream' }); fs.createReadStream(f).pipe(res);
});

const MOCK = ({ catalog, diag }) => {
  let rev = 0, st = null; window.__calls = [];
  window.__st = () => JSON.parse(JSON.stringify(st));
  window.couchNative = {
    loadState() { return JSON.stringify({ rev, fresh: st === null, state: st }) },
    saveState(json, base) { if (Number(base) !== rev) return '-1'; st = JSON.parse(json); rev++; return String(rev) },
    migrateState(json) { if (st !== null) return '-1'; st = JSON.parse(json); rev++; return String(rev) },
    loadCatalog() { return catalog }, play(id) { window.__calls.push('play:' + id) }, openAccounts() { window.__calls.push('accounts') },
    exportBackup() { return '{}' }, copyText(t) { window.__calls.push('copy:' + t.length); return 'ok' }, importBackup() { return '{}' },
    diagnostics() { return diag },
  };
};

server.listen(0, '127.0.0.1', async () => {
  const port = server.address().port;
  const catalog = fs.readFileSync(path.join(ROOT, 'games.json'), 'utf8');
  const total = JSON.parse(catalog).games.length;
  const browser = await chromium.launch();
  const ctx = await browser.newContext({ viewport: { width: 960, height: 540 }, locale: 'he-IL' });
  await ctx.addInitScript(MOCK, { catalog, diag: 'app 1.4.0 (release), android 9\n--- the last start ---\nactivity created: 900 ms\nhub ready: 5300 ms' });
  const page = await ctx.newPage(); const errs = []; page.on('pageerror', e => errs.push(String(e)));
  await page.goto(`http://127.0.0.1:${port}/hub/index.html?tv=1`); await page.waitForTimeout(700);

  ok(await page.evaluate(() => document.documentElement.classList.contains('tv') && document.documentElement.classList.contains('reduce-motion')), 'tv=1 puts the hub in its light mode (no recolouring layers, no animations)');
  ok(await page.evaluate(() => getComputedStyle(document.querySelector('.art .tint') || document.body).display === 'none' || !document.querySelector('.art .tint')), 'the illustration tint layers are not drawn on a TV');

  await page.click('.tab[data-view="settings"]'); await page.waitForTimeout(200);
  const rows = await page.evaluate(() => document.querySelectorAll('#ul .urlrow').length);
  ok(rows === total, `the settings tab lists every game's link (${rows} of ${total})`);
  ok(await page.evaluate(() => [...document.querySelectorAll('#ul .urlrow')].filter(r => r.querySelector('.tag') && /עמוד ראשי/.test(r.textContent)).length) >= 5, 'games that lead only to the Haaretz main page are marked so');
  ok(await page.evaluate(() => document.getElementById('diag').textContent.includes('hub ready: 5300 ms')), 'the diagnostics show what the app says about its last start');

  // 1) editing a link
  await page.fill('input[data-url="hzchain"]', 'https://www.haaretz.co.il/riddles/chain-today');
  await page.click('[data-act="saveLinks"]'); await page.waitForTimeout(200);
  let st = await page.evaluate(() => window.__st());
  ok(st.urls && st.urls.hzchain === 'https://www.haaretz.co.il/riddles/chain-today', 'a changed link is saved');
  await page.fill('input[data-url="hz5"]', 'http://not-secure.example/x');
  await page.click('[data-act="saveLinks"]'); await page.waitForTimeout(200);
  st = await page.evaluate(() => window.__st());
  ok(!st.urls.hz5 && st.urls.hzchain, 'a link that is not https is refused and nothing else is lost');
  ok(await page.evaluate(() => /https/.test(document.getElementById('toast').textContent)), 'and the person is told why');

  // 2) adding one game
  await page.fill('#ngName', 'Games of ours'); await page.fill('#ngUrl', 'https://games.example.org/daily');
  await page.selectOption('#ngMetric', 'time');
  await page.click('[data-act="addGame"]'); await page.waitForTimeout(250);
  st = await page.evaluate(() => window.__st());
  const c1 = (st.custom || [])[0];
  ok(c1 && c1.n === 'Games of ours' && c1.u === 'https://games.example.org/daily' && c1.metric === 'time' && c1.s === 'my', 'a new game is saved in the state as one of our own');
  ok(st.prefs[c1.id] === 'yes', 'and it goes straight onto the shelf');
  ok(await page.evaluate(id => !!document.querySelector(`#ul .urlrow[data-id="${id}"]`), c1.id), 'and has its own row in the list of links');
  await page.fill('#ngName', ''); await page.fill('#ngUrl', 'http://x');
  await page.click('[data-act="addGame"]'); await page.waitForTimeout(100);
  ok((await page.evaluate(() => window.__st())).custom.length === 1, 'a game with no name or no https link is not added');

  // 3) several at once
  await page.fill('#manyTxt', 'מילים בשש | https://a.example/1 | מילים\nמשחק שני | https://b.example/2\nשורה שבורה\nשלישי | http://insecure');
  await page.click('[data-act="addMany"]'); await page.waitForTimeout(250);
  st = await page.evaluate(() => window.__st());
  ok(st.custom.length === 3 && st.custom[1].k === 'מילים', 'a pasted list adds the lines that make sense (2 of 4)');
  ok(await page.evaluate(() => /2 משחקים/.test(document.getElementById('toast').textContent) && /2 שורות/.test(document.getElementById('toast').textContent)), 'and says how many it understood and how many it did not');

  // 4) our games are in the library and can be played
  await page.click('.tab[data-view="home"]'); await page.waitForTimeout(250);
  ok(await page.evaluate(id => !!document.querySelector(`.row[data-id="${id}"]`), c1.id), 'the new game shows in the evening list');
  await page.evaluate(id => playGame(id), c1.id);
  ok((await page.evaluate(() => window.__calls)).includes('play:' + c1.id), 'and pressing play asks the app to open it');

  // 5) deleting
  await page.click('.tab[data-view="settings"]'); await page.waitForTimeout(150);
  await page.click(`[data-act="delcustom"][data-id="${c1.id}"]`); await page.waitForTimeout(250);
  st = await page.evaluate(() => window.__st());
  ok(!st.custom.some(x => x.id === c1.id) && !st.prefs[c1.id], 'a game of ours can be removed again');
  ok(!(await page.evaluate(() => [...document.querySelectorAll('[data-act="delcustom"]')].some(b => !byId[b.dataset.id]))), 'only our own games offer deleting');
  ok(await page.evaluate(() => !document.querySelector('[data-act="delcustom"][data-id="wordle"]')), 'a built-in game cannot be deleted');

  await page.click('[data-act="copydiag"]'); await page.waitForTimeout(100);
  ok((await page.evaluate(() => window.__calls)).some(c => c.startsWith('copy:')), 'the diagnostics can be copied');
  ok(errs.length === 0, 'no JavaScript errors ' + errs.join('|'));
  await browser.close(); server.close();
  console.log(bad ? `\n${bad} FAILED` : '\nall passed'); process.exit(bad ? 1 : 0);
});
