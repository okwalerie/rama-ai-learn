# Private evidence

All commands run from this challenge directory with
`JDK_JAVA_OPTIONS='-Xmx1g -XX:ActiveProcessorCount=2 --add-opens java.base/java.lang=ALL-UNNAMED --enable-native-access=ALL-UNNAMED'`.

- Source: `next-level-backends-with-rama-clj` at
  `1b2e0430539962f1b8798b157e3491ccb8b821fe`. The copies under
  `test-resources/upstream/nlb/` are byte-identical to baseline commit
  `43abd3fccff8777d8995f8a29658f3c4c97df01a`.
- Retry regression, old reference (baseline `module.clj` wrapping the upstream
  stream topology) plus the new `retry-does-not-duplicate-posts` test:
  `timeout 400s clojure -X:test-private-harness` exited 1 with
  `Ran 2 tests containing 51 assertions. 1 failures, 0 errors.` The forced
  `:streaming-complete` failure left two identical posts in the feed.
- Fixed reference (`core` microbatch topology), same command: exited 0 with
  `Ran 2 tests containing 51 assertions. 0 failures, 0 errors.` The
  acceptance contract runs at 2 and 4 tasks with explicit
  `wait-for-processing!` after writes.
- Negative control, fixed reference: temporarily replaced
  `(filter> (not *muted?))` with `(filter> true)`, then ran the same command:
  `Ran 2 tests containing 51 assertions. 26 failures, 0 errors.` The edit was
  reverted and the file verified byte-identical to the fixed version.
- `timeout 300s clojure -X:test-private` (candidate classpath) cannot load
  `content-moderation.module` because `implementations/content-moderation`
  does not exist here, so no candidate pass, worker restart, or asymptotic
  performance claim is made.
- `:test-private` has candidate implementation paths and no reference source;
  `:test-private-harness` uses `:replace-paths` for isolated reference paths.
