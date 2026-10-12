#!/usr/bin/env node
// tools/gen_golden_fixtures.mjs
//
// Golden fixture generator — Stage 1 T1.3 (plans/stages/stage_1_sync_unblock_and_fixtures.md).
// Ground truth: plans/sync_format_spec.md §1 (backup/export envelope + hash canonicalization),
// §2 (tokenization), §3 (Dropbox /state.json wrapper), §8 (known unknowns / golden-file need).
//
// PROVENANCE — how the PWA functions below were obtained
// --------------------------------------------------------
// legacy_pwa/app.js and legacy_pwa/sync.js are NOT ES modules: neither file contains an
// `export` statement, and both rely on implicit browser globals (`S`, `document`,
// `localStorage`, `indexedDB`, `navigator`, `crypto`, DOM elements) that are woven through
// the same top-level scope as the tokenization/hash code. `import`ing either file directly
// into Node is not feasible without a full DOM/IDB/localStorage shim. Per the task's fallback
// instruction, the exact functions this generator needs are extracted VERBATIM below, each
// tagged with the precise source line range it was copied from (as read on 2026-07-17). Only
// two pieces are intentionally NOT byte-verbatim, both clearly marked and justified inline:
//   1. `syncDeviceId()` (real: sync.js:57-65) — the real version persists a generated id into
//      localStorage via syncCfg()/syncCfgSave(). That plumbing is irrelevant to wire format, so
//      it is replaced with a fixed constant. The `wrap()` function that calls it is verbatim.
//   2. `buildHashPreimage()` — duplicates app.js:5257-5264 (the local `backup`/`preJson`
//      construction inside buildBackupFile) because buildBackupFile does not return that
//      intermediate string. Kept character-for-character identical to the source, and the
//      script self-checks that hashing this duplicate reproduces the exact `_backup.hash`
//      byte that the verbatim buildBackupFile() embeds in the real envelope (see assertion
//      in main()). If the two ever disagree, generation fails loudly instead of silently
//      drifting from the real code path.
//
// Nondeterminism pinned for byte-identical repeated runs:
//   - `Date.now()` / `new Date()` — app.js's buildBackupFile() calls both directly (exportedAt,
//     items.lastActivityAt, the questa-backup-<stamp> filename). Pinned via a `Date` subclass
//     below (FixedDate) so every call anywhere in this process returns the same instant.
//   - `dayStamp()` (app.js:1120) uses local-time `getFullYear/getMonth/getDate`. `process.env.TZ`
//     is forced to UTC before any Date is constructed so the result is host-independent.
//   - `Math.random()` (used by the real `uid()`, app.js:1116) is never invoked: every entity id
//     in the synthetic state is a hardcoded literal in the same no-hyphen base36-looking shape
//     `uid()` produces (see spec §6), so the fixture never depends on Math.random.
//   - Event `uid` fields use fixed literals in the `<deviceId>-<ts36>-<rand36>` shape described
//     in spec §6 for event uids.

'use strict';
process.env.TZ = 'UTC';

