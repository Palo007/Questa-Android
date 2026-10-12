// WP-8.12 live scenarios 1-17 (plans/PARITY_FIX_PLAN.html §6.2). Each scenario lists the
// Dropbox paths it may write or delete (inside the app folder Apps/Questa-temp only), the
// things the USER must do, and a run(ctx) that interleaves PWA steps (in-page) and phone steps
// (LiveInteropTest). ctx.check(name, ok, detail) records a pass condition; a scenario passes
// when every check is true. Nothing here runs on import.
import {
  charXpMatches, checkBackupRotation, diffPaths, expectedCharXp, idOrderDiff, makeInboxRecord, parseBackupName, pickCrossTz, sameTasks, sectionsExcept,
  sortById, stableAllById, stableById, stableStringify, stateUploads, taskById, tombstoneFor, TZ_CANDIDATES, uploadedPaths, zoneOffsetMin,
} from './lib/checks.mjs';

// Paths the two apps' own rounds may write in ANY scenario (the normal sync traffic).
export const ROUND_WRITES = [
  '/state.json (update by rev)',
  '/events/<device>-<yyyymm>.json (each device writes only its own month files)',
  '/inbox-meta/habits.json, /inbox-meta/reminders.json (only when the list changed)',
  '/questa-backups/<tier>-<dev6>-<slot>-<stamp>.json (only when an enabled auto-backup tier is due)',
];
// ... and may delete in ANY scenario: only a device's OWN backup file in the slot it reuses.
export const ROUND_DELETES = ["/questa-backups/<tier>-<ownDev6>-<slot>-*.json (own rotation, when a tier fires)"];

const DAY = 86400000;
// Kotlin persists the inbox throttle (InboxSteps: prefs.lastRunAt, INBOX_MIN_INTERVAL_MS = 60 s), so
// a round within 60 s of the last inbox run skips the inbox. Inbox scenarios wait this long first.
const INBOX_WAIT_MS = 61000;
const sleep = (ms) => new Promise((ok) => setTimeout(ok, ms));
const habits = (sub) => ((sub && sub.tasks) || []).filter((t) => t && t.type === 'habit');

// ---- small shared sequences ---------------------------------------------------
async function kAddAndShare(ctx, p, type, prefix) {
  const id = ctx.id(prefix);
  ctx.k('add-task', { id, type, title: 'LIVE-' + prefix });
  ctx.k('round');
  await p.round();
  const got = taskById((await p.subset()).subset, id);
  if (!got) throw new Error('setup: the PWA did not receive ' + id);
  return id;
}
const kSub = (ctx) => ctx.k('snapshot').result;
const kStatus = (ctx, since = 0) => ctx.k('status', since ? { since } : {}).result;

