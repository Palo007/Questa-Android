// CR-PWA-004 golden fixture generator.
// Run:  git -C <Opti> show <fix-commit>:app.js > app.js        (<fix-commit> = see README.md)
//       node gen_untick_fix.js app.js > untick_fix.json        (TZ=UTC)
//
// Pins the FIXED un-tick behaviour (CR-PWA-004 / RE-PWA-021 quirks a+b):
//   - uncompleteTodo now DELETES t.completedAt (quirk a: the analytics todo fallback
//     `!any && t.completedAt` stopped counting a reverted todo).
//   - unlogToday no longer gates on the point being dated the current UTC day (quirk b):
//     the retraction is unconditional; pop-vs-keep is still decided by _histCreated +
//     the scored flags (only a point THIS completion created is popped).
//
// The code under test is NOT copied by hand: it is cut out of Opti app.js at run time
// by text markers and run with `new Function`:
//   - logHistory        (merge a same-UTC-day point in place, else push + out-of-order re-sort)
//   - completeTask      (daily: history point + _histCreated + streak/doneDay; todo: completedAt +
//                        history point; both: realized-delta _gr, doneAt, doneDay)
//   - reverseGrant      (xp/gold/mp clawback + clamped value restore from _gr.delta)
//   - unlogToday        (un-mark the reverted completion's point; no day test since CR-PWA-004;
//                        pop only when this completion created the point)
//   - uncompleteDaily / uncompleteTodo (todo: completedAt deleted since CR-PWA-004)
//   - helpers           clamp, DIFF, valueDelta, completionReward, isDailyDueOn, isDailyDueToday, dayStamp
//
// Stubbed: S.char side effects (gainXp, takeDamage), buzz, floatFx, fxGain, bumpAvatar, save,
// render, toast. logEvent is captured in the stored-record shape the real logEvent writes:
// `{ts, uid, dev}` first, then the event (uid here is harness-stamped "fx20-<n>").
// Frozen clock: `Date` is shadowed per step (Date.now()/new Date() = that step's ms; TZ=UTC);
// the HLC `now()` returns a fixed base, +1 per call.
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
  fnSrc("valueDelta"), fnSrc("completionReward"),
  fnSrc("isDailyDueOn"), fnSrc("isDailyDueToday"), fnSrc("dayStamp"),
  fnSrc("logHistory"), fnSrc("reverseGrant"), fnSrc("unlogToday"),
].join("\n");
const bodies = [fnSrc("completeTask"), fnSrc("uncompleteDaily"), fnSrc("uncompleteTodo")].join("\n");

const runStep = new Function("Date", "S", "now", "logEvent", "gainXp", "takeDamage", "buzz", "floatFx", "fxGain", "bumpAvatar", "save", "render", "toast", "t", "ev", "op",
  helpers + "\n" + bodies + `
  if (op === "complete") { completeTask(t, ev); return; }
  if (op === "uncomplete") { (t.type === "daily" ? uncompleteDaily : uncompleteTodo)(t); return; }
  throw new Error("unknown op: " + op);`);

function clockClass(ms) {
  return class FrozenDate extends Date {
    constructor(...a) { if (a.length) { super(...a); } else { super(ms); } }
    static now() { return ms; }
  };
}

const noop = () => {};

// --- cases -----------------------------------------------------------------------------------
const NOW = Date.UTC(2026, 9, 16, 10, 0, 0); // 2026-10-16T10:00:00Z
const ALL7 = [true, true, true, true, true, true, true];
const clone = o => JSON.parse(JSON.stringify(o));

let uidSeq = 0;
function mkLogEvent(list, ms) {
  return ev => { uidSeq++; list.push(Object.assign({ ts: ms, uid: "fx20-" + uidSeq, dev: "dev-pwa" }, clone(ev))); };
}

