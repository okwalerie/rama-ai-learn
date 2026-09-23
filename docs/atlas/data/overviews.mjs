import { graph } from '../graph.mjs';

// LR deliberately stops at the four component columns. Guards, routing and
// command-specific paths belong to the independently authored TD scenarios.
const hld = {
  'hld-url-shortener': ['*alias-events', 'core', ['$$links'], []],
  'hld-rate-limiter': ['*user-events', 'core', ['$$users'], []],
  'hld-notification-system': ['*events', 'core', ['$$users', '$$submissions', '$$task-pos'], []],
  'hld-web-crawler': ['*events', 'core', ['$$hosts'], []],
  'hld-search-autocomplete': ['*events', 'core', ['$$owner', '$$generation', '$$blocked', '$$phrases', '$$sessions', '$$prefixes'], ['suggest', 'phrase']],
  'hld-file-sync': ['*commands', 'core', ['$$namespaces'], ['file-head']],
  'hld-ticketing-system': ['*commands', 'core', ['$$events'], ['seats', 'hold']],
  'hld-payment-system': ['*commands', 'core', ['$$tenants'], []],
  'hld-stock-exchange': ['*commands', 'core', ['$$symbols'], []],
  'hld-hotel-reservation': ['*commands', 'core', ['$$properties'], ['availability']],
  'hld-metrics-pipeline': ['*series-events', 'metrics', ['$$series'], ['query-raw', 'query-rollup']],
  'hld-ad-click-aggregation': ['*campaign-events', 'click-accounting', ['$$campaigns'], ['windows-in-range']],
  'hld-job-scheduler': ['*execution-events', 'lifecycle', ['$$executions'], []],
  'hld-feature-flag-service': ['*flag-writes', 'flags', ['$$flags'], []],
  'hld-enterprise-rag': ['*writes', 'entities', ['$$docs', '$$chunks', '$$users', '$$postings'], []]
};

function overview(slug, evidence, nodes, edges, notes = []) {
  const g = graph(`${slug}-overview`, 'Components', 'lr', '', evidence, nodes, edges, notes);
  g.columns = ['Depot', 'ETL', 'PState', 'Query'].map((title, i) => ({ title,
    nodes: g.nodes.filter(n => i === 0 ? n.kind === 'depot' : i === 1 ? n.kind === 'etl' : i === 2 ? n.kind === 'state' : ['query','external'].includes(n.kind)).map(n => n.id) }));
  return g;
}

