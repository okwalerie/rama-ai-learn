# Plan — hld-ticketing-system (subsystem: seat-inventory, whole spec)

Private reference-implementation plan (Phase 1). Inputs: README.md,
`src/hld_ticketing_system/protocol.clj`, `test-resources/IMPLICIT_SPEC.md`,
`test-resources/DECOMPOSITION.json`. Design only; no topology code.

## Reads

All reads are scoped by `event-id`, the depot partition key and top-level
PState key, so every read routes to exactly one task.

| Read | Access method | Path / query | RocksDB reads |
|---|---|---|---|
| `get-outcome ev rid` | `foreign-select-one` | `[(keypath ev :requests rid)]` on `$$events` → request record or nil; client renders the outcome map | 2 |
| `get-clock ev` | `foreign-select-one` | `[(keypath ev :clock)]` → `Long` or nil (unknown event) | 1 |
| `get-hold ev hid` | query topology `hold` | `(|hash *ev)`; read `[(keypath *ev :clock)]`; if non-nil read `[(keypath *ev :holds *hid)]`; derive `:state` (`:confirmed`/`:released` from the stored status, else `:active` if `clock < deadline` else `:expired`); emit the plain hold record with `:hold-id *hid`, `:state`, and `:payment-ref` (nil unless confirmed) — the record has no subindexed child, so it may cross the wire; `(|origin)` | 2 when the event exists |
| `get-seats ev seat-ids` | query topology `seats` | `(|hash *ev)`; read `[(keypath *ev :clock)]` → nil ⇒ result nil; else `[(keypath *ev :seats) (submap distinct-seat-ids)]` (one point seek per distinct seat) and derive each seat's state against the clock; `(|origin)` | 1 + k (k = distinct seat-ids ≤ 64) |
| `get-compensations ev after limit` | `foreign-select` | client returns `[]` without a call when `after = Long/MAX_VALUE` (`(inc after)` would overflow); else `[(keypath ev :compensations) (sorted-map-range-from (inc after) {:max-amt limit}) ALL]` → `[seq rec]` pairs ascending; client builds each entry as `(assoc rec :seq seq)` because the stored record does not carry `:seq` | 1 + 1 seek + `limit` iterations |

Decision per read: `get-outcome`, `get-clock`, `get-compensations` are one
path on one partition → foreign select. `get-hold` and `get-seats` need the
clock plus one or more records from the same partition → query topology,
one roundtrip each. Both have a leading `(|hash *ev)` evaluated
client-side.

Seat state derivation (used by `seats` and by `hold-seats!`): a seat record
is `{:hold-id :user-id :deadline :confirmed?}`. State =
`:confirmed` if `confirmed?`; else `:held` if `hold-id` non-nil and
`clock < deadline`; else `:available` (with `hold-id`/`user-id` reported
as nil). A released hold clears the seat record's hold fields, so
"released" never needs a hold lookup.

Validation: queries validate arguments client-side (`limit` 1..500,
`after-seq ≥ 0`, 1..64 seat-ids, non-empty ids ≤ 128) and throw
`IllegalArgumentException` before any call. `get-seats` deduplicates
seat-ids (README: duplicates collapse to one key).

## Writes

One depot `*commands`, `(hash-by :event-id)`, six `defrecord` types, each
carrying `event-id` and `request-id`:

| Command | Depot record |
|---|---|
| `create-event!` | `(->CreateEvent event-id request-id)` |
| `add-seats!` | `(->AddSeats event-id request-id seat-ids)` |
| `advance-clock!` | `(->AdvanceClock event-id request-id now)` |
| `hold-seats!` | `(->HoldSeats event-id request-id user-id seat-ids deadline)` |
| `confirm-hold!` | `(->ConfirmHold event-id request-id hold-id user-id payment-ref)` |
| `release-hold!` | `(->ReleaseHold event-id request-id hold-id user-id)` |

Client appends with `:ack` after structural validation (bounds, distinct
seat-ids for add/hold, 1..1000 / 1..8 sizes, `0 ≤ time ≤ 2^53`) and
returns `nil`. Record equality is payload equality (same event and
request-id by construction; different record types never compare equal).

