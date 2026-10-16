// CR-KT-023 golden fixture generator.
// Run:  git -C <Opti> show 3581f49:app.js > app.js
//       node gen_reps_history.js app.js > reps_history.json        (TZ=UTC)
//
// Bug: the Kotlin bulk "Log N reps" sheet (MainViewModel.addHabitReps, pre-CR-KT-023) bumped
// cUp/cDown and logged a `habitReps` event row but wrote NO history point, so Analytics' reps
// metric (which reads only history points) never saw bulk reps. The PWA's addReps writes the point
// via logHistory.
//
// The REAL Opti 3581f49 `addReps`, `scoreHabit`, `logHistory` and helpers (`clamp`, `DIFF`,
// `valueDelta`, `completionReward`, `missDamage`, `repsPerTap`) are cut out of app.js at run time
// and run with `new Function`; `Date` is frozen per step, the HLC `now()` counts up, side effects
// (`gainXp`, `takeDamage`, `buzz`, `floatFx`, `fxGain`, `bumpAvatar`, `save`, `render`, `toast`,
// `bootGateBlocksInput`) are stubbed and `logEvent` is captured.
//
// forward[]  Part A: the fixed Kotlin RpgEngine.addReps must equal the PWA addReps (history,
//            cUp, cDown, value) for the same task and signed n.
// replay[]   Part B: each case is one tap/reps TAPE run in two worlds:
//              pwa     -- every op through the real PWA code (the history the PWA -- and the fixed
//                         Kotlin -- writes): the reference;
//              kotlin  -- the buggy Kotlin: taps write their point, a local bulk-reps op writes
//                         only the counters + a `habitReps` row (dev = MY_DEV); ops made on the PWA
//                         (pulled/imported rows) and post-fix ops wrote their point.
//            `task_before` is the kotlin-world task, `events` its event_logs rows (Kotlin shape),
//            `boundary_id` the pinned replay boundary, `expected_history` the replay reference
//            (repairHistory below = the CR spec). The generator ASSERTS expected_history deep-equals
//            the pwa-world history, point for point (no omitted fields).
// render[]   the real PWA analytics (`metric` source, day group, TZ=UTC) over replayed tasks:
//            expected_metric_by_day, asserted equal to the pwa-world task's and different from the
//            buggy task's (the bug: bulk reps missing from the metric).
"use strict";
process.env.TZ = "UTC";
const fs = require("fs");

const src = fs.readFileSync(process.argv[2], "utf8");

function fnSrc(name) {
  const a = src.indexOf("\nfunction " + name + "(");
  if (a < 0) throw new Error("function not found: " + name);
  let i = src.indexOf("{", a), depth = 0;
  for (; i < src.length; i++) {
    if (src[i] === "{") depth++;
    else if (src[i] === "}" && --depth === 0) break;
  }
  return src.slice(a + 1, i + 1);
}

const helpers = [
  fnSrc("clamp"),
  src.match(/\nconst DIFF = [^\n]*;/)[0],
  fnSrc("valueDelta"), fnSrc("completionReward"), fnSrc("missDamage"), fnSrc("repsPerTap"),
  fnSrc("logHistory"),
].join("\n");
const bodies = [fnSrc("addReps"), fnSrc("scoreHabit")].join("\n");

const runOp = new Function("Date", "S", "now", "logEvent", "gainXp", "takeDamage", "buzz", "floatFx", "fxGain",
  "bumpAvatar", "save", "render", "toast", "bootGateBlocksInput", "_suppressHabitClick", "id", "op", "n",
  helpers + "\n" + bodies + `
  if (op === "reps") { addReps(id, n); return; }
  if (op === "tap") { scoreHabit(id, n, null); return; }
  throw new Error("unknown op: " + op);`);

const anSrc = [fnSrc("repsPerTap"), fnSrc("localDayKey"), fnSrc("anViewItems"), fnSrc("anAllEvents"), fnSrc("anMatcher"),
  fnSrc("anViewMetric"), fnSrc("anSourceEvents"), fnSrc("anBucket")].join("\n");
