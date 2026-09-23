# Independent validation (reference candidate)

Input archive SHA-256: `a49f7b5cccd267a7c858f34a8850e652f0736e9398d573900a6650612ef3a059`.
`sha256sum -c .amp/hld-program/transfers/hld-search-autocomplete.contract.sha256`
reports OK for README, deps, protocol before and after testing. Reference
`module.clj` SHA-256 before mutation and after restoration:
`e3de084692ed63d7d0847a3301eb450d70d67ad8bf38bec3264d9e377a226f53`.
No public or reference files are delivered modified.

From `challenges/hld-search-autocomplete`, run
`clojure -J-Xmx1600m -X:test-private-harness`. Restored full run: exit 0,
4 tests, 356 assertions, 0 failures/errors, 91.86 seconds, 2 and 4 tasks.
The previous harness had 2 tests/314 assertions. Logs: `independent_run.log`,
`independent_dedup_mutant.log`, `independent_scan_mutant.log`. The `test-private`
alias instead resolves the implementation path, not this bundled reference;
`test-private-harness` adds the reference resources. No solver evaluation ran.

`independent_test.clj` fixes query inputs and k while increasing an already
populated same-prefix corpus 300→3,000 (within the 10,000-entry snapshot bound);
get-phrase and one new search hold phrase fixed while unique-session history
increases 60→601 (both populated, with literal expected counts); one-entry
publication is measured with 20 versus 320 prior-generation novel trends. Counts are aggregate
hook events across tasks, not topology-layout budgets; they include the write
and barrier in the write comparisons. On the restored candidate, for each task
configuration, suggest point/iterator/next was `4/1/2 → 4/1/2`, get-phrase
`5/0/0 → 5/0/0`, and search point/iterator/next/writes
`54/0/0/24 → 54/0/0/24`. Publish was `34/0/0/18 → 34/0/0/18` with 2 tasks,
`40/0/0/22 → 40/0/0/22` with 4. Diverse locales reached depot tasks
`#{0 1}` and `#{0 1 2 3}` respectively, **diagnostic only**: ingress
placement does not establish balanced PState storage or work, nor should a
valid implementation be required to partition its depot. Balance remains a
source-review limitation; one legitimate hot key need not distribute. The
unchanged-module update preserved current generation, block
policy, score and session membership; fresh-wrapper writes then updated the
old wrapper's reads. IPC update emitted transient Kafka index recovery and
assignment-cache messages, but assertions passed.

Compiled negative controls, each run using the full 2/4-task command and
restored afterward:

1. Replace the session membership filter with `(filter> true)`: exit 1,
   4 tests/358 assertions, 48 failures/0 errors **on the previous test revision**.
   The independent post-update
   check specifically observed 3 rather than 2 sessions and score 10029 rather
   than 10019; original model checks also failed.
2. On the revised test, read 3,000 ranks in `suggest`, then return only
   `(take k ranks)`: output remains correct but work grows from 301 to 3,001
   iterator-next events for 300→3,000 matching candidates. Exit 1,
   4 tests/356 assertions, 2 failures, 0 errors, both in the independent work
   comparison (306→3,006 aggregate reads).

The qualitative comparison uses a 4× slack against a 10× populated-input increase,
not an asserted exact number of seeks, hops, or topology operations. Hook
counts do not reveal serialized bytes, prove balanced storage/work or throughput under skew,
prove no production crash failure, or bound lifetime disk consumption. The
reference retains obsolete generations; this test shows publication work does
not traverse prior trends, not that old bytes are reclaimed. An unchanged
module update is not a worker-kill/crash durability proof. No wall-clock
acceptance threshold was used.
