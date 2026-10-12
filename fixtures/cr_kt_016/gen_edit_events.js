// CR-KT-016 golden fixture generator.
// Run:  git -C <Opti> show 027e371:app.js > app.js
//       node gen_edit_events.js app.js > edit_events.json
//
// The code under test is NOT copied by hand: it is cut out of Opti app.js (027e371) at run time
// by text markers and run with `new Function`:
//   - saveTask counter loops   `let _cXp=0,...`            .. before `if(gainParts){ floatFx`
//   - saveTask change list     `const _ch=[];`             .. before `}catch(e){}`
//   - saveTask new-task event  `logEvent({kind:'create', ...});`
//   - feed renderer            `else if (e.kind==='edit'){ icon=...` .. the `desc='Edited '` line
//   - helpers clamp, DIFF, valueDelta, completionReward, missDamage, _evDiffText, esc
//
// orig / EDIT: the change list is run with `orig` = the task when the sheet opened (Opti `_base`)
// and `EDIT` = the sheet copy. In 027e371 itself `EDIT = orig;` runs just before the change list,
// so the two names point at ONE object and only the attachments / counter items can ever appear
// (see README "PWA defect"). The harness passes two objects, which is what the code is written for.
"use strict";
const fs = require("fs");

const src = fs.readFileSync(process.argv[2], "utf8");

function cut(from, to, startAt = 0, inclusiveTo = false) {
  const a = src.indexOf(from, startAt);
  if (a < 0) throw new Error("marker not found: " + from);
  const b = src.indexOf(to, a + from.length);
  if (b < 0) throw new Error("end marker not found: " + to);
  return src.slice(a, inclusiveTo ? b + to.length : b);
}
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

const saveAt = src.indexOf("\nfunction saveTask(");
const helpers = [
  fnSrc("clamp"),
  src.match(/\nconst DIFF = [^\n]*;/)[0],
  fnSrc("valueDelta"), fnSrc("completionReward"), fnSrc("missDamage"),
  fnSrc("esc"), fnSrc("_evDiffText"),
].join("\n");
const counterLoops = cut("let _cXp=0,_cGold=0,_cMp=0,_cHp=0;", "if(gainParts){ floatFx", saveAt);
const changeList = cut("const _ch=[];", "}catch(e){}", saveAt);
const createLine = cut("logEvent({kind:'create'", ");", saveAt, true);

const feedBody = cut("const _summ=[]; let _detail='';", "_detail;", src.indexOf("else if (e.kind==='edit'){ icon="), true);

// --- runners -------------------------------------------------------------------------------
const TS = 1791460200000;
let seq = 0;
function makeLogEvent(out) {
  return ev => { seq++; out.push(Object.assign({ ts: TS + seq * 60000, uid: "fx16-" + seq, dev: "dev-pwa" }, ev)); };
}

// Opti saveTask: the counter loops run on t (the live record, which already holds the sheet's
// fields), then the change list. Side effects on S.char / fx are stubbed out.
const runSave = new Function("orig", "EDIT", "_attDelta", "upDelta", "downDelta", "t", "logEvent",
  helpers + `
  const S = { char: { xp: 0, gold: 0, mp: 0, hp: 50, maxHp: 50, lvl: 1 } };
  function gainXp(){} function takeDamage(){} function fxGain(){ return ''; }
  let gainParts=null, loseParts=null, doBump=false;
  ` + counterLoops + `
  ` + changeList);

const runCreate = new Function("EDIT", "logEvent", createLine);

const renderEdit = new Function("e", "titleHtml", helpers + `
  let icon='', desc='';
  ` + feedBody + `
  return desc;`);

function htmlLines(html) {
  const dec = s => s.replace(/&bull;/g, "•").replace(/&middot;/g, "·").replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">").replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&amp;/g, "&");
  return html.replace(/<div[^>]*>/g, "\n").replace(/<[^>]+>/g, "").split("\n").map(dec).filter(l => l.length);
}

// --- cases ---------------------------------------------------------------------------------
const clone = o => JSON.parse(JSON.stringify(o));
const ci = (id, text, done = false) => ({ id, text, done });
const rem = { id: "r1", enabled: true, kind: "once", time: "09:00", lastFiredKey: "", date: "2026-10-09" };
const ALL7 = [true, true, true, true, true, true, true];