export const SCENARIOS = [
  {
    n: 1, title: 'Idle, 20 rounds', pass: 'The rev is unchanged; 0 conflict events.',
    writes: [], deletes: [], user: [],
    async run(ctx) {
      const t0 = Date.now();
      await ctx.pwa({}, async (p) => {
        ctx.k('round'); await p.round(); ctx.k('round'); await p.round(); // settle first
        const rev0 = (await p.status()).lastRev;
        // Opti keeps each device's LOCAL array order (sync.js:1552-1558) and its nothingToPush
        // test (sync.js:2467-2470) sorts object keys, not arrays. Two devices holding the same
        // content in two orders therefore re-upload on EVERY round, forever: each download
        // differs from its own merge by order only. Reproduced offline with two Opti 7d5d4f3
        // instances (10+10 idle rounds: 19 uploads; same order: 0). Kotlin copies the rule
        // (SyncManager nothingToPush), so the churn is Opti's, not a Kotlin bug. Scenario 2
        // leaves exactly that state behind. Kotlin's half of the churn is also invisible in
        // kr.calls: every `am instrument` is a fresh process whose NETWORK trigger
        // (SyncScheduler onAvailable) syncs before LiveInteropTest installs its recorder.
        const orderDiff = idOrderDiff(kSub(ctx), (await p.subset()).subset);
        let stateWrites = 0; const revs = []; const pStable = [];
        for (let i = 0; i < 10; i++) {
          const kr = ctx.k('round');
          stateWrites += stateUploads(kr.calls).length;
          const pr = await p.round();
          revs.push(pr.revAfter);
          if (orderDiff.length) pStable.push(stableAllById((await p.subset()).subset));
        }
        const ks = kStatus(ctx, t0);
        const pConf = await p.call('events', { from: t0, kind: 'conflict' });
        // Kotlin keeps its rev in the sync_base row (status.baseRev) since WP-8.2; the old
        // DROPBOX_LAST_REV key (status.lastRev) is migrated away and always null.
        if (!orderDiff.length) {
          ctx.check('rev unchanged over 20 rounds', revs.every((r) => r === rev0) && ks.baseRev === rev0, { rev0, revs, kotlin: ks.baseRev });
          // kr.calls misses Kotlin's pre-recorder round (see above), so stateWrites is only a hint. A
          // Kotlin upload makes a NEW rev, which the PWA's next download records as its lastRev, and
          // Kotlin's own base rev would move too: judge by the revs, not by the recorded calls.
          ctx.check('Kotlin made 0 state.json uploads (no new rev seen by the PWA; Kotlin base rev = rev0)',
            revs.every((r) => r === rev0) && ks.baseRev === rev0, { rev0, revs: [...new Set(revs)], kotlinBaseRev: ks.baseRev, recordedStateWrites: stateWrites });
        } else {
          ctx.note('[DEFERRED_OPEN_QUESTION] the two devices hold the same content in a different order ('
            + orderDiff.join(', ') + '), so Opti\'s order-sensitive nothingToPush re-uploads every round '
            + '(legacy limit, kept for parity). The rev check is replaced by a content check. revs: ' + new Set(revs).size);
          const kEnd = stableAllById(kSub(ctx));
          ctx.check('content unchanged over 20 rounds (arrays by id; order differs, see note)',
            pStable.every((s) => s === pStable[0]) && kEnd === pStable[0], { orderDiff, distinctRevs: new Set(revs).size });
        }
        ctx.check('0 conflict events (Kotlin)', ks.conflictEvents === 0, { n: ks.conflictEvents });
        ctx.check('0 conflict events (PWA)', pConf.length === 0, { n: pConf.length });
      });
    },
  },
  {
    n: 2, title: 'Concurrent ties', pass: 'stableStringify is equal after 1 round each.',
    writes: ['one test todo "LIVE-s2" in /state.json'], deletes: [], user: [],
    async run(ctx) {
      await ctx.pwa({}, async (p) => {
        const id = await kAddAndShare(ctx, p, 'todo', 's2');
        ctx.k('edit-title', { id, title: 'LIVE-s2-K' });
        await p.call('editTitle', { id, title: 'LIVE-s2-P' });
        ctx.k('round'); await p.round(); ctx.k('round');
        const k = kSub(ctx); const ps = await p.subset();
        // Opti mergeCollection keeps each device's LOCAL order and a new task is unshifted on the
        // device that made it (app.js:6227), so the two devices may hold the same tasks in two
        // orders. Content is compared with tasks/rewards/tags sorted by id; the order is a note.
        ctx.check('stableStringify equal (tasks/rewards/tags by id)', stableById(k) === stableById(ps.subset),
          { diff: diffPaths(sortById(k), sortById(ps.subset)) });
        if (stableStringify(k) !== ps.stable) ctx.note('[DEFERRED_OPEN_QUESTION] same content, different array order (Opti local-order merge)');
        ctx.check('same winner title', taskById(k, id)?.title === taskById(ps.subset, id)?.title,
          { kotlin: taskById(k, id)?.title, pwa: taskById(ps.subset, id)?.title });
      });
    },
  },
  {
    n: 3, title: 'Kotlin +10 min',
    // The spec (PARITY_FIX_PLAN 6.2) says "the PWA edit is present". Opti cannot give that on
    // both sides: the fast device's edit wins in the end (see WP-8.12_NOTES, "Scenario 3").
    // The checks below test Opti's REAL outcome, step by step.
    pass: '0 tombstones; the PWA clamp rejects the future stamp (PWA edit wins on the PWA in round 1); '
      + 'Kotlin keeps its edit (GUARD 1, raw); both converge on the fast edit with no ping-pong; the future stamp is logged.',
    writes: ['one test todo "LIVE-s3" in /state.json'], deletes: [], user: [],
    // The phone clock is not changed (that is a system setting). The PWA runs 10 min BEHIND
    // instead (Playwright clock), so Kotlin's stamps look +10 min in the future to it. Every
    // compare in the merge is "stamp vs the merging device's own clock", so only the relative
    // skew matters: a real +10 min phone gives the same result.
    async run(ctx) {
      await ctx.pwa({ clockOffsetMs: -10 * 60000 }, async (p) => {
        const id = await kAddAndShare(ctx, p, 'todo', 's3');
        await p.call('watchSkew');
        const title = async () => taskById((await p.subset()).subset, id)?.title;
        // 2026-09-25 rerun: the checks alone could not tell WHY the devices diverged (a Kotlin round
        // Error is status "ok" for the step). Every round now lands in `trace`: the Kotlin result
        // string, its Dropbox calls (endpoint, path, status), the phone clock and the base rev; the
        // PWA revs and lastError; and both task stamps. Written to s3-trace.json and checked.
        const trace = [];
        const callSummary = (calls) => (calls || []).map((c) => {
          let path = null; try { path = JSON.parse(c.arg || 'null')?.path ?? null; } catch { /* not JSON */ }
          return { ep: String(c.endpoint || '').replace(/^https:\/\/[^/]+/, ''), path, status: c.status };
        });
        const task = (sub) => { const t = taskById(sub, id); return t ? { title: t.title, updatedAt: t.updatedAt, createdAt: t.createdAt } : null; };
        // 2026-09-25 (2nd rerun): Kotlin's uploads are NOT in r.calls. Every `am instrument` is a new
        // app process whose own network/startup trigger runs a real round BEFORE LiveInteropTest
        // installs its recorder (same gap as s1). So a Kotlin upload is judged by the REMOTE: after
        // each Kotlin round the PWA page downloads /state.json and the check reads its rev, the
        // wrapper's deviceId (authorship) and the task. The PWA page makes no round in between.
        const remote = async () => {
          const d = await p.download('/state.json'); let w = null; try { w = JSON.parse(d.text); } catch { /* corrupt */ }
          return { rev: d.rev, deviceId: w?.deviceId ?? null, task: task(w?.state) };
        };
        const kRound = async (label) => {
          const r = ctx.k('round'); const st = kStatus(ctx);
          const e = { label, side: 'kotlin', result: r.result, calls: callSummary(r.calls), phoneNowMs: st.nowMs, baseRev: st.baseRev,
            kDev: st.deviceId, task: task(kSub(ctx)), remote: await remote() };
          trace.push(e); return { ...r, e };
        };
        const pRound = async (label) => {
          const r = await p.round();
          const e = { label, side: 'pwa', ...r, pageNowMs: await p.page.evaluate(() => Date.now()), task: task((await p.subset()).subset) };
          trace.push(e); return r;
        };
        const kOk = (r) => !/Error|Interlock|NeedsFirst|null/.test(String(r.result));
        try {
          ctx.k('edit-title', { id, title: 'LIVE-s3-K' });
          const kr1 = await kRound('K1 after the Kotlin edit');
          await p.call('editTitle', { id, title: 'LIVE-s3-P' });
          // PWA round 1: both changed; _clampFuture turns Kotlin's stamp into 0 (sync.js:1458).
          await pRound('P1 after the PWA edit');
          const p1 = await title();
          ctx.check('round 1: the PWA clamp rejects the future stamp (PWA shows the PWA edit)', p1 === 'LIVE-s3-P', { title: p1 });
          // Kotlin round: only remote changed; GUARD 1 compares RAW stamps (sync.js:1412), keeps K
          // and must UPLOAD it (merged != remote).
          const p1Rev = trace[trace.length - 1].revAfter;
          const kr2 = await kRound('K2');
          ctx.check('Kotlin keeps its own edit (GUARD 1, raw stamps)', kr2.e.task?.title === 'LIVE-s3-K', kr2.e.task);
          const r2 = kr2.e.remote;
          ctx.check('after K2 the remote holds K, written by the Kotlin device (rev moved past the PWA upload)',
            r2.task?.title === 'LIVE-s3-K' && r2.deviceId === kr2.e.kDev && r2.rev !== p1Rev && r2.rev === kr2.e.baseRev,
            { remote: r2, kotlinDev: kr2.e.kDev, kotlinBaseRev: kr2.e.baseRev, pwaUploadRev: p1Rev });
          // PWA round 2: only remote changed; GUARD 1 raw, K > P, so the PWA takes K.
          await pRound('P2');
          const kLast = await kRound('K3');
          const pLast = await pRound('P3');
          const k = kSub(ctx); const ps = (await p.subset()).subset; const notes = await p.call('skewNotes');
          const kt = taskById(k, id)?.title, pt = taskById(ps, id)?.title;
          ctx.check('every Kotlin round returned Ok/NothingToPush', [kr1, kr2, kLast].every(kOk), [kr1, kr2, kLast].map((r) => r.result));
          ctx.check("converged: same title on both (the fast device's edit, Opti outcome)", kt === pt && kt === 'LIVE-s3-K', { kotlin: kt, pwa: pt });
          // No ping-pong = the task CONTENT stays put. The rev may still move every round: that is the
          // order-only churn of Opti's order-sensitive nothingToPush ([DEFERRED_OPEN_QUESTION], REWORK).
          const rEnd = await remote();
          ctx.check('no ping-pong: after K3 and P3 the remote task is still K with the same stamp',
            kLast.e.remote.task?.title === 'LIVE-s3-K' && rEnd.task?.title === 'LIVE-s3-K' && rEnd.task?.updatedAt === kr2.e.remote.task?.updatedAt,
            { afterK3: kLast.e.remote, afterP3: rEnd });
          if (new Set([kr2.e.remote.rev, kLast.e.remote.rev, rEnd.rev]).size > 1) {
            ctx.note('[DEFERRED_OPEN_QUESTION] the rev still moves each round with the task unchanged: order-only churn (see REWORK deferred question)');
          }
          ctx.check('0 tombstones for the task', !tombstoneFor(k, id) && !tombstoneFor(ps, id));
          const future = notes.filter((x) => x.future);
          ctx.check('the future stamp was logged (PWA skew diag, past the 120 s threshold)', future.some((x) => x.id === id),
            { future: future.slice(0, 5), all: notes.length, pwaLastRev: pLast && pLast.revAfter });
        } finally {
          ctx.saveText('s3-trace.json', JSON.stringify(trace, null, 2));
        }
      });
    },
  },
  {
    n: 4, title: 'Cross-TZ daily', pass: 'done=true on both; no missedOn.',
    writes: ['one test daily "LIVE-s4" in /state.json'], deletes: [], user: [],
    async run(ctx) {
      const ks = kStatus(ctx);
      const cands = TZ_CANDIDATES.map((z) => ({ zone: z, offsetMin: zoneOffsetMin(z, ks.nowMs) }));
      const tz = pickCrossTz(ks.nowMs, ks.tzOffsetMin, cands);
      if (!tz) { ctx.check('found a zone with the same date and >= 2 h offset', false, { phone: ks.tzOffsetMin }); return; }
      ctx.note('PWA time zone: ' + tz.zone + ' (' + tz.gap + ' min from the phone)');
      let id;
      await ctx.pwa({ timezoneId: tz.zone }, async (p) => {
        id = await kAddAndShare(ctx, p, 'daily', 's4');
        ctx.k('complete', { id }); ctx.k('round');
        await p.round();
      });
      await ctx.pwa({ timezoneId: tz.zone }, async (p) => { // fresh boot: the PWA's own startDay runs in its zone
        await p.round(); ctx.k('round');
        const pt = taskById((await p.subset()).subset, id); const kt = taskById(kSub(ctx), id);
        ctx.check('done=true on the PWA', !!pt?.done, pt);
        ctx.check('done=true on Kotlin', !!kt?.done, kt);
        ctx.check('no missedOn', !pt?.missedOn && !kt?.missedOn, { pwa: pt?.missedOn, kotlin: kt?.missedOn });
      });
    },
  },
  {
    n: 5, title: 'Inbox injection + kill after claim', pass: 'cUp +1 exactly; inbox and claimed are empty after the next round.',
    writes: ['one test habit "LIVE-s5" in /state.json', '/inbox/<id>.json (injected by the harness)',
      '/inbox-claimed/<kotlinDevice>/<id>.json (moved there by Kotlin)'],
    deletes: ['/inbox-claimed/<kotlinDevice>/<id>.json (Kotlin finishes the record)'], user: [],
    async run(ctx) {
      let id, rec, kdev, before;
      await ctx.pwa({}, async (p) => {
        await p.call('holdInbox'); // this page must not claim the record itself
        id = await kAddAndShare(ctx, p, 'habit', 's5');
        kdev = kStatus(ctx).deviceId;
        before = taskById(kSub(ctx), id).cUp || 0; // read BEFORE the injection: no phone step in between
        rec = makeInboxRecord({ id: ctx.id('rec5').replace(/[^A-Za-z0-9-]/g, ''), habitId: id, dir: 1, ts: Date.now() });
        await p.inject(rec.path, rec.text);
      });
      await sleep(INBOX_WAIT_MS);
      const crash = ctx.kRaw('round-kill-after-claim');
      ctx.check('the app process died after the claim', crash.status === 'crashed', { status: crash.status });
      await ctx.pwa({}, async (p) => {
        await p.call('holdInbox');
        const claimed = await p.list('/inbox-claimed/' + kdev);
        ctx.check('the record sits in claimed after the crash', claimed.includes(rec.name), { claimed });
      });
      await sleep(INBOX_WAIT_MS); ctx.k('round'); // crash recovery: the claimed record is applied and finished
      await sleep(INBOX_WAIT_MS); ctx.k('round'); // a second inbox run must not apply it again
      const after = taskById(kSub(ctx), id).cUp || 0;
      ctx.check('cUp +1 exactly', after === before + 1, { before, after });
      await ctx.pwa({}, async (p) => {
        await p.call('holdInbox');
        const inbox = await p.list('/inbox'); const claimed = await p.list('/inbox-claimed/' + kdev);
        ctx.check('inbox and claimed are empty of the record', !inbox.includes(rec.name) && !claimed.includes(rec.name), { inbox, claimed });
      });
    },
  },
  {
    n: 6, title: 'Kotlin backup -> PWA', pass: 'The hash gate passes; tasks are equal.',
    writes: ['/questa-backups/daily-<kotlinDev6>-<slot>-<stamp>.json (Kotlin auto-backup)'],
    deletes: ['/questa-backups/daily-<kotlinDev6>-... (only Kotlin\'s own file in the same slot, by rotation)'], user: [],
    async run(ctx) {
      const r = ctx.k('backup-due', { tier: 'daily' });
      const path = uploadedPaths(r.calls).find((x) => /^\/questa-backups\/daily-/.test(x));
      ctx.check('Kotlin uploaded a daily backup', !!path, { uploads: uploadedPaths(r.calls) });
      if (!path) return;
      const k = kSub(ctx);
      await ctx.pwa({}, async (p) => {
        const file = await p.download(path);
        const saved = ctx.saveText('s6-backup.json', file.text);
        const tasks = await p.call('detokTasks', file.text);
        const hash = ctx.node(['tools/verify_with_pwa.mjs', saved]);
        const imp = ctx.node(['tools/verify_with_pwa.mjs', '--import', saved]);
        ctx.check('hash gate (Opti computeHashWith) passes', hash.code === 0, hash.tail);
        ctx.check('Opti importData accepts the file', imp.code === 0, imp.tail);
        const same = sameTasks(tasks, k.tasks);
        ctx.check('tasks are equal (backup vs Kotlin)', same.ok, same.diff);
      });
    },
  },
  {
    n: 7, title: 'PWA partial -> Kotlin', pass: 'Other sections are unchanged, locally and on remote.',
    writes: ['one test todo "LIVE-s7" in /state.json (PWA partial import, To-dos only)'], deletes: [], user: [],
    async run(ctx) {
      await ctx.pwa({}, async (p) => {
        await p.round(); ctx.k('round'); await p.round();
        const kBefore = kSub(ctx); const pBefore = (await p.subset()).subset;
        const id = ctx.id('s7'); const t = Date.now();
        const todo = { id, type: 'todo', title: 'LIVE-s7', notes: '', difficulty: 'easy', value: 0, done: false,
          checklist: [], tags: [], history: [], createdAt: t, updatedAt: t };
        await p.call('importPartial', { data: { tasks: [todo] }, keys: ['todos'] });
        await p.round(); ctx.k('round');
        const kAfter = kSub(ctx); const pAfter = (await p.subset()).subset;
        const keys = ['tasks', 'rewards', 'tags', 'char', 'charHistory'];
        const pick = (s) => { const o = sectionsExcept(s, ['todo']); return Object.fromEntries(keys.map((x) => [x, o[x]])); };
        ctx.check('the todo arrived on Kotlin', !!taskById(kAfter, id));
        ctx.check('other sections unchanged on Kotlin', stableStringify(pick(kBefore)) === stableStringify(pick(kAfter)), diffPaths(pick(kBefore), pick(kAfter)));
        ctx.check('other sections unchanged on remote (PWA after its push)', stableStringify(pick(pBefore)) === stableStringify(pick(pAfter)), diffPaths(pick(pBefore), pick(pAfter)));
      });
    },
  },
  {
    n: 8, title: 'Pause PWA, Kotlin cron', pass: 'hp is unchanged; 0 missedOn; pausedDays equal.',
    writes: ['prefs.paused / pausedDays in /state.json'], deletes: [],
    user: ['Part a today. Part b after the next local midnight (the real day change runs the Kotlin cron), or right away with --fake-day 1 (dev build app clock offset; the phone clock is not changed).',
      'After a --fake-day run the test Dropbox holds data stamped up to ~1 day ahead; later normal runs print a WARNING until that time passes (the harness keeps it in .build/live-runs/state/fake-day.json).'],
    parts: ['a', 'b'],
    async run(ctx) {
      if (ctx.part === 'a') {
        await ctx.pwa({}, async (p) => { await p.call('pause', true); await p.round(); ctx.k('round'); });
        const k = kSub(ctx);
        ctx.saveJson('s8-a.json', { hp: k.char?.hp, missedOn: Object.fromEntries((k.tasks || []).map((t) => [t.id, t.missedOn || null])), atMs: Date.now() });
        ctx.check('Kotlin sees paused=true', !!k.pause?.paused, k.pause);
        ctx.note('Now wait until after local midnight, then run: --scenario 8 --part b');
        return;
      }
      const a = ctx.loadJson('s8-a.json');
      // ctx.now() = real time, or the --fake-day shifted time (WP-8.12-T1; same shift as the phone app).
      if (new Date(a.atMs).toDateString() === new Date(ctx.now()).toDateString()) throw new Error('part b must run on a later day than part a (or add --fake-day 1)');
      ctx.k('round');
      const k = kSub(ctx);
      await ctx.pwa({}, async (p) => {
        await p.round(); ctx.k('round');
        const k2 = kSub(ctx); const ps = (await p.subset()).subset;
        const newMissed = (k2.tasks || []).filter((t) => t.missedOn && t.missedOn !== a.missedOn[t.id]);
        ctx.check('hp unchanged (Kotlin cron while paused)', k.char?.hp === a.hp, { before: a.hp, after: k.char?.hp });
        ctx.check('0 new missedOn', newMissed.length === 0, newMissed.map((t) => t.id));
        ctx.check('pausedDays equal', stableStringify(k2.pause?.pausedDays || []) === stableStringify(ps.pause?.pausedDays || []),
          { kotlin: k2.pause?.pausedDays, pwa: ps.pause?.pausedDays });
        await p.call('pause', false); await p.round(); ctx.k('round'); // unpause again
      });
    },
  },
  {
    n: 9, title: 'Taps L5/R3', pass: 'cUp = 8 on both; xp and gold are the sums.',
    writes: ['one test habit "LIVE-s9" in /state.json; its tap events in /events'], deletes: [], user: [],
    async run(ctx) {
      await ctx.pwa({}, async (p) => {
        const id = await kAddAndShare(ctx, p, 'habit', 's9');
        ctx.k('round'); await p.round();
        const c0 = kSub(ctx).char; const p0 = await p.call('charNow');
        ctx.k('tap', { id, dir: 1, n: 5 }); const c1 = kSub(ctx).char;
        await p.call('tap', { id, dir: 1, n: 3 }); const p1 = await p.call('charNow');
        ctx.k('round'); await p.round(); ctx.k('round');
        const k = kSub(ctx); const ps = (await p.subset()).subset;
        const dg = (c1.gold - c0.gold) + (p1.gold - p0.gold);
        ctx.check('cUp = 8 on Kotlin', taskById(k, id)?.cUp === 8, taskById(k, id)?.cUp);
        ctx.check('cUp = 8 on the PWA', taskById(ps, id)?.cUp === 8, taskById(ps, id)?.cUp);
        // Summed on Opti's TOTAL xp (sync.js:1667-1688), so a level-up on either side is checked too.
        const want = expectedCharXp(c0, c1, p0, p1);
        ctx.check('xp is the sum (Opti total-xp formula, level-ups included)', charXpMatches(k.char, want) && charXpMatches(ps.char, want),
          { c0, c1, p0, p1, want, kotlin: { lvl: k.char.lvl, xp: k.char.xp }, pwa: { lvl: ps.char.lvl, xp: ps.char.xp } });
        ctx.check('gold is the sum', Math.abs(k.char.gold - (c0.gold + dg)) < 1e-6 && Math.abs(ps.char.gold - (c0.gold + dg)) < 1e-6, { c0: c0.gold, dg, kotlin: k.char.gold, pwa: ps.char.gold });
      });
    },
  },
  {
    n: 10, title: 'Legacy upgrade vs a populated remote (option B: never linked, then Pull or Push)',
    pass: 'Room 7; row counts equal; pre-switch export imports in Opti; APP_KEY_OF_DATA=9bmdh; no EVT keys; NeedsFirstConnectChoice; (a) Kotlin = remote / (b) remote = Kotlin; base rev = remote rev, 0 merges.',
    // Option B (orchestrator decision): the legacy app is NEVER linked and never syncs; no path in
    // this harness links the legacy app key. 10L/10D, the Unlink (M1) path and real legacy EVT_* keys are
    // [DEFERRED_OPEN_QUESTION] (WP-8.12_NOTES.md).
    writes: ['(b) Push only: /state.json is replaced by the legacy G1 data', '/events/<newDevice>-<yyyymm>.json'],
    deletes: [],
    user: ['Uninstall Questa dev (wipes its data).', 'Install the legacy APK with the file manager.', 'Import G1 in the legacy app.',
      'Delete one task in the legacy app. Do NOT link Dropbox in the legacy app (option B).', 'Install the Stage 8 dev APK with the file manager.', 'Connect in Questa dev.'],
    parts: ['handlers-legacy', 'legacy-counts', 'handlers-dev', 'after-upgrade', 'first-connect', 'converge'],
    async run(ctx) {
      const part = ctx.part;
      if (part === 'converge') {
        // 2026-09-25 (first live push run): (b) compares Kotlin with the PWA right after the PWA's
        // round, but that round unions the PWA's own older tombstones into the remote (Opti merges
        // deletions as a union), and Kotlin only sees them on its next round. So: one more Kotlin
        // round, one more PWA round, then compare.
        await ctx.pwa({}, async (p) => {
          await p.round();
          ctx.k('round');
          await p.round();
          const k = kSub(ctx); const ps = await p.subset();
          ctx.check('Kotlin equals the PWA after a full round trip (content by id)', stableAllById(k) === stableAllById(ps.subset),
            { order: idOrderDiff(k, ps.subset), diff: diffPaths(JSON.parse(stableAllById(k)), JSON.parse(stableAllById(ps.subset))),
              deletions: { kotlin: (k.deletions || []).length, pwa: (ps.subset.deletions || []).length }, lastCron: { kotlin: k.lastCron, pwa: ps.subset.lastCron } });
        });
        return;
      }
      if (part === 'handlers-legacy' || part === 'handlers-dev') {
        const scheme = part === 'handlers-legacy' ? 'questa' : 'questa-dev';
        const out = ctx.phone.handlers(scheme);
        // pm query-activities prints packageName for the activity AND its application block.
        const pkgs = [...new Set([...out.matchAll(/packageName=([\w.]+)/g)].map((m) => m[1]))];
        const want = part === 'handlers-legacy' ? 'com.example.dev' : 'io.github.palo007.questa.dev'; // P6b: legacy fixture keeps the old id
        ctx.check(scheme + '://oauth -> exactly ' + want, pkgs.length === 1 && pkgs[0] === want, { pkgs });
        return;
      }
      if (part === 'legacy-counts') {
        const counts = await ctx.legacyCounts();
        ctx.saveJson('s10-legacy-counts.json', counts);
        ctx.check('legacy row counts read (character row present)', (counts?.character || 0) >= 1, counts);
        return;
      }
      if (part === 'after-upgrade') {
        const s = kStatus(ctx);
        const legacy = ctx.loadJson('s10-legacy-counts.json');
        const sameCounts = Object.entries(legacy).filter(([t, c]) => s.rowCounts[t] !== c);
        ctx.check('Room version = 7', s.roomVersion === 7, s.roomVersion);
        ctx.check('row counts equal the seeded counts', sameCounts.length === 0, { differ: sameCounts, now: s.rowCounts });
        // The app key is supplied at build time and never committed: pin it exactly when the
        // caller passes DROPBOX_APP_KEY, else assert only that it is a well-formed key.
        const expectedAppKey = process.env.DROPBOX_APP_KEY;
        const appKeyOk = expectedAppKey
            ? s.appKeyOfData === expectedAppKey
            : /^[a-z0-9]{15,}$/.test(String(s.appKeyOfData || ''));
        ctx.check('APP_KEY_OF_DATA is a well-formed app key', appKeyOk, s.appKeyOfData);
        const evt = s.prefKeyNames.filter((n) => /^(evt_file_revs|evt_last_upload_ts|evt_last_pull_at|EVT_)/i.test(n));
        // Option B: the legacy app never synced, so there were no EVT_* keys to clear. This only checks
        // that none appear; the real "cleared" case is [DEFERRED_OPEN_QUESTION].
        ctx.check('no legacy EVT keys present', evt.length === 0, evt);
        ctx.check('files/pre-appkey-switch-*.json exists', s.preSwitchFiles.length > 0, s.preSwitchFiles);
        if (s.preSwitchFiles.length) {
          const saved = ctx.saveText('s10-' + s.preSwitchFiles[0], ctx.phone.readFile('files/' + s.preSwitchFiles[0]));
          const imp = ctx.node(['tools/verify_with_pwa.mjs', '--import', saved]);
          ctx.check('the pre-switch export passes Opti importData', imp.code === 0, imp.tail);
        }
        ctx.note('Now relink: Questa dev -> Settings -> Dropbox -> Connect (key 9bmdh). Then run --part first-connect --choice pull|push');
        return;
      }
      // first-connect
      const first = ctx.k('round').result;
      ctx.check('the first round returns NeedsFirstConnectChoice', /NeedsFirstConnectChoice/.test(first), first);
      const fc = ctx.k('first-connect', { choice: ctx.choice }).result;
      ctx.check('first connect (' + ctx.choice + ') succeeded', /Success|Ok/.test(fc), fc);
      // 2026-09-25 (first live pull run): opening the PWA page runs the PWA's own sync, and every
      // phone step is a new app process whose boot round runs BEFORE LiveInteropTest's recorder.
      // When the two devices hold the same content in two orders, each such round re-uploads
      // (order-only churn, REWORK deferred question), so a rev read before a later Kotlin step is
      // stale and recorded calls miss the upload. So: judge revs by the Kotlin base BEFORE/AFTER
      // the round and the remote read AFTER the last Kotlin step; compare content by id; a pure
      // order difference is a note, not a failure.
      await ctx.pwa({}, async (p) => {
        await p.call('holdInbox');
        const sA = kStatus(ctx);
        const r2 = ctx.k('round');
        const sB = kStatus(ctx);
        const remoteAfter = await p.download('/state.json');
        let remoteState = null; try { remoteState = JSON.parse(remoteAfter.text).state; } catch { /* corrupt */ }
        const kB = kSub(ctx);
        const order = idOrderDiff(kB, remoteState);
        const revs = { baseBefore: sA.baseRev, baseAfter: sB.baseRev, remoteAfterLastKotlinStep: remoteAfter.rev, recordedUploads: stateUploads(r2.calls) };
        if (!order.length) {
          ctx.check('base rev = remote rev (read after the last Kotlin step)', sB.baseRev === remoteAfter.rev, revs);
          ctx.check('next round: 0 merges (Kotlin base rev unchanged across the round, no recorded upload)',
            sA.baseRev === sB.baseRev && stateUploads(r2.calls).length === 0, revs);
        } else {
          ctx.note('[DEFERRED_OPEN_QUESTION] Kotlin and the remote hold the same ids in a different order (' + order.join(', ')
            + '): order-only churn, so the strict rev checks are replaced by content checks. ' + JSON.stringify(revs));
        }
        ctx.check('Kotlin equals the remote read after the last Kotlin step (content by id)', !!remoteState && stableAllById(kB) === stableAllById(remoteState),
          { order, diff: diffPaths(sortById(kB), sortById(remoteState)) });
        await p.round();
        const k = kSub(ctx); const ps = await p.subset();
        const same = stableAllById(k) === stableAllById(ps.subset);
        ctx.check(ctx.choice === 'pull' ? '(a) Kotlin equals remote (content by id)' : '(b) remote equals Kotlin (content by id)', same,
          { order: idOrderDiff(k, ps.subset), diff: diffPaths(sortById(k), sortById(ps.subset)) });
        if (same && stableStringify(k) !== ps.stable) ctx.note('[DEFERRED_OPEN_QUESTION] same content, different array order (Opti local-order merge)');
      });
    },
  },
  {
    n: 11, title: 'Delete vs an earlier edit', pass: "Absent on both; tombstone at >= the edit's updatedAt.",
    writes: ['one test todo "LIVE-s11" in /state.json, then its tombstone (deletions[])'], deletes: [], user: [],
    async run(ctx) {
      await ctx.pwa({}, async (p) => {
        const id = await kAddAndShare(ctx, p, 'todo', 's11');
        const editAt = await p.call('editTitle', { id, title: 'LIVE-s11-P' });
        await p.round();
        await sleep(1500);
        ctx.k('delete-task', { id }); // Kotlin has not pulled the edit: the delete is concurrent and later
        ctx.k('round'); await p.round(); ctx.k('round');
        const k = kSub(ctx); const ps = (await p.subset()).subset;
        ctx.check('absent on Kotlin', !taskById(k, id));
        ctx.check('absent on the PWA', !taskById(ps, id));
        const tk = tombstoneFor(k, id), tp = tombstoneFor(ps, id);
        ctx.check("tombstone at >= the edit's updatedAt", !!tk && !!tp && tk.at >= editAt && tp.at >= editAt, { editAt, kotlin: tk, pwa: tp });
      });
    },
  },
  {
    n: 12, title: 'Events both directions', pass: 'Visible within 2 rounds; uid counts are equal.',
    writes: ['one test habit "LIVE-s12"; one tap event each side in /events'], deletes: [], user: [],
    async run(ctx) {
      const t0 = Date.now();
      await ctx.pwa({}, async (p) => {
        const id = await kAddAndShare(ctx, p, 'habit', 's12');
        ctx.k('tap', { id, dir: 1, n: 1 }); await p.call('tap', { id, dir: 1, n: 1 });
        for (let i = 0; i < 2; i++) { await sleep(65000); ctx.k('round'); await p.round(); } // events pull runs at most once a minute
        const kEv = kStatus(ctx, t0).events.filter((e) => e.kind === 'habitTap' && e.taskId === id);
        const pEv = await p.call('events', { from: t0, kind: 'habitTap', taskId: id });
        const kU = new Set(kEv.map((e) => e.uid)), pU = new Set(pEv.map((e) => e.uid));
        const both = [...kU].every((u) => pU.has(u)) && [...pU].every((u) => kU.has(u));
        ctx.check('each side sees the other tap within 2 rounds', both && kU.size === 2, { kotlin: [...kU], pwa: [...pU] });
        ctx.check('uid counts are equal', kU.size === pU.size, { kotlin: kU.size, pwa: pU.size });
      });
    },
  },
  {
    n: 13, title: 'Backup rotation, 2 devices', pass: '<= 10/7/4/4 files per device per tier; 0 foreign deletions.',
    writes: ['/questa-backups/<tier>-<kotlinDev6>-*.json', '/questa-backups/<tier>-<pwaDev6>-*.json'],
    deletes: ["each device's own oldest file in a reused slot (Opti/Kotlin rotation); never another device's file"], user: [],
    async run(ctx) {
      const fires = { fourHour: 11, daily: 8, weekly: 5, monthly: 5 };
      await ctx.pwa({}, async (p) => {
        const kdev6 = kStatus(ctx).deviceId.slice(-6); const pdev6 = (await p.status()).deviceId.slice(-6);
        const before = await p.list('/questa-backups');
        for (const [tier, n] of Object.entries(fires)) for (let i = 0; i < n; i++) {
          ctx.k('backup-due', { tier }); await p.call('backupDue', tier); await sleep(1100); // distinct stamps
        }
        const after = await p.list('/questa-backups');
        const r = checkBackupRotation(before, after, [kdev6, pdev6]);
        ctx.check('<= 10/7/4/4 per device per tier', r.over.length === 0, { over: r.over, count: r.count });
        ctx.check('0 foreign deletions', r.foreignDeletions.length === 0, r.foreignDeletions);
        ctx.check('both devices wrote backups', after.some((n) => parseBackupName(n)?.dev === kdev6) && after.some((n) => parseBackupName(n)?.dev === pdev6));
      });
    },
  },
  {
    n: 14, title: 'Bubblewrap parsers read Kotlin meta', pass: 'The lists are non-null and equal.',
    writes: ['one test habit "LIVE-s14"', '/inbox-meta/habits.json and /inbox-meta/reminders.json (written by Kotlin)'], deletes: [],
    user: ['PARTIAL: the Bubblewrap parser run needs the Bubblewrap repo (NOTES Q6). The harness saves the bytes.'],
    async run(ctx) {
      await ctx.pwa({}, async (p) => {
        await p.call('holdInbox');
        const id = ctx.id('s14');
        ctx.k('add-task', { id, type: 'habit', title: 'LIVE-s14' });
        const r = ctx.k('round');
        const wrote = uploadedPaths(r.calls).filter((x) => x.startsWith('/inbox-meta/'));
        ctx.check('Kotlin wrote the meta files', wrote.includes('/inbox-meta/habits.json'), wrote);
        const hm = await p.download('/inbox-meta/habits.json'); const rm = await p.download('/inbox-meta/reminders.json');
        ctx.saveText('s14-habits.json', hm ? hm.text : 'null'); ctx.saveText('s14-reminders.json', rm ? rm.text : 'null');
        let h = null; try { h = JSON.parse(hm.text); } catch { /* checked below */ }
        const list = h && (Array.isArray(h) ? h : h.habits);
        ctx.check('habits list is non-null', Array.isArray(list), typeof h);
        const ids = (list || []).map((x) => x && (x.id || x.habitId)).sort();
        const kIds = habits(kSub(ctx)).map((t) => t.id).sort();
        ctx.check('the list holds the new habit', ids.includes(id), { ids });
        ctx.note('Kotlin habit ids: ' + kIds.length + ', meta ids: ' + ids.length + ' (the meta may filter; compare with the Bubblewrap parser, NOTES Q6)');
      });
    },
  },
  {
    n: 15, title: 'Force push / pull vs PWA', pass: 'States are equal; 0 conflict retries.',
    writes: ['/state.json overwritten twice (Kotlin force push, then PWA force push)'], deletes: [], user: [],
    async run(ctx) {
      await ctx.pwa({}, async (p) => {
        const a = ctx.k('force-push');
        ctx.check('Kotlin force push ok, one upload', !/Error/.test(a.result) && stateUploads(a.calls).length === 1, { r: a.result, n: stateUploads(a.calls).length });
        const pr = await p.call('forcePull');
        const s1 = await p.subset(); const k1 = kSub(ctx);
        ctx.check('PWA force pull: states equal', s1.stable === stableStringify(k1), diffPaths(k1, s1.subset));
        const pp = await p.call('forcePush');
        ctx.check('PWA force push/pull: no error', !pr.lastError && !pp.lastError, { pr, pp });
        const b = ctx.k('force-pull');
        const s2 = await p.subset(); const k2 = kSub(ctx);
        ctx.check('Kotlin force pull: states equal', s2.stable === stableStringify(k2), diffPaths(k2, s2.subset));
        ctx.check('0 conflict retries (Kotlin)', !/Error/.test(b.result), b.result);
      });
    },
  },
  {
    n: 16, title: 'Late inbox record (ts = yesterday)', pass: 'Kotlin counters unchanged; 1 habitTap event with late:true and scored:false; the file is finished.',
    writes: ['one test habit "LIVE-s16"', '/inbox/<id>.json (injected)', '/inbox-claimed/<kotlinDevice>/<id>.json'],
    deletes: ['/inbox-claimed/<kotlinDevice>/<id>.json (finished)'], user: [],
    async run(ctx) {
      const t0 = Date.now();
      let id, rec, kdev, before;
      await ctx.pwa({}, async (p) => {
        await p.call('holdInbox');
        id = await kAddAndShare(ctx, p, 'habit', 's16');
        kdev = kStatus(ctx).deviceId;
        before = taskById(kSub(ctx), id);
        rec = makeInboxRecord({ id: ctx.id('rec16').replace(/[^A-Za-z0-9-]/g, ''), habitId: id, dir: 1, ts: Date.now() - DAY });
        await p.inject(rec.path, rec.text);
      });
      await sleep(INBOX_WAIT_MS);
      ctx.k('round');
      const after = taskById(kSub(ctx), id);
      const ev = kStatus(ctx, t0 - 2 * DAY).events.filter((e) => e.uid === 'inbox-' + JSON.parse(rec.text).id);
      ctx.check('Kotlin counters unchanged', after.cUp === before.cUp && after.cDown === before.cDown, { before, after });
      ctx.check('exactly 1 habitTap event, late:true, scored:false', ev.length === 1 && ev[0].kind === 'habitTap' &&
        ev[0].payload?.late === true && ev[0].payload?.scored === false, ev);
      await ctx.pwa({}, async (p) => {
        await p.call('holdInbox');
        const inbox = await p.list('/inbox'); const claimed = await p.list('/inbox-claimed/' + kdev);
        ctx.check('the file is finished', !inbox.includes(rec.name) && !claimed.includes(rec.name), { inbox, claimed });
      });
    },
  },
  {
    n: 17, title: 'Disconnect -> relaunch -> reconnect the same account', pass: 'No choice demanded; EVT_FILE_COUNTS unchanged; the first round merges with the stored base (base rev used as update rev).',
    writes: ['one test todo "LIVE-s17" in /state.json'], deletes: [],
    user: ['When asked: in Questa dev tap Settings -> Dropbox -> Connect, log in with the SAME account, tap Allow.'],
    async run(ctx) {
      const s0 = kStatus(ctx);
      ctx.k('disconnect');
      ctx.phone.forceStop(); ctx.phone.launch();
      // Before the reconnect: the disconnect + relaunch kept the base and the event counts (D8).
      const sD = kStatus(ctx);
      ctx.check('EVT_FILE_COUNTS unchanged by disconnect + relaunch', sD.evtFileCounts === s0.evtFileCounts, { before: s0.evtFileCounts, after: sD.evtFileCounts });
      ctx.check('base kept by disconnect + relaunch', sD.baseRev === s0.baseRev, { before: s0.baseRev, after: sD.baseRev });
      // sync_last_error is not a public status value, but its KEY name is listed: while NOT_LINKED
      // every boot round is refused by the interlock and saves that message (REWORK R-3).
      const hasErr = (st) => (st.prefKeyNames || []).includes('sync_last_error');
      if (hasErr(sD)) ctx.note('while disconnected a sync error is stored (sync_last_error present): see REWORK R-3');
      await ctx.ask('On the phone: Questa dev -> Settings -> Dropbox -> Connect Dropbox. Log in with the SAME account and tap Allow. Do NOT tap Sync. Press Enter here when it shows "Linked".');
      // 2026-09-25: every `am instrument` is a new app process, and LiveInteropTest installs its call
      // recorder only AFTER StartupBarrier + BootGate, i.e. after the app's own boot round. So the
      // post-reconnect upload is usually NOT in any step's calls (the first live run saw up: []).
      // Collect the calls of EVERY step after the reconnect, and judge by the revs as well: in s17
      // Kotlin is the only writer (no PWA page is open), so a new rev can only be Kotlin's upload.
      // Each recorded upload is paired with the base the app had stored right before that step.
      const revChain = [s0.baseRev];
      const seeRev = (st) => { if (st.baseRev && st.baseRev !== revChain[revChain.length - 1]) revChain.push(st.baseRev); };
      const up = [];
      const kStep = (step, extra) => {
        const baseBefore = revChain[revChain.length - 1];
        const o = ctx.k(step, extra);
        for (const u of stateUploads(o.calls)) up.push({ ...u, step, baseBefore });
        return o;
      };
      const kSt = () => { const st = kStep('status').result; seeRev(st); return st; };
      let sR = kSt();
      for (let i = 0; i < 6; i++) {
        await sleep(10000);
        const next = kSt();
        const stable = next.baseRev === sR.baseRev && next.lastRev === sR.lastRev;
        sR = next;
        if (stable) break;
      }
      ctx.check('LINKED again', sR.authState === 'LINKED', sR.authState);
      if (sR.baseRev !== s0.baseRev) ctx.note("the app's own round ran after the reconnect (base " + s0.baseRev + ' -> ' + sR.baseRev + ')');
      ctx.check('the app kept a base across the reconnect', !!sR.baseRev, sR.baseRev);
      const tid = ctx.id('s17');
      kStep('add-task', { id: tid, type: 'todo', title: 'LIVE-s17' });
      const sPre = kSt(); // the base the next round must use
      const r = kStep('round');
      const sEnd = kSt();
      ctx.check('no first-connect choice demanded', !/NeedsFirstConnectChoice/.test(r.result), r.result);
      const kHasTask = !!taskById(kSub(ctx), tid);
      const detail = { recordedUploads: up, revChain, storedBase: s0.baseRev, finalBase: sEnd.baseRev, lastRound: r.result };
      if (up.length) {
        ctx.check('the first post-reconnect upload uses the stored base rev as update rev (sPre.baseRev when it is the round step)', up[0].updateRev === up[0].baseBefore, detail);
        ctx.check('0 conflict retries (no 409 on any recorded state.json upload)', up.every((u) => u.status === 200), detail);
      } else {
        // Not recorded: Dropbox accepts an update only against the CURRENT rev, and the current rev
        // was the stored base (sole writer). One hop from the stored base to a rev that the next
        // recorded round finds equal to its own merge (NothingToPush) = Kotlin's upload from the base.
        ctx.check('the post-reconnect upload came from the stored base (rev chain: stored base -> Kotlin revs; next round NothingToPush; task present)',
          revChain.length >= 2 && revChain[0] === s0.baseRev && /NothingToPush/.test(String(r.result)) && kHasTask, detail);
        ctx.note('0 conflict retries: NOT observable in this run (the upload ran in a boot round before the recorder); a 409 retry would leave the same rev chain');
      }
      ctx.check('no sync error stored after the successful round', !hasErr(sEnd), { prefKeyNames: sEnd.prefKeyNames });
    },
  },
];