const runAnalytics = new Function("S", "anPrefs", "taskTags", "createdMs", "updatedMs", "fmtDate", "view",
  anSrc + "\n  const b = anBucket(anSourceEvents(view),'day',0,4102444800000);\n  return b.series;");

// ---------------------------------------------------------------- harness

const RealDate = Date;
const HOUR = 3600000, DAY_MS = 86400000;
const NOW = RealDate.UTC(2026, 9, 16, 10, 0);           // 2026-10-16T10:00Z
const at = (dayOff, h, m = 0) => RealDate.UTC(2026, 9, 16 + dayOff, h, m);
const MY_DEV = "dev-k0t11n01";
const PWA_DEV = "d-pwa-7f3a";

function frozenDate(ms) {
  function D(...a) { return a.length ? new RealDate(...a) : new RealDate(ms); }
  D.now = () => ms; D.UTC = RealDate.UTC; D.parse = RealDate.parse; D.prototype = RealDate.prototype;
  return D;
}
function assert(cond, msg) { if (!cond) throw new Error("ASSERT: " + msg); }
const clone = o => JSON.parse(JSON.stringify(o));
const canon = v => JSON.stringify(v);
// key-order-free (JS `==` on JSON values; the Kotlin golden test compares the same way)
const sortKeys = v => Array.isArray(v) ? v.map(sortKeys) : (v && typeof v === "object")
  ? Object.keys(v).sort().reduce((o, k) => (o[k] = sortKeys(v[k]), o), {}) : v;
const same = (a, b) => JSON.stringify(sortKeys(a)) === JSON.stringify(sortKeys(b));

let hlc = NOW + 1000;
function pwaOp(task, ts, op, n) {
  const S = { tasks: [task], char: { xp: 100, gold: 50, mp: 5, hp: 50, maxHp: 50, lvl: 2 } };
  const evs = [];
  const nop = () => { };
  runOp(frozenDate(ts), S, () => ++hlc, e => evs.push(Object.assign({ ts }, e)), nop, nop, nop, nop, () => "", nop, nop, nop, nop,
    () => false, null, task.id, op, n);
  return evs;
}

// ---------------------------------------------------------------- the replay spec (RepsReplay.kt)

const utcDay = ms => Math.floor(ms / DAY_MS);
function isLocal(e, myDev, boundary) {
  return e.kind === "habitReps" && e.dev === myDev && e.imported !== true && !e.synthetic && e.id <= boundary;
}
const signed = e => { const r = Math.abs(e.reps || 0); return (e.direction || 0) < 0 ? -r : r; };
function repairHistory(task, rows) {
  const history = (task.history || []).map(p => ({ ...p }));
  for (const e of rows) {
    const n = signed(e), ts = e.timestamp, day = utcDay(ts);
    let idx = -1;
    for (let i = history.length - 1; i >= 0; i--) if (utcDay(history[i].date) === day) { idx = i; break; }
    if (idx >= 0) {
      const p = history[idx];
      const later = ts >= p.date;
      if (later) { p.date = ts; p.value = e.value; }
      p.reps = (p.reps || 0) + n;
      p.repCounted = true;
      p.scored = p.scored === true;
    } else {
      const p = { date: ts, value: e.value, reps: n, repCounted: true, scored: false };
      let j = history.length;
      while (j > 0 && history[j - 1].date > ts) j--;
      history.splice(j, 0, p);
    }
  }
  return history;
}
function selectRows(events, boundary) {
  return events.filter(e => isLocal(e, MY_DEV, boundary)).sort((a, b) => a.timestamp - b.timestamp || a.id - b.id);
}

// ---------------------------------------------------------------- forward cases (Part A)

const logHabit = (id, title, extra = {}) => Object.assign({ id, type: "habit", title, difficulty: "log", value: 0, cUp: 0, cDown: 0, history: [] }, extra);
const easyHabit = (id, title, extra = {}) => Object.assign({ id, type: "habit", title, difficulty: "easy", value: 1.5, cUp: 0, cDown: 0, history: [] }, extra);

