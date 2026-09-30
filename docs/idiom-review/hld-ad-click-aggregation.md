# Idiom review: HLD ad-click aggregation

Baseline: `43abd3fccff8777d8995f8a29658f3c4c97df01a`. This reviews the
reference implementation against the challenge contract and the Rama skill.
It does not propose a rewrite. None of the cited source files changed after
the baseline, so line numbers refer to it. Short paths used below:

- `module.clj` = `challenges/hld-ad-click-aggregation/test-resources/hld_ad_click_aggregation/module.clj`
- `functional_test_support.clj`, `efficiency_test_support.clj`,
  `independent_semantics_test.clj` = files under
  `challenges/hld-ad-click-aggregation/test-private/hld_ad_click_aggregation/`
- `README.md`, `protocol.clj` = `challenges/hld-ad-click-aggregation/README.md`,
  `challenges/hld-ad-click-aggregation/src/hld_ad_click_aggregation/protocol.clj`
- `auction_module/module.clj`, `chat_app/module.clj` = the reference modules
  under `challenges/auction-module/test-resources/` and
  `challenges/chat-app/test-resources/`
- `SKILL.md`, `references/…` = `plugins/rama-skill/skills/rama/…`

## Summary

The architecture is small and fits the contract:

- **One depot.** `*campaign-events` is hashed by `:campaign-id` and carries
  both event types (`module.clj:22-23, 179`).
- **One microbatch topology.** `"click-accounting"` consumes the depot with
  direct emit and dispatches with `<<subsource` (`module.clj:181-228`).
- **One PState rooted at the campaign.** `$$campaigns` holds the watermark,
  the subindexed audit map, and the subindexed window → breakdown maps
  (`module.clj:182-193`).
- **One query topology.** `"windows-in-range"` serves both `get-window` and
  `get-windows` (`module.clj:230-253`).
- **A foreign client** that makes one roundtrip per protocol call, plus a
  test-only barrier (`module.clj:258-301`).

What the write path does **not** contain:

- no `:ingress-seq` or other sequencing field. The depot records are the
  plain `AdvanceWatermark` and `RecordClick` defrecords (`module.clj:22-23`);
- no `<<batch`, no `+group-by`, and no partitioner. The only `|hash` and
  `|origin` in the file are in the query topology (`module.clj:232, 251`).

Per-campaign ordering therefore comes from depot partitioning plus
microbatch append-order emission. Counters are updated by a direct-emit
`+compound` on the task that already owns the campaign.

The range read uses `loop<-`, `|origin`, `aggs/+vec-agg`, and a final sort.
Those are read-path costs sized by the output.

Recommendation: **keep the implementation unchanged.** The findings below
are tradeoffs to document, plus one comment in `module.clj` whose stated
cause is imprecise (Finding 5). None needs a rewrite.

## Findings

### 1. One multiplexed, campaign-hashed depot is the right boundary

- **Evidence**
  - `module.clj:22-23` defines the two record types.
  - `module.clj:179` declares `*campaign-events` with
    `(hash-by :campaign-id)`.
  - `module.clj:198-220` dispatches them with `<<subsource` / `case>`.
  - The contract: writes to one campaign take effect in client order, and
    nothing is ordered across campaigns (`README.md:138-145`).
  - The private suite exercises the ordering in one synchronized phase, in
    both directions: advance-then-click and click-then-advance
    (`functional_test_support.clj:405-427`).
  - Campaign-scoped identity: the same request ID under two campaigns with
    different watermarks, then conflicting replays
    (`independent_semantics_test.clj:15-49`; also
    `functional_test_support.clj:330-347`).
- **Skill basis**
  - `references/depot-design.md:15-17`: use the same depot when events need
    local ordering on one entity, and dispatch with `<<subsource`.
  - `references/app-design.md:118-124` says the same.
    `app-design.md:133-135`: partition by the entity so that its events are
    processed sequentially.
- **Comparison with the other references**
  - chat-app uses the same idiom. Every client write except registration
    goes to `*user-actions-depot` (`hash-by :user-id`,
    `chat_app/module.clj:160`). Both its stream and microbatch topologies
    dispatch it with `<<subsource` (`chat_app/module.clj:200-251, 279-390`).
    Registration uses a separate `*register-depot` hashed by `:handle`
    (`chat_app/module.clj:159, 186-194`), because a handle claim is decided
    on the handle's task before any user ID exists. Ack-return alone is not
    the reason: `create-room!` also ack-returns, and it rides the
    user-actions depot (`chat_app/module.clj:210-221`).
  - auction uses one depot per event family (`auction_module/module.clj:70-71`).
    Bids hash by the listing owner (`nested-listing-user-id`,
    `auction_module/module.clj:66-67, 71`), so they colocate with the owner's
    listing state. Listings and bids have no ordering requirement between
    them (`references/depot-design.md:18`).
  - Neither pattern is a reason to split this depot.
