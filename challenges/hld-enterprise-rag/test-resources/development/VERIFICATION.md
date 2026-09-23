# Verification — 2026-09-23

Run from repository root unless noted. Contract manifest supplied with transfer:

```sh
sha256sum -c .amp/hld-program/transfers/hld-enterprise-rag.contract.sha256
# README.md: OK; deps.edn: OK; protocol.clj: OK
```

From `challenges/hld-enterprise-rag`:

```sh
clojure -J-Xmx1200m -X:test-private-harness
# Running tests in #{"test-private"}
# Testing hld-enterprise-rag.contract-test
# Ran 5 tests containing 74 assertions.
# 0 failures, 0 errors.
```

Final restored run: exit 0, real 1m17.363s; full stdout/stderr captured
in orb at `/tmp/hld-rag-final.log` (14 lines). Each test loops through explicit
2-task and 4-task IPC deployments; the update test redeploys the same module.
Rama emitted transient Kafka index recovery/leader-resolution diagnostics
in earlier green runs during IPC shutdown/update; they did not fail assertions.

Alias isolation:

```sh
clojure -J-Xmx1000m -X:test-private
# exit 1: 5 errors, solver module absent from classpath (expected here).
# This alias does NOT accidentally test the reference.
```

Focused negative controls (both compiled and executed):

1. Set `old #{}` in `posting-diff`, disabling posting deletes. Run
   `clojure -J-Xmx1200m -X:test-private-harness :vars '[hld-enterprise-rag.contract-test/fences-denial-and-tenant-isolation]'`.
   Exit 1; 1 test/22 assertions, **6 failures, 0 errors**: authorized live
   tombstone and later recreation erroneously retained `same-doc/c` in both
   task configurations. Restored `(references key old-chunks)`.
2. Sort raw postings by score and `(take k)` before ACL filtering in
   `query-results` (postfilter top-k). Run with `:vars
   '[hld-enterprise-rag.contract-test/revision-and-authorization]'`.
   Exit 1; 1 test/22 assertions, **2 failures, 0 errors**: forbidden
   score-2 chunk occupied the sole slot; expected authorized score-1 `d/a`
   at `k=1`, actual `[]`, at both task counts. Removed the early sort/take.

The full 5-test suite above is the post-restoration run. Event-hook growth
checks count RocksDB reads/writes with generous architecture-neutral
headroom, not opaque-value bytes. Module update is not a process crash or
schema-migration simulation. No solve evaluation or push was performed.