import { writeFileSync, mkdirSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { createHash } from 'node:crypto';

const __dirname = dirname(fileURLToPath(import.meta.url));
const ROOT = join(__dirname, '..');
const FIXTURES_DIR = join(ROOT, 'fixtures');
mkdirSync(FIXTURES_DIR, { recursive: true });

// ---------------------------------------------------------------------------
// Deterministic clock (pins Date.now()/`new Date()` for every extracted
// function below — see "Nondeterminism pinned" note above).
// ---------------------------------------------------------------------------
const FIXED_NOW_MS = Date.UTC(2026, 6, 17, 12, 0, 0); // 2026-07-17T12:00:00.000Z
const RealDate = Date;
class FixedDate extends RealDate {
  constructor(...args) { super(...(args.length ? args : [FIXED_NOW_MS])); }
  static now() { return FIXED_NOW_MS; }
}
globalThis.Date = FixedDate;

// Fixed timestamp constants for the synthetic entities (all epoch ms, UTC).
const T_CHAR_UPDATED   = Date.UTC(2026, 6, 16, 21, 0, 0);
const T_HABIT_CREATED  = Date.UTC(2026, 5, 1, 9, 0, 0);
const T_HABIT_UPDATED  = Date.UTC(2026, 6, 15, 8, 30, 0);
const T_DAILY_CREATED  = Date.UTC(2026, 5, 3, 7, 0, 0);
const T_DAILY_UPDATED  = Date.UTC(2026, 6, 17, 7, 5, 0);
const T_DAILY_DONE_AT  = Date.UTC(2026, 6, 17, 7, 6, 0);
const T_TODO_CREATED   = Date.UTC(2026, 6, 10, 18, 0, 0);
const T_TODO_UPDATED   = Date.UTC(2026, 6, 16, 19, 45, 0);
const T_REWARD_CREATED = Date.UTC(2026, 5, 20, 12, 0, 0);
const T_REWARD_UPDATED = Date.UTC(2026, 5, 20, 12, 0, 0);
const T_TAG_HEALTH     = Date.UTC(2026, 5, 1, 8, 0, 0);
const T_TAG_CHORES     = Date.UTC(2026, 5, 1, 8, 1, 0);
const T_DEVICE_UPDATED = Date.UTC(2026, 6, 17, 11, 0, 0);
const T_CHARHIST       = Date.UTC(2026, 6, 16, 21, 0, 0);
const T_DELETION       = Date.UTC(2026, 6, 5, 10, 0, 0);
const T_VIEW_CREATED   = Date.UTC(2026, 5, 25, 9, 0, 0);
const T_VIEW_UPDATED   = Date.UTC(2026, 5, 25, 9, 0, 0);
const T_EXPORT_TS      = Date.UTC(2026, 6, 16, 8, 0, 0);
const T_EV1 = Date.UTC(2026, 6, 17, 7, 6, 0);
const T_EV2 = Date.UTC(2026, 6, 16, 19, 44, 0);
const T_EV3 = Date.UTC(2026, 6, 16, 8, 0, 5);
const T_EV4 = Date.UTC(2026, 6, 15, 8, 29, 0);

// Fixed, uid()-shaped (base36, no hyphens) entity ids — see spec §6.
const TASK_HABIT_ID  = 'lqxh1a2b3c4d';
const TASK_DAILY_ID  = 'lqxh2e5f6g7h';
const TASK_TODO_ID   = 'lqxh3i8j9k0l';
const REWARD_ID      = 'lqxh4m1n2o3p';
const TAG_HEALTH_ID  = 'lqxh5q4r5s6t';
const TAG_CHORES_ID  = 'lqxh6u7v8w9x';
const DEVICE_ID      = 'lqxh7y0z1a2b';
const REMINDER_ID    = 'lqxh8c3d4e5f';
const TODO_SUB1_ID   = 'lqxh9g6h7i8j';
const TODO_SUB2_ID   = 'lqxh0k9l0m1n';
const VIEW_ID        = 'lqxhaop2q3r4';
const DELETED_TAG_ID = 'lqxhbs5t6u7v';

// Fixed device id used both as the Dropbox device id and the events' `dev` field.
const FIXED_DEVICE_ID = 'lqxh7y0z1a2b';

// ===========================================================================
// VERBATIM EXTRACTION — legacy_pwa/app.js
// (line ranges cite the file as read 2026-07-17; see plans/sync_format_spec.md §1/§2)
// ===========================================================================

// app.js:3
const APP_VERSION = "v2026.07.16-1611";

// app.js:21
const DRAG_DELAY_DEFAULT = 200;

// app.js:103-115
function freshState(){
  return {
    version:1,
    char:{ name:"Adventurer", face:"🧙", cls:"Warrior",
           lvl:1, xp:0, hp:50, maxHp:50, mp:0, gold:0 },
    tasks:[], rewards:[], tags:[], devices:[],
    lastCron: dayStamp(new Date()),
    history:[], charHistory:[],
    monthlyBackups: [],
    deletions: [],
    prefs:{ width:480, notesLines:3, lastTab:'habits', haptics:true, cardThick:0, saveBtnTop:false }
  };
}

// app.js:116,119 (module-scoped HLC state). Real app.js does `let S = load()` at
// parse time (app.js:117); here `S` is populated after the synthetic state is
// assembled below, then migrate()/now() run against it exactly as on a real boot.
var lastIssued = 0;
let S;
function now(){ var p=Date.now(); lastIssued=Math.max(p, lastIssued+1); try{ if(S) S.__hlcLast=lastIssued; }catch(e){} return lastIssued; }

// app.js:150-155
function legacySubtaskId(taskId, text, occurrence){
  const s = String(taskId) + '\x1f' + String(text || '') + '\x1f' + String(occurrence);
  let h = 5381;
  for(let i=0;i<s.length;i++){ h = ((h*33) ^ s.charCodeAt(i)) >>> 0; }
  return 'lg-' + h.toString(36);
}

// app.js:156-216
function migrate(s){ const f=freshState();
  const out=Object.assign(f,s,{char:Object.assign(f.char,s.char||{})});
  out.prefs=Object.assign({width:480, notesLines:3, lastTab:'habits', tipDelay:0, haptics:true, cardThick:0, notificationsEnabled:false, saveBtnTop:false}, s.prefs||{});
  if(out.prefs.cardPad !== undefined){
    let cp = parseInt(out.prefs.cardPad, 10);
    if(isFinite(cp)){
      out.prefs.cardThick = Math.max(0, cp - 5);
    }
    delete out.prefs.cardPad;
  }
  out.prefs.tipDelay=0;
  if(out.prefs.dragDelay!=null){ let d=parseInt(out.prefs.dragDelay,10);
    out.prefs.dragDelay=isFinite(d)?Math.min(300,Math.max(100,d)):DRAG_DELAY_DEFAULT; }
  if(!Array.isArray(out.tags)) out.tags=[];
  if(!Array.isArray(out.devices)) out.devices=[];
  if(!Array.isArray(out.monthlyBackups)) out.monthlyBackups=[];
  if(!Array.isArray(out.deletions)) out.deletions=[];
  delete out.events;
  out.prefs.gfs = (out.prefs.gfs && typeof out.prefs.gfs==="object") ? out.prefs.gfs : {daily:0, weekly:0, monthly:0};
  out.prefs.gfs.daily   = Number(out.prefs.gfs.daily)   || 0;
  out.prefs.gfs.weekly  = Number(out.prefs.gfs.weekly)  || 0;
  out.prefs.gfs.monthly = Number(out.prefs.gfs.monthly) || 0;
  if(Array.isArray(out.tasks)){ out.tasks.forEach(normalizeTaskReminders); }
  (out.tasks||[]).forEach(t=>{
    const seenByText = new Map();
    (t.checklist||[]).forEach(c=>{
      if(!c) return;
      const text = c.text || '';
      const occurrence = seenByText.get(text) || 0;
      seenByText.set(text, occurrence + 1);
      if(!c.id) c.id = legacySubtaskId(t.id, text, occurrence);
    });
  });
  (out.tasks||[]).forEach(t=>{ t.updatedAt = t.updatedAt || t.createdAt || 0; });
  (out.rewards||[]).forEach(r=>{ r.updatedAt = r.updatedAt || r.createdAt || 0; });
  (out.tags||[]).forEach(g=>{ g.updatedAt = g.updatedAt || g.createdAt || 0; });
  (out.devices||[]).forEach(d=>{ d.updatedAt = d.updatedAt || 0; });
  if(out.prefs && out.prefs.an){
    (out.prefs.an.views||[]).forEach(v=>{ v.updatedAt = v.updatedAt || v.createdAt || 0; });
    (out.prefs.an.metrics||[]).forEach(m=>{ m.updatedAt = m.updatedAt || m.createdAt || 0; });
  }
  if(out.char && (out.char.updatedAt == null)) out.char.updatedAt = now();
  return out; }

// app.js:1116
const uid = ()=> Date.now().toString(36)+Math.random().toString(36).slice(2,7);

// app.js:1120
function dayStamp(d){ return d.getFullYear()*10000 + (d.getMonth()+1)*100 + d.getDate(); }

// app.js:1146-1150
function normalizeTaskReminders(t) {
  if (!t.reminders || !Array.isArray(t.reminders)) {
    t.reminders = [];
  }
}

// app.js:883-894
async function computeHash(str){
  if(typeof crypto!=="undefined" && crypto.subtle && crypto.subtle.digest){
    try{
      const buf = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(str));
      return Array.from(new Uint8Array(buf)).map(b=>b.toString(16).padStart(2,"0")).join("");
    }catch(e){ /* fall through */ }
  }
  let h = 0;
  for(let i=0; i<str.length; i++){ h = ((h<<5)-h)+str.charCodeAt(i); h |= 0; }
  return 'fallback-' + Math.abs(h).toString(16).padStart(8,'0');
}

