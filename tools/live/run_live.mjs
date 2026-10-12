#!/usr/bin/env node
// WP-8.12 live gate runner. See plans/stage8/WP-8.12_RUN_SHEET.md.
//
//   node tools/live/run_live.mjs --plan [N]                     print what scenario N does (no browser, no adb, no Dropbox)
//   node tools/live/run_live.mjs --login                        one-time: open the test PWA headed; YOU tap Connect Dropbox
//   node tools/live/run_live.mjs --live --setup                 first sync of both sides (Kotlin first connect = Pull)
//   node tools/live/run_live.mjs --live --scenario N [--part x] [--choice pull|push] [--serial S] [--fake-day N]
//
// --fake-day N (WP-8.12-T1, dev build only): runs the scenario with the phone app's debug clock
// offset set to 00:05 local, N days ahead (the phone system clock is not touched), relaunches the
// app first, shifts the PWA page clock by the same amount, and always clears the offset at the end.
//
// --live is required for anything that talks to Dropbox. No token is ever read by this process:
// the PWA side runs in the persistent profile (.build/live-pwa-profile) with the PWA's own stored
// auth, and the phone side runs inside Questa dev with its encrypted token store.
import { execFileSync, spawnSync } from 'node:child_process';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createInterface } from 'node:readline/promises';
import { liveId } from './lib/checks.mjs';
import { BUILD_DIR, REPO_ROOT } from './lib/opti.mjs';
import { CLEAR_CLOCK_CMD, makePhone, PKG } from './lib/phone.mjs';
import { openForLogin, runOpti } from './lib/pwa.mjs';
import { ROUND_DELETES, ROUND_WRITES, scenario, SCENARIOS } from './scenarios.mjs';

export const LOGIN_TEXT = [
  'A Chromium window opens with the test PWA.',
  'In it: Settings -> Dropbox -> tap the button "Connect Dropbox".',
  'Do NOT tap "Connect & Force Push" or any force-push button (they are disabled here anyway).',
  'Log in to Dropbox and tap "Allow". Do not click anything else.',
  'The window closes by itself when the PWA is linked and its first sync is done.',
].join('\n');

export function parseArgs(argv) {
  const o = { live: false };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    const val = () => { const v = argv[++i]; if (v === undefined) throw new Error(a + ' needs a value'); return v; };
    if (a === '--live') o.live = true;
    else if (a === '--login') o.cmd = 'login';
    else if (a === '--setup') o.cmd = 'setup';
    else if (a === '--plan') { o.cmd = 'plan'; if (/^\d+$/.test(argv[i + 1] || '')) o.n = Number(argv[++i]); }
    else if (a === '--scenario') { o.cmd = 'scenario'; o.n = Number(val()); }
    else if (a === '--part') o.part = val();
    else if (a === '--choice') o.choice = val();
    else if (a === '--serial') o.serial = val();
    else if (a === '--fake-day') { const v = val(); if (!/^[1-9]\d?$/.test(v)) throw new Error('--fake-day needs a whole number 1..99'); o.fakeDay = Number(v); }
    else throw new Error('unknown argument ' + a);
  }
  if (!o.cmd) throw new Error('nothing to do: use --plan, --login, --setup or --scenario N');
  if (o.fakeDay && o.cmd !== 'scenario') throw new Error('--fake-day only works with --scenario N');
  o.fakeDay = o.fakeDay || 0;
  if ((o.cmd === 'setup' || o.cmd === 'scenario') && !o.live) throw new Error('refused: add --live (this talks to your real Dropbox app folder, D30)');
  if (o.cmd === 'scenario') {
    const s = scenario(o.n);
    if (s.parts && !s.parts.includes(o.part)) throw new Error('scenario ' + o.n + ' needs --part ' + s.parts.join('|'));
    if (!s.parts && o.part) throw new Error('scenario ' + o.n + ' has no parts');
    if (o.n === 10 && o.part === 'first-connect' && !['pull', 'push'].includes(o.choice)) throw new Error('--choice pull|push is required');
  }
  return o;
}

