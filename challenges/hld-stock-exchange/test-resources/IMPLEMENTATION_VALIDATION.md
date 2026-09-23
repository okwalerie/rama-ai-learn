# Implementation Validation

Verdict: pass (source-level contract trace; IPC checks recorded in TEST_VALIDATION.md).

- Ingress order: `*commands` is symbol-partitioned; pre-aggregation reads and advances `:ingress-seq` per record. The grouped vector is **sorted by position**, never trusted for ordering. One per-symbol loop completes each command before advancing. The outer and matching loops yield cooperatively; other symbols can progress without another active command for this symbol in the same microbatch.
- Retry/durability: the ingress position, requests, order and trade counters, all matching records, and level aggregates are PState writes in one microbatch. No client-side book exists. An identical replay does no writes; a different payload increments only the conflict count. Outcomes, orders, and trades remain after fills and cancellation.
- Matching: best ask uses ascending tick key; best bid uses `1000000001 - price`, also ascending. Queue keys are immutable accepted order seqs. Each loop step consumes one maker, records its price and both accounts, adjusts order and aggregate, and deletes filled queue entries. IOC residual is recorded as cancelled, never queued. Cancellation checks absence, then owner, then openness and updates the target's queue/order/aggregate only.
- Resource layout: all growing maps (orders, requests, trades, levels, outer and inner queues) are subindexed. No symbol entry is replaced wholesale. Matching seeks one best level, one queue head, and one maker per trade; depth ranges over levels, tape ranges from the requested seq, point queries use keypaths. Batch vectors are capped to 1000 records per depot partition per microbatch. The wrapper stores only handles and a shared synchronization counter.

The documented `clj-kondo --lint` invocation is not configured with Rama macro hooks in this checkout and reports DSL false positives (118 errors), while namespace loading and IPC execution succeeded. It is not treated as a passing lint check.

PHASE_VALIDATION:pass
