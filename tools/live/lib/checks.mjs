// WP-8.12 live gate: pure helpers (no I/O, no network). Unit-tested offline in
// tools/live/test/checks.test.mjs. Nothing here ever sees a Dropbox token.

// ---- Dropbox path policy -----------------------------------------------------
// The app key names an "App folder" app (see DropboxConfig / local.properties -- never
// committed), so every path below is relative to that app folder, and Dropbox itself
// refuses anything outside it.
// The harness writes ONE kind of file itself: an inbox record (scenarios 5, 16).
// Everything else is written by the two apps' own sync code.
export const INJECT_DIR = '/inbox';
const INBOX_NAME = /^[A-Za-z0-9-]{8,64}\.json$/;

export function assertInjectPath(path) {
  if (typeof path !== 'string') throw new Error('inject path must be a string');
  const m = /^\/inbox\/([^/]+)$/.exec(path);
  if (!m || !INBOX_NAME.test(m[1]) || path.includes('..')) {
    throw new Error('refused: the harness may only write /inbox/<id>.json, got ' + path);
  }
  return path;
}

// ---- Opti stableStringify (sync.js:787-797), sorted keys, cycle-safe -----------
export function stableClone(o, seen) {
  if (o === null || typeof o !== 'object') return o;
  seen = seen || new WeakSet();
  if (seen.has(o)) return null;
  seen.add(o);
  // Exactly as Opti: `seen` is never cleared, so a repeated (non-circular) reference is null too.
  if (Array.isArray(o)) return o.map((x) => stableClone(x, seen));
  const out = {};
  for (const k of Object.keys(o).sort()) out[k] = stableClone(o[k], seen);
  return out;
}
export function stableStringify(o) { return JSON.stringify(stableClone(o)); }

// ---- inbox records (Bubblewrap InboxUploadWorker bytes, G6 key order) ----------
// G6's own samples ("r1", no `kind`) all parse to null in Opti: the id is shorter than
// /^[A-Za-z0-9-]{8,64}$/ and `kind` is missing. Live records therefore carry a long id and
// kind:"habit" (see WP-8.12_NOTES.md, G6 question).
export function makeInboxRecord({ id, habitId, dir, ts }) {
  const rec = { v: 1, id, kind: 'habit', habitId, dir, ts };
  const text = JSON.stringify(rec);
  const name = id + '.json';
  if (!parseInboxRecord(text, name)) throw new Error('invalid inbox record ' + text);
  return { name, path: INJECT_DIR + '/' + name, text };
}

/** Mirror of Opti inboxParseRecord (sync.js:547-560); used to self-check injected bytes. */
export function parseInboxRecord(text, fileName) {
  let o;
  try { o = JSON.parse(text); } catch { return null; }
  if (!o || typeof o !== 'object' || o.v !== 1) return null;
  if (typeof o.id !== 'string' || !/^[A-Za-z0-9-]{8,64}$/.test(o.id)) return null;
  if (fileName !== o.id + '.json') return null;
  if (o.kind !== 'habit') return null;
  if (typeof o.habitId !== 'string' || !o.habitId) return null;
  if (o.dir !== 1 && o.dir !== -1) return null;
  if (typeof o.ts !== 'number' || !isFinite(o.ts) || o.ts <= 0) return null;
  return o;
}

export function liveId(prefix, nowMs, rand = Math.random) {
  const r = Math.floor(rand() * 0xffffff).toString(16).padStart(6, '0');
  return ('live812-' + prefix + '-' + nowMs.toString(36) + '-' + r).slice(0, 64);
}

// ---- state helpers -----------------------------------------------------------
export function taskById(subset, id) {
  return ((subset && subset.tasks) || []).find((t) => t && t.id === id) || null;
}
export function tombstoneFor(subset, id) {
  return ((subset && subset.deletions) || []).find((d) => d && d.id === id) || null;
}
export function sectionsExcept(subset, keepTypes) {
  // "other sections" for scenario 7: everything but tasks of the imported types.
  const out = {};
  for (const k of Object.keys(subset || {})) {
    if (k === 'tasks') out.tasks = (subset.tasks || []).filter((t) => !keepTypes.includes(t && t.type));
    else out[k] = subset[k];
  }
  return out;
}

