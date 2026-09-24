import { graph as g } from '../graph.mjs';

const source = (slug, path, lines) => [{ path: `challenges/${slug}/${path}`, lines }];
const ref = (slug, lines) => source(slug, 'test-resources/' + ({
  'who-to-follow': 'nlb/who_to_follow.clj',
  'timed-notifications': 'nlb/timed_notifications.clj',
  'top-users-module': 'rama/gallery/top_users_module.clj'
}[slug]), lines);
const protocol = (slug, lines) => source(slug, 'src/' + ({
  'who-to-follow': 'who_to_follow/protocol.clj',
  'timed-notifications': 'timed_notifications/protocol.clj',
  'top-users-module': 'top_users_module/protocol.clj'
}[slug]), lines);

function overview(slug, title, summary, evidence, nodes, edges) {
  const graph = g(`${slug}-lr`, title, 'lr', summary, evidence, nodes, edges);
  graph.columns = ['Depot', 'ETL', 'PState', 'Query'].map((title, i) => ({
    title,
    nodes: graph.nodes.filter(n => i === 0 ? n.kind === 'depot' : i === 1 ? n.kind === 'etl' : i === 2 ? n.kind === 'state' : ['query', 'external'].includes(n.kind)).map(n => n.id)
  }));
  return graph;
}