// Test-environment tools (not part of the 17). 2026-09-25: after a --fake-day run the test PWA
// keeps lastCron = tomorrow (Opti gives a future remote cron no authority, so Kotlin never takes it)
// and pause.at / cResetOn in the future. 99 moves every future value in the TEST PWA's data back to
// today/now, force-pushes it, force-pulls it into Kotlin and checks both sides are clean and equal.
// It replaces /state.json (the test folder only).
export const TOOLS = [
  {
    n: 99, phoneAs: 15, title: 'Repair fake-day dates (test environment only)', pass: 'No future values on either side; states equal.',
    writes: ['/state.json overwritten (PWA force push)'], deletes: [], user: [],
    async run(ctx) {
      await ctx.pwa({}, async (p) => {
        const fix = await p.call('repairFakeDay');
        ctx.note('PWA values moved back: ' + JSON.stringify(fix));
        if (fix.count || !ctx.part) { // rerun-safe: push again only when something was moved (or on a plain run)
          const pp = await p.call('forcePush');
          ctx.check('PWA force push: no error', !pp.lastError, pp);
        }
        const b = ctx.k('force-pull');
        ctx.check('Kotlin force pull ok', !/Error/.test(b.result), b.result);
        await p.round(); ctx.k('round'); await p.round();
        const k = kSub(ctx); const ps = (await p.subset()).subset;
        const lim = Date.now() + 120000; const today = (await p.call('repairFakeDay', { dryRun: true })).today;
        const future = (o) => { const hits = []; const walk = (v, path) => { if (Array.isArray(v)) v.forEach((x, i) => walk(x, path + '[' + i + ']'));
          else if (v && typeof v === 'object') for (const key of Object.keys(v)) walk(v[key], path + '.' + key);
          else if (typeof v === 'number' && ((v > lim && v < 4e12) || (v > today && v < 30000000))) hits.push(path); }; walk(o, '$'); return hits; };
        const kf = future({ ...k, tasks: (k.tasks || []).map((t) => ({ ...t, history: undefined })) });
        const pf = future({ ...ps, tasks: (ps.tasks || []).map((t) => ({ ...t, history: undefined })) });
        ctx.check('no future values on Kotlin', kf.length === 0, kf.slice(0, 20));
        ctx.check('no future values on the PWA', pf.length === 0, pf.slice(0, 20));
        ctx.check('Kotlin equals the PWA (content by id)', stableAllById(k) === stableAllById(ps),
          { diff: diffPaths(JSON.parse(stableAllById(k)), JSON.parse(stableAllById(ps))) });
      });
    },
  },
];

export function scenario(n) {
  const s = SCENARIOS.find((x) => x.n === n) || TOOLS.find((x) => x.n === n);
  if (!s) throw new Error('no scenario ' + n);
  return s;
}
