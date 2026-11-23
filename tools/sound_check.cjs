// Real-Chromium check of the script that looks after a page's sound on the TV (SoundScript): a page that asks for no particular audio
// buffer gets a longer one, a page that asks for a long one keeps its own, nothing about the audio context looks different to the
// page, and the numbers for the report come out in the shape the app reads.
// The same for the script that counts how hard a page works the box (PageBusy).
// 1) ./gradlew :app:testPhoneDebugUnitTest --tests "app.couch.alhasapa.player.*"           (writes app/build/display-scripts/sound-*.js, busy-*.js)
// 2) node tools/sound_check.cjs app/build/display-scripts                                    (needs the `playwright` package)
const { chromium } = require('playwright');
const fs = require('fs');
const dir = process.argv[2];
const TAKE = fs.readFileSync(dir + '/sound-take.js', 'utf8');
let bad = 0;
const ok = (c, m) => { console.log((c ? 'ok   ' : 'FAIL ') + m); if (!c) bad++; };
// One second of silence as a WAV file, for an <audio> that really plays.
const wav = (() => { const n = 8000, b = Buffer.alloc(44 + n, 128); b.write('RIFF', 0); b.writeUInt32LE(36 + n, 4); b.write('WAVEfmt ', 8); b.writeUInt32LE(16, 16);
  b.writeUInt16LE(1, 20); b.writeUInt16LE(1, 22); b.writeUInt32LE(8000, 24); b.writeUInt32LE(8000, 28); b.writeUInt16LE(1, 32); b.writeUInt16LE(8, 34); b.write('data', 36); b.writeUInt32LE(n, 40); return b; })();

async function page(browser, mode) {
  const ctx = await browser.newContext();
  if (mode) await ctx.addInitScript({ content: fs.readFileSync(`${dir}/sound-${mode}.js`, 'utf8') });
  const p = await ctx.newPage(); const errors = []; p.on('pageerror', e => errors.push(String(e)));
  await p.route('**/*', r => r.request().url().endsWith('.wav') ? r.fulfill({ contentType: 'audio/wav', body: wav })
    : r.fulfill({ contentType: 'text/html', body: '<!doctype html><meta charset=utf-8><video id=v></video>' }));
  await p.goto('https://game.test/');
  return { p, errors, close: () => ctx.close() };
}
const probe = () => {
  const lat = o => { const c = o === undefined ? new AudioContext() : new AudioContext(o); const b = c.baseLatency; c.close(); return b; };
  const a = new AudioContext();
  class Mine extends AudioContext { hello() { return 'hi'; } }
  const m = new Mine();
  const errs = {};
  try { new AudioContext({ sampleRate: 1 }); errs.rate = 'none'; } catch (e) { errs.rate = e.name; }
  try { new AudioContext(5); errs.type = 'none'; } catch (e) { errs.type = e.name; }
  return {
    plain: a.baseLatency, interactive: lat({ latencyHint: 'interactive' }), balanced: lat({ latencyHint: 'balanced' }), short: lat({ latencyHint: 0.02 }),
    playback: lat({ latencyHint: 'playback' }), long: lat({ latencyHint: 0.3 }), rateKept: new AudioContext({ sampleRate: 22050 }).sampleRate,
    is: [a instanceof AudioContext, a instanceof BaseAudioContext, a.constructor === window.AudioContext, Object.prototype.toString.call(a), AudioContext.name,
      typeof AudioContext.prototype.createGain, 'createStereoPanner' in AudioContext.prototype].join(),
    sub: [m.hello(), m instanceof Mine, m instanceof AudioContext].join(), subLatency: m.baseLatency, errs,
  };
};

