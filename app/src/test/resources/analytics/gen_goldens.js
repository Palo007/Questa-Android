// WP-8.20 (PX-110): golden values for the Analytics formulas, computed with Opti's REAL code.
//
// Usage (from the repo root; Opti is read-only, only `git show` is used). Opti is the original
// web app -- see $OPTI_ROOT, or a sibling checkout named `Opti`:
//   git -C "${OPTI_ROOT:-../Opti}" show 7d5d4f3:app.js > "$TMPDIR/opti_app.js"
//   TZ=Europe/Bratislava node app/src/test/resources/analytics/gen_goldens.js "$TMPDIR/opti_app.js"
//
// It writes fixture.json (Opti-shaped tasks/tags/rewards/metrics/views) and goldens.json next to
// this file. AnalyticsGoldenTest parses the same fixture through SerializationEngine and checks
// the Kotlin AnalyticsCalc against goldens.json. The function bodies are sliced from app.js at
// 7d5d4f3 by line range (the commit is immutable, so the ranges cannot drift).
'use strict';
const fs = require('fs');
const path = require('path');
const vm = require('vm');

process.env.TZ = 'Europe/Bratislava'; // node honours a runtime TZ change before the first Date use
if (new Date(2026, 0, 1).getTimezoneOffset() !== -60) { console.error('TZ not applied'); process.exit(2); }
const src = fs.readFileSync(process.argv[2], 'utf8').split('\n');
const lines = (a, b) => src.slice(a - 1, b).join('\n');
const code = [
  lines(100, 101),   // isDailyDueOn, isDailyDueToday
  lines(2207, 2207), // clamp
  lines(3255, 3257), // ensureTags, tagById, taskTags
  lines(3323, 3324), // createdMs, updatedMs
  lines(3430, 3704), // DAY .. anCompareSeries, fmtDate, typeIcon
  lines(3736, 3814), // svgSpark, heatColor, METRIC_COLORS, svgCompare, svgBars
  lines(4118, 4350), // V_* .. anTagSummaryBody (incl. renderView)
  lines(4409, 4411), // viewMetaLabel
  lines(5069, 5088), // anHeatmapHTML
  lines(8429, 8429), // esc
].join('\n');

const NOW = new Date(2026, 8, 20, 12, 0, 0).getTime(); // 2026-09-20 12:00 local
const D = (y, m, d, h = 12, mi = 0) => new Date(y, m - 1, d, h, mi).getTime();

