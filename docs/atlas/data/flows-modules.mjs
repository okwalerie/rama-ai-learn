import { graph as g } from '../graph.mjs';
import exemplars from './flows-exemplars.mjs';
const src = (slug, lines) => [{ path: `challenges/${slug}/test-resources/${slug.replaceAll('-', '_')}/module.clj`, lines }];
const bank = lines => src('bank-transfer-module', lines);
const auction = lines => src('auction-module', lines);
const time = lines => src('time-series-module-hard', lines);

// These references actually share the inspected social-graph/cache algorithms.
// Adapt only the evidenced module boundary and routing differences, not a generic
// event→ETL→state template. The combined implementation uses local hash/direct.
const combined = exemplars.fanout.filter(x => x.id !== 'fanout-lr').map(original => {
  const replace = text => text
    .replaceAll('hash$$ mirror-control', 'hash')
    .replaceAll('direct$$ mirror-followers', 'direct')
    .replaceAll('hash$$ mirror-followees', 'hash')
    .replaceAll('$$mirror-followees', '$$user-followees')
    .replaceAll('mirror-control', '$$partitioned-followers-control')
    .replaceAll('mirror-followers', '$$partitioned-followers')
    .replaceAll('mirror-followees', '$$user-followees')
    .replaceAll('SocialGraph', 'same-module social graph')
    .replaceAll('Fanout', 'same module')
    .replaceAll('remote task', 'local module task')
    .replaceAll('remote read; local position unchanged', 'local read on follower shard')
    .replaceAll('local mirror select', 'local PState read')
    .replaceAll('task-specific mirror select', 'local follower shard read')
    .replaceAll('bounded mirror read', 'bounded local read')
    .replaceAll('mirror shards', 'local follower shards');
  const titles = {
    'fanout-new': 'New post: self and local follower-shard branches',
    'fanout-resume': 'Resume pending cursors on their local follower shards',
    'fanout-read': 'Warm timeline versus local-state reconstruction',
    'fanout-social': 'Same-module follow/unfollow and shard allocation',
    'fanout-partitions': 'One module: graph ownership, continuation state, ephemeral cache'
  };
  const summaries = {
    'fanout-new': 'One module: post fanout hashes the poster to read the control list, then direct-routes to each follower shard. Continuation cursors are written on that shard. Self, new followers and resumed followers unify before target-key grouping.',
    'fanout-resume': 'Tick or post microbatch scans pending entries on all tasks, direct-routes to the stored follower task, then reads and advances/removes the cursor there. Unlike Fanout mirrors, direct routing moves execution within this module.',
    'fanout-read': 'Cold reconstruction reads this module’s own $$user-followees; no mirror or separate SocialGraph module exists. It takes 300 followees × 10 recent post IDs and retains the newest 800. Self posts are not explicitly added on this cold path.',
    'fanout-social': 'The social-graph stream is in the same module as writes and fanout. Follow/unfollow maintains sharded follower sets; removing a follower retains the allocation assignment and control metadata.',
    'fanout-partitions': 'All state is in one module. hash(account), hash(target) and direct(allocated shard) select local tasks. Pending fanouts reside on follower shards; cache delivery groups by recipient. No mirrors are declared.'
  };
  const ranges = { 'fanout-new': '336-380', 'fanout-resume': '303-335', 'fanout-read': '402-448', 'fanout-social': '63-87,237-284', 'fanout-partitions': '227-344' };
  return { ...original, id: `combined-${original.id}`, title: titles[original.id], summary: summaries[original.id], sources: src('social-graph-and-fanout', ranges[original.id]), nodes: original.nodes.map(n => ({ ...n, kind: n.kind === 'external' ? 'etl' : n.kind, label: n.id === 'local' ? 'Local follower shard task; all-task scan resumes here' : replace(n.label) })), edges: original.edges.map(e => ({ ...e, label: replace(e.label) })), notes: original.notes.map(replace) };
});

