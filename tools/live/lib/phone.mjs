// WP-8.12: the phone side. Every Dropbox call on the phone is made by "Questa dev" itself
// (io.github.palo007.questa.dev), inside LiveInteropTest, with the tokens from the app's encrypted store.
// This file only runs adb: `am instrument` for one step, `run-as cat` to read the step's
// output file, `am force-stop` / `am start` / `am kill`. It never installs anything.
import { execFileSync, spawnSync } from 'node:child_process';

export const PKG = 'io.github.palo007.questa.dev';
export const RUNNER = 'io.github.palo007.questa.dev.test/androidx.test.runner.AndroidJUnitRunner';
export const TEST_CLASS = 'com.example.data.sync.LiveInteropTest';

/** The exact `am instrument` argument list for one step (unit-tested). */
export function instrumentArgs(scenario, step, extra = {}) {
  if (!Number.isInteger(scenario) || scenario < 1 || scenario > 17) throw new Error('scenario must be 1..17');
  if (!/^[a-z0-9-]+$/.test(step)) throw new Error('bad step name ' + step);
  const args = ['shell', 'am', 'instrument', '-w', '-e', 'live', 'yes', '-e', 'scenario', String(scenario), '-e', 'step', step];
  for (const [k, v] of Object.entries(extra)) {
    if (!/^[a-zA-Z][a-zA-Z0-9]*$/.test(k)) throw new Error('bad arg name ' + k);
    const s = String(v);
    if (!/^[A-Za-z0-9._:+-]{1,200}$/.test(s)) throw new Error('bad arg value for ' + k);
    args.push('-e', k, s);
  }
  args.push('-e', 'class', TEST_CLASS, RUNNER);
  return args;
}

/** WP-8.12-T1: the debug-only receiver that sets the app clock offset (dev build only). */
export const CLOCK_RECEIVER = PKG + '/com.example.debug.DebugClockReceiver';

/**
 * `am broadcast` args: set the offset (integer ms, 0 clears), or read it back when [offsetMs] is
 * null. `--include-stopped-packages`: after `am force-stop` the system drops even explicit
 * broadcasts to the app (FLAG_EXCLUDE_STOPPED_PACKAGES) -- review 40, F1.
 */
export function clockOffsetArgs(offsetMs) {
  if (offsetMs === null) return ['shell', 'am', 'broadcast', '-a', PKG + '.DEBUG_GET_CLOCK_OFFSET', '--include-stopped-packages', '-n', CLOCK_RECEIVER];
  if (!Number.isSafeInteger(offsetMs)) throw new Error('clock offset must be an integer of ms');
  return ['shell', 'am', 'broadcast', '-a', PKG + '.DEBUG_SET_CLOCK_OFFSET', '--el', 'offsetMs', String(offsetMs), '--include-stopped-packages', '-n', CLOCK_RECEIVER];
}

/** The manual recovery command (printed at the start of every --fake-day run). */
export const CLEAR_CLOCK_CMD = 'adb ' + clockOffsetArgs(0).join(' ');

/** Parses `Broadcast completed: result=1, data="offsetMs=N"`; throws on anything else. */
export function parseClockOffset(out) {
  const m = /Broadcast completed: result=1, data="offsetMs=(-?\d+)"/.exec(String(out || ''));
  if (!m) throw new Error('clock offset broadcast failed (is Questa dev installed?):\n' + String(out || '').slice(-500));
  return Number(m[1]);
}

/** Parses `am instrument -w` output: OK / FAILED / crashed. */
export function parseInstrumentOutput(out) {
  if (/Process crashed/i.test(out) || /INSTRUMENTATION_RESULT: shortMsg=Process crashed/.test(out)) return { status: 'crashed' };
  if (/^OK \(1 test\)/m.test(out)) return { status: 'ok' };
  if (/OK \(0 tests\)|assumption/i.test(out)) return { status: 'skipped' };
  const m = /FAILURES!!![\s\S]*/.exec(out) || /INSTRUMENTATION_FAILED[\s\S]*/.exec(out);
  return { status: 'failed', detail: m ? m[0].slice(0, 2000) : out.slice(-2000) };
}

