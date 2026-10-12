// WP-8.12: drives the local test PWA in a PERSISTENT, HEADED Playwright Chromium profile.
//
// Token rule: the Dropbox tokens live only in that profile's localStorage, put there by the
// PWA's own "Connect Dropbox" flow when the USER logs in (run_live.mjs --login). The harness
// never reads, returns, prints or stores them. Every Dropbox call below runs INSIDE the page and
// goes through the PWA's own functions (dbxListFolder, dbxDownloadRaw, dbxUploadText, syncNow),
// which read the stored auth themselves. Every value that comes back from the page is checked by
// assertNoSecret() before the harness uses it.
import { join } from 'node:path';
import { BUILD_DIR, DEFAULT_OPTI_ROOT, extractOpti, serveOpti } from './opti.mjs';

export const PROFILE_DIR = join(BUILD_DIR, 'live-pwa-profile');

const SECRET_SHAPES = [/\bsl\.[A-Za-z0-9_-]{20,}/, /"(access|refresh)_?[Tt]oken"\s*:\s*"[^"]{8,}/, /\bBearer\s+\S{8,}/];

/** Throws if a value that left the page looks like it carries a token. */
export function assertNoSecret(value, where) {
  const text = typeof value === 'string' ? value : JSON.stringify(value === undefined ? null : value);
  for (const r of SECRET_SHAPES) if (r.test(text)) throw new Error('refused: a value from ' + where + ' looks like a token; nothing was saved');
  return value;
}

