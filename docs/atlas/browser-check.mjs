// Run in the Portal browser: import('./browser-check.mjs').then(m => m.run()).
// It navigates all real routes and exercises all five review surfaces.
import { flows, diagramCard, renderGraphs } from './diagrams.mjs';
import { mermaidSource } from './graph.mjs';

export async function run() {
  const report = window.atlasCheck = { width: innerWidth, routes: [], graphs: [], errors: [], links: [] };
  const check = (ok, message) => { if (!ok) report.errors.push(message); };
  const wait = async predicate => {
    const start = performance.now();
    while (!predicate()) {
      if (performance.now() - start > 60000) throw new Error('Timed out waiting for route/render');
      await new Promise(resolve => setTimeout(resolve, 50));
    }
  };
  for (const [slug, graphs] of Object.entries(flows)) {
    location.hash = slug;
    await wait(() => document.querySelector('.breadcrumbs')?.textContent.includes(slug));
    check(document.querySelectorAll('[role=tab]').length === 5, `${slug}: five tabs`);
    for (const tab of ['lr', 'td', 'requirements', 'user', 'reference']) {
      document.getElementById(`tab-${tab}`).click();
      const panel = document.getElementById(`panel-${tab}`);
      await wait(() => [...panel.querySelectorAll('.graph-card')].every(c => ['ready','error'].includes(c.dataset.rendered)));
      check(panel.classList.contains('active'), `${slug}/${tab}: active panel`);
      check(panel.textContent.trim().length > 100, `${slug}/${tab}: empty content`);
      check(document.documentElement.scrollWidth <= innerWidth + 1, `${slug}/${tab}: page overflow ${document.documentElement.scrollWidth}`);
      for (const button of panel.querySelectorAll('[data-diagram]')) {
        button.click();
        check(document.activeElement === document.getElementById(button.dataset.diagram)?.querySelector('.graph-viewport'), `${slug}: scenario navigation focus`);
        check(location.hash === `#${slug}`, `${slug}: scenario navigation changed route`);
      }
      for (const card of panel.querySelectorAll('.graph-card')) {
        const id = card.dataset.graph;
        const g = graphs.find(g => g.id === id);
        check(card.dataset.rendered === 'ready', `${id}: render error`);
        const svg = card.querySelector('svg');
        if (!svg) continue;
        check(svg.querySelectorAll('.node').length === g.nodes.length, `${id}: node count`);
        check(svg.querySelectorAll('.edgePath, .flowchart-link').length >= g.edges.length, `${id}: edge count`);
        check(svg.getAttribute('role') === 'img' && svg.hasAttribute('aria-label'), `${id}: accessible SVG`);
        check(card.querySelectorAll('.graph-text li').length === g.edges.length, `${id}: text transition count`);
        check(!/&(lt|gt|quot|amp);/.test(svg.textContent), `${id}: literal HTML entity in label`);
        if (g.columns) {
          const clusters = [...svg.querySelectorAll('.cluster')].sort((a,b) => a.getBBox().x - b.getBBox().x);
          check(clusters.map(x => x.textContent.trim()).join('/') === 'Depot/ETL/PState/Query', `${id}: four-column order`);
        }
        const box = svg.getBBox(), view = svg.viewBox.baseVal;
        check(box.x >= view.x - 2 && box.y >= view.y - 2 && box.x + box.width <= view.x + view.width + 2 && box.y + box.height <= view.y + view.height + 2, `${id}: drawing outside viewBox`);
        for (const n of svg.querySelectorAll('.node')) {
          const label = n.querySelector('.label');
          const shape = n.querySelector('rect,polygon,path,circle,ellipse');
          if (!label || !shape) continue;
          const l = label.getBoundingClientRect(), s = shape.getBoundingClientRect();
          check(l.left >= s.left - 2 && l.right <= s.right + 2 && l.top >= s.top - 2 && l.bottom <= s.bottom + 2, `${id}/${n.id}: label outside shape`);
        }
        card.querySelector('[data-action=actual]').click();
        check(card.querySelector('output').value === '100%', `${id}: actual zoom`);
        card.querySelector('[data-action=out]').click();
        check(card.querySelector('output').value === '80%', `${id}: zoom out`);
        card.querySelector('[data-action=fit]').click();
        const viewport = card.querySelector('.graph-viewport');
        check(viewport.scrollWidth <= viewport.clientWidth + 1, `${id}: Fit leaves horizontal overflow`);
        check(getComputedStyle(viewport).backgroundImage === 'none', `${id}: decorative diagram background`);
        check(getComputedStyle(viewport).overscrollBehaviorY === 'auto', `${id}: inline diagram traps page scrolling`);
        check(getComputedStyle(viewport).overscrollBehaviorX === 'contain', `${id}: horizontal scroll containment`);
        card.querySelector('[data-action=expand]').click();
        check(card.classList.contains('expanded'), `${id}: expand`);
        check(getComputedStyle(viewport).overscrollBehaviorY === 'contain', `${id}: expanded diagram leaks page scrolling`);
        check(document.documentElement.scrollWidth <= innerWidth + 1, `${id}: expanded page overflow`);
        card.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
        check(!card.classList.contains('expanded'), `${id}: escape`);
        report.graphs.push(id);
      }
    }
    report.links.push(...[...document.querySelectorAll('#main a[href]')].map(a => a.href));
    report.routes.push(slug);
  }
  // Failure control: invalid graph must expose all its text and an alert.
  const g = { ...flows['chat-app'][0], columns: undefined, id: 'negative-control', nodes: [{ id: 'bad;directive', kind: 'event', label: 'Unsafe identifier' }], edges: [] };
  flows['negative-control'] = [g];
  const fixture = document.createElement('section');
  fixture.innerHTML = diagramCard(g); document.body.append(fixture);
  await renderGraphs(fixture);
  check(fixture.querySelector('.graph-card').dataset.rendered === 'error', 'negative control: failure not detected');
  check(fixture.querySelector('details').open && fixture.querySelector('[role=alert]'), 'negative control: no accessible fallback');
  fixture.remove(); delete flows['negative-control'];
  // Mermaid label escaping is tested by rendering hostile label content.
  const { default: mermaid } = await import('./vendor/mermaid.mjs');
  const hostile = { ...g, nodes: [{ id: 'safe', kind: 'event', label: '"><script>window.atlasInjected=1</script>' }], edges: [] };
  const { svg } = await mermaid.render('hostileLabelTest', mermaidSource(hostile));
  const parsed = new DOMParser().parseFromString(svg, 'image/svg+xml');
  check(!parsed.querySelector('script, foreignObject, [onload], [onclick]') && !window.atlasInjected, 'hostile label created active markup');
  report.links = [...new Set(report.links)];
  report.done = true;
  console.log(`Atlas browser check: ${report.routes.length} routes, ${report.graphs.length} graphs, ${report.errors.length} errors at ${report.width}px`);
  return report;
}
