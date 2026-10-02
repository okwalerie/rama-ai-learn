# Design judges

The quality axis of [SCORING_RUBRIC.md](../SCORING_RUBRIC.md) is a vector of
the binary judges below. Each judge detects exactly one Rama design failure
mode. Judges never change the headline outcome or the score (see
[outcome-taxonomy.md](outcome-taxonomy.md)).

## Rules for every judge

- **One failure mode per judge.** A judge that notices a different problem
  does not report it. A different problem needs its own judge.
- **One output per judge:** `Pass`, `Fail`, `N/A` (the precondition does not
  hold), or `Unjudged` (not calibrated, not run, or the detector could not
  decide).
- **No aggregation.** Do NOT average, sum, weight, or combine judge outputs.
  Report the vector as it is.
- **Detection order: code, then event hook, then LLM.** Use a static check of
  the implementation source when it decides the question. Otherwise use an
  event hook in an instrumented test run. Use an LLM only when neither can
  observe the failure mode, and say why. None of the judges below needs an
  LLM; when a detector cannot decide, the output is `Unjudged`, not an LLM
  guess.
- **Event hooks use `com.rpl.rama.test/with-event-hook`**, the hook the
  private graders already use. The judges cite only events those graders
  observe: `:rocks-read`, `:rocks-iterator`, `:rocks-iterator-read`,
  `:rocks-commit` (`:write-batch-count`), `:depot-read`, `:partitioner`, and
  `:topology-event` (`:type` is `:stream`, `:microbatch` or `:query`). Graders
  group rocks and depot events by the event's `:task-id`.
- **Measure growth, not absolute counts.** Run the same request at two or more
  data sizes and compare. Absolute counts depend on task count and schema.
  Growth thresholds are set during calibration, not guessed here.
- **Preconditions are declared by a human per challenge,** never inferred by
  an LLM. Store the declarations with the private suite (`test-private/`), which
  the runner protects. `docs/` is not protected, so this file stays generic.

## Summary

| Id | Judge | Failure mode | Detection |
|---|---|---|---|
| J1 | `per-recipient-durable-writes` | One event writes durable state once per recipient | Event hook; code as a hint |
| J2 | `unsubindexed-growing-collection` | A growing nested collection is not subindexed | Code; event hook confirms growth |
| J3 | `unbounded-read-scan` | A bounded read touches entries in proportion to total data | Event hook |
| J4 | `unnecessary-repartition` | A partitioner hop is not needed by the work after it | Code; event hook confirms |
| J5 | `stream-breaks-atomic-visibility` | Updates that must appear together are written by a stream topology across tasks | Code and event hook |
| J6 | `non-idempotent-retry` | Replaying one input applies its effect twice | Code and event hook |
| J7 | `global-or-hot-partition` | Per-entity load funnels onto one task | Code and event hook |
| J8 | `client-side-distributed-query` | One client call makes data-dependent roundtrips and merges on the client | Code and event hook |

Examples use placeholder names (`$$prices`, `*votes`, `$$stock`). They show
the pattern only and are not complete modules.

## J1 `per-recipient-durable-writes`

**Failure mode.** One source event makes one durable write per recipient
(follower, member, subscriber), so write cost grows with the audience.

**Applies when.** The contract delivers one event to a variable number of
recipients, and the challenge declaration does not require per-recipient
durable state on each event. Otherwise `N/A`.

**Pass.** Durable writes for one event stay bounded as the recipient count
grows.

**Fail.** Durable writes for one event grow with the recipient count.

Fail example: a product's new price is copied into every watcher's entry.

```clojure
(local-select> [(keypath *product-id) ALL] $$product-watchers :> *user-id)
(|hash *user-id)
(local-transform> [(keypath *user-id *product-id) (termval *price)] $$watched-prices)
```

Pass example: the price is stored once per product; a watch list stores
product ids, and reads look up the current prices.

```clojure
(local-transform> [(keypath *product-id) (termval *price)] $$prices)
```

**Detection: event hook.** Process one event with R recipients for two or
more values of R (for example 1 and 64). Sum `:write-batch-count` over the
`:rocks-commit` events recorded while that event is processed. `Fail` when the
sum grows with R beyond the calibrated bound. Code hint (never decides alone):
`ops/explode` or `ALL` over a membership collection followed by a PState write.

## J2 `unsubindexed-growing-collection`

