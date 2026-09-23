# Plan Validation

<!-- Phase 2 artifact for hld-rate-limiter. Adversarial review of PLAN.md against README.md, protocol.clj, IMPLICIT_SPEC.md. -->

## Query topology
None. `get-decision`, `get-config`, `get-status` are each one `foreign-select-one` on
`hash(user)`; N == M == 1 for every example (PLAN Reads table). PASS.

## PState schemas
- One PState `$$users`; nothing else shares (String user, hash(user)). PASS.
- No `Object`. `:limiter`, buckets, decisions are `fixed-keys-schema`; nullable `:reason`,
  `:config-version`, `:remaining` on one shared shape (allowed). PASS.
- `:endpoints` / `:endpoint-buckets` ≤16 by input grammar (README table) → inline.
  `:decisions` ≤1,000,000/user → subindexed. PASS.

## Partitioning
- All writes on `hash(user-id)`: 5M users; hot user thousands/s must serialize on one clock anyway;
  storage per user ≤ 2 KB + subindexed decisions. PASS.
- No `|all`. Tables for N = 1/16/128, proportions 0.85+0.10+0.05 = 1.0, weighted seeks 1.0,
  totals across tasks, flat in N. Global `$$decisions` alternative costed (2 hops on 1M/s). PASS.

## Topologies
Microbatch only; decisions are read after the barrier, no ack return needed. No stream, no
observability rationale. PASS.

## Production readiness
- Concurrent clients: same-user records serialize on one depot partition; spec accepts any serial
  order and requires the recorded decisions to be exactly that order — one task, no hops. PASS.
- Client restart: no business state in wrapper. PASS.
- Worker restart: microbatch exactly-once plus `decisions[rid]` guard (no double debit) and
  `version <= current` guard (no double reset). PASS.
- Scale: subindexed decisions; limiter inline bounded by grammar. PASS.

## Internal depots / cross-topology / stream / TaskGlobals
None. PASS.

## Minimality
Sketch: one depot by user, one microbatch topology, one PState with limiter record + decision map.
Plan equals sketch. `:limiter` inline record: delete → two-bucket atomicity ("both buckets are
debited or neither") needs two writes. `:decisions`: delete → cannot replay ("first decision ...
replayed for every retry"). `:remaining` stored: derivable at decision time only; decision map
"never changes once recorded" while buckets move. PASS.

## Throughput
`check!` = 2 seeks (decision guard, limiter) + ≤2 writes, one hop. A 1-seek design would need the
decision map and limiter in one value, making the value grow with 1,000,000 decisions — violates
fixed work. Reads 1 seek. PASS.

## Spec coverage

### set-config! versioning and atomic reset
- Source: "version strictly greater ... replaces the config and resets every bucket"; "Versions
  less than or equal ... ignored entirely"; "The user clock is unchanged."
- Trace: u1 v1 {user cap 10 refill 1, e1 cap 5 refill 0} → limiter {v1, clock 0, buckets full,
  at 0}. Debit 3 at T=7 → user tokens 7 at 7, clock 7. v1 again (retry) → no-op, tokens stay 7.
  v2 {e2 only, user cap 20} → one `termval`: v2, clock 7 kept, user 20 full, e2 full, e1 gone;
  later `check! e1` → `:unknown-endpoint`. PLAN Writes row `set-config!`. PASS.

### check! rules 1–5, monotone clock, atomic debit
- Source: protocol rules 1–5; "clock advances to T only when the decision debits".
- Trace (u1 v1 above, fresh): r1 e1 cost 5 now 3 → T=3, au=10, ae=5 → allow, user 5 at 3,
  e1 0 at 3, clock 3, remaining {5 0}. r2 e1 cost 1 now 1 → T=max(1,3)=3, ae=0 → denied
  `:insufficient-tokens`, remaining {5 0}, nothing written but the decision, clock 3. r3 e1 cost 6
  now 50 → cost > e1 cap 5 → `:cost-exceeds-capacity`, no debit, clock still 3. r1 retry with
  now 99 → recorded → no-op. No-config user → `{:allowed false ... :config-version nil
  :remaining nil}` with T = now (clock 0). Shadow config: same accounting, `:allowed true`
  except `:no-config`. Overshoot: user 5 at 3, now 100 → min(10, 5+97) = 10. PASS.
- Faults: replay → rule-1 guard; single partition, one `termval` for both buckets. PASS.
- Races: two clients, same user, same tick → sequential on one task; second sees first's debit
  with zero refill (`T - at = 0`). PASS.

### get-status purity
- Source: "WITHOUT changing the clock or any bucket".
- Trace: read `:limiter`, compute `available(max(now, clock))` in wrapper; repeated calls equal.
  Unknown endpoint → nil; no config → nil. PASS.

### get-decision / get-config
1 seek; nil until processed; immutable; `get-config` echoes stored `:config` verbatim. PASS.

### Ordering, barrier, 2/4 tasks
One depot partition per user; barrier by shared cumulative counter; task count irrelevant. PASS
after fix 1.

## FAIL items (localized; fixed directly in PLAN.md)
1. **Sync counter semantics.** Increment after a successful `foreign-append!` (before the method
   returns), not before the call — a failed append must not be counted.
2. **Output shape.** Decision maps carry six keys with explicit nils; `get-config` returns
   `{:version :config}` equal to the installed input; `get-status` four keys. Fix: wrapper
   constructs each map explicitly rather than returning the stored record as-is.
3. **Bucket `at` after reset.** Plan writes `at 0` on reset; `available(T)` then adds `T × refill`
   to a full bucket — harmless because of the `min(capacity, …)` clamp, but the plan must say so
   (protocol: "A freshly reset bucket has tokens = capacity (its `at` is then irrelevant)").

## Decision / Basis / Outcome
- Decision: minor-fail. Basis: inline limiter record on `hash(user)` delivers both-or-neither debit
  and config+reset atomicity with one `termval`; every protocol rule traces correctly. Outcome:
  PLAN.md edited for items 1–3; proceed to build.

PHASE_VALIDATION:minor-fail
