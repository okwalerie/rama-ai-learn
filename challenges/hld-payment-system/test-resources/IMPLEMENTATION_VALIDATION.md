# Implementation validation — payment-system reference

The frozen README, protocol, and deps manifest matched the handoff archive before implementation. No public-contract contradiction was found. The reference module uses one tenant-keyed command depot and one microbatch topology. Its per-tenant batch is ordered by stamped ingress positions before any command is applied. Only the bounded current command records and point-read tenant/account/charge/request values enter the transition function; the growing account, charge, request, and journal maps are subindexed. Tenant creation writes individual fields, preserving precreation rejections. The wrapper holds handles and a shared processed-count atom, not business state.

The implementation uses bounded `Object` leaf maps for account, charge, journal, and request records instead of the plan's individually typed fixed-key leaf schemas. This keeps their individual subindexed entries bounded and addressable; it sacrifices schema-level shape enforcement, not the public access or persistence bounds. No history-sized map is selected or replaced. One grouped batch is capped at 1,000 depot records per task; the per-tenant loop yields between completed commands.

Validation command from `challenges/hld-payment-system`:

```sh
/usr/bin/time -f 'ELAPSED:%e EXIT:%x' clojure -J-Xmx1600m -X:test-private-harness
```

Restored run: **3 tests, 89 assertions, 0 failures, 0 errors; exit 0; 56.41 seconds**. It launches 2-task and 4-task ledgers, then a separate 4-task 520-transaction deep-history ledger. The two-client barrier, module update, precreation rejection, independent tenant currencies, signed entries, charge's original seq, refund limits, and cached rejections are checked. Read/event-hook bounds count RocksDB operations but cannot measure bytes deserialized from an opaque blob; the reference layout was inspected separately. The hook assertions require positive observed reads, preventing a zero-count instrument from passing. The 4-task write-cost capture also spans append through processing barrier. IPC can log Kafka index recovery during module update without a test failure.

Restored negative controls (both were source mutations, not compilation failures):

1. Reverse the refund posting pair in `apply-original`: `ledger-two-tasks` failed the exact row-3 journal assertion (expected merchant −7, customer +7; observed customer +7, merchant −7).
2. Add `:seq seqno` to the charge update on refund: `ledger-two-tasks` failed the charge-record assertion (expected original seq 2; observed 3) and post-second-refund seq assertion (observed 4).

Both edits were reverted before the final full-suite run. A focused Oracle source review found no concrete reference ledger defect; its test-gap findings drove stronger update/replay, conflict-count, malformed-command barrier, account-read, and positive-hook assertions. This suite does not simulate worker crashes, prove all production retry interleavings, or measure opaque serialized value bytes. `clj-kondo` without Rama hooks reports false unresolved-dataflow symbols; loading the namespace and the IPC suite are the compile/runtime checks.
