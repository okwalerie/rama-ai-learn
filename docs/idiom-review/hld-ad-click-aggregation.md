# Idiom review: HLD ad-click aggregation

Baseline: `43abd3fccff8777d8995f8a29658f3c4c97df01a` (`master` at the requested
baseline). This is an audit of the reference implementation and challenge
contract, not a proposed rewrite. Line numbers below refer to that baseline.

## Summary

The implementation is already shaped around the challenge's hardest
constraints: all mutations for a campaign go through one campaign-hashed
depot, the microbatch processes watermark advances and clicks together, and
the nested PState paths keep one campaign's audit and aggregate state
colocated. The bounded page query is more careful than a plain full-map scan.
Those are defensible choices, not automatic anti-patterns.

The main tradeoff is that campaign is both the required ordering boundary and
the storage/routing boundary. A very hot campaign therefore remains a hot
task, and the immutable audit plus readable windows necessarily retain
growing per-campaign collections. The contract does not permit solving that
growth by deleting records. The write path has no `:ingress-seq`,
`+group-by`, or extra repartition stage for regrouping events. The range-read
query does use `|origin`, `+vec-agg`, sorting, and a bounded `loop<-` (see
Finding 4); the client does not loop over distributed selects. Keep those
read-path costs distinct from the write path.

## Concrete findings and alternatives

### 1. One multiplexed depot and one ordered microbatch

- `module.clj:179` declares `*campaign-events` with `(hash-by :campaign-id)`.
- `module.clj:181-228` consumes both `AdvanceWatermark` and `RecordClick`
  through the same source and dispatches with `<<subsource`.
- The README requires same-campaign writes to take effect in client order
  (`README.md:138-145`); functional coverage includes advance/click ordering
  in one synchronized phase (`functional_test_support.clj:405-427`).
- The skill's microbatch reference says a depot partition emits its local
  records in append order (`.agents/skills/rama/references/microbatch.md`,
  “Source binding and emitting records”).

**Assessment:** Retain this as the default. Splitting clicks and watermark
advances into separate depots/topologies would make a campaign's cross-type
ordering harder to preserve and could make the click's observed watermark
depend on topology scheduling. Auction demonstrates that separate depots
are idiomatic when they represent distinct independently partitionable event
families (`auction-module/module.clj:70-71, 76-117`); it is not evidence that
this campaign-ordered stream should be split. A stream topology is another
option only if the product requires low-latency visibility or append
coordination; the skill defaults to microbatch for exactly-once processing
and throughput (`plugins/rama-skill/skills/rama/SKILL.md:63`).

### 2. Per-record admission followed by `+compound`

- Replay detection, watermark lookup, disposition, immutable audit write, and
  the counted/uncounted decision are at `module.clj:206-220`.
- Counted rows converge at `module.clj:221-228`; `+compound` updates totals
  and one breakdown leaf from the same counter delta.
- `count-row` supplies a complete counter delta (`module.clj:76-93`), and
  `NO-COUNT-ROW` prevents advances, replays, and late clicks from updating
  aggregates (`module.clj:70-74, 204-228`).

**Assessment:** A tempting alternative is to collect raw events, group/sort
them, then route them back through extra repartition stages. That may reduce
repeated aggregate writes when many events share a key, but grouping cannot
be moved ahead of first-arrival detection: request IDs are campaign-scoped,
replays must not count, and a watermark advance can change the disposition
of later clicks in the same campaign. If aggregation is changed, first
establish the ordered admission decisions, then aggregate only accepted
counter deltas; measure the extra buffering, repartition, and latency against
the current single PState update path. The skill's aggregator reference
describes `+group-by` as a batch/query aggregation that emits one row per
group, not as a replacement for ordered business-state transitions.

### 3. Campaign-rooted state: local and bounded paths, but a hot-key ceiling

- `module.clj:181-193` stores watermark, requests, windows, and breakdowns
  under one campaign key. Nested request/window/breakdown maps disable size
  tracking through `:subindex-options`.
- Writes use campaign-rooted paths at `module.clj:200-228`; point reads use
  request/campaign paths in the foreign client at `module.clj:279-282`.
