import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { flows, diagramCard } from './diagrams.mjs';
import { mermaidSource } from './graph.mjs';

const root = fileURLToPath(new URL('../../', import.meta.url));
const entries = ['hld-a', 'hld-b', 'modules', 'other', 'references-a', 'references-b', 'references-c'].flatMap(name => JSON.parse(readFileSync(new URL(`data/${name}.json`, import.meta.url))));
const directories = readdirSync(`${root}challenges`, { withFileTypes: true }).filter(x => x.isDirectory());
const expected = directories.filter(x => !/\*\*Source:\*\* leetcode/i.test(readFileSync(`${root}challenges/${x.name}/README.md`, 'utf8'))).map(x => x.name).sort();
assert.equal(directories.length - expected.length, 8, 'LeetCode exclusions from README provenance');
assert.deepEqual(entries.map(x => x.slug).sort(), expected);
assert.deepEqual(Object.keys(flows).sort(), expected);
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
const adversarial = { ...all[0], columns: undefined, nodes: [{ id: 'safe', kind: 'event', label: '"><script>alert(1)</script>' }], edges: [{ from: 'safe', to: 'safe', label: '" & < >' }] };
assert(!mermaidSource(adversarial).includes('<script>'));
assert(!diagramCard(adversarial).includes('<script>'));
assert.throws(() => mermaidSource({ ...adversarial, nodes: [{ id: 'x; click', kind: 'event', label: 'bad' }] }));
assert.throws(() => mermaidSource({ ...adversarial, edges: [{ from: 'safe', to: 'unknown' }] }));
if (errors.length) { console.error(errors.join('\n')); process.exitCode = 1; }
else console.log(`PASS ${entries.length} routes, ${all.length} graphs, ${all.reduce((n,g) => n + g.edges.length,0)} directed edges; source paths/ranges, ownership views, exemplar branches, grammar negative controls`);