// ---- fixture (Opti S shape) -------------------------------------------------------------------
const tags = [
  { id: 'tg1', name: 'Fitness', color: '#48b677' },
  { id: 'tg2', name: 'Mind', color: '#50b5e9' },
  { id: 'tg3', name: 'Empty', color: '#f74e52' },
];
const hist = [];
const tasks = [
  { id: 'h1', type: 'habit', title: 'Kliky 20', tags: ['tg1'], createdAt: D(2025, 12, 1), updatedAt: D(2026, 9, 1), history: [
    { date: D(2025, 12, 2), value: 0, scoredUp: 2, scoredDown: 0 },                       // old import: no reps -> 20*2
    { date: D(2026, 3, 28, 23, 30), value: 1.2, scoredUp: 1, scoredDown: 0, reps: 20, repCounted: true, scored: true },
    { date: D(2026, 3, 29, 9, 0), value: 1.5, scoredUp: 3, scoredDown: 0, reps: 60, repCounted: true, scored: true }, // DST day
    { date: D(2026, 8, 25), value: 2, scoredUp: 0, scoredDown: 0, reps: 50, repCounted: true, scored: false },  // rep sheet
    { date: D(2026, 8, 26), value: 2, scoredUp: 0, scoredDown: 0, reps: -20, repCounted: true, scored: false }, // removal
    { date: D(2026, 9, 1, 7, 15), value: 2.2, scoredUp: 2, scoredDown: 1, reps: 40, repCounted: true, scored: true },
    { date: D(2026, 9, 18), value: 2.4, scoredUp: 1, scoredDown: 0, scored: true },          // no reps key -> 20
    { date: D(2026, 9, 20, 8), value: 2.5, scoredUp: 1, scoredDown: 0, reps: 20, repCounted: true, scored: true },
  ] },
  { id: 'h2', type: 'habit', title: 'Zdvihy x5', repsPerTap: 5, tags: [], createdAt: D(2026, 2, 1), updatedAt: D(2026, 2, 1), history: [
    { date: D(2026, 2, 3), value: 0, scoredUp: 4, scoredDown: 0 },
    { date: D(2026, 7, 14), value: 0.5, scoredUp: 2, scoredDown: 0, reps: 10, repCounted: true, scored: true },
    { date: D(2026, 9, 12), value: 0.8, scoredUp: 0, scoredDown: 2, reps: 0, repCounted: false, scored: true },
  ] },
  { id: 'h3', type: 'habit', title: 'Read book', tags: ['tg2'], createdAt: D(2026, 5, 5), updatedAt: D(2026, 9, 10), history: [
    { date: D(2026, 5, 6), value: 0, scoredUp: 1, scoredDown: 0, reps: 1, repCounted: true, scored: true },
    { date: D(2026, 9, 15), value: 1, scoredUp: 3, scoredDown: 0, reps: 3, repCounted: true, scored: true },
  ] },
  { id: 'd1', type: 'daily', title: 'Morning run', tags: ['tg1'], repeat: [false, true, true, true, true, true, false], streak: 4, done: false,
    createdAt: D(2026, 9, 1, 6), updatedAt: D(2026, 9, 5), history: [
    { date: D(2026, 9, 1, 20), value: 1, completed: true },
    { date: D(2026, 9, 2, 20), value: 1, completed: true },
    { date: D(2026, 9, 3, 20), value: 0, completed: false },
    { date: D(2026, 9, 7, 20), value: 1, completed: true },
    { date: D(2026, 9, 8, 20), value: 1, completed: true },
    { date: D(2026, 9, 9, 20), value: 1, completed: true },
    { date: D(2026, 9, 10, 20), value: 1, completed: true },
    { date: D(2026, 9, 17, 20), value: 0, completed: false },
  ] },
  { id: 'd2', type: 'daily', title: 'Meditate', tags: ['tg1', 'tg2'], streak: 10, done: true,
    createdAt: D(2026, 9, 10, 6), updatedAt: D(2026, 9, 10, 6), history: [
    { date: D(2026, 9, 11, 21), value: 1, completed: true },
    { date: D(2026, 9, 12, 21), value: 1, completed: true },
    { date: D(2026, 9, 19, 21), value: 1, completed: true },
  ] },
  { id: 't1', type: 'todo', title: 'Buy milk', tags: [], done: false, createdAt: D(2026, 9, 14), updatedAt: D(2026, 9, 16), history: [] },
  { id: 't2', type: 'todo', title: 'File taxes', tags: ['tg2'], done: true, completedAt: D(2026, 9, 10, 10),
    createdAt: D(2026, 8, 1), updatedAt: D(2026, 8, 1), history: [{ date: D(2026, 9, 10, 10), value: 0, completed: true }] },
  { id: 't3', type: 'todo', title: 'Old no-date', tags: [], done: true, completedAt: D(2026, 6, 20), history: [] },
];
const rewards = [
  { id: 'r1', title: 'Coffee', cost: 10, createdAt: D(2026, 9, 2), updatedAt: D(2026, 9, 19) },
  { id: 'r2', title: 'Movie', cost: 50, createdAt: D(2026, 3, 1), updatedAt: D(2026, 3, 1) },
];
const metrics = [
  { id: 'm1', name: 'Kliky', keyword: 'klik', createdAt: 1, updatedAt: 1 },
  { id: 'm2', name: 'Zdvihy', keyword: 'zdvih', createdAt: 2, updatedAt: 2 },
  { id: 'm3', name: 'Exact', keyword: '', exact: true, habits: [{ id: 'h1', reps: null }, { id: 'h3', reps: 2.5 }], createdAt: 3, updatedAt: 3 },
  { id: 'm4', name: 'ExactEmpty', keyword: 'read', exact: true, habits: [], createdAt: 4, updatedAt: 4 },
];
const V = (id, source, group, chart, extra = {}) => Object.assign({ id, name: id, source, group, chart, tags: [], types: [] }, extra);
const views = [
  V('v01', 'overview', 'day', 'list'),
  V('v02', 'tagsummary', 'tag', 'bar'),
  V('v03', 'completed', 'day', 'line'),
  V('v04', 'completed', 'week', 'bar'),
  V('v05', 'created', 'tag', 'bar'),
  V('v06', 'updated', 'type', 'list'),
  V('v07', 'incomplete', 'type', 'bar'),
  V('v08', 'incomplete', 'day', 'line'),
  V('v09', 'streaks', 'day', 'line'),
  V('v10', 'streaks', 'tag', 'list'),
  V('v11', 'tagcount', 'month', 'bar'),
  V('v12', 'reps', 'day', 'heatmap'),
  V('v13', 'reps', 'tag', 'bar', { types: ['habit'] }),
  V('v14', 'metric', 'week', 'bar', { metricId: 'm3' }),
  V('v15', 'metric', 'day', 'list', { metricId: 'gone' }),
  V('v16', 'completed', 'day', 'list', { tags: ['tg1'] }),
  V('v17', 'incomplete', 'week', 'line', { types: ['daily'] }),
  V('v18', 'created', 'month', 'heatmap'),
  V('v19', 'reps', 'type', 'line'),
  V('v20', 'streaks', 'day', 'heatmap'),
];

