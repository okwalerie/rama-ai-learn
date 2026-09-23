# Full-Spec Review

Verdict: pass
Summary: Fresh adversarial pass over the whole public spec (README.md, the
`MetricsPipeline` protocol docstrings, the harness `Synchronizable` contract)
against the entire reference module (`test-resources/hld_metrics_pipeline/module.clj`),
the entire private suite (`test-private/hld_metrics_pipeline/*`), and the
development artifacts (IMPLICIT_SPEC, DECOMPOSITION, PLAN, PLAN_VALIDATION,
TEST_VALIDATION, IMPLEMENTATION_VALIDATION). Every operation, every admission /
retention / rollup rule, the ordering and synchronization requirements, the
durability requirement, the 2- and 4-task requirement, and the efficiency contract
were traced clause by clause to module lines and to the test that exercises them.
One concrete defect was found and fixed: the client-side synchronization registry
was a process-wide `defonce` map keyed by IPC instance, which strongly retained
every closed IPC for the JVM's lifetime. It is now created per `create-module`
result and captured by `:wrap-client`. No spec-visible behavior changed. The full
private harness suite was rerun after the fix: `Ran 4 tests containing 306
assertions. 0 failures, 0 errors.` A second hunt after the fix found nothing new.
The mutation negative control recorded in TEST_VALIDATION.md (10 failures when raw
expiry is off by one on both the physical cutoff and the query clamp) was not
rerun; the fix does not touch expiry, admission, or query code.

## Hunt: spec clause -> module -> test

Line numbers refer to `test-resources/hld_metrics_pipeline/module.clj` after the fix.
F = `functional_test_support.clj`, E = `efficiency_test_support.clj`.