function runCase(c) {
  const t = clone(c.task);
  const S = { char: { xp: 100, gold: 50, mp: 5, hp: 50, maxHp: 50, lvl: 2 }, prefs: { paused: !!c.paused } };
  const hlcBase = (c.hlcBase != null) ? c.hlcBase : NOW + 1000;
  let hlc = hlcBase;
  const now = () => ++hlc;

  const out = { name: c.name };
  if (c.note) out.note = c.note;
  out.task_before = clone(c.task);
  out.hlc_base = hlcBase;

  const ms1 = (c.nowMs != null) ? c.nowMs : NOW;
  const e1 = [];
  runStep(clockClass(ms1), S, now, mkLogEvent(e1, ms1), noop, noop, noop, noop, noop, noop, noop, noop, noop, t, null, "complete");
  out.now_ms = ms1;
  out.after_complete = clone(t);
  out.events = e1;

  if (c.uncomplete) {
    const ms2 = (c.secondMs != null) ? c.secondMs : ms1;
    const e2 = [];
    runStep(clockClass(ms2), S, now, mkLogEvent(e2, ms2), noop, noop, noop, noop, noop, noop, noop, noop, noop, t, null, "uncomplete");
    out.now_ms_2 = ms2;
    out.after_uncomplete = clone(t);
    out.events_uncomplete = e2;
  }
  return out;
}

const cases = [
  {
    name: "daily_uncomplete_pop_same_day",
    note: "SAME-DAY parity with CR-KT-020 `daily_uncomplete_pop_fresh`: the output must stay byte-identical (same-day pop is unchanged by CR-PWA-004)",
    task: { id: "dl6", type: "daily", title: "Run", difficulty: "medium", value: 3.25, repeat: ALL7, streak: 4, history: [] },
    uncomplete: true,
  },
  {
    name: "daily_uncomplete_pop_next_day",
    note: "was CR-KT-020 `daily_uncomplete_next_day_guard` (point left completed:true); NOW the fresh point is POPPED and _histCreated cleared",
    task: { id: "dl8", type: "daily", title: "Journal", difficulty: "easy", value: 0.75, repeat: ALL7, streak: 1, history: [] },
    uncomplete: true,
    secondMs: NOW + 86400000 + 300000,
  },
  {
    name: "daily_uncomplete_keep_merged_next_day",
    note: "merged point (cron miss base) un-ticked on the NEXT day: KEPT, completed=false, value restored, reward dropped (CR-KT-020 case 8 was same-day only)",
    task: { id: "dl7", type: "daily", title: "Stretch", difficulty: "easy", value: 2.0, repeat: ALL7, streak: 2,
      history: [{ date: NOW - 2 * 3600000, value: 1.4, completed: false, isDue: true, repeat: ALL7 }] },
    uncomplete: true,
    secondMs: NOW + 86400000 + 300000,
  },
  {
    name: "todo_uncomplete_same_day",
    note: "was CR-KT-020 `todo_uncomplete_keeps_point` (completedAt STAYED); NOW completedAt is DELETED; the point is still kept completed=false (todos never stamp _histCreated)",
    task: { id: "td2", type: "todo", title: "Call mum", difficulty: "easy", value: 1.0, history: [], createdAt: NOW - 3600000 },
    uncomplete: true,
  },
  {
    name: "todo_uncomplete_next_day",
    note: "todo un-ticked on the NEXT day: completedAt deleted AND the point retracted (pre-fix both were left in place)",
    task: { id: "td3", type: "todo", title: "Water plants", difficulty: "easy", value: 0.5, history: [], createdAt: NOW - 3000000 },
    uncomplete: true,
    secondMs: NOW + 86400000 + 300000,
  },
  {
    name: "todo_uncomplete_across_utc_midnight",
    note: "the reported UTC+2 regression: complete 2026-10-15T22:30:00Z (= 00:30 local Oct 16), un-tick 2026-10-16T00:30:00Z (= 02:30 local, still Oct 16). Under TZ=UTC this is simply a cross-UTC-day un-tick; pre-fix the UTC buckets differed and both the point and completedAt were left in place",
    task: { id: "td4", type: "todo", title: "Ship CR-PWA-004", difficulty: "medium", value: 1.5, history: [], createdAt: Date.UTC(2026, 9, 15, 8, 0, 0) },
    nowMs: Date.UTC(2026, 9, 15, 22, 30, 0),
    uncomplete: true,
    secondMs: Date.UTC(2026, 9, 16, 0, 30, 0),
  },
];

process.stdout.write(JSON.stringify({
  _note: "CR-PWA-004 golden fixture. Generated by gen_untick_fix.js from the fixed Opti app.js (see README.md for the exact source commit) (TZ=UTC). Never edit by hand; never edit after ACCEPTED (P4).",
  clock_now_ms: NOW,
  cases: cases.map(runCase),
}, null, 2) + "\n");