// ---- backup rotation (scenario 13) ------------------------------------------
// Opti _bkPath: /questa-backups/<prefix>-<dev6>-<slot2>-<yyyymmdd-hhmmss>.json
export const TIER_LIMITS = { '4hour': 10, daily: 7, weekly: 4, monthly: 4 };
const BK_NAME = /^(4hour|daily|weekly|monthly)-([A-Za-z0-9_-]{1,6})-(\d{2})-(\d{8}-\d{6})\.json$/;

export function parseBackupName(name) {
  const m = BK_NAME.exec(name);
  return m ? { tier: m[1], dev: m[2], slot: Number(m[3]), stamp: m[4] } : null;
}

/** ok when every device keeps <= the tier limit, and no file of a device vanished unless it
 *  is that device's own rotation (`ownRotators` = the devices that fired in between). */
export function checkBackupRotation(before, after, ownRotators) {
  const count = {};
  for (const n of after) {
    const p = parseBackupName(n); if (!p) continue;
    const k = p.dev + '/' + p.tier; count[k] = (count[k] || 0) + 1;
  }
  const over = Object.entries(count).filter(([k, c]) => c > TIER_LIMITS[k.split('/')[1]]);
  const afterSet = new Set(after);
  const foreign = before.filter((n) => {
    if (afterSet.has(n)) return false;
    const p = parseBackupName(n);
    return !p || !ownRotators.includes(p.dev);
  });
  return { ok: over.length === 0 && foreign.length === 0, over, foreignDeletions: foreign, count };
}

// ---- cross-TZ pick (scenario 4) -----------------------------------------------
/** Picks the candidate zone whose calendar date equals the phone's, with the largest offset gap. */
export function pickCrossTz(nowMs, phoneOffsetMin, candidates) {
  const day = (offMin) => new Date(nowMs + offMin * 60000).toISOString().slice(0, 10);
  const phoneDay = day(phoneOffsetMin);
  let best = null;
  for (const c of candidates) {
    if (day(c.offsetMin) !== phoneDay) continue;
    const gap = Math.abs(c.offsetMin - phoneOffsetMin);
    if (gap >= 120 && (!best || gap > best.gap)) best = { ...c, gap };
  }
  return best;
}