**Failure mode.** A nested collection that grows with usage (history, log,
membership, per-entity index) is declared without `{:subindex? true}`, so
reading or writing one element loads and rewrites the whole collection.

**Applies when.** The module declares a PState with a nested map, set, list
or vector. Otherwise `N/A`.

**Pass.** Every nested collection that grows with events or entities is
subindexed. An unsubindexed nested collection is bounded: a closed key set, or
a maximum size stated in the contract.

**Fail.** At least one nested collection grows with events or entities and is
not subindexed.

Fail example: one entry per commit, stored as a plain nested map.

```clojure
(declare-pstate s $$repo-commits {String (map-schema Long Object)})
```

Pass examples: the same log subindexed, and a bounded record.

```clojure
(declare-pstate s $$repo-commits {String (map-schema Long Object {:subindex? true})})
(declare-pstate s $$settings {String (fixed-keys-schema {:theme String :timezone String})})
```

**Detection: code, confirmed by event hook.** Code: list every nested
collection schema in `declare-pstate` without `:subindex? true`. For each one,
append K events for one entity at two values of K and read the collection
size through the foreign PState (for example
`(foreign-select-one [(keypath k) (view count)] pstate)`).
`Fail` when a listed collection grows with K. `Pass` when no collection is
listed, or every listed one stays bounded.

## J3 `unbounded-read-scan`

**Failure mode.** A read with a bounded result (key lookup, top-N, page,
range) touches a number of stored entries that grows with the total data
rather than with the result size.

**Applies when.** The contract has a read with a bounded result. Otherwise
`N/A`.

**Pass.** For a fixed request, entries read stay flat as unrelated data grows.

**Fail.** Entries read grow with unrelated data.

Fail example: read a warehouse's whole shipment log, then sort and keep 10.

```clojure
(local-select> [(keypath *warehouse-id) MAP-VALS] $$shipments {:allow-yield? true} :> *shipment)
```

Pass example: read at most 10 entries from a subindexed sorted map.

```clojure
(local-select> [(keypath *warehouse-id) (sorted-map-range-from *cursor {:max-amt 10})] $$shipments :> *page)
```

**Detection: event hook.** Count `:rocks-read` plus `:rocks-iterator-read`
during one read. Grow data outside the requested result (more entries for the
same key outside the range, more keys on the same partition), then repeat
the same read. `Fail` when the count grows beyond the calibrated bound.

## J4 `unnecessary-repartition`

**Failure mode.** A partitioner hop moves processing to a task that does no
partitioned work there that the previous task could not do.

**Applies when.** A topology contains a partitioner other than the closing
`|origin` of a query topology. Otherwise `N/A`.

**Pass.** Each partitioner is followed, before the next partitioner or the end
of the topology, by a PState read or write or an aggregation that needs the
destination task.

**Fail.** A partitioner is followed only by another partitioner or by the end
of the topology, or it re-routes by the key the event is already partitioned
by.

Fail example: two hops before any state access; the first hop does nothing.

```clojure
(|hash *user-id)
(|hash *item-id)
(+compound $$item-counts {*item-id (aggs/+count)})
```

Pass example: each hop precedes the state on its destination task.

```clojure
(|hash *user-id)
(+compound $$user-counts {*user-id (aggs/+count)})
(|hash *item-id)
(+compound $$item-counts {*item-id (aggs/+count)})
```

**Detection: code, confirmed by event hook.** Code: walk each dataflow and list
the forms between consecutive partitioners. `Fail` when a segment holds no
PState access or aggregation, or when its partition key equals the one the
event is already on (the depot partitioner key or the previous hop's key).
Event hook: count `:partitioner` events for one source event and compare with
the code walk. A custom partitioner the walk cannot resolve gives `Unjudged`.

## J5 `stream-breaks-atomic-visibility`

**Failure mode.** Updates that the contract requires to become visible
together are written by a stream topology across more than one task, so a
reader can see some of them without the others.

**Applies when.** The challenge declaration names at least one group of updates
that must become visible together. Otherwise `N/A`.

**Pass.** Each declared group is written by a microbatch topology, which makes
the batch's writes visible together, or by one stream event on a single task,
where reads and writes are atomic.

**Fail.** A declared group is written by a stream topology and its writes
cross a partitioner.

Fail example: moving stock between two warehouses writes two partitions in a
stream topology; a read between the hops sees the stock in neither warehouse.

```clojure
(let [s (stream-topology topologies "stock-moves")]
  (<<sources s
    (source> *stock-moves :> {:keys [*from *to *qty]})
    (|hash *from)
    (- *qty :> *removed)
    (+compound $$stock {*from (aggs/+sum *removed)})
    (|hash *to)
    (+compound $$stock {*to (aggs/+sum *qty)})))
```

Pass example: the same two writes in a `microbatch-topology`, whose writes
become visible together when the batch commits.

**Detection: code and event hook.** Event hook: while the declared operation
is processed, record `:topology-event` `:type` and the `:partitioner` events
between the group's writes. Code: find the topology that writes the group's
PStates. `Fail` when the type is `:stream` and a partitioner separates the
group's writes. The reverse mismatch (the contract requires writes to be
visible when the append returns, and the module uses microbatch) is a
different failure mode and is not judged by J5.