| Spec clause (verbatim) | Module | Test |
|---|---|---|
| "Two series are the same iff all three parts are equal. `{"host" "a" "zone" "b"}` and `{"zone" "b" "host" "a"}` are the same labels." | `series-key` line 23 canonicalizes labels to a sorted map; depot `(hash-by :series)` line 101; queries `(|hash *series)` lines 186, 199; `foreign-select-one` keyed by the same value | F "label insertion order and map type do not change series identity" (array-map, sorted-map, hash-map, 12-key reverse-sorted map); F "tenant, metric, and empty-vs-non-empty labels isolate series" |
| "A call with a value less than or equal to the current clock is a no-op." | `(<<if (> *clock *cur)` line 131; nothing written otherwise | F worked example step 15 (`5000` after `7920`); F "fresh series at clock 0" (`advance 0`); F "same-series writes" (stale `700` after `800`) |
| Admission table rows 1-4 "Checks run in this order and the first that fires decides the outcome" | `<<cond` lines 159-171: future line 160, expired line 163, duplicate point lookup lines 167-168 only after rules 1-2, accepted otherwise | F "admission boundaries at clock C" (699/700/701/1000/1001; expired re-offer of accepted 701 counts as expired); F worked example steps 2-7; F "conflicting duplicate" |
| "Rejection never reserves a timestamp" | rejected branches write only the counter (line 181) | F "rejection never reserves a timestamp; retry after clock catches up"; F "same-series writes" O2 |
| "The first accepted sample for a timestamp is final." | duplicate branch performs no raw or bucket write | F "conflicting duplicate keeps the first accepted value everywhere" (value 7 vs -100) |
| "An accepted sample with timestamp `ts` is retained while `ts + 300 > clock`. Once `ts + 300 <= clock` it is gone from `query-raw`." | physical delete of keys `< clock - 299` line 137; query clamp `raw-window` line 50 (`lo = max(start, clock-299)`) | F worked example step 11 (1001 drops 701); F "one huge advance" (`BIG-299` kept at `BIG`, gone at `BIG+60`); F "second-wrapper write barrier" (first row 702 after advance 1001) |
| "Every accepted sample is folded into two buckets ... A bucket carries `:count`, `:sum`, `:min`, `:max`" | `bucket-start` line 43; 60-fold `+compound` line 176; 3600-fold lines 177-180; `fold-value-into-bucket` line 78 (first sample sets min=max=value) | F "bucket membership at width boundaries" (3599/3600/3659/3660); F "negative, zero, and > 32-bit values" |
| "A bucket is **complete** when `start + width <= clock`." / "retained while `start + width + 7200 > clock`." | `rollup-window` lines 59-60; physical 60-bucket delete line 145; 3600 prune line 153 | F worked example steps 9, 10, 12, 13, 14 and the 10799/10800 hourly boundary; F "fresh series at clock 0" (59 vs 60) |
| "Raw expiry never subtracts from a bucket." | advance path writes only `:clock` and deletes entries; never touches aggregates | F worked example steps 11, 13; F "tenant ... isolate series" |
| "A late-but-admissible sample may land in a bucket that is already complete. Its aggregates change accordingly" | fold lines 176-180 run regardless of completeness | F "late samples land in an already-complete bucket and change it" (count 1 -> 3, min -3, max 5) |
| "`query-raw` returns retained samples with `start <= timestamp < end`" ... "Both queries return ascending order." | lines 184-195, `sorted-map-range` is `[lo, hi)`, submap iteration ascending | F "rejection never reserves" (`500 500`, `501 600`); F "bucket membership" (`3600 3660`, `3701 BIG`) |
| "`query-rollup` returns complete, retained, non-empty buckets whose start lies in the half-open range `[start, end)`" | lines 197-214; non-empty automatic (entries exist only via folds); 3600 branch filters `lo <= s < hi` then sorts | F "bucket membership" (`3600 3660`, `3720 3780`, `3600 3600`); F "one huge advance" (`999999999720 BIG` empty slots) |
| "`get-series-info` returns the current clock and cumulative admission counters. Counters never decrease; expiry does not touch them." / "For a series that has never been written, all values are 0." | client `reduce-kv` over `submap INFO-KEYS` onto `ZERO-INFO` lines 240-247 | F "unknown series"; F worked example 10800 ("expiry never touches the counters"); F "one huge advance"; F "admission boundaries" (counters sum to applied calls) |
| "Writes addressed to the same series must take effect in the order the client invoked them" | one depot for both record kinds line 101; no partitioner after `%microbatch` line 126-127 | F "same-series writes take effect in client order, even inside one sync phase" |
| "All authoritative business state must be durable Rama state ... transient synchronization counters are allowed." | all state in `$$series` lines 104-123; only client memory is `append-counts` (sync only) | F "durable state survives a simulated worker restart (module update)" (`rtest/update-module!` then reads and further writes) |
| "The module runs with both 2 and 4 tasks in private validation." | hash placement only; no `|global`, `|all`, task arithmetic | both suites run explicitly with `{:tasks 2}` and `{:tasks 4}` |
| "Multiple clients wrapping the same deployed module must observe the same business state after the writing client synchronizes." | shared registry keyed by IPC lines 225-256 | every F block alternates writers `ca`/`cb`; F "second-wrapper write barrier" (wrapper created after 301 processed records waits for its own 201) |
| Efficiency: "`query-raw` and `query-rollup` must do work proportional to the queried series' data inside the requested range" | clock read then one clamped range read lines 187-192, 200-213 | E growth comparisons (raw, rollup 60, rollup 3600, info) after adding 106 out-of-range samples, 100 out-of-range buckets, 23 other series; E absolute `< 50` rollup bound |
| Efficiency: "`ingest-sample!` must do bounded work per sample" | point reads/writes only lines 158-181 | E ingest growth comparison |
| Efficiency: "`advance-clock!` may do work proportional to the data it expires on that series. It must not touch other series." | range-to scans + point deletes lines 135-155, all under `(keypath *series ...)` | E no-expiry advance growth; E 10^12 jump bound; E and F "other series untouched" |

Boundary / malformed inputs: the spec's only invalid inputs are future, expired,
duplicate samples and stale clock advances; every member of that class is
exercised at both sides of its boundary (see rows above). `start == end`,
ranges entirely in the future, ranges entirely below retention, unknown
series, `{}` labels, and 10^12 clocks/timestamps/bounds are all covered.

Test-expectation independence: every F expectation is a literal derived by hand
from the README rules; E expectations come from `expected-raw` /
`expected-rollup`, which implement the README predicates directly on the input
sample list. Tests reference only the public protocol namespace, the harness,
and `com.rpl.rama.test`; no module name, PState name, or topology name.