## PState Design

One PState `$$events`, owned by microbatch topology `core`:

```clojure
(declare-pstate mb $$events
  {String                                              ; event-id
   (fixed-keys-schema
     {:clock         Long                              ; logical clock, 0 at creation; non-nil ⇔ event exists
      :ingress-seq   Long                              ; last ingress position assigned (ordering only; see ETL structure)
      :next-comp-seq Long                              ; last compensation seq issued
      :seats         (map-schema String                ; seat-id ->
                       (fixed-keys-schema
                         {:hold-id    String           ; nil when never held / released
                          :user-id    String
                          :deadline   Long
                          :confirmed? Boolean})
                       {:subindex? true})
      :holds         (map-schema String                ; hold-id (= request-id of the hold) ->
                       (fixed-keys-schema
                         {:user-id     String
                          :seat-ids    (vector-schema String)   ; ≤ 8, structural bound
                          :deadline    Long
                          :status      clojure.lang.Keyword     ; :held | :released | :confirmed
                          :payment-ref String})                 ; set when :confirmed
                       {:subindex? true})
      :compensations (map-schema Long                  ; seq ->
                       (fixed-keys-schema
                         {:request-id String :hold-id String :user-id String
                          :payment-ref String :reason clojure.lang.Keyword})
                       {:subindex? true})
      :requests      (map-schema String                ; request-id ->
                       (fixed-keys-schema
                         {:command              clojure.lang.Keyword
                          :payload              ICommand
                          :outcome              IOutcome
                          :conflicting-attempts Long})
                       {:subindex? true})})})
```

```clojure
(definterface ICommand)   ; implemented by the six depot records
(definterface IOutcome)
(defrecord CreateAccepted [] IOutcome)
(defrecord AddSeatsAccepted [added] IOutcome)
(defrecord ClockAccepted [clock] IOutcome)
(defrecord HoldAccepted [hold-id deadline] IOutcome)
(defrecord ConfirmAccepted [seat-ids payment-ref] IOutcome)
(defrecord ReleaseAccepted [seat-ids] IOutcome)
(defrecord Rejected [reason unavailable-seats compensation-seq] IOutcome)
;; unavailable-seats only for :seat-unavailable; compensation-seq only when a record was written
```

Why one PState: all collections share the key `event-id` and partitioner.
The top-level value is three Longs plus four subindex handles; every growing
collection (seats up to tens of thousands, holds unbounded, compensations
unbounded, requests unbounded) is subindexed. The entry is **never
written whole**, not even at event creation: an event's key may already
hold `:requests` entries (commands rejected with `:no-such-event` before
the event existed) and an `:ingress-seq`, and a `termval` of the entry
would erase them and orphan the subindexed children (`paths.md`,
`pstate-schema.md`). Event existence is `:clock` non-nil, which only
`CreateEvent` writes; all three scalars are read and written by field
path only.

Alternatives costed for the dominant reads (`get-seats`, `hold-seats!`):

- **Option A (chosen): seat record carries `hold-id`, `user-id`,
  `deadline`, `confirmed?`.** `get-seats` for k seats = 1 + k seeks and
  no hold reads; `hold-seats!` availability = k ≤ 8 seat seeks. Confirm
  and release write each of the hold's ≤ 8 seats (allowed: README bounds
  them by the hold size). Expiry is lazy: the stored deadline is compared
  with the clock at read time, so `advance-clock!` writes one Long and
  touches no seat or hold.
- **Option B: seat record holds only `hold-id`; state resolved through
  `:holds`.** `get-seats` = 1 + k + (up to k) seeks; `hold-seats!` = up
  to 16 seeks. Saves ≤ 8 seat writes per confirm/release. Rejected: reads
  dominate (every onsale query and every hold does the seat reads), and
  Option A's extra writes are bounded by the hold size the spec already
  charges.
- Seats as one map per event (non-subindexed): rejected — tens of
  thousands of seats would be deserialized on every hold.

