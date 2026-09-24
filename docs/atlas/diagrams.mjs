import { mermaidSource } from './graph.mjs';
import exemplars from './data/flows-exemplars.mjs';
import hldA from './data/flows-hld-a.mjs';
import hldB from './data/flows-hld-b.mjs';
import modules from './data/flows-modules.mjs';
import other from './data/flows-other.mjs';
import referencesA from './data/flows-references-a.mjs';
import referencesB from './data/flows-references-b.mjs';
import referencesC from './data/flows-references-c.mjs';
import { componentOverview } from './data/overviews.mjs';

export const referenceSlugs = new Set(Object.keys({ ...referencesA, ...referencesB, ...referencesC }));
export const flows = Object.fromEntries(Object.entries({ ...exemplars, ...hldA, ...hldB, ...modules, ...other, ...referencesA, ...referencesB, ...referencesC }).map(([slug, graphs]) => {
  const prior = graphs.filter(g => g.view === 'lr');
  const overview = componentOverview(slug, prior.flatMap(g => g.sources));
  if (overview) overview.notes.push(...prior.flatMap(g => g.notes));
  return [slug, overview ? [overview, ...graphs.filter(g => g.view !== 'lr')] : graphs];
}));
const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const plain = text => text.replace(/\\n/g, ' ');
const sourceLink = s => referenceSlugs.has(s.path.split('/')[1])
  ? `/source/${s.path}#L${s.lines.split(',')[0].split('-')[0]}`
  : `https://github.com/okwalerie/rama-ai-learn/blob/e2bfe2e0dcca2a5b2683fe818acfe15b0c1b8dc5/${s.path}#L${s.lines.split(',')[0].replace('-', '-L')}`;
let engine;
let renderSerial = 0;
const cache = new Map();

export function diagramCard(g) {
  const names = Object.fromEntries(g.nodes.map(n => [n.id, plain(n.label)]));
  return `<article class="graph-card" id="${esc(g.id)}" data-graph="${esc(g.id)}">
    ${g.columns ? '' : `<header>${g.view === 'partition' ? '<span class="section-kicker">State ownership</span>' : ''}<h3>${esc(g.title)}</h3>${g.summary ? `<p>${esc(g.summary)}</p>` : ''}</header>`}
    <div class="graph-tools" role="group" aria-label="Diagram controls for ${esc(g.title)}">
      <button data-action="out" aria-label="Zoom out">−</button><output aria-live="polite" aria-label="Zoom">100%</output><button data-action="in" aria-label="Zoom in">+</button><button data-action="fit">Fit</button><button data-action="actual" aria-label="Zoom to 100%">100%</button><button data-action="expand" aria-pressed="false">Expand</button><button data-action="download" disabled>Save SVG</button>
    </div>
    <div class="graph-viewport" tabindex="0" role="region" aria-label="${esc(g.title)}. Scroll to explore."><div class="graph-canvas"><p class="graph-status" role="status">Rendering diagram…</p></div></div>
    ${g.notes?.length ? `<aside class="graph-notes">${g.notes.map(n => `<p>${esc(n)}</p>`).join('')}</aside>` : ''}
    <footer class="graph-sources">Source: ${g.sources.map(s => `<a href="${esc(sourceLink(s))}" target="_blank" rel="noopener noreferrer">${esc(s.path.split('/').slice(-2).join('/'))}:${esc(s.lines)}</a>`).join(' · ')}</footer>
    <details class="graph-text"><summary>Text version · ${g.edges.length} transitions</summary><ol>${g.edges.map(e => `<li><b>${esc(names[e.from])}</b>: ${esc(plain(e.label || 'then'))} → <b>${esc(names[e.to])}</b></li>`).join('')}</ol></details>
  </article>`;
}

async function getEngine() {
  if (!engine) engine = import('./vendor/mermaid.mjs').then(({ default: mermaid }) => {
    mermaid.initialize({ startOnLoad: false, securityLevel: 'strict', htmlLabels: false, suppressErrorRendering: true, theme: 'base', fontFamily: 'Arial, sans-serif', fontSize: 15, flowchart: { htmlLabels: false, useMaxWidth: false, curve: 'linear', nodeSpacing: 28, rankSpacing: 46, padding: 12 }, themeVariables: { primaryTextColor: '#252b2c', lineColor: '#697371', edgeLabelBackground: '#fafaf7', background: '#fafaf7' } });
    return mermaid;
  });
  return engine;
}