const FORWARD = [
  { name: "fwd_plus_fresh_day", note: "+25 on a day with no point: a new point {date, value, reps:25, repCounted:true, scored:false}; cUp += 25.",
    task: logHabit("f1", "Pushups", { cUp: 2, history: [{ date: at(-1, 9), value: 0, reps: 1, repCounted: true, scored: false }] }), n: 25, at: NOW },
  { name: "fwd_minus_fresh_day", note: "-4 (the minus sheet): reps:-4 point; cDown += 4, cUp unchanged.",
    task: logHabit("f2", "Pushups", { cUp: 3 }), n: -4, at: NOW },
  { name: "fwd_merge_onto_tap_point", note: "same UTC day as today's tap point: reps add (1 + 10), date moves, value set, flags OR.",
    task: logHabit("f3", "Pullups", { cUp: 1, history: [{ date: NOW - HOUR, value: 0, reps: 1, repCounted: true, scored: false }] }), n: 10, at: NOW },
  { name: "fwd_merge_onto_scored_point", note: "a scored (easy) habit's tap point today: scored stays true (`last.scored || false`), scoredUp kept, reps add.",
    task: easyHabit("f4", "Situps", { cUp: 1, value: 2.4, history: [{ date: NOW - 2 * HOUR, value: 2.4, scoredUp: 1, reps: 1, repCounted: true, scored: true }] }), n: 4, at: NOW },
  { name: "fwd_merge_onto_legacy_point", note: "a legacy point without reps/repCounted/scored keys: reps = 0 + n, repCounted true, scored false (added).",
    task: easyHabit("f5", "Plank", { history: [{ date: NOW - 3 * HOUR, value: 1.5, scoredUp: 1 }] }), n: -2, at: NOW },
];

const forward = FORWARD.map(c => {
  const t = clone(c.task);
  const evs = pwaOp(t, c.at, "reps", c.n);
  assert(evs.length === 1 && evs[0].kind === "habitReps" && evs[0].reps === c.n, c.name + ": PWA habitReps event");
  return {
    name: c.name, note: c.note, task_before: c.task, n: c.n, at: c.at,
    expected: { cUp: t.cUp, cDown: t.cDown, value: t.value, history: t.history },
    pwa_event: evs[0],
  };
});

// ---------------------------------------------------------------- replay cases (Part B)
// tape ops: tap (dir), reps (local buggy Kotlin), repsFixed (local, post-fix: wrote its point),
// pwaReps (made on the PWA, row pulled by EventPuller), importedReps (row from a PWA backup import).

