# Implicit Spec

<!-- Phase 0 artifact for hld-rate-limiter. Requirements only; no design. -->

Domain: per-user token-bucket limiter. Entities: **user limiter**
(keyed by user-id: config, version, clock, user bucket, endpoint
buckets), **decision** (keyed by user-id + request-id). Time is logical,
supplied by callers, clamped monotone per user. Harness runs 2 or 4
tasks and a second client.

Ordering: sequential writes from one client to the same logical owner
(user) process in invocation order; writes from different clients
serialize arbitrarily but a write that returned before a
`wait-for-processing!` barrier processes before any write issued after
it. Latency figures below are production aspirations, never acceptance
thresholds; tests enforce only bounded-work contracts.

## Operations

### set-config! [user-id version config]

- **Latency**: observable after wait; not latency critical.
- **Throughput**: rare (< 10/s fleet-wide).
- **Invariants**:
  - Applied iff version > current version (first config: any version
    ≥ 1 applies). Equal version is a retry and must not reset buckets.
  - On apply, every bucket is full for its new capacity; the user
    clock is unchanged; previously recorded decisions are unchanged.
  - Config and buckets change together: no reader may observe the new
    config with old bucket balances or vice versa.
  - Processing retries must not reset buckets twice (a second reset
    after debits would refund tokens).
- **Data growth**: 5M users; config ≤ 16 endpoints; point lookup by
  user.
- **Concurrency**: config change racing checks for the same user from
  another client: serialized. Each check uses whichever config was
  current when it was processed; its decision records that version.
  From one client, a config then a check always sees the new config.
- **Edge cases**: version 1 after none; version decreases (ignored);
  same version different content (ignored); config that removes an
  endpoint (later checks on it → `:unknown-endpoint`); config that
  changes capacity of an existing endpoint (bucket becomes full at the
  NEW capacity); config with refill 0 (bucket never refills; once
  drained only a new version restores it); shadow flag toggled by a new
  version (past decisions keep their `:allowed`).

### check! [user-id request-id endpoint cost now]

- **Latency**: not sub-ms; decision observable after wait.
- **Throughput**: ~1M/s fleet-wide; hot users thousands/s. Dominant
  write.
- **Invariants**:
  - Exactly one decision per (user, request-id), ever. Retries (same or
    different args) are no-ops: no debit, no clock advance.
  - Both-or-neither debit across the user bucket and the endpoint
    bucket. Never a state where one is debited and the other is not.
  - Debit only when would-allow is true; shadow mode does not change
    accounting, only `:allowed`.
  - Effective tick is monotone per user: T = max(now, clock) is
    evaluated and recorded for every new check, but the clock advances
    to T only for would-allow true (debiting) decisions, enforcing or
    shadow. A check with a smaller `now` than the clock is evaluated at
    the clock (no time travel; no negative elapsed).
  - A would-allow false decision (any reason) records its map and has
    no other effect: buckets and clock unchanged.
  - `:remaining` reports post-decision availability at T.
  - Processing retries must not double-debit.
- **Data growth**: decisions per user up to 1,000,000; must be point
  addressable by (user, request-id). Bucket state is O(endpoints).
- **Concurrency**: checks for one user from one client process in
  invocation order; from different clients they are serialized in some
  order. The sum of debits equals cost × number of would-allow true
  decisions; tokens never go negative. Checks for different users are
  independent.
- **Edge cases**:
  - First ever check for a user with config: buckets full → allowed if
    cost ≤ both capacities.
  - cost equal to available (allowed, remaining 0).
  - cost = available + 1 (denied, nothing debited).
  - Refill overshoot: elapsed × refill exceeding capacity → clamped to
    capacity.
  - Two requests at the same tick T: the second sees the first's debit
    with zero refill.
  - User bucket sufficient, endpoint bucket insufficient (denied, user
    bucket NOT debited) and vice versa.
  - cost > endpoint capacity but ≤ user capacity → `:cost-exceeds-capacity`
    with no debit.
  - Check before any config → `:no-config`; a later config does not
    re-evaluate it; the same request-id after config → still `:no-config`
    (recorded decision replays).
  - Denied checks (`:no-config`, `:unknown-endpoint`,
    `:cost-exceeds-capacity`, `:insufficient-tokens`) do not advance
    the clock: a later check with a smaller `now` is evaluated at the
    clock as set by the last debiting decision, not by the denial.
  - A denied check at a large `now` followed by a debiting check at a
    smaller `now`: the second uses T = max(its now, clock), where the
    clock still reflects only earlier debits.
  - Shadow: `:allowed true`, `:would-allow false`, `:reason
    :insufficient-tokens`, no debit.
  - Shadow with `:unknown-endpoint` → `:allowed true`; with `:no-config`
    → `:allowed false` (no config means no shadow flag).
  - Same request-id under two different users: independent decisions.