export async function renderGraphs(root) {
  const all = Object.values(flows).flat();
  for (const card of root.querySelectorAll('.graph-card:not([data-rendered])')) {
    const g = all.find(g => g.id === card.dataset.graph);
    if (!g || !card.isConnected) continue;
    card.dataset.rendered = 'pending';
    try {
      const mermaid = await getEngine();
      let svgText = cache.get(g.id);
      if (!svgText) {
        const source = mermaidSource(g);
        await mermaid.parse(source);
        const result = await mermaid.render(`flow${++renderSerial}`, source);
        svgText = result.svg;
        cache.set(g.id, svgText);
      }
      if (!card.isConnected) continue;
      const canvas = card.querySelector('.graph-canvas');
      // Mermaid strict mode sanitizes the SVG; our grammar admits only quoted
      // labels and fixed shapes, never raw diagram source or click directives.
      canvas.innerHTML = svgText;
      const svg = canvas.querySelector('svg');
      svg.setAttribute('role', 'img');
      svg.setAttribute('aria-label', `${g.title}. ${g.summary} A complete text transition list follows.`);
      const box = svg.viewBox.baseVal;
      const width = box.width;
      const height = box.height;
      const viewport = card.querySelector('.graph-viewport');
      let zoom = Math.max(.7, Math.min(1, (viewport.clientWidth - 32) / width));
      const resize = () => {
        svg.style.width = `${width * zoom}px`;
        svg.style.height = `${height * zoom}px`;
        card.querySelector('output').value = `${Math.round(zoom * 100)}%`;
      };
      resize();
      card.querySelector('[data-action="download"]').disabled = false;
      card.querySelectorAll('[data-action]').forEach(button => button.addEventListener('click', () => {
        switch (button.dataset.action) {
          case 'in': zoom = Math.min(2.5, zoom * 1.25); break;
          case 'out': zoom = Math.max(.15, zoom / 1.25); break;
          case 'actual': zoom = 1; break;
          case 'fit': zoom = Math.min(1, (viewport.clientWidth - 32) / width); break;
          case 'expand': {
            const expanded = card.classList.toggle('expanded');
            button.setAttribute('aria-pressed', String(expanded));
            button.textContent = expanded ? 'Close' : 'Expand';
            if (expanded) viewport.focus();
            break;
          }
          case 'download': {
            const url = URL.createObjectURL(new Blob([svgText], { type: 'image/svg+xml' }));
            const link = document.createElement('a');
            link.href = url; link.download = `${g.id}.svg`; link.click();
            setTimeout(() => URL.revokeObjectURL(url), 1000);
            break;
          }
        }
        resize();
      }));
      card.addEventListener('keydown', e => {
        if (e.key === 'Escape' && card.classList.contains('expanded')) card.querySelector('[data-action="expand"]').click();
      });
      let drag;
      viewport.addEventListener('pointerdown', e => {
        if (e.pointerType !== 'mouse' || e.button !== 0) return;
        drag = { x: e.clientX, y: e.clientY, left: viewport.scrollLeft, top: viewport.scrollTop };
        viewport.setPointerCapture(e.pointerId);
      });
      viewport.addEventListener('pointermove', e => {
        if (!drag) return;
        viewport.scrollLeft = drag.left - e.clientX + drag.x;
        viewport.scrollTop = drag.top - e.clientY + drag.y;
      });
      viewport.addEventListener('pointerup', () => { drag = null; });
      viewport.addEventListener('pointercancel', () => { drag = null; });
      card.dataset.rendered = 'ready';
    } catch (error) {
      card.dataset.rendered = 'error';
      card.querySelector('.graph-canvas').innerHTML = `<p class="graph-status" role="alert">Diagram could not render. Read the text version below. ${esc(error.message)}</p>`;
      card.querySelector('details').open = true;
      console.error(`Diagram ${g.id}:`, error);
    }
  }
}
