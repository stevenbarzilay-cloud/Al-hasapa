// Real-Chromium check of the injected display script (light mode and banner CSS). It must NOT touch the viewport: sizing is done
// by making the WebView itself phone-sized (see ScaledFrame), which Chromium sees as an ordinary window of that size.
// 1) ./gradlew :app:testDebugUnitTest --tests "app.couch.alhasapa.display.*"   (writes app/build/display-scripts/display.js)
// 2) node tools/display_check.cjs app/build/display-scripts                     (needs the `playwright` package)
const { chromium } = require('playwright');
const fs = require('fs');
const script = fs.readFileSync(process.argv[2] + '/display.js', 'utf8');
let bad = 0;
const ok = (c, m) => { console.log((c ? 'ok   ' : 'FAIL ') + m); if (!c) bad++; };
const PAGE = (meta) => `<!doctype html><meta charset=utf-8>${meta}<style>html,body{margin:0}.full{height:100vh}</style><div class="smartbanner" style="height:60px;background:red">get the app</div><div class="full" id="f">board</div>`;

(async () => {
  const browser = await chromium.launch();
  // A phone-sized window, the way the WebView is now sized: the page must simply see 430 x 800.
  for (const meta of ['<meta name="viewport" content="width=device-width, initial-scale=1">', '', '<meta name="viewport" content="width=500, user-scalable=yes">']) {
    const ctx = await browser.newContext({ viewport: { width: 430, height: 800 }, deviceScaleFactor: 1 });
    const page = await ctx.newPage();
    await page.addInitScript({ content: script });
    await page.route('**/*', r => r.fulfill({ contentType: 'text/html', body: PAGE(meta) }));
    await page.goto('https://example.test/games/x'); await page.waitForLoadState('load');
    const m = await page.evaluate(() => ({
      w: innerWidth, h: innerHeight, vh100: document.getElementById('f').getBoundingClientRect().height,
      banner: getComputedStyle(document.querySelector('.smartbanner')).display,
      scheme: getComputedStyle(document.documentElement).colorScheme,
      metas: [...document.querySelectorAll('meta[name=viewport]')].map(x => x.content),
    }));
    const own = (meta.match(/content="([^"]*)"/) || [])[1];
    ok(m.w === 430 && m.h === 800 && m.vh100 === 800, `a 430x800 window: the page sees ${m.w}x${m.h}, 100vh = ${m.vh100}`);
    ok(own === undefined ? m.metas.length === 0 : (m.metas.length === 1 && m.metas[0] === own), `the page's own viewport meta is untouched (${JSON.stringify(m.metas)})`);
    ok(m.banner === 'none', 'the app banner is hidden');
    ok(m.scheme.includes('light'), `light only (${m.scheme})`);
    await ctx.close();
  }
  // Re-running it is harmless (a profile change re-runs it on the live page).
  const ctx = await browser.newContext({ viewport: { width: 430, height: 800 } });
  const page = await ctx.newPage();
  await page.route('**/*', r => r.fulfill({ contentType: 'text/html', body: PAGE('') }));
  await page.goto('https://example.test/');
  await page.evaluate(script); await page.evaluate(script); await page.evaluate(script);
  ok(await page.evaluate(() => document.querySelectorAll('#couch-display-css').length) === 1, 'exactly one style element after three runs');
  // On a TV, for a site whose profile names it (Krillion's low-power mode): the site's own preference is set before the page's first
  // script runs, only when it is not there, and nothing else in the storage is touched.
  const tvFile = process.argv[2] + '/display-tv-krillion.js';
  if (fs.existsSync(tvFile)) {
    const tvScript = fs.readFileSync(tvFile, 'utf8');
    const KEY = 'krillion-low-power';
    const SEEN = `<!doctype html><meta charset=utf-8><script>window.__seen = localStorage.getItem('${KEY}')</script><div class="smartbanner">x</div>`;
    for (const [before, expected, what] of [[null, '1', 'not chosen yet: on'], ['0', '0', 'switched off in the game: stays off'], ['1', '1', 'already on: stays on']]) {
      const c = await browser.newContext({ viewport: { width: 880, height: 540 } });
      const p = await c.newPage();
      await p.route('**/*', r => r.fulfill({ contentType: 'text/html', body: SEEN }));
      await p.goto('https://krillion.test/'); await p.evaluate(([k, v]) => { localStorage.clear(); localStorage.setItem('other', 'kept'); if (v !== null) localStorage.setItem(k, v); }, [KEY, before]);
      await p.addInitScript({ content: tvScript });
      await p.goto('https://krillion.test/play'); await p.waitForLoadState('load');
      const r = await p.evaluate((k) => ({ seen: window.__seen, now: localStorage.getItem(k), other: localStorage.getItem('other'), n: localStorage.length, banner: getComputedStyle(document.querySelector('.smartbanner')).display }), KEY);
      ok(r.seen === expected && r.now === expected, `TV preference, ${what} (the page's first script saw ${JSON.stringify(r.seen)})`);
      ok(r.other === 'kept' && r.n === 2, 'nothing else in the storage is touched');
      ok(r.banner === 'none', 'and the rest of the display script still runs');
      await c.close();
    }
    // A page whose storage is closed (a browser in a private mode throws): the script goes on.
    const c = await browser.newContext({ viewport: { width: 880, height: 540 } });
    const p = await c.newPage();
    await p.addInitScript(() => { Object.defineProperty(window, 'localStorage', { get() { throw new Error('closed'); } }); });
    await p.addInitScript({ content: tvScript });
    await p.route('**/*', r => r.fulfill({ contentType: 'text/html', body: '<!doctype html><div class="smartbanner">x</div>' }));
    const errors = []; p.on('pageerror', e => errors.push(e.message));
    await p.goto('https://krillion.test/'); await p.waitForLoadState('load');
    ok(await p.evaluate(() => getComputedStyle(document.querySelector('.smartbanner')).display) === 'none' && errors.length === 0, 'closed storage: no error, the display script still runs');
    await c.close();
  } else ok(false, 'display-tv-krillion.js is missing (run the display unit tests first)');
  await browser.close();
  console.log(bad ? `\n${bad} FAILED` : '\nall passed');
  process.exit(bad ? 1 : 0);
})();