Storage granularity: no field holds a whole collection; `seat-ids` in a
hold is ≤ 8 by structural validation.

## Depots

- `*commands`: `(hash-by :event-id)`, six record types. One depot because
  the README orders all commands of an event relative to each other (hold,
  advance, hold, confirm issued back-to-back must resolve in issue order)
  and request-ids are shared across command types.

## Topologies and PStates

- `core`: **microbatch** (default). No single-digit-millisecond
  visibility requirement (barrier-based), no ack-return, and the writes
  are non-idempotent (compensation seq, `:conflicting-attempts`, seat
  acquisition that must not double-apply). Microbatch exactly-once makes
  "exactly one compensation record per qualifying rejection" hold under
  retry. Concerns: replay/conflict, event/seat creation, clock, hold
  acquisition, fenced confirm/release, compensation log, outcomes — all
  per-event, same task, one event each; none needs stream.
  - Owns `$$events`.
  - ETL structure: one `<<batch` block implementing **ordered per-event
    batching** (next subsection). An event's commands of one microbatch
    are applied by ONE loop event per event-id, in depot append order,
    each command completely (including its outcome) before the next; the
    loop yields cooperatively between commands and inside the bounded
    1000-seat `add-seats!` work.

### Ordered per-event batching (ETL structure)

The README applies an event's commands in issue order, one at a time,
and the skill's cooperative-multitasking rule forbids unbounded
synchronous work on a task: an onsale event can queue thousands of holds
in one microbatch, and one `add-seats!` performs up to 1000 reads and
writes. A `yield-if-overtime` inside plain per-record processing would
let the next same-event record start between one hold's availability
check and its seat writes (`dataflow.md` "Yielding and ordering").
Resolution: make an event's whole batch of commands one event, ordered
by a durable ingress position, and yield only inside it.

```clojure
(<<sources mb
  (source> *commands :> %mb)
  (<<batch
    ;; pre-agg: runs on the depot partition task (hash of event-id) in
    ;; depot append order, synchronously, before any partitioner or yield
    (%mb :> *cmd)
    (get *cmd :event-id :> *ev)
    (local-select> [(keypath *ev :ingress-seq) (nil->val 0)] $$events :> *prev)
    (inc *prev :> *pos)
    (local-transform> [(keypath *ev :ingress-seq) (termval *pos)] $$events)
    (vector *pos *cmd :> *pair)
    ;; agg: one row per event; +group-by hash-partitions by *ev, the same
    ;; routing the PState uses for its top-level key
    (+group-by *ev
      (aggs/+vec-agg *pair :> *pairs))
    ;; post-agg: one loop event per event-id per microbatch
    (sort-pairs-by-position *pairs :> *ordered)   ; defn sorting by *pos; +vec-agg order is NOT assumed
    (loop<- [*remaining *ordered :> *done]
      (<<if (empty? *remaining)
        (:> true)
       (else>)
        (first *remaining :> [*pos *cmd])
        ;; apply-command: the complete per-command procedure below,
        ;; including its bounded inner loops (which may yield) and the
        ;; request-record write; replay, conflict, rejection, and
        ;; acceptance branches all unify here, so exactly one continue>
        ;; runs per command
        (yield-if-overtime)
        (continue> (rest *remaining))))))
```

Properties relied on:

- `%mb` emits per task in depot append order and the pre-agg segment up
  to the `+group-by` partitioner is synchronous, so ingress positions are
  assigned in append order and are unique per event; reads inside the
  owning topology see the attempt's own writes (`microbatch.md` "Read
  visibility").
- `+group-by` needs no explicit partitioner and emits one row per key;
  post-agg has only `*ev` and `*pairs` in scope and allows no
  partitioner (`batch.md`, `aggregators.md`). `+vec-agg` element order
  is not documented, hence the explicit sort by position.