Efficiency-test scope statement: the RocksDB event hook counts reads, iterator
seeks, and iterator steps and carries no payload sizes. A design that stores a
whole series as one non-subindexed blob and filters in memory shows a constant
read count and is not detected. The suite therefore demonstrates absence of
scan growth across other series, other tenants, and out-of-range data on the
same series; it is not an exhaustive efficiency proof and is not claimed as one.

## Items found and fixed
| Location | Why it violated the spec | Fix applied | Evidence it holds |
|---|---|---|---|
| `module.clj` client section, formerly `(defonce append-counts (atom {}))` keyed by IPC instance | README: "transient synchronization counters are allowed". The counter was transient in role but not in lifetime: a process-wide strong-reference map retained every closed `InProcessCluster` (and its object graph) for the JVM's lifetime, one per launch. Not a spec-visible behavior defect; a resource defect in the reference package. | Registry moved into `create-module` (line 263, `(let [append-counts (atom {})] ...)`) and captured by the returned `:wrap-client` (line 265); `make-client` takes it as a parameter (line 225). Still keyed by IPC. PLAN.md and IMPLEMENTATION_VALIDATION.md updated to describe the new location. | `clojure -X:test-private-harness` after the change: `Ran 4 tests containing 306 assertions. 0 failures, 0 errors.` The F "second-wrapper write barrier" block (three wrappers from one `wrap-client`, second wrapper waits for its own writes after 301 already-processed records) passed at 2 and 4 tasks, which is the scenario the shared registry exists for. |

Decision: registry keyed by IPC per `create-module` result, rather than a single
plain per-`create-module` counter.
Basis: the harness (`with-module`) and both private drivers call `create-module`
once per launch and build every wrapper from that result, so a plain shared
counter would be equivalent for all current callers. A plain counter, however,
would hang `wait-for-processing!` if one `create-module` result were ever used
to wrap two sequential IPCs (the count carried over from the first cluster could
never be reached by the second). Keying by IPC costs the same two existing
expressions (`update ipc (fnil inc 0)` / `(get @m ipc 0)`), removes the
process-lifetime retention, and keeps that robustness.
Outcome: applied; suite green.

## Second hunt after the fix
Re-traced the synchronization rows above and the restart scenario against the
new closure: a wrapper created after writes were made reads durable state with no
local bookkeeping (F "fresh wrapper sees state written before it existed"), a
wrapper with no appends waits for count 0 and returns immediately, and
`rtest/update-module!` does not reset the module-cumulative processed count (the
restart block's post-update writes and sync passed). No new items.

## Oracle follow-up and coordinator repair (2026-09-23)

The user-requested independent Oracle code/test review found three gaps in the
earlier pass: unaligned large rollup query endpoints in the private tests,
unpublished numeric limits and overflow-prone timestamp arithmetic, and a bounded
`get-series-info` efficiency assertion not explicitly published in the README.

Decision: keep a signed-64-bit input domain with fitting counters and sums,
publish that domain and bounded info reads, and support time comparisons through
`Long/MAX_VALUE`. The raw query no longer computes `clock + 1`; admitted samples
already cannot exceed the clock. Admission compares timestamp with `clock - 300`
rather than adding retention to timestamp. The large query sentinel is now
divisible by both rollup widths, independently of the tested logical clock;
both private rollup helpers assert endpoint alignment.

Verification: coordinator directly ran `clojure -X:test-private-harness` in this
package after these repairs. Result: **4 tests, 312 assertions, 0 failures,
0 errors**. This supersedes the earlier 306-assertion receipt. The added cases
exercise an empty query at maximum clock and admitted/rejected timestamps on
both sides of its exact retention boundary. All runs deploy both 2 and 4 tasks.
The previous expiry mutation's 10 failures remain evidence for that unchanged
boundary rule; the mutant was restored before the passing runs.

Collaboration: Claude CLI model `claude-fable-5-1` authored the public draft and
ran fresh phase-0, decompose, plan, plan-validation, build and full-spec-review
sessions. Oracle supplied two public-contract reviews and this independent
implementation review. No evaluation solve was run. No model substitution,
quota exhaustion or reasoning-extraction safeguard occurred in these sessions.