- The efficiency suite grows request, window, campaign, and breakdown counts
  and checks operation scaling (`efficiency_test_support.clj:104-182,
  183-262`).
- The audit is immutable and windows stay readable after closure, so the
  contract requires long-lived request and window data (`README.md:58-75,
  101-108`).

**Assessment:** The nesting makes campaign reads isolated and keeps related
state colocated; it is consistent with the skill's partition-alignment and
colocation guidance (`plugins/rama-skill/skills/rama/SKILL.md:55-61`). The
subindexed maps are essential to avoid treating every growing map as one
in-memory value. Auction uses the same idiom for growing per-user listings
and per-listing bids (`auction-module/module.clj:78-93`), and chat uses
subindexed per-room/per-user maps for its growing message and derived views
(`chat-app/module.clj:172-179, 255-277`).

The structural limit is tenant skew: every event for one campaign is routed
to the same task to preserve its sequence, so one exceptionally hot campaign
cannot be spread across tasks without changing the ordering/atomicity model.
Splitting the maps into separate PStates while keeping campaign partitioning
could clarify access patterns, but would not remove that hot-task limit.
Partitioning request storage by `(campaign-id, request-id)` could spread
audit reads/writes, but would require cross-partition coordination to ensure
the first-arrival decision and aggregate update remain exactly once and
ordered with watermark advances. Treat that as a new architecture, not a
local schema cleanup.

### 4. Range query pagination and result materialization

- `page-rows`, page-size limits, and the reason for bounded
  `sorted-map-range-from` reads are documented at `module.clj:106-154`.
- The query routes to the campaign at `module.clj:230-240`, loops over
  bounded pages at `module.clj:236-245`, and scans each selected window's
  breakdown at `module.clj:246-250`.
- `+vec-agg` and final sort are at `module.clj:251-253`.
- `get-window` and `get-windows` each invoke the range query once from the
  client (`module.clj:283-287`); there is no client loop of per-window
  `foreign-select` calls.
- The README explicitly permits work proportional to requested windows and
  returned breakdown entries (`README.md:113-125`); efficiency coverage
  measures out-of-range growth (`efficiency_test_support.clj:144-182,
  194-217`).

**Assessment:** Keep the query topology instead of moving range traversal to
the client: client-side foreign calls are network roundtrips, while this
query can perform the bounded scan on the campaign's owning task and return
the requested result in one invocation. `{:allow-yield? true}` on the range
and breakdown reads is also appropriate for potentially large scans. The
vector and sort cost is proportional to output size; sorting may be
removable only if the exact emitted order is guaranteed by the query and
aggregator semantics, so verify that before changing it. Point reads for
watermark/request are each one bounded key lookup and need not become query
topologies unless callers need a combined result or a server-side multi-step
operation.

## Reference idioms consulted

- **Auction:** event-specific depots with matching hash partitioners and
  nested subindexed maps (`challenges/auction-module/test-resources/auction_module/module.clj:70-93`);
  client point/range reads at `:185-204`.
- **Chat app:** distinct depots where registration and user-action routing
  differ (`challenges/chat-app/test-resources/chat_app/module.clj:158-160`),
  several focused PStates/topologies with subindexed growth (`:164-179,
  253-277`), and query topologies for multi-step pages (`:417-494`). Its
  client includes a bounded member-page read and a deliberate chunked scan
  plus query calls (`:556-580`); that pattern is not needed for this
  challenge's server-side window range query.
- **Skill:** production/retry correctness and task model
  (`plugins/rama-skill/skills/rama/SKILL.md:22-34`), partitioning and
  subindexing (`:53-68`), plus aggregator semantics in
  `plugins/rama-skill/skills/rama/references/aggregators.md`.

## Recommendation

Keep the implementation unchanged for this review. The selected depot,
microbatch, and query topology align with explicit ordering, durability, and
efficiency contracts. If this module is evolved for very large single-
campaign throughput, first decide whether the product can weaken per-
campaign serialization; without that decision, repartitioning the growing
collections is not a safe or complete fix. The private suite was not run for
this doc-only change; no implementation rewrite was attempted.
