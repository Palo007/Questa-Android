#!/usr/bin/env node
// tools/parity_check.mjs — KT-05 (fix plan packet P12): ONE parity-check command.
//
// Steps (fix_design.md P12; Momus 24, 29-31; DEC-15 = this tool runs NO Gradle):
//   1. Kotlin export precheck: --kotlin-export (default <repo>/app/build/kotlin_torture_export.json,
//      written by HashPreimageGoldenTest.writesKotlinTortureExport in every full unit-test run).
//      Missing, or mtime older than the HEAD commit time -> exit 2 "run the full suite first".
//   2. `git worktree add --detach <tmp> HEAD` (tmp under os.tmpdir(), outside the repo); junction
//      <tmp>/tools/node_modules -> <repo>/tools/node_modules when that exists.
//   3. Run every generator inside <tmp> (they write under dirname(script)/.., i.e. <tmp>).
//   4. Drift = `git -C <tmp> status --porcelain --untracked-files=all` over the WHOLE tree (no path
//      filter: fixtures/**, app/src/test/resources/events/evt_vectors.json, any new file).
//      Each entry carries the file's sha256. Gate: every entry must be in tools/parity_allow.json
//      (path match; if the entry pins sha256, the hash must match too).
//      First run (no baseline file): the drift list is written to the baseline file and the drift
//      step is NOT gated on that run (day-one baseline). Known drift then goes into the allow-list.
//   5. `git worktree remove --force <tmp>` + `git worktree prune` on EVERY path (try/finally).
//      The real tree is never written.
//   6. verify_with_pwa.mjs <export> (hash) and verify_with_pwa.mjs --import <export>.
//   7. `git -C <opti> diff --stat 7d5d4f3 HEAD -- sync.js app.js` printed; non-empty = FAIL.
//
// Exit: 0 all pass; 1 parity FAIL (drift / verify / Opti moved); 2 Kotlin export missing/stale;
//       3 tool error.
//
// Usage:
//   node tools/parity_check.mjs [--kotlin-export <path>] [--baseline <path>] [--opti-root <dir>]
//   node tools/parity_check.mjs --self-test [--opti-root <dir>]
//   (npm --prefix tools run parity-check)

'use strict';

import { execFileSync, spawnSync } from 'node:child_process';
import { existsSync, lstatSync, mkdirSync, mkdtempSync, readFileSync, rmdirSync, rmSync, statSync, symlinkSync, unlinkSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import os from 'node:os';

const __dirname = dirname(fileURLToPath(import.meta.url));
const REPO = resolve(__dirname, '..');
// The original PWA checkout these fixtures are verified against:
//   https://github.com/Palo007/Questa  (pinned to TARGET_OPTI_GIT)
// It is a separate repository and is NOT vendored here. Resolve it from $OPTI_ROOT, else look
// for a sibling checkout named `Opti`. Previously this was a hardcoded absolute path under one
// maintainer's home directory, so it resolved for exactly one person on one machine.
// Where to find the original PWA checkout. The caller validates it: a missing checkout is
// reported as EXIT_NEED_SUITE with an explanation rather than crashing on a bad path.
function defaultOptiRoot() {
  if (process.env.OPTI_ROOT) return process.env.OPTI_ROOT;
  return join(dirname(REPO), 'Opti');
}
const TARGET_OPTI_GIT = '7d5d4f3'; // must match gen_opti_fixtures.mjs / verify_with_pwa.mjs
// Written outside the repo by default: a generated baseline is a local artifact, and the
// original hardcoded path pointed into a separate private analysis repository.
const DEFAULT_BASELINE = join(os.tmpdir(), 'questa-parity-baseline.md');
const ALLOW_PATH = join(__dirname, 'parity_allow.json');
const EXIT_OK = 0, EXIT_FAIL = 1, EXIT_NEED_SUITE = 2, EXIT_TOOL = 3;

const GENERATORS = ['gen_opti_fixtures.mjs', 'gen_golden_fixtures.mjs', 'gen_evt_vectors.mjs', 'gen_wp88_inbox_golden.mjs'];
const OPTIONAL_GENERATORS = ['gen_kt51_fixture.mjs', 'gen_kt38_fixture.mjs'];

function parseArgs(argv) {
  const out = {
    optiRoot: defaultOptiRoot(),
    selfTest: false,
    kotlinExport: join(REPO, 'app', 'build', 'kotlin_torture_export.json'),
    baseline: process.env.PARITY_BASELINE || DEFAULT_BASELINE,
  };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--self-test') out.selfTest = true;
    else if (a === '--opti-root') out.optiRoot = argv[++i];
    else if (a === '--kotlin-export') out.kotlinExport = resolve(argv[++i]);
    else if (a === '--baseline') out.baseline = resolve(argv[++i]);
    else throw new Error('unknown argument: ' + a);
  }
  return out;
}

