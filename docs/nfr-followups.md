# NFR follow-ups that need a README or reference change

The NFR test pass added only tests that the current README already states
and the current reference already passes. Each item below needs a README
decision, a reference fix, or both, before its test can land. Each item gives
the gap, the proposed README sentence, the reference fix and the test to add
afterwards.

Test helpers for all of these are in `lib/harness/src/rama_challenges/nfr.clj`
(`with-forced-stream-retry`, `capture-rocks-ops`, `capture-iterator-reads`,
`capture-per-task-ops`, `task-spread`, `topology-types-used`).

## Exactly-once under stream retry: the reference double-applies

### timed-notifications

- Gap: one forced `:streaming-complete` failure around `schedule-post!`
  delivers the post twice (feed `["x" "x"]`). The scheduled record has no
  identity, so the retry schedules the item a second time.
- README: delete the disclaimer "They do not measure ... replay, or
  exactly-once delivery behavior" (`README.md:25-26`) and add: "Each
  scheduled post is delivered exactly once, even when processing of the
  schedule request is retried."
- Reference fix: put a client-generated UUID in the depot record and
  deduplicate it at schedule time (skip scheduling when the UUID is already
  recorded), or schedule from a microbatch topology.
- Test: `with-forced-stream-retry` around `schedule-post!`, advance sim time,
  `tick!`, assert the feed holds exactly one item. Also add a tick-retry
  variant (the reference already passes it).