export function planText(n) {
  const list = n ? [scenario(n)] : SCENARIOS;
  const lines = [];
  for (const s of list) {
    lines.push('Scenario ' + s.n + ': ' + s.title, '  Pass: ' + s.pass);
    if (s.parts) lines.push('  Parts: ' + s.parts.join(', '));
    lines.push('  Dropbox writes (inside Apps/Questa-temp):');
    for (const w of [...s.writes, ...ROUND_WRITES]) lines.push('    - ' + w);
    lines.push('  Dropbox deletes (inside Apps/Questa-temp):');
    for (const d of [...s.deletes, ...ROUND_DELETES]) lines.push('    - ' + d);
    if (s.user.length) { lines.push('  You do:'); for (const u of s.user) lines.push('    - ' + u); }
    lines.push('');
  }
  return lines.join('\n');
}

/**
 * WP-8.12-T1 `--fake-day N`: the offset (ms) that moves "now" to 00:05 local, N days ahead
 * (N = 1: just after the next local midnight). Local = this PC's zone; the phone must match.
 */
export function fakeDayOffsetMs(nowMs, n) {
  if (!Number.isInteger(n) || n < 1) throw new Error('--fake-day must be >= 1');
  const t = new Date(nowMs);
  t.setHours(24, 5, 0, 0); // next local midnight + 5 min
  t.setDate(t.getDate() + (n - 1)); // calendar days, so a DST change is still 00:05
  return t.getTime() - nowMs;
}

/** How long the relaunched app gets for its boot (BootGate start-of-day cron) before the part runs. */
export const FAKE_DAY_BOOT_WAIT_MS = 10000;

/**
 * Sets the phone's app clock offset, relaunches Questa dev (its boot runs the day change the same
 * way a real next-day open does), runs [fn], and ALWAYS clears the offset again (finally).
 */
export async function runWithFakeDay({ phone, offsetMs, fn, sleep = (ms) => new Promise((r) => setTimeout(r, ms)), proc = process }) {
  // Review F2: Ctrl-C / SIGTERM exit without unwinding try/finally, so clear from the handler
  // (setClockOffset is synchronous: execFileSync). Registered before the first set.
  const onSignal = () => {
    try { phone.setClockOffset(0); } catch (e) { console.error('clearing the clock offset failed: ' + (e && e.message) + '\nClear it by hand: ' + CLEAR_CLOCK_CMD); }
    try { phone.forceStop(); } catch { /* best effort */ }
    proc.exit(130);
  };
  proc.once('SIGINT', onSignal); proc.once('SIGTERM', onSignal);
  let runErr = null; let result;
  try {
    phone.setClockOffset(offsetMs);
    phone.forceStop();
    phone.launch();
    await sleep(FAKE_DAY_BOOT_WAIT_MS);
    result = await fn();
  } catch (e) { runErr = e; }
  // Review F6: a failed clear must not hide the scenario's own error (setClockOffset(0) reads the value back).
  let clearErr = null;
  try { phone.setClockOffset(0); } catch (e) { clearErr = e; }
  try { phone.forceStop(); } finally { proc.removeListener('SIGINT', onSignal); proc.removeListener('SIGTERM', onSignal); }
  if (clearErr) {
    const msg = 'clearing the clock offset failed: ' + (clearErr && clearErr.message) + '\nClear it by hand: ' + CLEAR_CLOCK_CMD;
    if (runErr) throw new Error('scenario failed: ' + (runErr && runErr.stack || runErr) + '\nALSO ' + msg, { cause: runErr });
    throw new Error(msg, { cause: clearErr });
  }
  if (runErr) throw runErr;
  return result;
}

/** Review F4: the harness state file that remembers until when Dropbox holds future-stamped data. */
export const FAKE_DAY_STATE = 'fake-day.json';

/** Real time until which a fake-day run's writes are in the future: its real end + the offset (never shrinks). */
export function futureUntilMs(prev, realEndMs, offsetMs) {
  return Math.max((prev && prev.futureUntilMs) || 0, realEndMs + offsetMs);
}

