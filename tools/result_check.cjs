// Real-Chromium check of the end-screen watcher (ResultWatch): it finds a game's own Share or Copy button only when it is really on
// the screen, says where it is in the WebView's own pixels, and looks at nothing else.
// 1) ./gradlew :app:testPhoneDebugUnitTest --tests "app.couch.alhasapa.results.*"   (writes app/build/display-scripts/result-watch.js)
// 2) node tools/result_check.cjs app/build/display-scripts                           (needs the `playwright` package)
const { chromium } = require('playwright');
const fs = require('fs');
const script = fs.readFileSync(process.argv[2] + '/result-watch.js', 'utf8');
let bad = 0;
const ok = (c, m) => { console.log((c ? 'ok   ' : 'FAIL ') + m); if (!c) bad++; };
const READY = "window.__couchResult.ready()", AT = "window.__couchResult.at()";
const page = body => `<!doctype html><meta charset=utf-8><style>body{margin:0;font:16px sans-serif}button{font:inherit;padding:10px 18px}</style>${body}`;

(async () => {
  const browser = await chromium.launch();
  const ctx = await browser.newContext({ viewport: { width: 400, height: 700 }, deviceScaleFactor: 2 });
  await ctx.addInitScript({ content: script });
  const p = await ctx.newPage();
  const errors = []; p.on('pageerror', e => errors.push(String(e)));
  const at = async html => { await p.route('**/*', r => r.fulfill({ contentType: 'text/html', body: page(html) })); await p.goto('https://game.test/' + Math.random()); return [await p.evaluate(READY), await p.evaluate(AT)]; };

  let [r, a] = await at('<h1>Wordle</h1><div id=board style="height:300px"></div>');
  ok(r === 0 && a === '', 'while playing (no share button): nothing');
  [r, a] = await at('<div style="position:absolute;top:400px;left:100px"><button id=s>Share</button></div>');
  const box = await p.evaluate(() => { const b = document.getElementById('s').getBoundingClientRect(); return [Math.round((b.left + b.width / 2) * 2), Math.round((b.top + b.height / 2) * 2)]; });
  ok(r === 1 && a === box.join(','), `the end screen's Share button: found, at ${a} (its middle, in device pixels: ${box})`);
  [r] = await at('<button>שתפו את התוצאה</button>');
  ok(r === 1, 'in Hebrew too');
  [r] = await at('<a href="#">Copy results</a>');
  ok(r === 1, 'a link that copies');
  [r] = await at('<button style="display:none">Share</button><button style="visibility:hidden">Copy</button><button style="opacity:0">Share</button>');
  ok(r === 0, 'hidden ones are not the end screen');
  [r] = await at('<div style="height:2000px"></div><button>Share</button>');
  ok(r === 0, 'nor one below the screen');
  [r] = await at('<button>Share your thoughts about the new daily puzzle format</button><button>Shared folders</button>');
  ok(r === 0, 'nor a longer label that merely begins like one, nor another word');
  [r] = await at('<button aria-label="Share"><svg width="16" height="16"></svg></button>');
  ok(r === 0, 'an icon with no words (a header\'s share while playing) is not the end');
  // Run twice, it is there once; nothing in the page is changed.
  const before = await p.evaluate(() => document.documentElement.outerHTML);
  await p.evaluate(script); await p.evaluate(READY);
  ok(before === await p.evaluate(() => document.documentElement.outerHTML), 'the page is left exactly as it was');
  ok(errors.length === 0, 'no errors in the page (' + (errors.join(' | ') || 'none') + ')');

  // The end screen in words (1.9.10, ResultWatch.LINES), for a game that copies nothing: its short result lines, from the page and
  // a frame of the same site inside it; not the article around the game, not a line with no result in it.
  const LINES = fs.readFileSync(process.argv[2] + '/result-lines.js', 'utf8');
  const article = 'במחקר שפורסם השבוע נמצא כי יותר מ-50% מהשחקנים משחקים בבוקר, לפני העבודה, ושהם נוטים לחזור למשחק גם למחרת, כך לפי הסקר המלא.';
  await p.route('**/*', r => r.request().url().endsWith('/frame')
    ? r.fulfill({ contentType: 'text/html', body: page('<p>17 מתוך 20 תשובות נכונות</p>') })
    : r.fulfill({ contentType: 'text/html', body: page(`<p>${article}</p><h2>כל הכבוד!</h2><p>פתרתם ב-3 ניסיונות</p><p>טובים יותר מ-82% מהשחקנים</p><p>המילה הייתה: שלום</p><p style="display:none">5 טעויות</p><iframe src="/frame"></iframe>`) }));
  await p.goto('https://game.test/end'); await p.waitForTimeout(300);
  const lines = (await p.evaluate(LINES)).split('\n');
  ok(lines.includes('פתרתם ב-3 ניסיונות') && lines.includes('טובים יותר מ-82% מהשחקנים'), `the result lines are read (${JSON.stringify(lines)})`);
  ok(lines.includes('17 מתוך 20 תשובות נכונות'), 'and a frame of the same site');
  ok(!lines.some(l => l.includes('שלום')), 'not the line with the answer in it');
  ok(!lines.some(l => l.includes('במחקר')), 'not the article around the game');
  ok(!lines.some(l => l.includes('5 טעויות')), 'not what is hidden on the page');
  await browser.close();
  console.log(bad ? `\n${bad} FAILED` : '\nall good');
  process.exit(bad ? 1 : 0);
})();