const REPLAY = [
  { name: "replay_plus_fresh_days", note: "two buggy +n rows on two past days, no points: two points inserted in date order.",
    task: logHabit("r1", "Pushups"),
    tape: [{ op: "reps", t: at(-3, 10), n: 20 }, { op: "reps", t: at(-1, 18), n: 15 }] },
  { name: "replay_minus_and_same_day_merge", note: "+30 then -5 the same day merge into one point (reps 25); a -3 on the next day stays negative.",
    task: logHabit("r2", "Squats"),
    tape: [{ op: "reps", t: at(-2, 9), n: 30 }, { op: "reps", t: at(-2, 9, 30), n: -5 }, { op: "reps", t: at(-1, 8), n: -3 }] },
  { name: "replay_merge_onto_tap_point", note: "log habit 'Squats 10' (10 reps/tap): tap 08:00, bulk +12 09:00, tap 20:00 the same day. The row merges into the tap point; the later tap keeps its date/value.",
    task: logHabit("r3", "Squats 10"),
    tape: [{ op: "tap", t: at(-1, 8), n: 1 }, { op: "reps", t: at(-1, 9), n: 12 }, { op: "tap", t: at(-1, 20), n: 1 }] },
  { name: "replay_merge_onto_scored_point", note: "scored (easy) habit: bulk +6 before a scored tap (keeps the tap's date/value, scored stays true); on another day a tap then bulk -2 (date moves to the row).",
    task: easyHabit("r4", "Situps", { value: 1.0 }),
    tape: [{ op: "reps", t: at(-2, 7), n: 6 }, { op: "tap", t: at(-2, 12), n: 1 }, { op: "tap", t: at(-1, 9), n: 1 }, { op: "reps", t: at(-1, 21), n: -2 }] },
  { name: "replay_out_of_order_between_points", note: "points on day -5 and day -1 and today exist; two buggy rows on day -3 are inserted between them as ONE point (8 + 4).",
    task: logHabit("r5", "Lunges"),
    tape: [{ op: "tap", t: at(-5, 9), n: 1 }, { op: "reps", t: at(-3, 10), n: 8 }, { op: "reps", t: at(-3, 22), n: 4 }, { op: "tap", t: at(-1, 9), n: -1 }, { op: "tap", t: at(0, 8), n: 1 }] },
  { name: "replay_excludes_foreign_rows", note: "a PWA bulk +8 (row pulled, dev = PWA) and a PWA backup-imported -2 (imported:true) already have their points (synced in): never replayed. Only the local +5 is. A local habitTap row is not a habitReps row.",
    task: logHabit("r6", "Burpees"),
    tape: [{ op: "pwaReps", t: at(-3, 10), n: 8 }, { op: "importedReps", t: at(-2, 10), n: -2 }, { op: "tap", t: at(-1, 7), n: 1 }, { op: "reps", t: at(-1, 12), n: 5 }] },
  { name: "replay_excludes_post_fix_rows", note: "the buggy +10 is replayed; the first fixed addHabitReps pinned the boundary before its insert, so the fixed +7 / +3 rows (their points already written) are above it and skipped.",
    task: logHabit("r7", "Dips"),
    tape: [{ op: "reps", t: at(-2, 10), n: 10 }, { op: "repsFixed", t: at(-1, 10), n: 7 }, { op: "repsFixed", t: at(0, 9), n: 3 }] },
];

function simulate(c) {
  const pwa = clone(c.task), kt = clone(c.task);
  const events = [];
  let id = 0, boundary = null;
  const row = (s, extra) => Object.assign({ id: ++id, timestamp: s.t }, extra);
  for (const s of c.tape) {
    if (s.op === "tap") {
      pwaOp(pwa, s.t, "tap", s.n);
      const evs = pwaOp(kt, s.t, "tap", s.n);
      const e = evs[0];
      events.push(row(s, { kind: "habitTap", taskType: "habit", taskId: kt.id, taskTitle: kt.title, direction: e.dir, reps: e.reps, value: e.value, dev: MY_DEV }));
    } else if (s.op === "reps") {                        // buggy Kotlin: counters + row, no point
      pwaOp(pwa, s.t, "reps", s.n);
      if (s.n > 0) kt.cUp = (kt.cUp || 0) + s.n; else kt.cDown = (kt.cDown || 0) + (-s.n);
      events.push(row(s, { kind: "habitReps", taskId: kt.id, taskTitle: kt.title, direction: s.n > 0 ? 1 : -1, reps: Math.abs(s.n), value: kt.value, dev: MY_DEV }));
    } else if (s.op === "repsFixed") {                   // fixed Kotlin: pin, point, row
      if (boundary === null) boundary = id;
      pwaOp(pwa, s.t, "reps", s.n);
      pwaOp(kt, s.t, "reps", s.n);
      events.push(row(s, { kind: "habitReps", taskId: kt.id, taskTitle: kt.title, direction: s.n > 0 ? 1 : -1, reps: Math.abs(s.n), value: kt.value, dev: MY_DEV }));
    } else if (s.op === "pwaReps" || s.op === "importedReps") { // the PWA wrote the point; it synced in
      pwaOp(pwa, s.t, "reps", s.n);
      const e = pwaOp(kt, s.t, "reps", s.n)[0];
      const r = row(s, { kind: "habitReps", taskId: e.taskId, taskTitle: e.taskTitle, direction: e.dir, reps: e.reps, value: e.value, dev: PWA_DEV });
      if (s.op === "importedReps") r.imported = true;
      events.push(r);
    } else throw new Error("op " + s.op);
  }
  if (boundary === null) boundary = id;                  // the replay pins MAX(id)
  return { pwa, kt, events, boundary };
}

