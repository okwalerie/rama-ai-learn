# Hotel reference validation

Run from `challenges/hld-hotel-reservation`:

```sh
clojure -J-Xmx1600m -X:test-private-harness
clojure -J-Xmx1600m -M:test-private-harness -e "(require 'hld-hotel-reservation.reference-test) (clojure.test/run-tests 'hld-hotel-reservation.reference-test)"
```

Final full harness: **5 tests, 122 assertions, 0 failures, 0 errors**, exit 0,
78.05 seconds. Reference-only append failure injection: **1 test, 6
assertions, 0 failures, 0 errors**, exit 0, 89.03 seconds (including JVM
shutdown). Complete console logs are in `validation/`. Both suites deploy
explicitly with **2 and 4 tasks**; the full harness also updates the module
and reads from a wrapper made by a fresh factory.

The `:test-private` alias contains `test-private` and the solver's
`implementations/hld-hotel-reservation/src`, **not** `test-resources`;
`requiring-resolve` therefore uses the solver on that path. The
`:test-private-harness` alias adds the reference directory. The
reference-only failure injection lives under `test-resources` and is not
discovered by the solver suite.

Two focused mutation controls were run against the private contract tests,
then restored before the final green run:

| Mutant | Expected distinction | Observed |
|---|---|---|
| `available <= quantity` instead of `<` | Exactly-full bookings must succeed | 48 assertion failures, 0 errors at 2/4 tasks; `first` rejected, no booking, broken journal and availability. See `validation/capacity-mutant.log`. |
| Cancellation adds `2 × quantity` rather than `quantity` | Restoration must not exceed capacity | 4 assertion failures, 0 errors at 2/4 tasks; `[3 3 3 0]` vs `[1 1 1 0]`, and 199 vs 99. See `validation/cancel-mutant.log`. |

## Independent validation of expanded private harness

After replacing the 160-record work test, the final restored-reference
`-X:test-private-harness` run passed **5 tests, 202 assertions, 0 failures,
0 errors**, exit 0, 104.06 seconds (`validation/independent-harness.log`).
The separate reference-only command above passed **2 tests, 20 assertions,
0 failures, 0 errors**, exit 0, 109.69 seconds
(`validation/independent-reference.log`). Both use 2- and 4-task IPCs.
The latter retains the pre-append failure injection and adds a paused queue
of 1004 same-client commands straddling the reference's 1000-record
microbatch limit; it checks rate, reserve/cancel order, availability, and
contiguous journal seqs. It is reference-only because pausing requires a
topology name, which the solver is free to choose. This observed ordering
does not prove every yielding interleaving, worker crash, or stamp/read
visibility scenario.

The private work test compares 240 versus 1200 configured nights and
bookings in one property, 240 versus 1201 event entries, corresponding
request outcomes, plus 20 other properties. At each scale it checks a
fixed one-night stay and point records, a first and a deep two-event page,
and a fresh one-night reservation. Across all tasks, for every sampled
operation and each observed RocksDB point read, iterator entry, and
write-batch count, the integrated test permits twice low-scale work + 24.
Parent removed the worker revision's unpublished absolute caps of 60/120;
a bounded design with a larger constant must not fail solely for that constant.
The worker logs above describe the earlier 202-assertion revision. The integrated
suite has 160 assertions. This finite growth heuristic is not a latency limit,
exact reference count, or proof of asymptotic complexity.
Hooks do **not** measure deserialized bytes in one opaque value; the
reference's nested nights, bookings, requests, and events are subindexed
in `module.clj`, but the private test cannot prove an arbitrary solver's
opaque-value footprint. It requires no private topology/PState names.

A behavior-preserving scan mutant replaced cursor-bounded event selection
with selection of all events starting at seq 1 followed by filtering and
take. The focused private work test ran (not a compile failure) and failed
8 assertions, 0 errors, at 2/4 tasks: first/deep pages iterated 1209 entries
instead of staying under 60 and grew from 240 by more than 24. This is worker
evidence for the earlier revision; the parent did not rerun the mutant.
The observed 1209 also exceeds the revised growth bound, 2 × 240 + 24. See
`validation/independent-scan-mutant.log`. The reference was restored
byte-identical (SHA-256
`77af2ec6be618a2c1a1a72aaafd7b4f33be8a757aee7f40d09b3e70039fc9fef`)
before the final green run. The `-M` focused test command exits 0 even when
`clojure.test/run-tests` reports failures; its footer is the verdict.

IPC module update and pre-append failure injection are exercised. A real
worker crash in the middle of a microbatch and an accepted append whose
acknowledgment was lost were not fault-injected; their behavior follows
the Rama microbatch transaction and depot-offset model, rather than an
executed failure-mode test. IPC shutdown sometimes emits transient
`LeaderNotFoundException` or Kafka index-rebuild log lines; test footers
and process exit status, not those incidental log lines, determine the
recorded result.
