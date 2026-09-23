# Reference and private harness verification (2026-09-23)

From `challenges/hld-feature-flag-service`:

```
clojure -J-Xmx1600m -X:test-private-harness :nses '[hld-feature-flag-service.private-harness-test]'
```

Restored reference: 2 tests (explicit 2-task and 4-task launches), 94
assertions, 0 failures, 0 errors, exit 0, 52.30 seconds. Both wrappers
observe accepted writes after the writer's barrier. A third wrapper is
constructed after prior processing. A same-module `update-module!` preserves
the observed config, followed by an accepted write. Five published buckets
and three additional independently calculated buckets are asserted as
constants, not computed by the reference. The source is durable per-key Rama
PState; only the processed-record counter lives in a client closure.

Negative controls, each applied to the reference then restored:

- Unknown-operator scan changed from `some` to `every?`: both task-count
  tests failed on a matching early rule with a later unknown operator,
  reporting `:rule` where `:unknown-operator` was required (and also on
  empty rules). 4 failed assertions, 0 errors, exit 1.
- Revision gate changed from `>` to `>=`: equal-revision conflicting kill
  replaced accepted content and equal-revision second body replaced the
  first. 14 failed assertions, 0 errors, exit 1.
- After restoring both mutations, the full reference suite again passed
  with 94 assertions, exit 0.

The `:test-private` entrypoint calls `requiring-resolve` for the solver
namespace; it does not substitute the reference. The harness entrypoint
requires the reference. **Frozen `deps.edn` does not isolate the two
entrypoints:** both aliases discover all `*-test` files under `test-private`,
and project paths prefer an implementation namespace if present. Therefore
the explicit `:nses` override above is essential for independent reference
validation. The parent should consider unfreezing only alias configuration
to select the respective test namespace, with `:replace-paths` on the
harness alias to remove solver paths. No frozen file was edited here.

The RocksDB event-hook smoke test bounds reads after 150 unrelated flags,
but cannot see serialized bytes or client-side scans. Static layout inspection
supports one-key reads and one-key updates; IPC tests do not prove worker crash
recovery, network partitions, or production counter behavior. No evaluation
solve was run. `clj-kondo` cannot resolve the Rama macros in this repository's
configuration; namespace compilation and executed IPC tests succeeded.

Focused Oracle review inspected the contract and harness; its findings about
extra metadata, alias isolation, kill-vs-unknown precedence, absent rollout,
typed equality, and late second wrapper were incorporated or documented.