function git(args) {
  return execFileSync('git', args, { encoding: 'utf8', maxBuffer: 256 << 20, stdio: ['ignore', 'pipe', 'pipe'] });
}
const sha256 = (buf) => createHash('sha256').update(buf).digest('hex');

// Temp worktree of HEAD outside the repo. Removed (then `git worktree prune`) on EVERY path.
function withTempWorktree(fn) {
  const parent = mkdtempSync(join(os.tmpdir(), 'questa-parity-'));
  const tmp = join(parent, 'wt');
  let added = false;
  let junction = null;
  try {
    git(['-C', REPO, 'worktree', 'add', '--detach', tmp, 'HEAD']);
    added = true;
    const realNm = join(REPO, 'tools', 'node_modules');
    if (existsSync(realNm)) {
      junction = join(tmp, 'tools', 'node_modules');
      symlinkSync(realNm, junction, 'junction');
    }
    return fn(tmp);
  } finally {
    let junctionGone = true;
    if (junction) {
      try { rmdirSync(junction); } catch { try { unlinkSync(junction); } catch { /* checked below */ } }
      try { lstatSync(junction); junctionGone = false; } catch { junctionGone = true; }
    }
    if (added && junctionGone) {
      try { git(['-C', REPO, 'worktree', 'remove', '--force', tmp]); } catch (e) { console.error('parity_check: worktree remove failed: ' + e.message); }
    } else if (added) {
      console.error('parity_check: junction ' + junction + ' could not be removed; temp worktree left at ' + tmp + ' (remove the junction by hand, then `git worktree prune`)');
    }
    try { git(['-C', REPO, 'worktree', 'prune']); } catch (e) { console.error('parity_check: worktree prune failed: ' + e.message); }
    if (junctionGone) { try { rmSync(parent, { recursive: true, force: true }); } catch { /* temp dir */ } }
  }
}

function runGenerators(tmp, optiRoot) {
  for (const g of [...GENERATORS, ...OPTIONAL_GENERATORS]) {
    const p = join(tmp, 'tools', g);
    if (!existsSync(p)) {
      if (OPTIONAL_GENERATORS.includes(g)) continue;
      throw new Error('generator missing: tools/' + g);
    }
    const args = g === 'gen_opti_fixtures.mjs' ? ['--opti-root', optiRoot]
      : (g === 'gen_evt_vectors.mjs' || g === 'gen_wp88_inbox_golden.mjs') ? [optiRoot] : [];
    const r = spawnSync(process.execPath, [p, ...args], {
      cwd: tmp, encoding: 'utf8', maxBuffer: 256 << 20, stdio: ['ignore', 'pipe', 'pipe'],
      env: { ...process.env, OPTI_ROOT: optiRoot },
    });
    if (r.status !== 0) throw new Error(`generator ${g} exit ${r.status}: ${(r.stderr || String(r.error || '')).slice(-2000)}`);
    console.log('  ran tools/' + g);
  }
}

// Drift list = WHOLE temp tree, no path filter. Each entry carries the content hash, so a new
// change to an already-drifting file is still a new entry.
// DEC-21 finding: with core.autocrlf=true (Git for Windows system default) a tracked file that a
// generator rewrites with LF endings (checkout had CRLF) is listed " M" by `git status` even though
// its cleaned blob equals HEAD (`git diff` is empty). Such a line-ending-only entry is not drift:
// drop a " M" entry when `git hash-object <path>` (clean filter applied) == the HEAD blob id.
function isLineEndingOnly(tmp, st, path) {
  if (st !== ' M') return false;
  try {
    const work = git(['-C', tmp, 'hash-object', '--', path]).trim();
    const head = git(['-C', tmp, 'rev-parse', 'HEAD:' + path]).trim();
    return work === head;
  } catch { return false; }
}