// app.js:5156-5167
const _EXPORT_FIELD_MAP = {
  ts:'t', uid:'u', dev:'d', kind:'k', taskTitle:'n', notes:'no', id:'i',
  taskType:'ty', taskId:'ti', streak:'st', reward:'rw', repeat:'rp',
  checklist:'cl', changes:'ch', subId:'si', subText:'sx', done:'do',
  clawback:'cb', deviceId:'di', deviceName:'dn', prevDeviceName:'pn',
  createdAt:'ca', completedAt:'co', dir:'dr', reps:'rs', value:'v',
  dmg:'dg', cost:'ct', effect:'ef', counter:'cr', preBumpSeq:'ps',
  storedSeq:'ss', granted:'gr', log:'lg', detail:'dt', visibilityState:'vs',
  hidden:'hd', dirty:'dy', found:'fd', idbSeq:'is', liveSeq:'ls',
  winner:'wn', loser:'lo', charTitle:'ctt', day:'dyy', late:'lt',
  source:'o', synthetic:'sy', repCounted:'rc', inferred:'in'
};

// app.js:5168-5190
function _tokenizeEvents(eventsArr){
  const events = eventsArr||[];
  const kindArr=[], srcArr=[], tidArr=[], titleArr=[];
  const kindIdx={}, srcIdx={}, tidIdx={}, titleIdx={};
  function idx(arr,map,v){ if(v==null) return -1; if(!(v in map)){ map[v]=arr.length; arr.push(v); } return map[v]; }
  const out = events.map(function(e){
    const o={};
    for(const f in e){
      const v=e[f];
      if(f==='uid'||f==='dev'||f==='id'){ o[f]=v; continue; }
      const sk=_EXPORT_FIELD_MAP[f];
      if(!sk){ o[f]=v; continue; }
      if(f==='kind') o[sk]=idx(kindArr,kindIdx,v);
      else if(f==='source') o[sk]=idx(srcArr,srcIdx,v);
      else if(f==='taskId') o[sk]=idx(tidArr,tidIdx,v);
      else if(f==='taskTitle') o[sk]=idx(titleArr,titleIdx,v);
      else if(f==='synthetic'||f==='repCounted'||f==='inferred'||f==='done') o[sk]=(v?1:0);
      else o[sk]=v;
    }
    return o;
  });
  return { E: out, K: kindArr, SRC: srcArr, TID: tidArr, TT: titleArr };
}

