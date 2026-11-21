// Extracts the game catalogue (GAMES, SITES, METRIC) from the original prototype into app/src/main/assets/games.json,
// so the catalogue is data (editable) and not code. Run once; afterwards games.json is the source of truth:
//   node tools/extract_catalog.mjs
import fs from "node:fs"; import vm from "node:vm"; import path from "node:path";
const root = path.join(path.dirname(new URL(import.meta.url).pathname), "..");
const html = fs.readFileSync(path.join(root, "couch-artifact/index.html"), "utf8");
const a = html.indexOf("const G = q =>"), b = html.indexOf("const KINDS");
const c = html.indexOf("const METRIC="), d = html.indexOf("const isDuel");
const ctx = {}; vm.createContext(ctx);
vm.runInContext(html.slice(a, b) + "\n" + html.slice(c, d) + "\nthis.out={SITES,GAMES,METRIC};", ctx);
const { SITES, GAMES, METRIC } = ctx.out;

// What the prototype did not know: where each site's accounts live and which domains the player may stay on.
const SITE_EXTRA = {
  nyt: { account: true, label: "NYT", domains: ["nytimes.com"], loginUrl: "https://myaccount.nytimes.com/auth/login", sessionCookies: ["NYT-S"], profile: "games" },
  hz:  { account: true, label: "הארץ", domains: ["haaretz.co.il", "haaretz.com"], extraHosts: ["piano.io", "tinypass.com"], loginUrl: "https://www.haaretz.co.il/riddles", sessionCookies: [], profile: "games" },
  li:  { account: true, label: "LinkedIn", domains: ["linkedin.com"], loginUrl: "https://www.linkedin.com/login", sessionCookies: ["li_at"], profile: "player" },
};
const sites = {};
for (const [id, s] of Object.entries(SITES)) sites[id] = { id, name: s.name, color: s.color, account: false, domains: [], profile: "games", ...(SITE_EXTRA[id] || {}) };

const isPlaceholder = u => /^https:\/\/www\.google\.com\/search\?/.test(u);
const games = GAMES.map(g => {
  const o = { id: g.id, n: g.n, s: g.s, k: g.k, m: g.m, u: g.u, d: g.d, metric: METRIC[g.id] || "none" };
  if (g.lib) o.lib = 1;
  if (g.why) o.why = g.why;
  if (isPlaceholder(g.u)) o.placeholder = true;      // a Google search standing in for a direct link: replace it in the app
  return o;
});
const out = {
  v: 1,
  _comment: "The game catalogue. The only thing you normally change is a game's \"u\" (its link), or edit it in the app. " +
            "metric = how a result is scored: guesses | mistakes | hints | time | words | none. placeholder = a search link to replace with a direct one.",
  sites, games,
};
const dest = path.join(root, "app/src/main/assets/games.json");
fs.writeFileSync(dest, JSON.stringify(out, null, 1) + "\n");
console.log(`wrote ${dest}: ${games.length} games, ${Object.keys(sites).length} sites, ${games.filter(g => g.placeholder).length} placeholder links`);