function driftOf(tmp) {
  const out = git(['-C', tmp, 'status', '--porcelain', '-z', '--untracked-files=all']);
  return out.split('\0').filter(Boolean).filter((rec) => !isLineEndingOnly(tmp, rec.slice(0, 2), rec.slice(3))).map((rec) => {
    const st = rec.slice(0, 2);
    const path = rec.slice(3);
    const f = join(tmp, path);
    const sha = existsSync(f) ? sha256(readFileSync(f)) : '-';
    return { st, path, sha };
  }).sort((a, b) => (a.path < b.path ? -1 : a.path > b.path ? 1 : 0));
}

const driftKey = (e) => `${e.st} ${e.path} ${e.sha}`;

// Self-test gate: 1 when any drift entry (path + status + content hash) is not in the baseline.
function driftGate(drift, baseline) {
  const known = new Set(baseline.map(driftKey));
  return drift.some((e) => !known.has(driftKey(e))) ? 1 : 0;
}

function loadAllow() {
  const j = JSON.parse(readFileSync(ALLOW_PATH, 'utf8'));
  if (!Array.isArray(j.allow)) throw new Error('tools/parity_allow.json: "allow" must be an array');
  for (const a of j.allow) {
    if (!a.path || !a.reason) throw new Error('tools/parity_allow.json: each entry needs "path" and "reason": ' + JSON.stringify(a));
  }
  return j.allow;
}

// Real gate: entries not covered by the allow-list.
function notAllowed(drift, allow) {
  return drift.filter((e) => {
    const a = allow.find((x) => x.path === e.path);
    return !a || (a.sha256 && a.sha256 !== e.sha);
  });
}

function writeBaseline(path, drift, head) {
  mkdirSync(dirname(path), { recursive: true });
  const lines = [
    '# P12 parity_check day-one drift baseline',
    '',
    `- Recorded: ${new Date().toISOString()} by tools/parity_check.mjs`,
    `- Questa HEAD: ${head}; Opti pin: ${TARGET_OPTI_GIT}`,
    `- Drift entries: ${drift.length} (format: porcelain-XY path sha256)`,
    '- Next: add each KNOWN drift path to tools/parity_allow.json with one reason each; then the gate is live.',
    '',
    '```',
    ...drift.map(driftKey),
    '```',
    '',
  ];
  writeFileSync(path, lines.join('\n'), 'utf8');
}

function checkKotlinExport(path) {
  if (!existsSync(path)) return `Kotlin export missing: ${path}`;
  const headMs = Number(git(['-C', REPO, 'log', '-1', '--format=%ct', 'HEAD']).trim()) * 1000;
  const mtime = statSync(path).mtimeMs;
  if (mtime < headMs) return `Kotlin export stale: ${path} (mtime ${new Date(mtime).toISOString()} < HEAD commit ${new Date(headMs).toISOString()})`;
  return null;
}

function runVerify(extraArgs, exportPath, optiRoot) {
  const r = spawnSync(process.execPath, [join(__dirname, 'verify_with_pwa.mjs'), ...extraArgs, '--opti-root', optiRoot, exportPath], {
    encoding: 'utf8', maxBuffer: 256 << 20, stdio: ['ignore', 'pipe', 'pipe'],
  });
  const tail = ((r.stdout || '') + (r.stderr || '')).trim().split('\n').slice(-3).join('\n    ');
  return { ok: r.status === 0, status: r.status, tail };
}

const SELF_TEST_PLANTS = [
  { name: 'plant 1 byte in generator output fixtures/golden_state.json', path: 'fixtures/golden_state.json', want: 1 },
  { name: 'plant 1 byte in app/src/test/resources/events/evt_vectors.json', path: 'app/src/test/resources/events/evt_vectors.json', want: 1 },
  { name: 'no plant (drift == in-run baseline)', path: null, want: 0 },
];

function selfTest(opts) {
  const realBefore = git(['-C', REPO, 'status', '--porcelain', '--untracked-files=all']);
  let ok = true;
  withTempWorktree((tmp) => {
    runGenerators(tmp, opts.optiRoot);
    const d0 = driftOf(tmp);
    console.log(`  in-run baseline: ${d0.length} drift entr${d0.length === 1 ? 'y' : 'ies'}`);
    for (const c of SELF_TEST_PLANTS) {
      const f = c.path && join(tmp, c.path);
      let orig = null;
      if (f) {
        orig = readFileSync(f);
        const b = Buffer.from(orig);
        b[0] ^= 0x01;
        writeFileSync(f, b);
      }
      let got;
      try { got = driftGate(driftOf(tmp), d0); } finally { if (f) writeFileSync(f, orig); }
      const pass = got === c.want;
      if (!pass) ok = false;
      console.log(`  ${pass ? 'PASS' : 'FAIL'} ${c.name}: exit ${got}, want ${c.want}`);
    }
  });
  const realAfter = git(['-C', REPO, 'status', '--porcelain', '--untracked-files=all']);
  const clean = realBefore === realAfter;
  if (!clean) ok = false;
  console.log(`  ${clean ? 'PASS' : 'FAIL'} real tree not written`);
  console.log(ok ? 'parity_check self-test: PASS' : 'parity_check self-test: FAIL');
  return ok ? 0 : 1;
}