export function componentOverview(slug, evidence) {
  if (hld[slug]) {
    const [depot, etl, states, queries] = hld[slug];
    const nodes = [`depot | depot | ${depot}`, `etl | etl | ${etl}\n(microbatch)`, ...states.map((s,i) => `s${i} | state | ${s}`),
      ...(queries.length ? queries.map((q,i) => `q${i} | query | ${q}`) : ['direct | external | No query topology\nDirect PState client reads'])];
    const edges = ['depot | etl | consume', ...states.map((_,i) => `etl | s${i} | write`)];
    if (slug === 'hld-search-autocomplete') {
      edges.push('s1 | q0 | read', 's5 | q0 | read', 's1 | q1 | read', 's2 | q1 | read', 's3 | q1 | read');
    } else if (queries.length) {
      queries.forEach((_,i) => edges.push(`s0 | q${i} | read`));
    } else {
      states.forEach((s,i) => { if (!['$$task-pos', '$$chunks'].includes(s)) edges.push(`s${i} | direct | read`); });
    }
    return overview(slug, evidence, nodes.join('\n'), edges.join('\n'), queries.length ? ['Direct client reads are shown in TD alongside the query topologies.'] : []);
  }
  const custom = {
    'chat-app': [`
      reg | depot | *register-depot
      actions | depot | *user-actions-depot
      core | etl | core (stream)
      derived | etl | derived (microbatch)
      identity | state | $$handles, $$users\n$$room-names, $$rooms\n$$profiles
      messages | state | $$room-messages\n$$thread-replies
      membership | state | $$room-members, $$user-rooms\n$$room-seq, $$read-cursors
      threads | state | $$room-reply-counts, $$thread-meta\n$$thread-participants\n$$rt-by-act, $$rt-idx
      mentions | state | $$mentions
      pages | query | room-page, thread-page\nmentions-page
      activity | query | recent-threads\nunread-counts
      presence | query | heartbeat, presence\nonline-filter
    `, `
      reg | core | consume
      actions | core | consume
      actions | derived | consume
      core | identity | write
      core | messages | write
      derived | membership | write
      derived | threads | write
      derived | mentions | write
      messages | pages | read
      mentions | pages | read
      identity | pages | profile read
      threads | pages | reply-count read
      threads | activity | read
      membership | activity | read
      identity | presence | heartbeat registration read
    `, ['Presence timestamps live in ephemeral task-global memory, outside PStates.']],
    'fanout': [`
      profile | depot | *profile-depot
      posts | depot | *post-depot
      tick | depot | *process-tick
      writes | etl | writes (stream)
      fanout | etl | fanout (microbatch)
      durable | state | $$profiles\n$$user-posts
      pending | state | $$pending-fanouts
      mirrors | state | Mirrors of SocialGraph:\n$$mirror-control\n$$mirror-followers\n$$mirror-followees
      user | query | get-user-timeline
      timeline | query | get-timeline\nreconstruct-timeline\ncompute-timeline-init
    `, `
      profile | writes | consume
      posts | writes | consume
      posts | fanout | consume
      tick | fanout | trigger
      writes | durable | write
      fanout | pending | write / resume
      fanout | mirrors | read control / followers
      durable | user | post read
      durable | timeline | posts / profiles read
      mirrors | timeline | followees read
    `, ['Mirrors read the separate SocialGraph module. Timeline delivery updates ephemeral cache memory.']],
    'bank-transfer-module': [`
      deposit | depot | *deposit-depot
      transfer | depot | *transfer-depot
      etl | etl | banking (microbatch)
      state | state | $$funds\n$$outgoing-transfers\n$$incoming-transfers
      reads | external | No query topology\nDirect PState client reads
    `, `deposit | etl | consume
      transfer | etl | consume
      etl | state | write
      state | reads | read`],
    'auction-module': [`
      listing | depot | *listing-depot
      bids | depot | *bid-depot
      tick | depot | *expire-tick
      auction | etl | auction (stream)
      expiry | etl | expirations (microbatch)
      active | state | $$user-listings\n$$listing-bidders\n$$listing-top-bid, $$user-bids
      expired | state | $$finished-listings\n$$notifications
      scheduler | state | Scheduler helper state\n(library-owned schema)
      reads | external | No query topology\nDirect PState client reads
    `, `listing | auction | consume
      bids | auction | consume
      listing | expiry | consume
      tick | expiry | trigger
      auction | active | write
      expiry | expired | write
      expiry | scheduler | schedule / expire
      active | reads | read
      expired | reads | read`],
    'time-series-module-hard': [`
      depot | depot | *render-latency-depot
      etl | etl | timeseries (microbatch)
      state | state | $$window-stats
      query | query | get-stats-for-range
    `, `depot | etl | consume
      etl | state | write
      state | query | read`],
    'unbalanced-social-graph': [`
      depot | depot | *follow-depot
      etl | etl | social-graph (stream)
      state | state | $$partitioned-followers-control\n$$partitioned-follower-tasks\n$$partitioned-followers\n$$user-followees
      query | query | get-followers
    `, `depot | etl | consume
      etl | state | write
      state | query | read control / followers`],
    'social-graph-and-fanout': [`
      follow | depot | *follow-depot
      profile | depot | *profile-depot
      posts | depot | *post-depot
      tick | depot | *process-tick
      graph | etl | social-graph (stream)
      writes | etl | writes (stream)
      fanout | etl | fanout (microbatch)
      social | state | $$partitioned-followers-control\n$$partitioned-follower-tasks\n$$partitioned-followers\n$$user-followees
      durable | state | $$profiles, $$user-posts
      pending | state | $$pending-fanouts
      followers | query | get-followers
      user | query | get-user-timeline
      timeline | query | get-timeline\nreconstruct-timeline\ncompute-timeline-init
    `, `follow | graph | consume
      profile | writes | consume
      posts | writes | consume
      posts | fanout | consume
      tick | fanout | trigger
      graph | social | write
      writes | durable | write
      fanout | pending | write / resume
      fanout | social | read control / followers
      social | followers | read
      social | timeline | followees read
      durable | timeline | posts / profiles read
      durable | user | post read`, ['All PStates are local to one module; no mirrors are declared. Ephemeral timeline cache is detailed in TD.']],
    'diagnose-stream-runtime-fail': [`
      depot | depot | *payments
      etl | etl | payments (stream)
      state | state | $$payment-totals
      query | external | No query topology\nStream append acknowledgement
    `, `depot | etl | consume
      etl | state | write after parse
      state | query | completed transform precedes ack`, ['Diagnostic fixture only; the malformed amount fails before any PState write. See TD for the failure path.']]
  };
  return custom[slug] ? overview(slug, evidence, ...custom[slug]) : null;
}
