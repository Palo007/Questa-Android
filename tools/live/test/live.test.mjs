// Offline tests for the WP-8.12 live harness: `node --test tools/live/test/`.
// No browser, no adb, no Dropbox. The Opti tests only READ Opti's git object database.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { existsSync, mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync, mkdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import vm from 'node:vm';
import {
  assertInjectPath, charFromTotalXp, charTotalXp, charXpMatches, checkBackupRotation, diffPaths, expectedCharXp, xpToLevel, exportCoverage, liveId, makeInboxRecord, parseBackupName,
  idOrderDiff, parseInboxRecord, pickCrossTz, sameTasks, sectionsExcept, sortById, stableAllById, stableById, stableStringify, stateUploads, uploadedPaths,
  zipEntryNames, zoneOffsetMin,
} from '../lib/checks.mjs';
import { DEFAULT_OPTI_ROOT, extractOpti, OPTI_REV, resolveStatic, serveOpti } from '../lib/opti.mjs';
import { CLEAR_CLOCK_CMD, CLOCK_RECEIVER, clockOffsetArgs, instrumentArgs, isDaemonError, makePhone, parseInstrumentOutput, PKG, RUNNER, TEST_CLASS } from '../lib/phone.mjs';
import {
  assertNoSecret, disableForcePush, DROPBOX_COOKIE_DOMAIN, guardedExpression, inPage, probeLinked, PROFILE_DIR, refuseLoginIfLinked,
} from '../lib/pwa.mjs';
import { fakeDayOffsetMs, fakeDayWarning, futureUntilMs, LOGIN_TEXT, parseArgs, planText, runWithFakeDay } from '../run_live.mjs';
import { SCENARIOS } from '../scenarios.mjs';
import { coverageReport } from '../export_coverage.mjs';

const HERE = new URL('.', import.meta.url);
const LIVE_DIR = new URL('..', HERE);
const haveOpti = existsSync(join(DEFAULT_OPTI_ROOT, '.git'));

// ---- path policy ---------------------------------------------------------------
test('inject path: only /inbox/<id>.json', () => {
  assert.equal(assertInjectPath('/inbox/live812-rec5-abc-123456.json'), '/inbox/live812-rec5-abc-123456.json');
  for (const bad of ['/state.json', '/inbox/../state.json', '/inbox/a/b.json', '/inbox/short.json', '/events/x.json',
    'inbox/live812-rec5-abc.json', '/inbox-claimed/dev/live812-rec5-abc.json', '/inbox/live812_rec.json']) {
    assert.throws(() => assertInjectPath(bad), /refused/, bad);
  }
});

test('in-page inject guard matches the node guard', () => {
  const src = inPage.inject.toString();
  assert.match(src, /\^\\\/inbox\\\/\[A-Za-z0-9-\]\{8,64\}\\\.json\$/);
});

// ---- inbox records ---------------------------------------------------------------
test('inbox record: valid live record parses; G6 samples do not (no kind, short id)', () => {
  const r = makeInboxRecord({ id: 'live812-rec5-kf00-abcdef', habitId: 'h-1', dir: 1, ts: 1700000000000 });
  assert.equal(r.path, '/inbox/live812-rec5-kf00-abcdef.json');
  assert.deepEqual(parseInboxRecord(r.text, r.name), JSON.parse(r.text));
  // G6 valid_habit_tap bytes (tools/gen_opti_fixtures.mjs:962): Opti's inboxParseRecord refuses them.
  assert.equal(parseInboxRecord(JSON.stringify({ v: 1, id: 'r1', dir: 1, ts: 1700000000000, habitId: 'h1' }), 'r1.json'), null);
  assert.equal(parseInboxRecord(JSON.stringify({ v: 1, id: 'abcdefgh1', dir: 1, ts: 1, habitId: 'h1' }), 'abcdefgh1.json'), null);
  assert.throws(() => makeInboxRecord({ id: 'x', habitId: 'h', dir: 1, ts: 1 }));
  assert.throws(() => makeInboxRecord({ id: 'abcdefgh1', habitId: 'h', dir: 0, ts: 1 }));
});

test('liveId is a valid inbox id and <= 64 chars', () => {
  const id = liveId('rec16', Date.now(), () => 0.5);
  assert.match(id, /^[A-Za-z0-9-]{8,64}$/);
});

test('Opti inboxParseRecord agrees with the mirror (read from the pinned commit)', { skip: !haveOpti }, () => {
  const src = execFileSync('git', ['-C', DEFAULT_OPTI_ROOT, 'show', OPTI_REV + ':sync.js'], { encoding: 'utf8' });
  const start = src.indexOf('function inboxParseRecord(');
  const end = src.indexOf('\nasync function _inboxReject', start);
  const box = {}; vm.runInNewContext(src.slice(start, end) + '\nbox.f = inboxParseRecord;', { box });
  const cases = [
    [makeInboxRecord({ id: 'live812-abcdefgh', habitId: 'h', dir: -1, ts: 5 }).text, 'live812-abcdefgh.json'],
    [JSON.stringify({ v: 1, id: 'r1', dir: 1, ts: 1, habitId: 'h1' }), 'r1.json'],
    [JSON.stringify({ v: 1, id: 'abcdefgh1', kind: 'habit', dir: 1, ts: 1, habitId: 'h1' }), 'other.json'],
    ['not json', 'x.json'],
  ];
  for (const [text, name] of cases) assert.equal(!!box.f(text, name), !!parseInboxRecord(text, name), text);
});

// ---- stableStringify parity with Opti ---------------------------------------------
test('stableStringify matches Opti sync.js:787-797 byte for byte', { skip: !haveOpti }, () => {
  const src = execFileSync('git', ['-C', DEFAULT_OPTI_ROOT, 'show', OPTI_REV + ':sync.js'], { encoding: 'utf8' });
  const start = src.indexOf('function stableClone(');
  const end = src.indexOf('function deepEqual(', start);
  const box = {}; vm.runInNewContext(src.slice(start, end) + '\nbox.f = stableStringify;', { box });
  const shared = { z: 1 };
  const samples = [{ b: 2, a: [3, { d: 1, c: null }], u: undefined }, [shared, shared], 'x', 5, null, { n: -0, f: 1.5e21 }];
  for (const s of samples) assert.equal(stableStringify(s), box.f(s));
});