- Yields inside the loop (`yield-if-overtime` between commands and in
  the `add-seats!` write loop, `{:allow-yield? true}` on the up-to-1000
  key `submap` read) let other events on the task run: other events'
  loops, query topologies, foreign reads. None touches this event's
  uncommitted writes: the only writer of an event-id within a microbatch
  is this loop, external readers see committed state only, and the next
  microbatch starts only after this one completes on all tasks
  (`microbatch.md`). A retry rolls back and reapplies the ingress
  positions and the business writes together (exactly-once).
- `:ingress-seq` is a durable per-event Long written by field path
  only; it counts every command (replays, conflicts, rejections
  included), is independent of `:next-comp-seq`, and is never exposed.
- Batch bound: `(set-launch-depot-dynamic-option! setup "*commands"
  "depot.microbatch.max.records" 200)` caps records per depot partition
  per microbatch. An `add-seats!` record can reach ~130 KB (1000 ids ×
  128 B), so 200 records bound the grouped vectors at ≈ 26 MB worst case
  per task and a few hundred KB typically, never lifetime history. Group
  memory is transient batch state, not a TaskGlobal and not a durable
  inbox.
- Cost: one `+group-by` hop per command (a network partitioner landing on
  the same task as the depot partition), plus one extra navigation of
  the event's top-level entry and one field write in pre-agg. The
  earlier "zero partitioners" claim is withdrawn.
- Limitation, stated plainly: the per-event loop is bounded by the batch
  size (≤ 200 commands, each ≤ ~1000 seeks and writes for `add-seats!`,
  ≤ 10 otherwise) and yields every ~5 ms; no single microbatch attempt
  approaches `topology.microbatch.phase.timeout.seconds` under the
  README bounds; no input cap beyond the README's is imposed.
- Build-time verifications: (a) `local-transform>` and `loop<-` are
  accepted in the post-agg of a microbatch `<<batch`; fallback is
  `(materialize> *ev *ordered :> $$grouped)` and running the loop in the
  pre-agg of a second `<<batch` that reads `($$grouped :> *ev *ordered)`.
  (b) `+group-by` routing lands on the PState partition of `*ev` at 2
  and 4 tasks. (c) `sort-pairs-by-position` is a plain `defn`.

Per-command processing (`apply-command`, on the event's task, inside the
loop above):

