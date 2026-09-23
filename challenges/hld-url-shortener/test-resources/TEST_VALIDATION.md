# Test Validation

<!-- Phase 6 artifact for hld-url-shortener (BUILD phase, resumed from checkpoint 21e6676).
     Reviewed: test-private/hld_url_shortener/functional_test_support.clj (F),
     performance_test_support.clj (P), and the two *_challenge_test.clj entrypoints, against
     protocol.clj, README.md and IMPLICIT_SPEC.md. -->

## Minimize IPC launches
Four `deftest`s total: functional and performance, each at 2 and 4 tasks. The task count is a
launch parameter, so 2-vs-4 cannot share an IPC; functional and performance are separated because
the performance suite installs event hooks around specific operations and builds 512-entry
histories on aliases whose counts the functional scenarios never touch, but keeping the hook
captures free of unrelated functional traffic requires a quiet cluster. Every functional scenario
lives in one `deftest` per task count using disjoint aliases. PASS.

## Implicit spec coverage
Each IMPLICIT_SPEC edge case / entity-state × write cell and the `testing` block (file) that
exercises it with a hand-derived expected value:

- Write methods return nil — "write methods return nil" (F).
- Missing alias reads: resolve at 0 and max tick, count 0, outcome nil — "missing alias reads" (F).
- missing × create; retry of original pair with changed body; second request-id rejected and
  replayed with a changed body; same request-id on another alias independent —
  "create, resolve, outcome, first request wins" (F).
- alias of length 1, target-url exactly `https://`, expires-at 0 born expired —
  "grammar boundaries" (F).
- Upper grammar bounds: 32-char alias, 64-char request-id and click-id, 2048-char target-url
  round-tripped exactly, expires-at = 10^12-1 (active at max-1, expired at max), a 31-char
  suffix of the alias is a distinct missing alias — "grammar upper bounds" (F; added by the
  full-spec review).
- now = expires-at-1 active, now = expires-at expired, max tick expired, expired alias still
  reserved (rejected) — "exact expiry boundary" (F).
- expired × block (blocked outranks expired), idempotent block, unblock restores expired/active by
  now, idempotent unblock, interleaved block/unblock/block from one client last wins —
  "block outranks expired; unblock restores; idempotent" (F).
- missing × block/unblock/delete leave no trace, later create starts unblocked and active —
  "block/unblock/delete on a missing alias leave no trace" (F).
- blocked × delete (deleted outranks blocked), deleted × unblock/block/delete no change, deleted ×
  create rejected, original outcome still `:created` — "delete is irreversible and outranks block
  and expiry" (F).
- click on missing alias dropped without trace (same click-id counts after create), duplicate from
  both clients, click-id reused across aliases independent, expired/blocked/deleted × click counted,
  duplicates after delete still deduplicated, reads do not mutate —
  "clicks: dropped when missing, deduplicated, status-independent" (F).
- one-client invocation order across create/click/block/click/delete/click/create with no
  intermediate barrier; sequential creates first-issued wins —
  "one-client invocation order without intermediate barriers" (F).
- concurrent creates from two clients: exactly one created, resolve matches the winner —
  "concurrent creates from two clients: exactly one created" (F).
- delete racing create across clients: only the two sequential outcomes are accepted
  (`:active` if the delete was processed first, `:deleted` otherwise), the create outcome is
  `:created` either way, and after a barrier the delete is final and a later create is
  rejected — same block, alias `dc-race` (F; added by the full-spec review).
- up to many request-ids on one alias, each outcome exact, unknown request-id nil —
  "many request-ids on one alias" (F).
- concurrent distinct/duplicate observations from two clients, count equals distinct ids —
  "distinct concurrent observations from two clients" (F).
- nil expires-at with huge now active — "create, resolve, outcome, first request wins" (F,
  `max-tick` resolve of `alpha`).
- Fixed read work at 32 vs 512 history, click write work at 32 vs 512, duplicate click bounded,
  independently derived counts/outcomes at both sizes — "read and write work is bounded..." (P).
- Observed RocksDB work balanced across tasks for 400 owners through two clients —
  "observed RocksDB work is distributed across tasks with many owners" (P).