- **Alternative considered:** separate `*watermarks` and `*clicks` depots.
  **Rejected.** Depot ordering is per depot partition
  (`references/microbatch.md:13`), so a click's relation to an advance on the
  same campaign would no longer follow client order. The audit `:watermark`
  and the disposition would then depend on processing order. The ordering
  tests at `functional_test_support.clj:405-427` would be at risk.

### 2. Direct-emit microbatch with per-record admission

- **Evidence**
  - `module.clj:196-197` binds `source>` to `%microbatch` and emits
    `(%microbatch :> *event)` with no `<<batch`.
  - For each `RecordClick`, on the campaign's own task:
    - read `:requests *request-id` to detect a replay (`module.clj:209`);
    - read `:watermark` (`module.clj:211`);
    - compute window and disposition (`module.clj:32, 44-52, 212-213`);
    - write the immutable audit record with `termval` (`module.clj:214-217`);
    - produce a count row (`module.clj:218`).
  - `AdvanceWatermark` reads the watermark and writes it only when it grows
    (`module.clj:199-203`).
- **Why this is correct**
  - `references/microbatch.md:13`: `%mb` emits each task's local partition
    in depot append order.
  - Tasks are single-threaded (`SKILL.md:24`), and reads inside the owning
    topology see its uncommitted writes (`references/microbatch.md:130`). So
    one campaign's records run in order, and each record sees the audit and
    watermark writes of the records before it. The suite checks a replay
    inside the same phase (`functional_test_support.clj:276-287`) and
    advance/click interleavings (`functional_test_support.clj:405-427`).
  - Retry: a microbatch attempt resets PState writes before it reapplies
    (`references/microbatch.md:126, 128`). Replaying the same records in
    the same order therefore converges to the same audit records and
    counters. `SKILL.md:32` requires exactly this.
  - Microbatch is the skill default (`SKILL.md:63`;
    `references/microbatch.md:5`). Nothing in the contract needs stream
    latency or append coordination: all writes are asynchronous and followed
    by `wait-for-processing!` (`README.md:140-145`).
- **Alternative considered:** `<<batch` around admission, to get batched or
  two-phase aggregation. **Rejected.** Batch blocks are declarative, and
  "Rama decides the execution order" (`references/batch.md:3`). The
  sequential first-arrival and watermark decisions would no longer be
  guaranteed. Direct emit is the stated fit for per-record processing
  (`references/microbatch.md:70`).

### 3. Campaign-rooted, growing, subindexed PState maps

- **Evidence**
  - `$$campaigns` (`module.clj:182-193`) has this shape:
    `{String (fixed-keys-schema {:watermark Long :requests (map-schema String audit …) :windows (map-schema Long {:totals … :breakdown (map-schema GeoDevice counters …)})})}`.
  - All three nested maps declare `{:subindex-options {:track-size? false}}`
    (`module.clj:187, 192, 193`). That is subindexing with size tracking off
    (`references/pstate-schema.md:127-139`).
  - Every PState access starts with `(keypath *campaign-id …)`:
    - writes at `module.clj:200-228`;
    - query reads at `module.clj:237, 248`;
    - client reads at `module.clj:280, 282`.
- **Skill basis**
  - Subindex collections that grow: `SKILL.md:59`;
    `references/pstate-schema.md:88-94`. Requests, windows, and breakdown
    pairs all grow with usage.
  - Turn size tracking off when size is never queried
    (`references/pstate-schema.md:129-130`). This module never calls
    `(view count)`.
  - Colocate related data (`SKILL.md:57`).
- **Growth is required by the contract**
  - Audit records are immutable, late clicks are audited, and closed windows
    stay readable (`README.md:66-78`;
    `functional_test_support.clj:305-328`).
  - `SKILL.md:34` forbids deleting data unless the spec requires it.