// ---- in-page helpers (serialized into the page; they run in the PWA's global scope) --------
// Each returns plain data only. None of them returns syncCfg() itself (it holds the tokens).
export const inPage = {
  ready: () => typeof syncNow === 'function' && typeof syncSubset === 'function' && typeof S !== 'undefined'
    && typeof bootGateBlocksInput === 'function' && !bootGateBlocksInput(),

  status: () => {
    const c = syncCfg();
    return {
      connected: !!c.refreshToken, enabled: !!c.enabled, appKey: dbxAppKey(), deviceId: syncDeviceId(),
      lastRev: c.lastRev || null, lastError: c.lastError || null, nowMs: Date.now(),
      tzOffsetMin: -new Date().getTimezoneOffset(),
    };
  },

  subset: () => { const s = syncSubset(); return { stable: stableStringify(s), subset: JSON.parse(JSON.stringify(s)) }; },

  round: async () => {
    const before = syncCfg().lastRev || null;
    await syncNow();
    const c = syncCfg();
    return { revBefore: before, revAfter: c.lastRev || null, lastError: c.lastError || null };
  },

  list: async (path) => (await dbxListFolder(path)).map((e) => e && e.name).filter(Boolean),

  download: async (path) => { const r = await dbxDownloadRaw(path); return r ? { text: r.text, rev: r.rev || null } : null; },

  inject: async ({ path, text }) => {
    // same rule as checks.assertInjectPath, repeated inside the page on purpose
    if (!/^\/inbox\/[A-Za-z0-9-]{8,64}\.json$/.test(path)) throw new Error('refused inject path ' + path);
    await dbxUploadText(path, text);
    return true;
  },

  events: async ({ from, kind, taskId }) => JSON.parse(JSON.stringify(await getEvents({ from, kind, taskId }))),

  hasUid: async (uid) => !!(await evtHasUid(uid)),

  // Test-only runtime stubs (in memory, this page only; the Opti files are never changed).
  holdInbox: () => { window.__liveInboxHeld = true; syncInboxConsume = async function () { return []; }; return true; },
  watchSkew: () => {
    window.__liveSkew = [];
    const orig = _skewDiagNote;
    // Opti calls _skewDiagNote for EVERY record at its sites and only counts the ones past
    // _skewDiagThreshold (sync.js:1288-1296). Record that verdict, so a check can tell a real
    // future stamp from a plain call (the 2026-09-25 s3 run passed on 5 past-dated calls).
    _skewDiagNote = function (k, ts, id) {
      const thr = (typeof _skewDiagThreshold === 'number') ? _skewDiagThreshold : 0;
      window.__liveSkew.push({ kind: k, ts: ts, id: id, future: thr > 0 && (Number(ts) || 0) > thr });
      return orig.apply(this, arguments);
    };
    return true;
  },
  skewNotes: () => (window.__liveSkew || []).slice(),

  // State edits, through the PWA's own code paths.
  addTask: ({ id, type, title }) => {
    const t = { id, type, title, notes: '', difficulty: 'easy', value: 0, done: false, streak: 0, checklist: [], tags: [],
      history: [], createdAt: now(), updatedAt: now(), up: true, down: true, cUp: 0, cDown: 0,
      repeat: type === 'daily' ? [true, true, true, true, true, true, true] : undefined };
    S.tasks.unshift(t); save(); return true;
  },
  editTitle: ({ id, title }) => { const t = S.tasks.find((x) => x.id === id); if (!t) return false; t.title = title; t.updatedAt = now(); save(); return t.updatedAt; },
  tap: ({ id, dir, n }) => { for (let i = 0; i < n; i++) { _suppressHabitClick = null; scoreHabit(id, dir, null); } const t = S.tasks.find((x) => x.id === id); return t ? { cUp: t.cUp || 0, cDown: t.cDown || 0 } : null; },
  charNow: () => ({ xp: S.char.xp, gold: S.char.gold, hp: S.char.hp, lvl: S.char.lvl }),
  pause: (on) => { setPause(!!on); return { paused: !!S.prefs.paused, pausedDays: (S.prefs.pausedDays || []).slice() }; },
  importPartial: async ({ data, keys }) => { await applyImportSections(data, keys, 'merge'); return true; },
  backupDue: async (tier) => {
    S.prefs.autoBackupEnabled = Object.assign({}, S.prefs.autoBackupEnabled || {}, { [tier]: true }); save();
    const bk = bkLocalLoad(); bk[tier].lastTs = 0; bkLocalSave(bk); // due now; slot index kept
    await syncMaybeAutoExport();
    return true;
  },
  detokTasks: (text) => {
    const parsed = JSON.parse(text);
    const data = (parsed && parsed._backup && parsed._backup.schema === 2) ? _detokenizeSnapshot(parsed) : parsed;
    return JSON.parse(JSON.stringify(data.tasks || []));
  },
  // Scenario 99 (test environment repair after --fake-day): move every future value in THIS test
  // profile's data back to today/now and save. In memory + this profile's localStorage only.
  repairFakeDay: (opt) => {
    const now = Date.now(), lim = now + 120000, today = Number(dayStampOf(now));
    if (opt && opt.dryRun) return { today };
    const moved = [];
    const isDayKey = (k) => /^(cResetOn|doneDay|missedOn|day)$/.test(k);
    const walk = (o, path) => {
      if (Array.isArray(o)) { o.forEach((x, i) => walk(x, path + '[' + i + ']')); return; }
      if (!o || typeof o !== 'object') return;
      for (const k of Object.keys(o)) {
        if (k === 'history') continue; // task/char history rows are dated records, not clocks
        const v = o[k];
        if (typeof v === 'number' && v > lim && v < 4e12) { o[k] = now; moved.push(path + '.' + k); }
        else if (typeof v === 'number' && isDayKey(k) && v > today && v < 30000000) { o[k] = today; moved.push(path + '.' + k); }
        else walk(v, path + '.' + k);
      }
    };
    if (Number(S.lastCron) > today) { S.lastCron = today; moved.push('$.lastCron'); }
    ['char', 'tasks', 'rewards', 'tags', 'deletions'].forEach((k) => walk(S[k], '$.' + k));
    if (S.prefs) {
      if (Number(S.prefs.pausedAt) > lim) { S.prefs.pausedAt = now; moved.push('$.prefs.pausedAt'); }
      if (Array.isArray(S.prefs.pausedDays)) {
        const keep = S.prefs.pausedDays.filter((d) => Number(d) <= today);
        if (keep.length !== S.prefs.pausedDays.length) { S.prefs.pausedDays = keep; moved.push('$.prefs.pausedDays'); }
      }
    }
    if (Number(S.__hlcLast) > lim) { S.__hlcLast = now; moved.push('$.__hlcLast'); }
    // Sync cfg clocks stamped by the fake-day page clock (e.g. evtLastPullAt): a future value
    // throttles every events pull (`now - evtLastPullAt < 60s`) until real time passes it.
    const cfg = syncCfg(), patch = {};
    for (const k of Object.keys(cfg)) {
      const v = cfg[k];
      if (typeof v === 'number' && v > lim && v < 4e12) { patch[k] = now; moved.push('$cfg.' + k); }
    }
    if (Object.keys(patch).length) syncCfgSave(patch);
    save();
    return { today, count: moved.length, sample: moved.slice(0, 12) };
  },
  forcePush: async () => { await syncForcePush(); const c = syncCfg(); return { rev: c.lastRev || null, lastError: c.lastError || null }; },
  forcePull: async () => { await syncForcePull(); const c = syncCfg(); return { rev: c.lastRev || null, lastError: c.lastError || null }; },
};

