# Private evidence

- Source: `next-level-backends-with-rama-clj` at `1b2e0430539962f1b8798b157e3491ccb8b821fe`.
  `diff -q` against both pinned source and test files produced no differences
  after restoring the temporary mutation.
- Baseline command (from this challenge directory):
  `JDK_JAVA_OPTIONS='-Xmx1g -XX:ActiveProcessorCount=2' timeout 240s clojure -X:test-private-harness`.
  `Ran 1 tests containing 50 assertions. 0 failures, 0 errors.`
  The shared acceptance suite launched the private protocol adapter at 2 and
  4 tasks, with explicit `wait-for-processing!` calls after writes.
- Negative control: temporarily replaced upstream `(filter> (not *muted?))`
  with `(filter> true)` in the private copy, then ran the **same** command and
  assertions. Exit 1: `Ran 1 tests containing 50 assertions. 26 failures,
  0 errors.` Reverted that edit and verified source equality. This demonstrates
  detection of an actual skipped-mute-filter defect, not a standalone false
  assertion.
- Before the private adapter's beyond-end check, the original upstream query
  threw `IndexOutOfBoundsException` for offset 5 on an empty feed, fatally
  stopping an IPC task. That initial run was not a pass (50 assertions,
  0 failures, 2 errors); the adapter handles only this reference edge case.
- `:test-private` has candidate implementation paths and no upstream source;
  `:test-private-harness` uses `:replace-paths` for isolated reference paths.
  Candidate implementation and its own tests were not available here, so no
  candidate pass, worker restart, or asymptotic performance claim is made.
