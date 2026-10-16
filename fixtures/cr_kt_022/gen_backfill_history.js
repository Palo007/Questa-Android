// CR-KT-022 golden fixture generator.
// Run:  git -C <Opti> show 338d430:app.js > app.js
//       node gen_backfill_history.js app.js > backfill_history.json        (TZ=UTC)
//
// The repair under test replays the KOTLIN event rows (kind complete/uncomplete, written by
// RpgRepository.completeTask/uncompleteTask) and writes the per-task history points that were
// never written before CR-KT-020. The PWA side of this harness is the same cut-from-Opti technique
// as gen_complete_history.js (CR-KT-020): the REAL completeTask / uncompleteDaily / uncompleteTodo
// / logHistory / unlogToday (Opti 338d430 -- the commit that made CR-PWA-004 live; app.js is
// byte-identical on the main merge 3581f49) are cut out of app.js at run time and run with
// `new Function` over the SAME tap timeline, producing `pwa_today` -- the reference history the
// PWA itself would have written for those taps. The render pins run the real anSourceEvents /
// anBucket (completed source, day group) over repaired tasks.
//
// Per case the generator ASSERTS the repair reference output against that PWA reference:
//   fresh           point-for-point equal, modulo the omitted reward/checklist
//   counted         same completed days (under the analytics-source rules incl. the todo fallback)
//   ordered-insert  an earlier-day point is inserted before a later pre-existing one, untouched
//   noop            the history must not change (noise un-tick, dedup, late credit)
//
// The un-tick references run the FIXED CR-PWA-004 code: an un-tick retracts the completion record
// regardless of day distance (fresh points pop, merged/older points are un-marked with the value
// restored, todos drop `completedAt`) -- exactly the repair's cancel semantics; no deviation is
// pinned. Cases where the PWA keeps an un-marked leftover the add-only repair does not write still
// assert COUNT parity and say so in their note.
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

const anSrc = [fnSrc("localDayKey"), fnSrc("anViewItems"), fnSrc("anSourceEvents"), fnSrc("anBucket")].join("\n");
const runAnalytics = new Function("S", "view", "from", "to",
  anSrc + "\n  return anBucket(anSourceEvents(view),'day',from,to);");

// ---------------------------------------------------------------- repair reference (the CR spec)

const DAY_MS = 86400000;
const utcDay = ms => Math.floor(ms / DAY_MS);

function repairHistory(task, events) {
  // 1. complete rows pair LIFO with uncomplete rows: an un-tick cancels its completion.
  const open = [];
  for (const e of events) {
    if (e.kind === "complete") {
      let late = false;
      if (e.payloadJson) { try { late = !!JSON.parse(e.payloadJson).late; } catch (_) { } }
      if (late) continue;                 // creditYesterday wrote its own backdated point
      open.push(e);
    } else if (e.kind === "uncomplete") {
      if (open.length) open.pop();
    }
  }
  // 2. one point per UTC day for the standing completions (the last one wins).
  const perDay = new Map();
  for (const e of open) perDay.set(utcDay(e.timestamp), e);
  const history = (task.history || []).map(p => ({ ...p }));
  const days = [...perDay.keys()].sort((a, b) => perDay.get(a).timestamp - perDay.get(b).timestamp);
  for (const day of days) {
    const e = perDay.get(day);
    const idx = history.findIndex(p => utcDay(p.date) === day);
    if (idx >= 0) {
      if (history[idx].completed === true) continue;          // counted day: never modify
      history[idx] = { ...history[idx], date: e.timestamp, value: e.value, completed: true };
    } else {
      const p = { date: e.timestamp, value: e.value, completed: true };
      if (task.type === "daily") { p.isDue = true; p.repeat = (task.repeat || []).slice(); }
      let j = history.length;
      while (j > 0 && history[j - 1].date > p.date) j--;      // keep date order
      history.splice(j, 0, p);
    }
  }
  return history;
}

// ---------------------------------------------------------------- harness helpers

function assert(cond, msg) { if (!cond) throw new Error("ASSERT: " + msg); }
const clone = o => JSON.parse(JSON.stringify(o));
const sorted = s => [...s].sort((a, b) => a - b);

