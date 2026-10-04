import { flows, diagramCard, renderGraphs, referenceSlugs } from './diagrams.mjs';

const groups = ['HLD', 'Rama module', 'Rama diagnosis', 'Rama Q&A', 'AtCoder'];
const labels = { HLD: 'HLD case studies', 'Rama module': 'Rama modules', 'Rama diagnosis': 'Diagnosis', 'Rama Q&A': 'Rama Q&A', AtCoder: 'AtCoder algorithms' };
const files = ['hld-a', 'hld-b', 'modules', 'other', 'references-a', 'references-b', 'references-c'];
let entries = [];
let activeFilter = 'All';
let query = '';
let mutantReport;
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
  const before = mutantReport.summary.before;
  const after = mutantReport.summary.after;
  main.innerHTML = `<section class="hero"><h2>Challenge architectures</h2><p>Trace reference implementations from inputs to state and queries. Compare their requirements, users and design choices.</p><p class="coverage">${entries.length} challenges: ${entries.filter(x => x.kind === 'HLD').length} HLD adaptations, ${entries.filter(x => x.kind === 'Rama module').length} Rama modules, 10 algorithms, 2 Q&A and 1 diagnosis. The 8 LeetCode challenges are excluded.</p><p class="mutation-overview">Private graders killed <strong>${after.killed}/${after.mutants} mutants (${Math.round(after.rate * 100)}%)</strong> after PR #11, up from ${before.killed}/${before.mutants} (${Math.round(before.rate * 100)}%). All 16 references passed. <a href="https://github.com/okwalerie/rama-ai-learn/blob/5b933fbafa5060ae699fcdd422d4bcbd6b752bed/docs/mutant-kill-rate.md">Full comparison ↗</a></p></section>
  <div class="intro-grid"><div class="note-card"><h3>HLD scope and attribution</h3><p>The HLD cases adapt bounded correctness problems from the Handbook. Each README lists exclusions. Rama depots, ETLs and PStates handle the included queue, worker and storage responsibilities.</p><p>Adapted under <a href="https://creativecommons.org/licenses/by-sa/4.0/">CC BY-SA 4.0</a>; see the <a href="https://github.com/okwalerie/rama-ai-learn/blob/master/HLD_CASE_STUDIES.md">attribution catalogue</a> and each README. No affiliation or endorsement is implied.</p></div><div class="note-card"><h3>Views</h3><ul><li>LR: Depot / ETL / PState / Query overview.</li><li>TD: event branches, routing, outcomes and state ownership.</li><li>One-pagers: requirements, users and reference decisions.</li><li>Algorithm and Q&A diagrams trace functions and reasoning.</li></ul></div></div>
  <div class="catalog-head"><h3>Challenges</h3><span>${cards.length} shown</span></div><div class="catalog">${cards.map(x => `<a href="#${esc(x.slug)}"><small>${esc(labels[x.kind])}</small><strong>${esc(x.title)}</strong><p>${esc(x.summary)}</p></a>`).join('')}</div>`;
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
function mutationEvidence(item, rows) {
  const killed = rows.filter(row => row.after === 'killed').length;
  const prior = rows.filter(row => row.before === 'killed').length;
  const designs = new Map(mutantReport['after-run'].challenges.find(c => c.challenge === item.slug).mutants.map(m => [m.id, m['wrong-design']]));
  return `<article class="page-card mutation-evidence"><h3>Mutant kill rate: ${killed}/${rows.length} after PR #11</h3><p>Before (${esc(mutantReport.graders.before)}): ${prior}/${rows.length} killed. After (${esc(mutantReport.graders.after.split(' ')[0])}): ${killed}/${rows.length} killed. References passed in both runs.</p><div class="mutation-table-wrap"><table><caption>Mutant results for ${esc(item.title)}</caption><thead><tr><th scope="col">Wrong design</th><th scope="col">Before</th><th scope="col">After</th><th scope="col">Killing test (after)</th><th scope="col">PR #11 test?</th></tr></thead><tbody>${rows.map(row => `<tr><th scope="row">${esc(row.mutant)}<small>${esc(designs.get(row.mutant))}</small></th><td data-label="Before">${esc(row.before)}</td><td data-label="After" class="${row.before === 'survived' && row.after === 'killed' ? 'mutation-new' : ''}">${esc(row.after)}</td><td data-label="Killing test">${row['killing-test'].length ? esc(row['killing-test'].join(', ')) : '—'}</td><td data-label="PR #11 test?">${row['killed-by-pr11-nfr-test'] ? 'Yes' : 'No'}</td></tr>`).join('')}</tbody></table></div><p><a href="mutant-kill-rate.json">Full run data ↗</a> · <a href="https://github.com/okwalerie/rama-ai-learn/blob/5b933fbafa5060ae699fcdd422d4bcbd6b752bed/docs/mutant-kill-rate.md">Method and comparison ↗</a></p></article>`;
}
function renderDetail(item) {
  const mutants = mutantReport.mutants.filter(row => row.challenge === item.slug);
  const tabs = [['lr', 'LR overview'], ['td', 'TD scenarios + partitions'], ['requirements', 'Requirements'], ['user', 'Users'], ['reference', 'Reference decisions'], ...(mutants.length ? [['mutation', 'Grader evidence']] : [])];
  const prose = document.createElement('div');
  prose.innerHTML = onepagers(item);
  const cards = [...prose.querySelectorAll('.page-card')].map(x => x.outerHTML);
  const notes = [...new Set((flows[item.slug] || []).flatMap(g => g.notes || []))];
  cards[2] += `<article class="page-card">${notes.length ? `<h3>Source notes</h3>${list(notes)}` : ''}<details><summary>Component inventory</summary><p>Reading index from the original atlas. Cited diagrams govern routing and transitions.</p>${[['depots', 'Depots'], ['etls', 'ETLs'], ['pstates', 'PStates'], ['queries', 'Queries and client reads']].map(([key,label]) => `<h4>${label}</h4>${list(item[key])}`).join('')}</details></article>`;
  main.innerHTML = `<div class="breadcrumbs"><a href="#">← All challenges</a> / ${esc(item.slug)}</div><header class="detail-heading"><span class="badge">${esc(labels[item.kind])}</span><h2>${esc(item.title)}</h2><p>${esc(item.summary)}</p><div class="source-row">${sources(item)}</div></header><div class="tabs" role="tablist" aria-label="Challenge views">${tabs.map(([id, label], i) => `<button class="tab" id="tab-${id}" role="tab" aria-controls="panel-${id}" aria-selected="${i === 0}" tabindex="${i === 0 ? 0 : -1}">${label}</button>`).join('')}</div>${tabs.map(([id], i) => `<section class="panel ${i === 0 ? 'active' : ''}" id="panel-${id}" role="tabpanel" aria-labelledby="tab-${id}">${i < 2 ? diagramSection(item, id) : i < 5 ? cards[i - 2] : mutationEvidence(item, mutants)}</section>`).join('')}${item.caveat ? `<div class="caveat"><b>Scope.</b> ${esc(item.caveat)}</div>` : ''}<div class="footer-nav"><a href="#">← All challenges</a><a href="#${esc(entries[(entries.indexOf(item) + 1) % entries.length].slug)}">Next challenge →</a></div>`;
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
  if (item) renderDetail(item); else renderHome();
  renderNav(item?.slug);
  window.scrollTo(0, 0);
}
document.querySelector('#search').addEventListener('input', event => {
  query = event.target.value.trim().toLowerCase();
  renderNav(location.hash.slice(1));
  if (!location.hash) renderHome();
});
Promise.all([...files.map(name => fetch(`data/${name}.json`).then(response => {
  if (!response.ok) throw new Error(`${name}: HTTP ${response.status}`);
  return response.json();
})), fetch('mutant-kill-rate.json').then(response => {
  if (!response.ok) throw new Error(`Mutant report: HTTP ${response.status}`);
  return response.json();
})]).then(groupsData => {
  mutantReport = groupsData.pop();
  entries = groupsData.flat().sort((a,b) => groups.indexOf(a.kind) - groups.indexOf(b.kind) || a.title.localeCompare(b.title));
  renderFilters(); route();
  window.addEventListener('hashchange', route);
}).catch(error => { main.innerHTML = `<p class="loading">Unable to load challenge data: ${esc(error.message)}</p>`; });
