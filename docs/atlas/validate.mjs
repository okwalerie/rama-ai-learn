import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { flows, diagramCard } from './diagrams.mjs';
import { mermaidSource, orderedColumns } from './graph.mjs';

const root = fileURLToPath(new URL('../../', import.meta.url));
const entries = ['hld-a', 'hld-b', 'modules', 'other', 'references-a', 'references-b', 'references-c'].flatMap(name => JSON.parse(readFileSync(new URL(`data/${name}.json`, import.meta.url))));
const directories = readdirSync(`${root}challenges`, { withFileTypes: true }).filter(x => x.isDirectory());
const expected = directories.filter(x => !/\*\*Source:\*\* leetcode/i.test(readFileSync(`${root}challenges/${x.name}/README.md`, 'utf8'))).map(x => x.name).sort();
assert.equal(directories.length - expected.length, 8, 'LeetCode exclusions from README provenance');
assert.deepEqual(entries.map(x => x.slug).sort(), expected);
assert.deepEqual(Object.keys(flows).sort(), expected);
const report = JSON.parse(readFileSync(`${root}docs/mutant-kill-rate.json`, 'utf8'));
assert.deepEqual([report.summary.before.killed, report.summary.after.killed], [6, 13]);
assert.equal(report.mutants.length, 25);
assert.equal(report.references.length, 16);
assert(report.references.every(r => r.before && r.after));
assert(report.references.every(r => entries.some(item => item.slug === r.challenge)));
const baseline = new Map(report['before-run'].challenges.flatMap(c => c.mutants.map(m => [`${c.challenge}/${m.id}`, m])));
const after = new Map(report['after-run'].challenges.flatMap(c => c.mutants.map(m => [`${c.challenge}/${m.id}`, m])));
assert.equal(baseline.size, 25);
assert.equal(after.size, 25);
assert.equal(new Set(report.mutants.map(m => `${m.challenge}/${m.mutant}`)).size, 25);
for (const row of report.mutants) {
  const key = `${row.challenge}/${row.mutant}`;
  const before = baseline.get(key), current = after.get(key);
  assert(before && current, `${key}: missing run evidence`);
  assert.equal(row.before, before['killed?'] ? 'killed' : 'survived');
  assert.equal(row.after, current['killed?'] ? 'killed' : 'survived');
  assert.deepEqual(row['killing-test'], [...new Set(current['killed-by'].map(hit => hit.test))].sort());
}
assert.equal(report.mutants.filter(m => m.before === 'survived' && m.after === 'killed' && m['killed-by-pr11-nfr-test']).length, 7);
const all = Object.values(flows).flat();
assert.equal(new Set(all.map(g => g.id)).size, all.length);
const errors = [];
for (const item of entries) {
  const graphs = flows[item.slug];
  assert(graphs.some(g => g.view === 'lr'), `${item.slug}: missing LR`);
  assert(graphs.some(g => g.view === 'td'), `${item.slug}: missing TD`);
  if (['HLD', 'Rama module', 'Rama diagnosis'].includes(item.kind)) assert(graphs.some(g => g.view === 'partition'), `${item.slug}: missing partition`);
  for (const g of graphs) {
    try {
      mermaidSource(g);
      assert(g.edges.length, 'no transitions');
      for (const n of g.nodes) assert(g.edges.some(e => e.from === n.id || e.to === n.id), `isolated ${n.id}`);
      for (const s of g.sources) {
        assert(!s.path.includes('..') && s.path.startsWith('challenges/'), `unsafe source ${s.path}`);
        const count = readFileSync(`${root}${s.path}`, 'utf8').trimEnd().split('\n').length;
        for (const range of s.lines.split(',')) {
          const [start, end = start] = range.split('-').map(Number);
          assert(start > 0 && end >= start && end <= count, `${s.path}:${range} exceeds ${count}`);
        }
      }
    } catch (e) { errors.push(`${g.id}: ${e.message}`); }
  }
}
const chat = flows['chat-app'];
const fanout = flows.fanout;
assert(chat.filter(g => g.view === 'td').length >= 5);
assert(fanout.filter(g => g.view === 'td').length >= 4);
assert(chat.flatMap(g => g.nodes).filter(n => n.kind === 'decision').length >= 12);
assert(fanout.flatMap(g => g.nodes).some(n => /1000/.test(n.label)));
for (const g of all.filter(g => g.columns)) {
  assert.deepEqual(g.columns.map(c => c.title), ['Depot', 'ETL', 'PState', 'Query']);
  assert(!g.nodes.some(n => ['decision', 'route'].includes(n.kind)), `${g.id}: LR must remain a component overview`);
}
// Asymmetric destinations distinguish a true crossing from a harmless fan-in.
const crossing = {
  columns: [{ title: 'Depot', nodes: ['a', 'b'] }, { title: 'ETL', nodes: ['x', 'y'] }],
  edges: [{ from: 'a', to: 'y', label: 'one' }, { from: 'b', to: 'x', label: 'two' }]
};
assert.deepEqual(orderedColumns(crossing).map(c => c.nodes), [['b', 'a'], ['x', 'y']]);
assert.deepEqual(crossing.columns.map(c => c.nodes), [['a', 'b'], ['x', 'y']], 'layout must not mutate authored graph');
assert.deepEqual(orderedColumns({ ...crossing, edges: [{ from: 'a', to: 'x' }, { from: 'b', to: 'x' }] })
  .map(c => c.nodes), [['a', 'b'], ['x', 'y']], 'ties retain authored order');
const auction = flows['auction-module'][0];
assert.deepEqual(orderedColumns(auction)[0].nodes, ['bids', 'listing', 'tick']);
const reserve = flows['hld-hotel-reservation'].find(g => g.id === 'hld-hotel-reserve-td');
assert.deepEqual(reserve.edges.filter(e => e.from === 'short').map(e => e.to), ['total', 'reject']);
assert.deepEqual(mermaidSource(reserve).split('\n').filter(s => s.startsWith('n_short -->'))
  .map(s => s.match(/n_(\w+)$/)[1]), ['reject', 'total']);
const adversarial = { ...all[0], columns: undefined, nodes: [{ id: 'safe', kind: 'event', label: '"><script>alert(1)</script>' }], edges: [{ from: 'safe', to: 'safe', label: '" & < >' }] };
assert(!mermaidSource(adversarial).includes('<script>'));
assert(!diagramCard(adversarial).includes('<script>'));
assert.throws(() => mermaidSource({ ...adversarial, nodes: [{ id: 'x; click', kind: 'event', label: 'bad' }] }));
assert.throws(() => mermaidSource({ ...adversarial, edges: [{ from: 'safe', to: 'unknown' }] }));
if (errors.length) { console.error(errors.join('\n')); process.exitCode = 1; }
else console.log(`PASS ${entries.length} routes, ${all.length} graphs, ${all.reduce((n,g) => n + g.edges.length,0)} directed edges; source paths/ranges, ownership views, exemplar branches, grammar negative controls`);
