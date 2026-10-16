// CR-KT-020 golden fixture generator.
// Run:  git -C <Opti> show afdae43:app.js > app.js
//       node gen_complete_history.js app.js > complete_history.json        (TZ=UTC)
//
// The code under test is NOT copied by hand: it is cut out of Opti app.js (afdae43) at run time
// by text markers and run with `new Function`:
//   - logHistory        (merge a same-UTC-day point in place, else push + out-of-order re-sort)
//   - completeTask      (daily: history point + _histCreated + streak/doneDay; todo: completedAt +
//                        history point; both: realized-delta _gr, doneAt, doneDay)
//   - reverseGrant      (xp/gold/mp clawback + clamped value restore from _gr.delta)
//   - unlogToday        (un-mark today's point; pop only when this completion created the point;
//                        only dailies stamp _histCreated, so a todo un-tick always keeps the point)
//   - uncompleteDaily / uncompleteTodo
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
    name: "todo_complete_fresh",
    note: "todo: completedAt set + point pushed (no isDue/repeat/checklist); _histCreated is NOT stamped for todos",
    task: { id: "td1", type: "todo", title: "Buy milk", difficulty: "easy", value: 0.5, history: [], createdAt: NOW - 86400000 },
  },
  {
    name: "daily_complete_fresh",
    note: "daily: point pushed with isDue/repeat/reward; doneDay + streak rise; _histCreated true",
    task: { id: "dl1", type: "daily", title: "Run", difficulty: "medium", value: 3.25, repeat: ALL7, streak: 4, history: [] },
  },
  {
    name: "daily_complete_merge_onto_miss",
    note: "last point is today's cron miss (completed:false): merged in place, date moves to now, _histCreated false",
    task: { id: "dl2", type: "daily", title: "Stretch", difficulty: "easy", value: 2.0, repeat: ALL7, streak: 2,
      history: [{ date: NOW - 2 * 3600000, value: 1.4, completed: false, isDue: true, repeat: ALL7 }] },
  },
  {
    name: "daily_complete_checklist",
    note: "checklist snapshot rides the point (done flags as they are at completion; ids are dropped)",
    task: { id: "dl3", type: "daily", title: "Trip", difficulty: "trivial", value: 0, repeat: ALL7, streak: 0,
      checklist: [{ id: "c1", text: "Pack", done: false }, { id: "c2", text: "Wallet", done: true }], history: [] },
  },
  {
    name: "daily_complete_at_ceiling",
    note: "value 98.9 + delta clamps to 99: _gr.delta is the REALIZED delta, not the requested one",
    task: { id: "dl4", type: "daily", title: "Read", difficulty: "easy", value: 98.9, repeat: ALL7, streak: 10, history: [] },
  },
  {
    name: "daily_complete_paused",
    note: "paused: the point is still written; streak does NOT rise (PWA test 6a)",
    task: { id: "dl5", type: "daily", title: "Meditate", difficulty: "easy", value: 1.0, repeat: ALL7, streak: 3, history: [] },
    paused: true,
  },
  {
    name: "daily_uncomplete_pop_fresh",
    note: "un-tick the same UTC day after a fresh point: the point is POPPED, _histCreated deleted",
    task: { id: "dl6", type: "daily", title: "Run", difficulty: "medium", value: 3.25, repeat: ALL7, streak: 4, history: [] },
    uncomplete: true,
  },
  {
    name: "daily_uncomplete_keep_merged",
    note: "un-tick after a merge onto today's miss point: point KEPT, completed=false, value restored, reward dropped",
    task: { id: "dl7", type: "daily", title: "Stretch", difficulty: "easy", value: 2.0, repeat: ALL7, streak: 2,
      history: [{ date: NOW - 2 * 3600000, value: 1.4, completed: false, isDue: true, repeat: ALL7 }] },
    uncomplete: true,
  },
  {
    name: "todo_uncomplete_keeps_point",
    note: "todo un-tick: no _histCreated stamp -> point KEPT with completed=false; completedAt STAYS (analytics fallback still counts it)",
    task: { id: "td2", type: "todo", title: "Call mum", difficulty: "easy", value: 1.0, history: [], createdAt: NOW - 3600000 },
    uncomplete: true,
  },
  {
    name: "daily_uncomplete_next_day_guard",
    note: "un-tick on the NEXT UTC day: unlogToday leaves the point alone, so completed:true stays (quirk)",
    task: { id: "dl8", type: "daily", title: "Journal", difficulty: "easy", value: 0.75, repeat: ALL7, streak: 1, history: [] },
    uncomplete: true,
    secondMs: NOW + 86400000 + 300000,
  },
];

process.stdout.write(JSON.stringify({
  _note: "CR-KT-020 golden fixture. Generated by gen_complete_history.js from Opti afdae43 app.js (TZ=UTC). Never edit by hand; never edit after ACCEPTED (P4).",
  clock_now_ms: NOW,
  cases: cases.map(runCase),
}, null, 2) + "\n");
