#!/usr/bin/env node
// WP-8.12 / D30 export-coverage check (offline; no browser, no adb, no Dropbox).
//
//   node tools/live/export_coverage.mjs <PWA-export.json> <Apps-Questa-temp.zip>
//
// Answers: can the two D30 backups restore everything the live gate touches?
//   1. the JSON export is a full, hash-valid export that Opti's own importData accepts;
//   2. the zip holds each Dropbox area the scenarios write (state.json, events/, inbox*,
//      inbox-meta/, questa-backups/). An area missing from the zip is listed; that is only fine
//      when it did not exist in the folder before the first live connect.
// Prints names and counts only (never file contents).
import { readFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { exportCoverage, zipEntryNames } from './lib/checks.mjs';
import { REPO_ROOT } from './lib/opti.mjs';

export function coverageReport(exportPath, zipPath, runNode = defaultRunNode) {
  const exportJson = JSON.parse(readFileSync(exportPath, 'utf8'));
  const zipNames = zipEntryNames(readFileSync(zipPath));
  const c = exportCoverage({ exportJson, zipNames });
  const hash = runNode(['tools/verify_with_pwa.mjs', exportPath]);
  const imp = runNode(['tools/verify_with_pwa.mjs', '--import', exportPath]);
  const missing = Object.entries(c.zipAreas).filter(([, v]) => !v).map(([k]) => k);
  return {
    ...c,
    hashOk: hash.code === 0,
    importOk: imp.code === 0,
    zipEntries: zipNames.length,
    zipMissingAreas: missing,
    pass: hash.code === 0 && imp.code === 0 && c.zipHasState,
  };
}

function defaultRunNode(args) {
  const r = spawnSync(process.execPath, args, { cwd: REPO_ROOT, encoding: 'utf8' });
  return { code: r.status };
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const [exp, zip] = process.argv.slice(2);
  if (!exp || !zip) { console.error('usage: node tools/live/export_coverage.mjs <export.json> <folder.zip>'); process.exit(2); }
  const r = coverageReport(exp, zip);
  console.log(JSON.stringify(r, null, 2));
  process.exit(r.pass ? 0 : 1);
}