// app.js:5220-5231
function _buildFieldMap(snap){
  const freq={};
  (function walk(o){
    if(Array.isArray(o)){ for(let i=0;i<o.length;i++) walk(o[i]); }
    else if(o && typeof o==='object'){ for(const k in o){ freq[k]=(freq[k]||0)+1; walk(o[k]); } }
  })(snap);
  const keys=Object.keys(freq).sort(function(a,b){ return freq[b]-freq[a]; });
  const map={}; let i=0;
  function nextCode(){ let s='', n=i; do{ s=String.fromCharCode(97+(n%26))+s; n=Math.floor(n/26); }while(n>0); i++; return s; }
  for(let j=0;j<keys.length;j++) map[keys[j]]=nextCode();
  return map;
}

// app.js:5232-5236
function _tokDeep(o, fm){
  if(Array.isArray(o)){ const r=[]; for(let i=0;i<o.length;i++) r.push(_tokDeep(o[i], fm)); return r; }
  if(o && typeof o==='object'){ const r={}; for(const k in o){ r[fm[k]||k]=_tokDeep(o[k], fm); } return r; }
  return o;
}

// app.js:5242-5247
function _tokenizeSnapshot(s){
  const snap={};
  for(const k in s){ if(k!=='events') snap[k]=s[k]; }
  const fm=_buildFieldMap(snap);
  return { S: _tokDeep(snap, fm), FM: fm };
}