## J6 `non-idempotent-retry`

**Failure mode.** Processing the same input twice (a topology retry after a
failure, or a client re-append after a timeout) applies its effect twice.

**Applies when.** The module makes a non-idempotent write: an increment, a sum
or count aggregation, an append to a list, a balance change. Otherwise `N/A`.

**Pass.** Each logical input takes effect once. The write happens in a
microbatch topology, which is exactly-once for its own processing, or it is
idempotent (keyed overwrite, set insert). When the contract allows client
retries, a request id is checked before the effect is applied.

**Fail.** Replaying one input changes state a second time.

Fail example: a stream topology counts votes; a retry counts the vote again.

```clojure
(let [s (stream-topology topologies "tallies")]
  (<<sources s
    (source> *votes :> {:keys [*option-id]})
    (+compound $$tallies {*option-id (aggs/+count)})))
```

Pass example: the same aggregation in a microbatch topology. When clients may
retry, the topology first checks `$$counted-votes` for the vote id and skips
a vote it has already counted.

**Detection: code and event hook.** Code: `Fail` when a non-idempotent write
(`aggs/+sum`, `aggs/+count`, `AFTER-ELEM`, `(term inc)` and similar) sits in
a stream topology with no dedup read before it. Event hook, when the contract
allows client retries: append the same record twice with the same request id,
wait for processing, and compare state with a single append. `Fail` when the
states differ.

## J7 `global-or-hot-partition`

**Failure mode.** State or processing for many independent entities is routed
to one task, through a `{:global? true}` PState, `|global`, or a partition key
with few distinct values, so that task limits throughput.

**Applies when.** The contract has many independent entities (users,
accounts, items, keys). Otherwise `N/A`.

**Pass.** Per-entity writes and state are partitioned by an entity-level key.
Global PStates and `|global` hold only singletons, or data already reduced by
a two-phase aggregation in a batch block.

**Fail.** Per-entity data lives in a global PState, per-entity events pass
through `|global` without prior reduction, or the partition key has far fewer
distinct values than there are entities.

Fail example:

```clojure
(declare-pstate s $$player-scores {String Long} {:global? true})
```

Pass example: the same PState without `:global?`, written after
`(|hash *player-id)`.

**Detection: code and event hook.** Code: list `{:global? true}` PStates keyed
by entity ids and `|global` outside batch-block aggregation. Event hook: for a
workload over many entities (spread across tasks with
`rtest/gen-hashing-index-keys`), count `:depot-read` events and sum
`:rocks-commit` `:write-batch-count`, both by `:task-id`. `Fail` when one
task's share exceeds the calibrated bound.

## J8 `client-side-distributed-query`

**Failure mode.** One client API call makes a number of roundtrips
(`foreign-select`, `foreign-select-one`, `foreign-invoke-query`) that grows
with the data, and merges the results on the client.

**Applies when.** The contract has a read that combines data from many keys
or partitions. Otherwise `N/A`.

**Pass.** Each client call makes a bounded number of roundtrips whatever the
data size, typically one `foreign-invoke-query` of a query topology that does
the fan-out and merge on the cluster.

**Fail.** Roundtrips for one client call grow with the number of keys,
partitions or results.

Fail example: one roundtrip per id, merged on the client.

```clojure
(mapv #(foreign-select-one (keypath %) items) ids)
```

Pass example: one roundtrip to a query topology that partitions by id, reads,
and returns the merged result with `|origin`.

```clojure
(foreign-invoke-query items-by-ids ids)
```