const replay = REPLAY.map(c => {
  const { pwa, kt, events, boundary } = simulate(c);
  const expected = repairHistory(kt, selectRows(events, boundary));
  assert(same(expected, pwa.history), c.name + ": replay != PWA history\n  rep " + canon(expected) + "\n  pwa " + canon(pwa.history));
  assert(kt.cUp === pwa.cUp && kt.cDown === pwa.cDown && kt.value === pwa.value, c.name + ": counters/value already equal");
  assert(!same(expected, kt.history), c.name + ": the case must actually repair something");
  return { name: c.name, note: c.note, my_dev: MY_DEV, boundary_id: boundary, replay_done: false,
    task_before: kt, events, expected_history: expected, pwa_history: pwa.history };
});

// Re-run: after the first run the task holds the replayed history and the done flag is set; the
// runner replays nothing. (Folding the same rows again WOULD double count -- asserted, it is why
// the flag exists.)
{
  const first = replay[0];
  const after = Object.assign(clone(first.task_before), { history: clone(first.expected_history) });
  const again = repairHistory(after, selectRows(first.events, first.boundary_id));
  assert(!same(again, after.history), "rerun: a second fold would double count");
  replay.push({ name: "replay_rerun_noop", note: "the done flag is set after the first run: a re-run (boot or Diagnostics) replays nothing, even though the rows are still there.",
    my_dev: MY_DEV, boundary_id: first.boundary_id, replay_done: true,
    task_before: after, events: first.events, expected_history: after.history, pwa_history: first.pwa_history });
}

// ---------------------------------------------------------------- render pins

const METRICS = {
  keyword: { id: "m-kw", name: "Squats", keyword: "squats", exact: false, habits: [] },
  exact: { id: "m-ex", name: "Upper body", keyword: "", exact: true, habits: [{ id: "r6", reps: null }, { id: "r4", reps: 3 }] },
};
function metricSeries(tasks, metric) {
  const S = { tasks };
  const view = { id: "v", name: "v", source: "metric", group: "day", chart: "bar", types: [], tags: [], metricId: metric.id };
  return runAnalytics(S, () => ({ metrics: [metric], activeMetric: metric.id }), () => [], () => 0, () => 0, () => "", view)
    .map(x => ({ day_ms: x.d, v: x.v }));
}
const byName = n => replay.find(r => r.name === n);
const RENDER = [
  { name: "metric_keyword_squats", note: "keyword metric 'squats' over r2 + r3 (title contains 'squats'): bulk reps now count, signed.", metric: METRICS.keyword, cases: ["replay_minus_and_same_day_merge", "replay_merge_onto_tap_point"] },
  { name: "metric_exact_with_override", note: "exact metric over r6 (no override) and r4 (override 3 per tap): the override multiplies scoredUp only; a bulk-reps-only point counts its reps.", metric: METRICS.exact, cases: ["replay_excludes_foreign_rows", "replay_merge_onto_scored_point"] },
];
const render = RENDER.map(r => {
  const repaired = r.cases.map(n => Object.assign(clone(byName(n).task_before), { history: clone(byName(n).expected_history) }));
  const pwaTasks = r.cases.map(n => Object.assign(clone(byName(n).task_before), { history: clone(byName(n).pwa_history) }));
  const buggy = r.cases.map(n => clone(byName(n).task_before));
  const exp = metricSeries(repaired, r.metric);
  assert(canon(exp) === canon(metricSeries(pwaTasks, r.metric)), r.name + ": metric == PWA");
  assert(canon(exp) !== canon(metricSeries(buggy, r.metric)), r.name + ": the bug shows in the metric");
  return { name: r.name, note: r.note, metric: r.metric, tasks: repaired, expected_metric_by_day: exp,
    buggy_metric_by_day: metricSeries(buggy, r.metric) };
});

process.stdout.write(JSON.stringify({
  _note: "CR-KT-023 golden fixture. Generated by gen_reps_history.js from Opti 3581f49 app.js (addReps, scoreHabit, logHistory, analytics), TZ=UTC. Never edit by hand; never edit after ACCEPTED (P4).",
  forward, replay, render,
}, null, 1) + "\n");
