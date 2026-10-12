#!/usr/bin/env node
// tools/capture_dropbox_bodies.mjs -- WP-8.1 G10 (D11, D30).
//
// Pulls the G10 capture that G10CaptureTest (app/src/androidTest/.../G10CaptureTest.kt) wrote
// inside the installed debug app, re-checks that no token or account id is in it, and writes
// fixtures/opti/G10/<name>.json plus fixtures/opti/G10/index.json (sha256 of every file).
//
// This script never talks to Dropbox and never sees a token. The capture itself runs inside the
// app with the user's own login (see plans/stage8/WP-8.1_G10_STEPS.md for the full steps).
//
// Usage:
//   node tools/capture_dropbox_bodies.mjs            pull from the phone (adb) and write fixtures
//   node tools/capture_dropbox_bodies.mjs --verify   check fixtures/opti/G10 against its index
//   node tools/capture_dropbox_bodies.mjs --self-test  offline checks of the leak scanner
//
// Env: ADB (default "adb"), G10_PACKAGE (default "io.github.palo007.questa.dev").

import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(__dirname, "..");
const outDir = path.join(repoRoot, "fixtures", "opti", "G10");
const ADB = process.env.ADB || "adb";
const PKG = process.env.G10_PACKAGE || "io.github.palo007.questa.dev";

// Anything that looks like a credential or the account id. A hit aborts before any file is written.
const LEAK_PATTERNS = [
  /sl\.[A-Za-z0-9_-]{10,}/,          // Dropbox short-lived access token
  /dbid:[A-Za-z0-9_-]+/,             // Dropbox account id
  /bearer\s+[^<\s"]+/i,              // Authorization header value
  /\\?"(access_token|refresh_token)\\?"\s*:/,   // token JSON fields, raw or inside an escaped body
];

const REQUIRED_KEYS = ["name", "kind", "expect", "status", "headers", "body"];

export function findLeaks(text) {
  return LEAK_PATTERNS.filter((re) => re.test(text)).map((re) => re.source);
}

export function validateRecord(fileName, text) {
  const leaks = findLeaks(text);
  if (leaks.length) throw new Error(`${fileName}: looks like it holds a secret (${leaks.join(", ")}) -- nothing written`);
  const rec = JSON.parse(text);
  for (const k of REQUIRED_KEYS) if (!(k in rec)) throw new Error(`${fileName}: missing key ${k}`);
  if (`${rec.name}.json` !== fileName) throw new Error(`${fileName}: name field is ${rec.name}`);
  return rec;
}

function sha256(buf) {
  return createHash("sha256").update(buf).digest("hex");
}

function adb(args, opts = {}) {
  return execFileSync(ADB, args, { maxBuffer: 64 * 1024 * 1024, ...opts });
}

function runAs(cmd) {
  return adb(["exec-out", "run-as", PKG, "sh", "-c", cmd]);
}

function pull() {
  const listing = runAs("ls files/G10 2>/dev/null").toString("utf8").split(/\s+/).filter(Boolean);
  if (!listing.includes("index.json")) {
    console.error(`No capture found in ${PKG}/files/G10. Run the instrumentation step first (see WP-8.1_G10_STEPS.md).`);
    process.exit(1);
  }
  const deviceIndex = JSON.parse(runAs("cat files/G10/index.json").toString("utf8"));
  const names = deviceIndex.files;
  if (!Array.isArray(names) || names.length === 0) throw new Error("device index lists no files");

  // Read and check everything first; write only if every file is clean.
  const records = new Map();
  for (const f of names) {
    if (!/^[a-z0-9_]+\.json$/.test(f)) throw new Error(`unexpected file name ${f}`);
    const text = runAs(`cat files/G10/${f}`).toString("utf8");
    validateRecord(f, text);
    records.set(f, text);
  }

  if (existsSync(outDir)) rmSync(outDir, { recursive: true, force: true });
  mkdirSync(outDir, { recursive: true });
  const hashes = {};
  for (const [f, text] of records) {
    writeFileSync(path.join(outDir, f), text);
    hashes[f] = sha256(Buffer.from(text, "utf8"));
  }
  const index = {
    capturedAtMs: deviceIndex.capturedAtMs,
    package: deviceIndex.package,
    source: "G10CaptureTest (WP-8.1), live Dropbox, real account in isolation (D30)",
    files: hashes,
  };
  writeFileSync(path.join(outDir, "index.json"), JSON.stringify(index, null, 2) + "\n");
  // The capture on the phone is no longer needed.
  runAs("rm -r files/G10");
  console.log(`Wrote ${records.size} G10 files to ${path.relative(repoRoot, outDir)} (no secrets found).`);
}

function verify() {
  const index = JSON.parse(readFileSync(path.join(outDir, "index.json"), "utf8"));
  const onDisk = readdirSync(outDir).filter((f) => f.endsWith(".json") && f !== "index.json").sort();
  const listed = Object.keys(index.files).sort();
  if (JSON.stringify(onDisk) !== JSON.stringify(listed)) throw new Error(`index lists ${listed} but folder has ${onDisk}`);
  for (const f of listed) {
    const text = readFileSync(path.join(outDir, f), "utf8");
    validateRecord(f, text);
    if (sha256(Buffer.from(text, "utf8")) !== index.files[f]) throw new Error(`${f}: sha256 mismatch`);
  }
  console.log(`G10 OK: ${listed.length} files, hashes match, no secrets.`);
}

function selfTest() {
  const ok = JSON.stringify({ name: "x", kind: "upload", expect: "Conflict", status: 409, headers: {}, body: "{\"error_summary\": \"path/conflict/file/..\"}" });
  validateRecord("x.json", ok);
  const bad = [
    { body: "Bearer abc.def" },
    { body: "{\"access_token\": \"x\"}" },
    { body: "token sl.ABCDEFGHIJKLMNOPQRSTUV" },
    { body: "account dbid:AAB12cd-EF" },
  ];
  for (const b of bad) {
    const text = JSON.stringify({ name: "x", kind: "k", expect: "e", status: 200, headers: {}, ...b });
    let threw = false;
    try { validateRecord("x.json", text); } catch (e) { threw = true; }
    if (!threw) throw new Error(`leak not caught: ${text}`);
  }
  let threw = false;
  try { validateRecord("y.json", ok); } catch (e) { threw = true; }
  if (!threw) throw new Error("name mismatch not caught");
  if (findLeaks("<redacted>").length) throw new Error("redaction marker flagged as a leak");
  console.log("self-test OK");
}

const isMain = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (isMain) {
  const arg = process.argv[2];
  if (arg === "--self-test") selfTest();
  else if (arg === "--verify") verify();
  else if (!arg) pull();
  else { console.error(`unknown option ${arg}`); process.exit(2); }
}