// app.js:5256-5290
async function buildBackupFile(eventsArr){
  const backup=Object.assign({}, S, {events: eventsArr||[]});
  const _lastMs=function(arr){let mx=0;(arr||[]).forEach(x=>{const c=(x.createdAt||0),u=(x.updatedAt||0);if(c>mx)mx=c;if(u>mx)mx=u;});return mx;};
  backup._backup={ schema:2, exportedAt:new Date().toISOString(), appVersion:APP_VERSION,
                   eventCount:(eventsArr||[]).length,
                   items:{ tasks:(S.tasks||[]).length, rewards:(S.rewards||[]).length,
                           tags:(S.tags||[]).length,
                           views:((S.prefs&&S.prefs.an&&S.prefs.an.views)||[]).length,
                           lastActivityAt:new Date(Math.max(_lastMs(S.tasks),_lastMs(S.rewards))||Date.now()).toISOString() } };
  let hash = null;
  try{
    const preJson = JSON.stringify(backup);
    hash = await computeHash(preJson);
    backup._backup.hash = hash;
  }catch(e){ /* hash optional; export proceeds without it */ }
  const tok = _tokenizeEvents(eventsArr||[]);
  const snapTok = _tokenizeSnapshot(S);
  const env = {
    _backup: backup._backup,
    K: tok.K, SRC: tok.SRC, TID: tok.TID, TT: tok.TT, FM: snapTok.FM,
    S: snapTok.S,
    E: tok.E
  };
  const finalJson = JSON.stringify(env, null, 2);
  const blob = new Blob([finalJson], {type:'application/json'});
  const d=new Date(); const p=(n)=>String(n).padStart(2,'0');
  const stamp=''+d.getFullYear()+p(d.getMonth()+1)+p(d.getDate())+'-'+p(d.getHours())+p(d.getMinutes());
  const filename = 'questa-backup-'+stamp+'.json';
  return {blob, filename, eventCount: (eventsArr||[]).length};
}

// Duplicates app.js:5257-5264 exactly. buildBackupFile() (above) does not return
// its local `preJson` string, so this is re-derived here to write
// fixtures/golden_hash_preimage.txt. main() asserts computeHash() of this string
// equals the `_backup.hash` that the verbatim buildBackupFile() actually embeds,
// so any accidental drift between the two is caught rather than silently shipped.
function buildHashPreimage(eventsArr){
  const backup=Object.assign({}, S, {events: eventsArr||[]});
  const _lastMs=function(arr){let mx=0;(arr||[]).forEach(x=>{const c=(x.createdAt||0),u=(x.updatedAt||0);if(c>mx)mx=c;if(u>mx)mx=u;});return mx;};
  backup._backup={ schema:2, exportedAt:new Date().toISOString(), appVersion:APP_VERSION,
                   eventCount:(eventsArr||[]).length,
                   items:{ tasks:(S.tasks||[]).length, rewards:(S.rewards||[]).length,
                           tags:(S.tags||[]).length,
                           views:((S.prefs&&S.prefs.an&&S.prefs.an.views)||[]).length,
                           lastActivityAt:new Date(Math.max(_lastMs(S.tasks),_lastMs(S.rewards))||Date.now()).toISOString() } };
  return JSON.stringify(backup);
}

// ===========================================================================
// VERBATIM EXTRACTION — legacy_pwa/sync.js
// ===========================================================================

// sync.js:812-825
function cleanDevices(arr){
  if(!Array.isArray(arr)) return [];
  const byId = new Map();
  arr.forEach(d => {
    if(!d || !d.id) return;
    const prev = byId.get(d.id);
    if(!prev){ byId.set(d.id, d); return; }
    const nameOf = x => x && typeof x.name === "string" ? x.name.trim() : "";
    const isJunk = x => !nameOf(x) && ((x.updatedAt) || 0) === 0;
    const score = x => isJunk(x) ? -1 : ((x.updatedAt) || 0) + (nameOf(x) ? 0.5 : 0);
    if(score(d) > score(prev)) byId.set(d.id, d);
  });
  return [...byId.values()];
}

