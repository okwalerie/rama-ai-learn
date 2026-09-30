# Private verification receipt

- `JDK_JAVA_OPTIONS='-Xmx1g -XX:ActiveProcessorCount=2 --add-opens java.base/java.lang=ALL-UNNAMED --enable-native-access=ALL-UNNAMED' clojure -X:test-private-harness`
  completed: `Ran 5 tests containing 59 assertions. 0 failures, 0 errors.`
  The upstream direct tests and adapter-facing tests ran in the same harness.
  Protocol tests forced both 2 and 4 tasks, exercised two IDs, stale insertion
  tie, split removal (version 2 → 4), no-op stale removal (version remains 4),
  unknown ID, and subsequent edit persistence within the same module.
- Stream retry: `stream-retry-applies-each-edit-once` fails the first
  `:streaming-complete` of each edit at 2 and 4 tasks for a stale insertion, a
  stale split removal, and a fully subsumed stale removal. Against the previous
  adapter, which delegated to the upstream module: `Ran 5 tests containing 59
  assertions. 4 failures, 0 errors` (stale insertion stored twice,
  `"abcXY!!def"` version 4, and the later-edit check, at both task counts).
  The removal cases pass on both because a replayed removal is subsumed by its
  own stored operations. Replacing the applied-request `filter>` with
  `(filter> true)` in the retry-safe module gave the same 4 failures, and
  restoring it gave 0 failures.
- Earlier mutation negative control (4-test suite, before the retry test): temporarily changed the reference adapter's
  AddText conversion from `(:content action)` to `(str "!" (:content action))`.
  Re-ran the exact same command: `Ran 4 tests containing 49 assertions.
  16 failures, 0 errors` (nonzero exit). Both 2- and 4-task text assertions
  detected altered output. Restored the adapter and reran the exact command:
  `Ran 4 tests containing 49 assertions. 0 failures, 0 errors.`
- Rama emitted transient `LeaderNotFoundException` resolve log lines during
  IPC teardown/retries; the test-runner exited 0 on the final clean run.
- Direct Clojure execution also confirmed source transform results: stale
  insertion at offset 3 against deletion `[2,5)` returns offset 0, while
  removal `[10,16)` against deletion `[8,12)` returns offset 8 length 4.

Candidate `clojure -X:test-private` is intentionally not runnable without a
candidate implementation at `../../implementations/collaborative-document-editor/src`.
The harness alone does not prove an unseen candidate implementation passes.