function pointDays(history) {
  const s = new Set();
  for (const p of history) if (p.completed === true && typeof p.date === "number") s.add(utcDay(p.date));
  return s;
}
// anSourceEvents semantics: completed points; todo completedAt only when NO completed point exists.
function pwaCounted(history, task) {
  const s = pointDays(history);
  if (s.size === 0 && task.type === "todo" && task.completedAt) s.add(utcDay(task.completedAt));
  return s;
}
function samePointModuloOmitted(rep, ref) {
  const eq = (a, b) => JSON.stringify(a) === JSON.stringify(b);
  for (const k of ["date", "value", "completed", "isDue", "repeat"]) {
    if (!eq(rep[k], ref[k])) return false;
  }
  if ("reward" in rep || "checklist" in rep) return false;   // repaired points omit them
  return true;
}

function clockClass(ms) {
  return class FrozenDate extends Date {
    constructor(...a) { if (a.length) { super(...a); } else { super(ms); } }
    static now() { return ms; }
  };
}
const noop = () => {};

const NOW = Date.UTC(2026, 9, 16, 10, 0, 0); // 2026-10-16T10:00:00Z
const DAY = 86400000;
const ALL7 = [true, true, true, true, true, true, true];

let uidSeq = 0;
function mkLogEvent(list, ms) {
  return ev => { uidSeq++; list.push(Object.assign({ ts: ms, uid: "fx22-" + uidSeq, dev: "dev-pwa" }, clone(ev))); };
}

// ---------------------------------------------------------------- cases