// sync.js:388-404
function syncSubset(){
  const an = (S.prefs && S.prefs.an) ? { views: S.prefs.an.views || [], metrics: S.prefs.an.metrics || [] } : { views: [], metrics: [] };
  const raw = {
    char: S.char || {},
    tasks: S.tasks || [],
    rewards: S.rewards || [],
    tags: S.tags || [],
    devices: cleanDevices(S.devices || []),
    lastCron: S.lastCron || 0,
    history: S.history || [],
    charHistory: S.charHistory || [],
    an: an,
    monthlyBackups: S.monthlyBackups || [],
    deletions: S.deletions || []
  };
  return JSON.parse(JSON.stringify(raw));
}

// sync.js:1004-1006
function wrap(subset){
  return { schema: 1, savedAt: Date.now(), deviceId: syncDeviceId(), state: subset };
}

// sync.js:57-65, simplified: the real syncDeviceId() persists a uid()-generated id
// into localStorage via syncCfg()/syncCfgSave() the first time it's called. That
// persistence plumbing is orthogonal to wire format, so it is replaced with a fixed
// constant; wrap() itself (above) is untouched/verbatim.
function syncDeviceId(){ return FIXED_DEVICE_ID; }

// ===========================================================================
// Synthetic seed state — fixed uids/timestamps throughout (see constants above).
// Covers: 1 habit, 1 daily (with checklist + streak + reminder), 1 todo (with
// 2-item checklist), 1 reward, 2 tags, 1 device, 1 tombstoned deletion, 1
// charHistory point, 2 monthlyBackups entries, 1 analytics view under prefs.an.
// ===========================================================================
const rawState = {
  char: {
    name: "Aria Stormblade", face: "⚔️", cls: "Warrior",
    lvl: 5, xp: 230, hp: 42, maxHp: 60, mp: 12, gold: 87.5,
    updatedAt: T_CHAR_UPDATED
  },
  tasks: [
    {
      id: TASK_HABIT_ID, type: 'habit', title: 'Drink Water', notes: '',
      difficulty: 'easy', value: 3, done: false,
      checklist: [], up: true, down: true, tags: [TAG_HEALTH_ID],
      // Stage 3 / T3.6: decoy unknown field inside a task object -- must survive Kotlin
      // import -> export via SerializationEngine's per-task extraJson passthrough (T3.1),
      // exactly like `up`/`down`/`reminders[]` already do.
      __taskDecoy: 'keep-me',
      createdAt: T_HABIT_CREATED, updatedAt: T_HABIT_UPDATED
    },
    {
      id: TASK_DAILY_ID, type: 'daily', title: 'Morning Meditation', notes: '10 minutes, no phone',
      difficulty: 'easy', value: 0, done: true,
      checklist: [ { text: 'Breathe', done: true } ], // no id -> migrate() backfills via legacySubtaskId
      repeat: [true, true, true, true, true, true, true], resetFreq: 'daily',
      streak: 4, doneAt: T_DAILY_DONE_AT, tags: [TAG_HEALTH_ID],
      reminders: [ { id: REMINDER_ID, enabled: true, kind: 'daily', time: '07:00', lastFiredKey: '', days: [true, true, true, true, true, true, true] } ],
      createdAt: T_DAILY_CREATED, updatedAt: T_DAILY_UPDATED
    },
    {
      id: TASK_TODO_ID, type: 'todo', title: 'Buy groceries', notes: 'Weekly run',
      difficulty: 'hard', value: -1, done: false,
      checklist: [
        // Stage 3 / T3.6: decoy unknown field inside a checklist item -- must survive via
        // ChecklistItem's own extraJson passthrough (T3.1 opus-review), independent of the
        // containing task's own extraJson.
        { id: TODO_SUB1_ID, text: 'Milk', done: false, __subDecoy: 'keep-me-too' },
        { id: TODO_SUB2_ID, text: 'Eggs', done: true }
      ],
      tags: [TAG_CHORES_ID],
      createdAt: T_TODO_CREATED, updatedAt: T_TODO_UPDATED
    }
  ],
  rewards: [
    { id: REWARD_ID, title: 'Movie night', cost: 20, notes: '', createdAt: T_REWARD_CREATED, updatedAt: T_REWARD_UPDATED }
  ],
  tags: [
    { id: TAG_HEALTH_ID, name: 'Health', color: '#4CAF50', createdAt: T_TAG_HEALTH, updatedAt: T_TAG_HEALTH },
    { id: TAG_CHORES_ID, name: 'Chores', color: '#FF9800', createdAt: T_TAG_CHORES, updatedAt: T_TAG_CHORES }
  ],
  devices: [
    { id: DEVICE_ID, name: 'Pixel 7', updatedAt: T_DEVICE_UPDATED }
  ],
  lastCron: dayStamp(new Date()),
  history: [],
  charHistory: [
    { date: T_CHARHIST, hp: 40, maxHp: 60, xp: 220, mp: 10, gold: 80, lvl: 5 }
  ],
  monthlyBackups: ['2026-06', '2026-07'],
  deletions: [
    { id: DELETED_TAG_ID, at: T_DELETION }
  ],
  prefs: {
    width: 500, notesLines: 4, lastTab: 'analytics', haptics: true, cardThick: 10, saveBtnTop: true,
    dragDelay: 180, notificationsEnabled: true, exportIntervalDays: 7, lastExportTs: T_EXPORT_TS,
    an: {
      views: [
        { id: VIEW_ID, name: 'Weekly completions', source: 'completed', group: 'week', chart: 'bar', tags: [], types: ['habit', 'daily'], metricId: null, createdAt: T_VIEW_CREATED, updatedAt: T_VIEW_UPDATED }
      ],
      metrics: []
    }
  },
  // Stage 3 / T3.6 (stage gate): top-level unknown-field decoy. NOT a key freshState() (or any
  // known PWA schema field) ever produces -- simulates a genuinely future/unmodeled top-level `S`
  // field. Placed as the LAST property of this object literal so migrate()'s
  // `Object.assign(freshState(), rawState, {char:...})` appends it after every known key (keys
  // Object.assign already finds on the target update in place, at their existing position; a
  // brand-new key is appended at the target's current end, in `rawState`'s own enumeration order --
  // i.e. last-declared-here = last-appended-there). Must survive Kotlin import -> export verbatim
  // via SnapshotExtrasEntity/exportBackup's `snapshotExtras` overlay (T3.1).
  __futureField: { nested: ['decoy', 1, true] }
};

