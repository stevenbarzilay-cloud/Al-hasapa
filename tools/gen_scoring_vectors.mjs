// Golden vectors for the Kotlin scoring port: runs the ORIGINAL prototype's functions (couch-artifact/index.html)
// over a corpus of inputs and records what they return. ScoringGoldenTest must reproduce every line.
//   node tools/gen_scoring_vectors.mjs
import fs from "node:fs"; import vm from "node:vm"; import path from "node:path";
const root = path.join(path.dirname(new URL(import.meta.url).pathname), "..");
const html = fs.readFileSync(path.join(root, "couch-artifact/index.html"), "utf8");
const a = html.indexOf("const METRIC="), b = html.indexOf("/* ================= helpers");
const ctx = { state: { log: {} } }; vm.createContext(ctx);
vm.runInContext(html.slice(a, b) + "\nthis.api={parseResult,resultPts,entryPts,duelWinner,fmtT,fmtVal,dayPts,bestTime,KIND_LABEL};", ctx);
const api = ctx.api, J = x => JSON.parse(JSON.stringify(x === undefined ? null : x));   // normalise like a JSON round trip

const KINDS = ["guesses", "mistakes", "hints", "time", "words", "none"];
const squares = rows => rows.join("\n");
const conn = (...r) => "Connections\nPuzzle #512\n" + squares(r);
const TEXTS = [
  "", "   ", "\n\t ", "hello", "no result here",
  // guesses
  "Wordle 1,618 3/6", "Wordle 1,618 X/6", "Wordle 1,618 x/6", "Wordle 1 1/6", "Wordle 99 6/6", "wordle 5 2/6 *", "Wordle 500 4/6\n⬛🟨⬛⬛⬛\n🟩🟩🟩🟩🟩",
  "3 / 6", "3/6", "X/6", "7/6", "0/6", "10/12", "12/6", "1/10", "2/5", "ניסיון 3/6", "ניחוש 4/6 מצוין", "abc3/6", "3/6abc", "a 3/6 b",
  "2 guesses", "1 guess", "3 Guesses", "4 clues", "1 clue", "5 ניחושים", "3 ניסיונות", "Pinpoint #504 | 2 guesses", "Pinpoint #504 | 5 guesses", "I did it in 12 guesses",
  // mistakes
  conn("🟨🟨🟨🟨", "🟩🟩🟩🟩", "🟦🟦🟦🟦", "🟪🟪🟪🟪"),
  conn("🟨🟨🟨🟨", "🟩🟩🟦🟩", "🟩🟩🟩🟩", "🟦🟦🟦🟦", "🟪🟪🟪🟪"),
  conn("🟩🟩🟦🟩", "🟨🟨🟨🟨", "🟩🟩🟩🟩", "🟦🟦🟦🟦", "🟪🟪🟪🟪"),
  conn("🟨🟦🟩🟪", "🟨🟨🟨🟨", "🟩🟩🟩🟩", "🟦🟦🟦🟦", "🟪🟪🟪🟪"),
  conn("🟨🟦🟩🟪", "🟨🟦🟩🟪", "🟨🟦🟩🟪", "🟨🟦🟩🟪"),
  conn("🟨🟦🟩🟪", "🟨🟦🟩🟪", "🟨🟦🟩🟪", "🟨🟦🟩🟪", "🟨🟨🟨🟨"),
  conn("🟨🟦🟩🟪", "🟦🟦🟩🟩", "🟨🟨🟦🟦", "🟪🟪🟩🟩", "🟨🟨🟨🟨"),
  "Connections\r\n🟨🟨🟨🟨\r\n🟩🟩🟩🟩\r\n🟦🟦🟦🟦\r\n🟪🟪🟪🟪", "🟨 🟨 🟨 🟨\n🟩 🟩 🟩 🟩", "🟨🟨🟨\n🟩🟩🟩", "🟨🟨🟨🟨🟨\n🟩🟩🟩🟩🟩",
  "2 mistakes", "0 mistakes", "1 mistake", "4 mistakes", "9 mistakes", "3 טעויות", "טעות 1", "1 טעות", "שתי טעויות",
  // hints
  "Strands #270\n“Hello”\n💡🔵🔵🟡", "Strands #1\n🔵🔵🔵🔵", "Strands #1\n🟡🔵", "💡💡💡💡", "💡", "Strands\n🔵", "3 hints", "1 hint", "0 hints", "2 רמזים", "רמז 1", "Strands without anything",
  // time
  "Queens #612 | 1:24", "Queens #612 | 0:42\nFirst 👑", "Zip #200 | 0:42\nWith 1 backtrack", "Tango #99 | 12:05", "Mini Sudoku #5 | 1:02:03", "1:02:03", "0:00", "59:59", "100:00", "9:5", "12:345",
  "took 45 seconds", "30 sec", "5 שניות", "1:5 2:30", "Crossclimb #3 | 2:15 • 4:20", "time 3:10:00:00",
  // words
  "12 words", "1 word", "7 מילים", "2 words", "30 words", "Letter Boxed: 3 words",
  // mixed / noise
  "Wordle 1 3/6 and 1:24 and 2 mistakes", "💡 3/6", "🟨🟨🟨🟨 2:00",
];
const parse = [];
for (const kind of KINDS) for (const text of TEXTS) parse.push({ kind, text, out: J(api.parseResult(text, kind)) });

