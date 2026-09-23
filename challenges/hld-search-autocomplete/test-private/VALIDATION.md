# Private validation record

Run from `challenges/hld-search-autocomplete`:

```sh
clojure -J-Xmx1600m -X:test-private-harness
```

Restored reference: 2 tests, 314 assertions, 0 failures, 0 errors; 2 and 4
tasks explicitly launched, with two wrappers and cross-wrapper barriers.
Observed elapsed time: 45.38 seconds. The `:test-private` alias omits
`test-resources` and resolves the solver namespace from the implementation
classpath; `:test-private-harness` adds `test-resources` to run this reference.
Do not interpret a solver namespace-load failure as a passing private test.

Negative controls (temporary edits, **both restored**): changing the indexed
search increment from 10 to 9 compiled and produced 44 assertion failures,
0 errors (score/order checks). Admitting searches whenever a generation exists,
instead of only for the matching generation, compiled and produced 26 assertion
failures, 0 errors (including intermediate empty-generation checks). Both ran
the same 2/4-task private command and exited 1; the restored run above exited 0.

Work-bounds inspection: `suggest` reads generation and at most `k` entries of
one subindexed ordered prefix set; it never iterates candidates. `get-phrase`
and `get-generation` use fixed point reads. An event checks one owner generation,
one phrase record and one subindexed session membership, then updates at most
64 prefix ranks. A publication touches the new entries and broadcasts one
generation value per task; it does not visit prior trend data. Test data include
600 same-prefix candidates and a 64-character novel phrase. These original
tests do **not** measure work growth. `rtest/with-event-hook` can observe
RocksDB point reads, iterator creation/reads, and write batch counts (see
`INDEPENDENT_VALIDATION.md`); it does not expose opaque serialized-value bytes
or prove a production seek/latency budget. The original tests do not simulate
worker failures or module upgrades.
Superseded generation data remain durable and unreclaimed; lifetime disk usage
grows with the number of publications. No public documentation was changed.