// ---- backups ------------------------------------------------------------------------
test('backup names and rotation check', () => {
  assert.deepEqual(parseBackupName('daily-a1b2c3-03-20260925-101500.json'), { tier: 'daily', dev: 'a1b2c3', slot: 3, stamp: '20260925-101500' });
  assert.equal(parseBackupName('export-backup.json'), null);
  const mk = (tier, dev, n) => Array.from({ length: n }, (_, i) => `${tier}-${dev}-${String(i).padStart(2, '0')}-20260925-1${String(i).padStart(5, '0')}.json`);
  const before = [...mk('daily', 'kotdev', 7), ...mk('daily', 'realph', 3)];
  const after = [...mk('daily', 'kotdev', 7).slice(1), 'daily-kotdev-00-20260926-000000.json', ...mk('daily', 'realph', 3)];
  assert.equal(checkBackupRotation(before, after, ['kotdev', 'pwadev']).ok, true);
  const lost = checkBackupRotation(before, after.filter((n) => !n.includes('realph-00')), ['kotdev']);
  assert.equal(lost.ok, false); assert.deepEqual(lost.foreignDeletions, ['daily-realph-00-20260925-100000.json']);
  const over = checkBackupRotation([], mk('weekly', 'kotdev', 5), ['kotdev']);
  assert.equal(over.ok, false); assert.deepEqual(over.over, [['kotdev/weekly', 5]]);
});

// ---- time zones ----------------------------------------------------------------------
test('pickCrossTz keeps the calendar date and needs >= 2 h', () => {
  const now = Date.UTC(2026, 8, 25, 12, 0); // 12:00 UTC
  const c = [{ zone: 'A', offsetMin: 13 * 60 }, { zone: 'B', offsetMin: 9 * 60 }, { zone: 'C', offsetMin: -5 * 60 }, { zone: 'D', offsetMin: 180 }];
  // phone at +02:00 (14:00, same date): A is 01:00 next day (skip); B (+7 h) beats C (-7 h? equal) and D (+1 h, too small)
  const best = pickCrossTz(now, 120, c);
  assert.ok(best && ['B', 'C'].includes(best.zone) && best.gap === 420);
  assert.equal(pickCrossTz(now, 120, [{ zone: 'D', offsetMin: 180 }]), null);
  assert.equal(zoneOffsetMin('UTC', now), 0);
  assert.equal(zoneOffsetMin('Asia/Kolkata', now), 330);
});

// ---- zip + export coverage -------------------------------------------------------------
function makeZip(names) {
  const locals = []; const centrals = []; let off = 0;
  for (const n of names) {
    const nb = Buffer.from(n, 'utf8');
    const lh = Buffer.alloc(30); lh.writeUInt32LE(0x04034b50, 0); lh.writeUInt16LE(nb.length, 26);
    const ch = Buffer.alloc(46); ch.writeUInt32LE(0x02014b50, 0); ch.writeUInt16LE(nb.length, 28); ch.writeUInt32LE(off, 42);
    locals.push(lh, nb); centrals.push(ch, nb); off += 30 + nb.length;
  }
  const cd = Buffer.concat(centrals);
  const eocd = Buffer.alloc(22); eocd.writeUInt32LE(0x06054b50, 0); eocd.writeUInt16LE(names.length, 8); eocd.writeUInt16LE(names.length, 10);
  eocd.writeUInt32LE(cd.length, 12); eocd.writeUInt32LE(off, 16);
  return Buffer.concat([...locals, cd, eocd]);
}

test('zipEntryNames reads the central directory', () => {
  const names = ['Questa-temp/state.json', 'Questa-temp/events/dev-202609.json', 'Questa-temp/questa-backups/daily-a-00-x.json'];
  assert.deepEqual(zipEntryNames(makeZip(names)), names);
  assert.throws(() => zipEntryNames(Buffer.from('nope')), /not a zip/);
});

test('exportCoverage names the missing areas', () => {
  const c = exportCoverage({
    exportJson: { _backup: { schema: 2, hash: 'abc', sections: ['char', 'habits'] }, tasks: [1, 2] },
    zipNames: ['Questa-temp/state.json', 'Questa-temp/events/a.json'],
  });
  assert.equal(c.exportSchema, 2); assert.equal(c.exportHasHash, true); assert.equal(c.zipHasState, true);
  assert.equal(c.zipAreas['events/'], true); assert.equal(c.zipAreas['questa-backups/'], false);
});

