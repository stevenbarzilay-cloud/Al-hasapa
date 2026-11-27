// Real-Chromium check of the TV's light effects for a heavy site (DisplayScript.LIGHT_FX, Dialed): blurs and frosted glass leave the
// page's style sheets and the inline styles its scripts animate; other filters, a blur that keeps something locked, colours and
// layout stay.
// 1) ./gradlew :app:testTvDebugUnitTest --tests "app.couch.alhasapa.display.*"   (writes app/build/display-scripts/display-tv-dialed.js)
// 2) node tools/light_check.cjs app/build/display-scripts                         (needs the `playwright` package)
const { chromium } = require('playwright');
const fs = require('fs');
const script = fs.readFileSync(process.argv[2] + '/display-tv-dialed.js', 'utf8');
let bad = 0;
const ok = (c, m) => { console.log((c ? 'ok   ' : 'FAIL ') + m); if (!c) bad++; };
const CSS2 = `.late{filter:blur(12px) saturate(2)}`;
// A sheet from another site (as Dialed's are): the page's scripts cannot read it, its blurs must go all the same.
const CDN = `.cdnblur{filter:blur(18px)}.cdnglass{backdrop-filter:blur(12px)}.glow::before{content:"";display:block;width:50px;height:50px;filter:blur(20px);background:red}
@keyframes rise{0%{filter:blur(4px);transform:translateY(10px)}to{filter:blur(0);transform:none}} .rise{animation:rise 5s infinite}`;
const PAGE = `<!doctype html><meta charset=utf-8>
<style>
 .aur{filter:blur(70px);opacity:.5;position:absolute;inset:-30%;background:radial-gradient(#f0f,#0000)}
 .glass{backdrop-filter:blur(14px) saturate(160%);-webkit-backdrop-filter:blur(14px) saturate(160%);background:#fff3}
 .dark{filter:brightness(0)}
 .mixed{filter:blur(10px) brightness(1.5)}
 .frame{will-change:filter;filter:blur(0px);background:rgb(30,120,200)}
 .grid.locked{filter:blur(3px)}
 @keyframes slide{0%{filter:blur(4px);opacity:0}to{filter:blur(0);opacity:1}}
 .anim{animation:slide 10s infinite}
 @media (min-width:1px){.inmedia{filter:blur(6px)}}
</style>
<div class=aur id=aur></div><div class=glass id=glass>label</div><svg class=dark id=dark></svg><div class=mixed id=mixed></div>
<div class=frame id=frame>colour</div><div class="grid locked" id=locked>locked</div><div class=anim id=anim></div><div class=inmedia id=inmedia></div>
<div id=inline>inline</div><div class=late id=late></div>
<link rel=stylesheet href="https://cdn.other.example/site.css">
<div class=cdnblur id=cdnblur></div><div class=cdnglass id=cdnglass></div><div class=glow id=glow></div><div id=riser></div>
<script>
  setTimeout(()=>{const l=document.createElement('link');l.rel='stylesheet';l.href='/late.css';document.head.appendChild(l)},100);
  setTimeout(()=>{document.getElementById('inline').style.filter='blur(5px) contrast(1.2)'},300);
  setTimeout(()=>{document.getElementById('riser').className='rise'},400);
</script>`;

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 960, height: 540 } });
  await page.addInitScript({ content: script });
  await page.route('**/*', r => {
    const u = r.request().url();
    if (u.endsWith('/late.css')) return r.fulfill({ contentType: 'text/css', body: CSS2 });
    if (u.startsWith('https://cdn.other.example/')) return r.fulfill({ contentType: 'text/css', body: CDN });
    return r.fulfill({ contentType: 'text/html', body: PAGE });
  });
  await page.goto('https://dialed.example/color'); await page.waitForLoadState('load');
  await page.waitForTimeout(900);
  const m = await page.evaluate(() => {
    const cs = id => getComputedStyle(document.getElementById(id));
    const kf = [...document.styleSheets].flatMap(s => { try { return [...s.cssRules] } catch (e) { return [] } })
      .filter(r => r.type === CSSRule.KEYFRAMES_RULE).flatMap(r => [...r.cssRules].map(k => k.style.filter));
    return {
      aur: cs('aur').filter, glass: cs('glass').backdropFilter, dark: cs('dark').filter, mixed: cs('mixed').filter,
      frame: cs('frame').filter, frameWill: cs('frame').willChange, locked: cs('locked').filter, kf, inmedia: cs('inmedia').filter,
      inline: document.getElementById('inline').style.filter, late: cs('late').filter, n: window.__couchLightCount && window.__couchLightCount(),
      colour: cs('frame').backgroundColor,
      cdnblur: cs('cdnblur').filter, cdnglass: cs('cdnglass').backdropFilter,
      glow: getComputedStyle(document.getElementById('glow'), '::before').filter, riser: cs('riser').filter, riserMoves: cs('riser').transform,
      unreadable: [...document.styleSheets].filter(s => { try { s.cssRules; return false } catch (e) { return true } }).length,
    };
  });
  ok(m.aur === 'none', `the 70 px blur is gone (${m.aur})`);
  ok(m.glass === 'none', `the frosted glass is gone (${m.glass})`);
  ok(m.dark === 'brightness(0)', `another filter stays (${m.dark})`);
  ok(m.mixed === 'brightness(1.5)', `a blur among other filters goes, the rest stays (${m.mixed})`);
  ok(m.frame === 'none' && m.frameWill === 'auto', `a blur that is animated goes, and its layer hint (${m.frame}, ${m.frameWill})`);
  ok(m.locked === 'blur(3px)', `a blur that keeps something locked stays (${m.locked})`);
  ok(m.kf.every(f => !f.includes('blur')), `keyframes lose their blurs (${JSON.stringify(m.kf)})`);
  ok(m.inmedia === 'none', `inside a media rule too (${m.inmedia})`);
  ok(!m.inline.includes('blur') && m.inline.includes('contrast'), `an inline blur set by the page's script goes (${m.inline})`);
  ok(!m.late.includes('blur') && m.late.includes('saturate'), `a style sheet that arrives later too (${m.late})`);
  ok(m.colour === 'rgb(30, 120, 200)', `the colours are the page's own (${m.colour})`);
  ok(m.unreadable === 1, `one sheet cannot be read by the page (${m.unreadable}), as on Dialed`);
  ok(m.cdnblur === 'none', `a blur from that sheet goes all the same (${m.cdnblur})`);
  ok(m.cdnglass === 'none', `and its frosted glass (${m.cdnglass})`);
  ok(m.glow === 'none', `a glow drawn before an element loses its blur (${m.glow})`);
  ok(m.riser === 'none' && m.riserMoves !== 'none', `an animation that blurs keeps its motion without the blur (${m.riser}, ${m.riserMoves})`);
  ok(m.n > 0, `counted ${m.n} changes`);
  await browser.close();
  console.log(bad ? `\n${bad} FAILED` : '\nall passed');
  process.exit(bad ? 1 : 0);
})();