const cases = [
  // ---- sim cases: reference = the real PWA code over the same taps ----
  {
    name: "fresh_daily_complete",
    note: "one daily completion: the repair pushes the point the PWA would have pushed (isDue/repeat; no reward/checklist).",
    parity: "fresh",
    task: { id: "dl1", type: "daily", title: "Run", difficulty: "medium", value: 3.25, repeat: ALL7, streak: 4, history: [] },
    tape: [{ op: "complete", at: NOW }],
  },
  {
    name: "fresh_todo_complete",
    note: "one todo completion: point pushed, no isDue/repeat; the repair does NOT touch completedAt (pre-1.3 never set it).",
    parity: "fresh",
    task: { id: "td1", type: "todo", title: "Buy milk", difficulty: "easy", value: 0.5, history: [], createdAt: NOW - DAY },
    tape: [{ op: "complete", at: NOW }],
  },
  {
    name: "daily_merge_onto_cron_miss",
    note: "today's cron miss point (completed:false) is merged in place: date moves to the completion, value/completed updated; isDue/repeat stay; no reward added by the repair.",
    parity: "fresh",
    task: { id: "dl2", type: "daily", title: "Stretch", difficulty: "easy", value: 2.0, repeat: ALL7, streak: 2,
      history: [{ date: NOW - 2 * 3600000, value: 1.4, completed: false, isDue: true, repeat: ALL7 }] },
    tape: [{ op: "complete", at: NOW }],
  },
  {
    name: "daily_same_day_untick_pops",
    note: "same-day un-tick: the completion is cancelled; the PWA pops the fresh point too (no day is counted either way, and both histories end empty).",
    parity: "counted",
    task: { id: "dl3", type: "daily", title: "Meditate", difficulty: "easy", value: 1.0, repeat: ALL7, streak: 3, history: [] },
    tape: [{ op: "complete", at: NOW - 2 * 3600000 }, { op: "uncomplete", at: NOW - 3600000 }],
  },
  {
    name: "daily_same_day_untick_after_miss_merge",
    note: "un-tick after merging onto today's miss point: the repair leaves the miss point untouched (add-only); today's PWA keeps it un-marked (completed:false). Count parity (no completed day either way); the cosmetic date/value drift is documented.",
    parity: "counted",
    task: { id: "dl4", type: "daily", title: "Read", difficulty: "easy", value: 1.5, repeat: ALL7, streak: 1,
      history: [{ date: NOW - 3 * 3600000, value: 1.2, completed: false, isDue: true, repeat: ALL7 }] },
    tape: [{ op: "complete", at: NOW - 2 * 3600000 }, { op: "uncomplete", at: NOW - 3600000 }],
  },
  {
    name: "daily_late_night_untick_pops",
    note: "completion 23:50Z, un-tick 00:30Z (40 min later, next UTC day). CR-PWA-004 (live): the fixed unlogToday pops the fresh point regardless of day distance, and the repair's cancel leaves no point either -- both histories end empty, no day counted.",
    parity: "counted",
    task: { id: "dl5", type: "daily", title: "Journal", difficulty: "easy", value: 0.75, repeat: ALL7, streak: 1, history: [] },
    tape: [{ op: "complete", at: Date.UTC(2026, 9, 15, 23, 50) }, { op: "uncomplete", at: Date.UTC(2026, 9, 16, 0, 30) }],
  },
  {
    name: "todo_untick_later_cancels_earlier_then_recomplete",
    note: "todo completed Oct 13, un-ticked Oct 15, re-completed 30 min later. CR-PWA-004 (live): the un-tick un-marks the Oct 13 point (value restored, reward dropped) and deletes completedAt, so only Oct 15 counts; the repair cancels Oct 13 and omits the un-marked leftover -- count-equal (cosmetic leftover on the PWA copy only).",
    parity: "counted",
    task: { id: "td2", type: "todo", title: "Call mum", difficulty: "easy", value: 1.0, history: [], createdAt: Date.UTC(2026, 9, 13, 9, 0) },
    tape: [
      { op: "complete", at: Date.UTC(2026, 9, 13, 10, 0) },
      { op: "uncomplete", at: Date.UTC(2026, 9, 15, 9, 0) },
      { op: "complete", at: Date.UTC(2026, 9, 15, 9, 30) },
    ],
  },
  {
    name: "todo_same_day_untick_cancels",
    note: "todo completed and un-ticked the same day. CR-PWA-004 (live): the un-tick un-marks the point and deletes completedAt -> the day no longer counts (was quirk a in CR-KT-020); the repair omits the point entirely -- count-equal (cosmetic leftover on the PWA copy only).",
    parity: "counted",
    task: { id: "td3", type: "todo", title: "Water plants", difficulty: "trivial", value: 0.5, history: [], createdAt: NOW - DAY },
    tape: [{ op: "complete", at: NOW - 3 * 3600000 }, { op: "uncomplete", at: NOW - 3600000 }],
  },
  {
    name: "out_of_order_insert_older_day",
    note: "a repaired earlier-day point is inserted BEFORE a later synced point (date order kept) and the pre-existing point is left untouched (same as logHistory's out-of-order sort branch).",
    parity: "ordered-insert",
    task: { id: "dl6", type: "daily", title: "Walk", difficulty: "easy", value: 2.5, repeat: ALL7, streak: 2,
      history: [{ date: NOW, value: 3.2, completed: true, isDue: true, repeat: ALL7, reward: { xp: 5, gold: 0.8, mp: 1, delta: 0.7 } }] },
    tape: [{ op: "complete", at: NOW - DAY - 3600000 }],
  },
  {
    name: "todo_recomplete_same_day",
    note: "todo complete -> un-tick -> complete again, one UTC day: the last completion stands (one point, last value wins); the PWA merges onto the kept point and counts the same day.",
    parity: "fresh",
    task: { id: "td4", type: "todo", title: "Inbox zero", difficulty: "medium", value: 1.0, history: [], createdAt: NOW - DAY },
    tape: [{ op: "complete", at: NOW - 4 * 3600000 }, { op: "uncomplete", at: NOW - 2 * 3600000 }, { op: "complete", at: NOW - 3600000 }],
  },

  // ---- hand-built cases: no PWA tap timeline (repair-only semantics) ----
  {
    name: "noise_uncomplete_no_op",
    note: "the Kotlin repo logs an uncomplete even for a task that was never completed (unconditional insertLog); nothing to cancel -- the repair is a no-op.",
    parity: "noop",
    task: { id: "dl7", type: "daily", title: "Sketch", difficulty: "easy", value: 1.0, repeat: ALL7, history: [] },
    events: [{ id: 1, timestamp: NOW, kind: "uncomplete", taskType: "daily", taskId: "dl7", taskTitle: "Sketch", value: 1.0 }],
  },
  {
    name: "dedup_existing_completed_point",
    note: "the day already carries a completed:true point (recorded on the PWA and synced, or by a 1.3 completion); an imported/older complete row must not add or modify anything -- also makes the repair idempotent on re-runs.",
    parity: "noop",
    task: { id: "dl8", type: "daily", title: "Yoga", difficulty: "easy", value: 1.5, repeat: ALL7, streak: 5,
      history: [{ date: NOW - 5 * 3600000, value: 2.4, completed: true, isDue: true, repeat: ALL7, reward: { xp: 6, gold: 1.0, mp: 1, delta: 0.9 } }] },
    events: [{ id: 1, timestamp: NOW - 5 * 3600000, kind: "complete", taskType: "daily", taskId: "dl8", taskTitle: "Yoga", streak: 5, value: 2.4, imported: true }],
  },
  {
    name: "late_credit_backdated_point_skipped",
    note: "creditYesterday writes its OWN backdated point (date = yesterday local noon) and logs the complete row with payload {late:true} (RpgEngine.kt:617-667, app.js:2667-2691) -- the repair skips late rows, never duplicating the credit.",
    parity: "noop",
    task: { id: "dl9", type: "daily", title: "Shower", difficulty: "easy", value: 1.0, repeat: ALL7, streak: 6,
      history: [{ date: Date.UTC(2026, 9, 15, 12, 0), value: 1.9, completed: true, isDue: true, repeat: ALL7, reward: { xp: 5, gold: 0.9, mp: 1, delta: 0.9 } }] },
    events: [{ id: 1, timestamp: NOW, kind: "complete", taskType: "daily", taskId: "dl9", taskTitle: "Shower", streak: 6, value: 1.9,
      payloadJson: "{\"reward\":{\"xp\":5,\"gold\":0.9,\"mp\":1,\"delta\":0.9},\"repeat\":[true,true,true,true,true,true,true],\"late\":true,\"checklist\":null}" }],
  },
];