test('coverageReport wires hash + import + zip (fake node runner)', () => {
  const dir = mkdtempSync(join(tmpdir(), 'cov-'));
  try {
    writeFileSync(join(dir, 'e.json'), JSON.stringify({ _backup: { schema: 2, hash: 'h' } }));
    writeFileSync(join(dir, 'f.zip'), makeZip(['state.json', 'inbox-meta/habits.json']));
    const calls = [];
    const r = coverageReport(join(dir, 'e.json'), join(dir, 'f.zip'), (a) => { calls.push(a); return { code: 0 }; });
    assert.equal(r.pass, true); assert.equal(calls.length, 2); assert.ok(calls[1].includes('--import'));
    assert.ok(r.zipMissingAreas.includes('events/'));
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

// ---- reporting helpers -----------------------------------------------------------------
test('diffPaths, sameTasks, sectionsExcept', () => {
  assert.deepEqual(diffPaths({ a: 1, b: [1, 2] }, { a: 1, b: [1, 3] }), ['$.b[1]']);
  assert.deepEqual(diffPaths({ a: 1 }, { a: 1 }), []);
  assert.equal(sameTasks([{ id: 'b', type: 'todo', title: 'x' }, { id: 'a', type: 'habit', title: 'y', cUp: 1 }],
    [{ id: 'a', type: 'habit', title: 'y', cUp: 1, extra: 9 }, { id: 'b', type: 'todo', title: 'x', done: false }]).ok, true);
  assert.equal(sameTasks([{ id: 'a', title: 'y' }], [{ id: 'a', title: 'z' }]).ok, false);
  const s = sectionsExcept({ tasks: [{ id: 1, type: 'todo' }, { id: 2, type: 'habit' }], char: { lvl: 1 } }, ['todo']);
  assert.deepEqual(s, { tasks: [{ id: 2, type: 'habit' }], char: { lvl: 1 } });
});

test('stateUploads reads mode/update rev from Dropbox-API-Arg only', () => {
  const calls = [
    { endpoint: '/2/files/upload', arg: JSON.stringify({ path: '/state.json', mode: { '.tag': 'update', update: '015abc' } }), status: 200 },
    { endpoint: '/2/files/upload', arg: JSON.stringify({ path: '/events/d-202609.json', mode: { '.tag': 'overwrite' } }), status: 200 },
    { endpoint: '/2/files/download', arg: JSON.stringify({ path: '/state.json' }), status: 200 },
  ];
  assert.deepEqual(stateUploads(calls), [{ path: '/state.json', tag: 'update', updateRev: '015abc', status: 200 }]);
  assert.deepEqual(uploadedPaths(calls), ['/state.json', '/events/d-202609.json']);
});

// ---- phone -----------------------------------------------------------------------------
test('instrumentArgs: gated, exact, validated', () => {
  assert.deepEqual(instrumentArgs(5, 'round-kill-after-claim', { id: 'live812-x', n: 3 }), [
    'shell', 'am', 'instrument', '-w', '-e', 'live', 'yes', '-e', 'scenario', '5', '-e', 'step', 'round-kill-after-claim',
    '-e', 'id', 'live812-x', '-e', 'n', '3', '-e', 'class', TEST_CLASS, RUNNER]);
  assert.throws(() => instrumentArgs(0, 'round'));
  assert.throws(() => instrumentArgs(18, 'round'));
  assert.throws(() => instrumentArgs(1, 'Round; rm -rf'));
  assert.throws(() => instrumentArgs(1, 'round', { title: 'a b' }));
  assert.throws(() => instrumentArgs(1, 'round', { 'x;y': '1' }));
});

test('parseInstrumentOutput', () => {
  assert.equal(parseInstrumentOutput('...\nOK (1 test)\n').status, 'ok');
  assert.equal(parseInstrumentOutput('INSTRUMENTATION_RESULT: shortMsg=Process crashed.').status, 'crashed');
  assert.equal(parseInstrumentOutput('FAILURES!!!\nTests run: 1').status, 'failed');
});

// ---- Opti extract + server -----------------------------------------------------------------
test('resolveStatic refuses traversal and dotfiles', () => {
  const dir = mkdtempSync(join(tmpdir(), 'opti-'));
  try {
    writeFileSync(join(dir, 'index.html'), 'hi'); mkdirSync(join(dir, '.well-known')); writeFileSync(join(dir, '.well-known', 'a.json'), '{}');
    assert.equal(resolveStatic(dir, '/'), join(dir, 'index.html'));
    assert.equal(resolveStatic(dir, '/index.html?code=x'), join(dir, 'index.html'));
    for (const bad of ['/../x', '/%2e%2e/x', '/.well-known/a.json', '/missing.js', '/%00']) assert.equal(resolveStatic(dir, bad), null, bad);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

// Some hosts (sandboxes, endpoint security) block loopback TCP; the server test then skips.
async function loopbackWorks() {
  const { createServer } = await import('node:http');
  const s = createServer((q, r) => r.end('ok'));
  const port = 17000 + Math.floor(Math.random() * 900);
  await new Promise((ok) => s.listen(port, '127.0.0.1', ok));
  try { return (await (await fetch('http://127.0.0.1:' + port + '/', { signal: AbortSignal.timeout(3000) })).text()) === 'ok'; }
  catch { return false; } finally { s.close(); }
}

test('serveOpti is GET/HEAD only', async (t) => {
  if (!(await loopbackWorks())) { t.skip('loopback TCP is blocked on this host'); return; }
  const dir = mkdtempSync(join(tmpdir(), 'opti-'));
  writeFileSync(join(dir, 'index.html'), '<p>ok</p>');
  const port = 18000 + Math.floor(Math.random() * 1000);
  const s = await serveOpti(dir, { port });
  try {
    const base = 'http://127.0.0.1:' + port;
    assert.equal(await (await fetch(base + '/')).text(), '<p>ok</p>');
    assert.equal((await fetch(base + '/', { method: 'POST', body: 'x' })).status, 405);
    assert.equal((await fetch(base + '/nope')).status, 404);
  } finally { await s.close(); rmSync(dir, { recursive: true, force: true }); }
});

test('extractOpti reads the pinned commit and leaves the Opti repo untouched', { skip: !haveOpti }, () => {
  const status = () => execFileSync('git', ['-C', DEFAULT_OPTI_ROOT, 'status', '--porcelain'], { encoding: 'utf8' });
  const head = () => execFileSync('git', ['-C', DEFAULT_OPTI_ROOT, 'rev-parse', 'HEAD'], { encoding: 'utf8' });
  const s0 = status(), h0 = head();
  const out = mkdtempSync(join(tmpdir(), 'optix-'));
  try {
    const dir = extractOpti({ outRoot: out });
    for (const f of ['index.html', 'app.js', 'sync.js', 'sw.js']) assert.ok(existsSync(join(dir, f)), f);
    // Shape check only: the value is the identity of a real Dropbox app and is not committed.
    assert.match(readFileSync(join(dir, 'sync.js'), 'utf8'), /const DBX_APP_KEY = "[a-z0-9]{15,}"/);
    assert.equal(extractOpti({ outRoot: out }), dir); // stamp: no second extract
  } finally { rmSync(out, { recursive: true, force: true }); }
  assert.equal(status(), s0); assert.equal(head(), h0);
});

// ---- PWA side: no token ever leaves the page ------------------------------------------------
test('assertNoSecret refuses token-shaped values', () => {
  assert.doesNotThrow(() => assertNoSecret({ connected: true, lastRev: '015f' }, 't'));
  assert.throws(() => assertNoSecret({ x: 'sl.' + 'A'.repeat(40) }, 't'), /refused/);
  assert.throws(() => assertNoSecret('{"refreshToken":"abcdefghijk"}', 't'), /refused/);
  assert.throws(() => assertNoSecret('Bearer abcdefghijkl', 't'), /refused/);
});

// Runs guardedExpression in a fake "page" (vm context with the PWA's config reader), so the in-page
// value comparison is tested for real, including a bare refresh token no pattern would catch.
async function runGuarded(name, arg, cfg, globals = {}) {
  const store = { 'questa.sync.pkce': JSON.stringify({ v: 'pkce-verifier-0123456789' }) };
  const sandbox = {
    syncCfg: () => cfg,
    localStorage: { getItem: (k) => (k in store ? store[k] : null) },
    JSON, ...globals,
  };
  return vm.runInNewContext(guardedExpression(name, arg), sandbox);
}

test('in-page guard refuses a result holding a stored token (bare refresh token too)', async () => {
  const bare = 'q7XbareRefreshTokenWithNoPrefix000111'; // no "sl." prefix, no key name: layer 2 misses it
  const cfg = { accessToken: 'sl.u.AAAAaccessToken1234567890', refreshToken: bare, lastRev: '0a1', enabled: true };
  assert.doesNotThrow(() => assertNoSecret({ text: 'x' + bare }, 't')); // shows why layer 1 is needed
  const ok = await runGuarded('download', '/inbox-meta/habits.json', cfg, { dbxDownloadRaw: async () => ({ text: '{"habits":[]}', rev: 'r1' }) });
  assert.deepEqual(JSON.parse(JSON.stringify(ok)), { text: '{"habits":[]}', rev: 'r1' });
  await assert.rejects(runGuarded('download', '/x.json', cfg, { dbxDownloadRaw: async () => ({ text: 'leak ' + bare, rev: 'r' }) }), /refused in page/);
  await assert.rejects(runGuarded('download', '/x.json', cfg, { dbxDownloadRaw: async () => ({ text: cfg.accessToken }) }), /refused in page/);
  await assert.rejects(runGuarded('download', '/x.json', cfg, { dbxDownloadRaw: async () => ({ text: 'pkce-verifier-0123456789' }) }), /refused in page/);
  // the refusal text never carries the token
  try { await runGuarded('download', '/x.json', cfg, { dbxDownloadRaw: async () => ({ text: bare }) }); } catch (e) { assert.ok(!String(e.message).includes(bare)); }
  // status reports only "connected", never the token
  const st = await runGuarded('status', undefined, cfg, { dbxAppKey: () => 'stub-app-key', syncDeviceId: () => 'dev-1', Date, });
  assert.equal(st.connected, true); assert.ok(!JSON.stringify(st).includes(bare));
  assert.throws(() => guardedExpression('nope'), /no in-page helper/);
});

test('--login probe, gate and force-push stubs', () => {
  const store = {};
  const probe = (v) => { if (v === undefined) delete store['questa.sync.v1']; else store['questa.sync.v1'] = v;
    return vm.runInNewContext('(' + probeLinked + ')()', { localStorage: { getItem: (k) => (k in store ? store[k] : null) }, JSON }); };
  assert.equal(probe(undefined), false);
  assert.equal(probe(JSON.stringify({ appKey: 'x' })), false);
  assert.equal(probe(JSON.stringify({ refreshToken: 'abc' })), true);
  assert.equal(probe('{broken'), false);
  assert.match(String(probeLinked), /return !!\(c && c\.refreshToken\)/); // a boolean only
  assert.throws(() => refuseLoginIfLinked(true, false), /refused: the test profile is already linked/);
  assert.doesNotThrow(() => refuseLoginIfLinked(true, true));
  assert.doesNotThrow(() => refuseLoginIfLinked(false, false));
  const win = {}; const removed = [];
  vm.runInNewContext('(' + disableForcePush + ')()', { window: win, alert: () => {}, localStorage: { removeItem: (k) => removed.push(k) } });
  for (const f of ['confirmConnectForForcePush', 'confirmForcePush', 'confirmEventsForcePush']) assert.equal(typeof win[f], 'function', f);
  assert.deepEqual(removed, ['questa.sync.pendingForcePush']);
});

test('dropbox cookies are cleared after login and at the end of every run', () => {
  for (const d of ['.dropbox.com', 'www.dropbox.com', 'dropbox.com', 'api.dropboxapi.com']) assert.match(d, DROPBOX_COOKIE_DOMAIN, d);
  for (const d of ['localhost', 'notdropbox.com.evil', 'dropbox.com.evil.net', 'mydropbox.com']) assert.doesNotMatch(d, DROPBOX_COOKIE_DOMAIN, d);
  const src = readFileSync(new URL('lib/pwa.mjs', LIVE_DIR), 'utf8');
  const runOptiSrc = src.slice(src.indexOf('export async function runOpti'), src.indexOf('export const PROBE_PATH'));
  assert.match(runOptiSrc, /finally \{\s*await clearDropboxCookies\(ctx\);\s*await ctx\.close\(\);/);
  const loginSrc = src.slice(src.indexOf('export async function openForLogin'));
  assert.match(loginSrc, /await clearDropboxCookies\(ctx\);\s*await ctx\.close\(\);\s*return \{ linked: true \}/);
  assert.match(loginSrc, /refuseLoginIfLinked\(linked, allowLinked\)[\s\S]*page\.goto\(server\.url\);/, 'the gate runs before the PWA loads');
  assert.match(src, /serviceWorkers: 'block'/);
  const run = readFileSync(new URL('run_live.mjs', LIVE_DIR), 'utf8');
  assert.match(run, /openForLogin\(\{ allowLinked: o\.live \}\)/);
  assert.match(LOGIN_TEXT, /"Connect Dropbox"/); assert.match(LOGIN_TEXT, /Do NOT tap "Connect & Force Push"/);
});

test('scenario 9 xp: Opti total-xp formula across a level-up', () => {
  assert.equal(xpToLevel(1), 150); // round(0.25 + 10 + 139.75)
  assert.equal(charTotalXp({ lvl: 3, xp: 5 }), 5 + xpToLevel(1) + xpToLevel(2));
  assert.deepEqual(charFromTotalXp(charTotalXp({ lvl: 3, xp: 5 })), { lvl: 3, xp: 5 });
  // both sides start at lvl 1, xp 140; Kotlin gains 20 (-> lvl 2, xp 10), the PWA gains 6 (-> lvl 1, xp 146)
  const c0 = { lvl: 1, xp: 140 }, c1 = { lvl: 2, xp: 10 }, p0 = { lvl: 1, xp: 140 }, p1 = { lvl: 1, xp: 146 };
  const want = expectedCharXp(c0, c1, p0, p1);
  assert.deepEqual(want, { lvl: 2, xp: 16 });
  assert.equal(charXpMatches({ lvl: 2, xp: 16 }, want), true);
  assert.equal(charXpMatches({ lvl: 2, xp: 10 }, want), false); // LWW of the Kotlin side: caught now
  assert.equal(charXpMatches({ lvl: 1, xp: 166 }, want), false); // naive residual sum: caught
  const src = readFileSync(new URL('scenarios.mjs', LIVE_DIR), 'utf8');
  assert.doesNotMatch(src, /!lvlSame \|\|/);
});

test('scenario 9 xp formula matches Opti sync.js at the pinned commit', { skip: !haveOpti }, () => {
  const src = execFileSync('git', ['-C', DEFAULT_OPTI_ROOT, 'show', OPTI_REV + ':sync.js'], { encoding: 'utf8' });
  const a = src.indexOf('function _xpNeed('), b = src.indexOf('function _charCanonical(', a);
  const box = {}; vm.runInNewContext(src.slice(a, b) + '\nbox.t = _charTotalXp; box.f = _charFromTotalXp;', { box });
  for (const c of [{ lvl: 1, xp: 0 }, { lvl: 7, xp: 33.5 }, { lvl: 40, xp: 1 }, { lvl: '3', xp: '2' }, {}]) {
    assert.equal(charTotalXp(c), box.t(c));
    assert.deepEqual(charFromTotalXp(charTotalXp(c)), { ...box.f(box.t(c)) });
  }
});

test('scenario 10 is option B: no h2bco link anywhere in the harness', () => {
  for (const f of ['scenarios.mjs', 'run_live.mjs', 'lib/phone.mjs', 'lib/pwa.mjs']) {
    assert.doesNotMatch(readFileSync(new URL(f, LIVE_DIR), 'utf8'), /h2bco/i, f);
  }
  const s10 = SCENARIOS.find((s) => s.n === 10);
  assert.match(s10.title, /option B/);
  assert.ok(s10.user.some((u) => /Do NOT link Dropbox in the legacy app/.test(u)));
  const sheet = readFileSync(new URL('../../plans/stage8/WP-8.12_RUN_SHEET.md', LIVE_DIR), 'utf8');
  const s10Sheet = sheet.slice(sheet.indexOf('### Scenario 10'));
  assert.doesNotMatch(s10Sheet, /Tap \*\*Unlink\*\*|link Dropbox in the legacy app\. Log in/i);
});

test('scenario 17 judges the base the app actually stored', () => {
  const src = readFileSync(new URL('scenarios.mjs', LIVE_DIR), 'utf8');
  const s17 = src.slice(src.indexOf('n: 17,'));
  // Each recorded upload is judged against the base stored right before its step (never a fixed s0).
  assert.match(s17, /const baseBefore = revChain\[revChain\.length - 1\]/);
  assert.match(s17, /up\[0\]\.updateRev === up\[0\]\.baseBefore/);
  assert.match(s17, /next\.baseRev === sR\.baseRev && next\.lastRev === sR\.lastRev/);
  assert.doesNotMatch(s17, /updateRev === s0\.baseRev/);
  // The boot round runs before the recorder, so an empty upload list is judged by the rev chain.
  assert.match(s17, /revChain\.length >= 2 && revChain\[0\] === s0\.baseRev/);
});

test('in-page helpers never return or pass a token', () => {
  for (const [name, fn] of Object.entries(inPage)) {
    const src = fn.toString().replace('!!c.refreshToken', '');
    assert.doesNotMatch(src, /accessToken|refreshToken|syncToken|Authorization|localStorage/i, name);
  }
  assert.doesNotMatch(inPage.status.toString(), /return c\b|\.\.\.c\b/);
});

test('harness sources: no token handling, no Opti writes, profile under .build', () => {
  const files = ['lib/checks.mjs', 'lib/opti.mjs', 'lib/phone.mjs', 'run_live.mjs', 'scenarios.mjs', 'export_coverage.mjs'];
  for (const f of files) {
    const src = readFileSync(new URL(f, LIVE_DIR), 'utf8');
    assert.doesNotMatch(src, /syncToken\(|\.accessToken|\.refreshToken|Authorization:/, f);
    assert.doesNotMatch(src, /writeFileSync\([^)]*Opti|DEFAULT_OPTI_ROOT[^\n]*write/i, f);
  }
  assert.match(PROFILE_DIR.replace(/\\/g, '/'), /\/\.build\/live-pwa-profile$/);
  const phoneSrc = readFileSync(new URL('lib/phone.mjs', LIVE_DIR), 'utf8');
  assert.doesNotMatch(phoneSrc, /'install'|"install"|pm', 'install|uninstall/);
});

test('the Kotlin LiveInteropTest is gated and token-free', () => {
  const src = readFileSync(new URL('../../app/src/androidTest/java/com/example/data/sync/LiveInteropTest.kt', LIVE_DIR), 'utf8');
  assert.match(src, /assumeTrue\("live steps run only with -e live yes", args\.getString\("live"\) == "yes"\)/);
  assert.ok(src.indexOf('assumeTrue("live steps') < src.indexOf('AccountInterlock('), 'gate before anything else');
  assert.ok(src.indexOf('AccountInterlock(') < src.indexOf('run(app, step'), 'interlock before any step');
  const net = /NETWORK_STEPS = setOf\(([^)]*)\)/.exec(src)[1];
  for (const s of ['round', 'round-kill-after-claim', 'first-connect', 'force-push', 'force-pull', 'backup-due', 'disconnect']) {
    assert.match(net, new RegExp('"' + s + '"'), s + ' must pass the interlock first');
  }
  assert.doesNotMatch(src, /accessToken|refreshToken|TokenStore|activeAccessTokenChecked|header\("Authorization"\)/);
});

// ---- runner --------------------------------------------------------------------------------
test('parseArgs: --live is required for Dropbox work; parts are checked', () => {
  assert.equal(parseArgs(['--plan']).cmd, 'plan');
  assert.equal(parseArgs(['--plan', '7']).n, 7);
  assert.throws(() => parseArgs(['--scenario', '1']), /--live/);
  assert.throws(() => parseArgs(['--setup']), /--live/);
  assert.equal(parseArgs(['--live', '--scenario', '1']).n, 1);
  assert.throws(() => parseArgs(['--live', '--scenario', '8']), /--part a\|b/);
  assert.throws(() => parseArgs(['--live', '--scenario', '10', '--part', 'first-connect']), /--choice/);
  assert.equal(parseArgs(['--live', '--scenario', '10', '--part', 'first-connect', '--choice', 'push']).choice, 'push');
  assert.throws(() => parseArgs(['--live', '--scenario', '18']), /no scenario/);
  assert.throws(() => parseArgs([]), /nothing to do/);
});

test('17 scenarios, each with its Dropbox writes/deletes and a runner', () => {
  assert.deepEqual(SCENARIOS.map((s) => s.n), Array.from({ length: 17 }, (_, i) => i + 1));
  for (const s of SCENARIOS) {
    assert.ok(Array.isArray(s.writes) && Array.isArray(s.deletes) && Array.isArray(s.user), 's' + s.n);
    assert.equal(typeof s.run, 'function');
    for (const p of [...s.writes, ...s.deletes]) assert.doesNotMatch(p, /\/Apps\/(?!Questa-temp)|\.\.\//,'s' + s.n + ': ' + p);
  }
  const plan = planText();
  for (let n = 1; n <= 17; n++) assert.match(plan, new RegExp('Scenario ' + n + ':'));
  assert.match(plan, /inside Apps\/Questa-temp/);
});

test('test folder only holds tests', () => {
  assert.ok(readdirSync(HERE).every((f) => f.endsWith('.test.mjs')));
});

test('adb daemon error: start-server once, then one retry', () => {
  const DAEMON = ['* daemon not running; starting now at tcp:5037', 'could not read ok from ADB Server',
    '* failed to start daemon', 'adb.exe: cannot connect to daemon'].join('\r\n');
  assert.equal(isDaemonError(DAEMON), true);
  assert.equal(isDaemonError('error: no devices/emulators found'), false);
  const log = []; let n = 0;
  const exec = (cmd, args) => {
    log.push(args.join(' '));
    if (args[0] === 'start-server') return '';
    if (n++ === 0) { const e = new Error('Command failed'); e.stderr = DAEMON; throw e; }
    return JSON.stringify({ result: { ok: 1 } });
  };
  let spawned = 0;
  const spawn = () => (spawned++ === 0 ? { stdout: '', stderr: DAEMON } : { stdout: 'OK (1 test)\n', stderr: '' });
  const phone = makePhone({ exec, spawn, sleep: () => {} });
  const r = phone.step(1, 'round');
  assert.equal(r.status, 'ok');
  assert.equal(spawned, 2);
  assert.deepEqual(r.out, { result: { ok: 1 } });
  assert.equal(log.filter((l) => l === 'start-server').length, 2); // once for the step, once for the read
  // a non-daemon failure is not retried
  const once = makePhone({ exec: () => { const e = new Error('x'); e.stderr = 'error: device offline'; throw e; }, sleep: () => {}, readTries: 1 });
  assert.throws(() => once.devices(), /x/);
});

test('readOut retries a missing or partial output file, then gives up', () => {
  let n = 0; const waits = [];
  const exec = () => { n++; if (n === 1) throw new Error('No such file'); if (n === 2) return '{"resu'; return '{"result":5}'; };
  assert.deepEqual(makePhone({ exec, sleep: (ms) => waits.push(ms) }).readOut(1, 'round'), { result: 5 });
  assert.deepEqual(waits, [500, 500]);
  const never = makePhone({ exec: () => { throw new Error('No such file'); }, sleep: () => {}, readTries: 3 });
  assert.throws(() => never.readOut(1, 'round'), /No such file/);
});

test('sortById: same content in two task orders compares equal; content change does not', () => {
  const a = { tasks: [{ id: 'x', title: 'X' }, { id: 'a', title: 'A' }], rewards: [], tags: [], devices: [{ id: 'd' }] };
  const b = { tasks: [{ id: 'a', title: 'A' }, { id: 'x', title: 'X' }], rewards: [], tags: [], devices: [{ id: 'd' }] };
  assert.notEqual(stableStringify(a), stableStringify(b));
  assert.equal(stableById(a), stableById(b));
  assert.notEqual(stableById(a), stableById({ ...b, tasks: [{ id: 'a', title: 'A' }, { id: 'x', title: 'Y' }] }));
  assert.deepEqual(a.tasks.map((t) => t.id), ['x', 'a']); // input untouched
  assert.deepEqual(sortById(a).tasks.map((t) => t.id), ['a', 'x']);
});

test('scenario 1 reads the Kotlin rev from the sync_base row, not DROPBOX_LAST_REV', () => {
  const src = readFileSync(new URL('scenarios.mjs', LIVE_DIR), 'utf8');
  const s1 = src.slice(src.indexOf('n: 1,'), src.indexOf('n: 2,'));
  assert.match(s1, /ks\.baseRev === rev0/);
  assert.doesNotMatch(s1, /ks\.lastRev/);
});

test('idOrderDiff / stableAllById: same content in two orders', () => {
  const a = { tasks: [{ id: 'x' }, { id: 'a' }], devices: [{ id: 'd1' }, { id: 'd2' }], an: { views: [{ id: 'v' }], metrics: [] } };
  const b = { tasks: [{ id: 'a' }, { id: 'x' }], devices: [{ id: 'd2' }, { id: 'd1' }], an: { views: [{ id: 'v' }], metrics: [] } };
  assert.deepEqual(idOrderDiff(a, b), ['tasks', 'devices']);
  assert.deepEqual(idOrderDiff(a, a), []);
  assert.equal(stableAllById(a), stableAllById(b));
  assert.notEqual(stableAllById(a), stableAllById({ ...b, tasks: [{ id: 'a' }] }));
  assert.deepEqual(a.tasks.map((t) => t.id), ['x', 'a']); // input untouched
});

test('scenario 1 keeps the strict rev rule only when both devices hold the same order', () => {
  const src = readFileSync(new URL('scenarios.mjs', LIVE_DIR), 'utf8');
  const s1 = src.slice(src.indexOf('n: 1,'), src.indexOf('n: 2,'));
  assert.match(s1, /const orderDiff = idOrderDiff\(kSub\(ctx\), \(await p\.subset\(\)\)\.subset\);/);
  assert.match(s1, /if \(!orderDiff\.length\) \{\s*ctx\.check\('rev unchanged over 20 rounds'/);
  assert.match(s1, /\[DEFERRED_OPEN_QUESTION\]/);
});

// Opti's own merge + nothingToPush (the real sync.js at the pinned commit, fake Dropbox): two
// devices with the same content in two task orders re-upload on every idle round.
test('Opti 7d5d4f3: same content in two orders re-uploads every idle round (legacy)', { skip: !haveOpti }, async () => {
  const show = (f) => execFileSync('git', ['-C', DEFAULT_OPTI_ROOT, 'show', OPTI_REV + ':' + f], { encoding: 'utf8', maxBuffer: 64 << 20 });
  const syncJs = show('sync.js'); const appJs = show('app.js');
  const fnOf = (src, head) => { const i = src.indexOf(head); const j = src.indexOf('\n}', i); return src.slice(i, j + 2); };
  const appFns = ['function dayStamp(', 'function ratchetHlc('].map((h) => fnOf(appJs, h)).join('\n');
  const run = async (reorder) => {
    const shared = { remote: null, rev: 0, uploads: 0 };
    const device = (devId, tasks) => {
      const store = {}; let base = null;
      const box = vm.createContext({ console, setTimeout, clearTimeout, navigator: {}, crypto: globalThis.crypto, TextEncoder,
        localStorage: { getItem: (k) => (k in store ? store[k] : null), setItem: (k, v) => { store[k] = String(v); }, removeItem: (k) => { delete store[k]; } } });
      vm.runInContext('var S = null; var lastIssued = 0; const HLC_RATCHET_TOLERANCE_MS = 120000;\n' + appFns
        + '\nfunction logEvent(){} function save(){} function render(){}\n' + syncJs.slice(0, syncJs.indexOf('/* BEGIN_BOOT_GATE */')), box);
      box.__f = {
        dl: async () => shared.remote && JSON.parse(JSON.stringify(shared.remote)),
        up: async (p, w) => { shared.uploads++; const rev = 'r' + (++shared.rev); shared.remote = { state: JSON.parse(JSON.stringify(w.state)), savedAt: w.savedAt, deviceId: w.deviceId, rev }; return { rev }; },
        bg: async () => base && { base: JSON.parse(base.j), lastRev: base.r }, bp: async (j, r) => { base = { j, r }; return true; },
      };
      box.__S = { char: { name: 'c', lvl: 3, xp: 10, gold: 1, hp: 50, maxHp: 50, updatedAt: 1000 }, tasks, rewards: [], tags: [], devices: [], lastCron: 0,
        history: [], charHistory: [], monthlyBackups: [], deletions: [], prefs: {} };
      box.__id = devId;
      vm.runInContext(`S = __S; dbxDownload = () => __f.dl(); dbxUpload = (p, w, r) => __f.up(p, w, r);
        syncBaseGet = () => __f.bg(); syncBasePut = (j, r) => __f.bp(j, r);
        syncInboxConsume = async () => []; syncInboxFinish = async () => {}; syncDeviceId = () => __id;`, box);
      return () => vm.runInContext('_syncNowAttempt(0)', box);
    };
    const mk = (id) => ({ id, type: 'todo', title: 'T-' + id, updatedAt: 1000, createdAt: 1000 });
    const A = device('devAAAAAAAA', [mk('t1'), mk('t2'), mk('t3')]);
    const B = device('devBBBBBBBB', reorder ? [mk('t2'), mk('t3'), mk('t1')] : [mk('t1'), mk('t2'), mk('t3')]);
    await A(); await B(); await A(); await B(); // seed + settle
    const u0 = shared.uploads;
    for (let i = 0; i < 5; i++) { await B(); await A(); }
    return shared.uploads - u0;
  };
  assert.equal(await run(false), 0, 'same order: idle rounds never upload');
  assert.ok(await run(true) >= 9, 'two orders: (nearly) every idle round uploads (Opti keeps LOCAL order; nothingToPush does not sort arrays)');
});

// ---- WP-8.12-T1: --fake-day (debug app clock offset on the phone) ------------------------------
test('fakeDayOffsetMs: next local midnight + 5 min, plus N-1 days', () => {
  const now = new Date(2026, 8, 25, 15, 0, 0, 0).getTime();
  assert.equal(now + fakeDayOffsetMs(now, 1), new Date(2026, 8, 26, 0, 5, 0, 0).getTime());
  assert.equal(now + fakeDayOffsetMs(now, 2), new Date(2026, 8, 27, 0, 5, 0, 0).getTime());
  const late = new Date(2026, 8, 25, 23, 59, 0, 0).getTime();
  assert.equal(late + fakeDayOffsetMs(late, 1), new Date(2026, 8, 26, 0, 5, 0, 0).getTime());
  assert.throws(() => fakeDayOffsetMs(now, 0), /fake-day/);
});

test('parseArgs: --fake-day N (scenario only, N >= 1)', () => {
  assert.equal(parseArgs(['--live', '--scenario', '8', '--part', 'b']).fakeDay, 0);
  assert.equal(parseArgs(['--live', '--scenario', '8', '--part', 'b', '--fake-day', '1']).fakeDay, 1);
  assert.throws(() => parseArgs(['--live', '--scenario', '8', '--part', 'b', '--fake-day', '0']), /--fake-day/);
  assert.throws(() => parseArgs(['--live', '--scenario', '8', '--part', 'b', '--fake-day', 'x']), /--fake-day/);
  assert.throws(() => parseArgs(['--live', '--setup', '--fake-day', '1']), /--fake-day/);
});

test('phone clock offset: exact am broadcast args (stopped packages included), read back after every set', () => {
  assert.deepEqual(clockOffsetArgs(86400000), ['shell', 'am', 'broadcast', '-a', PKG + '.DEBUG_SET_CLOCK_OFFSET', '--el', 'offsetMs', '86400000', '--include-stopped-packages', '-n', CLOCK_RECEIVER]);
  assert.deepEqual(clockOffsetArgs(null), ['shell', 'am', 'broadcast', '-a', PKG + '.DEBUG_GET_CLOCK_OFFSET', '--include-stopped-packages', '-n', CLOCK_RECEIVER]);
  assert.throws(() => clockOffsetArgs(1.5), /integer/);
  // Derived from PKG, like the two assertions above. The literal `com.example.dev` here predated
  // DEC-6, which moved the applicationId to io.github.palo007.questa.dev; it went stale while
  // the assertions built from PKG stayed correct.
  const pkgRe = PKG.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  assert.match(CLEAR_CLOCK_CMD, new RegExp('^adb shell am broadcast -a ' + pkgRe + '\\.DEBUG_SET_CLOCK_OFFSET --el offsetMs 0 --include-stopped-packages -n '));
  // a stateful fake app: SET stores, GET reports
  let stored = 7; const log = [];
  const reply = (v) => 'Broadcasting: Intent { }\nBroadcast completed: result=1, data="offsetMs=' + v + '"\n';
  const exec = (cmd, args) => { log.push(args.includes('--el') ? 'set' : 'get'); if (args.includes('--el')) stored = Number(args[args.indexOf('--el') + 2]); return reply(stored); };
  const phone = makePhone({ exec, sleep: () => {} });
  assert.equal(phone.getClockOffset(), 7);
  log.length = 0;
  assert.equal(phone.setClockOffset(123), 123);
  assert.deepEqual(log, ['set', 'get'], 'every set is read back with GET');
  assert.equal(phone.setClockOffset(0), 0);
  // the set replies ok but the read-back disagrees -> fail
  const liar = makePhone({ exec: (c, args) => reply(args.includes('--el') ? 5 : 99), sleep: () => {} });
  assert.throws(() => liar.setClockOffset(5), /reads back 99/);
  const bad = makePhone({ exec: () => 'Broadcast completed: result=0\n', sleep: () => {} });
  assert.throws(() => bad.setClockOffset(5), /clock offset/);
});

const fakePhone = (over = {}) => {
  const calls = [];
  return { calls, phone: {
    setClockOffset: (ms) => { calls.push('set ' + ms); if (over.set) over.set(ms); return ms; },
    forceStop: () => calls.push('stop'), launch: () => calls.push('launch'),
  } };
};
const fakeProc = () => {
  const h = {}; const exits = [];
  return { h, exits, once: (s, f) => { h[s] = f; }, removeListener: (s, f) => { if (h[s] === f) delete h[s]; }, exit: (c) => exits.push(c) };
};

test('runWithFakeDay: sets, relaunches, runs, and ALWAYS clears (also on a throw)', async () => {
  const a = fakePhone(); const pa = fakeProc();
  const r = await runWithFakeDay({ phone: a.phone, offsetMs: 42, proc: pa, sleep: async () => a.calls.push('wait'), fn: async () => { a.calls.push('run'); return 'ok'; } });
  assert.equal(r, 'ok');
  assert.deepEqual(a.calls, ['set 42', 'stop', 'launch', 'wait', 'run', 'set 0', 'stop']);
  assert.deepEqual(Object.keys(pa.h), [], 'signal handlers removed at the end');
  const b = fakePhone();
  await assert.rejects(runWithFakeDay({ phone: b.phone, offsetMs: 42, proc: fakeProc(), sleep: async () => {}, fn: async () => { throw new Error('boom'); } }), /boom/);
  assert.deepEqual(b.calls.slice(-2), ['set 0', 'stop']);
  // the first set throws -> still cleared
  let first = true;
  const c = fakePhone({ set: () => { if (first) { first = false; throw new Error('no app'); } } });
  await assert.rejects(runWithFakeDay({ phone: c.phone, offsetMs: 42, proc: fakeProc(), sleep: async () => {}, fn: async () => 'x' }), /no app/);
  assert.deepEqual(c.calls, ['set 42', 'set 0', 'stop']);
});

test('runWithFakeDay: a failed clear keeps the scenario error visible too (review F6)', async () => {
  const d = fakePhone({ set: (ms) => { if (ms === 0) throw new Error('clear broke'); } });
  await assert.rejects(runWithFakeDay({ phone: d.phone, offsetMs: 42, proc: fakeProc(), sleep: async () => {}, fn: async () => { throw new Error('scenario broke'); } }),
    (e) => /scenario broke/.test(e.message) && /clear broke/.test(e.message) && e.message.includes(CLEAR_CLOCK_CMD));
  assert.equal(d.calls.at(-1), 'stop', 'force-stop still runs');
  const e2 = fakePhone({ set: (ms) => { if (ms === 0) throw new Error('clear broke'); } });
  await assert.rejects(runWithFakeDay({ phone: e2.phone, offsetMs: 42, proc: fakeProc(), sleep: async () => {}, fn: async () => 'ok' }), /clear broke/);
});

test('runWithFakeDay: SIGINT / SIGTERM clear the offset, force-stop and exit 130 (review F2)', async () => {
  for (const sig of ['SIGINT', 'SIGTERM']) {
    const a = fakePhone(); const p = fakeProc();
    await runWithFakeDay({ phone: a.phone, offsetMs: 42, proc: p, sleep: async () => {}, fn: async () => {
      assert.equal(typeof p.h.SIGINT, 'function'); assert.equal(typeof p.h.SIGTERM, 'function');
      p.h[sig]();
      assert.deepEqual(a.calls.slice(-2), ['set 0', 'stop']);
      assert.deepEqual(p.exits, [130]);
    } });
  }
  // registered BEFORE the first set
  const b = fakePhone(); const pb = fakeProc(); let registered = null;
  b.phone.setClockOffset = (ms) => { if (registered === null) registered = typeof pb.h.SIGINT === 'function'; return ms; };
  await runWithFakeDay({ phone: b.phone, offsetMs: 42, proc: pb, sleep: async () => {}, fn: async () => {} });
  assert.equal(registered, true);
});

test('fake-day future window: saved until real end + offset; a later normal run only warns (review F4)', () => {
  assert.equal(futureUntilMs(null, 1000, 500), 1500);
  assert.equal(futureUntilMs({ futureUntilMs: 9000 }, 1000, 500), 9000, 'never shrinks');
  assert.equal(fakeDayWarning(1000, null), null);
  assert.equal(fakeDayWarning(2000, { futureUntilMs: 1500 }), null);
  assert.match(fakeDayWarning(1000, { futureUntilMs: 1500 }), /WARNING.*future/);
});
