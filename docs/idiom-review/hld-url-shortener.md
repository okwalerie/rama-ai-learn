# HLD URL shortener: Rama idiom review

## Scope and result

Reviewed the challenge at baseline `43abd3fccff8777d8995f8a29658f3c4c97df01a`. All paths in this
document are repository-relative.

- `challenges/hld-url-shortener/README.md`
- `challenges/hld-url-shortener/src/hld_url_shortener/protocol.clj`
- all four files in `challenges/hld-url-shortener/test-private/hld_url_shortener/`
- `challenges/hld-url-shortener/test-resources/hld_url_shortener/module.clj`
- `challenges/hld-url-shortener/test-resources/PLAN.md` and `FULL_SPEC_REVIEW.md` (design and
  validation records cited below)
- `challenges/auction-module/test-resources/auction_module/module.clj`
- `challenges/chat-app/test-resources/chat_app/module.clj`
- `plugins/rama-skill/skills/rama/SKILL.md` and `plugins/rama-skill/skills/rama/references/paths.md`

The requested architecture description is only partly present in this checkout. The reference
does have one alias-partitioned multiplexed depot, one microbatch topology, a single PState, and
client-side foreign PState reads. But the reference does **not** contain `:ingress-seq`,
`+group-by`, `+vec-agg`, sorting, `loop<-`, or a read-side repartition. Those symbols/forms do
not occur in the challenge source. Treating them as current defects would be a false positive.

No architectural rewrite is recommended. The implementation is aligned with the contract and its
bounded-work tests, and the alternative patterns in findings 1-4 would add coordination or I/O
for this single-key workload. Finding 5 records one minor, measured local alternative that is
deliberately not applied to the reference.

## Findings and idiomatic alternatives

### 1. One multiplexed depot and microbatch topology

**Observed.** `challenges/hld-url-shortener/test-resources/hld_url_shortener/module.clj:24-27`
declares `*alias-events` with `(hash-by :alias)` and one `microbatch-topology` named `core`;
lines 38-97 dispatch the five record types from the shared depot. Each record carries the owner
alias (`challenges/hld-url-shortener/test-resources/hld_url_shortener/module.clj:18-22`). The
PState is also keyed by alias
(`challenges/hld-url-shortener/test-resources/hld_url_shortener/module.clj:28-36`), so source
events and state operations remain colocated. The README specifies per-client per-alias order
and barrier semantics (`challenges/hld-url-shortener/README.md:163-179`), and the functional
suite checks sequential mixed operations and competing creates
(`challenges/hld-url-shortener/test-private/hld_url_shortener/functional_test_support.clj:234-289`).

**Idiomatic choice.** Keep this layout while all writes are ordered by alias and each event
touches only alias-local state. The shared depot does not imply a global processing bottleneck:
its partitioner distributes aliases, and the state uses the same ownership key. Microbatch is
consistent with the contract's explicit post-write barrier and avoids stream retry/partial-write
complexity for the counter and recorded create outcomes. The generic Rama skill likewise defaults
to microbatch unless low-latency/ack coordination requires stream
(`plugins/rama-skill/skills/rama/SKILL.md:63`).

**Alternative and why not here.** Split depots/topologies only if a write class needs materially
different latency, ack, or partitioning semantics. In particular, a separate stream path would
need retry-safe state changes and a correct shared barrier spanning that path; it is not justified
by this contract. The auction example uses separate depots and a stream plus microbatch
(`challenges/auction-module/test-resources/auction_module/module.clj:69-77,95-121`) because
listing/bid/expiry work has different access and scheduling needs; that is not evidence that this
shortener needs the same split.

### 2. No ingress sequence, group-by, vector aggregate, global sort, or loop repartition

**Observed.** Searching `challenges/hld-url-shortener/src/`,
`challenges/hld-url-shortener/test-resources/hld_url_shortener/`, and
`challenges/hld-url-shortener/test-private/` found no `:ingress-seq`, `+group-by`, `+vec-agg`,
`sort`, `loop<-`, `|hash`, or query topology; the only `select>` matches are `local-select>`. The
dataflow is a record-type dispatch and local updates
(`challenges/hld-url-shortener/test-resources/hld_url_shortener/module.clj:38-97`); there is no
cross-alias fan-in whose order must be reconstructed.

