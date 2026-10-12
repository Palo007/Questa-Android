// CR-KT-017 golden fixture generator: the task editor's field-wise write-back.
// Run:  git -C <Opti> show b7a94cd:app.js > app.js
//       node gen_writeback.js app.js > writeback_cases.json
//
// The code under test is NOT copied by hand: the write-back IIFE is cut out of Opti app.js
// (b7a94cd, saveTask) at run time by text markers and run with `new Function`:
//   from `(function(){\n      const _eq`   to   `EDIT = orig;`
// Inputs per case: `base` (Opti `_base`, the task when the sheet opened), `live` (Opti `orig`,
// the stored record at Save; a sync merge may have moved it), `sheet` (Opti `EDIT`, the sheet
// copy after the checklist touchedAt stamping). Output `expected` = `orig` after the block, and
// `expected_json` = JSON.stringify(expected) (key order = the live record's, new keys appended).
// Not in the block: `EDIT.updatedAt = now()` (runs after it) and the attachments reconcile
// (runs before it; every case here has no user image delta, so EDIT.attachments = base's).
"use strict";
const fs = require("fs");
const src = fs.readFileSync(process.argv[2], "utf8").replace(/\r\n/g, "\n");

const START = "(function(){\n      const _eq";
const END = "EDIT = orig;";
const a = src.indexOf(START);
if (a < 0) throw new Error("start marker not found");
const b = src.indexOf(END, a);
if (b < 0) throw new Error("end marker not found");
const block = src.slice(a, b);
const run = new Function("_base", "orig", "EDIT", block + "\nreturn orig;");

const clone = (o) => JSON.parse(JSON.stringify(o));
const R7 = [true, true, true, true, true, true, true];

const habit = {
  id: "h1", type: "habit", title: "Push-ups", notes: "", difficulty: "easy", value: 1, done: false,
  checklist: [], repeat: R7, up: true, down: true, resetFreq: "daily", tags: [],
  createdAt: 1000, updatedAt: 2000, cUp: 2, cDown: 0, history: [],
};
const todo = {
  id: "t1", type: "todo", title: "Buy milk", notes: "2 l", difficulty: "easy", value: 0, done: false,
  checklist: [{ id: "a", text: "shop", done: false }, { id: "b", text: "pay", done: false }],
  repeat: R7, up: true, down: true, resetFreq: "daily", tags: [], createdAt: 1000, updatedAt: 2000,
};

function mk(name, note, base, live, sheet) {
  const exp = run(clone(base), clone(live), clone(sheet));
  return { name, note, base, live, sheet, expected: exp, expected_json: JSON.stringify(exp) };
}

const cases = [];

// 1. A subtask synced in while the sheet was open; the user edited the title only.
{
  const base = clone(todo);
  const live = clone(todo); live.checklist.push({ id: "c", text: "peer", done: false, touchedAt: 2500 }); live.updatedAt = 2500;
  const sheet = clone(todo); sheet.title = "Buy oat milk";
  cases.push(mk("sync_arrived_subtask", "checklist untouched: the live list (with c) is kept", base, live, sheet));
}
// 2. A peer tapped + on the habit during the edit; the user edited the title only.
{
  const base = clone(habit);
  const live = clone(habit); live.cUp = 3; live.value = 2; live.updatedAt = 2600;
  const sheet = clone(habit); sheet.title = "Push-ups x10";
  cases.push(mk("peer_counter_tap", "cUp/value/updatedAt untouched: the peer's values are kept", base, live, sheet));
}
// 2b. The user changed only cDown; a peer tap moved cUp meanwhile (finding D).
{
  const base = clone(habit);
  const live = clone(habit); live.cUp = 3; live.value = 2; live.updatedAt = 2600;
  const sheet = clone(habit); sheet.cDown = 1;
  cases.push(mk("cdown_only_peer_cup", "only cDown is written; the live cUp=3 stays", base, live, sheet));
}
// 3. Habit with repeat; the sheet does not touch repeat; notes edited.
{
  const base = clone(habit); base.repeat = [true, false, true, false, true, false, true];
  const live = clone(base);
  const sheet = clone(base); sheet.notes = "morning";
  cases.push(mk("untouched_repeat_habit", "repeat untouched: kept as stored", base, live, sheet));
}
// 4. Title edit only, nothing moved meanwhile.
{
  const base = clone(todo), live = clone(todo), sheet = clone(todo);
  sheet.title = "Buy bread";
  cases.push(mk("title_edit_only", "only title changes", base, live, sheet));
}
// 5. The sheet removed a key (repsPerTap); a peer changed notes meanwhile.
{
  const base = clone(habit); base.repsPerTap = 5;
  const live = clone(base); live.notes = "peer note"; live.updatedAt = 2700;
  const sheet = clone(base); delete sheet.repsPerTap;
  cases.push(mk("field_removed_in_sheet", "repsPerTap removed; the peer's notes stay", base, live, sheet));
}
// 6. The user edited the checklist (ticked a, deleted b); c synced in meanwhile.
{
  const base = clone(todo);
  const live = clone(todo); live.checklist.push({ id: "c", text: "peer", done: false, touchedAt: 2500 }); live.updatedAt = 2500;
  const sheet = clone(todo); sheet.checklist = [{ id: "a", text: "shop", done: true, touchedAt: 3000 }];
  cases.push(mk("checklist_edit_plus_sync_subtask", "the sheet list wins, c (never in base) is appended, b stays deleted", base, live, sheet));
}

process.stdout.write(JSON.stringify({ source: "Opti b7a94cd app.js saveTask write-back", cases }, null, 2) + "\n");
