# Private harness verification notes

## Successor coordinator acceptance — September 23, 2026

The clean-orb rerun of the command below completed with exit 0: **12 tests,
188 assertions, zero failures/errors**, including the independent suite at
both 2 and 4 tasks. Full output is `SUCCESSOR_HARNESS.log`. This replaces the
inconclusive memory-pressure run retained in `PARENT_INTERRUPTED.log`; it does
not relabel that interrupted run as a pass. At 240→1,040 same-recipient history,
point reads stayed 1, recent/dead-letter reads stayed 102 plus one iterator,
and submit/attempt/receipt reads and writes stayed equal. Independent unchanged-
module update tests exercise retained state, not production process crashes.
Worker negative-control evidence remains in `test-private`; it was inspected,
not rerun by the successor. IPC recovery/leader messages remain in the log.

Run from `challenges/hld-notification-system`:

```sh
clojure -J-Xmx1600m -X:test-private-harness
```

The `:test-private-harness` alias includes `test-resources`, so
`requiring-resolve` loads the reference module. The `:test-private` alias
does **not** include `test-resources`; it resolves the solver module from
`implementations/hld-notification-system/src`. Neither alias substitutes
the reference on the solver path. The full last-run output and elapsed time
are retained in `VERIFICATION.log`. The IPC teardown sometimes emits a
`LeaderNotFoundException` worker log while closing; the test runner still
reports zero failures and errors and exits successfully.

Each scenario launches its own IPC with exactly 2 or 4 tasks, creates two
independent wrappers from one module factory, and uses a wait through the
opposite wrapper before asserting reads. Cross-owner submissions are
checked against the possible serial histories, rather than asserting a
specific task winner. For dead-letter acceptance order across distinct
submission owners, the page test explicitly waits after each report.

## Negative controls (reference restored after each)

| Temporary mutation | Selected test | Observed detection |
|---|---|---|
| Ignore retry due tick (`>= now 0`) | `two-tasks` | 2 assertion failures; early attempt 2 incorrectly advanced attempts/due from 1/15 to 2/34. |
| Always overwrite a persisted submission | `two-tasks` | 7 assertion failures; loser Bob replaced Alice and entered Bob's recent list. |
| Invalidate any existing device without comparing generation | `lifecycle-two-tasks` | 3 assertion failures; refreshed device became invalid and disappeared from later fan-out. |
| Choose greatest rather than least same-batch contender rank | `collisions-and-pages-two-tasks` | 1 assertion failure; second same-owner payload won. |

All controls compiled and ran; none was merely a compile-failure signal.

## Bounds and limits of evidence

The 102-submission and 101-dead-letter tests verify exact page membership,
order and the 100-entry boundary; they do **not** measure read operations.
Source inspection confirms `:recent` and `:dead-letters` are subindexed
sorted maps traversed with `(sorted-map-range-to-end 100)` before
`MAP-VALS`, while fixed point reads include at most eight inline devices
or deliveries. Submits inspect only an inline profile and fan out at most
eight; attempts and receipts inspect one bounded submission record. No
opaque-value byte instrumentation exists in this harness. IPC passes do not
simulate worker crashes, module upgrades, distributed client processes, or
production throughput; the microbatch atomicity/replay argument rests on
Rama semantics and code inspection, not an executed failure-injection test.

The module's shared wrapper counter is synchronization bookkeeping, not
business state. A lock spans append/count and wait to prevent concurrent
later appends from satisfying a cumulative processed-count barrier while
older records remain pending. This serializes wrappers created by the same
factory during a barrier; it does not establish cross-process coordination.