(async () => {
  const browser = await chromium.launch({ args: ['--autoplay-policy=no-user-gesture-required'] });
  const bare = await page(browser, null); const B = await bare.p.evaluate(probe); await bare.close();
  ok(B.plain < B.playback && B.playback < B.long, `this browser: no hint ${ms(B.plain)}, "playback" ${ms(B.playback)}, 0.3 s ${ms(B.long)}`);

  for (const mode of ['playback', 'long', 'page']) {
    const t = await page(browser, mode); const R = await t.p.evaluate(probe);
    const want = mode === 'playback' ? B.playback : mode === 'long' ? R.long : B.plain;
    if (mode === 'long') ok(R.plain > B.playback * 3, `long: a page that asks for nothing gets the longest buffer (${ms(R.plain)})`);
    else ok(R.plain === want, `${mode}: a page that asks for nothing gets ${ms(R.plain)} (wanted ${ms(want)})`);
    if (mode !== 'page') {
      ok(R.interactive === R.plain && R.balanced === R.plain && R.short === R.plain, `${mode}: so does one that asks for the least delay ("interactive", "balanced", 20 ms)`);
      ok(R.subLatency === R.plain, `${mode}: and a page's own class built on AudioContext`);
    } else ok(R.interactive === B.interactive && R.short === B.short && R.playback === B.playback, 'page: every wish of the page is left as it is');
    ok(R.long === B.long, `${mode}: a page that asks for a longer buffer than ours keeps its own (${ms(R.long)})`);
    if (mode === 'playback') ok(R.playback === B.playback, 'playback: a page that asks for "playback" itself gets just that');
    ok(R.rateKept === 22050, `${mode}: the page's other wishes (the sample rate) are passed on`);
    ok(R.is === B.is && R.sub === B.sub, `${mode}: to the page an audio context is what it always was (${R.is}; ${R.sub})`);
    ok(R.errs.rate === B.errs.rate && R.errs.type === B.errs.type, `${mode}: a wrong wish fails exactly as it would (${R.errs.rate}, ${R.errs.type})`);

    // The numbers for the report.
    await t.p.evaluate(async () => {
      const c = new AudioContext(); const o = c.createOscillator(); o.connect(c.destination); o.start(); window.__c = c;
      const a = new Audio('https://game.test/one.wav'); await a.play(); window.__a = a;
    });
    await t.p.waitForTimeout(1500);
    const line = await t.p.evaluate(TAKE);
    const f = line.split(',');
    ok(f.length === 20 && f[0] === mode && f.slice(1).every(x => /^-?\d+$/.test(x)), `${mode}: the report line is the mode and 19 whole numbers (${line})`);
    ok(Number(f[1]) >= 1 && Number(f[2]) >= 1 && Number(f[3]) > 8000, `${mode}: it counts the page's audio contexts and their rate`);
    ok(Number(f[12]) === 1 && Number(f[14]) === 1, `${mode}: one <audio> that was played (though it is not in the page) and one <video> that is`);
    ok(Number(f[6]) === 1 ? Number(f[8]) >= 0 && Number(f[7]) >= 5 : Number(f[8]) === -1, `${mode}: gaps are counted where the browser counts them (${f[6] === '1' ? f[8] + ' in ' + f[7] / 10 + ' s' : 'not in this browser'})`);
    ok(t.errors.length === 0, `${mode}: no errors in the page (${t.errors.join(' | ') || 'none'})`);
    // Running it again (a WebView without document-start scripts runs it as each page begins) changes nothing.
    const before = await t.p.evaluateHandle(() => window.AudioContext);
    await t.p.evaluate(fs.readFileSync(`${dir}/sound-${mode}.js`, 'utf8'));
    ok(await t.p.evaluate(b => window.AudioContext === b && typeof window.__couchSound.take === 'function', before), `${mode}: run twice, it is there once`);
    await t.close();
  }
  // The load script (PageBusy): the browser's own count of long tasks, added up; a second take starts from nothing.
  if (fs.existsSync(dir + '/busy-install.js')) {
    const INSTALL = fs.readFileSync(dir + '/busy-install.js', 'utf8'), BUSY = fs.readFileSync(dir + '/busy-take.js', 'utf8');
    const ctx = await browser.newContext(); await ctx.addInitScript({ content: INSTALL });
    const p = await ctx.newPage(); const errors = []; p.on('pageerror', e => errors.push(String(e)));
    await p.route('**/*', r => r.fulfill({ contentType: 'text/html', body: '<!doctype html><meta charset=utf-8><p>game</p>' }));
    await p.goto('https://game.test/'); await p.waitForTimeout(300);
    const idle = (await p.evaluate(BUSY)).split(',').map(Number);
    ok(idle.length === 5 && idle[4] === 1 && idle[0] > 200 && idle[2] < 200, `load: an idle page has (next to) nothing to count (${idle.join()})`);
    await p.evaluate(() => new Promise(done => { let n = 0; const work = () => { const t = performance.now(); while (performance.now() - t < 120); if (++n < 5) setTimeout(work, 10); else setTimeout(done, 100); }; work(); }));
    const busy = (await p.evaluate(BUSY)).split(',').map(Number);
    ok(busy[1] >= 4 && busy[2] >= 450 && busy[2] <= busy[0] && busy[3] >= 100, `load: five pieces of work of 120 ms each are counted (${busy.join()})`);
    await p.waitForTimeout(300);
    const after = (await p.evaluate(BUSY)).split(',').map(Number);
    ok(after[1] === 0 && after[2] === 0, `load: the next look starts from nothing (${after.join()})`);
    await p.evaluate(INSTALL);
    ok((await p.evaluate(BUSY)).split(',').length === 5 && errors.length === 0, 'load: run twice it is there once, and no errors in the page');
    await ctx.close();
  }
  await browser.close();
  console.log(bad ? `\n${bad} FAILED` : '\nall good');
  process.exit(bad ? 1 : 0);
})();
function ms(s) { return Math.round(s * 1000) + ' ms'; }