export default {
  'bank-transfer-module': [
    g('bank-lr', 'Deposits and cross-user transfers', 'lr', 'Banking is a microbatch topology. Incoming and outgoing transfer records are written on both success and failure; only successful transfers move funds. Reads are direct foreign PState selections.', bank('14-99'), `
      client | external | BankTransfer client
      deposits | depot | *deposit-depot: hash(user-id)
      transfers | depot | *transfer-depot: hash(from-user-id)
      deposit | etl | banking: sum deposited amount
      transfer | etl | banking: check sender balance
      funds | state | $$funds: user → Long
      outgoing | state | $$outgoing-transfers[from][transfer-id]
      incoming | state | $$incoming-transfers[to][transfer-id]
      recipient | route | hash(to-user-id)
      reads | external | Direct balance / incoming / outgoing reads
    `, `
      client | deposits | deposit!
      client | transfers | transfer!
      deposits | deposit | consume
      deposit | funds | add amount
      transfers | transfer | consume
      funds | transfer | read sender balance, default 0
      transfer | funds | success: debit sender
      transfer | outgoing | persist success flag either way
      transfer | recipient | carry success and amount
      recipient | funds | success: credit recipient
      recipient | incoming | persist success flag either way
      funds | reads | get-balance
      outgoing | reads | get-outgoing-transfers
      incoming | reads | get-incoming-transfers
      reads | client | return data
    `),
    g('bank-transfer', 'Transfer: success and insufficient-funds paths rejoin', 'td', 'The protocol assumes each transfer ID is appended only once. Accordingly, the reference has no explicit transfer-id deduplication gate; history keys are not an additional idempotency promise.', bank('44-70'), `
      start | depot | Transfer from *transfer-depot
      read | etl | On sender task: read $$funds[from] or 0
      success | decision | balance ≥ amount?
      debit | state | $$funds[from] := balance − amount
      failure | etl | success? := false; preserve balance
      outgoing | state | Write $$outgoing-transfers[from][id]\nto-user-id, amount, success?
      route | route | hash(to-user-id)
      credit | decision | success?
      funds | state | Add amount to $$funds[to]
      incoming | state | Write $$incoming-transfers[to][id]\nfrom-user-id, amount, success?
      done | end | Microbatch processing barrier
    `, `
      start | read | hash(from-user-id)
      read | success | compare
      success | debit | yes
      success | failure | no
      debit | outgoing | success? true
      failure | outgoing | success? false
      outgoing | route | cross-user transition
      route | credit | use same decision
      credit | funds | yes
      credit | incoming | no
      funds | incoming | history written after credit
      incoming | done | processing completes
    `),
    g('bank-partitions', 'User-keyed money and transfer histories', 'partition', 'String user IDs select ownership. Both histories use subindexed transfer-id maps; sender and recipient can hash to different tasks.', bank('14-36'), `
      sender | route | hash(from-user-id)
      funds | state | $$funds[from]: Long balance
      outgoing | state | $$outgoing-transfers[from]\nString transfer-id → to-user-id, amt, success?
      recipient | route | hash(to-user-id)
      recipientFunds | state | $$funds[to]: Long balance
      incoming | state | $$incoming-transfers[to]\nString transfer-id → from-user-id, amt, success?
    `, `
      sender | funds | local balance
      sender | outgoing | local history
      sender | recipient | transfer carries result across hash hop
      recipient | recipientFunds | local balance
      recipient | incoming | local history
    `)
  ],
  'auction-module': [
    g('auction-lr', 'Listings, bids, scheduled expirations, and notifications', 'lr', 'The auction stream and expirations microbatch independently consume listings. Scheduler internals are library-owned; this source declares them through TopologyScheduler rather than exposing their schema.', auction('72-169'), `
      client | external | Auction client
      listing | depot | *listing-depot: hash(seller)
      bid | depot | *bid-depot: hash(listing seller)
      tick | depot | *expire-tick: every 30 seconds
      stream | etl | auction stream
      listings | state | $$user-listings
      bids | state | $$listing-bidders / $$listing-top-bid
      history | state | $$user-bids
      expiration | etl | expirations microbatch\nTopologyScheduler callbacks
      scheduler | state | $$scheduler helper PStates\nschema delegated to library
      finished | state | $$finished-listings
      notifications | state | $$notifications
      reads | external | Direct foreign PState selections
    `, `
      client | listing | list-item!
      client | bid | bid!
      listing | stream | store listing
      stream | listings | seller-keyed write
      bid | stream | reject finished; update bids
      finished | stream | read finished flag
      stream | bids | seller task writes
      stream | history | hash(bidder): write
      listing | expiration | schedule listing deadline
      expiration | scheduler | scheduleItem
      tick | expiration | handleExpirations
      scheduler | expiration | due listing callback
      expiration | finished | mark finished
      bids | expiration | winner + all bidder IDs
      expiration | notifications | seller, winner and loser results
      listings | reads | seller listings
      bids | reads | explicit pkey = seller
      notifications | reads | user notifications
      reads | client | protocol maps
    `),
    g('auction-bid', 'Bid: finished gate and independently maintained top bid', 'td', 'The reference overwrites a bidder’s stored amount on every unfinished bid, while the highest-bid record only changes for a strictly greater amount. It does not check listing existence here. Those are source behaviors, not the stronger README contract.', auction('109-124'), `
      depot | depot | Bid from *bid-depot
      owner | route | Arrive on hash(listing-id.user-id)
      closed | decision | $$finished-listings[partial-id] true?
      stop | end | Stop; no writes
      bidders | state | $$listing-bidders[partial-id][bidder]\n:= amount, unconditional overwrite
      top | decision | amount > current top amount (or 0)?
      replace | state | $$listing-top-bid := bidder, amount
      route | route | hash(bidder-id)
      history | state | $$user-bids[bidder][ListingId] := amount
    `, `
      depot | owner | partition by nested seller
      owner | closed | local finished read
      closed | stop | yes
      closed | bidders | no
      bidders | top | conditional transform
      top | replace | yes
      top | route | no: top unchanged
      replace | route | continue
      route | history | local write
    `),
    g('auction-expire', 'Expiration tick: sale/no-sale, winner/loser notification fanout', 'td', 'A listing event schedules its ListingId and deadline. A tick invokes the helper’s due-item callback; the source does not spell out helper routing or storage internals.', auction('125-169'), `
      listing | depot | Listing event
      schedule | etl | TopologyScheduler.scheduleItem\n(deadline, ListingId)
      tick | depot | *expire-tick
      callback | etl | handleExpirations callback\nfor due ListingId
      finished | state | $$finished-listings[partial-id] := true
      top | etl | Read $$listing-top-bid[partial-id]
      winner | decision | winner-id exists?
      sold | etl | Build seller :sale notification
      unsold | etl | Build seller :no-sale notification
      seller | state | Append $$notifications[seller]
      bidders | etl | Enumerate $$listing-bidders keys\nallow-yield enabled
      route | route | hash(bidder-id)
      won | decision | bidder-id = winner-id?
      notify | state | Append $$notifications[bidder]\n:won with amount or :lost
    `, `
      listing | schedule | expirations consumes listing
      tick | callback | trigger due processing
      schedule | callback | helper supplies due item
      callback | finished | local callback write
      finished | top | read winner
      top | winner | branch on winner ID
      winner | sold | yes
      winner | unsold | no
      sold | seller | append
      unsold | seller | append
      seller | bidders | scan bidders for listing
      bidders | route | each bidder
      route | won | select notification type
      won | notify | yes: won
      won | notify | no: lost
    `),
    g('auction-partitions', 'Seller ownership differs from the top-level listing key', 'partition', 'Listing-scoped PStates use partial UUID keys but are colocated by seller, not by hashing that UUID. Client bid queries explicitly supply the seller as pkey.', auction('72-99,125-129,201-218'), `
      seller | route | hash(seller user-id)
      listings | state | $$user-listings[seller]\nUUID partial-id → item, expiration\nsubindexed map
      bidders | state | $$listing-bidders[partial UUID]\nLong bidder → amount; subindexed
      top | state | $$listing-top-bid[partial UUID]\nuser-id, amount
      finished | state | $$finished-listings[partial UUID] → Boolean
      bidder | route | hash(bidder-id)
      history | state | $$user-bids[bidder]\nListingId → amount; subindexed
      user | route | hash(notification user-id)
      notifications | state | $$notifications[user]\nsubindexed vector(AuctionResult)
    `, `
      seller | listings | listing depot alignment
      seller | bidders | bid depot alignment
      seller | top | same seller ownership
      seller | finished | due-listing callback writes
      bidder | history | explicit hash hop
      user | notifications | seller local / bidder hash hop
    `)
  ],
  'time-series-module-hard': [
    g('timeseries-lr', 'Four rollup granularities, one URL owner', 'lr', 'Each measurement contributes to minute, hour, day and fixed 30-day buckets. Queries combine a non-overlapping cover, not all four levels for the same minutes.', time('48-138'), `
      client | external | TimeSeries client
      depot | depot | *render-latency-depot: hash(url)
      etl | etl | timeseries microbatch\nmake stat, emit four granularities
      state | state | $$window-stats\nurl → granularity → bucket → WindowStats
      query | query | get-stats-for-range
      cover | etl | query-granularities\ncoarsest aligned interiors + finer fringes
      combine | etl | origin: combine cardinality, total, min, max
    `, `
      client | depot | record-latency!
      depot | etl | consume
      etl | state | combine at each emitted bucket
      client | query | URL and half-open minute range
      query | cover | hash(url)
      cover | state | read selected sorted bucket ranges
      state | combine | bucket stats
      combine | client | aggregate WindowStats
    `),
    g('timeseries-write', 'Measurement: emit and merge every index granularity', 'td', 'This path has no event-type rejection branch in the supplied implementation. One RenderLatency becomes four index updates; the combiner explicitly handles nil accumulators.', time('10-62,103-124'), `
      depot | depot | RenderLatency(url, latency, timestamp)
      stat | etl | Make WindowStats(1, latency, latency, latency)
      emit | etl | Emit all four bucket indices
      minute | etl | :m = timestamp / 60000
      hour | etl | :h = minute / 60
      day | etl | :d = hour / 24
      month | etl | :td = day / 30
      merge | decision | Existing aggregate nil?
      first | etl | Use incoming WindowStats
      combine | etl | Sum cardinality and total\nmin/min and max/max, ignoring nil
      state | state | $$window-stats[url][granularity][bucket]
    `, `
      depot | stat | URL task, timeseries microbatch
      stat | emit | derive bucket IDs
      emit | minute | emit
      emit | hour | emit
      emit | day | emit
      emit | month | emit
      minute | merge | :m bucket
      hour | merge | :h bucket
      day | merge | :d bucket
      month | merge | :td bucket
      merge | first | yes
      merge | combine | no
      first | state | write merged value
      combine | state | write merged value
    `),
    g('timeseries-query', 'Range query: recursively select aligned rollups without overlap', 'td', 'The recursive range cover is the important branch. Partial edge buckets stay at the finer level; only a nonempty aligned interior recurses.', time('66-98,126-138'), `
      request | event | get-stats-for-range(url, start-minute, end-minute)
      hash | route | hash(url)
      level | decision | Coarser granularity available?
      whole | etl | Emit current range at this granularity
      align | etl | Compute ceiling start / floor end\nat next granularity
      interior | decision | Aligned end > aligned start?
      recurse | etl | Recurse on coarser interior
      fringes | etl | Emit nonempty left/right fringes\nat current granularity
      read | state | Read $$window-stats[url][granularity]\nsorted-map-range(start, end)
      origin | etl | origin: combine all bucket stats
      done | end | Return count, total, min, max
    `, `
      request | hash | invoke query
      hash | level | start at :m
      level | whole | no: :td
      level | align | yes
      align | interior | check aligned interior
      interior | whole | no: use current range
      interior | recurse | yes
      recurse | level | next coarser level
      interior | fringes | yes: independent edge ranges
      whole | read | emitted range
      fringes | read | emitted ranges
      read | origin | bucket stats
      origin | done | merge identity for empty data
    `),
    g('timeseries-partition', 'Nested rollups on the URL task', 'partition', 'The granularity map has four keyword keys; each bucket map is subindexed. Thirty-day buckets are fixed divisions, not calendar months.', time('103-115'), `
      key | route | hash(url String)
      root | state | $$window-stats[url]
      minute | state | :m → Long minute bucket → WindowStats
      hour | state | :h → Long hour bucket → WindowStats
      day | state | :d → Long day bucket → WindowStats
      month | state | :td → Long 30-day bucket → WindowStats
    `, `
      key | root | depot and query alignment
      root | minute | subindexed map
      root | hour | subindexed map
      root | day | subindexed map
      root | month | subindexed map
    `)
  ],
  'unbalanced-social-graph': [
    g('social-lr', 'Sharded followers with a target-owned directory', 'lr', 'A single stream maintains inverse membership, target-owned allocation metadata and direct-task follower shards. There are no cross-module mirrors in this challenge.', src('unbalanced-social-graph', '70-147'), `
      client | external | SocialGraph client
      depot | depot | *follow-depot: hash(account-id)
      stream | etl | social-graph stream\nFollowAccount / RemoveFollowAccount
      followees | state | $$user-followees[account]
      home | route | hash(target-id)
      directory | state | $$partitioned-followers-control\n$$partitioned-follower-tasks
      shard | route | direct(assigned task)
      followers | state | $$partitioned-followers[target]
      query | query | get-followers
      origin | etl | origin: set aggregate
    `, `
      client | depot | follow! / unfollow!
      depot | stream | full-ack stream
      stream | followees | add/delete target
      stream | home | target allocation lookup
      directory | home | read or allocate assignment
      home | directory | new follow: write allocation
      home | shard | selected task ID
      shard | followers | add/delete follower
      client | query | get-followers(target)
      directory | query | hash(target): control task list
      query | followers | direct(each task): enumerate shard
      followers | origin | follower IDs
      origin | client | union set
      followees | client | direct foreign select get-followees
    `),
    { ...exemplars.fanout.find(x => x.id === 'fanout-social'), id: 'unbalanced-social-branches', title: 'Follow/unfollow: allocate or reuse a follower shard', summary: 'This challenge implements the social graph itself. Existing assignment reuse and remove behavior match the inspected provided SocialGraph dependency, but no Fanout module participates.', sources: src('unbalanced-social-graph', '45-132') },
    g('social-partitions', 'Target directory, direct follower shards, account inverse', 'partition', 'The same target ID can appear in follower sets on multiple tasks. Hashing the target alone does not find all followers; the control list is the fanout directory.', src('unbalanced-social-graph', '70-86'), `
      account | route | hash(account-id)
      followees | state | $$user-followees\nLong account → subindexed set(Long target)
      target | route | hash(target-id)
      tasks | state | $$partitioned-followers-control\nLong target → List(task-id)
      assignment | state | $$partitioned-follower-tasks\nLong target → subindexed follower → task-id
      direct | route | direct(task-id from directory)
      followers | state | $$partitioned-followers\nLong target → subindexed set(follower)\nonly this task's assigned followers
    `, `
      account | followees | inverse index
      target | tasks | ordered allocation list
      target | assignment | retained per-follower assignment
      tasks | direct | enumerate shard IDs
      direct | followers | local shard
    `)
  ],
  'social-graph-and-fanout': [
    g('combined-lr', 'One module: graph maintenance, durable writes, memory-only fanout', 'lr', 'Unlike the standalone Fanout challenge, the control/follower/followee PStates are local and there are no mirrors. Follow processing is a stream; only timeline delivery runs asynchronously in fanout microbatches.', src('social-graph-and-fanout', '227-491'), `
      client | external | SocialApp client
      follow | depot | *follow-depot
      profile | depot | *profile-depot
      post | depot | *post-depot
      tick | depot | *process-tick: 1 second
      graph | etl | social-graph stream
      graphState | state | control / follower assignments\nsharded followers / user followees
      writes | etl | writes stream
      durable | state | $$profiles / $$user-posts
      fanout | etl | fanout microbatch\nresume + current batches
      pending | state | $$pending-fanouts on follower shards
      cache | memory | *cache: user → 800-entry ring
      timeline | query | get-timeline → reconstruct-timeline\n→ compute-timeline-init if cold
      followers | query | get-followers: control → shards → origin
      userPosts | query | get-user-timeline
    `, `
      client | follow | follow / unfollow
      client | profile | set-profile!
      client | post | post!
      follow | graph | hash(account-id)
      graph | graphState | inverse, directory, shard updates
      profile | writes | hash(account-id)
      post | writes | hash(account-id)
      writes | durable | local profile/post writes
      post | fanout | independent microbatch source
      tick | fanout | continue pending work
      graphState | fanout | hash(poster) then direct(shard)
      pending | fanout | all-task resume scan
      fanout | pending | advance or delete shard cursor
      fanout | cache | group by target; initialized only
      client | timeline | timeline page request
      graphState | timeline | cold: ≤300 local followees
      durable | timeline | cold: 10 posts each; warm: hydrate page
      timeline | cache | initialize if cold; read page of 20
      timeline | client | preserve page order; current names
      client | followers | request follower union
      graphState | followers | local directory and shard reads
      followers | client | follower set
      client | userPosts | account post history
      durable | userPosts | read all post contents
      userPosts | client | chronological contents
    `),
    ...combined
  ]
};