- **Efficiency coverage**
  - Bounded point-read and write cost as a campaign grows:
    `efficiency_test_support.clj:145-194`.
  - Counted-write cost independent of target-window breakdown width:
    `efficiency_test_support.clj:215-234`.
- **Tradeoff: one hot campaign is one hot task.** The contract makes the
  campaign the unit of ordering, so one hot campaign cannot be spread across
  tasks (`SKILL.md:53-55` names the balance goal). Two possible changes, and
  why neither is a local cleanup:
  - **Separate campaign-keyed PStates**, for example `$$requests` and
    `$$windows`. Equivalent and perhaps easier to read. It does not change
    the hot-task limit.
  - **Re-key the audit by `[cid rid]`.** This spreads storage, but the
    first-arrival decision would then need a partitioner hop away from the
    watermark's task and back. That is a different architecture.
- **Minor.** `GeoDevice` is a defrecord used only as a breakdown key
  (`module.clj:24, 191`). It is converted to `[geo device]` on read
  (`module.clj:95-102`). A vector key would drop the conversion; the record
  gives a typed schema. Either is fine.

### 4. `+compound` counter updates

- **Evidence**
  - Uncounted events produce `NO-COUNT-ROW` with a nil delta: advances,
    replays, and late clicks (`module.clj:70-74, 86, 204, 219-220`).
  - Counted first arrivals produce a full five-key delta
    (`module.clj:76-86`).
  - After `<<subsource`, all branches unify on `*row`, `filter>` drops nil
    deltas, and one `+compound` updates `:totals` and one breakdown entry
    (`module.clj:223-228`).
  - `+counters` is a combiner over `merge-with +` with
    `ZERO-COUNTERS` as `:init-fn` (`module.clj:88-93`). It meets the
    combiner laws (`references/aggregators.md:57-62`): it is associative
    and commutative, and `ZERO-COUNTERS` is its identity.
  - Missing nested keys are auto-initialised inside `+compound`
    (`references/aggregators.md:3-5`). Together with the `filter>`, this is
    why a window exists only once a click is counted into it, and a
    late-only window is never created
    (`functional_test_support.clj:236-241, 253`).
  - `:flush-required?` is not needed, because the state is a fixed
    five-key map (`references/aggregators.md:124-125, 211`).
- **Cost.** With direct emit, `+compound` applies per record: one
  read-modify-write of `:totals` and one of a single breakdown entry. There
  is no two-phase batching.
- **Alternatives considered**
  - **Five `aggs/+sum` leaves per counters map.** Idiomatic, but it spreads
    across leaves the per-disposition dispatch that `count-row` now
    centralises. This review found no evidence of an I/O difference.
    Optional.
  - **Pre-aggregate deltas per `(cid, ws, gd)` with `+group-by` / `<<batch`.**
    Could cut aggregate writes when many clicks in one microbatch hit the
    same key. It must run after ordered admission (Finding 2), for example
    in a later `<<batch` that reads deltas the first step wrote
    (`references/microbatch.md:63`). `+group-by` is batch/query only and
    emits one row per group (`references/aggregators.md:78-83`). Only worth
    doing if measured hot-key write amplification justifies the extra
    barrier.

### 5. Range query: paging, yielding, `|origin`, `+vec-agg`, sort

- **Evidence**
  - The query starts with `(|hash *campaign-id)` (`module.clj:232`). It is a
    leading partitioner on a topology input, so the client routes the
    invocation straight to the owning task
    (`references/query-topologies.md:120-126`).
  - `loop<-` pages with `(sorted-map-range-from *from {:max-amt *page})` and
    reads window keys plus `:totals` in one subselect (`module.clj:236-245`).
    - The first page is `min(16, slots in range)`, and at least 1.
    - Later pages double up to 1024, capped by the remaining slots.
    - Paging stops when a page is short or reaches `end`
      (`module.clj:116-154`).
  - Each emitted window gets one breakdown read with
    `(subselect ALL)` (`module.clj:248-249`). The breakdown is subindexed,
    so it has to be navigated into; it cannot be returned as a value inside
    the page selection (`references/pstate-schema.md:84`).
  - Then `(|origin)`, `aggs/+vec-agg`, and `sort-windows`
    (`module.clj:251-253`).
