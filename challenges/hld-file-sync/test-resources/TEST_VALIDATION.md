# Test Validation — hld-file-sync (private suite)

Written in the build phase; **updated 2026-09-23 by the full-spec-review
session** after that session changed the suite (see FULL_SPEC_REVIEW.md
for the per-item rationale). Reviewed:
`test-private/hld_file_sync/functional_test_support.clj`,
`performance_test_support.clj`, and the two `*_challenge_test.clj` entry
points, against README.md, `src/hld_file_sync/protocol.clj`, and
`test-resources/IMPLICIT_SPEC.md`. Both entry points resolve
`hld-file-sync.module/create-module`, so the same suite runs the reference
under `:test-private-harness` and an agent implementation under
`:test-private`.

## Minimize IPC launches
- Functional: one `deftest`, two launches — `{:tasks 2 :threads 2}` and
  `{:tasks 4 :threads 2 :workers 2}` — because the README requires both
  task counts and a second worker exercises inter-worker serialization of
  the custom records. Every scenario group shares one IPC per launch on
  disjoint namespaces (`sv`, `rb`, `rb2`, `cf`, `id`, `id2`, `rc1`, `rc2`,
  `ord`, `wide`, `pg`, `sh`); the third client is created after all
  other writes.
- Performance: one `deftest`, two launches (2 and 4 tasks). Separate from
  the functional IPC because its captures need a fixed, known history
  (1500 blocks, 1200 versions, 300 files) that the functional namespaces
  must not perturb, and because hooks must wrap single operations.
PASS.

## Implicit spec coverage
| Implicit-spec item | Test (file:testing block) |
|---|---|
| Structural throws for every bound and class: `Long` class for size/parent/after-seq/limit/version, `a~b`, `~`, `~`+129, duplicate hash with two sizes, non-vector inputs, non-map block, block without `:hash`, non-String / over-128 blocklist entry, nil file-id / path, over-128 `ns-id` on both commands, empty or nil ids on every query, create with generated id | functional: "structural violations … / register-blocks! bounds / commit-file! bounds / query bounds" |
| Malformed command appends nothing; fresh id stays nil then succeeds as original; malformed retry leaves outcome and counter untouched | "nothing was appended or created", "valid boundary values are accepted", "a malformed retry leaves an existing outcome untouched" |
| Commands return nil; boundary accepts: 128-char ids, 1024-char path, 1024 blocks, 1024-hash blocklist, size 0 and 10⁹, limit 1; version above head and at `Long/MAX_VALUE` → nil; generated id naming no file → nil; every point query on an unknown namespace → nil | "valid boundary values are accepted" |
| register: repeated hash counted once, re-register 0, known same size not new, size-mismatch atomic, cross-namespace independence | "register-blocks!" |
| commit: create, size with repeats, empty blocklist 0, `:file-exists`, `:no-such-file` for client and generated absent ids, `:unknown-parent` before `:need-blocks`, `:need-blocks` first-occurrence dedup, update to v2, history retained, rejections consume no seq, `size-bytes` equals registered sizes summed with repeats | "create, update, and rejections in README order" |
| Conflict copy: original untouched and seq immutable, copy fields, stale+missing blocks → `:need-blocks` and no copy | "stale parent creates a conflict copy …" |
| Copy is ordinary: update keeps `:conflict-of`, nested copies, journal shows each copy once with its provenance | "a copy is an ordinary file …" |
| 129-char returned id, path truncation to exactly 1024, create against generated id throws, copy of a 129-char-id file | "129-character generated ids and path truncation at the limit" |
| Request-id containing `~` → `~~t` | "request-ids containing ~ …" |
| Replay of accepted create; conflicting payloads 1,2,3 (parent change, cross-type reuse); identical replay after conflicts | "replays and conflicting attempts" |
| Replay of a stale commit → exactly one copy | "replay of a stale commit creates exactly one copy" |
| Rejected original stays rejected, `:need-blocks` not re-evaluated after registration, new id succeeds, altered body and cross-type reuse bump the counter only | "rejected originals stay rejected …" |
| Same request-id in two namespaces independent | "the same request-id in another namespace is unrelated" |
| Rejected-before-creation (reject → create → retained → replay rejected → altered body), back-to-back and across barriers; replay after `update-module!` once parent 1 equals the head | "an outcome stored before its file or namespace exists survives creation"; "writes continue after the update …" |
| Ordering without barriers: register then commit, two commits on one parent (v2 then copy), interleaved files, rejection between accepted commits, contiguous seqs | "commands of one namespace apply in issue order without barriers" |
| 1024 distinct hashes with distinct sizes: register → create (all) → update (reversed) → stale copy (512 evens) → replay create → replay stale, two files with one path, all without barriers; exact sizes 1547776 / 773632, versions, records, 5-entry journal | "1024 distinct hashes: …" |
| Paging 1200 entries: 500/500/200/[], limit 1, deep cursor, straddling page, beyond-last, `Long/MAX_VALUE`, `MAX-1`, unknown namespace, no `:blocklist`; 1000-version file; journal ↔ version agreement | "journal paging over 1200 entries and deep version history" |
| Second client sees first client's writes; same conflicting body twice → 2, replay → 2, `update-module!`, again → 3 with no extra version/journal; every read unchanged after the update; writes continue with contiguous seqs (dependent commits through one client or barriered); replay across the update; size-mismatch after update | "two clients and a module update share durable state" |
| A client created after all writes reads every earlier scenario's state and can issue and barrier its own commit | "a client created after the writes sees everything …" |
| Resource guarantees: register/commit/copy/rejected/replay cost vs. same-namespace history; constant point reads after growth; `get-changes` deep vs shallow, 500-page, empty tails (limit-proportional, small vs big journal); independent namespaces; 1024-entry register and commit | performance: all `testing` blocks, at 2 and at 4 tasks |
All entries present. PASS.