// state fixtures for personal bests: id -> day -> entry
const LOG = {
  "2026-01-01": { queens: { w: "", r: { v: 100, kind: "time", fail: false } }, zip: { w: "", r: { v: 60, kind: "time", fail: false } } },
  "2026-01-02": { queens: { w: "", r: { v: 80, kind: "time", fail: false } }, zip: { w: "", r: { v: 0, kind: "time", fail: true } }, tango: { w: "", r: { v: 10, kind: "guesses", fail: false } } },
  "2026-01-03": { queens: { w: "", d: { a: { v: 70, fail: false }, b: { v: 90, fail: false } } }, mini: { w: "t", r: { v: 95, kind: "time", fail: false } } },
  "2026-01-04": { wordle: { w: "a", r: { v: 3, max: 6, kind: "guesses", fail: false } }, connections: { w: "", r: { v: 1, kind: "mistakes", fail: false } } },
  "2026-01-05": { strands: { w: "b" }, hzconn: { w: "t", r: { v: 0, kind: "mistakes", fail: false } } },
};
ctx.state.log = LOG;
const points = [];
const push = (id, r, day) => points.push({ id, r, day, pts: J(api.resultPts(id, r, day)), best: J(api.bestTime(id, day)) });
const R = (kind, v, extra = {}) => ({ v, kind, fail: false, ...extra });
for (const v of [1, 2, 3, 4, 5, 6, 7]) for (const max of [undefined, 5, 6]) push("wordle", R("guesses", v, max === undefined ? {} : { max }), "2026-02-01");
push("wordle", R("guesses", 7, { max: 6, fail: true }), "2026-02-01"); push("wordle", R("guesses", 2, { max: 6 }), "2026-02-01");
for (const v of [0, 1, 2, 3, 4, 5, 9]) { push("connections", R("mistakes", v), "2026-02-01"); push("strands", R("hints", v), "2026-02-01"); }
push("connections", R("mistakes", 4, { fail: true }), "2026-02-01");
for (const v of [0, 1, 2, 3, 4, 5, 6, 7, 8, 10, 12]) push("letterboxed", R("words", v), "2026-02-01");
// times against the bests above: queens best (other days) = 70? no: duels carry no r, so best = min(100,80) = 80
for (const v of [30, 79, 80, 81, 99, 100, 101, 120, 200]) push("queens", R("time", v), "2026-02-01");
for (const v of [30, 59, 60, 61, 75, 76, 100]) push("zip", R("time", v), "2026-02-01");      // zip: best 60 (a failed 0 is ignored)
push("queens", R("time", 100), "2026-01-01"); push("queens", R("time", 60), "2026-01-02");     // the same day is excluded from "best"
push("tango", R("time", 50), "2026-02-01"); push("mini", R("time", 96), "2026-02-01"); push("mini", R("time", 90), "2026-02-01");
push("queens", R("time", 70, { fail: true }), "2026-02-01");
for (const r of [null, {}, { v: undefined, kind: "time" }, { v: null, kind: "guesses" }, { v: "", kind: "hints" }, R("none", 3), { v: 3, kind: "weird", fail: false }, { v: 0, kind: "guesses", max: 6, fail: false }])
  push("wordle", r, "2026-02-01");

const entries = [];
const E = (id, e, day) => entries.push({ id, e, day, pts: J(api.entryPts(id, e, day)) });
E("wordle", { w: "a", r: R("guesses", 3, { max: 6 }) }, "2026-02-01"); E("wordle", { w: "" }, "2026-02-01"); E("wordle", null, "2026-02-01"); E("wordle", undefined, "2026-02-01");
E("queens", { w: "", d: { a: { v: 80 } } }, "2026-02-01"); E("connections", { w: "t", r: R("mistakes", 1) }, "2026-02-01"); E("queens", { w: "", r: R("time", 60) }, "2026-02-01");
E("strands", { w: "b", r: R("hints", 2, { fail: true }) }, "2026-02-01");
const days = [];
for (const day of ["2026-01-01", "2026-01-02", "2026-01-03", "2026-01-04", "2026-01-05", "2026-03-09"]) days.push({ day, pts: J(api.dayPts(day)) });

const duel = [];
const sides = [{ v: 84 }, { v: 90 }, { v: 84 }, { v: 0, fail: true }, null, undefined, { v: 3 }, { v: 2 }, { v: 0 }];
for (const x of sides) for (const y of sides) duel.push({ a: J(x), b: J(y), out: J(api.duelWinner({ d: { a: x, b: y } })) });
duel.push({ a: null, b: null, noD: true, out: J(api.duelWinner(null)) }, { a: null, b: null, noD: true, out: J(api.duelWinner({ w: "t" })) });

const fmt = [];
for (const s of [0, 1, 5, 59, 60, 61, 84, 599, 600, 3599, 3600, 3723, 36000, 59.4, 59.5, 0.4, 1.5]) fmt.push({ fn: "fmtT", s, out: api.fmtT(s) });
for (const kind of KINDS) for (const r of [null, R(kind, 3, { max: 6 }), R(kind, 84), { v: 0, kind, fail: true }, R(kind, 0)]) fmt.push({ fn: "fmtVal", kind, r, out: api.fmtVal(kind, r) });

const out = { v: 1, log: LOG, parse, points, entries, days, duel, fmt, kinds: api.KIND_LABEL };
const dest = path.join(root, "app/src/test/resources/scoring_vectors.json");
fs.writeFileSync(dest, JSON.stringify(out) + "\n");
console.log(`wrote ${dest}: ${parse.length} parse, ${points.length} points, ${entries.length} entries, ${days.length} days, ${duel.length} duel, ${fmt.length} fmt cases; ${(fs.statSync(dest).size / 1024).toFixed(0)} KB`);
