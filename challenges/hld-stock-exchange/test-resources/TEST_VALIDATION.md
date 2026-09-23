# Test Validation

Verdict: pass for the implemented private harness; limitations below are not claims of executed production fault coverage.

- `matching-and-retries` distinguishes FIFO, maker pricing, partial priority retention, no-such-order before symbol creation, same-payload replay, conflicting body, and cross-type request-id conflict on both 2 and 4 tasks.
- `bid-priority-cancellation-and-ioc` distinguishes bid direction, wrong-owner rejection, filled-order rejection, IOC residual cancellation, zero-depth cleanup, and accepted cancellation.
- `validation-replay-and-update` checks synchronous structural rejection before request handling, self-trades, retained records/seqs after `update-module!`, and deep-page cursor boundaries.
- `bounded-work-and-deep-page` checks one symbol's 120-level history, 119 trades, deep 5-row pages, fixed-size query and command read work, and 80 orders at one level. Event hooks count `:rocks-read` and `:rocks-iterator-read`; they do not measure bytes deserialized from an opaque value. The reference's subindexed schema was also inspected directly.
- `cancellation-with-surviving-level-and-recreation` covers middle/head queue removal, partially filled maker cancellation, both sides, and subsequent price-level recreation. `symbol-scope-and-second-writer` reuses request IDs on different symbols and waits through the other client.

Expected maps and sequences are literal, independently derived from the README, not generated from the reference. The solver alias `:test-private` has no `test-resources` path; `:test-private-harness` adds it. The harness does not mandate PState or topology names.

Limitations: IPC appends are not forced into one named microbatch; a yield during matching and a failed microbatch retry are not injected. Event read counts cannot prove absence of whole-value deserialization in an opaque solver. No latency threshold is asserted. Production worker failures and concurrent different-client ordering are not claimed as executed guarantees.

Negative control: temporarily wrote trade price `1` instead of maker price in `trade-record`. The `matching-and-retries` focused test ran 44 assertions, reported **2 failures, 0 errors** (expected `[100 100]`, actual `[1 1]`); restored source then reran the complete suite green. Cognitect's test runner returned process status 0 despite assertion failures in that mutant run, so the footer, not status alone, is the gate.

Final command, from `challenges/hld-stock-exchange`:

```sh
clojure -J-Xmx1600m -X:test-private-harness
```

Last complete run: 6 tests, 626 assertions, 0 failures, 0 errors, exit 0, 91.17 seconds; explicit 2- and 4-task deployments in every test. Transient Rama startup/IPC log ERROR lines appeared in earlier runs without test failures.

PHASE_VALIDATION:pass