Cell "missing × create with a pair already recorded :rejected" is impossible by the spec's own
note and is not tested. Cross-client delete-racing-create is specified as either order acceptable;
the two sequential outcomes it reduces to are covered (create→delete in "delete is
irreversible", delete-of-missing→create in "block/unblock/delete on a missing alias") and the
race itself is now asserted either-of in "concurrent creates from two clients". PASS.

## Synchronization
Every write-then-read sequence in F and P is separated by `sync-a!` / `sync-b!`, which call
`harness/wait-for-processing!` through the other wrapper. The two deliberate no-barrier runs
("one-client invocation order", "concurrent creates") place the barrier after the whole write
burst and before any read. The build-history calls in P are followed by both barriers. PASS.

## Test namespaces compile
Both support namespaces require only `clojure.test`, `com.rpl.rama.test`, the protocol, and
`rama-challenges.harness`; the entrypoints resolve `create-module` with `requiring-resolve`. No
record constructors are referenced by tests. Compiled and executed in the runs recorded below. PASS.

## Architecture neutrality of the performance suite
- No `:depot-read` dependence and no exact record count; only RocksDB-level events on non-internal
  PStates (`:rocks-read`, `:rocks-iterator`, `:rocks-iterator-read`, `:rocks-commit`). A stream
  design is measured identically to a microbatch one.
- Fixed ceilings (`read-budget`, `write-budget`) plus a bounded-growth allowance from 32 to 512
  entries replace exact small/large equality. Ceilings and allowances are the ones published in
  README "Enforced work budgets".
- Distribution uses per-task written entries and per-task aggregate read work (point reads +
  iterator seeks + iterator reads) with the 0.5x-1.5x band and the published per-metric ceilings
  (see the resumption section below), launched with one thread per task because RocksDB events
  are attributed to the task thread.
- Event data was inspected at runtime: `:rocks-read` carries only `:name`, `:module-name`,
  `:task-id`, `:topology`; no value-size or byte field exists, so no materialization guard is
  asserted. This limitation is stated in the README. PASS (no invented fields).

## Verdict
pass — every check above passed by explicit walk-through with the covering `testing` block cited.

Decision: pass. Basis: every implicit-spec cell maps to a cited block with an independently derived
expectation; barriers precede every read; the performance suite depends on no topology or layout.
Outcome: proceed to the run and to mutation evidence (appended below after execution).

## BUILD-phase resumption: Oracle fixes, execution and mutation evidence

### Oracle-required fixes applied (tests and README only; module untouched)
- `functional_test_support.clj` "many request-ids on one alias": a `sync-a!` barrier now
  follows the initial `req-0` create before the competing cross-client creates. Without it,
  cross-client writes may serialize in either order and `req-0` is not guaranteed to win.
- `performance_test_support.clj` distribution scenario: each owner's create and click go
  through the SAME wrapper (owners alternate between wrappers), so the click is ordered
  after its create by the one-client rule. Previously the click could precede the create.
- Distribution now collects point reads, iterator seeks, iterator reads and written entries
  per task; balances written entries and aggregate read work (reads + seeks + iterator reads)
  in the 0.5x..1.5x band; the point-read minimum is removed; the combined `8*n` bound is
  replaced by published per-metric ceilings (`distribution-ceiling`: 8x point reads, 4x
  iterator seeks, 16x iterator reads, 1x..8x written entries) and reads must write nothing.
  The block is named "observed RocksDB work is distributed across tasks"; README states the
  hook reports counts only, so this observes work distribution, not bytes or storage.
- `read-budget` now includes `:writes 0`.
- Independently derived outcomes (winner `req-0`, last rejected `req-(n-1)`, missing `req-n`,
  click count, resolve) are asserted at BOTH 32 and 512; duplicate-click work is measured at
  both sizes with independent count checks (33 and 513).
- Hook accumulation uses `swap!` so concurrent task-thread events are not lost.
- README "Enforced work budgets" rewritten to match the executable checks exactly. The
  opaque-value materialization limitation is retained as a review-only rejection; the hook
  carries no value-size field and none was invented.

### Execution (from `challenges/hld-url-shortener`, unmutated module)
Command and result of the logged full-suite runs on this test revision (232 assertions):
```
clojure -J-Xmx1600m -X:test-private-harness
Ran 4 tests containing 232 assertions.
0 failures, 0 errors.
EXIT=0
```
Evidence count, corrected by the full-spec review: exactly TWO logged green runs exist on the
unmutated module with this revision — 14:23 UTC (`/tmp/baseline_run2.log`, after the Oracle
fixes) and 14:30 UTC (`/tmp/final_run.log`, 77 s, after all variant runs and restore). An
earlier claim of "three identical runs" is not supported by the logs: the third log
(`/tmp/baseline_run.log`, 14:12 UTC) is the PREVIOUS test revision (220 assertions, 1 failure:
`[600 0 619 0]` write attribution with threads=2 at 4 tasks). That failure is fixed by
launching with `:threads tasks`, which both later runs confirm. Observed distribution
histograms are in IMPLEMENTATION_VALIDATION.md.

The full-spec review then added two functional blocks (see coverage list) and re-ran the
suite twice: `/tmp/fsr_run1.log` (76 s) and `/tmp/fsr_run2.log` — each
`Ran 4 tests containing 258 assertions. 0 failures, 0 errors.` See FULL_SPEC_REVIEW.md.

### Mutation evidence
M1..M5 were completed earlier in this session against the previous test revision
(`/tmp/mutations.log`; module restored byte-identical after each). They are preserved, not
rerun; the assertions they tripped are unchanged in the corrected tests.

| Mutation | Suite | Result |
|---|---|---|
| M1 expiry boundary `>` instead of `>=` | functional | 10 failures (boundary, born-expired) |
| M2 retry overwrites recorded outcome | functional | 2 failures (first request wins) |
| M3 duplicate click counted | functional | 20 failures (dedup) |
| M4 blocked outranks deleted | functional | 12 failures (precedence) |
| M5 click count scans click history | performance | 6 failures (32 iterator reads > 8; growth) |
| M6 (old) writes funnel to one task | performance | compile error: NOT valid evidence, discarded |
| M6b centralized partition (this session) | full suite | 8 failures, 0 errors; functional 0 failures |

M6b: depot `(hash-by single-key)` with a top-level `(defn single-key [_] "all")`, and the
PState re-keyed as `{"all" (map-schema alias record {:subindex? true})}` with every
topology and foreign path prefixed by `"all"`, so writes and foreign reads are coherently
routed to one task. It compiles and every functional assertion passes (semantics intact).
Failures at both task counts (`/tmp/m6b_run.log`, 74 s): written entries `[0 1264]` and
`[0 1276 0 0]`, read work `[0 1600]` and `[0 1600 0 0]` (balance assertions at lines 212
and 220); incidentally `record-click!` also exceeded the 8-point-read ceiling (11) because
of the extra nesting level. Restored: `cmp` identical, `git diff --stat` empty.

### Bounded iterator-read alternative (accepted, not rejected)
Variant V1 replaced the click-id membership test with
`(subselect (sorted-set-range-from *click-id 1) ALL)` and `get-create-outcome` with a
`foreign-select` over `(sorted-map-range-from request-id 1) ALL`. Full suite:
`Ran 4 tests containing 232 assertions. 0 failures, 0 errors.` (`/tmp/v1_run.log`, 72 s).
Observed: `get-create-outcome` 1 point read + 1 iterator seek + 2 iterator reads at both 32
and 512; `record-click!` 5 point reads + 1 seek; duplicate 2 point reads + 1 seek + 1..2
iterator reads. Confirms the per-metric ceilings and growth allowance accept a bounded
iterator design. Restored: `cmp` identical.

### Limitations
- The event hook reports operation counts only. Storing a whole history as one opaque value
  is not detectable; rejected by review only (README states this).
- The distribution read-back uses `get-click-count` and `resolve-alias`; a design that
  serves those through iterators is accepted by the separate ceilings but was only
  demonstrated for `get-create-outcome` and click dedup in V1.
- `LeaderNotFoundException` lines in the logs are IPC shutdown noise, not test errors.

Decision: pass. Basis: two logged green runs of the unmutated module on the corrected 232-assertion
suite plus two green runs of the 258-assertion suite after the full-spec review; six valid
mutations (M1..M5, M6b) each caught by the intended assertions with functional semantics
isolated for the distribution mutation; iterator alternative accepted.
Outcome: BUILD phase complete for hld-url-shortener.

PHASE_VALIDATION:pass