/** True when adb output says the local adb server is down (the command never reached the phone). */
export function isDaemonError(text) {
  return /daemon not running|cannot connect to daemon|failed to start daemon|could not read ok from ADB Server/i.test(String(text || ''));
}

const sleepSync = (ms) => Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);

/**
 * [exec] / [spawn] / [sleep] are injectable for the offline tests. Every adb call that fails with a
 * daemon error runs `adb start-server` once and is retried once (a daemon error means the command
 * never reached the device, so the retry cannot run a step twice).
 */
export function makePhone({ serial, exec = execFileSync, spawn = spawnSync, sleep = sleepSync, readTries = 4, readDelayMs = 500 } = {}) {
  const base = serial ? ['-s', serial] : [];
  const startServer = () => { try { exec('adb', ['start-server'], { encoding: 'utf8' }); } catch { /* the retry reports it */ } };
  const adb = (args, opts = {}) => {
    const run = () => exec('adb', [...base, ...args], { encoding: 'utf8', maxBuffer: 64 << 20, ...opts });
    try { return run(); } catch (e) {
      if (!isDaemonError((e && e.stderr) + ' ' + (e && e.message))) throw e;
      startServer();
      return run();
    }
  };
  return {
    adb,
    devices: () => adb(['devices']),
    installed: () => adb(['shell', 'pm', 'list', 'packages']).split(/\r?\n/).filter((l) => /questa|com\.example/.test(l)),
    /** Runs one LiveInteropTest step; returns {status, out} (the JSON the step wrote, if any). */
    step(scenario, step, extra = {}) {
      const run = () => spawn('adb', [...base, ...instrumentArgs(scenario, step, extra)], { encoding: 'utf8', maxBuffer: 64 << 20 });
      let r = run();
      let text = (r.stdout || '') + (r.stderr || '');
      if (isDaemonError(text)) { startServer(); r = run(); text = (r.stdout || '') + (r.stderr || ''); }
      const parsed = parseInstrumentOutput(text);
      let out = null;
      if (parsed.status === 'ok') out = this.readOut(scenario, step);
      return { ...parsed, out };
    },
    /** Reads the step's output file; retried briefly (the file can lag the instrument exit). */
    readOut(scenario, step) {
      const name = 'files/live/s' + scenario + '-' + step + '.json';
      let last;
      for (let i = 0; i < readTries; i++) {
        if (i > 0) sleep(readDelayMs);
        try { return JSON.parse(adb(['exec-out', 'run-as', PKG, 'cat', name])); } catch (e) { last = e; }
      }
      throw last;
    },
    readFile(rel) {
      if (!/^files\/[A-Za-z0-9._/-]+$/.test(rel) || rel.includes('..')) throw new Error('bad path ' + rel);
      return adb(['exec-out', 'run-as', PKG, 'cat', rel]);
    },
    /** WP-8.12-T1: sets the dev app's clock offset, then reads it back with GET (review F1); throws on a mismatch. */
    setClockOffset(ms) {
      const got = parseClockOffset(adb(clockOffsetArgs(ms)));
      if (got !== ms) throw new Error('clock offset: asked ' + ms + ', app reports ' + got);
      const back = parseClockOffset(adb(clockOffsetArgs(null)));
      if (back !== ms) throw new Error('clock offset: asked ' + ms + ', app reads back ' + back);
      return back;
    },
    getClockOffset: () => parseClockOffset(adb(clockOffsetArgs(null))),
    /** The phone's zone id (persist.sys.timezone), for the fake-day midnight check. */
    timezone: () => adb(['shell', 'getprop', 'persist.sys.timezone']).trim(),
    forceStop: () => adb(['shell', 'am', 'force-stop', PKG]),
    kill: () => adb(['shell', 'am', 'kill', PKG]),
    launch: () => adb(['shell', 'am', 'start', '-n', PKG + '/com.example.MainActivity']),
    handlers: (scheme) => adb(['shell', 'pm', 'query-activities', '-a', 'android.intent.action.VIEW', '-d', scheme + '://oauth']),
  };
}
