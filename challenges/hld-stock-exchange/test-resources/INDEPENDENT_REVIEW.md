# Independent private harness review

Transfer archive SHA-256: `b9be3143032f0a36dd945c6c8c08d39191e48d9a4860929e587ebc2e2ba83a96`. Extracted at repository root. `sha256sum -c .amp/hld-program/transfers/hld-stock-exchange.contract.sha256` passed before and after review (README, protocol, deps). Restored reference SHA-256: `6db37dcaa3212823f8500d9bbc52a6a933e70875520c55696c1a229eba732a86`; no public/reference change retained.

From `challenges/hld-stock-exchange`:

```sh
clojure -J-Xmx1600m -X:test-private-harness
clojure -J-Xmx1600m -X:test-private-harness :vars '[hld-stock-exchange.private-test/bounded-work-and-deep-page]'
clojure -J-Xmx1600m -X:test-private-harness :vars '[hld-stock-exchange.private-test/matching-and-retries]'
```

The final plain full command (after all three mutants were removed) exited 0: **7 tests, 10096 assertions, 0 failures, 0 errors**. Full output is `review-logs/full-restored.log`. Both 2- and 4-task IPC deployments run in every test. Earlier focused paired test passed 1 test / 5244 assertions before the tape fixture correction; the final full result supersedes it.

Paired 320→1280 fixture metrics at each task count (read, iterator seek, iterator read, write-batch entries): depth `(1,1,4,0)→(1,1,4,0)`; 1280-order single-level depth `(1,1,2,0)→(1,1,2,0)`; order/outcome `(2,0,0,0)→(2,0,0,0)`; noncross `(10,1,2,6)→(12,1,2,6)`; cancel `(13,0,0,6)→(13,0,0,6)`; three-trade match `(36,7,19,18)→(36,7,19,18)`; deep five-row tape `(1,1,6,0)→(1,1,6,0)`. These are observations, not required constants. The gate compares each category against 2× the smaller observation plus 12 events, preserving arbitrary fixed overhead while rejecting a scan at this finite growth ratio. Outputs and touched counts remain fixed; functional expectations are literal and separate from event measurements.

Negative controls temporarily edited `test-resources/hld_stock_exchange/module.clj`, then restored it before the final full run:

| Mutant | Focused result | Log |
|---|---|---|
| `get-depth` fetches 100000 levels and takes requested count | iterator reads 323→1288; 2 failures / 0 errors, exit 1 | `review-logs/stock-scan-mutant.log` |
| queue head selects last of first two entries | 4 failures / 0 errors, exit 1 | `review-logs/stock-fifo-mutant.log` |
| trade record stores price 1 instead of maker price | 2 failures / 0 errors, exit 1 | `review-logs/stock-maker-mutant.log` |

The prior author's report recorded a failing mutant with process exit 0. This run's failures exited 1, but a robust external gate must parse a nonzero `Ran N tests containing M assertions` footer and `0 failures, 0 errors`; exit 0 alone does not establish success. A missing footer is inconclusive, not a pass.

Source inspection: `*commands` is symbol-partitioned; pre-group ingress positions are stored before `+vec-agg`, `ordered-pairs` sorts by those positions, and one per-symbol loop finishes a command before the next. Both outer and maker loops yield. The 1042-command single-client stress exceeds the reference batch cap and checks the final ordered effects at both task counts. It does **not** force a same-batch collision, a yield during matching, rollback/retry, or a production crash; those remain source-supported but not fault-injected evidence. Event hooks count operations, not deserialized bytes: a solver storing an opaque whole book could evade this finite count gate. The reference's growing request, order, level, queue, and trade maps are subindexed, including the inner per-level queue; no top-level whole-book rewrite was observed in source. Finite 320→1280 probes cannot prove an asymptotic guarantee or bound arbitrary history size. No topology/layout/latency threshold is imposed on solvers.
