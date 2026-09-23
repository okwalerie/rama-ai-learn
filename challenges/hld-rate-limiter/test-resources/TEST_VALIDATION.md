# Test Validation

<!-- Phase 6 artifact for hld-rate-limiter (BUILD completion, September 23, 2026).
     Reviewed: test-private/hld_rate_limiter/functional_test_support.clj (F),
     performance_test_support.clj (P) and the two *_challenge_test.clj entrypoints, against
     protocol.clj, README.md, IMPLICIT_SPEC.md and ORACLE_IMPLEMENTATION_REVIEW.md. -->

## Minimize IPC launches
Four `deftest`s: functional and performance, each at 2 and 4 tasks. The task count is a launch
parameter, so 2-vs-4 cannot share an IPC. Functional and performance are separate because the
performance suite launches with `:threads tasks` (per-task attribution of RocksDB events) and
wraps specific operations in event hooks that must not see unrelated functional traffic. Every
functional scenario lives in one `deftest` per task count on disjoint users. PASS.

## Implicit spec coverage
Each IMPLICIT_SPEC edge case / entity-state × write cell and the `testing` block (file) that
exercises it with a hand-derived expected value:

- Write methods return nil — "write methods return nil" (F).
- unconfigured × check (new id) `:no-config` with T = now; reads nil; id known for another user
  only — "no config: reads are nil..." (F).
- unconfigured × set-config: full buckets, clock untouched, earlier `:no-config` decision replays
  on retry after config — "first config: full buckets..." (F).
- Allowed debit: both buckets and clock; now < clock uses clock; other endpoint untouched; refill
  projection; status purity — "allowed debit sets both buckets and the clock" (F).
- Endpoint insufficient, user not debited — "insufficient endpoint tokens" and "same tick" (F).
- cost > endpoint capacity ≤ user capacity at a large now, clock unchanged — "cost above endpoint
  capacity..." (F).
- cost > user capacity while the endpoint could pay; enforcing unknown endpoint allowed=false,
  remaining nil; clock 0 and full buckets after each — "user-only over-capacity" (F, Oracle 1).
- Debit at a smaller now uses the clock — "debit at a smaller now uses the clock" (F).
- Retry with different args from both clients is a no-op — "retry of a recorded request-id..." (F).
- Refill overshoot clamps; refill 0 never refills — "refill overshoot clamps..." (F).
- Same tick second request sees the first debit — "same tick..." (F).
- User insufficient, endpoint not debited; clock unchanged by denial — "endpoint sufficient, user
  insufficient" (F).
- cost = available allowed with remaining 0; cost + 1 denied — "cost equal to available..." (F).
- Equal version different content ignored — "equal version with different content is ignored" (F).
- Newer version: reset, removed endpoint nil, clock kept, history survives, lower version ignored,
  same-client config-then-check without barrier — "newer version replaces config..." (F).
- Shadow: insufficient / unknown-endpoint / cost-exceeds / smaller-now denials all allowed=true,
  no debit, clock 2000; `:no-config` never shadowed — "shadow mode..." (F).
- Shadow denials at future ticks: projected `:remaining` at T=11 and T=1000, cost above user
  capacity, unknown endpoint; status at 0 stays clock 10 with stored 1/3 after EACH —
  "shadow denials at future ticks" (F, Oracle 2).
- Shadow → enforcing keeps old decisions; refill 0 drains until a new version; new version
  restores full — "back to enforcing..." (F).
- Changed-body retry queued from the same wrapper before the barrier debits only once; denied
  decision recorded without clock advance; v2 shadow config retaining only `b` resets full and
  keeps clock 10; payable retries of both ids on `b` from the other wrapper replay both original
  maps (including the denied one) and leave buckets full; a fresh id then debits from full —
  "changed-body retries across a config version" (F, Oracle 3).
- Users independent — "users are independent" (F).
- One-client invocation order across check/config/check/config/check without barriers —
  "one client: check, config, check..." (F).
- Two clients on one user: debits sum exactly, tokens never negative, denied maps exact —
  "two clients on one user" (F).
- Bounded read/write work at 32 vs 512 decisions, independently derived results at both sizes,
  retry and denial at both sizes with clock checks — "read and write work is bounded..." (P).
- Observed RocksDB work balanced across tasks for 400 users — "observed RocksDB work is
  distributed across tasks with many owners" (P).

All IMPLICIT_SPEC cells map to a cited block. PASS.

## Synchronization
Every write-then-read sequence in F and P is separated by `sync-a!` / `sync-b!`, which call
`harness/wait-for-processing!` through the OTHER wrapper. Deliberate no-barrier bursts (same-tick
pair, changed-body pair, one-client ordering, hot user) place the barrier after the burst and
before any read. Build-history calls in P are followed by both barriers. PASS.

## Test namespaces compile
Both support namespaces require only `clojure.test`, `com.rpl.rama.test`, the protocol and
`rama-challenges.harness`; entrypoints resolve `create-module` via `requiring-resolve`. No record
constructors referenced. Compiled and executed in the runs below. PASS.

## Architecture neutrality of the performance suite (Oracle "known performance repairs")
- No `:depot-read` dependence, no exact 800-record count, no `:local-transform` counting: only
  RocksDB-level events on non-internal PStates (`:rocks-read`, `:rocks-iterator`,
  `:rocks-iterator-read`, `:rocks-commit`). Stream and microbatch designs measure identically.
