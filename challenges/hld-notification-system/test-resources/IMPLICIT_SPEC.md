# Implicit Spec

<!-- Phase 0 artifact for hld-notification-system. Requirements only; no design. -->

Domain: push notification core. Entities: **device** (user-id +
device-id), **preference** (user-id + category), **submission**
(submission-id, owned by a recipient user-id), **delivery** (submission-id
+ device-id), **dead letter** (per recipient, append-only). Time is
logical, supplied by callers. Harness runs 2 or 4 tasks and a second
client.

Ordering: sequential writes from one client to the same logical owner
(recipient user, or submission for attempt and receipt reports) process in invocation order; writes from different clients
serialize arbitrarily but a write that returned before a
`wait-for-processing!` barrier processes before any write issued after
it. Latency figures below are production aspirations, never acceptance
thresholds; tests enforce only bounded-work contracts.

## Operations

### register-device! [user-id device-id token]

- **Latency**: observable after wait.
- **Throughput**: low (app installs/refreshes), ~1,000/s.
- **Invariants**: ≤ 8 device-ids per user, ever; generation strictly
  increases by 1 per registration of the same device-id; registration
  always yields valid; processing retries must not bump the generation
  twice for one call (a duplicated bump would make an in-flight
  delivery's snapshot stale and mis-target `:invalid-token` handling).
- **Data growth**: bounded per user (8). Point lookup by user.
- **Concurrency**: registration racing a submit for the same user from
  another client: the submit snapshots whatever generation/token was
  current when it was processed. From one client, register then submit
  always snapshots the new generation.
- **Edge cases**: 9th distinct device (ignored; get-devices still 8);
  re-register with the identical token (generation still increments);
  re-register an invalidated device (valid again, generation +1);
  registering after the 8-limit for an EXISTING device-id (allowed,
  it is not new).

### set-preference! [user-id category enabled?]

- **Latency**: observable after wait.
- **Throughput**: low.
- **Invariants**: last write wins; default enabled when unset; only
  affects submissions processed after it.
- **Edge cases**: setting true for a never-set category (now explicitly
  present in get-preferences with true); toggling between submissions
  (each submit uses the value current at its processing).

### submit! [submission-id user-id category payload ttl now]

- **Latency**: observable after wait; production aspiration p99 < 5 s
  end to end (not enforced).
- **Throughput**: ~100,000/s peak, hot recipients possible.
- **Invariants**:
  - Global first-wins on submission-id; a later submit with the same id
    and different user-id/payload changes nothing and is not attributed
    to the second user.
  - Fan-out and snapshot are atomic with the submission record: no
    reader sees a `:dispatched` submission with missing deliveries.
  - Deliveries are created only for devices valid at processing time;
    snapshots never change afterwards.
  - Preference check precedes device check: disabled category with no
    devices → `:suppressed`.
  - expires-at = now + ttl exactly.
  - Processing retries must not create duplicate deliveries or a second
    entry in get-recent-submissions.
- **Data growth**: submissions per recipient up to 100,000; deliveries
  ≤ 8 per submission. Access: point by submission-id; recent page by
  recipient.
- **Concurrency**: two submits with the same id from different clients:
  exactly one recorded, whichever is processed first. Submits for one
  recipient interleaved with register/preference writes: from one
  client, invocation order; across clients, serialized in some order;
  each submit reflects the state at its processing.
- **Edge cases**: ttl = 1 (expires at now + 1; an attempt at now is
  processable, at now + 1 it expires the delivery); payload "" (empty
  string allowed); recipient with 8 devices of which 3 invalid → 5
  deliveries; category never set → enabled; same recipient 101+
  submissions → get-recent-submissions returns the latest 100.

### report-attempt! [submission-id device-id attempt-no outcome now]

- **Latency**: observable after wait.
- **Throughput**: up to ~300,000/s combined with receipts.
- **Invariants**:
  - Guard order is exactly: existence → pending → attempt number and
    due time → expiry → apply. A mismatched attempt number or an early
    tick wins over expiry: a stale or wrong report with a late
    timestamp cannot expire a delivery.
  - `:attempts` equals the number of applied attempt reports, strictly
    increasing 0→1→2→3; never skips, never repeats.
  - `:next-attempt-at` after applied transient failure n is
    now + (10 if n = 1, 20 if n = 2); nil in every non-pending state.
  - A delivery produces at most one dead letter, ever.
  - `:invalid-token` invalidates the device iff generations match; a
    device re-registered after the snapshot keeps its validity.
  - Processing retries must not apply an attempt twice (attempt-number
    guard makes a replayed report a no-op).
- **Data growth**: per delivery fixed. Dead letters per recipient up to
  100,000, append-only, read newest-first page of 100.
- **Concurrency**: two reports for the same delivery with the same
  attempt-no: exactly one applies, the other is a no-op. From one
  client the first invoked applies. Reports for different deliveries
  of one submission are independent.
- **Edge cases**:
  - Report attempt 1 at now < submitted-at (early; ignored since
    next-attempt-at = submitted-at).
  - Attempt 2 reported at exactly next-attempt-at (applies); one tick
    earlier (ignored).
  - Attempt 1 reported twice: second ignored; attempt 3 reported before
    2: ignored.
  - Attempt with the correct attempt-no, due, at now = expires-at
    with `:accepted` outcome: delivery expires; accepted is discarded.
  - Attempt with a wrong attempt-no (duplicate or out of order) at
    now ≥ expires-at: ignored; the delivery stays pending and is not
    expired.
  - Attempt with the correct attempt-no but now < next-attempt-at
    (possible past expiry when next-attempt-at > expires-at): ignored
    as early, not expired.
  - Transient failure at attempt 2 at now such that now + 20 ≥
    expires-at: delivery stays pending with that next-attempt-at; the
    next report with attempt-no 3 at now ≥ next-attempt-at expires it;
    any other report is ignored.
  - Report for a delivery already `:read` via receipt: ignored.
  - `:invalid-token` on delivery with generation 1 when device is at
    generation 2: delivery terminal, device stays valid.
  - `:invalid-token` when generations match: device invalid; a
    subsequent submit for that user produces no delivery for it.
  - Report for a `:suppressed` submission (no deliveries): ignored.

### record-receipt! [submission-id device-id receipt]

- **Latency**: observable after wait.
- **Invariants**: accepted only in states accepted, delivered, read;
  then monotone max over accepted < delivered < read; `:pending`
  receipts are ignored (not deferred); terminal states are absorbing;
  `:next-attempt-at` stays nil; `:attempts` unchanged.
- **Concurrency**: `:read` then `:delivered` (out of order) → `:read`.
- **Edge cases**: receipt before any attempt report (pending; ignored;
  a later `:accepted` report still applies and the delivery is then
  `:accepted`, not `:delivered`); receipt on `:expired` (ignored);
  receipt on `:accepted` → delivered/read; duplicate receipts (no
  change); receipt for nonexistent delivery (ignored); receipt on a
  pending delivery with 2 applied attempts (ignored; attempt 3 still
  possible).

### get-devices / get-preferences [user-id]

- **Latency**: production aspiration ~50 ms (not enforced); fixed work
  (≤ 8 devices, ≤ 16 categories) enforced.
- **Invariants**: never wait, never mutate; reflect the latest applied
  writes.
- **Edge cases**: unknown user → {}.

### get-submission [submission-id]

- **Latency**: production aspiration ~50 ms (not enforced); fixed read
  work (≤ 8 deliveries) enforced.
- **Invariants**: nil until the first-wins submission is processed;
  immutable header fields; delivery maps reflect the latest applied
  attempt/receipt; `:deliveries` is {} for `:suppressed` and
  `:no-devices`.

### get-recent-submissions [user-id]

- **Latency**: production aspiration ~50 ms (not enforced); work bounded
  by 100 regardless of history (enforced).
- **Invariants**: newest first in acceptance order; no duplicates; at
  most 100; each id appears exactly once for its recipient; a rejected
  retry does not re-order or duplicate.
- **Edge cases**: exactly 100, 101 submissions; user with none → [].

### get-dead-letters [user-id]

- **Latency**: production aspiration ~50 ms (not enforced); work bounded
  by 100 (enforced).
- **Invariants**: append-only; newest first; each (submission, device)
  at most once; `:at` is the tick of the report that dead-lettered it.
- **Edge cases**: 101+ entries → latest 100; `:invalid-token` and
  `:expired` never appear.

## Entity State × Write Matrix

### Entity: device (user-id + device-id)

States: **absent**, **valid(g)**, **invalid(g)**. Reads: `get-devices`,
`get-submission` (snapshots of later submits).

```
absent x register-device! (user has < 8 devices)
  - get-devices: {device-id {:token t :generation 1 :valid? true}}
  - get-submission(later submit): delivery with generation 1
absent x register-device! (user has 8 devices)
  - get-devices: unchanged (8 entries, no new one)
  - get-submission(later submit): no delivery for this device-id
absent x report-attempt! :invalid-token (delivery snapshot for a device-id
                                        that does not exist — impossible,
                                        deliveries only exist for devices)
valid(g) x register-device!
  - get-devices: generation g+1, new token, valid true
  - get-submission(existing dispatched): unchanged snapshot g
  - get-submission(later submit): snapshot g+1
valid(g) x report-attempt! :invalid-token on delivery with generation g
  - get-devices: valid? false, generation g
  - get-submission(that submission): delivery :invalid-token
  - get-submission(later submit): no delivery for this device
valid(g) x report-attempt! :invalid-token on delivery with generation < g
  - get-devices: unchanged (valid true)
  - get-submission(that submission): delivery :invalid-token
invalid(g) x register-device!
  - get-devices: generation g+1, valid true
invalid(g) x report-attempt! :invalid-token (any generation)
  - get-devices: unchanged (still invalid, generation g)
invalid(g) x submit! (for its user)
  - get-submission: no delivery for this device
```

### Entity: preference (user-id + category)

States: **unset** (enabled by default), **enabled**, **disabled**.
Reads: `get-preferences`, `get-submission`.

```
unset x set-preference! false
  - get-preferences: {category false}; later submit in category → :suppressed
unset x set-preference! true
  - get-preferences: {category true}; later submit → not suppressed
disabled x set-preference! true
  - later submit → not suppressed; earlier :suppressed submissions unchanged
enabled/unset x submit!
  - get-submission: :no-devices or :dispatched
disabled x submit!
  - get-submission: :suppressed, deliveries {}
  - get-recent-submissions: includes it
```

### Entity: submission (submission-id)

States: **absent**, **suppressed**, **no-devices**, **dispatched**.
Reads: `get-submission`, `get-recent-submissions`, `get-dead-letters`.

```
absent x submit!
  - get-submission: full map per rules; get-recent-submissions(user): id first
  - get-dead-letters: unchanged
suppressed/no-devices/dispatched x submit! (same id, any args)
  - get-submission: unchanged; get-recent-submissions: unchanged
suppressed/no-devices x report-attempt! / record-receipt!
  - get-submission: unchanged (no deliveries); get-dead-letters unchanged
dispatched x report-attempt! / record-receipt!
  - see delivery matrix; header fields unchanged
any x register-device! / set-preference!
  - get-submission: unchanged (snapshots and status are frozen)
```

### Entity: delivery (submission-id + device-id)

States: **pending(n)** with n applied attempts 0..2 and a due tick,
**accepted**, **delivered**, **read**, **failed**, **expired**,
**invalid-token**. Reads: `get-submission` (delivery map),
`get-dead-letters`, `get-devices`.

```
pending(n) x report-attempt! (attempt-no != n+1 or now < due, any now)
  - get-submission: unchanged (never expired by a stale report)
pending(n) x report-attempt! (n+1, due, now >= expires-at, any outcome)
  - get-submission: state :expired, attempts n, next-attempt-at nil
  - get-dead-letters: unchanged
pending(n) x report-attempt! (n+1, due, :accepted)
  - get-submission: :accepted, attempts n+1, next nil
pending(0|1) x report-attempt! (n+1, due, :transient-failure)
  - get-submission: :pending, attempts n+1, next = now + (10 | 20)
pending(2) x report-attempt! (3, due, :transient-failure)
  - get-submission: :failed, attempts 3, next nil
  - get-dead-letters: new first entry {:reason :retries-exhausted :at now}
pending(n) x report-attempt! (n+1, due, :permanent-failure)
  - get-submission: :failed, attempts n+1
  - get-dead-letters: new first entry {:reason :permanent-failure :at now}
pending(n) x report-attempt! (n+1, due, :invalid-token)
  - get-submission: :invalid-token, attempts n+1
  - get-devices: device invalid iff current generation == snapshot
  - get-dead-letters: unchanged
pending(n) x record-receipt! :delivered / :read
  - get-submission: unchanged (:pending, attempts n, next unchanged)
accepted x record-receipt! :delivered / :read
  - get-submission: :delivered / :read
accepted x report-attempt! (any)
  - get-submission: unchanged
delivered x record-receipt! :delivered
  - unchanged
delivered x record-receipt! :read
  - :read
read x record-receipt! (any)
  - unchanged
failed/expired/invalid-token x record-receipt! (any)
  - unchanged
failed/expired/invalid-token x report-attempt! (any)
  - unchanged; get-dead-letters unchanged (never a second entry)
any x register-device! for the same device
  - get-submission: delivery snapshot unchanged
```

### Entity: dead-letter log (per recipient)

States: **empty**, **non-empty**. Reads: `get-dead-letters`.

```
any x report-attempt! producing :failed
  - get-dead-letters: entry prepended (newest first), page capped at 100
any x other writes
  - get-dead-letters: unchanged
```