function main() {
  const opts = parseArgs(process.argv.slice(2));
  if (opts.selfTest) return selfTest(opts);

  // 0. The web-app checkout. Checked before anything shells out to it: without this the
  //    generators die with an opaque spawn error and a stack trace instead of a real message.
  if (!statSync(opts.optiRoot, { throwIfNoEntry: false })?.isDirectory()) {
    console.error('parity_check: no web-app checkout at ' + opts.optiRoot);
    console.error('parity_check: the original PWA lives in its own repo (https://github.com/Palo007/Questa)');
    console.error("parity_check: git clone https://github.com/Palo007/Questa.git ../Opti   (or set OPTI_ROOT)");
    console.error('parity_check: and check out the pinned commit: git -C ../Opti checkout ' + TARGET_OPTI_GIT);
    return EXIT_NEED_SUITE;
  }

  // 1. Kotlin export precheck (no Gradle here, DEC-15).
  const exportProblem = checkKotlinExport(opts.kotlinExport);
  if (exportProblem) {
    console.error('parity_check: ' + exportProblem);
    console.error('parity_check: run the full suite first (./gradlew testDebugUnitTest writes it via HashPreimageGoldenTest.writesKotlinTortureExport)');
    return EXIT_NEED_SUITE;
  }
  const allow = loadAllow();
  const head = git(['-C', REPO, 'rev-parse', '--short', 'HEAD']).trim();
  const failures = [];

  // 2-5. Regenerate in a temp worktree, drift over the whole tree.
  console.log('[drift] regenerating fixtures in a temp worktree of ' + head);
  const drift = withTempWorktree((tmp) => { runGenerators(tmp, opts.optiRoot); return driftOf(tmp); });
  console.log(`[drift] ${drift.length} entr${drift.length === 1 ? 'y' : 'ies'}`);
  for (const e of drift) console.log('    ' + driftKey(e));
  if (!existsSync(opts.baseline)) {
    writeBaseline(opts.baseline, drift, head);
    console.log('[drift] BASELINE RECORDED -> ' + opts.baseline + ' (drift not gated on this first run)');
  } else {
    const bad = notAllowed(drift, allow);
    if (bad.length) {
      failures.push(`drift: ${bad.length} entr${bad.length === 1 ? 'y' : 'ies'} not in tools/parity_allow.json`);
      for (const e of bad) console.log('    NOT ALLOWED ' + driftKey(e));
    } else {
      console.log('[drift] PASS (all drift allow-listed)');
    }
  }

  // 6. PWA hash + import check on the Kotlin export.
  for (const [label, extra] of [['verify_with_pwa hash', []], ['verify_with_pwa --import', ['--import']]]) {
    const r = runVerify(extra, opts.kotlinExport, opts.optiRoot);
    console.log(`[${label}] ${r.ok ? 'PASS' : 'FAIL (exit ' + r.status + ')'}\n    ${r.tail}`);
    if (!r.ok) failures.push(label);
  }

  // 7. Has Opti moved past the pin?
  const stat = git(['-C', opts.optiRoot, 'diff', '--stat', TARGET_OPTI_GIT, 'HEAD', '--', 'sync.js', 'app.js']).trim();
  if (stat) {
    console.log(`[opti] sync.js/app.js changed since ${TARGET_OPTI_GIT}:\n${stat}`);
    failures.push(`Opti sync.js/app.js moved past ${TARGET_OPTI_GIT}`);
  } else {
    console.log(`[opti] PASS (sync.js/app.js unchanged since ${TARGET_OPTI_GIT})`);
  }

  if (failures.length) {
    console.log('parity_check: FAIL\n  - ' + failures.join('\n  - '));
    return EXIT_FAIL;
  }
  console.log('parity_check: PASS');
  return EXIT_OK;
}

try {
  process.exitCode = main();
} catch (e) {
  console.error('parity_check: ' + (e && e.stack || e));
  process.exitCode = EXIT_TOOL;
}
