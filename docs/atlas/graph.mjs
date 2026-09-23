// A deliberately small, data-only flowchart grammar. Neither labels nor evidence
// can supply Mermaid directives, HTML, callbacks, or executable links.
export function graph(id, title, view, summary, sources, nodes, edges, notes = []) {
  const rows = text => {
    const result = [];
    for (const line of text.trim().split('\n').filter(x => x.trim())) {
      if (line.includes('|')) result.push(line.split('|').map(s => s.trim()));
      else {
        const previous = result.at(-1);
        if (!previous) throw new Error('Continuation without a graph row');
        previous[previous.length - 1] += `\\n${line.trim()}`;
      }
    }
    return result;
  };
  return { id, title, view, summary, sources, nodes: rows(nodes).map(([id, kind, ...label]) => ({ id, kind, label: label.join(' | ') })), edges: rows(edges).map(([from, to, ...label]) => ({ from, to, label: label.join(' | ') })), notes };
}

export function mermaidSource(g) {
  const kinds = ['event', 'depot', 'etl', 'state', 'query', 'decision', 'route', 'memory', 'external', 'end'];
  const ids = new Set();
  const quote = value => String(value).split(/\\n/).flatMap(line => {
    const wrapped = [''];
    for (const word of line.split(/\s+/)) {
      if (wrapped.at(-1).length + word.length > 42) wrapped.push(word);
      else wrapped[wrapped.length - 1] += `${wrapped.at(-1) ? ' ' : ''}${word}`;
    }
    return wrapped;
  // Mermaid's SVG-text renderer displays HTML entities literally. Use visible
  // Unicode equivalents for its reserved punctuation, never executable markup.
  }).map(line => line.replace(/<=/g, '≤').replace(/>=/g, '≥')
    .replace(/[&"<>#]/g, char => ({ '&': '＆', '"': '＂', '<': '＜', '>': '＞', '#': '＃' }[char]))).join('<br/>');
  const lines = [`flowchart ${g.view === 'lr' ? 'LR' : 'TD'}`];
  const nodeLines = new Map();
  for (const n of g.nodes) {
    if (!/^[A-Za-z][A-Za-z0-9_]*$/.test(n.id) || ids.has(n.id) || !kinds.includes(n.kind)) throw new Error(`Invalid or duplicate node: ${n.id}`);
    ids.add(n.id);
    const label = `"${quote(n.label)}"`;
    const shape = n.kind === 'decision' ? `{${label}}` : n.kind === 'state' ? `[(${label})]` : ['event', 'end'].includes(n.kind) ? `([${label}])` : `[${label}]`;
    nodeLines.set(n.id, `n_${n.id}${shape}:::role_${n.kind}`);
  }
  if (g.columns) {
    const placed = new Set();
    g.columns.forEach((column, i) => {
      lines.push(`subgraph column${i}["${quote(column.title)}"]`, 'direction TB');
      for (const id of column.nodes) {
        if (!nodeLines.has(id) || placed.has(id)) throw new Error(`Invalid column member: ${id}`);
        lines.push(nodeLines.get(id)); placed.add(id);
      }
      lines.push('end', `style column${i} fill:#f5f6f2,stroke:#dce0dc,color:#252b2c`);
    });
    if (placed.size !== ids.size) throw new Error('Every overview node must belong to one column');
  } else {
    lines.push(...nodeLines.values());
  }
  for (const e of g.edges) {
    if (!ids.has(e.from) || !ids.has(e.to)) throw new Error(`Unknown edge endpoint: ${e.from} → ${e.to}`);
    lines.push(`n_${e.from} -->|"${quote(e.label || 'then')}"| n_${e.to}`);
  }
  lines.push('classDef default fill:#fff,stroke:#89928f,color:#252b2c,stroke-width:1px',
    'classDef depot fill:#f9eee8,stroke:#a3402a,color:#252b2c',
    'classDef event fill:#f9eee8,stroke:#a3402a,color:#252b2c',
    'classDef etl fill:#fff,stroke:#89928f,color:#252b2c',
    'classDef state fill:#e9ece6,stroke:#747f78,color:#252b2c',
    'classDef query fill:#fff,stroke:#89928f,color:#252b2c',
    'classDef decision fill:#f9eee8,stroke:#a3402a,color:#252b2c',
    'classDef route fill:#f5f6f2,stroke:#89928f,color:#252b2c',
    'classDef memory fill:#fff,stroke:#747f78,color:#252b2c,stroke-dasharray:3 3',
    'classDef external fill:#fff,stroke:#89928f,color:#252b2c,stroke-dasharray:6 3');
  return lines.join('\n').replace(/classDef (?!default)(\w+)/g, 'classDef role_$1');
}
