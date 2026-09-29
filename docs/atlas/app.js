import { flows, diagramCard, renderGraphs, referenceSlugs } from './diagrams.mjs';

const groups = ['HLD', 'Rama module', 'Rama diagnosis', 'Rama Q&A', 'AtCoder'];
const labels = { HLD: 'HLD case studies', 'Rama module': 'Rama modules', 'Rama diagnosis': 'Diagnosis', 'Rama Q&A': 'Rama Q&A', AtCoder: 'AtCoder algorithms' };
const files = ['hld-a', 'hld-b', 'modules', 'other', 'references-a', 'references-b', 'references-c'];
const onepagerFiles = ['rama-a', 'rama-b', 'rama-c', 'hld-streams', 'hld-txn', 'hld-jobs'];
const nfrCategories = [
  ['write_amp', 'Write', 'Write amplification'], ['read_cost', 'Read', 'Read cost'],
  ['topology_type', 'Topo', 'Stream vs microbatch'], ['exactly_once', 'Once', 'Exactly-once under retry'],
  ['task_balance', 'Bal', 'Task balance'], ['races', 'Race', 'Concurrency races'],
  ['bounded_work', 'Bound', 'Bounded work per event'], ['retention', 'Ret', 'Retention']];
const marks = { tested: ['●', 'tested'], stated: ['○', 'stated, untested'], implied: ['◌', 'implied, untested'], na: ['', 'not applicable'] };
const handbook = 'https://hld.handbook.academy/curriculum/case-studies/';
let entries = [];
let activeFilter = 'All';
let query = '';
const main = document.querySelector('#main');
const nav = document.querySelector('#challenge-nav');
const esc = value => String(value ?? '').replace(/[&<>"']/g, char => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[char]));
const list = items => items?.length ? `<ul>${items.map(x => `<li>${esc(x)}</li>`).join('')}</ul>` : '<p class="empty">Not specified in this challenge.</p>';
const repoFile = path => referenceSlugs.has(path.split('/')[1]) ? `/source/${path}` : `https://github.com/okwalerie/rama-ai-learn/blob/master/${path}`;
const sourceURL = slug => repoFile(`challenges/${encodeURIComponent(slug)}/README.md`);
const sources = item => {
  const raw = Array.isArray(item.source) ? item.source : [];
  const paths = typeof item.source === 'string' ? item.source.split(';').map(x => x.trim()).filter(x => /\.(clj|md)$/.test(x)).map(x => x.startsWith('challenges/') || x.startsWith('plugins/') ? x : `challenges/${item.slug}/${x}`) : [];
  const urls = [...new Set([sourceURL(item.slug), ...raw.filter(x => /^https:\/\//.test(x)).map(x => x.replace('/blob/main/', '/blob/master/')), ...raw.filter(x => x.startsWith('challenges/')).map(repoFile), ...paths.map(repoFile)])];
  return urls.map((url, i) => {
    const name = url.includes('handbook.academy') ? 'HLD case study' : url.endsWith('protocol.clj') ? 'Protocol' : url.endsWith('module.clj') ? 'Reference module' : url.endsWith('HLD_CASE_STUDIES.md') ? 'Attribution & scope' : i === 0 ? 'Challenge README' : 'Source';
    return `<a href="${esc(url)}" target="_blank" rel="noopener noreferrer">${name} ↗</a>`;
  }).join('');
};
function filtered() {
  return entries.filter(x => (activeFilter === 'All' || x.kind === activeFilter) && `${x.title} ${x.slug} ${x.summary}`.toLowerCase().includes(query));
}
function renderNav(selected) {
  const visible = filtered();
  document.querySelector('.nav-count').textContent = `${visible.length} of ${entries.length} challenges`;
  nav.innerHTML = groups.map(group => {
    const items = visible.filter(x => x.kind === group);
    return items.length ? `<div class="group-label">${labels[group]} · ${items.length}</div>${items.map(x => `<button class="nav-item ${x.slug === selected ? 'active' : ''}" data-slug="${esc(x.slug)}">${esc(x.title)}</button>`).join('')}` : '';
  }).join('') || '<div class="group-label">No matches</div>';
  nav.querySelectorAll('[data-slug]').forEach(button => button.addEventListener('click', () => location.hash = button.dataset.slug));
}
function renderFilters() {
  document.querySelector('.filters').innerHTML = ['All', ...groups].map(name => `<button class="filter ${name === activeFilter ? 'active' : ''}" data-filter="${esc(name)}" aria-pressed="${name === activeFilter}">${name === 'All' ? 'All' : name === 'Rama module' ? 'Modules' : name === 'Rama diagnosis' ? 'Debug' : name === 'Rama Q&A' ? 'Q&A' : name}</button>`).join('');
  document.querySelectorAll('[data-filter]').forEach(button => button.addEventListener('click', () => {
    activeFilter = button.dataset.filter;
    renderFilters(); renderNav(location.hash.slice(1));
    if (!location.hash) renderHome();
  }));
}
function renderHome() {
  const cards = filtered();
  main.innerHTML = `<section class="hero"><h2>Challenge architectures</h2><p>Trace reference implementations from inputs to state and queries. Compare their requirements, users and design choices.</p><p class="coverage">${entries.length} challenges: ${entries.filter(x => x.kind === 'HLD').length} HLD adaptations, ${entries.filter(x => x.kind === 'Rama module').length} Rama modules, 10 algorithms, 2 Q&A and 1 diagnosis. The 8 LeetCode challenges are excluded.</p></section>
  <div class="intro-grid"><div class="note-card"><h3>HLD scope and attribution</h3><p>The HLD cases adapt bounded correctness problems from the Handbook. Each README lists exclusions. Rama depots, ETLs and PStates handle the included queue, worker and storage responsibilities.</p><p>Adapted under <a href="https://creativecommons.org/licenses/by-sa/4.0/">CC BY-SA 4.0</a>; see the <a href="https://github.com/okwalerie/rama-ai-learn/blob/master/HLD_CASE_STUDIES.md">attribution catalogue</a> and each README. No affiliation or endorsement is implied.</p></div><div class="note-card"><h3>Views</h3><ul><li>LR: Depot / ETL / PState / Query overview.</li><li>TD: event branches, routing, outcomes and state ownership.</li><li>One-pagers: requirements, users and reference decisions.</li><li>Algorithm and Q&A diagrams trace functions and reasoning.</li><li>Study guides and the <a href="#coverage">NFR coverage matrix</a>: what each Rama module and HLD challenge tests.</li></ul></div></div>
  <div class="catalog-head"><h3>Challenges</h3><span>${cards.length} shown</span></div><div class="catalog">${cards.map(x => `<a href="#${esc(x.slug)}"><small>${esc(labels[x.kind])}</small><strong>${esc(x.title)}</strong><p>${esc(x.summary)}</p></a>`).join('')}</div>`;
}
const code = value => `<code>${esc(value)}</code>`;
const mark = value => { const [glyph, text] = marks[value] || marks.na; return `<td class="m m-${esc(value || 'na')}"><span aria-hidden="true">${glyph}</span><span class="vh">${text}</span></td>`; };
const verdictLabel = v => `<span class="verdict verdict-${esc(String(v).toLowerCase())}">${esc(v)}</span>`;
const matrixHead = first => `<thead><tr><th scope="col">${first}</th>${nfrCategories.map(([, short, full]) => `<th scope="col"><abbr title="${esc(full)}">${esc(short)}</abbr></th>`).join('')}<th scope="col">Verdict</th></tr></thead>`;
const matrixRow = (label, o) => `<tr><th scope="row">${label}</th>${nfrCategories.map(([key]) => mark(o.matrix?.[key])).join('')}<td class="matrix-verdict">${esc(o.nfr?.verdict)}</td></tr>`;
const legend = `<p class="matrix-legend"><span><span aria-hidden="true">●</span> tested</span><span><span aria-hidden="true">○</span> stated, untested</span><span><span aria-hidden="true">◌</span> implied, untested</span><span>blank: not applicable</span></p>`;
const abbreviations = `<p class="matrix-key">${nfrCategories.map(([, short, full]) => `<span><b>${esc(short)}</b> ${esc(full.toLowerCase())}</span>`).join('')}</p>`;
const studyName = s => `${s?.number ? `${esc(s.number)} ` : ''}${s?.title ? esc(s.title) : '(title not confirmed)'}`;
const studyLink = s => s?.url && String(s.url).startsWith(handbook) ? `<a href="${esc(s.url)}" target="_blank" rel="noopener noreferrer">${studyName(s)} ↗</a>` : studyName(s);
const table = (heads, rows) => rows?.length ? `<div class="table-wrap"><table class="design-table"><thead><tr>${heads.map(h => `<th scope="col">${h}</th>`).join('')}</tr></thead><tbody>${rows.join('')}</tbody></table></div>` : '<p class="empty">None.</p>';
function studyGuide(item) {
  const o = item.onepager, s = o.study || {}, d = o.design || {}, n = o.nfr || {};
  const section = (label, body) => `<section class="prose-section"><h4>${label}</h4><div>${body}</div></section>`;
  const study = s.fit === 'none'
    ? `<p>No handbook case study fits. ${esc(s.reason)}</p>`
    : `<p class="study-main">${studyLink(s)} <span class="fit">${esc(s.fit)} fit</span></p><p>${esc(s.reason)}</p>${s.then?.length ? `<p class="study-then">Then: ${s.then.map(studyLink).join(', ')}</p>` : ''}`;
  const design = `<h5>Depots</h5>${table(['Depot', 'Partitioning'], (d.depots || []).map(x => `<tr><td>${code(x.name)}</td><td>${esc(x.partition)}</td></tr>`))}
    <h5>PStates</h5>${table(['PState', 'Schema', 'Key'], (d.pstates || []).map(x => `<tr><td>${code(x.name)}</td><td>${code(x.schema)}</td><td>${esc(x.key)}</td></tr>`))}
    <h5>Topologies</h5>${table(['Topology', 'Type', 'Purpose'], (d.topologies || []).map(x => `<tr><td>${code(x.name)}</td><td>${esc(x.type)}</td><td>${esc(x.does)}</td></tr>`))}
    ${d.ownership ? `<p class="ownership">${esc(d.ownership)}</p>` : ''}`;
  const tested = n.tested?.length ? `<dl class="pairs">${n.tested.map(x => `<dt>${esc(x.nfr)}</dt><dd>${esc(x.mechanism)}</dd>`).join('')}</dl>` : '<p class="empty">No NFR is tested.</p>';
  const gaps = n.gaps?.length ? n.gaps.map(x => `<div class="gap"><p><span class="gap-key">Gap</span> ${esc(x.gap)}</p><p><span class="gap-key">Wrong design that passes today</span> ${esc(x.wrongDesign)}</p></div>`).join('') : '<p class="empty">No gaps recorded.</p>';
  const nfr = `<p>${verdictLabel(n.verdict)}</p><h5>Stated</h5>${list(n.stated)}<h5>Tested</h5>${tested}<h5>Gaps</h5>${gaps}`;
  const row = `<div class="matrix-wrap"><table class="matrix matrix-single"><caption class="vh">NFR coverage for ${esc(item.title)}</caption>${matrixHead('Challenge')}<tbody>${matrixRow(esc(item.slug), o)}</tbody></table></div>${legend}<p><a href="#coverage">Full coverage matrix →</a></p>`;
  return `<article class="page-card study-guide">${section('What it asks', `<p>${esc(o.contract)}</p>`)}${section('Study next', study)}${section('Reference design at a glance', design)}${section('Non-functional requirements', nfr)}${section('Coverage', row)}</article>`;
}
function renderCoverage() {
  const rows = groups.filter(g => g === 'HLD' || g === 'Rama module').sort((a, b) => a === 'Rama module' ? -1 : b === 'Rama module' ? 1 : 0)
    .map(group => [group, entries.filter(x => x.kind === group && x.onepager)]);
  const all = rows.flatMap(([, items]) => items);
  const expected = entries.filter(x => ['HLD', 'Rama module'].includes(x.kind)).length;
  const body = rows.filter(([, items]) => items.length).map(([group, items]) => `<tbody><tr class="matrix-group"><th scope="colgroup" colspan="${nfrCategories.length + 2}">${esc(labels[group])} · ${items.length}</th></tr>${items.map(x => matrixRow(`<a href="#${esc(x.slug)}">${esc(x.title)}</a>`, x.onepager)).join('')}</tbody>`).join('');
  const totals = `<tfoot><tr><th scope="row">Tested of ${all.length}</th>${nfrCategories.map(([key]) => `<td>${all.filter(x => x.onepager.matrix?.[key] === 'tested').length}</td>`).join('')}<td></td></tr></tfoot>`;
  const verdicts = ['ADEQUATE', 'PARTIAL', 'ABSENT'].map(v => `${all.filter(x => x.onepager.nfr?.verdict === v).length} ${v.toLowerCase()}`).join(', ');
  main.innerHTML = `<div class="breadcrumbs"><a href="#">← All challenges</a> / coverage</div><section class="hero"><h2>NFR test coverage</h2><p>Which non-functional requirements each Rama module and HLD challenge tests, states without testing, or only implies. Verdicts come from the NFR audit. Select a challenge to open its study guide.</p>${all.length < expected ? `<p class="coverage">${all.length} of ${expected} study guides loaded.</p>` : ''}</section>
  ${all.length ? `${legend}${abbreviations}<div class="matrix-wrap" role="region" aria-label="Coverage matrix" tabindex="0"><table class="matrix"><caption class="vh">NFR coverage by challenge. Rows are challenges; columns are NFR categories.</caption>${matrixHead('Challenge')}${body}${totals}</table></div><p class="matrix-note">Verdicts: ${verdicts}.</p>` : '<p class="empty">No study guides are available.</p>'}`;
}
function diagramSection(item, view) {
  const diagrams = (flows[item.slug] || []).filter(g => view === 'lr' ? g.view === 'lr' : g.view !== 'lr');
  const native = ['HLD', 'Rama module', 'Rama diagnosis'].includes(item.kind);
  const intro = native ? '' : '<p class="view-intro">Function or reasoning flow; no deployed Rama module.</p>';
  const index = diagrams.length > 1 ? `<nav class="graph-index" aria-label="Scenarios and state ownership">${diagrams.map(g => `<button data-diagram="${esc(g.id)}">${esc(g.title)}</button>`).join('')}</nav>` : '';
  return `${intro}<details class="diagram-help"><summary>Reading diagrams</summary><p>Arrows name transitions, reads and writes. Diamonds mark conditions; cylinders mark PStates. Routing and ephemeral memory are labelled in the nodes.</p><p>Fit shows the whole graph. Use 100% for detail, then scroll or drag. A text version follows each diagram.</p></details>${index}${diagrams.map(diagramCard).join('')}`;
}
function onepagers(item) {
  const block = (label, values) => `<section class="prose-section"><h4>${label}</h4>${list(values)}</section>`;
  return `<article class="page-card">${block('Behavior',item.requirements)}${block('Performance and reliability',item.nonfunctional)}${block('Constraints',item.constraints)}${block('Invariants',item.invariants)}${block('Acceptance',item.acceptance)}</article><article class="page-card">${block('Users',item.actors)}${block('Environment',item.environment)}${block('Interfaces',item.interfaces)}</article><article class="page-card">${block('Design choices',item.decisions)}${block('Tradeoffs',item.tradeoffs)}${block('External boundaries',item.seam)}</article>`;
}
function renderDetail(item) {
  const tabs = [...(item.onepager ? [['guide', 'Study guide']] : []), ['lr', 'LR overview'], ['td', 'TD scenarios + partitions'], ['requirements', 'Requirements'], ['user', 'Users'], ['reference', 'Reference decisions']];
  const cardIndex = { requirements: 0, user: 1, reference: 2 };
  const prose = document.createElement('div');
  prose.innerHTML = onepagers(item);
  const cards = [...prose.querySelectorAll('.page-card')].map(x => x.outerHTML);
  const notes = [...new Set((flows[item.slug] || []).flatMap(g => g.notes || []))];
  cards[2] += `<article class="page-card">${notes.length ? `<h3>Source notes</h3>${list(notes)}` : ''}<details><summary>Component inventory</summary><p>Reading index from the original atlas. Cited diagrams govern routing and transitions.</p>${[['depots', 'Depots'], ['etls', 'ETLs'], ['pstates', 'PStates'], ['queries', 'Queries and client reads']].map(([key,label]) => `<h4>${label}</h4>${list(item[key])}`).join('')}</details></article>`;
  main.innerHTML = `<div class="breadcrumbs"><a href="#">← All challenges</a> / ${esc(item.slug)}</div><header class="detail-heading"><span class="badge">${esc(labels[item.kind])}</span><h2>${esc(item.title)}</h2><p>${esc(item.summary)}</p><div class="source-row">${sources(item)}</div></header><div class="tabs" role="tablist" aria-label="Challenge views">${tabs.map(([id, label], i) => `<button class="tab" id="tab-${id}" role="tab" aria-controls="panel-${id}" aria-selected="${i === 0}" tabindex="${i === 0 ? 0 : -1}">${label}</button>`).join('')}</div>${tabs.map(([id], i) => `<section class="panel ${i === 0 ? 'active' : ''}" id="panel-${id}" role="tabpanel" aria-labelledby="tab-${id}">${id === 'guide' ? studyGuide(item) : id in cardIndex ? cards[cardIndex[id]] : diagramSection(item, id)}</section>`).join('')}${item.caveat ? `<div class="caveat"><b>Scope.</b> ${esc(item.caveat)}</div>` : ''}<div class="footer-nav"><a href="#">← All challenges</a><a href="#${esc(entries[(entries.indexOf(item) + 1) % entries.length].slug)}">Next challenge →</a></div>`;
  main.querySelectorAll('[data-diagram]').forEach(button => button.addEventListener('click', () => {
    const card = document.getElementById(button.dataset.diagram);
    card.scrollIntoView();
    card.querySelector('.graph-viewport').focus({ preventScroll: true });
  }));
  const buttons = [...main.querySelectorAll('.tab')];
  const activate = tab => {
    buttons.forEach(x => { x.setAttribute('aria-selected', String(x === tab)); x.tabIndex = x === tab ? 0 : -1; });
    main.querySelectorAll('.panel').forEach(x => x.classList.toggle('active', x.id === tab.getAttribute('aria-controls')));
    renderGraphs(main.querySelector('.panel.active'));
  };
  buttons.forEach((tab, i) => {
    tab.addEventListener('click', () => activate(tab));
    tab.addEventListener('keydown', e => {
      const next = e.key === 'ArrowRight' ? (i + 1) % buttons.length : e.key === 'ArrowLeft' ? (i + buttons.length - 1) % buttons.length : e.key === 'Home' ? 0 : e.key === 'End' ? buttons.length - 1 : null;
      if (next !== null) { e.preventDefault(); buttons[next].focus(); activate(buttons[next]); }
    });
  });
  renderGraphs(main.querySelector('.panel.active'));
}
function route() {
  let slug;
  try { slug = decodeURIComponent(location.hash.slice(1)); } catch { slug = ''; }
  const item = entries.find(x => x.slug === slug);
  if (item) renderDetail(item); else if (slug === 'coverage') renderCoverage(); else renderHome();
  renderNav(item?.slug);
  window.scrollTo(0, 0);
}
document.querySelector('#search').addEventListener('input', event => {
  query = event.target.value.trim().toLowerCase();
  renderNav(location.hash.slice(1));
  if (!location.hash) renderHome();
});
Promise.all(files.map(name => fetch(`data/${name}.json`).then(response => {
  if (!response.ok) throw new Error(`${name}: HTTP ${response.status}`);
  return response.json();
}))).then(async groupsData => {
  entries = groupsData.flat().sort((a,b) => groups.indexOf(a.kind) - groups.indexOf(b.kind) || a.title.localeCompare(b.title));
  // One-pager files are optional: a missing or invalid file leaves those entries without a study guide.
  const pages = await Promise.allSettled(onepagerFiles.map(name => fetch(`data/onepagers-${name}.json`).then(r => r.ok ? r.json() : [])));
  for (const result of pages) {
    if (result.status !== 'fulfilled' || !Array.isArray(result.value)) continue;
    for (const page of result.value) {
      const entry = entries.find(x => x.slug === page?.slug && ['HLD', 'Rama module'].includes(x.kind));
      if (entry) entry.onepager = page;
    }
  }
  renderFilters(); route();
  window.addEventListener('hashchange', route);
}).catch(error => { main.innerHTML = `<p class="loading">Unable to load challenge data: ${esc(error.message)}</p>`; });