### get-decision [user-id request-id]

- **Latency**: production aspiration ~50 ms (not enforced); fixed work (enforced).
- **Invariants**: nil until processed; then immutable. Never waits or
  mutates.
- **Edge cases**: request-id known for another user only (nil).

### get-config [user-id]

- **Latency**: production aspiration ~50 ms (not enforced); fixed work.
- **Invariants**: reflects the highest version applied; nil before any.
- **Edge cases**: after an ignored lower version → unchanged.

### get-status [user-id endpoint now]

- **Latency**: production aspiration ~50 ms (not enforced); fixed work (enforced).
- **Invariants**: pure projection; must not advance the user clock or
  store refilled tokens. `available` is computed at
  T = max(now, clock). Consecutive calls with the same args return the
  same map when no write intervened.
- **Edge cases**: now < clock (uses clock); endpoint removed by newer
  config (nil); full bucket (available = capacity for any T); refill 0
  (available constant).

## Entity State × Write Matrix

### Entity: user limiter (keyed by user-id)

States: **unconfigured** (no config, clock may be > 0 from checks),
**configured-enforcing**, **configured-shadow**. Sub-state: bucket
balances and clock. Reads: `get-decision`, `get-config`, `get-status`.

```
unconfigured x set-config! (version v)
  - get-config: {:version v :config c}
  - get-status: all buckets available = capacity at T = max(now, clock)
  - get-decision(previous ids): unchanged (:no-config decisions stay)
unconfigured x check! (new request-id)
  - get-decision: {:allowed false :would-allow false :reason :no-config
                   :tick T :config-version nil :remaining nil}
  - get-config: nil
  - get-status: nil; clock unchanged (denied decisions never advance it)
unconfigured x check! (recorded request-id)
  - get-decision: replay; clock unchanged; get-config nil; get-status nil

configured-enforcing x set-config! (version > current)
  - get-config: new; get-status: buckets full at new capacities,
    removed endpoints → nil; get-decision: all unchanged
configured-enforcing x set-config! (version <= current)
  - get-config / get-status / get-decision: unchanged
configured-enforcing x check! (new id, endpoint known, sufficient)
  - get-decision: allowed true, would-allow true, reason nil,
    remaining post-debit
  - get-status: both buckets reduced by cost at T; other endpoints untouched;
    clock = T
  - get-config: unchanged
configured-enforcing x check! (new id, insufficient in either bucket)
  - get-decision: allowed false, reason :insufficient-tokens,
    remaining = availabilities at T
  - get-status: unchanged balances; clock unchanged
configured-enforcing x check! (new id, cost > a capacity)
  - get-decision: reason :cost-exceeds-capacity; get-status unchanged;
    clock unchanged
configured-enforcing x check! (new id, unknown endpoint)
  - get-decision: allowed false, reason :unknown-endpoint, remaining nil,
    config-version v; get-status unchanged; clock unchanged
configured-enforcing x check! (recorded id, any args)
  - get-decision: replay; get-status unchanged; clock unchanged

configured-shadow x check! (new id, sufficient)
  - get-decision: allowed true, would-allow true; get-status debited;
    clock = T
configured-shadow x check! (new id, insufficient)
  - get-decision: allowed true, would-allow false, :insufficient-tokens;
    get-status unchanged; clock unchanged
configured-shadow x check! (new id, unknown endpoint)
  - get-decision: allowed true, would-allow false, :unknown-endpoint
configured-shadow x check! (new id, cost > capacity)
  - get-decision: allowed true, would-allow false, :cost-exceeds-capacity
configured-shadow x set-config! (newer, shadow? false)
  - get-config: enforcing; earlier shadow decisions unchanged
```

### Entity: decision (keyed by user-id + request-id)

States: **unrecorded**, **recorded**. Reads: `get-decision`, `get-status`.

```
unrecorded x check!
  - get-decision: the map computed by the rules; get-status: debited iff
    would-allow; clock advanced iff would-allow
recorded x check! (any args)
  - get-decision: unchanged; get-status: unchanged; clock unchanged
recorded x set-config!
  - get-decision: unchanged (history survives config changes)
```

### Entity: bucket (user bucket or endpoint bucket of a user)

States: **absent** (no config / endpoint not configured), **full**,
**partial** (0 ≤ tokens < capacity). Reads: `get-status`,
`get-decision` (`:remaining`).

```
absent x set-config! (introduces it)
  - get-status: available = capacity
full x check! allowed at T
  - get-status at T: capacity - cost; at T + k: min(capacity, capacity - cost + k*refill)
partial x check! denied
  - get-status: unchanged balance, computed at max(now, clock)
partial x set-config! newer
  - get-status: available = new capacity (full)
partial x set-config! same/older
  - get-status: unchanged
any x check! (recorded request-id)
  - get-status: unchanged
```