const cases = [
  { name: "title_and_notes", note: "notes '' -> text renders as 'new'",
    orig: { id: "t1", type: "todo", title: "Buy milk", notes: "", difficulty: "easy", checklist: [] },
    edit: { id: "t1", type: "todo", title: "Buy oat milk", notes: "two cartons", difficulty: "easy", checklist: [] } },
  { name: "difficulty_and_notes_removed", note: "notes text -> '' renders as 'removed'",
    orig: { id: "t2", type: "daily", title: "Run", notes: "5 km", difficulty: "easy", checklist: [], repeat: ALL7 },
    edit: { id: "t2", type: "daily", title: "Run", notes: "", difficulty: "hard", checklist: [], repeat: ALL7 } },
  { name: "checklist_changed_toggled_added", note: "by index: 0 text differs, 1 done differs, 2 same, 3 only in edit",
    orig: { id: "t3", type: "todo", title: "Trip", notes: "", difficulty: "easy",
      checklist: [ci("c1", "Pack"), ci("c2", "Tickets"), ci("c3", "Keys", true)] },
    edit: { id: "t3", type: "todo", title: "Trip", notes: "", difficulty: "easy",
      checklist: [ci("c1", "Pack bags"), ci("c2", "Tickets", true), ci("c3", "Keys", true), ci("c4", "Passport")] } },
  { name: "checklist_removed_and_text_wins_over_done", note: "index 0: text AND done differ -> only 'changed'; index 1 only in orig",
    orig: { id: "t4", type: "todo", title: "Shop", notes: "", difficulty: "easy",
      checklist: [ci("c1", "Bread"), ci("c2", "Eggs")] },
    edit: { id: "t4", type: "todo", title: "Shop", notes: "", difficulty: "easy",
      checklist: [ci("c1", "Rye bread", true)] } },
  { name: "schedule",
    orig: { id: "t5", type: "daily", title: "Stretch", notes: "", difficulty: "easy", checklist: [], repeat: ALL7 },
    edit: { id: "t5", type: "daily", title: "Stretch", notes: "", difficulty: "easy", checklist: [],
      repeat: [false, true, true, true, true, true, false] } },
  { name: "reminders",
    orig: { id: "t6", type: "todo", title: "Call mum", notes: "", difficulty: "easy", checklist: [], reminders: [] },
    edit: { id: "t6", type: "todo", title: "Call mum", notes: "", difficulty: "easy", checklist: [], reminders: [rem] } },
  { name: "attachments_only",
    orig: { id: "t7", type: "todo", title: "Fix the bike", notes: "", difficulty: "easy", checklist: [] },
    edit: { id: "t7", type: "todo", title: "Fix the bike", notes: "", difficulty: "easy", checklist: [] },
    att: { added: 2, removed: 1 } },
  { name: "counter_up_and_down", note: "habit counter edited in the sheet: cUp 0->2, cDown 0->1",
    orig: { id: "h1", type: "habit", title: "Water", notes: "", difficulty: "medium", checklist: [], value: 0, cUp: 0, cDown: 0 },
    edit: { id: "h1", type: "habit", title: "Water", notes: "", difficulty: "medium", checklist: [], value: 0, cUp: 2, cDown: 1 } },
  { name: "counter_reverse", note: "cUp 3->2, cDown 2->1 (rewards taken back, HP healed)",
    orig: { id: "h2", type: "habit", title: "Snack", notes: "", difficulty: "hard", checklist: [], value: 5.5, cUp: 3, cDown: 2 },
    edit: { id: "h2", type: "habit", title: "Snack", notes: "", difficulty: "hard", checklist: [], value: 5.5, cUp: 2, cDown: 1 } },
  { name: "all_fields_order", note: "every item at once: order title, notes, difficulty, checklist, schedule, reminders, attachments, counter",
    orig: { id: "h3", type: "habit", title: "Read", notes: "a", difficulty: "easy", checklist: [ci("c1", "Ch 1")],
      repeat: ALL7, reminders: [], value: 1.25, cUp: 0, cDown: 0 },
    edit: { id: "h3", type: "habit", title: "Read more", notes: "b", difficulty: "trivial", checklist: [ci("c1", "Ch 1", true)],
      repeat: [true, false, true, true, true, true, true], reminders: [Object.assign({}, rem, { kind: "weekly", days: ALL7, date: undefined })],
      value: 1.25, cUp: 1, cDown: 0 },
    att: { added: 1, removed: 0 } },
  { name: "no_change_no_event", note: "_ch empty -> logEvent is not called",
    orig: { id: "t8", type: "todo", title: "Same", notes: "n", difficulty: "easy", checklist: [ci("c1", "x")], repeat: null, reminders: [] },
    edit: { id: "t8", type: "todo", title: "Same", notes: "n", difficulty: "easy", checklist: [ci("c1", "x")], repeat: null, reminders: [] } },
];

const out = [];
for (const c of cases) {
  const orig = clone(c.orig), EDIT = clone(c.edit);
  const t = clone(c.edit);                       // the live record after the write-back
  const upDelta = (EDIT.cUp || 0) - (orig.cUp || 0);
  const downDelta = (EDIT.cDown || 0) - (orig.cDown || 0);
  const logged = [];
  runSave(orig, EDIT, c.att || { added: 0, removed: 0 }, upDelta, downDelta, t, makeLogEvent(logged));
  const ev = logged[0] || null;
  const row = { name: c.name };
  if (c.note) row.note = c.note;
  Object.assign(row, { orig: c.orig, edit: c.edit, attachments: c.att || { added: 0, removed: 0 },
    upDelta, downDelta, valueAfter: t.value });
  row.event = ev;
  row.event_json = ev ? JSON.stringify(ev) : null;
  if (ev) {
    const html = renderEdit(ev, '<strong class="evTaskClick">' + ev.taskTitle + "</strong>");
    row.feed_lines = htmlLines(html);
  }
  out.push(row);
}

const created = [];
runCreate({ id: "n1", type: "todo", title: "New task", notes: "", difficulty: "easy", checklist: [] }, makeLogEvent(created));
const createCase = { name: "create", event: created[0], event_json: JSON.stringify(created[0]),
  feed_lines: ["Created " + created[0].taskType + " " + created[0].taskTitle] };

process.stdout.write(JSON.stringify({
  _note: "CR-KT-016 golden fixture. Generated by gen_edit_events.js from Opti 027e371 app.js. Never edit by hand; never edit after ACCEPTED (P4).",
  edit_cases: out,
  create_case: createCase,
}, null, 2) + "\n");