const S = { tasks, tags, rewards, prefs: { an: { fromOff: 90, toOff: 0, snap: '90d', metrics, views, activeMetric: 'm1', activeView: 'v01', _viewsUpgraded: true } } };
const ctx = { S, Date: class extends Date { constructor(...a) { if (a.length === 0) super(NOW); else super(...a); } static now() { return NOW; } }, Math, Object, Number, String, Array, JSON, Set, Infinity, isFinite, parseInt, console };
ctx.uid = () => 'x';
ctx.now = () => NOW;
vm.createContext(ctx);
vm.runInContext(code + '\n;this.__api={anPrefs,anMatcher,anAllEvents,anSpan,anWindow,anCumulativeReps,anRepsSeries,anActivitySeries,anRollup,anAdherenceSeries,anIntensity,anStreaks,anMilestones,anBreakdown,anAllMetricTotals,anCompareSeries,anSourceEvents,anBucket,anSnapshotHistoryBucket,anSnapshotRows,anViewItems,anViewMetric,renderView,heatColor,svgBars,svgSpark,svgCompare,anHeatmapHTML,fmtDate,repsPerTap,createdMs,updatedMs,isDailyDueToday,viewMetaLabel,anTagRows,localDayKey,nextLocalDay};', ctx);
const A = ctx.__api;

const prefCases = [
  { name: '90d', fromOff: 90, toOff: 0, snap: '90d' },
  { name: '7d', fromOff: 7, toOff: 0, snap: '7d' },
  { name: 'all', fromOff: 294, toOff: 0, snap: 'all' },
  { name: 'custom', fromOff: 200, toOff: 10, snap: null },
  { name: 'zeroFrom', fromOff: 0, toOff: 0, snap: null },
  { name: 'beyond', fromOff: 5000, toOff: 0, snap: null },
];
const clone = o => JSON.parse(JSON.stringify(o));
const out = { now: NOW, tz: process.env.TZ, span: A.anSpan(), events: A.anAllEvents().map(e => ({ taskId: e.taskId, date: e.date, reps: e.reps, repCounted: e.repCounted, scored: e.scored, scoredUp: e.scoredUp, completed: e.completed })), windows: {}, cases: {} };
out.heatColors = [0, 1, 2, 3, 4, 5, 7, 10].map(v => A.heatColor(v, 10));
out.repsPerTap = ['Kliky 20', 'Read 2 chapters of book 3', 'none', 'x10', ''].map(A.repsPerTap);
out.bars = A.svgBars([{ label: 'a', v: 3 }, { label: 'b', v: 0 }, { label: 'c', v: 0.01 }, { label: 'd', v: 12 }], 'X');
out.spark = A.svgSpark([{ d: 1, v: 2 }, { d: 2, v: 5 }, { d: 3, v: 0 }], true, 'X', 70, null);
out.spark1 = A.svgSpark([{ d: 1, v: 2 }], false, 'X', 60, null);
out.compare = A.svgCompare(A.anCompareSeries(D(2026, 9, 1), NOW));
out.milestones = metrics.map(m => A.anMilestones(m));
out.rollup = metrics.map(m => A.anRollup(m, 'year'));
out.streaks = A.anStreaks();
out.metaLabels = views.map(v => A.viewMetaLabel(v));
for (const pc of prefCases) {
  Object.assign(S.prefs.an, { fromOff: pc.fromOff, toOff: pc.toOff, snap: pc.snap });
  const [from, to] = A.anWindow();
  const wf = A.anWindow(true);
  const c = { from, to, feedFrom: wf[0], feedTo: wf[1] };
  c.reps = metrics.map(m => { const r = A.anCumulativeReps(m, from, to); return { total: r.total, taps: r.taps, missingTapPts: r.missingTapPts, activityDays: r.activityDays, matched: r.matched, byTask: r.byTask }; });
  c.series = metrics.map(m => A.anRepsSeries(m, from, to));
  c.breakdownWeek = metrics.map(m => A.anBreakdown(m, from, to, 'week'));
  c.breakdownMonth = metrics.map(m => A.anBreakdown(m, from, to, 'month'));
  c.totals = A.anAllMetricTotals(from, to);
  c.compare = A.anCompareSeries(from, to);
  c.adherence = A.anAdherenceSeries(from, to);
  c.intensity = A.anIntensity(from, to);
  const maxI = Math.max(1, ...Object.values(c.intensity));
  const heat = A.anHeatmapHTML(from, to, c.intensity, maxI);
  c.heat = heat.split('<div class="anHeatCol">').slice(1).map(col => [...col.matchAll(/background:([^"]+)" data-tip="([^"]*)"/g)].map(m => [m[1], m[2].replace(/&quot;/g, '"').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&')]));
  c.views = views.map(v0 => {
    const v = clone(v0);
    const r = { id: v.id };
    if (v.source === 'overview' || v.source === 'tagsummary') { r.html = A.renderView(v, from, to); return r; }
    if (['incomplete', 'streaks', 'tagcount'].indexOf(v.source) >= 0) r.bucket = A.anSnapshotHistoryBucket(v, from, to);
    else r.bucket = A.anBucket(A.anSourceEvents(v), v.group, from, to);
    if (r.bucket.dayMap) delete r.bucket.dayMap;
    return r;
  });
  out.cases[pc.name] = c;
}
fs.writeFileSync(path.join(__dirname, 'fixture.json'), JSON.stringify({ now: NOW, tz: process.env.TZ, tasks, tags, rewards, metrics, views, prefCases }, null, 1));
fs.writeFileSync(path.join(__dirname, 'goldens.json'), JSON.stringify(out, null, 1));
console.log('ok', Object.keys(out.cases).length, 'cases,', out.events.length, 'events');
