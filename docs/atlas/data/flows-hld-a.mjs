import { graph as g } from '../graph.mjs';

const protocolEnds = { 'hld-url-shortener': 96, 'hld-rate-limiter': 115, 'hld-notification-system': 120, 'hld-web-crawler': 120, 'hld-search-autocomplete': 86, 'hld-file-sync': 59, 'hld-ticketing-system': 69, 'hld-payment-system': 66 };
const src = (slug, moduleLines, protocolLines = `1-${protocolEnds[slug]}`) => [
  { path: `challenges/${slug}/test-resources/${slug.replaceAll('-', '_')}/module.clj`, lines: moduleLines },
  { path: `challenges/${slug}/src/${slug.replaceAll('-', '_')}/protocol.clj`, lines: protocolLines },
  { path: `challenges/${slug}/README.md`, lines: '1-35' },
];

export default {
  'hld-url-shortener': [
    g('hld-url-shortener-lr', 'Alias events, status reads, and click counts', 'lr', 'One alias-hashed depot and one microbatch PState. Command acknowledgements indicate processing, not business success; create outcome is separately readable.', src('hld-url-shortener', '18-91,93-146'), `
      client | external | Protocol client
      depot | depot | *alias-events hash(alias)
      etl | etl | core subsource by record class
      links | state | $$links alias → link, outcomes, click-ids
      create | external | get-create-outcome: direct select
      resolve | external | resolve-alias: direct select + caller-time status
      count | external | get-click-count: direct select
    `, `
      client | depot | append create/delete/block/unblock/click
      depot | etl | consume microbatch
      etl | links | mutate conditional alias state
      links | create | outcome lookup
      links | resolve | link flags and caller time
      links | count | stored clicks, default zero
      create | client | outcome or nil
      resolve | client | derived status and target
      count | client | Long count
    `, ['The module contains no client-side clock source; expiration is derived from the caller-supplied now in resolve-status.']),
    g('hld-url-shortener-create-click', 'Create once and deduplicate click IDs', 'td', 'Two independent event branches make the hidden idempotency scopes explicit: create decisions by alias/request and click counts by alias/click-id.', src('hld-url-shortener', '35-73'), `
      event | event | CreateLink OR Click
      type | decision | Event class
      recorded | decision | Create outcome already stored?
      link | decision | Alias link already exists?
      created | etl | Store first link, defaults false flags and zero clicks
      rejected | end | Store alias-taken result
      outcome | state | outcomes[request-id]
      click | decision | Link click count exists?
      seen | decision | click-id already present?
      ids | state | click-ids set
      increment | state | clicks := prior + 1
      stop | end | Missing alias or duplicate click: no-op
    `, `
      event | type | dispatch
      type | recorded | CreateLink
      recorded | stop | yes: no-op
      recorded | link | no
      link | created | missing
      link | rejected | exists, never replaced
      created | outcome | persist :created
      rejected | outcome | persist :rejected
      type | click | Click
      click | stop | absent link
      click | seen | existing link
      seen | stop | duplicate click-id
      seen | ids | first observation
      ids | increment | then increase count
    `),
    g('hld-url-shortener-status', 'Lifecycle flags and precedence at resolution', 'td', 'Delete and block are one-way writes per operation in the implementation; unblock only clears blocked. The resolver checks deleted, blocked, then expiration.', src('hld-url-shortener', '44-65,93-104'), `
      event | event | DeleteLink / BlockLink / UnblockLink / resolve read
      exists | decision | Alias record exists?
      flags | state | deleted? or blocked? update
      now | event | Caller-provided now
      deleted | decision | deleted? true?
      blocked | decision | blocked? true?
      expired | decision | expires-at nonnil AND now >= expiry?
      status | end | deleted > blocked > expired > active
      missing | end | resolve returns missing
    `, `
      event | exists | mutation branch
      exists | flags | yes: set selected flag
      exists | missing | no: no-op
      now | deleted | resolve existing record
      deleted | status | yes
      deleted | blocked | no
      blocked | status | yes
      blocked | expired | no
      expired | status | yes
      expired | status | no: active
    `, ['The protocol labels delete-link irreversible; implementation stores a deleted flag with no undelete event. Expiry is read-time only and does not mutate the link.']),
    g('hld-url-shortener-partition', 'Alias ownership and nested records', 'partition', 'Every event and direct query is keyed by alias; request and click identifiers are nested under that owner.', src('hld-url-shortener', '15-33,112-137'), `
      key | route | hash(alias)
      root | state | $$links[alias]
      link | state | link → target-url, expires-at, deleted?, blocked?, clicks
      outcome | state | outcomes[request-id] → keyword
      clicks | state | click-ids subindexed set(String)
    `, `
      key | root | owner
      root | link | nested at alias
      root | outcome | nested at alias
      root | clicks | nested at alias
    `)
  ],

  'hld-rate-limiter': [
    g('hld-rate-limiter-lr', 'User event stream and fixed-work reads', 'lr', 'All limiter state and durable decisions are colocated by user-id. Bucket math is pure helper logic; successful checks replace limiter state atomically.', src('hld-rate-limiter', '20-168,170-204'), `
      client | external | RateLimiter client
      depot | depot | *user-events hash(user-id)
      etl | etl | core: config or check
      limiter | state | $$users[user].limiter
      decisions | state | $$users[user].decisions[request-id]
      decision | external | get-decision: direct select
      config | external | get-config: direct select
      status | external | get-status: direct select + pure bucket arithmetic
    `, `
      client | depot | append SetConfig or Check
      depot | etl | consume
      etl | limiter | config replacement or accepted debit
      etl | decisions | first check records result
      decisions | decision | point read
      limiter | config | point read
      limiter | status | pure availability at max(now, stored clock)
      decision | client | normalized decision map
      config | client | version and config
      status | client | user and endpoint capacities / available
    `, ['README explicitly excludes gateway fleets, local chunk borrowing, Redis and fail-open infrastructure. The diagram follows only the bounded per-user module implementation.']),
    g('hld-rate-limiter-check', 'Check: replay, policy branches, and atomic debit', 'td', 'First request-id wins. Unknown endpoints, oversized costs, and insufficient tokens never debit; shadow mode changes allowed, not would-allow.', src('hld-rate-limiter', '44-99,115-139'), `
      event | event | Check(user, request-id, endpoint, cost, now)
      seen | decision | Decision already recorded?
      limiter | state | Read inline config, clock and buckets
      config | decision | Limiter configured?
      endpoint | decision | Endpoint configured?
      capacity | decision | Cost exceeds either capacity?
      tokens | decision | Both available balances >= cost?
      recorded | state | Store no-config / unknown / capacity / insufficient decision
      debit | state | Store decision, advance clock, debit both buckets
      replay | end | Existing decision: no-op
    `, `
      event | seen | request lookup
      seen | replay | yes
      seen | limiter | no
      limiter | config | none
      config | recorded | no: :no-config; no limiter mutation
      config | endpoint | yes
      endpoint | recorded | absent: shadow allowed, would-allow false
      endpoint | capacity | present
      capacity | recorded | exceeds: shadow allowed; no debit
      capacity | tokens | within capacity
      tokens | debit | both pass
      tokens | recorded | either short: shadow allowed; no debit
    `),
    g('hld-rate-limiter-config', 'Versioned config reset and query arithmetic', 'td', 'Only a strictly newer configuration replaces config and resets buckets; stored clock is retained. Status reads are non-mutating and do not persist their effective tick.', src('hld-rate-limiter', '31-42,105-114,141-168'), `
      event | event | SetConfig(user, version, config) or get-status
      prior | state | Current limiter or nil
      newer | decision | No prior OR version > current?
      reset | etl | New full buckets; preserve clock or initialize zero
      ignore | end | Equal or older config: no-op
      status | decision | get-status: endpoint configured?
      avail | etl | tick=max(now, clock); cap(tokens + elapsed × refill)
      none | end | Missing limiter/endpoint returns nil
    `, `
      event | prior | config handler
      prior | newer | compare version
      newer | reset | yes
      newer | ignore | no
      event | status | query handler
      status | none | no limiter or endpoint
      status | avail | found: calculate, do not write
    `),
    g('hld-rate-limiter-partition', 'User key with inline buckets and decision submap', 'partition', 'User-id is the sole top-level ownership key; no endpoint is separately routed.', src('hld-rate-limiter', '101-130'), `
      user | route | hash(user-id)
      root | state | $$users[user]
      limit | state | limiter → version, config, clock
      ub | state | limiter.user-bucket → tokens, at
      eb | state | limiter.endpoint-buckets[endpoint] → tokens, at
      decisions | state | decisions[request-id] → result fields, subindexed
    `, `
      user | root | keypath owner
      root | limit | inline limiter
      limit | ub | inline
      limit | eb | endpoint keyed nested map
      root | decisions | user-scoped idempotency
    `)
  ],

  'hld-notification-system': [
    g('hld-notification-system-lr', 'User events, deterministic submission winners, delivery state', 'lr', 'One owner-hashed event depot feeds separate user, winner, and delivery phases. Submission IDs are globally keyed; winning submission then writes the owner recent index.', src('hld-notification-system', '15-95,148-229'), `
      client | external | Notification client
      depot | depot | *events hash(owner)
      profile | state | $$users[user].profile: devices, prefs, sequences
      winner | etl | group candidates by submission-id; lowest task/position rank wins
      submissions | state | $$submissions[submission-id]
      recent | state | $$users[user].recent[submit-seq]
      delivery | etl | ReportAttempt / RecordReceipt helper decisions
      dead | state | per-user dead-letters[sequence]
      reads | external | Direct selects: devices, preferences,\nsubmission, recent, dead letters
    `, `
      client | depot | append owner and event fields
      depot | profile | only RegisterDevice, SetPreference, Submit
      profile | winner | build candidate from profile snapshot
      winner | submissions | only when submission ID absent
      submissions | recent | winning user, routed by hash(user)
      depot | delivery | only reports / receipts
      submissions | delivery | apply helper; missing/invalid transition filters out
      delivery | submissions | update one delivery
      delivery | profile | invalid-token generation guarded invalidation
      delivery | dead | dead-letter effect appends sequence
      profile | reads | direct PState reads
      submissions | reads | direct submission lookup
      reads | client | bounded newest-first pages
    `),
    g('hld-notification-system-submit', 'Submission snapshot, suppression, and duplicate IDs', 'td', 'Device registration caps at eight distinct IDs; re-registration advances generation. For a duplicate submission ID, candidate ranking chooses one record and existing durable submissions are not overwritten.', src('hld-notification-system', '17-48,104-171'), `
      event | event | RegisterDevice / SetPreference / Submit
      profile | etl | Read current profile
      kind | decision | Event type
      cap | decision | New device and device count = 8?
      device | state | Replace token, increment generation, valid true
      pref | state | Persist category boolean
      enabled | decision | Category explicitly disabled?
      devices | decision | Any valid devices?
      record | etl | suppressed / no-devices / dispatched with captured tokens
      rank | etl | Candidate rank = task × 2^40 + task position
      exists | decision | Submission ID already stored?
      save | state | Persist winner only if absent; index by user sequence
      noop | end | Cap, nonwinner, or existing submission: no-op
    `, `
      event | profile | consume in source batch
      profile | kind | dispatch
      kind | cap | registration
      cap | noop | full and new ID
      cap | device | otherwise
      kind | pref | preference update
      kind | enabled | Submit: build from pre-update profile snapshot
      enabled | record | disabled
      enabled | devices | enabled/default true
      devices | record | none valid
      devices | record | valid devices captured pending
      record | rank | candidate
      rank | exists | deterministic single candidate
      exists | noop | prior durable record
      exists | save | absent
    `, ['README contract scopes duplicate submission first-wins outcomes to processing order. Reference resolves same-batch candidates by task/position rank and suppresses later writes when an ID already exists; it does not implement durable command outcome records.']),
    g('hld-notification-system-delivery', 'Attempt and receipt helper gates, retries, and effects', 'td', 'Stale, duplicate, out-of-order, and early reports are no-ops before expiry is checked. A receipt arriving before accepted is dropped, not deferred.', src('hld-notification-system', '50-88,173-209'), `
      event | event | ReportAttempt or RecordReceipt
      sub | decision | Submission exists and device delivery exists?
      pending | decision | Report delivery state pending?
      order | decision | attempt = attempts + 1 AND now >= next-attempt-at?
      expiry | decision | now >= expires-at?
      outcome | decision | accepted / invalid-token / permanent / transient
      terminal | state | accepted, invalid-token, failed, or expired
      retry | state | transient attempts 1 and 2: next at +10 then +20
      effect | decision | Report effect present?
      route | route | hash(submission user-id)
      kind | decision | Effect kind?
      generation | decision | Current device generation = captured generation?
      invalidate | state | Set current device valid? := false
      dead | state | Increment dl-seq; append dead-letter with reason and time
      done | end | Delivery saved; no further user-profile effect
      receipt | decision | Existing delivery state accepted/delivered/read?
      rank | etl | Keep max delivery/receipt order
      receiptWrite | state | Persist accepted/delivered/read state; no report effect
      stop | end | Invalid report or receipt: no-op
    `, `
      event | sub | read one submission
      sub | stop | submission or device missing
      sub | pending | exists: report
      sub | receipt | exists: receipt
      pending | order | yes
      pending | stop | no: terminal state
      order | stop | no: stale/duplicate/early; expiry not evaluated
      order | expiry | valid attempt
      expiry | terminal | expired, attempts unchanged
      expiry | outcome | not expired
      outcome | terminal | accepted / invalid token / permanent / third transient
      outcome | retry | transient attempt 1 or 2
      retry | done | pending delivery saved
      terminal | effect | after delivery write
      effect | done | none: accepted or expired
      effect | route | invalid-token or failed
      route | kind | user owner
      kind | generation | invalid-token
      kind | dead | permanent or retries exhausted
      generation | invalidate | equal
      generation | done | differs: newer token preserved
      invalidate | done | profile saved
      dead | done | logical record saved
      receipt | rank | eligible only accepted/delivered/read
      receipt | stop | pending or terminal
      rank | receiptWrite | max rank, never regress
    `),
    g('hld-notification-system-partition', 'Global submission ID and per-owner nested records', 'partition', 'Submission IDs are top-level global keys, not nested under the user owner. User profile and recent/dead-letter state are owner-keyed.', src('hld-notification-system', '88-114'), `
      owner | route | depot hash(owner)
      user | route | hash(user-id)
      profile | state | $$users[user].profile.devices[device] → token/generation/valid
      prefs | state | $$users[user].profile.prefs[category]
      seq | state | profile submit-seq and dl-seq
      recents | state | $$users[user].recent[submit-seq] → submission-id
      dead | state | $$users[user].dead-letters[dl-seq] → submission/device/reason/at
      sid | route | hash(submission-id)
      submission | state | $$submissions[sid] → captured delivery map
      task | route | Current physical task, no hash hop
      position | state | $$task-pos[task-id] → local candidate position
    `, `
      owner | user | event owner is user for user commands
      user | profile | nested profile
      profile | prefs | nested preference map
      profile | seq | nested counters
      user | recents | recent index
      user | dead | dead-letter history
      sid | submission | global submission owner
      task | position | per-task rank state
    `)
  ],

  'hld-web-crawler': [
    g('hld-web-crawler-lr', 'Canonical host events, durable frontier, caller queries', 'lr', 'The client canonicalizes and groups discoveries by lowercase host. Host-hashed sequential command processing updates one host record; no network fetcher exists in scope.', src('hld-web-crawler', '8-45,47-232'), `
      client | external | CrawlFrontier client
      canonical | etl | HTTPS parse; lowercase host; drop fragment
      depot | depot | *events hash(host)
      etl | etl | ordered per-host command loop
      state | state | $$hosts[host]: info, URL map, pending set, claims
      claim | external | get-claim: direct select
      url | external | get-url: canonicalize then direct select
      host | external | get-host: lowercase then direct select
      pending | external | list-pending: direct cursor range
    `, `
      client | canonical | discover! only; other methods lowercase host
      canonical | depot | valid unique URLs grouped by host
      client | depot | policy, claim, completion events
      depot | etl | preserve command order per host
      etl | state | dedup, policy, lease and claim mutation
      state | claim | point lookup
      state | url | host + canonical URL point lookup
      state | host | fixed info lookup
      state | pending | sorted set range bounded by limit
      claim | client | immutable outcome or nil
      url | client | canonical state or nil
      host | client | info defaults included
      pending | client | ascending page
    `),
    g('hld-web-crawler-discover', 'Canonicalization and exact URL deduplication', 'td', 'Invalid URLs are filtered at the client. Canonical duplicates in the same discovery call are removed before host grouping; stored URL existence gates enqueueing.', src('hld-web-crawler', '8-45,58-90,204-216'), `
      input | event | discover!(urls)
      valid | decision | Restricted HTTPS URL pattern and <=2048 chars?
      canonical | etl | Lowercase host, path default /, preserve query, drop fragment
      seen | decision | Canonical URL already stored for host?
      url | state | New URL status queued
      pending | state | Add URL to sorted pending set
      queued | state | Increase queued count once
      ignore | end | Invalid URL, repeated input, or previously seen URL
    `, `
      input | valid | each URL
      valid | ignore | no
      valid | canonical | yes
      canonical | seen | distinct within call and group by host
      seen | ignore | stored record exists
      seen | url | absent
      url | pending | persist queued state and membership
      pending | queued | increment info by one
    `),
    g('hld-web-crawler-claim', 'Claim loop: busy, stale lease, politeness, robots and grant', 'td', 'A repeated claim ID is immutable. Effective logical time is max(input, host clock). Blocked queue heads are retired until an allowed URL is found or queue empties.', src('hld-web-crawler', '91-164'), `
      event | event | Claim(host, claim-id, now)
      prior | decision | Claim ID already stored?
      tick | etl | T = max(now, host clock); advance clock
      lease | decision | Lease exists and T < expiry?
      stale | decision | Expired lease exists?
      requeue | state | Stale lease: URL queued, clear lease, queued +1
      delay | decision | T < last-claim-at + delay?
      head | etl | Lexicographically scan pending in chunks <=64
      policy | decision | Longest matching path prefix allows?
      blocked | state | Disallowed URL retired as blocked; queued -1
      grant | state | Allowed: increment fence, lease URL through T+30
      empty | end | Persist busy/not-ready/empty or grant result
      noop | end | Repeated claim ID
    `, `
      event | prior | lookup claim
      prior | noop | exists
      prior | tick | absent
      tick | lease | evaluate current lease
      lease | empty | live: :busy
      lease | stale | not live
      stale | requeue | expired lease present
      stale | delay | no lease: no requeue mutation
      requeue | delay | then politeness
      delay | empty | too early: :not-ready
      delay | head | ready
      head | policy | smallest queued URL
      policy | blocked | denied: retire and continue scan
      blocked | head | continue until allowed / none
      policy | grant | allowed
      grant | empty | record granted claim and lease
      head | empty | no candidates: :empty
    `),
    g('hld-web-crawler-complete', 'Completion fence and exact lease boundary', 'td', 'Completion changes nothing unless fence matches and effective time is strictly before expiry. Failed fetches are terminal failed states, not requeued.', src('hld-web-crawler', '165-184'), `
      event | event | Complete(host, fence, outcome, now)
      lease | decision | Host currently leased?
      fence | decision | Supplied fence matches current lease?
      time | decision | max(now, clock) < expires-at?
      done | state | :fetched → URL done, clear lease, advance clock
      failed | state | Other accepted outcome → URL failed, clear lease
      noop | end | Missing lease, stale fence, or time >= expiry
    `, `
      event | lease | read info
      lease | noop | absent
      lease | fence | present
      fence | noop | mismatch
      fence | time | match
      time | noop | boundary or expired; no clock mutation
      time | done | strictly live and fetched
      time | failed | strictly live and failed
    `, ['README and protocol specify outcomes :fetched or :failed and strict expiry. Reference helper checks fence plus strict time and treats any non-:fetched accepted outcome as failed; public input grammar is relied on for outcome validity.']),
    g('hld-web-crawler-partition', 'Host-local maps, ordered queue, and counters', 'partition', 'All mutable frontier structures are nested under lowercase host. The pending set is not a scan-derived queue count; info.queued is persisted.', src('hld-web-crawler', '47-57,86-90'), `
      host | route | hash(lowercase host)
      info | state | info: delay, rules, clock, fence, last-claim-at, queued, lease
      urls | state | urls[canonical URL] → status/fence/expiry
      pending | state | sorted subindexed set(canonical URL)
      claims | state | claims[claim-id] → immutable result
      hostkey | route | get-url derives host from canonical URL
    `, `
      host | info | top-level record
      host | urls | nested map
      host | pending | nested ordered set
      host | claims | nested outcomes
      hostkey | host | derive owner for URL read
    `)
  ],

  'hld-search-autocomplete': [
    g('hld-search-autocomplete-lr', 'Locale event acceptance, phrase ownership, query paths', 'lr', 'Locale owns publication/current generation. Phrase records and prefix indexes route by (locale, first two phrase characters); one-character prefix has a separate first-character route.', src('hld-search-autocomplete', '8-31,33-144,146-213'), `
      client | external | Autocomplete client
      depot | depot | *events hash(locale)
      owner | state | $$owner[locale] current accepted generation
      work | etl | publish/search/block/unblock decision
      route | route | hash(locale, first two phrase chars)
      phrases | state | $$phrases[locale][generation][phrase]
      sessions | state | $$sessions[locale][generation][phrase] set(session)
      prefixes | state | $$prefixes[locale][generation][prefix] ranked set
      blocked | state | $$blocked[locale] persistent set
      suggest | query | shard route, generation lookup, first k ranks
      phrase | query | phrase metadata, blocked membership
      generation | external | direct $$owner select
      metadata | state | $$generation[locale]\nreplicated on all tasks
    `, `
      client | depot | append event
      depot | owner | current locale generation gate
      owner | work | publish newer, search exact, policy event uses current
      work | metadata | accepted publish: all-task max generation update
      work | route | placement by phrase
      route | phrases | base and session count
      route | sessions | dedup set
      route | prefixes | maintain ranked entries by full prefixes and 1-char special route
      route | blocked | locale policy set
      client | suggest | query: hash(prefix placement)
      client | phrase | query: hash(phrase placement)
      prefixes | suggest | top k ranks
      metadata | suggest | current generation at query task
      metadata | phrase | current generation at query task
      phrases | phrase | phrase metadata
      blocked | phrase | block membership
      owner | generation | get-generation direct read
      suggest | client | decoded score-ranked phrases
      phrase | client | generation/base/sessions/score/blocked
    `),
    g('hld-search-autocomplete-publish', 'New-generation publication and stale event filtering', 'td', 'Snapshot acceptance is decided at the locale owner; phrase init runs downstream. Search writes are accepted only for an exact current-generation match.', src('hld-search-autocomplete', '33-77'), `
      event | event | PublishSnapshot OR RecordSearch
      current | state | Read $$owner[locale]
      publish | decision | generation strictly newer?
      owner | state | Advance accepted generation
      replicate | state | all tasks: $$generation[locale] := max\nwith accepted generation
      entries | etl | Explode snapshot phrase/base items
      phrase | state | Initialize base and sessions zero
      search | decision | Search generation equals current?
      record | state | Continue accepted phrase/session work
      stale | end | Older/equal publish or stale search: no-op
    `, `
      event | current | owner task
      current | publish | publish op
      publish | stale | no: filter
      publish | owner | yes
      owner | replicate | branch generation max
      owner | entries | explode accepted entries
      entries | phrase | initialize on phrase owner
      current | search | search op
      search | record | exact generation match
      search | stale | mismatch including missing locale
    `, ['The code resets only records explicitly written from new entries; it does not enumerate old-generation trend data. Reads use the current generation, leaving old generation structures retained.']),
    g('hld-search-autocomplete-count-policy', 'Accepted search: session dedup, blocked counts, and rank replacement', 'td', 'After the locale-generation gate, a duplicate session is filtered before writes. Blocked phrases still count sessions but have no visible rank. Rank updates branch locally for long prefixes and hash again for the one-character prefix.', src('hld-search-autocomplete', '89-168'), `
      event | event | Accepted RecordSearch from locale owner
      route | route | hash(placement(locale,phrase))
      record | state | Read current phrase record and seen session
      seen | decision | Session already counted?
      add | state | Add session once; increment sessions
      blocked | decision | Phrase in persistent blocked set?
      visible | decision | Previous base > 0 OR sessions > 0?
      old | etl | old-rank := previous score rank
      first | etl | old-rank := nil
      new | etl | new-rank := base + 10 × new sessions
      long | state | For prefixes length >=2: remove old rank if present; insert new rank
      short | route | hash(placement(locale,first character))
      ranks | state | One-character prefix: remove old if present; insert new
      noop | end | Duplicate: no writes
      hidden | end | Counts saved; no visible rank mutation
    `, `
      event | route | owner accepted current generation
      route | record | phrase owner reads
      record | seen | point membership
      seen | noop | duplicate
      seen | add | first session
      add | blocked | counts independent of visibility
      blocked | hidden | yes: old and new ranks nil
      blocked | visible | no
      visible | old | previous candidate
      visible | first | no previous candidate
      old | new | score increases by 10
      first | new | newly eligible
      new | long | local rank-change branch
      new | short | short-prefix branch
      short | ranks | explicit routing hop
    `),
    g('hld-search-autocomplete-policy', 'Block/unblock: persist policy even without a current candidate', 'td', 'Policy commands take the locale owner generation, then route by phrase placement. Repeated membership changes are filtered. Only a current candidate produces a rank to add or remove.', src('hld-search-autocomplete', '86-98,124-168'), `
      event | event | Block or Unblock from *events
      route | route | Read owner generation; hash phrase placement
      prior | state | Read block membership and current phrase base/sessions
      type | decision | Operation?
      block | decision | Currently blocked?
      unblock | decision | Currently blocked?
      add | state | Add phrase to blocked set
      remove | state | Remove phrase from blocked set
      candidate | decision | Generation exists AND base>0 or sessions>0?
      rank | etl | Block: old-rank only; unblock: new-rank only
      long | state | Apply rank remove/add to prefixes length >=2 on phrase task
      short | route | hash(placement(locale,first character))
      write | state | Apply rank remove/add to one-character prefix
      noop | end | Repeated policy event: no-op
      saved | end | Policy saved; no candidate rank to mutate
    `, `
      event | route | locale owner acceptance path
      route | prior | phrase owner
      prior | type | dispatch policy event
      type | block | Block
      type | unblock | Unblock
      block | noop | yes
      block | add | no
      unblock | remove | yes
      unblock | noop | no
      add | candidate | derive candidate from existing record
      remove | candidate | derive candidate from existing record
      candidate | saved | no
      candidate | rank | yes
      rank | long | local long-prefix branch
      rank | short | one-character branch
      short | write | short-prefix owner
    `),
    g('hld-search-autocomplete-partition', 'Two-character placement and special short-prefix routing', 'partition', 'State is keyed by locale and generation, but phrase/index ownership is determined by the first two phrase characters. A one-character prefix query uses its own placement.', src('hld-search-autocomplete', '8-31,35-45,146-178'), `
      locale | route | depot hash(locale)
      owner | state | $$owner[locale]: accepted generation
      pair | route | placement(locale, first two chars of string)
      all | route | all tasks on accepted publication
      generation | state | $$generation[locale]: max generation\nreplicated metadata, not global singleton
      phrases | state | locale → generation → phrase → base/sessions
      sessions | state | locale → generation → phrase → subindexed session set
      prefixes | state | locale → generation → prefix → subindexed rank set
      blocked | state | locale → subindexed set(phrase), persistent generations
      short | route | one-character prefix placement(locale, prefix)
      ranks | state | rank key score-desc then phrase-asc encoding
    `, `
      locale | owner | acceptance authority
      locale | pair | accepted work: hash placement
      owner | all | accepted generation broadcast
      all | generation | read metadata on every task
      pair | phrases | phrase records at this shard
      pair | sessions | same phrase shard
      pair | prefixes | prefixes of length at least two
      pair | blocked | only phrases assigned to this shard
      pair | short | rank-change branch: hash short placement
      short | ranks | special one-character prefix owner
    `, ['Protocol contract says blocklist is locale-scoped. The implementation routes blocked phrase membership through phrase placement and queries at the phrase/prefix placement; inspect as implemented rather than infer one global physical owner.'])
  ],

  'hld-file-sync': [
    g('hld-file-sync-lr', 'Namespace command path, journal, and direct reads', 'lr', 'Commands are position-stamped on depot ownership, grouped and ordered by namespace, then processed sequentially. Large histories stay in nested subindexed maps.', src('hld-file-sync', '1-11,177-321,323-466'), `
      client | external | FileSync client
      depot | depot | *commands hash(ns-id)
      pre | etl | Stamp per-namespace ingress position
      order | etl | Group and sort by position; sequential loop
      state | state | $$namespaces[ns]: blocks, files, journal, requests
      head | query | file-head query: head + current version record
      reads | external | Direct outcome/block/version\nand ranged journal selects
    `, `
      client | depot | append validated command
      depot | pre | consume append order
      pre | order | namespace aggregation then sort
      order | state | replay/business decision and durable updates
      state | head | query hash(namespace)
      state | reads | point reads or bounded journal range
      head | client | current head record
      reads | client | outcomes, block size, version, change page
    `, ['The README explicitly bounds file sync to metadata; this diagram does not imply block-byte storage, transport, or garbage collection.']),
    g('hld-file-sync-idempotency', 'Original request, replay, and conflict counter', 'td', 'Structural validation occurs synchronously in the client. The ETL first stores the first business outcome, exact payload replay is a no-op, and any unequal payload increments only conflicting-attempts.', src('hld-file-sync', '40-104,282-319,379-454'), `
      call | event | register-blocks! or commit-file!
      validate | decision | Client structural checks pass?
      depot | depot | Append command after validation
      stop | end | Invalid structural input throws; no command reaches depot
      prior | decision | Request ID exists in namespace?
      payload | decision | Stored payload equals command?
      original | etl | Evaluate command and persist request outcome
      replay | end | Equal payload: no-op, preserve outcome
      conflict | state | Unequal payload: bump conflict counter only
      done | end | Original outcome or conflict count persisted
    `, `
      call | validate | synchronous client method
      validate | depot | valid
      validate | stop | invalid throws; no append
      depot | prior | consume ordered command
      prior | original | absent
      prior | payload | present
      payload | replay | equal
      payload | conflict | differs
      original | done | save initial outcome
      conflict | done | preserve payload/outcome except counter
    `),
    g('hld-file-sync-commit', 'Block registration, prechecks, and stale-parent copy', 'td', 'After the common request gate, registration is atomic on size mismatch. Commit precheck order precedes block lookup. A stale parent below head creates a separate generated file; equal parent advances the original.', src('hld-file-sync', '105-190,302-374'), `
      event | event | RegisterBlocks OR CommitFile
      type | decision | Command type?
      known | etl | Point-read distinct block hashes
      mismatch | decision | Known hash has different size?
      add | state | Register each new hash once
      stop | end | Size mismatch: register nothing
      head | state | Read current target file head
      check | decision | file-exists, no-such-file, unknown-parent in order
      reject | end | Persist first failed precheck reason
      sizes | etl | Point-read distinct commit block hashes
      missing | decision | Any block absent?
      need | end | Reject with distinct missing hashes, first occurrence order
      stale | decision | parent-version < head?
      copy | etl | target ~request-id; truncate path; conflict-of target file
      advance | etl | target same file; version head + 1
      writes | state | head/version, journal sequence, durable outcome
    `, `
      event | type | new request on namespace task
      type | known | RegisterBlocks
      known | mismatch | registration check
      mismatch | stop | yes
      mismatch | add | no; write distinct new hashes
      type | head | CommitFile
      head | check | existence/parent precheck
      check | reject | rejection
      check | sizes | passed
      sizes | missing | derive missing-hash list
      missing | need | nonempty
      missing | stale | empty
      stale | copy | older parent
      stale | advance | nil or equals current head
      copy | writes | generated ID version 1; original head unchanged
      advance | writes | existing target head increments or new version 1
    `),
    g('hld-file-sync-partition', 'Namespace root with file histories and cursor journal', 'partition', 'Every key path is namespace-local. File versions and journal are distinct durable indexes; blocklist repetition counts size repeatedly.', src('hld-file-sync', '245-284,323-391'), `
      ns | route | hash(ns-id)
      root | state | $$namespaces[ns]
      blocks | state | blocks[hash] → size, subindexed
      files | state | files[file-id] → head, conflict-of, versions[version]
      versions | state | versions subindexed; path, ordered blocklist, size, request, seq, provenance
      journal | state | journal[seq] → file/version/path/size/request/provenance, subindexed
      requests | state | requests[request-id] → payload/outcome/conflicts, subindexed
      query | route | hash(ns-id) for file-head topology
    `, `
      ns | root | top-level key
      root | blocks | namespace-local block index
      root | files | namespace-local files
      files | versions | per-file history
      root | journal | sequence keyed range index
      root | requests | request idempotency namespace
      query | root | file-head query reads head + version
    `)
  ],

  'hld-ticketing-system': [
    g('hld-ticketing-system-lr', 'Event commands and query interfaces', 'lr', 'One event-hashed depot and event-keyed PState. Commands are sequenced per event before decision helpers; query topologies route by event.', src('hld-ticketing-system', '1-50,138-342'), `
      client | external | Ticketing client
      depot | depot | *commands hash(event-id)
      order | etl | Per-event ingress sequence and sorted command loop
      decide | etl | Pure decide(cmd, clock, selected seats, hold)
      events | state | $$events[event]: seats, holds, requests, compensation history
      seats | query | seats(event, ids)
      hold | query | hold(event, hold-id)
      direct | external | Direct outcome, clock,\nranged compensations
    `, `
      client | depot | append command with ack
      depot | order | consume and group event commands
      order | decide | point selects required by command type
      decide | events | clock/hold/seat and compensation writes
      events | seats | event hash + bounded seat submap
      events | hold | event hash + hold lookup
      events | direct | direct PState selects
      seats | client | state derived at current clock
      hold | client | hold view
      direct | client | outcome, clock, compensation page
    `),
    g('hld-ticketing-system-hold', 'Hold validation order and atomic availability', 'td', 'Seat IDs must be structurally distinct before append. Business checks order unknown event, unknown seat, deadline boundary, then unavailable; all seats write only on full success.', src('hld-ticketing-system', '48-136,186-273'), `
      event | event | HoldSeats(event, user, seats, deadline)
      clock | decision | Event exists?
      seats | decision | Every requested seat exists?
      deadline | decision | deadline <= event clock?
      available | decision | Any confirmed or unexpired held seat?
      reject | end | Rejection stores outcome; no seat/hold writes
      hold | state | holds[request-id] status held, deadline, owner
      writes | state | Mark all requested seats held
      done | end | Accepted hold ID is request-id
    `, `
      event | clock | required event read
      clock | reject | absent event
      clock | seats | exists
      seats | reject | one missing
      seats | deadline | all found
      deadline | reject | deadline <= clock
      deadline | available | deadline > clock
      available | reject | confirmed or clock < existing deadline
      available | hold | all available including expired/released
      hold | writes | persist hold and all seat writes
      writes | done | persist accepted outcome
    `),
    g('hld-ticketing-system-confirm-compensation', 'Confirm/release transitions and logical compensation', 'td', 'Rejected confirmations append compensation only for expired/released holds or a confirmed hold with a different payment ref. The record is local logical bookkeeping, not a payment-system call.', src('hld-ticketing-system', '87-135,274-322'), `
      event | event | ConfirmHold or ReleaseHold
      hold | decision | Hold exists?
      owner | decision | Caller owns hold?
      terminal | decision | Confirmed or released already?
      expired | decision | clock >= hold deadline?
      compensate | decision | Confirm and reason expired/released OR confirmed with different payment?
      seq | state | Append per-event compensation sequence
      reject | end | Store rejection; seats remain unchanged
      transition | state | Confirm: seats confirmed; release: seats cleared
      noop | end | No compensation for other rejection / same payment confirmation
    `, `
      event | hold | current event + hold
      hold | reject | absent
      hold | owner | exists
      owner | reject | mismatch
      owner | terminal | matching owner
      terminal | reject | terminal hold
      terminal | expired | otherwise
      expired | reject | expired
      expired | transition | still live
      reject | compensate | only specified confirmation reasons
      compensate | seq | yes: append logical record
      compensate | noop | no: persist rejection without compensation
      transition | noop | accepted mutation path
    `, ['README contract has no external payment/refund effect: compensation records are logical, request-scoped, and not deduplicated across request IDs sharing a payment reference.']),
    g('hld-ticketing-system-request-events', 'Request replay/conflict and event, seat, clock commands', 'td', 'The common request gate precedes domain checks. Event creation initializes clock and compensation sequence; add-seats rejects atomically on any known seat; equal clock advance succeeds without changing time.', src('hld-ticketing-system', '48-85,138-273'), `
      event | event | CreateEvent / AddSeats / AdvanceClock
      prior | decision | Request ID already present?
      equal | decision | Exact original command payload?
      replay | end | Same payload: preserve original outcome and state
      conflict | state | Different payload: increment conflicts only
      dispatch | decision | Unseen request: command type?
      exists | decision | CreateEvent: event already has clock?
      create | state | Initialize clock 0, comp sequence 0
      seats | decision | AddSeats: event exists and any requested seat known?
      add | state | All seats absent: write all available seats
      time | decision | AdvanceClock: event exists and now < clock?
      advance | state | Set non-regressing clock to now
      reject | end | Business rejection: no seat/clock mutation
      save | end | Store original outcome and payload
    `, `
      event | prior | common request lookup
      prior | dispatch | no stored request
      prior | equal | stored request
      equal | replay | equal
      equal | conflict | not equal
      dispatch | exists | CreateEvent
      exists | reject | already exists
      exists | create | absent
      create | save | accepted
      dispatch | seats | AddSeats
      seats | reject | missing event or any duplicate-existing seat
      seats | add | event exists and every seat new
      add | save | accepted
      dispatch | time | AdvanceClock
      time | reject | missing event or regression
      time | advance | now equal or later
      advance | save | accepted
      reject | save | rejected outcome
    `),
    g('hld-ticketing-system-partition', 'Event root, nested entity keys, and query routes', 'partition', 'Event is the exact ownership key. Seat/hold/request/compensation identifiers are nested maps; logical seat availability is computed from the event clock.', src('hld-ticketing-system', '138-184,274-342'), `
      event | route | hash(event-id)
      root | state | $$events[event]
      clock | state | clock, ingress-seq, next-comp-seq
      seats | state | seats[seat-id] → hold-id/user/deadline/confirmed
      holds | state | holds[hold-id] → owner/seat-ids/deadline/status/payment
      requests | state | requests[request-id] → payload/outcome/conflicts
      comp | state | compensations[seq] → request, hold, user, payment, reason
      queries | route | query topology hash(event-id), direct selects same event key
    `, `
      event | root | sole top-level partition
      root | clock | inline fields
      root | seats | subindexed seat map
      root | holds | subindexed hold map
      root | requests | subindexed idempotency map
      root | comp | subindexed sequence map
      queries | root | source-aligned reads
    `)
  ],

  'hld-payment-system': [
    g('hld-payment-system-lr', 'Tenant command ledger and point/range read surface', 'lr', 'Tenant-hashed commands are grouped and ordered per tenant. A bounded pure helper evaluates using point-read records; accepted monetary commands update accounts, charge metadata, sequence, journal and outcome.', src('hld-payment-system', '1-48,50-245'), `
      client | external | PaymentSystem client
      depot | depot | *commands hash(tenant-id)
      order | etl | ingress positions; group and sort tenant commands
      reads | etl | point-read currency, sequence, charge, two accounts
      helper | etl | apply-original branches by command
      tenants | state | $$tenants[tenant]: currency, accounts, charges, journal, requests
      direct | external | Direct outcome, tenant, account/balance, charge
      journal | external | Direct sorted seq range after cursor
    `, `
      client | depot | structural validation then append
      depot | order | process tenant order
      order | reads | idempotency gate then necessary point reads
      reads | helper | helper decides accepted/rejected
      helper | tenants | persist outcome and applicable writes
      tenants | direct | direct point selections
      tenants | journal | subindexed sequence range
      direct | client | individual result
      journal | client | ordered bounded page
    `),
    g('hld-payment-system-command', 'Replay gate and account creation/funding/charge branches', 'td', 'Structural validation is client-side and precedes request lookup. A replay/conflict bypasses current ledger checks. Only accepted fund/charge emits journal and sequence writes.', src('hld-payment-system', '18-91,94-177,188-216'), `
      call | event | CreateTenant / CreateAccount / Fund / Charge
      valid | decision | Structural types, bounds, no client clearing target?
      depot | depot | Append valid command
      request | decision | Tenant request-id already stored?
      equal | decision | Same full command payload?
      replay | end | Equal: no effect
      conflict | state | Different: increment original outcome conflicts only
      type | decision | New command type?
      createTenant | decision | CreateTenant: tenant absent?
      tenant | decision | Tenant exists?
      operation | decision | Account / funding / charge?
      newAccount | decision | CreateAccount: account absent?
      funded | decision | Fund: target account exists?
      accounts | decision | Charge: both accounts exist\nand customer/merchant kinds match?
      funds | decision | Customer balance >= amount?
      accept | etl | Create tenant/clearing, account, or balanced posting pair
      reject | etl | Durable business rejection; no ledger/journal mutation
      invalid | end | Structural error thrown before append
      save | state | Store outcome; monetary accept increments seq and journal
    `, `
      call | valid | validate before request key
      valid | depot | yes
      valid | invalid | malformed
      depot | request | idempotency read
      request | type | absent
      request | equal | present
      equal | replay | identical
      equal | conflict | unequal
      type | createTenant | CreateTenant
      createTenant | accept | absent: initialize currency and clearing
      createTenant | reject | already exists
      type | tenant | CreateAccount / Fund / Charge
      tenant | reject | missing tenant
      tenant | operation | exists
      operation | newAccount | CreateAccount
      newAccount | accept | absent: zero-balance account
      newAccount | reject | already exists
      operation | funded | Fund
      funded | accept | exists: target credit + clearing debit
      funded | reject | missing
      operation | accounts | Charge
      accounts | reject | absent or wrong kind
      accounts | funds | correct accounts
      funds | reject | insufficient customer balance
      funds | accept | enough
      accept | save | update balances, record charge if needed, append journal
      reject | save | persist rejected outcome only
    `, ['README’s closed-ledger model is maintained by explicit clearing account updates on funding; there is no external payment processor in scope.']),
    g('hld-payment-system-refund', 'Refund validates accepted charge, remaining total, and merchant balance', 'td', 'Refund accounts are taken from the stored accepted charge. Refund seq is new; charge seq remains unchanged while refunded-total is updated.', src('hld-payment-system', '26-91,94-177'), `
      event | event | Refund(request-id, tenant, charge-id, amount)
      tenant | decision | Tenant exists?
      charge | decision | Accepted charge exists?
      total | decision | Refunded total + amount exceeds charge amount?
      balance | decision | Merchant balance below refund amount?
      reject | end | First failed business check; durable outcome, no journal
      accept | etl | Merchant debit then customer credit
      update | state | Charge refunded-total; keep original charge seq
      journal | state | New seq and refund posting row
      done | end | Outcome refund-id=request-id, own seq
    `, `
      event | tenant | refund branch
      tenant | reject | absent
      tenant | charge | exists
      charge | reject | no accepted charge
      charge | total | exists
      total | reject | exceeds
      total | balance | within cap
      balance | reject | merchant short
      balance | accept | sufficient
      accept | update | update balances and refunded total
      update | journal | append independent refund row
      journal | done | persist refund outcome
    `),
    g('hld-payment-system-partition', 'Tenant root: indexed account, charge, request and journal keys', 'partition', 'All business records live in one tenant-keyed state root; nested maps are subindexed. The sequence index supports bounded cursor reads without scanning unrelated history.', src('hld-payment-system', '93-108,188-245'), `
      tenant | route | hash(tenant-id)
      root | state | $$tenants[tenant]
      accounts | state | accounts[account-id] → kind, balance; subindexed
      charges | state | charges[charge-id] → parties, amount, refunded total, original seq
      requests | state | requests[request-id] → full payload, outcome; subindexed
      journal | state | journal[seq] → request, type, charge-id, ordered postings; subindexed
      cursor | external | sorted-map range from after-seq + 1, max limit
    `, `
      tenant | root | sole top-level owner
      root | accounts | account key
      root | charges | charge key
      root | requests | tenant-scoped idempotency key
      root | journal | sequence key
      journal | cursor | range select
    `, ['README forbids unbounded per-tenant collections as one read; implementation uses point paths and a subindexed journal range.'])
  ]
};
