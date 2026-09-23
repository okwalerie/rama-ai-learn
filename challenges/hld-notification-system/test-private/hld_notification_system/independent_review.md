# Independent validation (2- and 4-task IPC)

The contract archive SHA-256 was `9ac810a32310bcbcd0dbce01e90dde68e9b85e3497c05d333a3d59edf1bd9a61`; `sha256sum -c .amp/hld-program/transfers/hld-notification-system.contract.sha256` passed for README, protocol, and deps. The original reference module SHA-256 before and after temporary mutants was `72ef4fd3251223a2b41e33c34fe47822f5e000fd4d559e83cc87dfee5dfb4b7f`.

Run from `challenges/hld-notification-system`:

```sh
clojure -J-Xmx1600m -X:test-private-harness
```

`independent_test.clj` adds paired 240→1,040 histories on the same eight-device recipient. It checks 100-entry pages, 8-delivery snapshots, failed state on the last historical submission, and unique dead-letter page IDs, and measures aggregate `:rocks-read`, `:rocks-iterator-read`, `:rocks-iterator`, and `:rocks-commit` batch counts. Counts are captured around point/page queries and individual submit/attempt/receipt plus a processing barrier, at both task configurations. Correctness reads are outside the hook (a hooked range query can truncate output). A generous relative allowance of +150 operations per category rejects history scans or per-history writes but is not a latency or exact-layout threshold. Reads and writes may land on different tasks; aggregate accounting does not demand any particular depot-read placement. In the restored reference, both task configurations yielded identical small/large observations: point 1 read; recent and dead-letter page 102 reads + 1 iterator; submit 6 reads + 4 writes; attempt and receipt 2 reads + 1 write each. No hook measures opaque-value bytes, network messages, or CPU work not reflected in RocksDB events. The source uses hashed user and submission keys and subindexed tail ranges; this inspection, not an executable storage-balance assertion, supports distribution/bounded-page claims.

The update tests call `rtest/update-module!` with the **original unchanged module**, create a fresh wrapper, and verify persisted pending retry due time and attempt count. A duplicate/early report remains ignored; after a device refresh, the old-generation invalid-token report terminates its delivery without invalidating the new generation. An old submission-id remains first-wins, and a fresh submission fans out to the refreshed device. This exercises update/recovery, not an injected mid-microbatch crash or proven production replay. A wrapper made before update also reads after update. The existing cross-owner collision test accepts serially allowed winner histories; no cross-client arrival winner is prescribed.

Negative controls (temporary reference edits, compiled and executed, then byte-identically restored):

1. Replace generation comparison in `invalidate-profile` with membership-only invalidation. `:patterns '["hld-notification-system.independent-test"]'` ran 4 tests/98 assertions, 4 failures/0 errors: both task configurations observed the refreshed device incorrectly invalidated and absent from fresh fan-out.
2. Change only recent page retrieval to fetch 1,000 tail entries and `take 100` after reversing, preserving public page output. The same command ran 4 tests/98 assertions, 2 failures/0 errors at the read-work assertion. Observed recent reads rose 241→1,008 (240→1,040 entries), compared with restored 102→102. This is a meaningful semantic/work mutant, not a compilation failure.

The complete post-restore test log is `independent_full.log`; the additional post-second-mutant rerun is recorded in that log. The harness aliases differ: `:test-private-harness` adds `test-resources`, while `:test-private` does not, so solver tests do not accidentally resolve the reference. Neither the finite history sizes nor the hook prove an asymptotic bound for arbitrary history, opaque byte cost, process crashes, throughput, or production latency. No public-contract contradiction or demonstrated reference defect was found.
