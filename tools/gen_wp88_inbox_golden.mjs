#!/usr/bin/env node
// tools/gen_wp88_inbox_golden.mjs -- WP-8.8 (PX-58..60) extra G6 vectors.
//
// The WP-8.0 G6 parse_table.json feeds records without `kind:"habit"` and with 2-char ids, so
// every row there is null. This script adds accepted rows, the meta/reminders upload bytes and
// more _inboxHash inputs, all computed by the REAL Opti code at 7d5d4f3 (read-only: `git show`
// of the pinned blobs, never the Opti working tree). Output: fixtures/wp88/g6_inbox_golden.json.
//
//   node tools/gen_wp88_inbox_golden.mjs [path/to/Questa-web-checkout]
import fs from 'fs'; import vm from 'vm'; import { execFileSync } from 'child_process';
import { dirname, join, resolve } from 'node:path'; import { fileURLToPath } from 'node:url';

// The original PWA, https://github.com/Palo007/Questa -- a separate checkout, NOT vendored here.
// $OPTI_ROOT, else a sibling directory named `Opti`. Was a hardcoded absolute path under one
// maintainer's home directory, so it resolved for exactly one person on one machine.
const REPO = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const OPTI = process.argv[2] || process.env.OPTI_ROOT || join(dirname(REPO), 'Opti');
const REV = '7d5d4f3';
const show = (p) => execFileSync('git', ['-C', OPTI, 'show', `${REV}:${p}`], { encoding: 'utf8', maxBuffer: 64 << 20 });
const sync = show('sync.js'); const app = show('app.js');

// sync.js:534-714 (INBOX_* constants .. syncInboxWriteReminders), app.js quickLogMode/quickLogDirs
// (:2410-2421) and getReminderNotificationPayload (:2127-2145), all verbatim.
const a = sync.indexOf('const INBOX_DIR = '); const b = sync.indexOf('// 2026-09-19 (round 3, item 12)', a);
const grab = (src, start, end) => { const i = src.indexOf(start); const j = src.indexOf(end, i); return src.slice(i, j); };
const block = sync.slice(a, b);
const ql = grab(app, 'function quickLogMode(t)', 'function quickLogTargetOk');
const pay = grab(app, 'function getReminderNotificationPayload', '/* END_REMINDER_HELPERS */');
const ctx = vm.createContext({ console, JSON, Math, Date: { now: () => 1790000000000 } });
vm.runInContext(ql + '\n' + pay + '\n' + block + `
var __cfg={}; function syncCfg(){return __cfg;} function syncCfgSave(o){Object.assign(__cfg,o);}
var __up=[]; function dbxUploadText(p,t){__up.push({path:p,text:t}); return Promise.resolve({});}
`, ctx);

const S = { tasks: [
  { id: 'h1', type: 'habit', title: 'Drink water', quickLog: true },
  { id: 'h2', type: 'habit', title: 'Both but no down', quickLog: 'both', down: false },
  { id: 'h3', type: 'habit', title: 'Down only', quickLog: 'down', notes: 'less sugar' },
  { id: 'h4', type: 'habit', title: 'No shortcut' },
  { id: 'h5', type: 'habit', title: '\u010c"a\\j\n\u2028 \u0001 \u00e9\ud83d\ude00', quickLog: 'both' },
  { id: 'h6', type: 'habit', title: '', quickLog: 'down', down: false },
  { id: 'd1', type: 'daily', title: 'Stretch', quickLog: true, repeat: [true, false, true, false, true, false, true],
    reminders: [{ time: '08:00', enabled: true, kind: 'daily', days: [true, true, true, true, true, true, true] }, { time: '09:00', enabled: false }] },
  { id: 't1', type: 'todo', title: 'Done todo', done: true, reminders: [{ time: '10:00', enabled: true }] },
  { id: 't2', type: 'todo', title: 'Open todo', notes: 'call mom', reminders: [{ time: '', enabled: true }, { time: '11:30', enabled: true, kind: 'once', date: '2026-09-30', days: [true, true, true, true, true, true, true] }] },
  { id: 'h7', type: 'habit', title: 'Weekly', reminders: [{ time: '07:15', enabled: true, kind: 'weekly', days: [false, true, false, true, false, true, false] }, { time: '07:20', enabled: true, days: [true, true] }] },
] };
ctx.S = S;
await vm.runInContext('syncInboxWriteMeta()', ctx);
await vm.runInContext('syncInboxWriteReminders()', ctx);
const second = await vm.runInContext('syncInboxWriteMeta()', ctx); // unchanged list -> false, no upload
const up = vm.runInContext('__up', ctx); const cfg = vm.runInContext('__cfg', ctx);

