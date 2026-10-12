// `_maxOrderingTs` input-coercion vectors, produced by running the ORIGINAL PWA's REAL
// function (sync.js:2405-2426 at the pinned commit) in a vm sandbox.
//
// The PWA is a separate repository, https://github.com/Palo007/Questa, NOT vendored here.
// Read-only on it: the source text comes from `git show <rev>:sync.js`; only the
// `_maxOrderingTs` function body is extracted and evaluated (it is pure: it reads its argument
// and calls Number()).
// Output: fixtures/opti/KT51/max_ordering_ts.json (consumed by MaxOrderingTsParityTest).
//
// Usage: node tools/gen_kt51_fixture.mjs [OPTI_ROOT]
import { execFileSync } from 'node:child_process';
import { writeFileSync, mkdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import vm from 'node:vm';

const __dirname = dirname(fileURLToPath(import.meta.url));
// $OPTI_ROOT, else a sibling checkout named `Opti`. Was a hardcoded absolute path under one
// maintainer's home directory, so it resolved for exactly one person on one machine.
const OPTI_ROOT = process.argv[2] || process.env.OPTI_ROOT || join(dirname(__dirname), 'Opti');
const OPTI_REV = '7d5d4f3';

const src = execFileSync('git', ['-C', OPTI_ROOT, 'show', OPTI_REV + ':sync.js'], { encoding: 'utf8', maxBuffer: 64 << 20 });
const start = src.indexOf('function _maxOrderingTs(st){');
if (start < 0) throw new Error('_maxOrderingTs not found in sync.js@' + OPTI_REV);
const end = src.indexOf('\nasync function _syncNowAttempt', start);
if (end < 0) throw new Error('end of _maxOrderingTs not found');
const fnSrc = src.slice(start, end);
const startLine = src.slice(0, start).split('\n').length;
const endLine = startLine + fnSrc.trimEnd().split('\n').length - 1;

const ctx = vm.createContext({});
vm.runInContext(fnSrc + '\nthis._maxOrderingTs = _maxOrderingTs;', ctx);
const maxOrderingTs = ctx._maxOrderingTs;

// One value placed at tasks[0].updatedAt; the JSON value type is kept (string stays string).
const at = (v) => ({ tasks: [{ id: 't1', updatedAt: v }] });

const cases = [
  // Differ from the pre-fix Kotlin `num` (toDoubleOrNull / array -> 0):
  ['hex_string', at('0x10')],
  ['hex_upper_string', at('0X1F')],
  ['binary_string', at('0b101')],
  ['octal_string', at('0o17')],
  ['float_suffix_f', at('5f')],
  ['double_suffix_d', at('5d')],
  ['array_one_number', at([5])],
  ['array_one_string', at(['7'])],
  ['bom_prefixed', at('\uFEFF42')],
  // Same before and after (guards):
  ['padded_string', at(' 12 ')],
  ['whitespace_mix', at('\t\n 42\n')],
  ['nbsp_prefixed', at('\u00A042')],
  ['empty_string', at('')],
  ['exp_string', at('1e3')],
  ['plain_number', at(1790000000000)],
  ['numeric_string', at('1790000000001')],
  ['fraction_number', at(1.9)],
  ['negative_hex', at('-0x10')],
  ['garbage_suffix', at('12abc')],
  ['nan_string', at('NaN')],
  ['infinity_string', at('Infinity')],
  ['bool_true', at(true)],
  ['bool_false', at(false)],
  ['null_value', at(null)],
  ['array_empty', at([])],
  ['array_two', at([1, 2])],
  ['object_value', at({ a: 1 })],
  ['missing_value', { tasks: [{ id: 't1' }] }],
  // Every read site, with a coercion-sensitive value ("0x10" -> 16):
  ['site_checklist', { tasks: [{ id: 't1', updatedAt: 1, checklist: [{ id: 'c1', touchedAt: '0x20' }] }] }],
  ['site_char', { char: { updatedAt: '0x21' } }],
  ['site_devices', { devices: [{ id: 'd1', updatedAt: '0x22' }] }],
  ['site_deletions', { deletions: [{ id: 'x', at: '0x23' }] }],
  ['site_tags', { tags: [{ id: 'g1', updatedAt: '0x24' }] }],
  ['site_rewards', { rewards: [{ id: 'r1', updatedAt: '0x25' }] }],
  ['site_an_views', { an: { views: [{ id: 'v1', updatedAt: '0x26' }] } }],
  ['site_an_metrics', { an: { metrics: [{ id: 'm1', updatedAt: '0x27' }] } }],
  ['site_prefs_an_views', { prefs: { an: { views: [{ id: 'v1', updatedAt: '0x28' }] } } }],
  ['site_an_wins_over_prefs_an', { an: { views: [{ id: 'v1', updatedAt: 3 }] }, prefs: { an: { views: [{ id: 'v2', updatedAt: 99 }] } } }],
  ['max_across_sites', { tasks: [{ id: 't1', updatedAt: '5f' }], tags: [{ id: 'g1', updatedAt: [9] }], char: { updatedAt: ' 8 ' } }],
];

const vectors = cases.map(([name, state]) => {
  // Round-trip through JSON so the vector is exactly what the Kotlin test parses.
  const st = JSON.parse(JSON.stringify(state));
  return { name, state: st, opti: String(maxOrderingTs(st)) };
});

const out = {
  source: `Opti ${OPTI_REV}:sync.js:${startLine}-${endLine} _maxOrderingTs (Number(x)||0)`,
  note: '`opti` is String(mx). Kotlin returns Long: fractional -> truncated, Infinity -> Long.MAX_VALUE [DEFERRED_OPEN_QUESTION].',
  vectors,
};
const dir = join(__dirname, '..', 'fixtures', 'opti', 'KT51');
mkdirSync(dir, { recursive: true });
writeFileSync(join(dir, 'max_ordering_ts.json'), JSON.stringify(out, null, 2) + '\n');
console.log(`wrote ${vectors.length} vectors from sync.js:${startLine}-${endLine}`);
for (const v of vectors) console.log(v.name.padEnd(28), v.opti);
