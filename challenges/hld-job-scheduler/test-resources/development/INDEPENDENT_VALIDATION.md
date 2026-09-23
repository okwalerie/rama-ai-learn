# Independent validation of the transferred scheduler candidate

The archive `/tmp/hld-job-scheduler-candidate.tar.gz` matched SHA-256
`46cf51ae988854f431f7c97e837bc18e161a5dc5c6d88b00c1dfde35d88fa4f4`.
From the repository root, `sha256sum -c
.amp/hld-program/transfers/hld-job-scheduler.contract.sha256` reported OK for
README.md, deps.edn, and protocol.clj before and after this work. No existing
package file, reference module, or public contract was edited.

The independent private test adds a fixed two-node DAG and populations of 256
then 1,024 other executions **and** 256 then 1,024 denied decisions in its
execution. It measures the same API read/write paths after barriers, separately
at each size, including a historical point claim read, a fresh denial, a
conflicting replay, stale clock advance, and rejected completion. The event
hook sums RocksDB point reads, iterator creation/steps, and write-batch entry
counts; it does not assume a depot, PState, or topology name. For each path,
the later count must be at most `max(12, 8 + 3 × earlier count)`. This finite,
generous growth heuristic distinguishes scans at these two sizes but is **not
an asymptotic proof**, and it does not measure opaque serialized-value bytes,
RocksDB internal work, or production latency. The target's DAG, clock,
statuses, and outputs remain fixed. Population dimensions grow together, so
the test identifies history-coupled work without separately attributing it
to executions versus same-execution decisions. The reference's bounded
ExecutionState and subindexed claims were inspected; a future implementation
that hides a whole history in one opaque value could pass this hook.

Additional assertions distinguish unknown-execution advance/completion from
post-submission state, claim ID scope across two executions, and lease expiry
arithmetic at the largest **allowed** clock (`Long/MAX_VALUE - 10`). A clock
larger than that is explicitly outside the input contract. The existing
private harness covers expiry at equality and conflicting replay with a
changed body; its supplied `VERIFICATION-mutant-expiry.log` and
`VERIFICATION-mutant-replay.log` contain executable negative-control failures
from temporary reference mutations (12 and 22 failures respectively). This
validation did not alter or rerun those reference mutants.

From `challenges/hld-job-scheduler`:

```sh
clojure -J-Xmx1600m -X:test-private-harness
```

The full run in `/tmp/hld-independent-harness.log` took 120.96 s, exited 0,
and reported **3 tests, 132 assertions, 0 failures, 0 errors**. Both the
existing and new tests launched explicit 2- and 4-task clusters. New measured
counts were identical at both sizes for each task count: execution=1, node=1,
claim=2, claimable=1, denial=8, replay=2, clock=1, completion=1. The harness
alias includes `test-resources`; `-X:test-private` instead resolves a solver's
implementation path.

For an independent scan control, a temporary test-only delegating client
performed 256/1,024 extra `get-claim` point reads when reading `old-0`. The
same test then failed `:claim` at **516→2052** operations against the
1,556-operation high-stage bound, once at each task count: 2 failures,
0 errors, output `/tmp/hld-independent-scan-control.log`. The control file
was deleted and the reference never changed. The first attempted
`with-redefs` of the protocol var did not intercept compiled direct protocol
calls and was discarded; only the delegating-client control establishes scan
sensitivity. A green pass does not establish process-crash, external-effect,
or opaque-byte guarantees.