export default {
  'who-to-follow': [
    overview('who-to-follow', 'Follow events, recommendation sweep, and direct reads',
      'A follow stream stores relationships. Each task scans up to 15 accounts per tick and counts two-hop candidates. Clients read the recommendation PState directly.',
      [...ref('who-to-follow', '12-86'), ...protocol('who-to-follow', '4-9')], `
        follows | depot | *follows-depot\nhash(:from)
        tick | depot | *who-to-follow-tick\n30s production / test append depot
        core | etl | core (stream)
        sweep | etl | who-to-follow (microbatch)
        graph | state | $$follows\naccount → followed-ID set
        cursor | state | $$next-id\nsweep continuation key
        recs | state | $$who-to-follow\naccount → recommendation vector
        client | external | recommendations(account)\ndirect PState select
      `, `
        follows | core | Consume and add membership
        core | graph | write adjacency set
        tick | sweep | Start scheduled sweep
        graph | sweep | Scan graph page and count two-hop candidates
        cursor | sweep | Read task-local cursor
        sweep | cursor | Advance or reset task-local cursor
        sweep | recs | Write filtered recommendations
        recs | client | Read recommendations directly
      `),
    g('who-to-follow-refresh-td', 'Rank recommendations from a bounded scan', 'td',
      'Each task scans at most 15 accounts per tick. Candidates rank by how many followed accounts follow them. Keep 1,000 before excluding existing follows, then cap results at 300.',
      ref('who-to-follow', '12-71'), `
        tick | event | Production timer or explicit test tick
        page | state | Read $$next-id and up to 15 sorted $$follows entries
        continue | decision | Page has fewer than 15 account keys?
        rewind | state | Reset $$next-id to −1
        advance | state | Set cursor to last scanned account key
        expand | etl | Expand account and followed-ID pairs
        reverse | route | Hash followed ID; read its outgoing follows
        self | decision | Is the candidate the account itself?
        drop | end | Discard self candidate
        count | etl | Count candidates by account ID
        top | etl | Sort by popularity; keep 1,000 before filtering
        followed | decision | Does the requester follow this candidate?
        skip | etl | Exclude candidates already followed
        choose | etl | Append candidates until the result reaches 300
        result | state | Store recommendations by requesting account
      `, `
        tick | page | trigger sweep
        page | continue | Check page length
        continue | rewind | yes
        continue | advance | no
        rewind | expand | finish scan page
        advance | expand | finish scan page
        expand | reverse | Route by candidate followee
        reverse | self | candidate relationship
        self | drop | same account
        self | count | different account
        count | top | Rank two-hop counts
        top | followed | 1000 candidates selected
        followed | skip | already followed
        followed | choose | not followed
        skip | followed | Check next candidate
        choose | followed | Check next candidate if below cap
        skip | result | Candidates exhausted
        choose | result | Empty, cap reached, or candidates exhausted
      `),
    g('who-to-follow-partition', 'Follow ownership and recommendation ownership', 'partition',
      'Each source account owns its follow set. Candidate discovery reads outgoing follows for each followed ID, then returns counts to the requesting account.',
      ref('who-to-follow', '25-43,68-86'), `
        depot | depot | *follows-depot\nhash(:from)
        from | route | Source account key
        follows | state | $$follows[from] → set(to)
        reverse | route | Hash(to) for outgoing-follow scan
        account | route | Hash(requesting account)
        rank | etl | Candidate popularity aggregate
        output | state | $$who-to-follow[requesting account]
        cursor | state | $$next-id sweep cursor
        reader | external | Direct account-key recommendation select
      `, `
        depot | from | Route by source account
        from | follows | Store follow set
        follows | reverse | Find second-degree candidates
        reverse | account | Return counts to requester
        account | rank | Aggregate by requester
        rank | output | Write recommendations
        cursor | rank | Limit each task page
        output | reader | Select directly
      `)
  ],
  'timed-notifications': [
    overview('timed-notifications', 'Scheduled posts, timer processing, and direct feed reads',
      'The stream schedules records in TopologyScheduler. Timer expirations append posts to account feed vectors. Clients read feeds directly from PState.',
      [...ref('timed-notifications', '8-37'), ...protocol('timed-notifications', '4-8')], `
        scheduled | depot | *scheduled-post-depot\nhash(:id)
        tick | depot | *tick\n1s production / test append depot
        core | etl | core (stream)
        scheduler | state | TopologyScheduler state\n$$scheduled
        feeds | state | $$feeds\naccount → ordered post vector
        client | external | feed(account)\ndirect PState select
      `, `
        scheduled | core | Consume and schedule by time-millis
        tick | core | Process expirations
        core | scheduler | Schedule or handle expirations
        scheduler | core | Emit due ScheduledPost records
        core | feeds | Append post by account ID
        feeds | client | Read feed directly
      `),
    g('timed-notifications-tick-td', 'Append due posts to account feeds', 'td',
      'The source schedules each item at time-millis and processes expirations on ticks. Tests verify the inclusive due-time boundary with simulated time. They do not test restart or exactly-once behavior.',
      [...ref('timed-notifications', '10-37'), ...protocol('timed-notifications', '4-8')], `
        schedule | event | schedule-post!(id, time, post)
        record | etl | Append ScheduledPost to schedule depot
        queue | state | TopologyScheduler stores item at due time
        clock | event | Test advances simulated time or timer fires
        handle | etl | scheduler.handleExpirations
        due | decision | scheduled time ≤ current time?
        pending | end | Not due: leave pending
        account | etl | Extract item ID and post on scheduling task
        feed | state | Append post to $$feeds[id]
        read | external | feed(id) directly reads vector
      `, `
        schedule | record | Append
        record | queue | Call scheduler scheduleItem
        clock | handle | Timer or tick event
        handle | due | Check deadline
        due | pending | no
        due | account | yes
        account | feed | Update at account key; no new hash
        feed | read | Select directly
      `),
    g('timed-notifications-partition', 'Schedule ingress and feed partition ownership', 'partition',
      'The depot routes scheduled records by account ID, and expiration writes use the same key. The helper owns scheduler state and indexes it by deadline.',
      ref('timed-notifications', '10-37'), `
        input | depot | *scheduled-post-depot\nhash(:id)
        owner | route | Account ID
        item | state | ScheduledPost{id,time-millis,post}
        time | route | Scheduler deadline index
        scheduler | state | $$scheduled helper-owned data
        tick | depot | *tick
        feed | state | $$feeds[id] → post vector
        client | external | Direct account feed selection
      `, `
        input | owner | Route by ID
        owner | item | Record includes ID, time, and post
        item | time | Schedule at deadline
        time | scheduler | Index in helper
        tick | scheduler | Check for due items
        scheduler | feed | Emit due item to account key
        feed | client | Read directly
      `)
  ],
  'top-users-module': [
    overview('top-users-module', 'Purchase accumulation, global rank, and direct top-list reads',
      'A microbatch totals purchases by user, then updates a global top-500 list. Clients select that list directly; the source defines no query topology.',
      [...ref('top-users-module', '34-105'), ...protocol('top-users-module', '4-7')], `
        purchases | depot | *purchase-depot\nhash(:user-id)
        etl | etl | topusers (microbatch)
        totals | state | $$user-total-spend\nuser → cumulative cents
        top | state | $$top-spending-users\nglobal top 500 [user,total]
        client | external | top-users()\ndirect global PState read
      `, `
        purchases | etl | Consume microbatch
        etl | totals | Hash by user and sum purchases
        totals | etl | Emit updated totals
        etl | top | Repartition globally; rank 500 distinct IDs
        top | client | Select directly
      `),
    g('top-users-purchase-td', 'Update user totals and ranks', 'td',
      'The source adds each batch with +sum and emits the new total. The global top-monotonic aggregator uses user ID as identity and cumulative spend as sort value. The source does not specify tie order.',
      [...ref('top-users-module', '34-51,78-105'), ...protocol('top-users-module', '4-7')], `
        batch | event | Purchase(user-id,purchase-cents)
        group | etl | Hash by user ID in spend subbatch
        sum | state | +sum into $$user-total-spend
        updated | etl | Emit [user-id,new cumulative total]
        global | route | Repartition updated tuples globally
        rank | etl | +top-monotonic cap 500\nID = first, sort = last
        ranked | state | $$top-spending-users
        read | external | Select global list directly
      `, `
        batch | group | consume microbatch
        group | sum | Sum each user’s purchases
        sum | updated | Emit updated total
        updated | global | Route totals to global owner
        global | rank | Update each user’s rank by new total
        rank | ranked | Keep at most 500
        ranked | read | Read PState directly
      `),
    g('top-users-partition', 'Distributed totals converge on a single global top list', 'partition',
      'Purchase totals use user ID for local aggregation. Ranking state is global, so the source repartitions each updated total before ranking.',
      ref('top-users-module', '55-105'), `
        depot | depot | *purchase-depot\nhash(:user-id)
        users | route | hash(user-id)
        totals | state | $$user-total-spend[user-id]
        update | etl | Updated [user-id, total] tuple
        global | route | |global
        top | state | $$top-spending-users\nGlobal, maximum 500 users
        reader | external | Direct global PState selection
      `, `
        depot | users | Route purchases by user
        users | totals | Sum at user owner
        totals | update | Emit changed total
        update | global | Route to one partition
        global | top | Update distinct user ranks
        top | reader | Select directly
      `)
  ]
};