1. `[(keypath *ev :requests *rid)]` → `*req`. Non-nil: replay if
   `(= (:payload *req) *cmd)` (no-op), else rewrite the record with
   `:conflicting-attempts` +1 (`termval` of the read record). Stop
   (unify to the loop's `continue>`).
2. `[(keypath *ev :clock)]` → `*clock` (nil ⇒ event absent).
   - `CreateEvent`: absent → write the scalar fields only,
     `[(keypath *ev :clock) (termval 0)]` and
     `[(keypath *ev :next-comp-seq) (termval 0)]`; never `termval` the
     event entry, because `:requests` entries from pre-creation
     rejections and the `:ingress-seq` already live under this key and
     must survive. Present → `Rejected :event-exists`.
   - `AddSeats`: absent → `:no-such-event`. Read
     `[(keypath *ev :seats) (submap seat-ids)]` with
     `{:allow-yield? true}` (n ≤ 1000 seeks); any present →
     `:seat-exists`, none written. Else write each seat
     `{:hold-id nil :user-id nil :deadline nil :confirmed? false}`
     (write-only) in a `loop<-` over the ids with `(yield-if-overtime)`
     per iteration. `AddSeatsAccepted n`.
   - `AdvanceClock`: absent → `:no-such-event`; `now < clock` →
     `:clock-regression`; else `[(keypath *ev :clock) (termval now)]`,
     `ClockAccepted now`.
   - `HoldSeats`: absent → `:no-such-event`. Read the ≤ 8 seats with
     `(submap seat-ids)`. Any nil → `:no-such-seat`. `deadline <= clock`
     → `:deadline-passed`. Unavailable = seats that are `confirmed?` or
     have `hold-id` with `clock < deadline`, in the given order; any →
     `Rejected :seat-unavailable unavailable`. Else write each seat
     `{:hold-id rid :user-id u :deadline d :confirmed? false}`, write
     hold `[(keypath *ev :holds rid) (termval {... :status :held})]`,
     `HoldAccepted rid d`.
   - `ConfirmHold`: absent → `:no-such-event`. Read
     `[(keypath *ev :holds hid)]`; nil → `:no-such-hold` (rejected holds
     were never written). `user-id` mismatch → `:not-owner`. Status
     `:confirmed` → `:hold-confirmed`, with a compensation record iff
     `payment-ref` differs from the stored one. Status `:released` →
     `:hold-released` + compensation. `clock >= deadline` →
     `:hold-expired` + compensation (never revived, regardless of the
     seats' current holders). Else accept: hold status `:confirmed` with
     `payment-ref`; each seat of the hold gets `confirmed? true`
     (`termval` of the seat record; the seat's `hold-id` is `hid` by the
     "one active hold per seat" invariant, so no seat read is needed).
     `ConfirmAccepted seat-ids payment-ref`.
   - `ReleaseHold`: same fencing checks in README order; on acceptance
     hold status `:released`, and each seat of the hold is reset to
     `{:hold-id nil :user-id nil :deadline nil :confirmed? false}`
     (write-only). `ReleaseAccepted seat-ids`.
3. Compensation record: read `[(keypath *ev :next-comp-seq)]` (same
   top-level entry as the `:clock` read, no extra seek);
   `seq = (inc next-comp-seq)`; write
   `[(keypath *ev :compensations seq) (termval {...})]` and
   `[(keypath *ev :next-comp-seq) (termval seq)]`; the rejected outcome
   carries `compensation-seq`.
4. Write the request record with `:conflicting-attempts 0`. Every branch
   of steps 1–4 ends here and unifies into the outer loop's single
   `continue>`.

I/O per command: hold = 2 + 8 seeks, ≤ 11 writes; confirm/release = 3
seeks, ≤ 11 writes; add-seats = 2 + n seeks, n + 2 writes; advance = 2
seeks, 3 writes (the ingress position adds one navigation of the
top-level entry, which every command reads anyway, and one field write).
All bounded by the command's own input; no operation reads all seats,
all holds, or the compensation log.

Fencing correctness: a confirm or release names a hold-id; seats are only
written on acceptance, which requires the hold to be `:held` and active.
An active hold's seats cannot have been taken by another hold (a hold is
only granted over seats that are available, i.e. not under an active
hold), so writing the hold's seats never touches another hold's seats.
After expiry the hold is rejected before any seat write, so a re-held seat
is untouched.

## Query Topologies

- `hold` `[*ev *hid :> *result]`
  - Input 1: existing event, existing hold → 2 total reads (clock, hold),
    2 meaningful.
  - Input 2: unknown event → 1 read (clock nil), result nil; hold read
    skipped by `<<if`.
  - Input 3: existing event, unknown hold → 2 reads, result nil.
  - Variable (1 or 2), handled by `<<if`; result emitted exactly once.
- `seats` `[*ev *seat-ids :> *result]`
  - Input 1: existing event, 64 distinct known seats → 1 + 64 reads, all
    meaningful (each answers one key; unknown seats answer nil, which is
    a required output).
  - Input 2: existing event, 1 seat → 2 reads.
  - Input 3: unknown event → 1 read, result nil.
  - Variable, driven by the input list (`submap` over distinct ids, or
    `ops/explode` + `local-select>` + `aggs/+map-agg` if `submap` is not
    accepted on subindexed maps at build time); no padded reads.

## Partitioning efficiency

Optimal placement: every read and every command is scoped to one event
and the README requires per-event ordering and atomic multi-seat holds, so
`f(event-id) → one task`: `(hash-by :event-id)` on the depot and default
hash routing on `$$events`. Events are numerous; a hot onsale event is a
serial command stream by spec, so no partitioner could spread it. Seeks/op
is the number of tasks touched: 1 at every N.

Dominant read: `get-seats` (query `seats`).

### N = 1 task (single-task baseline)
| Data category | Frequency proportion | Seeks/op | Iterator reads/op |
|---|---|---|---|
| 8 seats of a small event | 0.40 | 1 | 0 |
| 64 seats of an event with 10⁵ seats and 10⁶ holds | 0.35 | 1 | 0 |
| mix of unknown and known seats | 0.15 | 1 | 0 |
| unknown event | 0.10 | 1 | 0 |
Weighted seeks = 1.0   |   Weighted iterator reads = 0

### N = 16 tasks
| Data category | Frequency proportion | Seeks/op | Iterator reads/op |
|---|---|---|---|
| 8 seats of a small event | 0.40 | 1 | 0 |
| 64 seats of an event with 10⁵ seats and 10⁶ holds | 0.35 | 1 | 0 |
| mix of unknown and known seats | 0.15 | 1 | 0 |
| unknown event | 0.10 | 1 | 0 |
Weighted seeks = 1.0   |   Weighted iterator reads = 0

### N = 128 tasks
| Data category | Frequency proportion | Seeks/op | Iterator reads/op |
|---|---|---|---|
| 8 seats of a small event | 0.40 | 1 | 0 |
| 64 seats of an event with 10⁵ seats and 10⁶ holds | 0.35 | 1 | 0 |
| mix of unknown and known seats | 0.15 | 1 | 0 |
| unknown event | 0.10 | 1 | 0 |
Weighted seeks = 1.0   |   Weighted iterator reads = 0

Flat across N. Within the one task, local point seeks are 1 + k where k
is the requested seat count, independent of event size.

## Design Decisions

- Subindexing: `:seats`, `:holds`, `:compensations`, `:requests` grow
  without bound per event → subindexed. `seat-ids` in a hold (≤ 8) and the
  top-level record are not.
- Colocation: depot `(hash-by :event-id)` = PState key = `+group-by *ev`
  routing. The only partitioner is the `+group-by` hop, which lands on
  the task that already holds the event.
- Ordering and yielding: ordered per-event batching (one loop event per
  event-id per microbatch, sorted by durable `:ingress-seq`), with
  `yield-if-overtime` between commands and inside `add-seats!`, and
  `{:allow-yield? true}` on the 1000-key seat read.
  `depot.microbatch.max.records` = 200 bounds per-microbatch group
  memory.
- Event entry creation: only `:clock` and `:next-comp-seq` are written;
  an event's entry is never written whole, so outcomes of commands
  rejected before `create-event!` survive creation.
- Lazy expiry: deadlines are stored on seats and holds and compared with
  the clock at processing/query time; `advance-clock!` is one Long write;
  a million expired holds cost nothing until a seat is touched.
- Rejected holds are never written to `:holds`, so `:no-such-hold` and
  `get-hold → nil` hold for them.
- Compensation records are per rejected request-id (not deduplicated by
  payment-ref); a replay returns early in step 1 and cannot write a second
  record.
- Nothing is deleted; a released hold's seats are reset (spec-required
  state change), the hold record is retained.