// ---- layer 1: the in-page token guard ------------------------------------------------------
// Runs one helper INSIDE the page, then (still inside the page) reads the stored tokens from the
// PWA's own config (syncCfg(), plus the PKCE verifier) and refuses the result if any returned
// string contains one of them. Only the result leaves the page; the token values never do, and
// the error text names no token. assertNoSecret() (pattern check) stays as layer 2 in node.
export const guardedCall = async (fn, arg) => {
  const out = await fn(arg);
  const text = JSON.stringify(out === undefined ? null : out);
  const secrets = [];
  try { const c = syncCfg(); secrets.push(c.accessToken, c.refreshToken); } catch (e) { /* no config yet */ }
  try { const pk = JSON.parse(localStorage.getItem('questa.sync.pkce') || 'null'); if (pk) secrets.push(pk.v); } catch (e) { /* none */ }
  for (const s of secrets) {
    if (typeof s === 'string' && s.length >= 8 && text.includes(s)) {
      throw new Error('refused in page: a result contained a stored Dropbox token; nothing left the page');
    }
  }
  return out;
};

/** The exact expression evaluated in the page for one helper call (unit-tested). */
export function guardedExpression(name, arg) {
  const fn = inPage[name];
  if (typeof fn !== 'function') throw new Error('no in-page helper ' + name);
  return '(' + guardedCall.toString() + ')(' + fn.toString() + ', ' + JSON.stringify(arg === undefined ? null : arg) + ')';
}

// dropbox.com / dropboxapi.com web-session cookies. The PWA needs none of them (it uses its bearer
// token), so they are removed after the login and at the end of every run.
export const DROPBOX_COOKIE_DOMAIN = /(^|\.)dropbox(api)?\.com$/;
export async function clearDropboxCookies(ctx) {
  try { await ctx.clearCookies({ domain: DROPBOX_COOKIE_DOMAIN }); } catch (e) { /* context already closed */ }
}

const LAUNCH = { headless: false, viewport: { width: 412, height: 915 }, serviceWorkers: 'block' };

/**
 * runOpti({ optiRoot, script, timezoneId, clockOffsetMs }): Promise<result of script(api)>.
 * The card's `token` parameter is gone on purpose (see WP-8.12_NOTES.md): auth stays in the page.
 */
export async function runOpti({ optiRoot = DEFAULT_OPTI_ROOT, script, timezoneId, clockOffsetMs = 0, readyTimeoutMs = 60000 } = {}) {
  const { chromium } = await import('playwright');
  const root = extractOpti({ optiRoot });
  const server = await serveOpti(root);
  const ctx = await chromium.launchPersistentContext(PROFILE_DIR, { ...LAUNCH, ...(timezoneId ? { timezoneId } : {}) });
  try {
    if (clockOffsetMs) await ctx.clock.install({ time: Date.now() + clockOffsetMs });
    const page = ctx.pages()[0] || (await ctx.newPage());
    await page.goto(server.url);
    const call = async (name, arg) => assertNoSecret(await page.evaluate(guardedExpression(name, arg)), 'page.' + name);
    const api = {
      page,
      call,
      waitReady: () => page.waitForFunction(inPage.ready, null, { timeout: readyTimeoutMs }),
      status: () => call('status'),
      subset: () => call('subset'),
      round: () => call('round'),
      list: (p) => call('list', p),
      download: (p) => call('download', p),
      inject: (p, text) => call('inject', { path: p, text }),
    };
    await api.waitReady();
    const st = await api.status();
    if (!st.connected) throw new Error('the test PWA is not connected; run: node tools/live/run_live.mjs --login');
    // Pin the PWA's app key exactly when one is supplied (DROPBOX_APP_KEY); otherwise check the
    // shape only. The literal value is deliberately not committed -- it is the identity of a real
    // Dropbox app and is supplied by whoever runs the live gate.
    const expectedAppKey = process.env.DROPBOX_APP_KEY;
    const appKeyOk = expectedAppKey
      ? st.appKey === expectedAppKey
      : /^[a-z0-9]{15,}$/.test(String(st.appKey || ''));
    if (!appKeyOk) throw new Error('unexpected test PWA app key: ' + st.appKey);
    return await script(api);
  } finally {
    await clearDropboxCookies(ctx);
    await ctx.close();
    await server.close();
  }
}