**Idiomatic choice.** Preserve per-alias ordering at the depot partitioner and process an
alias's records directly on its state-owning task. This avoids allocating sequence metadata,
grouping/aggregating event collections, buffering for sort, or an extra loop/repartition pass.
Those mechanisms make sense only if a real global ordering, cross-key batch reduction, bounded
page assembly, or cross-partition join requires them. For this API, each write concerns one alias
and the read methods are point lookups, so each such pass would add work without satisfying an
otherwise unmet contract.

### 3. Growing collections in one PState

**Observed.** `$$links` is keyed by alias
(`challenges/hld-url-shortener/test-resources/hld_url_shortener/module.clj:28-36`). Each alias
stores fixed-size link facts and a click counter, plus `:outcomes` and `:click-ids` collections
marked `{:subindex? true}`. The write path uses point lookup/insertion on the click-id set
(`challenges/hld-url-shortener/test-resources/hld_url_shortener/module.clj:85-97`) and point
lookup on request outcome
(`challenges/hld-url-shortener/test-resources/hld_url_shortener/module.clj:44-59`); read paths do
not scan either collection
(`challenges/hld-url-shortener/test-resources/hld_url_shortener/module.clj:130-142`). The private
performance support builds 32- and 512-entry histories and enforces bounded RocksDB work
(`challenges/hld-url-shortener/test-private/hld_url_shortener/performance_test_support.clj:83-90,127-188`);
the README also calls out the opaque-value materialization limitation
(`challenges/hld-url-shortener/README.md:122-126`).

**Idiomatic choice.** Keep the small fixed link record alongside the subindexed histories under
the same alias key: the hot reads and writes need that owner key and the tests require fixed work
as history grows. This follows the skill's subindexing guidance for large collections
(`plugins/rama-skill/skills/rama/SKILL.md:59`). Avoid an opaque vector/map value that must be read
and rewritten wholesale.

**Tradeoff / alternative.** The `:click-ids` index can grow to a million entries for a viral
alias (workload: `challenges/hld-url-shortener/README.md:70-75`); subindexing prevents loading the
whole set for each observation, but does not make its retained storage small or remove the hot
alias's task-level concentration. A global click-id PState partitioned independently could spread
that storage, but would make each click a cross-partition dedup-plus-counter operation and
complicate atomicity and the single-alias ordering guarantee. The plan records that global
click-id design as rejected Option B
(`challenges/hld-url-shortener/test-resources/PLAN.md:57-60`), and separately accepts hot-alias
task concentration while rejecting `|all` replication of hot links
(`challenges/hld-url-shortener/test-resources/PLAN.md:90-95`). For the present protocol, exact
lifetime deduplication requires remembering which IDs have already counted; do not silently
discard old IDs or change that guarantee. If future requirements relax lifetime dedupe or
introduce a bounded retention window, revisit the storage tradeoff explicitly.

### 4. Client-side foreign reads versus query topologies

**Observed.** The client wrapper obtains one foreign PState handle
(`challenges/hld-url-shortener/test-resources/hld_url_shortener/module.clj:108-114`) and performs
exactly one `foreign-select-one` per protocol read
(`challenges/hld-url-shortener/test-resources/hld_url_shortener/module.clj:130-142`). Each path is
rooted at the caller's alias; the challenge plan documents a single `hash(alias)` hop per read
(`challenges/hld-url-shortener/test-resources/PLAN.md:8-18,84-86`). These are not distributed
multi-key client-side loops.

**Idiomatic choice.** Keep point reads client-side. For one key and one compact result, this is
already one PState lookup/network request; moving it behind a query topology does not combine
any reads and would not improve the operation count.

**Alternative and why not here.** Use a query topology when one logical read needs several
partitioned PStates, a bounded scan/page, or a fan-out that would otherwise require multiple
client roundtrips. The chat example follows that pattern for room/thread pages and aggregation
(`challenges/chat-app/test-resources/chat_app/module.clj:417-494`), while retaining direct
client-side point reads for simple lookups
(`challenges/chat-app/test-resources/chat_app/module.clj:546-553`). The generic skill warns that
each client foreign select is a network roundtrip and recommends query topologies to consolidate
multi-read paths (`plugins/rama-skill/skills/rama/SKILL.md:39`); it does not imply that every
point lookup should become a query topology.