function runSim(c) {
  const t = clone(c.task);
  const S = { char: { xp: 100, gold: 50, mp: 5, hp: 50, maxHp: 50, lvl: 2 }, prefs: { paused: !!c.paused } };
  let hlc = NOW + 1000;
  const now = () => ++hlc;
  const kotlinEvents = [];
  const pwaEvents = [];
  let seq = 0;
  for (const step of c.tape) {
    const e1 = [];
    runStep(clockClass(step.at), S, now, mkLogEvent(e1, step.at), noop, noop, noop, noop, noop, noop, noop, noop, noop, t, null, step.op);
    assert(e1.length === 1, c.name + ": " + step.op + " did not produce exactly one PWA logEvent");
    pwaEvents.push(...e1);
    seq++;
    // the Kotlin event row RpgRepository would have written for this tap (EventLogEntity columns)
    const row = {
      id: seq,
      timestamp: step.at,
      kind: step.op === "complete" ? "complete" : "uncomplete",
      taskType: t.type,
      taskId: t.id,
      taskTitle: t.title,
      value: t.value,
    };
    if (t.streak != null) row.streak = t.streak;
    kotlinEvents.push(row);
  }
  return { simTask: t, kotlinEvents, pwaEvents };
}

const outCases = [];
for (const c of cases) {
  const taskBefore = clone(c.task);                 // pre-1.3: no completion points were appended
  let events, expected, pwaToday = null, pwaEvents = null;
  if (c.tape) {
    const sim = runSim(c);
    taskBefore.value = sim.simTask.value;
    if (sim.simTask.streak !== undefined) taskBefore.streak = sim.simTask.streak;
    events = sim.kotlinEvents;
    expected = repairHistory(taskBefore, events);
    pwaToday = clone(sim.simTask);
    pwaEvents = sim.pwaEvents;
    verify(c, taskBefore, expected, sim.simTask);
  } else {
    events = clone(c.events);
    expected = repairHistory(taskBefore, events);
    verify(c, taskBefore, expected, null);
  }
  const out = { name: c.name, note: c.note, parity: c.parity };
  out.task_before = taskBefore;
  out.events = events;
  out.expected_history = expected;
  out.pwa_today = pwaToday;
  if (pwaEvents && pwaEvents.length) out.pwa_events = pwaEvents;
  outCases.push(out);
}