const hashes = ['{}', '[]', '', '\u010c\ud83d\ude00\u2028', 'a'.repeat(1000)]
  .map((s) => { ctx.__s = s; return { input: s, hash: vm.runInContext('_inboxHash(__s)', ctx) }; });

const base = (o) => JSON.stringify(Object.assign({ v: 1, id: 'abcd1234', kind: 'habit', habitId: 'h1', dir: 1, ts: 5 }, o));
const recs = {
  valid_min: [JSON.stringify({ v: 1, id: 'abcd1234', kind: 'habit', habitId: 'h1', dir: 1, ts: 1790000000000 }), 'abcd1234.json'],
  valid_full: ['{"v":1,"id":"0b3c5a2e-1111-4222-8333-944445555666","kind":"habit","habitId":"h1","dir":-1,"ts":1790000000123,"tzOffsetMin":-120,"src":"android-shortcut"}', '0b3c5a2e-1111-4222-8333-944445555666.json'],
  v_float: ['{"v":1.0,"id":"abcd1234","kind":"habit","habitId":"h1","dir":1.0,"ts":1790000000000.0}', 'abcd1234.json'],
  ts_frac: ['{"v":1,"id":"abcd1234","kind":"habit","habitId":"h1","dir":1,"ts":1.5}', 'abcd1234.json'],
  v_string: [base({ v: '1' }), 'abcd1234.json'],
  id_short: [base({ id: 'abc1234' }), 'abc1234.json'],
  id_65: [base({ id: 'a'.repeat(65) }), 'a'.repeat(65) + '.json'],
  id_64: [base({ id: 'a'.repeat(64) }), 'a'.repeat(64) + '.json'],
  id_bad_char: [base({ id: 'abcd_1234' }), 'abcd_1234.json'],
  name_case: [base({}), 'abcd1234.JSON'],
  kind_missing: ['{"v":1,"id":"abcd1234","habitId":"h1","dir":1,"ts":5}', 'abcd1234.json'],
  kind_other: [base({ kind: 'daily' }), 'abcd1234.json'],
  habit_empty: [base({ habitId: '' }), 'abcd1234.json'],
  habit_num: [base({ habitId: 7 }), 'abcd1234.json'],
  dir_2: [base({ dir: 2 }), 'abcd1234.json'],
  dir_str: [base({ dir: '1' }), 'abcd1234.json'],
  ts_neg: [base({ ts: -5 }), 'abcd1234.json'],
  ts_str: [base({ ts: '5' }), 'abcd1234.json'],
  tz_str: [base({ tzOffsetMin: '60' }), 'abcd1234.json'],
  tz_frac: [base({ tzOffsetMin: 330.5 }), 'abcd1234.json'],
  src_empty: [base({ src: '' }), 'abcd1234.json'],
  src_long: [base({ src: 'x'.repeat(50) }), 'abcd1234.json'],
  src_num: [base({ src: 3 }), 'abcd1234.json'],
  array: ['[1,2]', 'abcd1234.json'],
  json_null: ['null', 'abcd1234.json'],
  empty: ['', 'abcd1234.json'],
  trailing: [base({}) + ' x', 'abcd1234.json'],
  dup_key: ['{"v":1,"id":"abcd1234","kind":"habit","habitId":"h1","dir":1,"dir":-1,"ts":5}', 'abcd1234.json'],
  extra_field: [base({ extra: { a: 1 } }), 'abcd1234.json'],
};
const parsed = {};
for (const [k, [t, f]] of Object.entries(recs)) { ctx.__t = t; ctx.__f = f; parsed[k] = vm.runInContext('inboxParseRecord(__t,__f)', ctx); }

const out = new URL('../fixtures/wp88/g6_inbox_golden.json', import.meta.url);
fs.writeFileSync(out, JSON.stringify({ rev: REV, tasks: S.tasks, up, cfg, second, hashes, recs, parsed }, null, 1) + '\n');
console.log('wrote', out.pathname);