// Real boot sequence is `let S = migrate(load())` (app.js:117,121-124); mirror it
// against the synthetic input instead of a localStorage read.
S = migrate(rawState);

// Mirror save()'s durable-state stamps (app.js:255-257) so the fixture looks like
// a state that has actually been saved at least once, exercising the exact
// __seq/__hlcLast/__savedAt key-insertion order the spec's D1 flags as the hash
// key-order crux: __seq is assigned first, then now() (which sets __hlcLast as a
// side effect before returning) is assigned to __savedAt.
S.__seq = 3;
S.__savedAt = now(); // sets S.__hlcLast internally first, matching app.js:256-257 order

// Fixed synthetic event log (as if read from IndexedDB via getEvents()). Ids
// mimic IDB autoincrement keys; uid fields follow the event-uid shape from spec
// §6 (`<deviceId>-<ts36>-<rand36>`, fixed literals, not generated).
const events = [
  {
    id: 1, ts: T_EV1, uid: FIXED_DEVICE_ID + '-lqxh40-aaaaa', dev: FIXED_DEVICE_ID,
    kind: 'complete', taskType: 'daily', taskId: TASK_DAILY_ID, taskTitle: 'Morning Meditation',
    streak: 4, reward: { xp: 10, gold: 2, mp: 0, delta: 1 },
    repeat: [true, true, true, true, true, true, true],
    // Same id migrate() backfills onto S.tasks[1].checklist[0] below (both derive
    // from legacySubtaskId(TASK_DAILY_ID, 'Breathe', 0)), so the event log and the
    // snapshot agree on this subtask's identity.
    checklist: [ { id: legacySubtaskId(TASK_DAILY_ID, 'Breathe', 0), text: 'Breathe', done: true } ]
  },
  {
    id: 2, ts: T_EV2, uid: FIXED_DEVICE_ID + '-lqxh41-bbbbb', dev: FIXED_DEVICE_ID,
    kind: 'subtask', taskType: 'todo', taskId: TASK_TODO_ID, taskTitle: 'Buy groceries',
    subId: TODO_SUB2_ID, subText: 'Eggs', done: true
  },
  {
    id: 3, ts: T_EV3, uid: FIXED_DEVICE_ID + '-lqxh42-ccccc', dev: FIXED_DEVICE_ID,
    kind: 'export', taskTitle: 'Export Data', notes: 'Created backup file via Download', synthetic: false
  },
  {
    // Stage 3 / T3.6: `dir` fixed from the string 'up' to the numeric `1` this event actually
    // uses in the real app -- app.js:1387/1391/1405/1412/1432 (`logEvent({kind:'habitTap',
    // dir:1|-1, ...})`) always emits `dir` as a signed integer, never a string; a prior pass of
    // this fixture invented a non-representative string value here, which produced a false-
    // positive "data loss" signal in the T3.6 round-trip test (EventLogEntity.direction is
    // Int?, so a string `dir` fails typed extraction) for a scenario the real PWA can never
    // actually produce. `kind`/`taskType`/`source`/`repCounted` are left as originally authored
    // (a plausible if not verbatim event shape) since logEvent()'s payload objects are not
    // schema-constrained -- extra decoy-ish fields here are additional passthrough coverage,
    // not a fidelity problem the way a wrong-TYPED known field is.
    id: 4, ts: T_EV4, uid: FIXED_DEVICE_ID + '-lqxh43-ddddd', dev: FIXED_DEVICE_ID,
    kind: 'complete', taskType: 'habit', taskId: TASK_HABIT_ID, taskTitle: 'Drink Water',
    dir: 1, value: 1, reps: 1, source: 'tap', repCounted: true
  }
];

