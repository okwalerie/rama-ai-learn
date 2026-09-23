import { graph as g } from '../graph.mjs';

const src = (slug, ns, lines) => [{ path: `challenges/${slug}/test-resources/${ns}/module.clj`, lines }];
const protocol = (slug, ns, lines) => [{ path: `challenges/${slug}/src/${ns}/protocol.clj`, lines }];

export default {
  'hld-stock-exchange': [
    g('hld-stock-exchange-core-lr', 'Symbol commands, book state, and direct reads', 'lr',
      'Commands are microbatched by symbol, ordered within a symbol, and mutate one durable per-symbol PState. All exposed reads are direct foreign selects; there is no query topology.',
      [...src('hld-stock-exchange', 'hld_stock_exchange', '76-112,213-255'), ...protocol('hld-stock-exchange', 'hld_stock_exchange', '21-47')], `
      client | external | Exchange protocol client
      commands | depot | *commands\nhash(symbol)
      core | etl | microbatch core\nposition + symbol-local ordering
      replay | decision | Existing request ID?\npayload equal or conflicting
      match | etl | Submit matches opposing price levels\nthen FIFO queue heads
      cancel | etl | Cancel: existence, owner, open-state checks
      symbols | state | $$symbols keyed by symbol\nseqs, orders, levels, queues, trades, requests
      outcome | external | get-outcome\ndirect select symbol/requests/id
      order | external | get-order\ndirect select symbol/orders/id
      depth | external | get-depth\nordered range of price levels
      tape | external | get-trades\nseq range capped by limit
      returned | external | Return order / depth / trade / outcome
    `, `
      client | commands | append command
      commands | core | consume; group and sort symbol ingress positions
      core | replay | look up symbol request-id
      replay | match | unseen submit payload
      replay | cancel | unseen cancel payload
      cancel | symbols | accepted removal or rejected outcome
      match | symbols | local writes to order book and trade tape
      replay | symbols | conflicting ID increments conflicting-attempts only
      symbols | outcome | direct PState selection
      symbols | order | direct PState selection
      symbols | depth | bounded sorted-map range
      symbols | tape | sorted-map-range-from with max-amt
      outcome | returned | return
      order | returned | return
      depth | returned | return
      tape | returned | return
    `, ['Despite the method name get-trades being query-like, it performs a bounded direct foreign PState range select, not a Rama query topology.']) ,
    g('hld-stock-exchange-match-td', 'Submit: maker-price FIFO, partial fill, and GTC versus IOC', 'td',
      'Each accepted submit gets a contiguous order sequence. Repeatedly inspect only the best opposing price and its first sequence; no crossing order ends matching. Maker partial fills retain the queue head position.',
      [...src('hld-stock-exchange', 'hld_stock_exchange', '25-63,101-181'), ...protocol('hld-stock-exchange', 'hld_stock_exchange', '21-28')], `
      event | event | submit-limit-order!(symbol, request-id,\naccount, side, price, qty, tif)
      replay | decision | Request already processed?
      dup | end | Identical payload: no rematch, original outcome unchanged
      conflict | end | Different payload: increment conflict count only
      init | etl | Unseen submit; allocate next order seq
      best | state | Read first opposing price level\nand its FIFO queue head
      cross | decision | Remaining positive AND\nbest opposing price crosses limit?
      maker | state | Read resting order; amount=min(left,remaining)
      trade | state | Append next trade seq\nat resting maker price
      partial | decision | Maker fully consumed?
      dequeue | state | Remove maker queue head
      retain | state | Partial fill: maker remains at head\nwith original seq
      continue | etl | Update maker level qty/count; reduce incoming remainder
      rest | decision | Remainder and time-in-force?
      own | state | Add incoming remainder at limit\nbehind existing same-price orders
      ioc | etl | IOC remainder becomes cancelled quantity
      finish | state | Store accepted order, counters, seqs, outcome
      done | end | Durable outcome after processing
    `, `
      event | replay | request lookup before matching
      replay | dup | same payload
      replay | conflict | unequal payload
      replay | init | unseen request
      init | best | begin match loop
      best | cross | best-level and queue head lookup
      cross | maker | yes
      cross | rest | no
      maker | trade | execute min quantity at maker price
      trade | partial | update maker filled and trade seq
      partial | dequeue | fully consumed
      partial | retain | not fully consumed
      dequeue | continue | remove head, update level
      retain | continue | preserve FIFO priority, update level qty
      continue | best | continue while crossing
      rest | own | GTC remainder
      rest | ioc | IOC positive remainder
      rest | finish | zero remainder: fully filled
      own | finish | store open order
      ioc | finish | store cancelled remainder
      finish | done | commit outcome and next sequences
    `),
    g('hld-stock-exchange-cancel-td', 'Cancel: idempotency precedes ordered business rejection', 'td',
      'Structural checks occur synchronously in the client before append. In the topology replay/conflict handling precedes cancellation existence, ownership, and open-state checks.',
      [...src('hld-stock-exchange', 'hld_stock_exchange', '36-40,119-124,182-210,231-246'), ...protocol('hld-stock-exchange', 'hld_stock_exchange', '29-35')], `
      call | event | cancel-order!(request-id, symbol, order-id, account)
      validate | etl | Client structural ID validation\nbefore append
      prior | decision | Symbol request ID exists?
      replay | end | Same payload: no effect
      conflict | state | Different payload: increment attempts only
      exists | decision | Accepted order exists?
      owner | decision | Requesting account owns order?
      open | decision | Remaining quantity positive?
      missing | end | Reject no-such-order
      notOwner | end | Reject not-owner
      closed | end | Reject order-not-open
      remove | state | Remove sequence from side/price queue
      level | decision | This is last level quantity?
      drop | state | Remove empty level and queue
      reduce | state | Subtract remaining qty; decrement order count
      cancelled | state | Order remaining=0; cancelled qty += previous remaining
      outcome | state | Persist accepted or rejected outcome\nwith original payload
    `, `
      call | validate | sync validation
      validate | prior | valid command append
      prior | replay | same request and payload
      prior | conflict | same request, different payload
      prior | exists | no request yet
      exists | missing | no order
      exists | owner | order found
      owner | notOwner | account differs
      owner | open | account matches
      open | closed | remaining is zero
      open | remove | remaining is positive
      remove | level | remove queue entry; inspect aggregate
      level | drop | all level quantity removed
      level | reduce | other orders remain at level
      drop | cancelled | update order record
      reduce | cancelled | update order record
      missing | outcome | store rejection
      notOwner | outcome | store rejection
      closed | outcome | store rejection
      cancelled | outcome | store accepted outcome
    `),
    g('hld-stock-exchange-partition', 'Partition close-up: symbol owns nested book, outcomes, and tape', 'partition',
      'The exchange PState top-level key and command depot partition key are both symbol. Price maps and FIFO maps are nested within that symbol; no client-local business cache is used.',
      src('hld-stock-exchange', 'hld_stock_exchange', '76-100,218-220'), `
      sym | route | hash(symbol)
      book | state | $$symbols[symbol]\ningress-seq, next-order-seq, next-trade-seq
      order | state | orders[order-id] → account, side, price, qty, tif, seq, fills, remainder, state
      bid | state | bid-levels[reversed-price] → qty, order-count\nsubindexed
      ask | state | ask-levels[price] → qty, order-count\nsubindexed
      bq | state | bid-queue[reversed-price][order-seq] → order-id\nsubindexed at both levels
      aq | state | ask-queue[price][order-seq] → order-id\nsubindexed at both levels
      tape | state | trades[trade-seq] → immutable maker/taker record\nsubindexed
      requests | state | requests[request-id] → payload, outcome, conflict count\nsubindexed
      query | external | Client direct selections by symbol, then nested key/range
    `, `
      sym | book | owns all symbol state
      book | order | lookup / write by order ID
      book | bid | aggregate bid depth by reverse price key
      book | ask | aggregate ask depth by price key
      book | bq | FIFO buy sequence keys per level
      book | aq | FIFO sell sequence keys per level
      book | tape | monotonically increasing trade sequence
      book | requests | symbol-scoped cross-command idempotency
      order | query | get-order
      bid | query | get-depth buy
      ask | query | get-depth sell
      tape | query | after-seq and limit
      requests | query | get-outcome
    `)
  ],
  'hld-hotel-reservation': [
    g('hld-hotel-reservation-core-lr', 'Property command journal, nested inventory, and read interfaces', 'lr',
      'Commands hash by property into one microbatch topology and one property-keyed PState. Availability is a query topology; night, booking, event-page, and outcome reads use direct PState selections.',
      [...src('hld-hotel-reservation', 'hld_hotel_reservation', '59-100,250-321'), ...protocol('hld-hotel-reservation', 'hld_hotel_reservation', '24-70')], `
      client | external | Reservation client
      commands | depot | *commands\nhash(property-id)
      core | etl | core microbatch\nper-property ingress ordering
      guard | decision | request-id original exists?
      apply | etl | create/configure/reserve/cancel branch
      properties | state | $$properties[property]\nroom types, nights, bookings, events, requests
      availability | query | availability topology\nrange [checkin,checkout)
      direct | external | get-night / booking / outcome / events\nforeign PState selects
      returned | external | Return snapshots / bounded page
    `, `
      client | commands | append command
      commands | core | consume and sequence same-property commands
      core | guard | replay or conflict test
      guard | apply | unseen command
      apply | properties | local inventory and journal writes
      properties | availability | query routes hash(property)
      properties | direct | direct property key selection/range
      availability | returned | night vector or nil
      direct | returned | scalar or bounded events
    `, ['The topology serializes commands per property in its microbatch by recorded ingress positions; no booking/payment service or external reservation system appears in this bounded module.']),
    g('hld-hotel-reserve-td', 'Reserve: validate all nights before any inventory decrement', 'td',
      'The stay is half-open and bounded to 30 nights. Branch order preserves property/type rejection before missing nights, and missing nights before capacity shortfalls; failure changes no inventory.',
      [...src('hld-hotel-reservation', 'hld_hotel_reservation', '21-40,166-208'), ...protocol('hld-hotel-reservation', 'hld_hotel_reservation', '37-45')], `
      call | event | reserve!(request-id becomes booking-id)
      replay | decision | Request record exists?
      replayed | end | Same payload: frozen outcome; no second reservation
      conflict | state | Different payload: attempts++ only
      prop | decision | Property exists?
      room | decision | Room type exists?
      scan | etl | Select nights in [checkin,checkout)\ncompute missing list
      missing | decision | Any night absent?
      capacity | etl | Only when nights configured\ncompute all insufficient nights
      short | decision | Any available < quantity?
      reject | state | Record rejection and offending nights\nno inventory mutation
      total | etl | Sum rate(night) × quantity
      decrement | state | Every stay night available -= quantity
      seq | state | Increment property event seq
      booking | state | Store confirmed booking\nlocked total, original seq
      event | state | Append reserved journal event
      outcome | state | Record outcome and request payload
    `, `
      call | replay | topology apply
      replay | replayed | known request same payload
      replay | conflict | known request different payload
      replay | prop | unseen request
      prop | reject | missing property
      prop | room | exists
      room | reject | missing room type
      room | scan | exists
      scan | missing | configured nights subset
      missing | reject | any absent, record ascending nights
      missing | capacity | none absent
      capacity | short | test computed shortfall list
      short | total | none insufficient
      short | reject | any insufficient: ascending nights
      total | decrement | compute locked total first
      decrement | seq | update all nights only after preflight
      seq | booking | event sequence allocated
      booking | event | store original reserved seq
      event | outcome | durable journal record
      reject | outcome | persist rejected outcome
    `),
    g('hld-hotel-cancel-td', 'Cancel: ordered authorization checks then restore exact stay', 'td',
      'Cancellation uses the stored booking snapshot, does not recalculate its total or replace the booking sequence, and appends a distinct cancellation event sequence.',
      [...src('hld-hotel-reservation', 'hld_hotel_reservation', '210-249'), ...protocol('hld-hotel-reservation', 'hld_hotel_reservation', '46-51')], `
      call | event | cancel-booking!(request-id, property, booking, guest)
      replay | decision | Request ID already recorded?
      noop | end | Same payload replay: no second restoration
      conflict | state | Different payload: conflicting-attempts++
      prop | decision | Property exists?
      booking | decision | Accepted booking exists?
      guest | decision | Guest matches booking?
      state | decision | Already cancelled?
      reject | state | Persist first failed reason in order
      nights | etl | Load booking room-type, dates, quantity
      restore | state | Add quantity to each original night
      mark | state | Booking state := cancelled\nkeep original reservation seq
      seq | state | Allocate next property journal seq
      event | state | Append cancelled event; request-id is cancel command
      outcome | state | Store outcome; accepted includes cancellation seq
    `, `
      call | replay | process
      replay | noop | same payload
      replay | conflict | different payload
      replay | prop | new request
      prop | reject | missing property
      prop | booking | exists
      booking | reject | unknown booking
      booking | guest | known booking
      guest | reject | wrong guest
      guest | state | correct guest
      state | reject | already cancelled
      state | nights | confirmed booking
      nights | restore | per-night quantity
      restore | mark | inventory restored exactly once
      mark | seq | original booking seq retained
      seq | event | new cancellation sequence
      event | outcome | record accepted cancellation
      reject | outcome | persist rejection
    `),
    g('hld-hotel-configure-td', 'Configure inventory: distinct command guards, not a setup pipeline', 'td',
      'After structural validation and the common request replay gate, each command independently selects its prerequisites. Rate updates preserve capacity and availability; none of these commands emits a booking journal event.',
      src('hld-hotel-reservation', 'hld_hotel_reservation', '109-164'), `
      event | event | New property-scoped command from *commands
      type | decision | Operation?
      property | decision | CreateProperty: next-seq absent?
      create | state | Set property next-seq := 0
      exists | decision | Other command: property exists?
      room | decision | Room type exists?
      roomOp | decision | CreateRoomType?
      createRoom | state | Write room-type exists? := true
      night | decision | Night already configured?
      nightOp | decision | InitNight or SetRate?
      init | state | InitNight: capacity and available := capacity; rate := input
      rate | state | SetRate: replace rate only
      reject | etl | First failed prerequisite becomes rejection
      outcome | state | Persist command payload and accepted/rejected outcome
    `, `
      event | type | ordered per-property loop
      type | property | CreateProperty
      property | create | absent
      property | reject | already exists
      type | exists | CreateRoomType / InitNight / SetRate
      exists | reject | missing property
      exists | room | exists
      room | createRoom | missing and CreateRoomType
      room | reject | missing and night command
      room | roomOp | exists
      roomOp | reject | yes: room-type-exists
      roomOp | night | no: night command
      night | nightOp | include existence in decision
      nightOp | init | InitNight and absent
      nightOp | rate | SetRate and present
      nightOp | reject | InitNight existing / SetRate missing
      create | outcome | accepted
      createRoom | outcome | accepted
      init | outcome | accepted
      rate | outcome | accepted, rate included
      reject | outcome | no inventory mutation
    `),
    g('hld-hotel-partition', 'Partition close-up: property owns room-type night inventory and journal', 'partition',
      'All business state is nested beneath property ID, which is also the depot hash key. Nested ordered maps are subindexed; availability reads a bounded night range.',
      src('hld-hotel-reservation', 'hld_hotel_reservation', '59-88,250-259'), `
      property | route | hash(property-id)
      root | state | $$properties[property]\nnext-seq and ingress-seq
      types | state | room-types[room-type] → exists?, nights
      nights | state | nights[night] → capacity, rate, available\nsubindexed
      bookings | state | bookings[booking-id] → immutable stay, total, state, reserved seq\nsubindexed
      events | state | events[event-seq] → reserved/cancelled payload\nsubindexed
      requests | state | requests[request-id] → full command, outcome, conflict count\nsubindexed
      query | query | availability range by property/type/date
      client | external | Direct outcome/night/booking/events selection
    `, `
      property | root | all property-owned state
      root | types | type key
      types | nights | room-type then night
      root | bookings | booking-id key
      root | events | monotonic seq key
      root | requests | property-scoped idempotency
      property | query | availability hashes property to state owner
      nights | query | range [checkin,checkout)
      bookings | client | get-booking
      requests | client | get-outcome
      events | client | after-seq range capped by limit
      nights | client | get-night direct point read
    `)
  ],
  'hld-metrics-pipeline': [
    g('hld-metrics-pipeline-core-lr', 'Series events, per-series microbatch state, and two range queries', 'lr',
      'A single depot routes by canonical SeriesKey into a microbatch topology. PState carries clock, counters, retained raw values, and two independent rollup widths; two query topologies read bounded ranges.',
      [...src('hld-metrics-pipeline', 'hld_metrics_pipeline', '21-29,100-123,125-182,184-214,219-251'), ...protocol('hld-metrics-pipeline', 'hld_metrics_pipeline', '16-64')], `
      client | external | Metrics client
      depot | depot | *series-events\nhash(canonical [tenant metric sorted labels])
      events | etl | metrics microbatch\nAdvanceClock or IngestSample
      series | state | $$series[SeriesKey]\nclock, cumulative admission counters, raw, buckets
      raw | query | query-raw\nhash(series), retained range
      roll | query | query-rollup\nhash(series), width-specific range
      info | external | get-series-info direct one-key submap
      clientOut | external | Return ascending raw / rollups / counters
    `, `
      client | depot | append typed event
      depot | events | consume
      events | series | per-series local writes
      series | raw | query topology reads range and origin returns rows
      series | roll | query topology reads 60 subindex or tiny 3600 map
      series | info | foreign-select-one counters
      raw | clientOut | ascending samples
      roll | clientOut | complete retained non-empty buckets
      info | clientOut | default zero fields for unseen series
    `),
    g('hld-metrics-ingest-td', 'Ingest: ordered admission checks, duplicate, and bucket folds', 'td',
      'The ETL uses pure bucket-start and fold helpers. Rejections increment their own cumulative counter, but only accepted samples write raw data and both rollup widths.',
      [...src('hld-metrics-pipeline', 'hld_metrics_pipeline', '43-44,78-95,157-182'), ...protocol('hld-metrics-pipeline', 'hld_metrics_pipeline', '25-37')], `
      event | event | IngestSample(series, timestamp, value)
      clock | state | Read series clock; unseen defaults 0
      future | decision | timestamp > clock?
      expired | decision | timestamp + 300 <= clock?
      duplicate | decision | raw timestamp already stored?
      futureCount | state | Increment rejected-future
      expiredCount | state | Increment rejected-expired
      duplicateCount | state | Increment rejected-duplicate
      accept | etl | First accepted timestamp
      raw | state | raw[timestamp] := value
      bstart | etl | Pure floor bucket starts\nmod 60 and mod 3600
      b60 | state | Fold count/sum/min/max\ninto subindexed buckets-60
      b3600 | state | Pure map fold into buckets-3600
      acceptedCount | state | Increment accepted
      done | end | No rejected path reserves timestamp
    `, `
      event | clock | process on series owner
      clock | future | read current clock
      future | futureCount | yes
      future | expired | no
      expired | expiredCount | yes
      expired | duplicate | no
      duplicate | duplicateCount | timestamp exists
      duplicate | accept | absent
      accept | raw | accepted only
      raw | bstart | pure bucket-start helper
      bstart | b60 | fold into 60-wide aggregate
      bstart | b3600 | fold into 3600-wide aggregate
      b60 | acceptedCount | write both folds
      b3600 | acceptedCount | write both folds
      acceptedCount | done | cumulative counter
      futureCount | done | increment reject count
      expiredCount | done | increment reject count
      duplicateCount | done | increment reject count
    `),
    g('hld-metrics-clock-td', 'Clock advance: stale no-op or three independent expiry paths', 'td',
      'Only a strictly increasing clock triggers expiry. Raw samples and 60-buckets are deleted by ordered range keys; 3600 buckets are read as a deliberately tiny plain map and pruned by helper.',
      [...src('hld-metrics-pipeline', 'hld_metrics_pipeline', '89-92,129-155'), ...protocol('hld-metrics-pipeline', 'hld_metrics_pipeline', '16-23')], `
      event | event | AdvanceClock(series, requested clock)
      current | state | Read clock default 0
      advances | decision | requested > current?
      noop | end | Equal or stale: no change, no expiry
      update | state | Write new clock
      expiry | etl | Branch three ways using new clock
      raw | state | Delete raw ts < clock - 299\nretention 300
      sixty | state | Delete bucket starts < clock - 7259\nwidth 60 plus 7200 retention
      hourly | state | prune pure map starts < clock - 10799\nwidth 3600 plus 7200 retention
      done | end | Raw expiry never subtracts aggregates
    `, `
      event | current | local read
      current | advances | compare requested and stored clocks
      advances | noop | requested <= current
      advances | update | requested > current
      update | expiry | write clock before prune branches
      expiry | raw | sorted map range to cutoff
      expiry | sixty | sorted map range to cutoff
      expiry | hourly | select tiny plain map
      raw | done | explode expired keys and delete
      sixty | done | explode expired starts and delete
      hourly | done | prune-buckets helper then replace map
      noop | done | no state changes
    `, ['Bucket aggregates are cumulative: raw expiry never subtracts. A late-but-admissible accepted sample can fold into an already-complete bucket; source has no finalization freeze.']),
    g('hld-metrics-query-td', 'Range reads: retained raw versus complete rollup windows', 'td',
      'Queries derive bounds from the current series clock. Rollups exclude incomplete buckets; width 60 uses a subindexed range, while width 3600 filters a tiny plain map. Both return to the origin.',
      src('hld-metrics-pipeline', 'hld_metrics_pipeline', '46-76,185-218'), `
      call | event | query-raw or query-rollup(series, range)
      route | route | hash(canonical SeriesKey)
      clock | state | Read $$series[key].clock, default 0
      type | decision | Query type?
      rawBounds | etl | raw lo=max(start,clock−299); hi=end
      rollBounds | etl | rollup lo=max(start,clock−width−7199)\nhi=min(end,clock−width+1)
      rawValid | decision | raw lo < hi?
      rollValid | decision | rollup lo < hi?
      empty | etl | Result []
      raw | state | Read sorted raw[lo,hi); format rows
      width | decision | width = 60?
      sixty | state | Read sorted buckets-60[lo,hi)
      hourly | state | Read tiny buckets-3600; filter and sort by start
      origin | route | origin
      end | end | Return ascending rows
    `, `
      call | route | foreign-invoke-query
      route | clock | local select
      clock | type | compute retained bounds
      type | rawBounds | raw
      type | rollBounds | rollup
      rawBounds | rawValid | intersect request with retention
      rollBounds | rollValid | intersect retention and completeness
      rawValid | empty | no
      rawValid | raw | yes
      rollValid | empty | no
      rollValid | width | yes
      width | sixty | yes
      width | hourly | no: 3600
      raw | origin | formatted samples
      sixty | origin | formatted bucket rows
      hourly | origin | formatted bucket rows
      empty | origin | no eligible interval
      origin | end | query response
    `),
    g('hld-metrics-partition', 'Partition close-up: canonical series key and nested time keys', 'partition',
      'The sorted-map SeriesKey ensures equivalent labels serialize and route consistently. Top-level ownership is the entire [tenant metric labels] tuple, not tenant or metric alone.',
      src('hld-metrics-pipeline', 'hld_metrics_pipeline', '21-29,100-123'), `
      key | route | hash(SeriesKey[tenant,metric,sorted labels])
      series | state | $$series[key] fixed fields
      clock | state | clock, accepted, rejected-future, rejected-expired, rejected-duplicate
      raw | state | raw[timestamp] → value\nsubindexed, raw retention 300
      m60 | state | buckets-60[bucket-start] → count,sum,min,max\nsubindexed
      m3600 | state | buckets-3600[bucket-start] → count,sum,min,max\ntiny plain map, ≤3 retained
      depot | depot | *series-events hash(series key)
      queries | query | query-raw/query-rollup route hash(same key)
    `, `
      key | series | exact series ownership key
      series | clock | fixed fields
      series | raw | timestamp ordered map
      series | m60 | width 60 key space
      series | m3600 | width 3600 map
      depot | key | hashed by series
      queries | key | foreign query partitioner hashes series
      raw | queries | bounded timestamp interval
      m60 | queries | bounded bucket range
      m3600 | queries | filter and sort tiny map by range
    `)
  ],
  'hld-ad-click-aggregation': [
    g('hld-ad-click-aggregation-core-lr', 'Campaign click records, immutable audit, windows, and range query', 'lr',
      'One campaign-hashed depot and microbatch own watermark, request audit, and nested window totals/breakdowns. Both get-window and get-windows invoke the one bounded, paged range query.',
      [...src('hld-ad-click-aggregation', 'hld_ad_click_aggregation', '22-24,178-253,258-287'), ...protocol('hld-ad-click-aggregation', 'hld_ad_click_aggregation', '22-75')], `
      client | external | Click accounting client
      depot | depot | *campaign-events\nhash(campaign-id)
      mb | etl | click-accounting microbatch
      pstate | state | $$campaigns[campaign]\nwatermark, requests, windows
      query | query | windows-in-range\npaged window start selection
      point | external | get-watermark / get-request\ndirect point selects
      clientOut | external | Return audit, watermark, sorted windows
    `, `
      client | depot | append click or watermark event
      depot | mb | consume
      mb | pstate | update audit and counted window rows
      pstate | query | query hashes campaign and reads bounded pages
      pstate | point | direct foreign point select
      query | clientOut | aggregate origin rows and sort by start
      point | clientOut | return point result
    `),
    g('hld-ad-click-disposition-td', 'Click: replay first, then late/fraud/invalid/billed disposition', 'td',
      'Disposition uses watermark at processing time and check precedence is explicit. Late clicks are audited but not counted; fraud and invalid clicks are counted but not billed. Replay produces no audit rewrite or counters.',
      [...src('hld-ad-click-aggregation', 'hld_ad_click_aggregation', '32-65,76-86,195-228'), ...protocol('hld-ad-click-aggregation', 'hld_ad_click_aggregation', '28-46')], `
      event | event | RecordClick(campaign, request, ts, geo, device, spend, valid, fraud)
      existing | decision | Audit record exists for campaign/request?
      replay | end | Existing: no effect, original frozen
      watermark | state | Read W, default 0
      window | etl | Pure floor ts to 60-wide start; E=start+60
      late | decision | W >= E + 120?
      fraud | decision | fraud flag true?
      valid | decision | valid flag true?
      audit | state | Write immutable first-arrival fields and W
      lateAudit | etl | :late; NO-COUNT-ROW
      fraudRow | etl | :fraud; clicks++, fraud-clicks++
      invalidRow | etl | :invalid; clicks++, invalid-clicks++
      billedRow | etl | :billed; clicks++, billed-clicks++, spend
      filter | decision | Delta non-nil?
      aggregate | state | +compound totals and [geo device] breakdown
      noWindow | end | Late: no aggregate write
      counted | end | Counted window updated
    `, `
      event | existing | check audit first
      existing | replay | yes
      existing | watermark | no
      watermark | window | current watermark
      window | late | compute start/end
      late | lateAudit | closed window
      late | fraud | still open
      fraud | fraudRow | true
      fraud | valid | false
      valid | invalidRow | false
      valid | billedRow | true
      lateAudit | audit | persist late audit with nil delta
      fraudRow | audit | count row
      invalidRow | audit | count row
      billedRow | audit | count row
      audit | filter | audit persisted; row normalized
      filter | noWindow | nil delta
      filter | aggregate | counted delta
      aggregate | counted | update totals and typed GeoDevice breakdown
    `),
    g('hld-ad-click-aggregation-watermark-td', 'Watermark: strictly increasing or no-op; it does not clean windows', 'td',
      'The implementation updates only the stored watermark. It does not expire audit requests or aggregate windows; query result windows have no retention branch.',
      [...src('hld-ad-click-aggregation', 'hld_ad_click_aggregation', '195-205,279-287'), ...protocol('hld-ad-click-aggregation', 'hld_ad_click_aggregation', '22-26')], `
      event | event | AdvanceWatermark(campaign, value)
      read | state | Read stored watermark default 0
      newer | decision | requested > current?
      noop | end | Equal/lower: no change
      write | state | Store requested watermark
      row | etl | Emit NO-COUNT-ROW
      done | end | No window deletion or audit pruning
    `, `
      event | read | consume campaign event
      read | newer | compare
      newer | noop | false
      newer | write | true
      write | row | update only watermark
      row | done | nil aggregation delta filtered
      noop | done | no-op
    `, ['Watermark advancement closes windows semantically for later click dispositions only. The source leaves historical request and window data durable.']),
    g('hld-ad-click-range-td', 'Window query: bounded pages, dimension reads, origin ordering', 'td',
      'Both get-window and get-windows invoke windows-in-range. The loop bounds each sorted-map-range-from read by remaining 60-unit slots and a doubling page cap, rather than scanning the entire suffix.',
      src('hld-ad-click-aggregation', 'hld_ad_click_aggregation', '105-155,232-259'), `
      call | event | windows-in-range(campaign,start,end)
      route | route | hash(campaign)
      init | etl | from=start; page=max(1,min(16,slots-left))
      read | state | $$campaigns[campaign].windows\nread at most page keys with totals, from inclusive
      split | etl | Keep rows with start < end; inspect last key
      more | decision | Page full AND last key < end?
      next | etl | from=last+1; page doubles up to 1024\nand remaining slot bound, minimum 1
      rows | etl | Emit in-range window/totals pairs from each page
      dims | state | Read complete breakdown for emitted window
      build | etl | Build totals plus joint geo/device map
      origin | route | origin
      done | end | Aggregate vector; sort by window start
    `, `
      call | route | invoke query
      route | init | initialize loop
      init | read | first bounded seek
      read | split | flat key/totals page
      split | more | evaluate continuation
      more | next | yes: schedule next iteration
      next | read | next bounded seek
      split | rows | emit this page regardless of continuation
      more | rows | no: last page, no further seek
      rows | dims | per emitted window
      dims | build | bounded by this window's dimensions
      build | origin | result row
      origin | done | return empty vector if no emitted rows
    `),
    g('hld-ad-click-aggregation-partition', 'Partition close-up: campaign, request ID, window, and joint dimension', 'partition',
      'Request IDs are campaign-scoped. The breakdown key is the typed GeoDevice pair representing the joint geo/device dimension, nested under window within campaign.',
      src('hld-ad-click-aggregation', 'hld_ad_click_aggregation', '24,159-193'), `
      campaign | route | hash(campaign-id)
      root | state | $$campaigns[campaign]\nwatermark
      requests | state | requests[request-id] → timestamp, start, dimensions, flags, disposition, observed watermark\nsubindexed
      windows | state | windows[window-start] → totals, breakdown\nsubindexed
      totals | state | counters: clicks, billed, invalid, fraud, billed-spend
      breakdown | state | breakdown[GeoDevice(geo,device)] → same counters\nsubindexed
      depot | depot | *campaign-events keyed by campaign
      query | query | windows-in-range: bounded sorted pages, then breakdown entries
      direct | external | get-request: direct point select
    `, `
      campaign | root | campaign-owned state
      root | requests | request identity scope
      root | windows | start = timestamp − mod(timestamp,60)
      windows | totals | window-level counters
      windows | breakdown | compound geo-and-device key
      depot | campaign | source partition key
      campaign | query | query partition key
      requests | direct | get-request direct selection
      windows | query | query range by start
    `)
  ],
  'hld-job-scheduler': [
    g('hld-job-scheduler-core-lr', 'Execution events, durable lifecycle state, and computed reads', 'lr',
      'Events route by execution ID into one microbatch and one PState. There is no query topology: client methods read PState directly and derive statuses or sorted ready nodes from the stored bounded DAG.',
      [...src('hld-job-scheduler', 'hld_job_scheduler', '9-14,16-78,79-117,119-152'), ...protocol('hld-job-scheduler', 'hld_job_scheduler', '24-108')], `
      client | external | Scheduler client
      depot | depot | *execution-events\nhash(execution-id)
      lifecycle | etl | lifecycle microbatch
      executions | state | $$executions[execution-id]\nExecutionState + claims map
      helpers | etl | DAG validation, status, claim decision, completion predicate
      reads | external | Direct selects for execution/node/claim\nclaimable nodes derived and sorted
      result | external | Return durable coordination state
    `, `
      client | depot | submit/clock/claim/complete
      depot | lifecycle | consume typed record
      lifecycle | helpers | pure bounded helper decisions
      helpers | executions | write state and/or claim decision
      executions | reads | foreign point selection
      reads | result | status projection / sorted ready IDs
    `),
    g('hld-job-scheduler-submit-claim-td', 'DAG submit and claim: invalid graph leaves no execution; claim IDs freeze decisions', 'td',
      'Kahn-style acyclicity helper follows reference validation of node count, IDs, dependency sets and references. A denied claim is still durably recorded; retry requires a fresh claim ID.',
      [...src('hld-job-scheduler', 'hld_job_scheduler', '16-65,86-111'), ...protocol('hld-job-scheduler', 'hld_job_scheduler', '24-61')], `
      submit | event | SubmitExecution(execution-id, DAG)
      valid | decision | map, 1..32 nodes, nonempty IDs, set deps, all refs resolve, acyclic?
      discard | end | Invalid: filtered; get-execution remains nil
      prior | decision | Existing state for execution?
      keep | end | Existing execution: immutable DAG no-op
      init | state | New DAG: clock 0; each node attempts 0, no lease, not success
      claim | event | Claim(execution,node,worker,claim-id)
      old | decision | Claim ID already decided?
      frozen | end | Existing: replay regardless of changed args/state
      state | etl | Read execution or nil; decide-claim helper
      reason | decision | unknown execution, node, success, deps, valid lease?
      deny | state | Save immutable denial with observed clock
      grant | state | attempts/token +1; lease expiry=clock+10; save grant
      ready | end | Claim decision stored, including denial
    `, `
      submit | valid | pure valid-dag? including Kahn cycle check
      valid | discard | false
      valid | prior | true
      prior | keep | already stored
      prior | init | unseen
      claim | old | lookup claim map
      old | frozen | present
      old | state | absent
      state | reason | decide-claim order
      reason | deny | any reason
      reason | grant | no reason
      deny | ready | store ClaimDecision wrapper
      grant | ready | replace state only when changed; store decision
    `),
    g('hld-job-scheduler-complete-td', 'Completion and lease expiry: strict fence, strict expiry boundary', 'td',
      'Clock advancement changes only logical time; status derives from current clock and lease expiry. Completion must match current worker and token and satisfy clock < expiry; invalid attempts silently leave state untouched.',
      [...src('hld-job-scheduler', 'hld_job_scheduler', '43-78,96-117,133-149'), ...protocol('hld-job-scheduler', 'hld_job_scheduler', '38-42,63-93')], `
      event | event | AdvanceClock(execution, value)
      state | decision | Execution exists and value > current clock?
      noop | end | Unknown execution or stale/equal clock
      write | state | Replace clock; leases remain stored
      status | etl | Derived: dependency success? then valid lease? else ready
      complete | event | Complete(execution,node,worker,token,result)
      exists | decision | Execution exists?
      node | decision | Node exists and not success?
      lease | decision | Current lease worker and token match?
      live | decision | Current clock < lease expiry?
      ignore | end | Any guard fails: no change
      success | state | Set success/result; clear lease
      reads | external | get-node/get-execution project current status\nclaimable nodes sorted
    `, `
      event | state | read state
      state | noop | no execution or no strict clock advance
      state | write | strict advance
      write | status | logical time now reinterprets expiry
      complete | exists | read execution state
      exists | ignore | missing execution
      exists | node | found
      node | ignore | absent or already success
      node | lease | pending candidate
      lease | ignore | wrong worker/token or nil
      lease | live | exact lease match
      live | ignore | clock >= expiry
      live | success | clock < expiry
      success | reads | immutable result, lease nil
      status | reads | derived status, expiry <= clock yields ready if deps complete
    `),
    g('hld-job-scheduler-partition', 'Partition close-up: execution contains bounded DAG and subindexed claim decisions', 'partition',
      'The execution ID hashes the depot and is the top-level PState key. DAG and node lifecycle live together as one ExecutionState; decisions are separately addressable by claim ID.',
      src('hld-job-scheduler', 'hld_job_scheduler', '79-85,119-149'), `
      execution | route | hash(execution-id)
      root | state | $$executions[execution-id]
      lifecycle | state | :state → ExecutionState(dag, clock, nodes)
      dag | memory | immutable node-id → dependency set\nmaximum 32 nodes
      nodes | memory | node-id → attempts, lease, success?, result
      claims | state | :claims[claim-id] → ClaimDecision\nsubindexed
      effect | route | Logical identity [execution-id,node-id]\nnot separate external subsystem
      client | external | direct execution state and claim selects
    `, `
      execution | root | all execution coordination state
      root | lifecycle | one bounded state object
      lifecycle | dag | immutable dependencies
      lifecycle | nodes | per-node mutable lifecycle
      root | claims | independently keyed decisions
      execution | effect | stable logical effect ID projection
      claims | client | get-claim point read
      lifecycle | client | node/execution/claimable projections
    `, ['External task execution and exactly-once side effects are explicitly outside the challenge. The source stores coordination state only; it has no worker service or dispatch queue.'])
  ],
  'hld-feature-flag-service': [
    g('hld-feature-flag-service-core-lr', 'Flag writes, one revisioned config per identity, and reads', 'lr',
      'One depot hashed by FlagId feeds a flags microbatch and one PState. Evaluation and config reads are direct PState reads; bucket computation is pure local SHA-256 with no stored-state access.',
      [...src('hld-feature-flag-service', 'hld_feature_flag_service', '13-51,53-108'), ...protocol('hld-feature-flag-service', 'hld_feature_flag_service', '24-58')], `
      client | external | Feature flag client
      depot | depot | *flag-writes\nhash FlagId(tenant,env,key)
      flags | etl | flags microbatch\nvalidate then strictly newer
      pstate | state | $$flags[FlagId] → whole configuration
      evaluate | external | direct config select + evaluate-config helper
      bucket | etl | Pure SHA-256 length-prefixed tuple; first 8 bytes mod 10000
      config | external | get-flag-config direct select
      result | external | Value, revision, reason and optional bucket
    `, `
      client | depot | put whole config
      depot | flags | consume
      flags | pstate | replace iff valid and newer revision
      pstate | evaluate | direct point select
      evaluate | bucket | invoked only when rules miss and rollout exists
      bucket | evaluate | deterministic integer result
      evaluate | result | ordered safe/rule/rollout/default result
      pstate | config | direct point select
      config | result | return exact stored map or nil
    `),
    g('hld-feature-flag-service-revision-td', 'Put config: invalid or stale writes are filtered without mutation', 'td',
      'Valid-config helper checks map/revision/rules/rollout shape. A valid equal or lower revision is a no-op; newer configuration replaces the whole stored record, not a partial merge.',
      [...src('hld-feature-flag-service', 'hld_feature_flag_service', '16-28,30-51'), ...protocol('hld-feature-flag-service', 'hld_feature_flag_service', '24-28')], `
      event | event | PutFlagConfig(FlagId, config)
      valid | decision | valid-config? shape and bounds pass?
      reject | end | Invalid: filter; no PState write
      prior | state | Read previous revision, nil if absent
      newer | decision | No prior OR incoming revision > stored?
      stale | end | Equal/lower: no-op
      replace | state | Replace complete config at FlagId
      done | end | No delete or partial update path
    `, `
      event | valid | validate pure helper
      valid | reject | false
      valid | prior | true
      prior | newer | local revision select
      newer | stale | false
      newer | replace | true
      replace | done | termval entire config
      stale | done | filter prevents transform
      reject | done | filter prevents revision lookup/write
    `),
    g('hld-feature-flag-service-evaluate-td', 'Evaluate: fail-safe short circuits before ordered equality and rollout', 'td',
      'Unknown operators anywhere in the rules force off-value even if an earlier equality rule would match. Rollout hash is computed only after no rule matches and rollout is present.',
      [...src('hld-feature-flag-service', 'hld_feature_flag_service', '53-86,98-104'), ...protocol('hld-feature-flag-service', 'hld_feature_flag_service', '34-58')], `
      call | event | evaluate(tenant, env, key, subject, attributes)
      read | state | Direct flag config read
      missing | decision | Config absent?
      none | end | value nil, revision nil, reason missing
      killed | decision | killed? true?
      off | end | Return off-value, reason killed
      safe | decision | Any rule operator is not :eq?
      unsafe | end | Return off-value, reason unknown-operator
      rule | decision | First ordered rule with present equal attribute?
      hit | end | Return serve, reason rule, rule index
      rollout | decision | Rollout config present?
      default | end | Return default; no bucket when no rollout
      hash | etl | Pure bucket from four length-prefixed identity components
      threshold | decision | bucket < threshold?
      served | end | Return rollout serve, bucket, reason rollout
      fallthrough | end | Return default and bucket, reason default
    `, `
      call | read | select exact FlagId
      read | missing | test config
      missing | none | absent
      missing | killed | present
      killed | off | true
      killed | safe | false
      safe | unsafe | unknown operator at any index
      safe | rule | all operators eq
      rule | hit | first match
      rule | rollout | no matching rule
      rollout | default | absent
      rollout | hash | present
      hash | threshold | SHA-256 pure helper
      threshold | served | bucket below threshold
      threshold | fallthrough | bucket at/above threshold
    `),
    g('hld-feature-flag-service-partition', 'Partition close-up: complete FlagId key stores one whole config', 'partition',
      'Tenant, environment, and flag key jointly route config. Subject identity participates in pure rollout hashing but is not a PState key or stored evaluation state.',
      src('hld-feature-flag-service', 'hld_feature_flag_service', '13-14,30-42,53-63,89-104'), `
      id | route | FlagId[tenant, env, flag-key]
      depot | depot | *flag-writes hash by full FlagId
      flags | state | $$flags[FlagId] → revision, killed, values, rules, rollout
      rule | memory | ordered vector of attribute/operator/value/serve
      rollout | memory | optional threshold and serve value
      subject | memory | subject-id input; no durable record
      hash | etl | UTF-8 byte-length prefix per tuple component\nSHA-256 first 8 bytes mod 10000
      client | external | exact FlagId PState select
    `, `
      id | depot | same identity key
      id | flags | top-level PState ownership
      flags | rule | config embeds ordered rules
      flags | rollout | optional config field
      subject | hash | pure input only
      id | hash | hash tuple uses tenant/env/key/subject
      flags | client | get/evaluate direct read
    `)
  ],
  'hld-enterprise-rag': [
    g('hld-enterprise-rag-core-lr', 'Revisioned entity writes, inverted token postings, and direct retrieval', 'lr',
      'One depot hashes by DocKey or UserKey; the entity microbatch independently applies membership, ACL, and content revisions. Content replacement diffs tenant-token postings. Query is client-orchestrated direct selects, not a query topology.',
      [...src('hld-enterprise-rag', 'hld_enterprise_rag', '10-33,35-89,109-167'), ...protocol('hld-enterprise-rag', 'hld_enterprise_rag', '21-76')], `
      client | external | Enterprise RAG client
      writes | depot | *writes\nhash event key (DocKey or UserKey)
      entities | etl | entities microbatch\nrevision branch by kind
      docs | state | $$docs[DocKey(tenant,doc)]\ncontent/ACL revisions, live count, groups
      chunks | state | $$chunks[DocKey][chunk-id]\ntext and distinct token set
      postings | state | $$postings[TokenKey(tenant,token)][ChunkRef(doc,chunk)]
      users | state | $$users[UserKey(tenant,user)]\nmembership revision and groups
      query | external | client selects membership, token postings, document metadata
      rank | etl | query-results authorization, scoring, deterministic sort, take k
      output | external | Citation chunks with revisions
    `, `
      client | writes | append put/delete/ACL/membership
      writes | entities | consume
      entities | users | membership newer branch
      entities | docs | ACL or accepted content branch
      docs | chunks | accepted content replaces complete chunk map
      chunks | postings | posting-diff emits removals and additions
      users | query | membership groups direct point read
      postings | query | distinct query tokens select tenant-scoped postings
      docs | query | metadata for matched doc IDs
      query | rank | join read results and groups
      rank | output | authorize before ranking/truncation
    `, ['Posting-key ownership can differ from document-key ownership: content ETL explicitly repartitions each posting diff by tenant-scoped TokenKey before changing $$postings.']),
    g('hld-enterprise-rag-revisions-td', 'Entity writes: independent revisions and content posting-diff helper', 'td',
      'Membership never touches documents. ACL never changes content liveness. Content put/delete share one revision fence; accepted replacement derives token sets, reads old chunks, diffs references, and routes each posting operation by TokenKey.',
      [...src('hld-enterprise-rag', 'hld_enterprise_rag', '15-33,52-89,127-137'), ...protocol('hld-enterprise-rag', 'hld_enterprise_rag', '21-48')], `
      event | event | Write kind membership / acl / put / delete
      membership | decision | membership revision strictly greater?
      user | state | Replace UserKey revision and groups
      doc | decision | ACL event?
      acl | decision | ACL revision strictly greater?
      aclWrite | state | Replace only ACL revision/groups\nmay remain tombstoned
      content | decision | Shared content revision strictly greater?
      stale | end | Equal/lower: no-op
      put | decision | Content kind :put?
      live | etl | chunks-map converts token vectors to sets
      tombstone | etl | Delete kind: new chunks map is {}
      metadata | state | Replace content revision and chunk-count
      old | state | Read old chunks for this DocKey
      replace | state | Replace $$chunks[DocKey] completely
      diff | etl | posting-diff set difference old/new refs
      op | decision | posting operation :add or :remove
      route | route | hash TokenKey(tenant,token)
      add | state | Write posting[ChunkRef] payload
      remove | state | Delete posting[ChunkRef]
    `, `
      event | membership | event kind
      membership | user | accepted only
      membership | stale | equal/lower membership revision
      event | doc | non-membership
      doc | acl | ACL kind
      acl | aclWrite | newer
      acl | stale | equal/lower
      doc | content | put or delete
      content | stale | not newer
      content | put | newer
      put | live | kind is put
      put | tombstone | kind is delete
      live | metadata | normalize token vectors into sets
      tombstone | metadata | empty chunk map
      metadata | old | update doc metadata, retrieve old chunk set
      old | replace | replace chunks
      replace | diff | pure posting-diff helper
      diff | route | explode each token/ref/op/payload
      route | op | compare operation after hash hop
      op | add | add branch
      op | remove | remove branch
    `),
    g('hld-enterprise-rag-query-td', 'Query: no candidate postings short-circuit; authorize before top-k', 'td',
      'The client returns empty for k=0/empty tokens and for empty membership groups. It reads postings for distinct tokens, then fetches document metadata and filters candidates by revision presence, intersecting ACL and positive distinct-token score before sorting and taking k. Liveness follows posting deletion, not an explicit chunk-count check.',
      [...src('hld-enterprise-rag', 'hld_enterprise_rag', '91-107,138-167'), ...protocol('hld-enterprise-rag', 'hld_enterprise_rag', '64-76')], `
      call | event | query(tenant,user,tokens,k)
      empty | decision | k=0 OR tokens empty?
      none | end | Return []
      member | state | Direct UserKey group select
      groups | decision | Groups empty or user unknown?
      denied | end | Return []
      skip | end | Omit this candidate; continue with others
      distinct | etl | Convert query tokens to set
      fetch | state | For each token select all postings under TokenKey(tenant,token)
      refs | etl | Union ChunkRefs from matches
      docs | state | Fetch metadata for distinct matched doc IDs
      chunks | etl | Distinct posting chunks keyed by ChunkRef
      auth | decision | content revision and ACL revision present; groups intersect?
      score | etl | Score cardinality(query-token set ∩ chunk-token set)
      positive | decision | score > 0?
      candidates | etl | Build citation with content and ACL revisions
      rank | etl | score desc, doc-id asc, chunk-id asc
      top | etl | Take k after authorization
      result | end | Return vector
    `, `
      call | empty | fast path
      empty | none | true
      empty | member | false
      member | groups | foreign membership select
      groups | denied | empty
      groups | distinct | nonempty
      distinct | fetch | distinct tokens only
      fetch | refs | tenant-keyed posting reads
      refs | docs | distinct docs from refs
      refs | chunks | matched payloads
      docs | auth | metadata join per doc
      chunks | auth | chunk payload
      auth | skip | missing content/ACL or no group intersection
      auth | score | authorized
      score | positive | set intersection cardinality
      positive | skip | zero
      positive | candidates | positive score
      candidates | rank | build result
      rank | top | deterministic comparator
      top | result | truncate after authorization
    `, ['Reference scope is sparse keyword postings with caller-supplied tokens and citation text. No embeddings, vector service, LLM, connector, or generation system is implemented.']),
    g('hld-enterprise-rag-partition', 'Partition close-up: tenant-qualified documents, users, postings, and chunk references', 'partition',
      'Document content and metadata are separated. Token postings have a different ownership key and are explicitly repartitioned during writes; chunk references carry doc and chunk IDs but the tenant is supplied by the TokenKey.',
      src('hld-enterprise-rag', 'hld_enterprise_rag', '10-13,22-26,35-51,81-89,150-167'), `
      docKey | route | DocKey[tenant,doc-id]
      docs | state | $$docs[DocKey] → content revision, chunk count, ACL revision, groups
      chunks | state | $$chunks[DocKey][chunk-id] → text, token set
      userKey | route | UserKey[tenant,user-id]
      users | state | $$users[UserKey] → membership revision, groups
      tokenKey | route | TokenKey[tenant,token]
      postings | state | $$postings[TokenKey][ChunkRef(doc-id,chunk-id)] → text, token set\nsubindexed
      depot | depot | *writes hash event :key
      query | external | tenant + token key to postings; refs then DocKey metadata
    `, `
      docKey | docs | durable document metadata
      docKey | chunks | isolated content payload
      userKey | users | tenant-qualified membership
      tokenKey | postings | tenant-qualified inverse index ownership
      docKey | tokenKey | accepted content diffs repartition by posting token
      tokenKey | postings | add/remove ChunkRef
      depot | docKey | document/ACL events
      depot | userKey | membership event
      tokenKey | query | one foreign selection per distinct query token
      postings | query | candidate chunk refs/payloads
      docKey | query | matched document ACL/content metadata
      userKey | query | current groups
    `)
  ]
};
