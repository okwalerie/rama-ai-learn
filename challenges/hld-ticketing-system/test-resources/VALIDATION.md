# Ticketing reference validation

## Successor coordinator acceptance — September 23, 2026

After the source coordinator's inconclusive memory-pressure control, the clean
successor executed an output-preserving full compensation-history scan: replace
the bounded range select with `foreign-select` of all compensation entries,
then filter sequence > cursor and take the requested limit. The selected
`bounded-own-history-work` test compiled and ran **1 test/56 assertions, 2
failures/0 errors, exit 1**. Both 2- and 4-task configurations returned correct
pages but grew from 258→1,294 reads for 256→1,280 history, failing only the two
page-read growth assertions. Full output: `SUCCESSOR_SCAN_CONTROL.log`.

The reference was restored byte-for-byte to SHA256
`44ec61428e8193aad61eddf57a2b8c0d13407de340a7be8009970f2b4ace5760` before running
the full command below: **9 tests/214 assertions, zero failures/errors, exit 0**,
logged in `SUCCESSOR_HARNESS.log`. Restored page reads stayed 3→3; all six
measured operations retained equal read/write counts at both task counts.
The parent-added paired history test replaces unpublished absolute caps.
The earlier parent reference-only ordering diagnostic (1/16 green) is retained
in `PARENT_DIAGNOSTIC.log`, not represented as a successor rerun. Original
deadline/compensation mutant evidence remains author-executed. Operation counts
do not measure opaque bytes or prove production crash behavior.

## Executed

From `challenges/hld-ticketing-system`:

```sh
clojure -J-Xmx1600m -X:test-private-harness
```

Final post-restoration solver-facing foreground run: exit 0, 1m54.920s,
9 tests, 176 assertions, 0
failures, 0 errors. Complete stdout/stderr is in `HARNESS_RUN.log`. Each test
deploys both 2 and 4 tasks; every write-to-read transition crosses a
`Synchronizable` barrier, including reads through a second wrapper. The
separate `:test-private` alias does not include `test-resources`: a
`requiring-resolve` probe returned `:solver-module-absent` when no solver was
installed. The harness resolves `hld-ticketing-system.module/create-module`
at runtime, so it can exercise either path without importing the reference.

The log contains transient IPC startup/update messages from Rama's worker
leader resolution and Kafka index recovery. They were not suppressed; the
test runner finished with no assertion failures or errors. No process crash
or production replica failure was injected.

Focused negative controls, each compiled and **failed assertions** before
restoration:

1. Changed `seat-view`'s active comparison from `<` to `<=`. The
   `release-and-boundaries` test failed twice at clock exactly equal to the
   new hold's deadline: expected available, observed held.
2. Compensated all `:hold-confirmed` rejections, including same-ref retries.
   The `ordered-holds-and-expiry-fencing` test failed for the unexpected
   compensation on `same`, shifted sequence on `different`, and four records
   rather than three. Both controls were rerun at 200-record batching with
   the plain ETL read: 2 and 8 failing assertions respectively. Full
   `MUTANT_DEADLINE.log` and `MUTANT_COMPENSATION.log` are retained. Both
   source changes were restored; the full green run above followed them.

## Implementation review

The source stores growing seats, holds, compensations and request outcomes in
subindexed per-event maps. It reads requested seat keys or one hold/request
key, writes only affected records, and starts compensation pages at the
requested sequence with a bounded range. The read/write event hook checks
that instrumentation fires, rejects broad seat/history scans, and bounds
advance-clock writes. This hook does **not** observe opaque-value bytes; the
layout inspection is necessary to rule out whole-event deserialization.

The earlier singleton-batch diagnosis was **wrong**. At 200 records per
partition, pre-aggregation assigned sorted positions `[1 create]`, then
`[2 add] [3 h1] [4 h2] [5 tick] [6 h3] [7 late]` in the failed unpaused
case. The `h1` decision nevertheless saw clock 0 and `seats {}` immediately
after `add` wrote seats. It rejected `:no-such-seat`; `h2` then saw available
`b` and was accepted. With the ETL seat `submap` changed only from
`{:allow-yield? true}` to a plain local read, the identical sorted batch
made `h1` see available `a,b`, `h2` see `b` held by `h1`, and `h3` see
expired `h1` seats at clock 7; the test passed at both 2 and 4 tasks.
The group/sort stamp was **not** the observed defect.

The controlled fresh-IPC A/B paused the topology after committed
`create`/`add`, queued `h1(a,b,7)` and `h2(c,b,11)`, resumed and barred,
then paused to queue `clock=7` and `h3(a,b,12)`. It also paused before
`create` and queued all six commands in one batch. At 2 and 4 tasks both
yielding and plain ETL reads passed those paused cases, with sorted stamps
`[1 create]...[6 h3]`. That result alone would not identify a cause;
the additional original unpaused sequence exposed the stale yielding read
and the simultaneous plain comparison resolved it. Full captured traces:
`AB_PAUSED_YIELD.log`, `AB_UNPAUSED_YIELD.log`, `AB_PLAIN.log`. Those
temporary tracing statements were removed from the reference. Loop yields
and the yielding **read-only query** remain unchanged.

The pause/resume experiment is retained as a **reference-only** diagnostic
in `hld_ticketing_system/ordering_diagnostic_test.clj`, outside the
solver-facing `test-private` directory because it necessarily names the
reference's `core` topology. Re-run from this challenge directory with:

```sh
clojure -J-Xmx1600m -M:test-private-harness -e "(require 'hld-ticketing-system.ordering-diagnostic-test) (clojure.test/run-tests 'hld-ticketing-system.ordering-diagnostic-test)"
```

After restoration this produced 1 test, 16 assertions, 0 failures/errors
at 2 and 4 tasks (`DIAGNOSTIC_RUN.log`). The solver-facing harness does not
assert any topology name, phase count, or pause API.

The resulting repair restores the plan's 200-record cap and uses a plain,
bounded ETL read of at most 1000 seat keys. The ordered loop yields between
commands and each add-seat write; thus only that one bounded read runs
synchronously. This is an observed same-microbatch visibility issue with a
yielding ETL read, not evidence of a general failure in Rama's stamp,
batch order, or other packages' nonyielding decision reads. Official
yielding-select documentation describes identical results; the trace does
not establish which internal snapshot/visibility mechanism produces the
observed difference. No production live interleaving or latency benchmark
was run, and no solving evaluation was run.

The prior focused Oracle review correctly challenged attribution from
outcomes alone; the A/B traces supersede that diagnosis. Its concrete
test-gap findings drove event-isolation, freed-blocker replay, stale-release
fencing, pagination, and update-ledger assertions. The tests inspect settled
IPC results, not live-query interleavings or production retries. Exactly-once
recovery and snapshot behavior rely on Rama's runtime contract rather than
an injected-failure proof.
