# Reference and harness validation

## Repaired-source successor acceptance (2026-09-23)

The clean successor ran `clojure -J-Xmx1600m -X:test-private-harness` alone:
**10 tests, 2,892 assertions, zero failures/errors, exit 0**. Complete output is
`SUCCESSOR_HARNESS.log`. This combines the independent private suite and parent
printable-ASCII boundary assertions against repaired source SHA256
`47eadfac94c8bf16d3780ea87d8d2beccd4f04ef20d2a4576ebea091cea9d5d9`.
The reference now has `+ordered-commands`, an outer sequential per-host command
loop and cooperative yields in command/enqueue/blocked-head loops. PLAN.md's
supersession note replaces its historical no-yield claims. The original query
regex accepts printable ASCII space; no invalid query-space rejection is tested.

For 220→1,000 retired/claim records and 520→1,300 queued URLs with ten fixed
blocked heads, aggregate claim work was 156→141 at 2 tasks and 141→134 at 4
tasks. Page work for limits 1/20/100 stayed [6,25,105] at both sizes/task counts.
These are observed operation events, not opaque bytes or production throughput.
IPC leader/cache/index recovery messages remain in the log without test errors.
The author's separate paused 1,000-blocked-head ordering experiment passed 2/4;
the successor did not rerun that reference-only experiment or directly count
yield suspensions. Neither experiment proves production crash/retry behavior.

## Private-harness follow-up (2026-09-23)

The transferred candidate archive SHA-256 was
`4b0f67b41bf6ec0a07fabd484411ac38eceaa1d995abf88d53d2eea2181338b8`;
the README, protocol, and deps all match the accompanying SHA-256 manifest.
The frozen candidate reference source SHA-256 is
`b1caa5ba2df726643f70d70623b4598a23cb1fe55e0ef9150e773d51effe48ac`.
Only the private test was changed in this follow-up; the parent is coordinating
reference-only repairs separately.

The old absolute claim/page event ceilings were replaced by paired measurements
at 2 and 4 tasks, comparing 220 → 1,000 retired URLs and recorded claim IDs,
520 → 1,300 queued URLs, with 10 blocked heads at claim time and the same page
limits (1, 20, 100) and output sizes. Captures include logical selects and
transforms plus RocksDB read, iterator, and iterator-next events. The permitted
large-minus-small difference is 100 aggregate events, not a topology-specific
operation budget or a time threshold. Setup and verification reads are excluded.
The original 1,000-blocked, Unicode rejection, expiry/replay, and pagination
assertions remain. A new unchanged-module update test uses a fresh wrapper and
checks durable claims, policy, deduplication, stale requeue, and fencing.

The full plain `clojure -J-Xmx1600m -X:test-private-harness` run exited 0 in
167.67 seconds: 10 tests, 2,890 assertions, 0 failures, 0 errors. Work totals:

| Tasks | Claim, small → large | Pages 1/20/100, small → large |
|---|---|---|
| 2 | 123 → 112 | [6, 25, 105] → [6, 25, 107] |
| 4 | 143 → 135 | [6, 25, 105] → [6, 25, 105] |

The solver alias `clojure -Spath -A:test-private` includes the implementations
path but not `test-resources`; it cannot silently resolve the reference. An
independent semantic check found `canonical-url "https://space.example/?a b"`
returns that same string. The initial defect report was withdrawn after checking
the frozen wording: only PATH specifies 0x21..0x7E; QUERY says printable ASCII,
which conventionally includes space. No query-space rejection is required by
the private suite, and the original query behavior is preserved. The parent
discarded an attempted rejection test as an invalid interpretation, not a
reference failure or mutation control. The reference also has no cooperative
yield in its 1,000-head skip path; the parent has assigned that source concern
to the separate reference owner. Existing author-run semantic mutants are
documented below; this follow-up did not mutate the concurrently owned source.

Event counts cannot measure opaque value bytes or prove production crash
recovery, same-host fairness under a loaded task, or worker restart. The IPC
unchanged-module update is a durability signal, not a crash simulation. A finite
1,000-blocked case and paired 1,300-queued population cannot establish cost at
the full 1,000,000-URL per-host workload. The page and claim comparisons bound
observed aggregate operations at these sizes, not exact I/O, latency, or storage.

## Original author validation

Run from `challenges/hld-web-crawler`:

```sh
clojure -J-Xmx1600m -X:test-private-harness
```

Final restored-reference run: exit 0 in 150 seconds; `Ran 9 tests containing
2866 assertions. 0 failures, 0 errors.` Logs also contained transient IPC
`LeaderNotFoundException` resolution messages during module teardown; they did
not produce test errors. The test command above regenerates the result.

The private tests resolve `hld-web-crawler.module/create-module` at runtime. The
`:test-private-harness` alias adds `test-resources` (reference), whereas
`:test-private` does not; it uses the implementation path from `deps.edn`.
Each scenario explicitly launches both 2 and 4 tasks and creates two wrappers
from one factory result. The test file includes no reference namespace require.

The module uses one host-partitioned depot and one microbatch topology. Per-host
PState state and claims are durable; the wrapper holds only pure URL parsing
and a shared count of successful appends for IPC barriers. Pending and history
are subindexed. Claims read sorted ranges of at most 64 URLs, retire disallowed
heads, and stop at the first allowed head without scanning the remaining tail.
The bound is fixed work plus O(blocked heads), not a latency threshold or a
required solver chunk size. Pagination bounds its range by the caller's limit.

The private hook captures logical selects/transforms and RocksDB point,
iterator, and iterator-next events. Captures surround only an operation and
its processing barrier, after setup and before verification reads. Broad
envelopes cover a 600-URL allowed tail, a 1,000-blocked prefix, deep cursors,
and pages of limits 1, 20, and 100 at both task counts. They neither require
this PState layout nor a topology type. The hook does **not** expose opaque
value sizes; these assertions bound observed operations, not bytes. IPC tests
cannot prove worker-restart or production latency guarantees.

Negative controls, restored afterward:

1. Changing completion's strict `C < expires-at` to `<=` compiled and caused
   the boundary test to fail on both task counts: URL was `:done` instead of
   `:leased`, then could not become `:failed` at C=69. This distinguishes a
   rejected exact-expiry completion from an accepted pre-expiry one.
2. Updating the clock on a replayed claim, without altering its recorded
   outcome, compiled and failed on both task counts: the subsequent grant
   expired at 1,000,029 rather than 140 and `last-claim-at` was 999,999
   rather than 110. This distinguishes immutable replay from a clock-only
   side effect.

Rama dataflow macros are not understood by the repository's unconfigured
`clj-kondo` invocation; loading the namespace and running the IPC harness are
the compilation checks. Oracle's focused static review caught supplementary
Unicode acceptance in the first regex; positive ASCII path/query classes and
protocol-level invalid-URL cases address it. The review was not an IPC run.
