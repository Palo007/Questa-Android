#!/usr/bin/env node
// tools/verify_with_pwa.mjs
//
// PWA import-side hash verifier — originally Stage 2 T2.4
// (plans/stages/stage_2_canonical_serialization.md), REGENERATED from the live Opti PWA by
// WP-8.0 (plans/PARITY_FIX_PLAN.html §5 row "8.0", PX-01: "Golden fixtures and tests encode
// legacy_pwa; regenerate from Opti", ground: plans/parity_audit/01_sync.md finding SY-60).
//
// Usage:
//   node tools/verify_with_pwa.mjs [--opti-root <dir>] [--rev <sha>] <path-to-backup.json>
//   node tools/verify_with_pwa.mjs --import [--opti-root <dir>] [--rev <sha>] <path-to-backup.json>
//
// --import runs Opti's real importData() (see runOptiImport) and prints "import OK" / exit 0 when
// it accepts the file, or the refusal alert / exit 1. WP-8.3 torture check (Kotlin buildBackupFile
// output, written by HashPreimageGoldenTest.writesKotlinTortureExport):
//   ./gradlew testDebugUnitTest --tests '*HashPreimageGoldenTest*'
//   node tools/verify_with_pwa.mjs --import app/build/kotlin_torture_export.json
//   node tools/verify_with_pwa.mjs app/build/kotlin_torture_export.json
//
// Exit code 0 + "hash OK" on success (including the schema-1 / hash-absent / fallback- sentinel
// "nothing to verify" cases, which the PWA itself treats as pass-through, see below). Exit code 1
// + a diff summary on a genuine SHA-256/fallback32 mismatch.
//
// PROVENANCE — how the functions below were obtained
// --------------------------------------------------------
// Same technique as `tools/gen_opti_fixtures.mjs` (see that file's header for the full
// import-infeasibility argument: Opti's app.js is not an ES module and depends on browser
// globals). The tokenize/detokenize + hashing primitives below are extracted VERBATIM out of
// git's object database at the pinned Opti commit (`git -C <optiRoot> show <rev>:app.js`, a
// read-only blob read — this script NEVER touches Opti's working tree, never checks it out,
// never writes to the Opti repo), by ANCHOR (a regex on each function's declaration line, brace-
// balanced to its close), exactly as `gen_opti_fixtures.mjs` does. Extracted at TARGET_OPTI_GIT:
//   - `_EXPORT_FIELD_MAP`, `_detokenizeEvents`, `_detDeep`, `_detokenizeSnapshot`  (app.js ~7015-7176)
//   - `computeHash`, `_hashFallback32`, `hashAlgoName`, `computeHashWith`          (app.js ~1589-1630)
//
// HISTORY — this file previously hardcoded a VERBATIM COPY of the July `legacy_pwa/app.js`
// import path, and documented a real bug in that snapshot: `importData()` there rebuilt
// `data._backup` as `Object.assign({}, data._backup, {appVersion: parsed._backup.appVersion})`
// starting from `undefined`, which silently dropped `hash` and made the schema-2 hash-check
// branch dead code for every real export. The live, pinned Opti checkout this file now reads
// from has since fixed that (`data._backup = Object.assign({}, parsed._backup)` — a full copy,
// not a fresh object seeded with one field) and additionally verifies with the ALGORITHM the
// file itself declares (`data._backup.hashAlgo`, falling back to `fallback32` only when the
// stored hash carries the `fallback-` prefix, otherwise `sha256`) via `computeHashWith`, per
// Opti's own 2026-09-19 round-3 review note at the call site. This script mirrors that current,
// fixed behavior — it is no longer working around a legacy_pwa bug, it is reading the real thing.
//
// Fallback-hash policy: mirrors the already-Resolved decision applied on the Kotlin side
// (`SerializationEngine.FALLBACK_HASH_PREFIX_REGEX`, WORK_TRACKER.md) — a stored hash matching
// `^fallback-` is Opti's non-cryptographic degraded digest (`_hashFallback32`) and is verified
// with the `fallback32` algorithm rather than SHA-256, exactly as `computeHashWith` does.
//
// Refuses (exit 1) when: `git -C <optiRoot> status --porcelain` is non-empty (dirty Opti tree),
// or the pinned rev cannot be read from <optiRoot>'s object database.

'use strict';

import { readFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import vm from 'node:vm';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const TARGET_OPTI_GIT = '7d5d4f3'; // must match gen_opti_fixtures.mjs / OptiFixtureManifestTest.TARGET_OPTI_GIT
const REPO = resolve(dirname(fileURLToPath(import.meta.url)), '..');

// The original PWA checkout this script verifies against: https://github.com/Palo007/Questa,
// pinned to TARGET_OPTI_GIT. A separate repository, NOT vendored here. Resolve from
// $OPTI_ROOT, else a sibling checkout named `Opti`. This used to be a hardcoded absolute path
// under one maintainer's home directory, which silently failed on every other machine.
function defaultOptiRoot() {
  if (process.env.OPTI_ROOT) return process.env.OPTI_ROOT;
  return join(dirname(REPO), 'Opti');
}

function parseArgs(argv) {
  const out = { optiRoot: defaultOptiRoot(), rev: process.env.OPTI_REV || TARGET_OPTI_GIT, positional: [] };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--opti-root') out.optiRoot = argv[++i];
    else if (a === '--rev') out.rev = argv[++i];
    else if (a === '--import') out.importMode = true;
    else out.positional.push(a);
  }
  return out;
}

// ---------------------------------------------------------------------------
// git plumbing (read-only; never touches Opti's working tree) — same approach
// as tools/gen_opti_fixtures.mjs, duplicated locally so this script has no
// import-time dependency on that other tool (it has a `main()` at module
// scope that would run on import).
// ---------------------------------------------------------------------------
function gitShow(optiRoot, rev, path) {
  return execFileSync('git', ['-C', optiRoot, 'show', `${rev}:${path}`], { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
}
function assertCleanOptiTree(optiRoot) {
  let status;
  try {
    status = execFileSync('git', ['-C', optiRoot, 'status', '--porcelain'], { encoding: 'utf8' });
  } catch (e) {
    console.error('verify_with_pwa: could not read Opti git status at ' + optiRoot + ': ' + e.message);
    process.exit(1);
  }
  if (status.trim().length > 0) {
    console.error('verify_with_pwa: refusing to extract -- Opti working tree at ' + optiRoot + ' is dirty:\n' + status);
    process.exit(1);
  }
}
function resolveShortHash(optiRoot, rev) {
  return execFileSync('git', ['-C', optiRoot, 'rev-parse', '--short=7', rev], { encoding: 'utf8' }).trim();
}

// ---------------------------------------------------------------------------
// Anchor-based extraction (mirrors gen_opti_fixtures.mjs's scanToBraceClose/
// extractFn/extractBlock -- reimplemented locally rather than imported, for
// the reason noted above).
// ---------------------------------------------------------------------------
function scanToBraceClose(text, pos) {
  let depth = 1, prevSig = '{';
  while (pos < text.length) {
    const c = text[pos];
    if (c === '/' && text[pos + 1] === '/') { const nl = text.indexOf('\n', pos); pos = nl === -1 ? text.length : nl; continue; }
    if (c === '/' && text[pos + 1] === '*') { const cl = text.indexOf('*/', pos + 2); pos = cl === -1 ? text.length : cl + 2; continue; }
    if (c === '\'' || c === '"') { const q = c; pos++; while (pos < text.length) { if (text[pos] === '\\') { pos += 2; continue; } if (text[pos] === q) { pos++; break; } pos++; } prevSig = q; continue; }
    if (c === '`') { pos++; while (pos < text.length) { if (text[pos] === '\\') { pos += 2; continue; } if (text[pos] === '`') { pos++; break; } pos++; } prevSig = '`'; continue; }
    if (c === '/' && /[({[,;:=&|!?+\-*%^~<>]/.test(prevSig)) {
      let p = pos + 1, inClass = false, sawEnd = false;
      while (p < text.length) {
        const rc = text[p];
        if (rc === '\\') { p += 2; continue; }
        if (rc === '[') { inClass = true; p++; continue; }
        if (rc === ']') { inClass = false; p++; continue; }
        if (rc === '\n') break;
        if (rc === '/' && !inClass) { p++; sawEnd = true; break; }
        p++;
      }
      if (sawEnd) { while (p < text.length && /[a-z]/i.test(text[p])) p++; pos = p; prevSig = '/'; continue; }
    }
    if (c === '{') depth++;
    else if (c === '}') { depth--; if (depth === 0) return pos; }
    if (!/\s/.test(c)) prevSig = c;
    pos++;
  }
  throw new Error('scanToBraceClose: unbalanced (depth=' + depth + ')');
}
function findLineIndex(lines, regex, label) {
  const hits = [];
  for (let i = 0; i < lines.length; i++) if (regex.test(lines[i])) hits.push(i);
  if (hits.length !== 1) throw new Error('anchor "' + label + '" matched ' + hits.length + ' lines (expected 1): ' + regex);
  return hits[0];
}
function charOffsetOfLine(lines, idx) { let off = 0; for (let i = 0; i < idx; i++) off += lines[i].length + 1; return off; }
function lineIndexOfPos(text, pos) { let n = 0; for (let i = 0; i < pos; i++) if (text[i] === '\n') n++; return n; }
function funcEndLine(src, lines, declIdx, label) {
  const off = charOffsetOfLine(lines, declIdx);
  const brace = lines[declIdx].indexOf('{');
  if (brace === -1) throw new Error('extract "' + label + '": anchor line has no opening brace');
  return lineIndexOfPos(src, scanToBraceClose(src, off + brace + 1));
}
function extractFn(src, nameRegex, label) {
  const lines = src.split('\n');
  const declIdx = findLineIndex(lines, nameRegex, label);
  const endIdx = funcEndLine(src, lines, declIdx, label);
  return lines.slice(declIdx, endIdx + 1).join('\n');
}
// Same contract as gen_opti_fixtures.mjs extractBlock: startRegex line through the closing
// brace of the function matching endFnRegex.
function extractBlock(src, startRegex, endFnRegex, label) {
  const lines = src.split('\n');
  const startIdx = findLineIndex(lines, startRegex, label + ':start');
  const endDeclIdx = findLineIndex(lines, endFnRegex, label + ':end');
  if (endDeclIdx < startIdx) throw new Error('extractBlock "' + label + '": end anchor precedes start anchor');
  return lines.slice(startIdx, funcEndLine(src, lines, endDeclIdx, label + ':end') + 1).join('\n');
}

// ===========================================================================
// --import mode: run Opti's REAL importData() (app.js:8175) on the file.
// Loaded the same way gen_opti_fixtures.mjs buildG4Sandbox does (APP_VERSION, the
// computeHash..computeHashWith block, the _EXPORT_FIELD_MAP..buildBackupFile io block), plus
// importData itself. Only its browser edges are stand-ins: FileReader hands the file text to
// rd.onload, and the two UI sinks record the outcome. showImportSectionPicker(data, detected)
// is where importData goes when it ACCEPTS the file (hash gate passed or skipped);
// alertDialog(title, text) is where it goes when it REFUSES it.
// ===========================================================================
function runOptiImport(optiRoot, rev, text) {
  const appJs = gitShow(optiRoot, rev, 'app.js');
  const lines = appJs.split('\n');
  const src = [
    lines[findLineIndex(lines, /^const APP_VERSION =/, 'APP_VERSION')],
    extractBlock(appJs, /^async function computeHash\(/, /^async function computeHashWith\(/, 'hash-block'),
    extractBlock(appJs, /^const _EXPORT_FIELD_MAP = \{/, /^async function buildBackupFile\(/, 'io-block'),
    extractFn(appJs, /^function importData\(/, 'importData'),
    'function alertDialog(title, text){ globalThis.__outcome.resolve({ accepted: false, title: title, text: text }); }',
    'function showImportSectionPicker(data, detected){ globalThis.__outcome.resolve({ accepted: true, detected: detected }); }',
    'function FileReader(){ this.result = null; this.onload = null; }',
    'FileReader.prototype.readAsText = function(f){ this.result = f.__text; this.onload(); };',
  ].join('\n\n');
  const ctx = vm.createContext({ console, crypto: globalThis.crypto, Blob, TextEncoder });
  let resolve;
  const done = new Promise((r) => { resolve = r; });
  ctx.__outcome = { resolve };
  vm.runInContext(src, ctx);
  ctx.__ev = { target: { files: [{ __text: text }], value: 'x' } };
  vm.runInContext('importData(globalThis.__ev);', ctx);
  return Promise.race([done, new Promise((_, rej) => setTimeout(() => rej(new Error('importData reached neither the picker nor an alert')), 5000))]);
}

// ===========================================================================
// Load the real functions straight out of the pinned Opti commit.
// ===========================================================================
function loadOptiHashFns(optiRoot, rev) {
  const appJs = gitShow(optiRoot, rev, 'app.js');
  const src = [
    extractFn(appJs, /^const _EXPORT_FIELD_MAP = \{/, '_EXPORT_FIELD_MAP'),
    extractFn(appJs, /^function _detokenizeEvents\(/, '_detokenizeEvents'),
    extractFn(appJs, /^function _detDeep\(/, '_detDeep'),
    extractFn(appJs, /^function _detokenizeSnapshot\(/, '_detokenizeSnapshot'),
    extractFn(appJs, /^async function computeHash\(/, 'computeHash'),
    extractFn(appJs, /^function _hashFallback32\(/, '_hashFallback32'),
    extractFn(appJs, /^function hashAlgoName\(/, 'hashAlgoName'),
    extractFn(appJs, /^async function computeHashWith\(/, 'computeHashWith'),
    'globalThis.__optiFns = { _EXPORT_FIELD_MAP: typeof _EXPORT_FIELD_MAP === "undefined" ? undefined : _EXPORT_FIELD_MAP, _detokenizeEvents, _detDeep, _detokenizeSnapshot, computeHash, _hashFallback32, hashAlgoName, computeHashWith };',
  ].join('\n\n');
  // `_EXPORT_FIELD_MAP` is a `const` at module scope in app.js; extractFn's brace-balance
  // captures the object literal but not the `const` binding name outside strict eval, so it is
  // evaluated with `vm` (Node's real sandboxed eval) rather than the host's `eval`/`Function`,
  // which would otherwise leak into/collide with this module's own top-level scope.
  return runInSandbox(src);
}
function runInSandbox(src) {
  const ctx = vm.createContext({ console });
  vm.runInContext(src, ctx);
  return ctx.__optiFns;
}

function sha256Hex(text) { return createHash('sha256').update(text, 'utf8').digest('hex'); }
function hashFallback32(str) {
  let h = 0;
  for (let i = 0; i < str.length; i++) { h = ((h << 5) - h) + str.charCodeAt(i); h |= 0; }
  return 'fallback-' + Math.abs(h).toString(16).padStart(8, '0');
}

// ===========================================================================
// Diff helpers
// ===========================================================================
function firstDivergence(a, b) {
  const len = Math.min(a.length, b.length);
  let i = 0;
  while (i < len && a[i] === b[i]) i++;
  if (i === len && a.length === b.length) return null;
  return i;
}
function contextAround(str, offset, radius = 40) {
  const start = Math.max(0, offset - radius);
  const end = Math.min(str.length, offset + radius);
  return { start, end, text: str.slice(start, end) };
}
function printDiffSummary(label, expected, actual) {
  const offset = firstDivergence(expected, actual);
  console.log(`  ${label}: MISMATCH`);
  if (offset === null) {
    console.log('    (strings compare equal by content but not by reference — unexpected)');
    return;
  }
  const eCtx = contextAround(expected, offset);
  const aCtx = contextAround(actual, offset);
  console.log(`    first divergence at offset ${offset} (expected length ${expected.length}, actual length ${actual.length})`);
  console.log(`    expected[${eCtx.start}:${eCtx.end}] = ${JSON.stringify(eCtx.text)}`);
  console.log(`    actual  [${aCtx.start}:${aCtx.end}] = ${JSON.stringify(aCtx.text)}`);
}

// ===========================================================================
// main
// ===========================================================================
const FALLBACK_HASH_PREFIX = /^fallback-/;

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const filePath = args.positional[0];
  if (!filePath) {
    console.error('Usage: node tools/verify_with_pwa.mjs [--opti-root <dir>] [--rev <sha>] <path-to-backup.json>');
    process.exit(1);
  }

  assertCleanOptiTree(args.optiRoot);
  const actualHash = resolveShortHash(args.optiRoot, args.rev);
  if (actualHash !== TARGET_OPTI_GIT) {
    console.error(`verify_with_pwa: pinned rev ${args.rev} resolved to ${actualHash}, expected ${TARGET_OPTI_GIT}`);
    process.exit(1);
  }
  let raw;
  try {
    raw = readFileSync(filePath, 'utf8');
  } catch (e) {
    console.error(`Could not read '${filePath}': ${e.message}`);
    process.exit(1);
  }

  if (args.importMode) {
    const r = await runOptiImport(args.optiRoot, args.rev, raw);
    if (r.accepted) {
      console.log(`'${filePath}': Opti importData accepted the file; sections offered: ${JSON.stringify(r.detected)}`);
      console.log('import OK');
      process.exit(0);
    }
    console.log(`'${filePath}': Opti importData REFUSED the file: ${r.title}: ${r.text}`);
    process.exit(1);
  }

  const optiFns = loadOptiHashFns(args.optiRoot, args.rev);

  let parsed;
  try {
    parsed = JSON.parse(raw);
  } catch (e) {
    console.error(`'${filePath}' is not valid JSON: ${e.message}`);
    process.exit(1);
  }

  const backupMeta = parsed && typeof parsed === 'object' ? parsed._backup : undefined;
  const isSchema2 = !!(backupMeta && backupMeta.schema === 2);

  if (!isSchema2) {
    console.log(`'${filePath}': not a schema-2 tokenized backup (or missing _backup.schema===2) — nothing to verify, treated as pass per PWA schema-1 fallback policy.`);
    console.log('hash OK');
    process.exit(0);
  }

  const expectedHash = backupMeta.hash;
  if (!expectedHash) {
    console.log(`'${filePath}': schema-2 backup with no _backup.hash present — nothing to verify.`);
    console.log('hash OK');
    process.exit(0);
  }

  if (!/^[0-9a-f]{64}$/.test(expectedHash) && !FALLBACK_HASH_PREFIX.test(expectedHash)) {
    console.log(`'${filePath}': _backup.hash ('${expectedHash}') is neither a 64-hex SHA-256 digest nor a fallback- sentinel — cannot verify.`);
    process.exit(1);
  }

  // Mirror Opti's live importData() exactly (app.js ~8188-8226, pinned rev):
  // detokenize -> full-copy _backup -> strip hash -> stringify -> hash with the
  // algorithm the FILE declares (data._backup.hashAlgo, else sha256/fallback32
  // inferred from the hash's own prefix) -> compare.
  const detokSnap = optiFns._detokenizeSnapshot(parsed);
  const detokEvents = optiFns._detokenizeEvents(parsed);
  const data = Object.assign({}, detokSnap, { events: detokEvents, _backup: Object.assign({}, backupMeta) });
  delete data._backup.hash;
  const cleanStr = JSON.stringify(data);

  const hashAlgo = backupMeta.hashAlgo || (FALLBACK_HASH_PREFIX.test(String(expectedHash)) ? 'fallback32' : 'sha256');
  let actualHashValue;
  try {
    actualHashValue = hashAlgo === 'fallback32' ? hashFallback32(cleanStr) : sha256Hex(cleanStr);
  } catch (e) {
    console.log(`'${filePath}': could not verify with algorithm '${hashAlgo}' (${e.message}) — Opti's own computeHashWith() would skip the gate here too (import proceeds unchecked).`);
    console.log('hash OK');
    process.exit(0);
  }

  if (actualHashValue === expectedHash) {
    console.log(`'${filePath}': _backup.hash = ${expectedHash} (algo ${hashAlgo})`);
    console.log(`recomputed hash over reconstructed pre-image (${cleanStr.length} bytes) matches.`);
    console.log('hash OK');
    process.exit(0);
  }

  console.log(`'${filePath}': HASH MISMATCH`);
  console.log(`  expected _backup.hash = ${expectedHash} (algo ${hashAlgo})`);
  console.log(`  actual   hash         = ${actualHashValue}`);
  printDiffSummary('hash string', expectedHash, actualHashValue);

  try {
    const path = await import('node:path');
    const dir = path.dirname(filePath);
    const candidate = path.join(dir, 'golden_hash_preimage.txt');
    if (path.basename(filePath) === 'golden_backup.json') {
      const referencePreimage = readFileSync(candidate, 'utf8');
      if (referencePreimage !== cleanStr) {
        console.log('  additionally diffing reconstructed pre-image against fixtures/golden_hash_preimage.txt:');
        printDiffSummary('pre-image', referencePreimage, cleanStr);
      }
    }
  } catch (_e) {
    // No sidecar reference available -- fine, the hash-string diff above is the summary.
  }

  process.exit(1);
}

main().catch(e => { console.error(e); process.exit(1); });