### Synchronization and client contract

`create-module` creates one `(atom 0)` in its closure shared by every
`:wrap-client`; commands increment it before appending;
`wait-for-processing!` calls
`(rtest/wait-for-microbatch-processed-count ipc module-name "core" @cnt)`.
The count is module-level and cumulative, so a second client and an
`update-module!` keep it valid. No business data lives in the client.

## State primitive selection

- `$$events` (PState): durable. Writes per command ≤ 11 (hold/confirm/
  release) or ≤ 1002 (add-seats), bounded by the command's input.
  `:ingress-seq` lives here because it must roll back with the attempt
  on retry; a TaskGlobal counter would not.
- Per-microbatch grouped vectors (`+vec-agg` output): transient batch
  state inside one microbatch attempt, bounded by
  `depot.microbatch.max.records`; not a TaskGlobal, not durable.
- No TaskGlobal.
- Client atom: sync bookkeeping only.

## Resource usage analysis

### Disk usage (PStates)
- Top-level entry: ≤ 128 B key + ~100 B. Negligible.
- Seat record: seat-id ≤ 128 B + hold-id ≤ 128 B + user-id ≤ 128 B + Long
  + Boolean → ≤ 400 B, typically ~100 B. 10⁵ seats ≈ 10 MB per event.
- Hold record: ≤ 8 seat-ids + ids + scalars → ≤ 1.5 KB, typically ~200 B.
  10⁶ holds ≈ 200 MB spread over tasks by event hash.
