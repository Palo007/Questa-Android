// WP-8.12: the local test PWA (the original Questa web app, pinned commit), extracted and served READ-ONLY.
//
// The PWA lives in its own repository, https://github.com/Palo007/Questa, and is NOT vendored
// here. Point $OPTI_ROOT at your checkout, or keep it as a sibling directory named `Opti`.
// It only reads that repository's object database: it never checks out, never writes to it.
// extractOpti(): the tree of <rev> (same files as `git archive <rev>`, read with ls-tree + cat-file)
// into .build/opti-<rev>/ (git-ignored).
// It only reads Opti's object database: it never checks out, never writes to the Opti repo.
// serveOpti(): a tiny GET/HEAD-only static server on http://localhost:8080 (Opti INSTALL.md's
// own address; the PWA's redirect_uri is location.origin + location.pathname, sync.js:147).
import { execFileSync } from 'node:child_process';
import { createServer } from 'node:http';
import { existsSync, mkdirSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { dirname, extname, join, normalize, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

export const OPTI_REV = '7d5d4f3'; // must match OptiFixtureManifestTest.TARGET_OPTI_GIT
export const REPO_ROOT = resolve(fileURLToPath(new URL('../../..', import.meta.url)));
// The original PWA: https://github.com/Palo007/Questa -- a separate checkout, not vendored.
// Resolve from $OPTI_ROOT, else a sibling directory named `Opti`. Previously a hardcoded
// absolute path under one maintainer's home directory, which worked on that machine only.
// (Declared after REPO_ROOT, which it reads -- a const used before its declaration throws.)
export const DEFAULT_OPTI_ROOT = process.env.OPTI_ROOT || join(dirname(REPO_ROOT), 'Opti');
export const BUILD_DIR = join(REPO_ROOT, '.build');
export const PWA_PORT = 8080;
export const PWA_ORIGIN = 'http://localhost:' + PWA_PORT;

export function extractOpti({ optiRoot = DEFAULT_OPTI_ROOT, rev = OPTI_REV, outRoot = BUILD_DIR } = {}) {
  const full = execFileSync('git', ['-C', optiRoot, 'rev-parse', '--verify', rev + '^{commit}'], { encoding: 'utf8' }).trim();
  if (!full.startsWith(rev)) throw new Error('Opti rev ' + rev + ' resolved to ' + full);
  const dir = join(outRoot, 'opti-' + rev);
  const stamp = join(dir, '.extracted-from');
  if (existsSync(stamp) && readFileSync(stamp, 'utf8').trim() === full) return dir;
  rmSync(dir, { recursive: true, force: true });
  mkdirSync(dir, { recursive: true });
  // Same bytes as `git archive <rev>`, read blob by blob from the object database (`ls-tree` +
  // one `cat-file --batch`), so no tar binary is needed on Windows.
  const tree = execFileSync('git', ['-C', optiRoot, 'ls-tree', '-r', '-z', '--full-tree', full], { maxBuffer: 64 << 20 })
    .toString('utf8').split('\0').filter(Boolean)
    .map((l) => { const [meta, path] = l.split('\t'); const [mode, type, sha] = meta.split(' '); return { mode, type, sha, path }; })
    .filter((e) => e.type === 'blob' && e.mode !== '120000');
  const batch = execFileSync('git', ['-C', optiRoot, 'cat-file', '--batch'], { input: tree.map((e) => e.sha).join('\n') + '\n', maxBuffer: 512 << 20 });
  let off = 0;
  for (const e of tree) {
    const nl = batch.indexOf(0x0a, off);
    const [sha, type, size] = batch.toString('utf8', off, nl).split(' ');
    if (sha !== e.sha || type !== 'blob') throw new Error('cat-file mismatch at ' + e.path);
    const body = batch.subarray(nl + 1, nl + 1 + Number(size));
    off = nl + 1 + Number(size) + 1;
    const target = resolve(dir, e.path);
    if (!target.startsWith(resolve(dir) + sep)) throw new Error('refused path ' + e.path);
    mkdirSync(dirname(target), { recursive: true });
    writeFileSync(target, body);
  }
  if (!existsSync(join(dir, 'index.html')) || !existsSync(join(dir, 'sync.js'))) throw new Error('extract incomplete: ' + dir);
  writeFileSync(stamp, full + '\n');
  return dir;
}

const TYPES = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.json': 'application/json',
  '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.webmanifest': 'application/manifest+json',
};

/** Maps a URL path to a file under root, or null (traversal, dotfile, missing). */
export function resolveStatic(root, urlPath) {
  let p;
  try { p = decodeURIComponent(urlPath.split('?')[0].split('#')[0]); } catch { return null; }
  if (p.includes('\0')) return null;
  if (p.endsWith('/')) p += 'index.html';
  const rel = normalize(p).replace(/^([/\\])+/, '');
  if (rel.split(/[/\\]/).some((seg) => seg === '..' || seg.startsWith('.'))) return null;
  const abs = resolve(root, rel);
  const base = resolve(root) + sep;
  if (!abs.startsWith(base)) return null;
  try { if (!statSync(abs).isFile()) return null; } catch { return null; }
  return abs;
}

/** Serves [root] on 127.0.0.1 and ::1 (Chromium's "localhost" may use either). */
export async function serveOpti(root, { port = PWA_PORT } = {}) {
  const handler = (req, res) => {
    if (req.method !== 'GET' && req.method !== 'HEAD') { res.writeHead(405); res.end(); return; }
    const file = resolveStatic(root, req.url || '/');
    if (!file) { res.writeHead(404); res.end('not found'); return; }
    res.writeHead(200, { 'Content-Type': TYPES[extname(file)] || 'application/octet-stream', 'Cache-Control': 'no-store' });
    res.end(req.method === 'HEAD' ? undefined : readFileSync(file));
  };
  const servers = [];
  for (const host of ['127.0.0.1', '::1']) {
    const s = createServer(handler);
    try {
      await new Promise((ok, fail) => { s.once('error', fail); s.listen(port, host, ok); });
      servers.push(s);
    } catch (e) {
      if (host === '127.0.0.1') throw e; // IPv6 loopback is optional
    }
  }
  return { url: 'http://localhost:' + port + '/', close: () => Promise.all(servers.map((s) => new Promise((ok) => s.close(ok)))) };
}