## Independence of expectations
Every expected map is built by test helpers (`accepted-commit`,
`file-rec`, `change`, `conflict-path`) from the test's own inputs and the
README rules (seq arithmetic, size sums with repeats, truncation length
1024 − 147, the 1024-entry size sums stated as literals). No expectation
is read back from the module or from reference helpers; the reference
namespace is not required by the tests. PASS.

## Synchronization
Every write is followed by `harness/wait-for-processing!` before the next
read. Barriers may go through a different client than the writer: the
README "Shared state" paragraph now states that `wait-for-processing!` on
any client of one `create-module` result waits for every command issued
through any client of that result. Commands whose outcome depends on
another command's processing are issued through one client or separated
by a barrier (README orders commands only within one client). PASS.

## Test namespaces compile
Both entry namespaces load under `:test-private-harness`; `clj-kondo
--lint test-private` reports 0 errors, 0 warnings. `rama-challenges.harness`
is used only for `wait-for-processing!`, as the README instructs. PASS.

## Efficiency-suite design notes and limitations
- Ceilings are loose constants (reads < 80/100, writes < 60, point reads
  < 25, page < 180 or 1600, empty tail < 40 + 2·limit, 1024-entry
  commands < 100 + 3·1024 reads and < 100 + 2·1024 writes) plus small-vs-big comparisons
  (`big ≤ 2·small + 30`, including the fixed-limit empty tail). Measured
  reference costs at both task counts: register 15 reads / 7 writes,
  commit 16 / 5, copy 13 / 5, rejected 10 / 2, replay 4 / 1, `get-file`
  5, `get-file-version` 3, `get-outcome` 2, `get-block-size` 2, 50-entry
  page 52 reads at any cursor, 500-entry page 504, empty tail 2 on both
  the 8-entry and 1502-entry journals, 1024-block register 2073 reads /
  1036 writes, 1024-hash commit 1036 reads / 5 writes. A scan of the
  1500-block index or 1200-version history exceeds every ceiling, so
  the bounds discriminate without pinning a design.
- Correctness of each captured operation is asserted outside the hook
  because query-topology results can be truncated under
  `with-event-hook`.
- Event counts cannot detect a whole-collection blob stored as one value;
  IMPLEMENTATION_VALIDATION.md covers that by schema/path review only.
- The per-task `:depot-read` distribution is printed as a diagnostic and
  not asserted: the README does not constrain the ingress topology.
- No wall-clock assertions; no topology, PState, or depot names asserted.

## Negative control (mutation) evidence
- Build phase: `commit-plan` with `copy? false` (last-writer-wins) →
  60 failures on the build-phase suite.
- Full-spec review, same mutant on the updated suite: 530 functional
  assertions, 70 failures, 0 errors, first at line 245 of the final file; the new
  1024-hash scenario fails too.
- Full-spec review, replay case `(case> false)` (a replay counted as a
  conflicting attempt): 40 failures, 0 errors, at final-file lines
  316–405, 460–462 and 552–594 (idempotency, `wide`, `sh`, `rc2` after
  update). The mutant runs preceded the final two-line edit at line 138,
  so their printed numbers are one lower for lines ≥ 139.
- After each mutant the module was restored from a backup and compared
  byte-for-byte (`cmp`; sha256 prefix `9048c319e3c63ca0` before and
  after); no `MUTANT` marker remains.

## Final run
`clojure -X:test-private-harness` from the package directory, fresh JVM,
foreground, after the last suite edit:
`Ran 2 tests containing 710 assertions. 0 failures, 0 errors.`
(functional 278 assertions per launch × 2 launches = 556; performance
77 per launch × 2 = 154). Wall clock 104 s.

## Verdict
**pass** — all checks hold with cited tests; the suite is green.

## Independent validation addendum (2026-09-23)

Post-fix Oracle review found one remaining private overconstraint: the
1024-hash commit assertion capped writes at 60, although README.md permits
work proportional to the submitted list. The assertion now allows
`< 100 + 2·(count hashes)` writes; small-input history-growth checks remain.
The three earlier overconstraints (cross-client winner, ingress task count,
constant empty-tail work) are resolved. Oracle found no concrete reference
bug. The accepted barrier scope is prior commands across wrappers of the
same create-module result, not future commands after invocation.

Independent negative control: sorting `missing-hashes` instead of retaining
first-occurrence order produced 2 assertion failures, 0 errors, at
`performance_test_support.clj:123` (expected `["zz" "yy"]`, actual
`["yy" "zz"]`) across 2- and 4-task launches. This was executable
contract discrimination, not a compilation failure; the module was restored
byte-identically to SHA-256 `9048c319e3c63ca003172152088b901398758f91c460cd22749f58e97d317b76`.

After restoration and the private bound correction, a fresh-JVM
`clojure -J-Xmx1600m -X:test-private-harness` run finished in 117.73 s,
exit 0: 2 tests, 710 assertions, 0 failures, 0 errors. Both explicit
task configurations executed. Captured 1024-entry register/commit costs
at each configuration: 2073 reads / 1036 writes and 1036 reads / 5 writes.
The reference stayed at the SHA-256 above; the public contract manifest
verified all three paths with `sha256sum -c`.