- **The paging comment names the wrong cause (measured).** The comment says
  `sorted-map-range` on a subindexed map iterates to the end of the map
  (`module.clj:106-114`). `references/paths.md:457` says instead that it is
  a "single disk seek + sequential scan". This review measured both with a
  standalone IPC probe (not committed). The probe used a subindexed
  `{String (map-schema Long Long {:subindex-options {:track-size? false}})}`
  PState, 2 tasks, and 3 keys in range, and captured RocksDB read events
  with `rtest/with-event-hook`, as `efficiency_test_support.clj:39-50` does.
  Iterator reads per 3-key range read:

  | Read | 0 keys above | 100 above | 1,100 above |
  |---|---|---|---|
  | `sorted-map-range`, no `:allow-yield?` | 3 | 4 | 4 |
  | `sorted-map-range`, `{:allow-yield? true}` | 3 | 103 | 301 |
  | `sorted-map-range-from` + `:max-amt 3`, `{:allow-yield? true}` | 3 | 4 | 4 |

  Each read also had 1 point read and 1 iterator seek. So the over-read
  comes from `sorted-map-range` **combined with** `:allow-yield?`, not from
  `sorted-map-range` alone. The 103 reads with 100 keys above match the
  comment's "104". The module's choice of `sorted-map-range-from` +
  `:max-amt` is still correct, because it keeps both bounded I/O and
  yielding.
  - Suggested fix (not applied, since the reference module is out of scope
    here): reword the comment to name `:allow-yield?`.
  - Possible skill gap (not applied): `references/dataflow.md:195` says
    yielding leaves a select's emits unchanged. It says nothing about I/O.
    A general note would help any module: a yielding bounded range read may
    scan past its end, so bound it with `-from`/`-to` plus `:max-amt`.
  - Not measured: whether a non-yielding `sorted-map-range` would pass
    `efficiency_test_support.clj:195-214` (700 → 2,900 out-of-range
    windows). Dropping yield also conflicts with `SKILL.md:26` for large
    ranges. The paged design avoids that tradeoff.
- **`{:allow-yield? true}`** on both scans follows `SKILL.md:26` and
  `references/dataflow.md:177-193`.
- **`get-window` reuses the range query** with `[ws, ws+60)`. The end is
  clamped at `Long/MAX_VALUE` (`module.clj:34-42, 283-285`). `first-page`
  gives a page of 1, so it reads at most one window key plus that window's
  breakdown. The top-of-range overflow case is tested
  (`functional_test_support.clj:366-403`).
  - The alternative is two client `foreign-select` calls, one for `:totals`
    and one for the breakdown. That costs two roundtrips, so the query
    topology is the better choice (`SKILL.md:39`;
    `references/app-design.md:229-246`).
- **Sorting.** All rows come from one task in ascending key order. The sort
  guards against `+vec-agg` output order, which the references do not
  document as preserved. It costs O(k log k) in the output size, which the
  README bound allows (`README.md:122-124`). Keep it.
- **Page-size constants.** `INITIAL-PAGE` and `MAX-PAGE` are tuning
  constants. The efficiency docstring warns that a large enough fixed page
  could pass its heuristic (`efficiency_test_support.clj:12-15`). The
  doubling design keeps work proportional to the in-range windows whatever
  the page constants.

### 6. Foreign client calls and synchronization

- **Writes.** `foreign-append!` with `:append-ack` (`module.clj:269-271`).
  A microbatch topology is decoupled from the append ack
  (`references/microbatch.md:3, 134`), so full `:ack` would add latency and
  no visibility guarantee.
- **Point reads.** Each is one `foreign-select-one`:
  - `get-watermark`: `(keypath cid :watermark) (nil->val 0)`
    (`module.clj:279-280`);
  - `get-request`: `(keypath cid :requests rid)` (`module.clj:281-282`).
  - Both navigate to a plain value rather than a subindexed handle, and
    `keypath` yields `nil` for absent keys
    (`references/foreign-client.md:96-107`).
  - Range reads are one `foreign-invoke-query` (`module.clj:283-287`). There
    is no client-side loop of selects.
- **Barrier.** `wait-for-processing!` calls
  `rtest/wait-for-microbatch-processed-count` with an append count **shared
  by every wrapper of the same IPC** (`module.clj:258-263, 269-270, 289-292,
  299-301`).
  - The processed count is cumulative for the IPC
    (`references/microbatch.md:171`). A wrapper created later that waited
    only on its own count could return before its writes were processed.
  - `functional_test_support.clj:470-504` tests exactly this case.
  - auction and chat keep per-wrapper counters
    (`auction_module/module.clj:175, 178, 206, 212`;
    `chat_app/module.clj:514-519, 597`). Their own suites accept that
    pattern. Here it would risk the late-wrapper test above, so this
    reference uses the stricter pattern. The harness docstring asks only for
    a cumulative count (`lib/harness/src/rama_challenges/harness.clj:21-25`).