// Reads ONLY "is a refresh token stored" from the profile, on the PWA origin, WITHOUT running
// the PWA (it syncs on page load): the probe URL is answered by a Playwright route with an empty
// page, and service workers are blocked, so no Opti script runs. Returns a boolean.
export const PROBE_PATH = '__live_probe';
export const probeLinked = () => {
  try { const c = JSON.parse(localStorage.getItem('questa.sync.v1') || 'null'); return !!(c && c.refreshToken); } catch (e) { return false; }
};

/** The login-time stubs: the force-push buttons would overwrite Dropbox with the empty test profile. */
export const disableForcePush = () => {
  const no = function () { alert('Disabled in the live-gate test profile. Use "Connect Dropbox".'); };
  window.confirmConnectForForcePush = no; window.confirmForcePush = no; window.confirmEventsForcePush = no;
  try { localStorage.removeItem('questa.sync.pendingForcePush'); } catch (e) { /* none */ }
  return true;
};

export function refuseLoginIfLinked(linked, allowLinked) {
  if (linked && !allowLinked) {
    throw new Error('refused: the test profile is already linked, and the PWA syncs on page load. Nothing to do. (To open it anyway, add --live.)');
  }
}

/**
 * One-time login. Opens the profile headed; the user taps "Connect Dropbox" and logs in. When the
 * PWA shows linked and its first round has settled, the dropbox.com cookies are cleared and the
 * window closes by itself. Returns { linked }.
 */
export async function openForLogin({ optiRoot = DEFAULT_OPTI_ROOT, allowLinked = false, timeoutMs = 15 * 60000 } = {}) {
  const { chromium } = await import('playwright');
  const server = await serveOpti(extractOpti({ optiRoot }));
  const ctx = await chromium.launchPersistentContext(PROFILE_DIR, LAUNCH);
  let closed = false;
  ctx.on('close', () => { closed = true; });
  try {
    const page = ctx.pages()[0] || (await ctx.newPage());
    await page.route(server.url + PROBE_PATH, (r) => r.fulfill({ contentType: 'text/html', body: '<!doctype html><title>probe</title>' }));
    await page.goto(server.url + PROBE_PATH);
    const linked = await page.evaluate(probeLinked);
    await page.unroute(server.url + PROBE_PATH);
    refuseLoginIfLinked(linked, allowLinked);

    page.on('load', () => { page.evaluate(disableForcePush).catch(() => {}); });
    await page.goto(server.url);
    const until = Date.now() + timeoutMs;
    while (!closed && Date.now() < until) {
      await new Promise((ok) => setTimeout(ok, 2000));
      if (closed) break;
      let st = null;
      try {
        st = assertNoSecret(await page.evaluate(guardedExpression('status')), 'page.status');
        st.inFlight = await page.evaluate('typeof _syncInFlight !== "undefined" && !!_syncInFlight');
      } catch (e) { continue; } // page navigating (Dropbox login, redirect back)
      if (st.connected && !st.inFlight && page.url().startsWith(server.url)) {
        await clearDropboxCookies(ctx);
        await ctx.close();
        return { linked: true };
      }
    }
    return { linked: false };
  } finally {
    if (!closed) { await clearDropboxCookies(ctx); await ctx.close().catch(() => {}); }
    await server.close();
  }
}