### 5. Minor: existence-guarded flag updates can use `must` (measured, not applied)

**Observed.** Delete, block, and unblock each read the whole `:link` record with
`local-select>`, test `(some? *link)`, and then run a separate `local-transform>` on one flag
(`challenges/hld-url-shortener/test-resources/hld_url_shortener/module.clj:61-80`). The selected
value is used only as an existence test.

**Idiomatic alternative.** The skill's path reference lists `[(must *k) ...]` as the "only if key
exists" transform (`plugins/rama-skill/skills/rama/references/paths.md:156,713-714`), and the
skill directs choosing the path form with the fewest storage reads
(`plugins/rama-skill/skills/rama/SKILL.md:65-68`). Equivalent single-transform form:

```clojure
(case> BlockLink :> {:keys [*alias]})
(local-transform> [(must *alias :link) :blocked? (termval true)] $$links)
```

**Evidence.** A throwaway inline module with the same `$$links` schema, run in the challenge
classpath at 2 tasks and measured with the private suite's `capture-ops` (RocksDB operations on
non-internal PStates), gave:

| Pattern | Existing alias | Missing alias |
|---|---|---|
| select + `<<if` + transform (current) | 2 point reads, 1 written entry | 1 point read, 0 written |
| `must` transform | 1 point read, 1 written entry | 1 point read, 0 written |

Both forms set the flag on the existing alias, left the 2008-character target URL intact, and
created no entry for the missing alias (`foreign-select-one [(keypath alias)]` returned `nil`).

**Why not applied.** Lifecycle flag writes are low-volume administrative operations, already well
inside the per-write budget, and the current form is correct. The saving is one point read per
lifecycle write on an existing alias. The create path (`module.clj:44-59`) and click path
(`module.clj:85-97`) are not candidates: they need the selected values (recorded outcome, current
click count) to choose the outcome or compute the `termval` increment.

## Validation evidence and limits

- Canonical private suite on the unmodified reference, from `challenges/hld-url-shortener` with no
  `implementations/hld-url-shortener` on the classpath:

  ```
  clojure -J-Xmx1600m -X:test-private-harness
  Testing hld-url-shortener.functional-challenge-test
  Testing hld-url-shortener.performance-challenge-test
  Ran 4 tests containing 270 assertions.
  0 failures, 0 errors.
  ```

  One `LeaderNotFoundException` log line appeared during IPC shutdown; it is logging noise and
  did not fail any test (same as noted in
  `challenges/hld-url-shortener/test-resources/FULL_SPEC_REVIEW.md:76-78`).
- Private functional tests cover 2- and 4-task runs, two wrappers, ordering, concurrent create
  outcomes, and click deduplication
  (`challenges/hld-url-shortener/test-private/hld_url_shortener/functional_challenge_test.clj:7-15`,
  `challenges/hld-url-shortener/test-private/hld_url_shortener/functional_test_support.clj:14-23,234-316`).
- Private performance tests measure reads/writes and task distribution at both task counts
  (`challenges/hld-url-shortener/test-private/hld_url_shortener/performance_challenge_test.clj:7-15`,
  `challenges/hld-url-shortener/test-private/hld_url_shortener/performance_test_support.clj:114-224`).
- The event hook cannot detect whole-history opaque-value materialization
  (`challenges/hld-url-shortener/test-resources/FULL_SPEC_REVIEW.md:53-62`); the reference's
  subindexed layout was checked by inspection (finding 3).
- Finding 5's measurement used a scratch module evaluated inline; no repository file was changed
  for it.
- Main residual capacity caveat: per-alias click dedupe history remains unbounded by contract,
  and a viral alias is intentionally concentrated on one partition. That is a workload/schema
  tradeoff, not evidence for global repartitioning under the current API.

## Conclusion

The cited multiplexed-depot, microbatch, subindexed per-alias state, and client point-read idioms
are suitable for this contract. The enumerated sequence/group/aggregate/sort/loop/repartition
pipeline is absent from the source at the requested baseline. The only local improvement found is
the optional `must` form for lifecycle flag writes (one fewer point read per existing-alias
update). Leave the reference, README, protocol, private tests, and skill unchanged; this is a
doc-only review.
