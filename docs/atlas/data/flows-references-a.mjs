import { graph as g } from '../graph.mjs';
const src = (name, lines) => [{ path: `challenges/${name}/test-resources/upstream/nlb/${name.replaceAll('-', '_')}.clj`, lines }];
const overview = (id, summary, sources, nodes, edges) => {
  const graph = g(id, summary, 'lr', summary, sources, nodes, edges);
  graph.columns = ['Depot', 'ETL', 'PState', 'Query'].map((title, i) => ({ title,
    nodes: graph.nodes.filter(n => i === 0 ? n.kind === 'depot' : i === 1 ? n.kind === 'etl' : i === 2 ? n.kind === 'state' : ['query', 'external'].includes(n.kind)).map(n => n.id) }));
  return graph;
};

export default {
  'family-tree': [
    overview('ft-overview', 'Family tree writes and graph queries', src('family-tree', '13-32,34-62'), `
      people | depot | *people-depot\nhash(:id)
      core | etl | core stream
      tree | state | $$family-tree\nUUID → parents, name, child UUID set
      ancestors | query | ancestors(start-id, generations)
      descendants | query | descendants-count(start-id, generations)
    `, `
      people | core | consume Person
      core | tree | write person by id; update each parent child set
      tree | ancestors | parent traversal reads
      tree | descendants | child traversal reads
    `),
    g('ft-descendants', 'Count descendants by path depth', 'td', 'The loop emits each node’s child count before expanding its children. It treats branches as paths and sums counts by depth. Source tests show {0 0} for a leaf with a positive generation limit.', src('family-tree', '51-62'), `
      query | query | descendants-count(start, generations)
      walk | etl | Start at depth 0; follow each child path
      bound | decision | depth < generations?
      read | state | $$family-tree[id].children
      count | etl | Emit depth and child count
      expand | etl | Explode children; depth + 1
      result | query | Sum counts by depth
    `, `
      query | walk | invoke
      walk | bound | check depth
      bound | read | continue when true
      read | count | select child set
      count | expand | emit count, then expand
      expand | walk | each child path
      count | result | sum at origin
    `),
    g('ft-partitions', 'UUID ownership and nested child sets', 'partition', 'The depot routes people by ID. Parent-child updates hash each parent UUID; both relationships sit in one UUID-keyed schema.', src('family-tree', '13-32,34-48'), `
      depot | depot | *people-depot hash(:id)
      person | state | $$family-tree[UUID]\nparent1: UUID; parent2: UUID; name: String; children: #{UUID}
      edge | etl | Exploded parent-child edge
      parentRoute | route | Hash parent UUID for child-set update
      ancestorQuery | query | ancestors hashes traversed person UUIDs
    `, `
      depot | person | Route by person ID
      person | edge | record includes parent fields
      edge | parentRoute | Hash by parent UUID
      parentRoute | person | Add child ID to parent set
      ancestorQuery | person | each graph lookup is keyed by UUID
    `)
  ],
  'collaborative-document-editor': [
    overview('editor-overview', 'Document edits and version query', src('collaborative-document-editor', '91-129'), `
      edits | depot | *edit-depot hash(:id)
      core | etl | core stream
      docs | state | $$docs\nLong → String
      history | state | $$edits\nLong → subindexed Edit vector
      read | query | doc+version(id)
    `, `
      edits | core | consume Edit
      core | docs | apply accepted operations
      core | history | append final applied edit vector
      docs | read | select current text
      history | read | count operations
    `),
    g('editor-transform', 'Apply current edits or transform stale edits', 'td', 'Current-version edits apply directly. For stale edits, the source selects history from the submitted version to latest-exclusive, transforms against missed edits, applies the result, then appends the final vector.', src('collaborative-document-editor', '64-80,103-121'), `
      edit | event | Edit(id, version, offset, action)
      history | state | $$edits[id], latest count
      range | state | $$edits[id][version..latest)
      current | decision | version equals latest?
      direct | etl | Use submitted edit vector
      stale | etl | Transform add/remove against missed edits
      docs | state | $$docs[id] or empty string
      apply | etl | Apply final vector and append it to history
    `, `
      edit | history | select current count
      history | current | compare version
      current | direct | yes
      current | range | no
      range | stale | transform operation
      direct | docs | apply
      stale | docs | apply
      docs | apply | write document and append final edits
    `),
    g('editor-partitions', 'Document key and nested edit history', 'partition', 'The depot and both PStates use document ID as their outer key. The query topology hashes the same ID.', src('collaborative-document-editor', '93-102,123-128'), `
      depot | depot | *edit-depot hash(:id)
      doc | state | $$docs[Long] → String
      history | state | $$edits[Long] → subindexed vector<Edit>
      docQuery | query | doc+version hashes document ID
    `, `
      depot | doc | Update by document ID
      depot | history | Append under document ID
      docQuery | doc | select text by ID
      docQuery | history | select count by ID
    `)
  ],
  'content-moderation': [
    overview('moderation-overview', 'Recipient posts, reader mutes, and filtered page query', src('content-moderation', '14-49'), `
      postsIn | depot | *post-depot hash(:to-user-id)
      muteIn | depot | *mute-depot hash(:user-id)
      core | etl | core stream
      posts | state | $$posts\nrecipient → subindexed Post vector
      mutes | state | $$mutes\nreader → subindexed author set
      page | query | get-posts → helper
      helper | query | get-posts-helper
    `, `
      postsIn | core | consume and append by recipient
      muteIn | core | consume and add or remove author
      core | posts | write timeline
      core | mutes | Write reader mute set
      posts | page | read raw feed count
      posts | helper | read raw range
      mutes | helper | filter current authors
      page | helper | invoke bounded range query
    `),
    g('moderation-scenarios', 'Filter muted authors while scanning a page', 'td', 'The query checks mute state at read time, so unmuting can reveal historical posts. It scans raw-feed offsets until the page is full or the feed ends.', src('content-moderation', '30-38,40-72'), `
      event | event | Mute(reader, author) / Unmute
      state | state | $$mutes[reader]
      feed | state | $$posts[reader]
      page | query | get-posts(reader, offset, limit)
      helper | query | get-posts-helper(raw range)
      muted | decision | Author in current mute set?
      visible | etl | Collect visible posts
      end | decision | Feed end or page full?
      result | end | Posts and next raw offset
    `, `
      event | state | Add or remove author
      page | feed | read raw count
      page | helper | Request bounded slice
      feed | helper | read raw range
      state | helper | read author mute membership
      helper | muted | Check each author
      muted | visible | no: include
      muted | end | yes: omit and continue
      visible | end | append visible post
      end | result | nil at feed end; raw continuation when full
    `),
    g('moderation-partitions', 'Recipient feed and reader mute ownership', 'partition', 'Posts and mutes use recipient or reader ID as their key. The query hashes the reader ID, which is also the feed recipient here.', src('content-moderation', '14-24,40-46,50-58'), `
      postDepot | depot | *post-depot hash(to-user-id)
      muteDepot | depot | *mute-depot hash(user-id)
      feed | state | $$posts[Long] → subindexed vector<Post>
      mutes | state | $$mutes[Long] → subindexed set<Long>
      query | query | get-posts-helper hashes reader/recipient ID
    `, `
      postDepot | feed | Route by recipient and append
      muteDepot | mutes | Store muted authors by reader
      query | feed | hash reader ID; select raw slice
      query | mutes | hash reader ID; select author membership
    `)
  ]
};