// ===========================================================================
// main
// ===========================================================================
function sha256Hex(str){ return createHash('sha256').update(str, 'utf8').digest('hex'); }

async function main(){
  // --- golden_backup.json (schema-2 tokenized envelope, §1) -----------------
  const { blob } = await buildBackupFile(events);
  const backupText = await blob.text();
  const env = JSON.parse(backupText);

  // Self-check: buildHashPreimage() (duplicated) must reproduce exactly the hash
  // buildBackupFile() (verbatim) embedded, proving the duplicate is faithful.
  const preJson = buildHashPreimage(events);
  const expectedHash = await computeHash(preJson);
  if(env._backup.hash !== expectedHash){
    throw new Error('generator bug: buildHashPreimage() duplicate does not match buildBackupFile()\'s embedded hash — ' + env._backup.hash + ' vs ' + expectedHash);
  }

  const backupPath = join(FIXTURES_DIR, 'golden_backup.json');
  writeFileSync(backupPath, backupText, 'utf8');

  const preimagePath = join(FIXTURES_DIR, 'golden_hash_preimage.txt');
  writeFileSync(preimagePath, preJson, 'utf8');

  // --- golden_state.json (Dropbox /state.json wrapper, §3) ------------------
  // Real upload body is `JSON.stringify(wrapperObj)` (sync.js:300) — compact, no
  // pretty-printing, unlike the backup envelope.
  const subset = syncSubset();
  const stateWrapper = wrap(subset);
  const stateText = JSON.stringify(stateWrapper);
  const statePath = join(FIXTURES_DIR, 'golden_state.json');
  writeFileSync(statePath, stateText, 'utf8');

  // --- report -----------------------------------------------------------
  const files = [
    ['fixtures/golden_backup.json', backupPath],
    ['fixtures/golden_state.json', statePath],
    ['fixtures/golden_hash_preimage.txt', preimagePath]
  ];
  console.log('Generated:');
  for(const [label, p] of files){
    const bytes = readFileSync(p);
    console.log('  ' + label + '  sha256=' + sha256Hex(bytes.toString('utf8')) + '  bytes=' + bytes.length);
  }
  console.log('_backup.hash  = ' + env._backup.hash);
  console.log('_backup.schema= ' + env._backup.schema);
  console.log('top-level env keys: ' + Object.keys(env).join(', '));
  console.log('top-level state.json keys: ' + Object.keys(stateWrapper).join(', '));
}

main().catch(e => { console.error(e); process.exit(1); });