function verify(c, taskBefore, expected, simTask) {
  if (c.parity === "noop") {
    assert(JSON.stringify(expected) === JSON.stringify(taskBefore.history || []), c.name + ": noop expected");
    return;
  }
  const expDays = sorted(pointDays(expected));
  const refDays = sorted(pwaCounted(simTask.history, simTask));
  if (c.parity === "fresh") {
    assert(expected.length === simTask.history.length, c.name + ": same point count");
    for (let i = 0; i < expected.length; i++) {
      assert(samePointModuloOmitted(expected[i], simTask.history[i]), c.name + ": point " + i + " modulo reward/checklist");
      assert(simTask.history[i].reward !== undefined, c.name + ": reference point " + i + " has reward");
    }
    assert(JSON.stringify(expDays) === JSON.stringify(refDays), c.name + ": counted days");
    return;
  }
  if (c.parity === "counted") {
    assert(JSON.stringify(expDays) === JSON.stringify(refDays), c.name + ": counted days " + JSON.stringify(expDays) + " vs " + JSON.stringify(refDays));
    return;
  }
  if (c.parity === "ordered-insert") {
    assert(expected.length === 2 && expected[0].date < expected[1].date, c.name + ": two sorted points");
    assert(samePointModuloOmitted(expected[0], simTask.history[0]), c.name + ": inserted point modulo");
    assert(JSON.stringify(expected[1]) === JSON.stringify(taskBefore.history[0]), c.name + ": pre-existing point preserved");
    assert(JSON.stringify(simTask.history[1]) === JSON.stringify(taskBefore.history[0]), c.name + ": reference preserves it");
    return;
  }
  throw new Error(c.name + ": unknown parity " + c.parity);
}

// ---------------------------------------------------------------- render pins (real PWA analytics)

function renderCase(name, note, task) {
  const S = { tasks: [clone(task)] };
  const res = runAnalytics(S, { source: "completed", group: "day" }, 0, 4102444800000);
  const rows = Object.keys(res.dayMap).map(k => ({ day_ms: Number(k), v: res.dayMap[k] })).sort((a, b) => a.day_ms - b.day_ms);
  return { name, note, task, expected_completed_by_day: rows };
}

const render = [
  renderCase(
    "repaired_daily_point_counts",
    "a repaired daily point (no reward) counts on its day; a cron-miss point (completed:false) on another day adds nothing.",
    { id: "dlr1", type: "daily", title: "Run", difficulty: "medium", value: 4.17, repeat: ALL7, streak: 5,
      history: [
        { date: Date.UTC(2026, 9, 15, 9, 0), value: 3.25, completed: false, isDue: true, repeat: ALL7 },
        { date: NOW, value: 4.17, completed: true, isDue: true, repeat: ALL7 }] }),
  renderCase(
    "repaired_todo_point_counts_without_completedAt",
    "a repaired todo point counts via the point alone (the repair never sets completedAt).",
    { id: "tdr1", type: "todo", title: "Buy milk", difficulty: "easy", value: 1.49,
      history: [{ date: NOW - 3600000, value: 1.49, completed: true }], createdAt: NOW - DAY }),
  renderCase(
    "two_repaired_days_count_twice",
    "two repaired days (multi-day chain) count once each.",
    { id: "dlr2", type: "daily", title: "Journal", difficulty: "easy", value: 2.0, repeat: ALL7,
      history: [
        { date: Date.UTC(2026, 9, 13, 10, 0), value: 1.2, completed: true, isDue: true, repeat: ALL7 },
        { date: Date.UTC(2026, 9, 15, 9, 30), value: 1.8, completed: true, isDue: true, repeat: ALL7 }] }),
];

process.stdout.write(JSON.stringify({
  _note: "CR-KT-022 golden fixture. Generated by gen_backfill_history.js from Opti 338d430 app.js (CR-PWA-004 fix; = main merge 3581f49), TZ=UTC. Never edit by hand; never edit after ACCEPTED (P4).",
  cases: outCases,
  render: render,
}, null, 2) + "\n");
