// Real-Chromium check of the injected display script (viewport, light mode, banner CSS).
// 1) ./gradlew :app:testDebugUnitTest --tests "app.couch.alhasapa.display.*"   (writes app/build/display-scripts/)
// 2) node tools/display_check.cjs app/build/display-scripts                     (needs the `playwright` package)
const { chromium } = require('playwright');
const fs = require('fs');
const D = process.argv[2];
const cases = JSON.parse(fs.readFileSync(D + '/cases.json', 'utf8'));
let bad = 0;
const ok = (c, m) => { console.log((c ? 'ok   ' : 'FAIL ') + m); if (!c) bad++; };

const PAGE = (meta) => `<!doctype html><html><head><meta charset=utf-8>${meta}<style>html,body{margin:0}.bar{position:fixed;top:0;left:0;right:0;height:50px;background:#eee}.full{height:100vh;background:linear-gradient(#fff,#ddd)}</style></head>
<body><div class="smartbanner" style="height:60px;background:red">get the app</div><div class="full" id="f">board</div></body></html>`;

(async () => {
  const browser = await chromium.launch();
  for (const [name, c] of Object.entries(cases)) {
    const script = fs.readFileSync(`${D}/${name}.js`, 'utf8');
    for (const [label, meta] of [['page with width=device-width', '<meta name="viewport" content="width=device-width, initial-scale=1">'], ['page with no viewport meta', ''], ['page with its own fixed meta', '<meta name="viewport" content="width=500, user-scalable=yes">']]) {
      const ctx = await browser.newContext({
        viewport: { width: Math.round(c.webWidth), height: Math.round(c.webHeight) },
        isMobile: true, hasTouch: true, deviceScaleFactor: 1,
      });
      const page = await ctx.newPage();
      await page.addInitScript({ content: script });                       // what addDocumentStartJavaScript does
      await page.route('**/*', r => r.fulfill({ contentType: 'text/html', body: PAGE(meta) }));
      await page.goto('https://example.test/games/x');
      await page.waitForLoadState('load');
      const m = await page.evaluate(() => ({
        w: innerWidth, h: innerHeight,
        vh100: document.getElementById('f').getBoundingClientRect().height,
        banner: getComputedStyle(document.querySelector('.smartbanner')).display,
        scheme: getComputedStyle(document.documentElement).colorScheme,
        metaCount: document.querySelectorAll('meta[name=viewport]').length,
        content: (document.querySelector('meta[name=viewport]') || {}).content,
      }));
      if (c.virtualWidth !== null) {
        ok(Math.abs(m.w - c.virtualWidth) <= 1, `${name} / ${label}: page sees width ${m.w} (want ${c.virtualWidth})`);
        ok(Math.abs(m.h - c.virtualHeight) <= 3, `${name} / ${label}: page sees height ${m.h} (want ~${Math.round(c.virtualHeight)})`);
        ok(Math.abs(m.vh100 - m.h) <= 1, `${name} / ${label}: 100vh is the whole virtual height (${m.vh100})`);
        ok(m.metaCount === 1, `${name} / ${label}: one viewport meta`);
      } else {
        // Native = the page decides its own viewport: whatever it asked for is exactly what it gets.
        const own = meta.match(/content="([^"]*)"/);
        ok(own ? m.content === own[1] : m.metaCount === 0, `${name} / ${label}: the page's own viewport is untouched (${m.content === undefined ? 'no meta' : m.content})`);
      }
      ok(m.scheme.includes('light'), `${name} / ${label}: light only (${m.scheme})`);
      if (name === 'portrait') ok(m.banner === 'none', `${name} / ${label}: the app banner is hidden`);
      await ctx.close();
    }
  }
  // Re-running the script on the live page (what a profile change does) switches the viewport and is idempotent.
  {
    const a = fs.readFileSync(`${D}/portrait.js`, 'utf8'), b = fs.readFileSync(`${D}/portrait-zoom125.js`, 'utf8');
    const c = cases.portrait;
    const ctx = await browser.newContext({ viewport: { width: Math.round(c.webWidth), height: Math.round(c.webHeight) }, isMobile: true, hasTouch: true, deviceScaleFactor: 1 });
    const page = await ctx.newPage();
    await page.route('**/*', r => r.fulfill({ contentType: 'text/html', body: PAGE('<meta name="viewport" content="width=device-width, initial-scale=1">') }));
    await page.goto('https://example.test/'); await page.evaluate(a);
    const w1 = await page.evaluate(() => innerWidth);
    await page.evaluate(a);
    const w1b = await page.evaluate(() => innerWidth);
    await page.evaluate(b); await page.waitForTimeout(200);
    const w2 = await page.evaluate(() => innerWidth);
    ok(w1 === 430 && w1b === 430, `live: portrait gives 430 (and again 430) -> ${w1}, ${w1b}`);
    ok(w2 === 344, `live: zoom 125 then gives 344 -> ${w2}`);
    ok(await page.evaluate(() => document.querySelectorAll('#couch-display-css').length) === 1, 'live: exactly one style element after three runs');
    await ctx.close();
  }
  await browser.close();
  console.log(bad ? `\n${bad} FAILED` : '\nall passed');
  process.exit(bad ? 1 : 0);
})();