- Fixed ceilings (`read-budget` with `:writes 0`, `write-budget`) plus `growth-allowance` from 32
  to 512 replace exact small/large equality. Published in README "Enforced work budgets".
- Earliest (`req-0`), latest (`req-(n-1)`) and missing (`req-n`) decision lookups are each
  executed INSIDE `capture-ops` at both 32 and 512 (P, `doseq` over five labelled read-fns), in
  addition to the independently derived value assertions outside capture.
- Retried and denied `check!` are each captured at both history sizes, with value and clock
  assertions after each.
- Launch uses `:threads tasks`; distribution uses `per-task-ops` for written entries and aggregate
  read work (point reads + iterator seeks + iterator reads), 0.5x..1.5x band, per-metric
  `distribution-ceiling` multiples of the user count, reads must write nothing.
- Each owner's `set-config!` and `check!` go through the SAME wrapper (one-client ordering);
  owners alternate between wrappers.
- Hook accumulation uses `swap!` so concurrent task-thread events are not lost.
- The hook carries no value-size or byte field; no such counter was invented. README states the
  opaque-history limitation as a review-only rejection. PASS.

## Verdict
pass — every check above passed by explicit walk-through with the covering `testing` block cited.

Decision: pass. Basis: every implicit-spec cell and every Oracle item maps to a cited block with an
independently derived expectation; barriers precede every read; the performance suite depends on
no topology or layout. Outcome: run and mutation evidence below.

## Execution (from `challenges/hld-rate-limiter`, unmutated module)
Baseline on this test revision, before any mutant (`/tmp/rl_baseline_run1.log`, 14:47 UTC):
```
clojure -J-Xmx1600m -X:test-private-harness
Ran 4 tests containing 306 assertions.
0 failures, 0 errors.
real 1m9.3s   EXIT=0
```
The final run after all mutants were restored is recorded in the section at the end.

## Mutation evidence
Each mutant edited `test-resources/hld_rate_limiter/module.clj`, ran the named namespace only,
and was then restored by copying the saved baseline; `cmp` reported identical after every run
(md5 705cd4a10c867b9e17a50b5b0a52aaf3). Runs that failed to compile are listed and discounted.

| Mutant | Suite | Result | Assertions tripped |
|---|---|---|---|
| M1 drop the user-capacity condition (`:cost-exceeds-capacity` tests endpoint only) | functional | 4 failures, 0 errors (`/tmp/rl_M1_drop_user_capacity.log`) | F:245 user-only over-capacity `x1`; F:267 shadow cost-5-at-1000 `s2` (became `:insufficient-tokens`); at 2 and 4 tasks |
| M2 shadow denial persists projected balances and advances the clock (`:allowed` used instead of `:would-allow`) | functional | 6 failures, 0 errors (`/tmp/rl_M2_shadow_denial_persists.log`) | F:264, 269, 273 status-at-0 must stay clock 10 / 1/3 after each shadow denial; at 2 and 4 tasks |
| M3 (first two attempts) `(or (nil? *recorded) (not (:would-allow *recorded)))` and `(get ...)` inline in `<<if` | functional | compile error (`let*` unresolved): NOT valid evidence, discarded (`/tmp/rl_M3_*.log`, `/tmp/rl_M3b_*.log`) | — |
| M3c denied decisions treated as absent via `(defn cached-decision? [d] (and d (:would-allow d)))` guard | functional | 64 failures, 0 errors (`/tmp/rl_M3c_denied_cache_absent.log`) | F:299 payable retry of the denied id re-evaluated in shadow, F:302 buckets no longer full, F:305/312 downstream; plus every earlier block whose denied ids were re-evaluated (F:96 onward); at 2 and 4 tasks |
| M4 duplicate lookup scans the whole history (`(keypath *user-id :decisions) (subselect ALL)` then `get`) | performance | 21 failures, 0 errors (`/tmp/rl_M4_history_scan.log`) | P:182/184/186 new check budget and growth (observed 32/33 iterator reads at 32 decisions, far more at 512), P:202-204 retry, P:224-226 denied; also P:154/155 values at 4 tasks because the yielding scan reordered same-batch processing |

Observed M4 count at 32 decisions: `{:reads 5 :iterators 1 :iterator-reads 32 :writes 2}` vs the
ceiling of 8 iterator reads; the growth allowance of 4 iterator reads was also exceeded at 512.

## Limitations
- The event hook reports operation counts only. Storing a user's whole decision history as one
  opaque value is not detectable by these tests; rejected by review only (README states this).
- `LeaderNotFoundException` lines in the logs are IPC shutdown noise, not test errors.
- The M3 mutant needed a top-level helper because Rama dataflow rejects `or` inline in `<<if`;
  the two compile-error attempts are recorded, not counted.

## Final run (after all mutants restored; module `cmp` identical to baseline)
`/tmp/rl_final_run.log`, 14:55 UTC:
```
clojure -J-Xmx1600m -X:test-private-harness
Ran 4 tests containing 306 assertions.
0 failures, 0 errors.
real 1m7.0s   EXIT=0
```

Decision: pass. Basis: two logged green runs of the unmutated module on the 306-assertion suite
(before and after the mutants); four valid mutants (M1, M2, M3c, M4) each caught by the intended
Oracle or budget assertions at both task counts; two compile-error attempts discounted.
Outcome: BUILD phase complete for hld-rate-limiter.

PHASE_VALIDATION:pass
