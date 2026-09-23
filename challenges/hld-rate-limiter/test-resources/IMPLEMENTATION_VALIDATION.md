# Implementation Validation

<!-- Phase 4 artifact for hld-rate-limiter (BUILD completion, September 23, 2026).
     Reviewed: test-resources/hld_rate_limiter/module.clj against PLAN.md, README.md,
     protocol.clj and IMPLICIT_SPEC.md. Line numbers refer to module.clj as checked in at
     21e6676; the file was not changed by this phase (md5 705cd4a10c867b9e17a50b5b0a52aaf3
     before and after every step below). -->

## Redundant conditionals
Check: every branch of an `<<if` doing the same operation with only a variable differing.
- `SetConfig` (130-135): one `<<if (some? *new-limiter)` with a single write in the true branch
  and nothing in the false branch.
- `Check` (139-149): outer `<<if (nil? *recorded)` guards the whole evaluation; the decision
  write (144-146) is unconditional inside it; the inner `<<if (some? *new-limiter)` (147-149)
  guards only the limiter write. No branch duplicates another. PASS.

## Consecutive keypath
Check: `(keypath *a) (keypath *b)`. Every path is one `keypath` with all segments inline:
`(keypath *user-id :limiter)` (131, 134, 142, 148), `(keypath *user-id :decisions *request-id)`
(140, 144), and the three foreign paths (193, 195, 198). PASS.

## Select-compute-transform
Check: `local-select>` → compute → `local-transform>` with `termval` replaceable by an aggregator.
- `SetConfig`: `*limiter` must be read to compare versions and to keep the clock (35-45); the
  write is the whole new limiter value. An aggregator would still need the read. PASS.
- `Check`: `*limiter` is read once (142) and `evaluate-check` (47-97) derives both the decision
  and the debited limiter from that single value; the two `termval`s reuse it, no re-read. PASS.

## Unnecessary nil->val
No `nil->val` anywhere in the module. `new-limiter` (38) and `evaluate-check` (52-58) handle a
nil limiter explicitly because the protocol needs a distinct `:no-config` outcome. PASS.

## :allow-yield?
The only navigations into the subindexed `:decisions` map are point lookups by request-id
(140, 193). No `ALL`, `MAP-VALS`, range or other iterating navigator anywhere. PASS.

## Non-subindexed collections without size limits
Schema (104-122): `:decisions` is `(map-schema String ... {:subindex? true})` (≤1,000,000 per
user). `:config :endpoints` and `:endpoint-buckets` are inline maps bounded to 16 entries by
the input grammar (README table, "1..16 endpoints"); `new-limiter` (43-45) builds
`:endpoint-buckets` only from the config's endpoints, so it can never exceed that bound. PASS.

## Stream topology idempotency
No stream topology; the only topology is microbatch `core` (103). Replay is exactly-once.
Additionally each write is read-guarded: version strictly greater (38), decision absent (140-141),
so re-executing an event is a no-op. PASS.

## Partial failure in stream topologies
No stream topology. Every event's writes are on one task in one microbatch transaction. PASS.

## Single depot append per client operation
`append!` (182-185) performs exactly one `foreign-append!`; `set-config!` and `check!` (187-190)
call it once. The `swap! counter inc` runs only after the append returned and is harness
synchronization, not a depot write. PASS.

## Application-state caches survive restart
No TaskGlobals, no caches. The wrapper holds `ipc`, foreign handles and the sync counter. PASS.

## No reimplementation of built-in operations
`available`, `full-bucket`, `new-limiter`, `evaluate-check` (26-97) are pure domain functions
of the protocol's bucket model, not duplicates of Rama ops. PASS.

## Plan conformance
PLAN.md vs module, item by item:
- Depot `*user-events` `(hash-by :user-id)`, records `SetConfig`/`Check` (20-21, 101): matches.
- One microbatch topology `core` owning `$$users` with the planned schema, `:decisions`
  subindexed (103-122): matches.
- `set-config!`: read `:limiter`, no-op when `version <= current`, else one `termval` of the
  whole limiter with clock kept and every bucket full at `at 0` (35-45, 130-135): matches,
  including the Phase-2 `at 0` note (clamp in `available`, 26-29).
- `check!`: decision lookup first (140), then limiter read (142), pure rules 2-5 (47-97),
  decision write (144-146), limiter write only when debiting (147-149): matches the Writes
  table, 2 seeks and ≤2 writes.
- Reads: three `foreign-select-one` point reads (193, 195, 198); `get-status` is a pure
  projection in the wrapper (162-173); every returned map is constructed explicitly with all
  keys (151-173, 196): matches the Phase-2 output-shape fix.
- Synchronization: counter created once in `create-module` (207), incremented after a
  successful append (184), `wait-for-microbatch-processed-count` on `core` (203): matches.
No divergences. PASS.

## Spec checks beyond the plan (Oracle review items)
- User-only over-capacity: line 77 tests `cost > user capacity` OR `cost > endpoint capacity`
  before the sufficiency test, so cost 3 against user capacity 2 / endpoint 5 is
  `:cost-exceeds-capacity` with remaining `{2 5}`, and nothing is written but the decision
  (second element nil, 81). Unknown endpoint in enforcing mode: `:allowed` is
  `(boolean shadow?)` = false, `:remaining nil` (61-64). PASS.
- Shadow denials at future ticks: `t = max(now, clock)` (52); `au`/`ae` are projected at `t`
  (72-73) and reported in `:remaining` (80, 95); every denial returns nil as the new limiter
  (81, 97), so the clock and stored balances are untouched. The debit branch is the only one
  that writes `:clock t` and the two buckets (85-91). PASS.
- Changed-body retries across versions: the recorded-decision lookup (140) precedes every
  evaluation and covers denied decisions too (`nil?` test, 141, not a `:would-allow` test);
  `new-limiter` replaces only `:limiter` (134) and never touches `:decisions`, so a retried id
  after a version change replays the old map and debits nothing. PASS.

## Verdict
pass — every check above passed by code trace; the implementation matches the validated plan and
the spec with no divergence.

Decision: pass. Basis: all checks traced with line citations; no gap acknowledged anywhere above.
Outcome: proceed to test validation; module unchanged.

## Observed RocksDB work (evidence run, `clojure -J-Xmx1600m -M:test-private-harness -i /tmp/rl_evidence.clj`, 14:49 UTC)
Identical at 2 and 4 tasks and at 32 and 512 recorded decisions; no iterator seeks or iterator
reads anywhere; growth from 32 to 512 is zero on every metric.

| Operation | point reads | written entries |
|---|---|---|
| get-decision (earliest, latest, missing) | 2 | 0 |
| get-config | 1 | 0 |
| get-status | 1 | 0 |
| check! new (write + barrier) | 6 | 2 |
| check! retried id | 2 | 0 |
| check! denied (cost > endpoint capacity) | 6 | 2 |

Distribution (400 users, set-config!+check! through the same wrapper, alternating wrappers):
written entries per task [421 404] at 2 tasks, [192 211 227 187] at 4 tasks; read work per task
[615 585] and [285 306 330 279]; read totals 1200 point reads, 0 writes. Every task within
0.5x..1.5x of the mean.

PHASE_VALIDATION:pass