- Also worth adding (no reference change; optional README line "tick work is
  proportional to the number of due items"): schedule 2,000 far-future items,
  assert an idle `tick!` makes fewer than 50 reads; make 20 due and assert
  fewer than 200.

### collaborative-document-editor

- Gap: `failed-streaming` around one `AddText "abc"` edit yields
  `{:doc "abcabc" :version 2}`. On retry the edit's own first application is
  treated as a missed edit and transformed against.
- README: replace "No latency, maximum document size, or edit-history
  compaction guarantee is made: retained history and transformation work grow
  with edit count" (`README.md:29-30`) with: "Each submitted edit is applied
  exactly once, even when processing is retried. Transforming an edit costs
  work proportional to the number of edits it missed, not to the document's
  total history. Reading a document does not replay its history."
- Reference fix: carry a client edit ID in the `Edit` record and skip it when
  already applied (store the last applied ID per document, or index edits by
  ID), or move edit processing to a microbatch topology.
- Tests: (1) retry exactly-once as above; (2) after 300 edits, a 5-stale edit
  has `0 < iterator-reads < 20` and total reads under 30; (3) after 300 edits
  a document read makes fewer than 10 reads. Tests 2 and 3 pass on the
  current reference but conflict with the current disclaimer.

### content-moderation

- Gap: one `post!` under `failed-streaming` appears twice in `get-posts`.
  The README preserves legitimate duplicate appends (`README.md:16-17`), so a
  retry duplicate is indistinguishable from one.
- README: add "Each `post!` call appears exactly once in each recipient's
  feed, even when processing is retried; separate `post!` calls with equal
  content remain separate entries." Replace the page-cost disclaimer
  (`README.md:21-23`) with "A page reads work proportional to the entries it
  scans; posting and muting do fixed work, independent of feed length."
- Reference fix: give each post a client-generated ID and deduplicate on
  append, or append from a microbatch topology.
- Tests: retry exactly-once; with 300 posts, `get-posts reader 250 10` has
  `pos?` iterator reads and total reads under about 80; `mute!` with a
  300-post feed writes fewer than 5 entries.

### profile-module

- Gap: `profile_test.clj:26` asserts `(not= id retry-id)` for a same-UUID
  retry, matching `README.md:9-10`. The reference generates a new ID on
  retry and overwrites the registration, orphaning the first ID. This rewards
  the less fault-tolerant behaviour.
- README: replace the retry sentence with "Retrying `register!` with the same
  username and UUID returns the same user ID and creates no second profile."
- Reference fix: when the stored registration's UUID equals the request's
  UUID, return the stored `:user-id` instead of generating a new one.
- Test: delete the `(not= id retry-id)` assertion; add same-UUID retry
  returns the same ID; add `with-forced-stream-retry` around `register!` and
  assert one profile and one ID.

## Storage reclamation: the reference keeps dead data

### hld-metrics-pipeline: expiry

- Gap: a lazy-retention design (queries clamp to the clock; nothing is ever
  deleted) passes every test. The reference deletes eagerly, so it would pass
  a reclamation test; only the README is missing.
- README: "Expired raw samples and rollup buckets must be physically removed
  when the clock passes their retention; storage for a series is bounded by
  its retention window."
- Test: around an `advance-clock!` that expires at least 200 raw samples and
  M buckets, assert the committed `:writes` are at least 200 + M.

### hld-metrics-pipeline: data above the query range

- Gap: all growth in the existing tests sits below or at the query range.
  `query-raw` and the 60-width `query-rollup` use `sorted-map-range` on
  subindexed maps (`test-resources/hld_metrics_pipeline/module.clj:190,207`),
  which the sibling ad-click build measured iterating to the end of the map.
  An above-range test would likely fail the reference, contradicting the
  README's "not ... data outside the range" (`README.md:123-137`).
- README: none (already stated).
- Reference fix: replace `sorted-map-range` with `sorted-map-range-from` plus
  a `:max-amt` page loop, as in `hld-ad-click-aggregation`'s reference.
- Test: add 290 samples above a narrow `query-raw` range; cost must stay
  within `2x + 20` of the baseline with nothing above. Same for rollup(60)
  with 100 buckets above.

### hld-search-autocomplete: superseded generations

- Gap: `$$phrases`, `$$sessions` and `$$prefixes` are keyed by generation and
  publish never deletes the old generation (`test-private/VALIDATION.md:35`).
  Deleting a whole generation during publish would contradict "publish must
  not depend on the size of the previous generation's trend data"
  (`README.md:95-97`).
- README: "Data belonging to a superseded generation is reclaimed within a
  bounded number of later operations; reclamation work is amortised and never
  proportional to the old generation's size in a single call."
- Reference fix: after publish, enqueue the old generation for deletion and
  delete a bounded slice per later `publish-snapshot!` or `record-search!`
  (or per tick), paging with `:max-amt`.
- Test: publish generation 2 over a 1,000-phrase generation 1, drive enough
  follow-up operations to drain the queue, and assert generation-1 reads
  return nil while no single call writes more than a fixed bound.

## Missing README budgets (reference already passes)

- **who-to-follow**, per-refresh cost independent of N: `README.md:27`
  ("computing more per call is allowed") permits full recompute. Proposed:
  "One `refresh!` does work bounded by the refreshed accounts' follow graphs,
  independent of the total number of accounts." Test: capture one
  `refresh!` at N=150 and N=600, ratio below 1.5.
- **hld-ad-click-aggregation**, write budget: "One counted click writes at
  most 8 PState entries; replayed and late clicks write at most 4." Balance
  across campaigns: "Work for distinct campaigns is balanced across tasks."
  (Per-campaign ordering, `README.md:141-145`, means a hot campaign stays on
  one task; that is intended.) Launch with `:threads tasks` for attribution.
- **hld-metrics-pipeline**, balance and write budget: "Work for distinct
  series is balanced across tasks; one sample writes at most 8 PState
  entries."
- **Transactional HLD challenges** (payment, stock-exchange, ticketing,
  hotel, file-sync):
  - Balance: "Commands for distinct tenants/symbols/events are spread across
    tasks." Test with `gen-task-keys` and `task-spread`.
  - Topology class (bank-transfer style): "Commands are processed with
    exactly-once semantics under worker failure" with a `#{:microbatch}`
    assertion. Optional.
  - A bank-style "no `:partitioner` events" test would fail all five
    references: each stamps an `:ingress-seq` write and then regroups with
    `+group-by` / `+vec-agg` and a `loop<-` (payment `module.clj:122-128`,
    stock `:106-111`, ticketing `:201-207`, hotel `:98`, file-sync
    `:290-295`). Rewrite the references to process per-entity in depot order
    without regrouping before adding that test.
- **family-tree, music-catalog-migration, content-moderation,
  collaborative-document-editor**: the NLB-port READMEs disclaim cost. Any
  cost assertion needs the disclaimer softened first.
- **rest-api-integration-module**: add "HTTP I/O must not block module
  processing" to justify a tasks-1/threads-1 responsiveness test (the
  reference passes at about 52 ms). Do not add "a slow URL must not delay
  other URLs": stream records on one partition are ordered, so the reference
  fails it.