/** The warning for a normal (non-fake) run started inside that window, or null. Warns only, never refuses. */
export function fakeDayWarning(nowMs, state) {
  if (!state || !(nowMs < state.futureUntilMs)) return null;
  return 'WARNING: a --fake-day run left future-stamped data (state.json, tombstones, /events, PWA HLC) in the test Dropbox until '
    + new Date(state.futureUntilMs).toString() + '. Until then the HLC heals (hlcReset), remote ratchets are refused (clockSkew) '
    + 'and fake-day edits can lose last-writer-wins; checks may be flaky.';
}

function makeCtx(o) {
  const phone = makePhone({ serial: o.serial });
  const runDir = join(BUILD_DIR, 'live-runs', new Date().toISOString().replace(/[:.]/g, '-') + '-s' + (o.n || 'setup') + (o.part ? '-' + o.part : ''));
  const stateDir = join(BUILD_DIR, 'live-runs', 'state'); // cross-part files (scenario 8, 10)
  mkdirSync(runDir, { recursive: true }); mkdirSync(stateDir, { recursive: true });
  const checks = []; const notes = [];
  const ctx = {
    n: o.n, part: o.part, choice: o.choice, phone, runDir,
    // WP-8.12-T1: the --fake-day shift (0 = real time). The PWA page gets the same shift.
    fakeOffsetMs: o.fakeOffsetMs || 0,
    now: () => Date.now() + (o.fakeOffsetMs || 0),
    id: (prefix) => liveId(prefix, Date.now()),
    check(name, ok, detail) { checks.push({ name, ok: !!ok, detail: detail === undefined ? null : detail }); console.log((ok ? '  PASS ' : '  FAIL ') + name); },
    note(t) { notes.push(t); console.log('  note: ' + t); },
    kRaw: (step, extra) => phone.step(o.n > 17 ? (scenario(o.n).phoneAs || 1) : (o.n || 1), step, extra),
    k(step, extra = {}) {
      const r = phone.step(o.n > 17 ? (scenario(o.n).phoneAs || 1) : (o.n || 1), step, extra);
      if (r.status !== 'ok') throw new Error('phone step ' + step + ' ' + r.status + (r.detail ? ':\n' + r.detail : ''));
      return r.out;
    },
    pwa: (opts, fn) => runOpti({ ...(o.fakeOffsetMs ? { clockOffsetMs: o.fakeOffsetMs } : {}), ...opts, script: fn }),
    saveText(name, text) { const f = join(runDir, name); writeFileSync(f, text); return f; },
    saveJson(name, obj) { const f = join(stateDir, name); writeFileSync(f, JSON.stringify(obj, null, 2)); return f; },
    loadJson(name) { return JSON.parse(readFileSync(join(stateDir, name), 'utf8')); },
    node(args) {
      const r = spawnSync(process.execPath, args, { cwd: REPO_ROOT, encoding: 'utf8' });
      return { code: r.status, tail: ((r.stdout || '') + (r.stderr || '')).trim().split(/\r?\n/).slice(-5).join('\n') };
    },
    async ask(q) { const rl = createInterface({ input: process.stdin, output: process.stdout }); await rl.question('\n>>> ' + q + '\n'); rl.close(); },
    async legacyCounts() {
      // The v6 DB only (no DataStore: the legacy DataStore holds plaintext legacy tokens, D30 rule).
      const dir = join(runDir, 'legacy-db'); mkdirSync(dir, { recursive: true });
      for (const suffix of ['', '-wal', '-shm']) {
        try {
          const buf = execFileSync('adb', [...(o.serial ? ['-s', o.serial] : []), 'exec-out', 'run-as', PKG, 'cat', 'databases/questa_database' + suffix], { maxBuffer: 256 << 20 });
          if (buf.length) writeFileSync(join(dir, 'questa_database' + suffix), buf);
        } catch { if (!suffix) throw new Error('could not read the legacy database via run-as'); }
      }
      const { DatabaseSync } = await import('node:sqlite');
      const db = new DatabaseSync(join(dir, 'questa_database'));
      const tables = db.prepare("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'room_%' AND name NOT LIKE 'android_%'").all().map((r) => r.name);
      const counts = {};
      for (const t of tables.sort()) counts[t] = Number(db.prepare('SELECT COUNT(*) AS c FROM `' + t + '`').get().c);
      db.close();
      return counts;
    },
  };
  ctx.finish = (extra = {}) => {
    const pass = checks.length > 0 && checks.every((c) => c.ok);
    writeFileSync(join(runDir, 'result.json'), JSON.stringify({ scenario: o.n || null, part: o.part || null, pass, checks, notes, ...extra }, null, 2));
    console.log((pass ? 'SCENARIO PASS' : 'SCENARIO FAIL') + ' -> ' + join(runDir, 'result.json'));
    return pass;
  };
  return ctx;
}

