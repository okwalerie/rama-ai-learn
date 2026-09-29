import assert from 'node:assert/strict';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
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
// Study-guide one-pagers: optional per file until all six exist (or ATLAS_REQUIRE_ONEPAGERS=1).
const onepagerDir = process.env.ATLAS_ONEPAGER_DIR ? new URL(`file://${process.env.ATLAS_ONEPAGER_DIR.replace(/\/?$/, '/')}`) : new URL('data/', import.meta.url);
const onepagerFiles = ['rama-a', 'rama-b', 'rama-c', 'hld-streams', 'hld-txn', 'hld-jobs'].map(name => new URL(`onepagers-${name}.json`, onepagerDir));
const presentFiles = onepagerFiles.filter(existsSync);
const onepagers = presentFiles.flatMap(file => {
  const data = JSON.parse(readFileSync(file, 'utf8'));
  assert(Array.isArray(data), `${file.pathname}: must be a JSON array`);
  return data;
});
const auditText = readFileSync(`${root}docs/nfr-audit.md`, 'utf8');
const auditVerdicts = new Map();
for (const row of auditText.matchAll(/^\|\s*([a-z0-9-]+)\s*\|\s*(ADEQUATE|PARTIAL|ABSENT)\b/gm)) auditVerdicts.set(row[1], row[2]);
const headings = [...auditText.matchAll(/^## \d+\. ([a-z0-9-]+)\s*$/gm)];
headings.forEach((h, i) => {
  const body = auditText.slice(h.index, headings[i + 1]?.index ?? auditText.length);
  const found = body.match(/Verdict[\s:*#]*(ADEQUATE|PARTIAL|ABSENT)\b/);
  if (found) auditVerdicts.set(h[1], found[1]);
});
const nfrKinds = new Set(['HLD', 'Rama module']);
const nfrSlugs = entries.filter(x => nfrKinds.has(x.kind)).map(x => x.slug).sort();
const matrixKeys = ['write_amp', 'read_cost', 'topology_type', 'exactly_once', 'task_balance', 'races', 'bounded_work', 'retention'];
const handbook = 'https://hld.handbook.academy/curriculum/case-studies/';
const seen = new Set();
for (const o of onepagers) {
  const where = `onepager ${o?.slug}`;
  try {
    assert(!seen.has(o.slug), 'duplicate slug'); seen.add(o.slug);
    assert(nfrSlugs.includes(o.slug), 'slug is not an HLD or Rama module entry');
    assert(typeof o.contract === 'string' && o.contract.trim(), 'missing contract');
    assert.deepEqual(Object.keys(o.matrix || {}).sort(), [...matrixKeys].sort(), 'matrix categories');
    for (const [key, value] of Object.entries(o.matrix)) assert(['tested', 'stated', 'implied', 'na'].includes(value), `matrix ${key}=${value}`);
    assert(['ADEQUATE', 'PARTIAL', 'ABSENT'].includes(o.nfr?.verdict), `verdict ${o.nfr?.verdict}`);
    assert(auditVerdicts.has(o.slug), 'no verdict for this slug in docs/nfr-audit.md');
    assert.equal(o.nfr.verdict, auditVerdicts.get(o.slug), 'verdict differs from docs/nfr-audit.md');
    const s = o.study || {};
    assert(['strong', 'partial', 'weak', 'none'].includes(s.fit), `study fit ${s.fit}`);
    if (s.fit === 'none') assert(s.url == null, 'fit none must have null url');
    for (const link of [s, ...(s.then || [])].filter(x => x.url != null)) assert(String(link.url).startsWith(handbook), `handbook url ${link.url}`);
    for (const t of o.design?.topologies || []) assert(['stream', 'microbatch', 'query'].includes(t.type), `topology type ${t.type}`);
  } catch (e) { errors.push(`${where}: ${e.message}`); }
}
const requireAll = process.env.ATLAS_REQUIRE_ONEPAGERS === '1' || presentFiles.length === onepagerFiles.length;
if (requireAll) {
  const missing = nfrSlugs.filter(x => !seen.has(x));
  if (presentFiles.length < onepagerFiles.length) errors.push(`onepagers: ${onepagerFiles.length - presentFiles.length} data files missing`);
  if (missing.length) errors.push(`onepagers: missing slugs ${missing.join(', ')}`);
  assert.equal(nfrSlugs.length, 31, 'expected 16 Rama modules and 15 HLD entries');
}
if (errors.length) { console.error(errors.join('\n')); process.exitCode = 1; }
else console.log(`PASS ${onepagers.length} one-pagers from ${presentFiles.length}/${onepagerFiles.length} files${requireAll ? ' (all required)' : ''}; ${entries.length} routes, ${all.length} graphs, ${all.reduce((n,g) => n + g.edges.length,0)} directed edges; source paths/ranges, ownership views, exemplar branches, grammar negative controls`);