- Compensation record: ≤ 550 B.
- Request record: payload (≤ 1.3 KB for a hold; up to 128 KB for a
  1000-seat add) + outcome ≤ 1.2 KB.
- Growth: one request record per command; one hold per accepted hold; one
  compensation per qualifying rejection.

### Memory usage (TaskGlobals)
None. Transient per-microbatch memory: the grouped `[pos cmd]` vectors,
≤ `depot.microbatch.max.records` (200) records per task per microbatch;
≈ 26 MB only if every record is a maximal 1000-seat `add-seats!`, a few
hundred KB for hold traffic; released when the attempt commits.

### Minimization
- Seat records duplicate `user-id` and `deadline` from the hold (≤ 136 B
  per seat) so `get-seats` and `hold-seats!` need no hold reads; justified
  above by read frequency.
- Request payloads are stored verbatim because replay is defined by `=`.
- `:ingress-seq` is one Long per event; it cannot be folded into
  `:next-comp-seq`, which must stay contiguous over compensation records
  only.

## Design difficulty log

- **Decision:** ordered per-event batching — pre-agg assigns a durable
  `:ingress-seq` position per command, `+group-by` event-id with
  `+vec-agg`, explicit sort by position, one `loop<-` per event-id per
  microbatch applying each command completely, `yield-if-overtime`
  between commands and inside the 1000-seat `add-seats!` write loop,
  `{:allow-yield? true}` on the 1000-key seat read. **Basis:** README
  per-event issue ordering and atomic multi-seat holds; skill
  cooperative-multitasking rule (onsale bursts queue thousands of holds
  per microbatch; `add-seats!` does up to 1000 reads and writes);
  `dataflow.md` states yielding gives up arrival ordering across events;
  `+vec-agg` ordering is undocumented. Alternatives: per-record
  processing without yield (unbounded synchronous stretch), per-record
  processing with yield (a second hold could read seats between the
  first hold's check and its seat writes), grouping without a durable
  position (order unverifiable), a durable inbox or TaskGlobal queue
  (extra state; TaskGlobal does not roll back on retry). **Outcome:**
  ordering and cooperative yielding both hold at the cost of one
  `+group-by` hop and one top-level read and field write per command.
  Needs build-time verification of post-agg `local-transform>`/`loop<-`
  acceptance and `+group-by`-to-PState routing agreement at 2 and 4
  tasks, plus a test that issues overlapping holds, an advance, and a
  confirm for one event in one microbatch and checks outcomes against a
  sequential oracle.
- **Decision:** `create-event!` writes only `:clock` and
  `:next-comp-seq`; the event entry is never written whole. **Basis:**
  `paths.md` (`termval` replaces the map), `pstate-schema.md` (orphaned
  subindexed children), and the README's requirement that a
  `:no-such-event` outcome stored before creation remains readable and
  replay-stable. **Outcome:** pre-creation outcomes and `:ingress-seq`
  survive creation. Verify with: `hold-seats!` on an unknown event
  (rejected) → `create-event!` → original outcome still `:no-such-event`,
  replay still rejected, conflicting payload increments the counter,
  both when the commands share a microbatch and when a barrier separates
  them.
- **Decision:** seat records carry `user-id` and `deadline` (Option A)
  rather than resolving through the hold (Option B). **Basis:**
  `get-seats` costs 1 + k seeks versus up to 1 + 2k; the extra ≤ 8 writes
  per confirm/release are already charged by the README's per-hold
  bound. **Outcome:** reads dominate, so A; `advance-clock!` stays a
  one-field write.
- **Decision:** no expiry index. **Basis:** README forbids per-hold
  background work; lazy comparison against the stored deadline needs no
  index. **Outcome:** determined directly.
- One depot, one microbatch topology, and one PState keyed by event are
  determined directly by the ordering, atomicity, and retry
  requirements.