async function setup(ctx) {
  const s = ctx.k('status').result;
  ctx.check('Questa dev is LINKED', s.authState === 'LINKED', s.authState);
  if (s.authState !== 'LINKED') return;
  let r = ctx.k('round').result;
  if (/NeedsFirstConnectChoice/.test(r)) { ctx.note('first connect: Pull (take the Dropbox copy)'); r = ctx.k('first-connect', { choice: 'pull' }).result; }
  ctx.check('Kotlin round ok', !/Error|Interlock/.test(r), r);
  await ctx.pwa({}, async (p) => {
    const pr = await p.round();
    ctx.check('PWA round ok', !pr.lastError, pr);
    ctx.k('round');
  });
}

async function main() {
  const o = parseArgs(process.argv.slice(2));
  if (o.cmd === 'plan') { console.log(planText(o.n)); return 0; }
  if (o.cmd === 'login') {
    console.log(LOGIN_TEXT);
    const r = await openForLogin({ allowLinked: o.live });
    console.log(r.linked ? 'Linked. The dropbox.com cookies were cleared and the window closed.' : 'Not linked (window closed or timed out).');
    return r.linked ? 0 : 1;
  }
  if (o.fakeDay) o.fakeOffsetMs = fakeDayOffsetMs(Date.now(), o.fakeDay);
  const ctx = makeCtx(o);
  const readFakeState = () => { try { return ctx.loadJson(FAKE_DAY_STATE); } catch { return null; } };
  const saveFakeState = () => ctx.saveJson(FAKE_DAY_STATE, { futureUntilMs: futureUntilMs(readFakeState(), Date.now(), o.fakeOffsetMs) });
  if (!o.fakeDay) { const w = fakeDayWarning(Date.now(), readFakeState()); if (w) { console.warn(w); ctx.note(w); } }
  try {
    if (o.cmd === 'setup') await setup(ctx);
    else if (o.fakeDay) {
      console.log('If this run is interrupted and the offset stays set, clear it by hand (add -s <serial> if needed):\n  ' + CLEAR_CLOCK_CMD);
      // Review F5 (note only, not fixed): the offset is computed for PC-local 00:05, but the Kotlin
      // day key uses the phone's zone; a mismatch (or an empty zone) is only reported here.
      const pcZone = Intl.DateTimeFormat().resolvedOptions().timeZone; const phoneZone = ctx.phone.timezone();
      if (phoneZone !== pcZone) ctx.note('phone zone "' + phoneZone + '" differs from this PC (' + pcZone + '): the fake midnight is PC-local');
      ctx.note('fake day ' + o.fakeDay + ': app clock offset ' + o.fakeOffsetMs + ' ms (now reads ' + new Date(ctx.now()).toString() + ')');
      console.log('Scenario ' + o.n + ': ' + scenario(o.n).title);
      saveFakeState(); // before the run, so an interrupted run still leaves the warning window
      try {
        await runWithFakeDay({ phone: ctx.phone, offsetMs: o.fakeOffsetMs, fn: () => scenario(o.n).run(ctx) });
        ctx.note('app clock offset cleared (0, read back) and Questa dev stopped');
      } finally {
        saveFakeState();
        ctx.note('Dropbox data is future-stamped until ' + new Date(readFakeState()?.futureUntilMs || 0).toString() + ' (later normal runs warn until then)');
      }
    } else { console.log('Scenario ' + o.n + ': ' + scenario(o.n).title); await scenario(o.n).run(ctx); }
  } catch (e) {
    ctx.check('ran to the end', false, String(e && e.stack || e));
  }
  return ctx.finish() ? 0 : 1;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().then((c) => process.exit(c), (e) => { console.error(e.message || e); process.exit(2); });
}