// ---- zip central directory (D30 export-coverage check) -----------------------
/** Lists the entry names of a zip file (Buffer). No decompression needed. */
export function zipEntryNames(buf) {
  let eocd = -1;
  for (let i = buf.length - 22; i >= Math.max(0, buf.length - 65557); i--) {
    if (buf.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
  }
  if (eocd < 0) throw new Error('not a zip file (no end-of-central-directory record)');
  let n = buf.readUInt16LE(eocd + 10);
  let off = buf.readUInt32LE(eocd + 16);
  const names = [];
  while (n-- > 0) {
    if (buf.readUInt32LE(off) !== 0x02014b50) throw new Error('bad central directory entry');
    const nameLen = buf.readUInt16LE(off + 28), extra = buf.readUInt16LE(off + 30), cmt = buf.readUInt16LE(off + 32);
    names.push(buf.toString('utf8', off + 46, off + 46 + nameLen));
    off += 46 + nameLen + extra + cmt;
  }
  return names;
}

/** Top-level Dropbox areas every scenario may touch; the zip must hold each one that existed. */
export const TOUCHED_AREAS = ['state.json', 'events/', 'inbox/', 'inbox-claimed/', 'inbox-rejected/', 'inbox-meta/', 'questa-backups/', 'export-backup.json'];

export function exportCoverage({ exportJson, zipNames }) {
  const b = (exportJson && exportJson._backup) || {};
  const sections = Array.isArray(b.sections) ? b.sections : null;
  // The zip is made from the folder, so its names start with "Questa-temp/" (or not).
  const rel = zipNames.map((n) => n.replace(/^[^/]*Questa-temp\//, ''));
  const areas = {};
  for (const a of TOUCHED_AREAS) areas[a] = rel.some((n) => (a.endsWith('/') ? n.startsWith(a) : n === a));
  return {
    exportSchema: b.schema ?? null,
    exportHasHash: typeof b.hash === 'string' && b.hash.length > 0,
    exportSections: sections,
    exportTaskCount: Array.isArray(exportJson && exportJson.tasks) ? exportJson.tasks.length : null,
    zipAreas: areas,
    zipHasState: areas['state.json'],
  };
}

// ---- xp across level-ups (scenario 9) ----------------------------------------
// Opti app.js:2210 xpToLevel and sync.js:1667-1688 _charTotalXp/_charFromTotalXp: char.xp is the
// residual inside the current level, so sums are done on the TOTAL and (lvl, xp) re-derived.
export function xpToLevel(lvl) { return Math.round(0.25 * lvl * lvl + 10 * lvl + 139.75); }
const num = (v) => Number(v) || 0;
export function charTotalXp(c) {
  const lvl = Math.max(1, Math.floor(num(c && c.lvl) || 1));
  let tot = Math.max(0, num(c && c.xp));
  for (let k = 1; k < lvl && k <= 9999; k++) tot += xpToLevel(k);
  return tot;
}
export function charFromTotalXp(total) {
  let rem = Math.max(0, num(total)), lvl = 1, need = xpToLevel(1);
  while (rem >= need && lvl <= 9999) { rem -= need; lvl++; need = xpToLevel(lvl); }
  return { lvl, xp: rem };
}
/** Expected (lvl, xp) after both sides' gains are summed: Kotlin c0 -> c1, PWA p0 -> p1 (c0 = p0 after a sync). */
export function expectedCharXp(c0, c1, p0, p1) {
  return charFromTotalXp(charTotalXp(c0) + (charTotalXp(c1) - charTotalXp(c0)) + (charTotalXp(p1) - charTotalXp(p0)));
}
export function charXpMatches(actual, expected) {
  return !!actual && Math.floor(num(actual.lvl) || 1) === expected.lvl && Math.abs(num(actual.xp) - expected.xp) < 1e-6;
}

// ---- reporting helpers -------------------------------------------------------
/** The JSON paths where a and b differ (after the Opti stable form), capped at [max]. */
export function diffPaths(a, b, max = 20, path = '$', out = []) {
  if (out.length >= max) return out;
  const A = stableClone(a), B = stableClone(b);
  if (JSON.stringify(A) === JSON.stringify(B)) return out;
  if (A && B && typeof A === 'object' && typeof B === 'object' && Array.isArray(A) === Array.isArray(B)) {
    const keys = new Set([...Object.keys(A), ...Object.keys(B)]);
    for (const k of keys) diffPaths(A[k], B[k], max, path + (Array.isArray(A) ? '[' + k + ']' : '.' + k), out);
  } else out.push(path);
  return out;
}

/** UTC offset of an IANA zone at nowMs, in minutes (uses the host's Intl data). */
export function zoneOffsetMin(zone, nowMs) {
  const parts = new Intl.DateTimeFormat('en-US', { timeZone: zone, timeZoneName: 'longOffset' }).formatToParts(new Date(nowMs));
  const name = (parts.find((p) => p.type === 'timeZoneName') || {}).value || 'GMT';
  const m = /GMT([+-])(\d{2}):?(\d{2})?/.exec(name);
  if (!m) return 0;
  return (m[1] === '-' ? -1 : 1) * (Number(m[2]) * 60 + Number(m[3] || 0));
}

export const TZ_CANDIDATES = ['Pacific/Kiritimati', 'Pacific/Auckland', 'Asia/Tokyo', 'Asia/Kolkata', 'Europe/London',
  'America/Sao_Paulo', 'America/New_York', 'America/Los_Angeles', 'Pacific/Honolulu', 'Pacific/Pago_Pago'];

/** Task identity used by "tasks are equal" (scenario 6): id, type, title, done, counters. */
export function taskKey(t) {
  return { id: t.id, type: t.type, title: t.title, done: !!t.done, cUp: t.cUp || 0, cDown: t.cDown || 0 };
}
/** A copy of a sync subset with tasks, rewards and tags sorted by id (order-free compare). */
export function sortById(sub) {
  if (!sub || typeof sub !== 'object') return sub;
  const byId = (x, y) => (String(x && x.id) < String(y && y.id) ? -1 : String(x && x.id) > String(y && y.id) ? 1 : 0);
  const out = { ...sub };
  for (const k of ['tasks', 'rewards', 'tags']) if (Array.isArray(sub[k])) out[k] = [...sub[k]].sort(byId);
  return out;
}
export function stableById(sub) { return stableStringify(sortById(sub)); }

// Every id-keyed array Opti merges with "keep LOCAL order, append remote-only at the end"
// (mergeCollection sync.js:1552-1558, mergeDevices sync.js:1650-1654).
// deletions: mergedDeletions is a Map filled base, remote, local (sync.js:2152-2200), so its order is per device too.
export const ORDERED_ID_ARRAYS = ['tasks', 'rewards', 'tags', 'devices', 'an.views', 'an.metrics', 'deletions'];
const at = (o, path) => path.split('.').reduce((v, k) => (v && typeof v === 'object' ? v[k] : undefined), o);
/** The ORDERED_ID_ARRAYS paths whose id sequence differs between two sync subsets. */
export function idOrderDiff(a, b) {
  const ids = (arr) => (Array.isArray(arr) ? arr.map((x) => String(x && x.id)) : []);
  return ORDERED_ID_ARRAYS.filter((p) => JSON.stringify(ids(at(a, p))) !== JSON.stringify(ids(at(b, p))));
}
/** stableStringify of a subset with every ORDERED_ID_ARRAYS array sorted by id (order-free). */
export function stableAllById(sub) {
  if (!sub || typeof sub !== 'object') return stableStringify(sub);
  const byId = (x, y) => (String(x && x.id) < String(y && y.id) ? -1 : String(x && x.id) > String(y && y.id) ? 1 : 0);
  const out = JSON.parse(JSON.stringify(sub));
  for (const p of ORDERED_ID_ARRAYS) {
    const keys = p.split('.'); const last = keys.pop();
    const parent = keys.reduce((v, k) => (v && typeof v === 'object' ? v[k] : undefined), out);
    if (parent && Array.isArray(parent[last])) parent[last] = [...parent[last]].sort(byId);
  }
  return stableStringify(out);
}
export function sameTasks(a, b) {
  const norm = (arr) => (arr || []).filter(Boolean).map(taskKey).sort((x, y) => (x.id < y.id ? -1 : x.id > y.id ? 1 : 0));
  const A = norm(a), B = norm(b);
  return { ok: stableStringify(A) === stableStringify(B), diff: diffPaths(A, B) };
}

function parseArg(c) { try { return JSON.parse(c.arg); } catch { return {}; } }

/** Dropbox uploads of /state.json recorded by LiveInteropTest (calls[].arg = Dropbox-API-Arg JSON). */
export function stateUploads(calls) {
  return (calls || []).filter((c) => /\/files\/upload$/.test(c.endpoint) && c.arg).map((c) => {
    const a = parseArg(c);
    const mode = a.mode || {};
    return { path: a.path, tag: mode['.tag'] || mode, updateRev: mode.update || null, status: c.status };
  }).filter((u) => u.path === '/state.json');
}

/** Paths the phone uploaded, from the same records (backups, events, meta, state). */
export function uploadedPaths(calls) {
  return (calls || []).filter((c) => /\/files\/upload$/.test(c.endpoint) && c.arg).map((c) => parseArg(c).path).filter(Boolean);
}