**Detection: code and event hook.** Code: `Fail` when a foreign read sits
inside iteration over a data-dependent collection (`map`, `mapv`, `for`,
`doseq`, `reduce`, `loop`) in client code. Hook: during one client call, count
calls to the foreign read functions with `with-redefs` wrappers, and count
`:topology-event` `:type :query` events. Repeat at two data sizes. `Fail` when
the count grows with data size.

## Calibration plan

**Status: no results.** No judge has been implemented or calibrated. Every
judge reports `Unjudged` until it meets the acceptance rule below. Do NOT
fill the results table from anything except a real labelled evaluation.

### Unit and sample

- A **trace** is one implementation snapshot: the module source, its run
  manifest entry (implementation SHA-256, repo and challenge SHAs), and any
  event-hook logs.
- Label about **100 traces per judge**. Aim for at least 30 human-confirmed
  `Fail` and 30 human-confirmed `Pass` traces per judge, so that TPR and TNR
  each have a usable denominator. Traces where the precondition does not
  hold are labelled `N/A` and counted separately.
- Sources: past solver implementations identified by their manifests;
  reference implementations (usually `Pass`, but labelled like any other
  trace); and mutants as `Fail` positives (below).

### Mutant positives from `wip/mutant-kill-rate`

Branch `origin/wip/mutant-kill-rate` (read at `78b293a`; mutants first
committed in `935c229`) stores mutants under
`challenges/<name>/test-private/mutants/<id>/`. Each has a `manifest.edn`
with `:targets-nfr`, `:wrong-design`, `:functional-preserved?` and an optional
`:split`. Its own doc marks the inventory **not executed**: no mutant has been
compiled or run, and its kill results are TBD.

A mutant is a `Fail` positive for judge J only when all of these hold:

1. A human maps its `:wrong-design` to J's failure mode and records the
   judge id in the manifest (for example `:judge "J7"`). Mutants are not
   auto-labelled for other judges.
2. It is a real mutation. The branch flags placeholders that are
   byte-identical to the reference as invalid; exclude them.
3. A real run shows it compiles and passes the functional tests, confirming
   `:functional-preserved? true`. Exclude mutants whose manifest or doc flags
   that results may change, until a run confirms them.

Mutants only add positives. They do not replace human-labelled natural
`Fail` traces: keep at least half of each judge's `Fail` set natural, so the
judge is not tuned to synthetic edits.

### Labelling protocol

- Two labellers label each trace independently. Neither sees the judge's
  output or the private verdict.
- A third person adjudicates disagreements. Report inter-rater agreement
  (Cohen's kappa) per judge.
- Record labeller ids, date, implementation SHA-256, repo SHA and challenge
  tree SHA with each label.
- Store labels and per-challenge precondition declarations under
  `test-private/` or outside the repository, never in `docs/`.

### Splits

- Split traces by challenge, so all traces of one challenge fall in one
  split. Hold out about 30%.
- A mutant keeps the `:split` in its manifest (`:dev` or `:held-out`); the
  branch assigns about 40% to held-out by hash when `:split` is absent.
- Tune detectors and thresholds on dev only. Evaluate held-out once per
  judge version. Any change to a judge after a held-out evaluation needs new
  held-out traces.

### Metrics and acceptance

- Positive class is `Fail`. On held-out:
  TPR = TP / (TP + FN), TNR = TN / (TN + FP).
- Report raw counts and Wilson 95% intervals. Exclude `N/A` traces from TPR
  and TNR, and report precondition agreement (judge `N/A` versus human `N/A`)
  separately.
- Maintainers set acceptance thresholds before the first held-out
  evaluation. Until a judge meets them, it reports `Unjudged`.

### Results template

| Judge | Version | Held-out Fail (n) | Held-out Pass (n) | TPR (95% CI) | TNR (95% CI) | Kappa | Status |
|---|---|---|---|---|---|---|---|
| J1 | TBD | TBD | TBD | TBD | TBD | TBD | Unjudged |
| J2 | TBD | TBD | TBD | TBD | TBD | TBD | Unjudged |
| J3 | TBD | TBD | TBD | TBD | TBD | TBD | Unjudged |
| J4 | TBD | TBD | TBD | TBD | TBD | TBD | Unjudged |
| J5 | TBD | TBD | TBD | TBD | TBD | TBD | Unjudged |
| J6 | TBD | TBD | TBD | TBD | TBD | TBD | Unjudged |
| J7 | TBD | TBD | TBD | TBD | TBD | TBD | Unjudged |
| J8 | TBD | TBD | TBD | TBD | TBD | TBD | Unjudged |