- **Test-only function.** `references/microbatch.md:173` forbids
  `wait-for-microbatch-processed-count` in client wrappers of production
  code. Here it exists only to satisfy the harness `Synchronizable`
  contract, and `README.md:147-148` allows transient synchronization
  counters. A production client would drop the barrier and accept
  eventually consistent reads.

### 7. Unmeasured write-path micro-alternatives (optional)

The current reads are correct and tested. These are noted only because
`SKILL.md:30` and `SKILL.md:65-68` ask for minimal reads. Neither is
measured, and neither is backed by a failing test.

- `AdvanceWatermark` does a `local-select>` and then a conditional
  `termval` (`module.clj:200-203`). A single `local-transform>` with
  `(term #(max % w))` under `(nil->val 0)` would merge read and write.
  However, it would also write on stale advances. The README publishes no
  bound for advances (`efficiency_test_support.clj:17-20`).
- A first arrival makes two separate `local-select>` calls on the same
  campaign key (`module.clj:209, 211`). One select with `multi-path` could
  read both, but it would also read the watermark on replays, which today
  skip it.

## Review of the uncommitted draft of this document

The working-tree draft on top of `e744b01` was treated as untrusted and
checked line by line against source. It got these right: no write-path
sequencing, batching, or repartition; the query's `loop<-`, `|origin`,
`+vec-agg`, and sort; no client loop; the auction topology ranges
(`77-114` stream, `115-158` microbatch); and chat's multiplexed
user-actions depot. It was corrected for these errors:

- It cited `independent_semantics_test.clj:40-81`, but the file has 52
  lines. The test body is `15-49`.
- It cited `microbatch.md:68-77` as the basis for later records seeing
  earlier writes. Those lines only show a direct-emit example. The basis is
  `SKILL.md:24` plus `microbatch.md:130`.
- It claimed that a stream retry "could re-observe its own audit write and
  misclassify the click as a replay". The claim is unsupported: same-task
  writes within one event are atomic (`SKILL.md:24`). It was removed. The
  microbatch rationale now rests on the stated default and on retry
  convergence.
- It said chat splits registration because registration "needs a stream
  ack-return". But `create-room!` also ack-returns and is on the multiplexed
  depot. The real reason is the handle partition key.
- It left the `sorted-map-range` claim unverified. It is now measured, and
  the comment's stated cause is shown to be `:allow-yield?` (Finding 5).
- It cited the client as `module.clj:258-299`; the actual range is
  `258-301`. The multi-wrapper test is `470-504`, not `470-492`. Several
  skill-reference ranges were narrowed to the exact lines.
- It reported the private suite as not run. It has now been run (see
  below).

## Recommendation

Keep the reference implementation, README, protocol, private tests, and
skill unchanged in this change.

- Each of these follows a stated skill principle and is exercised by the
  private suite:
  - the one multiplexed campaign-hashed depot;
  - the direct-emit microbatch;
  - the campaign-rooted subindexed PState;
  - the `+compound` counters;
  - the paged query topology.

Follow-ups, not applied:

- Reword the `module.clj:106-114` comment to name `sorted-map-range` +
  `:allow-yield?` as the measured cause.
- Consider the general skill note from Finding 5 on bounding yielding range
  reads with `:max-amt`.
- Optional cleanups: vector breakdown keys; split campaign-keyed PStates;
  the Finding 7 micro-alternatives, only after measurement.

The only structural limit is per-campaign hot-task throughput. It is
inherent in the per-campaign ordering contract.

## Validation

- `clojure -X:test-private-harness`, run from
  `challenges/hld-ad-click-aggregation`. The alias adds `test-private` and
  `test-resources` to the path, so the reference module is under test.
  Result: `Ran 6 tests containing 426 assertions. 0 failures, 0 errors.`
  The 6 tests are functional, efficiency, and independent-semantics, each at
  2 and 4 tasks.
- The Finding 5 probe was a throwaway script run on the same classpath with
  `clojure -M`. It is not part of the repository.
- `scripts/test_reference_packages.py` was not used, because its `PACKAGES`
  tuple does not list this challenge (`scripts/test_reference_packages.py:11-13`).
