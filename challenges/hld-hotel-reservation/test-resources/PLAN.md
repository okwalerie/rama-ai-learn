# Plan — hld-hotel-reservation (subsystem: room-inventory, whole spec)

Private reference-implementation plan (Phase 1). Inputs: README.md,
`src/hld_hotel_reservation/protocol.clj`, `test-resources/IMPLICIT_SPEC.md`,
`test-resources/DECOMPOSITION.json`. Design only; no topology code.

## Reads

All reads are scoped by `property-id`, the depot partition key and the
top-level PState key: one task per read.

| Read | Access method | Path / query | RocksDB reads |
|---|---|---|---|
| `get-outcome p rid` | `foreign-select-one` | `[(keypath p :requests rid)]` on `$$properties` → request record or nil; client renders the outcome map | 2 |
| `get-night p rt n` | `foreign-select-one` | `[(keypath p :room-types rt :nights n)]` → `{:capacity :rate :available}` or nil; client `assoc`s `:night` | 3 (property, room type, night) |
| `get-availability p rt ci co` | query topology `availability` | `(|hash *p)`; read `[(keypath *p :room-types *rt :exists?)]` → nil ⇒ result nil; else `[(keypath *p :room-types *rt :nights) (sorted-map-range *ci *co)]` (one seek + ≤ 30 iterations) and build the vector `[ci, co)` with nil for unconfigured nights; emit only that plain vector — the existence test reads the `:exists?` field, never the room-type entry, which holds the `:nights` subindex handle and must never be emitted; `(|origin)` | 2 + 1 seek + ≤ 30 iterations |
| `get-booking p bid` | `foreign-select-one` | `[(keypath p :bookings bid)]` → booking record or nil; client `assoc`s `:booking-id` | 2 |
| `get-booking-events p after limit` | `foreign-select` | client returns `[]` without a call when `after = Long/MAX_VALUE` (`(inc after)` would overflow); else `[(keypath p :events) (sorted-map-range-from (inc after) {:max-amt limit}) ALL]` → `[seq event]` pairs ascending; client builds each entry as `(assoc event :seq seq)` because the stored event does not carry `:seq` | 1 + 1 seek + `limit` iterations |

Decision per read: all but `get-availability` are one path on one
partition → foreign select. `get-availability` must distinguish "unknown
property/room type" (nil) from "no nights configured" (vector of nils),
which needs the room-type existence read plus the range → query topology,
one roundtrip, leading `(|hash *p)` evaluated client-side. Availability is
a stored counter per night, never recomputed from bookings.

Client validation: ids non-empty ≤ 128; nights `0..2^40`;
`1 ≤ checkout − checkin ≤ 30`; quantity 1..100; capacity `0..10⁶`; rate
`0..10⁹`; `limit` 1..500; `after-seq ≥ 0`; violations throw
`IllegalArgumentException` before any call.

## Writes

One depot `*commands`, `(hash-by :property-id)`, six `defrecord` types
with `property-id` and `request-id`:

| Command | Depot record |
|---|---|
| `create-property!` | `(->CreateProperty property-id request-id)` |
| `create-room-type!` | `(->CreateRoomType property-id request-id room-type)` |
| `init-night!` | `(->InitNight property-id request-id room-type night capacity rate)` |
| `set-rate!` | `(->SetRate property-id request-id room-type night rate)` |
| `reserve!` | `(->Reserve property-id request-id room-type guest-id checkin checkout quantity)` |
| `cancel-booking!` | `(->CancelBooking property-id request-id booking-id guest-id)` |

Client appends with `:ack` after structural validation and returns `nil`.
Record equality is payload equality; different record types never compare
equal (a `set-rate!` reusing a reservation's request-id is a conflicting
attempt).

## PState Design

One PState `$$properties`, owned by microbatch topology `core`:

```clojure
(declare-pstate mb $$properties
  {String                                                ; property-id
   (fixed-keys-schema
     {:next-seq   Long                                   ; last event seq issued (0 initially); non-nil ⇔ property exists
      :ingress-seq Long                                  ; last ingress position assigned (ordering only; see ETL structure)
      :room-types (map-schema String                     ; room-type ->
                    (fixed-keys-schema
                      {:exists? Boolean                  ; true once created; the existence marker for the room type
                       :nights (map-schema Long          ; night ->
                                 (fixed-keys-schema
                                   {:capacity  Long
                                    :rate      Long
                                    :available Long})
                                 {:subindex? true})})
                    {:subindex? true})
      :bookings   (map-schema String                     ; booking-id (= request-id) ->
                    (fixed-keys-schema
                      {:guest-id String :room-type String
                       :checkin Long :checkout Long :quantity Long :total Long
                       :state clojure.lang.Keyword       ; :confirmed | :cancelled
                       :seq Long})                       ; seq of the :reserved event, immutable
                    {:subindex? true})
      :events     (map-schema Long                       ; seq ->
                    (fixed-keys-schema
                      {:type clojure.lang.Keyword        ; :reserved | :cancelled
                       :request-id String :booking-id String :guest-id String
                       :room-type String :checkin Long :checkout Long
                       :quantity Long :total Long})
                    {:subindex? true})
      :requests   (map-schema String
                    (fixed-keys-schema
                      {:command              clojure.lang.Keyword
                       :payload              ICommand
                       :outcome              IOutcome
                       :conflicting-attempts Long})
                    {:subindex? true})})})
```

```clojure
(definterface ICommand)   ; the six depot records
(definterface IOutcome)
(defrecord PropertyAccepted [] IOutcome)
(defrecord RoomTypeAccepted [] IOutcome)
(defrecord NightAccepted [] IOutcome)
(defrecord RateAccepted [rate] IOutcome)
(defrecord ReserveAccepted [booking-id total nights seq] IOutcome)
(defrecord CancelAccepted [booking-id seq] IOutcome)
(defrecord Rejected [reason nights] IOutcome)   ; nights only for :night-not-configured / :insufficient-capacity
```

Alternatives costed for the dominant operations (`reserve!`,
`get-availability`):

- **Option A (chosen): nights nested `room-type → night → record`
  (Long keys, subindexed at both levels).** A stay is a contiguous Long
  range, so `reserve!`, `cancel-booking!`, and `get-availability` read the
  stay's nights with one `sorted-map-range` seek plus ≤ 30 iterations
  instead of 30 point seeks (IMPLICIT_SPEC efficiency concern). Writes
  are ≤ 30 `termval`s of already-read records. The room-type entry doubles
  as the existence record for `:no-such-room-type`, so no separate
  room-type set is needed.
- **Option B: flat `[room-type night]` composite keys.** One fewer read
  per point lookup, but serialized-vector key order is not guaranteed
  numeric, so the range read over a stay could not be relied on.
  Rejected.
- **Option C: per-night point reads (no range).** 30 seeks per 30-night
  stay versus 1 seek + 30 iterations (≈ 15 ms versus ≈ 0.65 ms).
  Rejected on cost.
- **Option D: availability recomputed from bookings.** Forbidden by the
  README ("must not be recomputed by scanning bookings").

Why one PState: all collections share the key `property-id` and
partitioner. The top-level value is two Longs plus four subindex handles.
It is **never written whole**, not even at property creation: a
property's key may already hold `:requests` entries (commands rejected
with `:no-such-property` before the property existed) and an
`:ingress-seq`, and a `termval` of the entry would erase them and orphan
the subindexed children (`paths.md`, `pstate-schema.md`). Property
existence is `:next-seq` non-nil, which only `CreateProperty` (0) and
accepted reserve/cancel (which require existence) write; both scalars
are read and written by field path only. Likewise a room-type entry is
never written whole: `CreateRoomType` writes the field
`[(keypath *p :room-types rt :exists?) (termval true)]`, existence
checks read that field, and nights are written through the nested
keypath. Room types per property are unbounded in principle, so
`:room-types` is subindexed; nights, bookings, events, and requests grow
without bound and are subindexed.

## Depots

- `*commands`: `(hash-by :property-id)`, six record types. One depot:
  the README orders all commands of a property (reserve, reserve, cancel,
  reserve issued back-to-back must resolve in issue order; set-rate
  between two reserves must affect only the second), and request-ids are
  shared across types.

## Topologies and PStates

- `core`: **microbatch** (default). No single-digit-millisecond
  visibility requirement (barrier-based), no ack-return, and every write
  is non-idempotent (availability decrements, seq, `:conflicting-
  attempts`). Microbatch exactly-once ensures a retried reserve never
  decrements twice and a retried cancel never restores twice.
  Concerns: replay/conflict, configuration writes, atomic multi-night
  reservation, cancellation, event journal, outcomes — all per-property,
  same task, one event each.
  - Owns `$$properties`.
  - ETL structure: one `<<batch` block implementing **ordered
    per-property batching** (next subsection). A property's commands of
    one microbatch are applied by ONE loop event per property, in depot
    append order, each command completely (including its outcome, atomic
    across all its nights) before the next; the loop yields
    cooperatively between commands.

### Ordered per-property batching (ETL structure)

The README applies a property's commands in issue order, one at a time,
and the skill's cooperative-multitasking rule forbids unbounded
synchronous work on a task: a popular property can queue thousands of
reservations in one microbatch. A `yield-if-overtime` inside plain
per-record processing would let the next same-property record read
availability between one reservation's check and its night writes
(`dataflow.md` "Yielding and ordering"). Resolution: make a property's
whole batch of commands one event, ordered by a durable ingress
position, and yield only inside it.

```clojure
(<<sources mb
  (source> *commands :> %mb)
  (<<batch
    ;; pre-agg: runs on the depot partition task (hash of property-id) in
    ;; depot append order, synchronously, before any partitioner or yield
    (%mb :> *cmd)
    (get *cmd :property-id :> *p)
    (local-select> [(keypath *p :ingress-seq) (nil->val 0)] $$properties :> *prev)
    (inc *prev :> *pos)
    (local-transform> [(keypath *p :ingress-seq) (termval *pos)] $$properties)
    (vector *pos *cmd :> *pair)
    ;; agg: one row per property; +group-by hash-partitions by *p, the
    ;; same routing the PState uses for its top-level key
    (+group-by *p
      (aggs/+vec-agg *pair :> *pairs))
    ;; post-agg: one loop event per property per microbatch
    (sort-pairs-by-position *pairs :> *ordered)   ; defn sorting by *pos; +vec-agg order is NOT assumed
    (loop<- [*remaining *ordered :> *done]
      (<<if (empty? *remaining)
        (:> true)
       (else>)
        (first *remaining :> [*pos *cmd])
        ;; apply-command: the complete per-command procedure below,
        ;; including the request-record write; replay, conflict,
        ;; rejection, and acceptance branches all unify here, so exactly
        ;; one continue> runs per command
        (yield-if-overtime)
        (continue> (rest *remaining))))))
```

Properties relied on:

- `%mb` emits per task in depot append order and the pre-agg segment up
  to the `+group-by` partitioner is synchronous, so ingress positions are
  assigned in append order and are unique per property; reads inside the
  owning topology see the attempt's own writes (`microbatch.md` "Read
  visibility").
- `+group-by` needs no explicit partitioner and emits one row per key;
  post-agg has only `*p` and `*pairs` in scope and allows no partitioner
  (`batch.md`, `aggregators.md`). `+vec-agg` element order is not
  documented, hence the explicit sort by position.
- `yield-if-overtime` between commands lets other events on the task run
  (other properties' loops, query topologies, foreign reads). None
  touches this property's uncommitted writes: the only writer of a
  property within a microbatch is this loop, external readers see
  committed state only, and the next microbatch starts only after this
  one completes on all tasks (`microbatch.md`). A retry rolls back and
  reapplies the ingress positions and the business writes together
  (exactly-once). A command's own work is ≤ 30 nights (one range seek +
  ≤ 30 iterations, ≤ 34 writes), so no yield is needed inside a command.
- `:ingress-seq` is a durable per-property Long written by field path
  only; it counts every command (replays, conflicts, rejections
  included), is independent of the journal `:next-seq`, and is never
  exposed.
- Batch bound: `(set-launch-depot-dynamic-option! setup "*commands"
  "depot.microbatch.max.records" 1000)` caps records per depot partition
  per microbatch, so a grouped vector holds at most one batch (≤ 1000
  records ≈ 450 KB per task), never lifetime history. Group memory is
  transient batch state, not a TaskGlobal and not a durable inbox.
- Cost: one `+group-by` hop per command (a network partitioner landing on
  the same task as the depot partition), plus one extra navigation of
  the property's top-level entry and one field write in pre-agg. The
  earlier "zero partitioners" claim is withdrawn.
- Limitation, stated plainly: the per-property loop is bounded by the
  batch size (≤ 1000 commands × ≤ 30-night work ≈ well under 10 s warm)
  and yields every ~5 ms; no single microbatch attempt approaches
  `topology.microbatch.phase.timeout.seconds` under the README bounds;
  no input cap beyond the README's is imposed.
- Build-time verifications: (a) `local-transform>` and `loop<-` are
  accepted in the post-agg of a microbatch `<<batch`; fallback is
  `(materialize> *p *ordered :> $$grouped)` and running the loop in the
  pre-agg of a second `<<batch` that reads `($$grouped :> *p *ordered)`.
  (b) `+group-by` routing lands on the PState partition of `*p` at 2 and
  4 tasks. (c) `sort-pairs-by-position` is a plain `defn`.

Per-command processing (`apply-command`, on the property's task, inside
the loop above):

1. `[(keypath *p :requests *rid)]` → replay (no-op) or conflict
   (`:conflicting-attempts` +1 via `termval` of the read record); stop
   (unify to the loop's `continue>`).
2. `[(keypath *p :next-seq)]` → nil ⇒ property absent.
   - `CreateProperty`: present → `:property-exists`; else write the
     scalar field only, `[(keypath *p :next-seq) (termval 0)]`; never
     `termval` the property entry, because `:requests` entries from
     pre-creation rejections and the `:ingress-seq` already live under
     this key and must survive.
   - `CreateRoomType`: absent → `:no-such-property`; read
     `[(keypath *p :room-types rt :exists?)]`; true → `:room-type-exists`;
     else write `[(keypath *p :room-types rt :exists?) (termval true)]`
     (field write; the nights map is created lazily on the first night
     write). Room-type existence checks in `InitNight`, `SetRate`, and
     `Reserve` read the same field.
   - `InitNight`: property/room-type checks (`:no-such-property`,
     `:no-such-room-type`); read `[(keypath *p :room-types rt :nights n)]`;
     present → `:night-exists` (regardless of values); else write
     `{:capacity c :rate r :available c}`.
   - `SetRate`: checks; night absent → `:night-not-configured`; else
     write the record with the new rate (`termval` of the read record;
     `available` and existing totals untouched); `RateAccepted r`.
   - `Reserve`: checks in README order. Read the stay with
     `[(keypath *p :room-types rt :nights) (sorted-map-range ci co)]` →
     submap of configured nights (1 seek, ≤ 30 iterations). Missing
     nights = `[ci, co)` minus the submap's keys, ascending; any →
     `Rejected :night-not-configured missing` (no capacity check). Nights
     with `available < quantity`, ascending; any → `Rejected
     :insufficient-capacity nights`; nothing decremented. Else `total =
     Σ rate(n) × quantity` over the stay using the rates just read;
     `seq = (inc next-seq)`; write each night's record with
     `available − quantity` (`termval`, ≤ 30 write-only), the booking
     `{... :state :confirmed :seq seq}`, the `:reserved` event at `seq`,
     `[(keypath *p :next-seq) (termval seq)]`; `ReserveAccepted rid total (− co ci) seq`.
   - `CancelBooking`: property check; read `[(keypath *p :bookings bid)]`
     → nil ⇒ `:no-such-booking` (rejected reserves were never written);
     guest mismatch → `:not-guest`; `:cancelled` → `:booking-cancelled`.
     Else read the booking's stay with the same range navigation
     (1 seek, ≤ 30 iterations), write each night with `available +
     quantity`, booking `:state :cancelled` (all other fields and `:seq`
     untouched), `:cancelled` event at `seq' = (inc next-seq)` with
     `:request-id` = the cancel command's own request-id, `:booking-id
     bid`, and the booking's original `guest-id`, `room-type`, `checkin`,
     `checkout`, `quantity`, `total` (README event shape; a `:reserved`
     event's `:request-id` equals its `booking-id`, a `:cancelled`
     event's does not), `[(keypath *p :next-seq) (termval seq')]`;
     `CancelAccepted bid seq'`.
3. Write the request record with `:conflicting-attempts 0`. Every branch
   of steps 1–3 ends here and unifies into the outer loop's single
   `continue>`.

I/O per command: reserve = 3 seeks + 1 range seek + ≤ 30 iterations,
≤ 35 writes; cancel = 3 seeks + 1 range seek + ≤ 30 iterations, ≤ 34
writes; configuration commands = 3–4 seeks, 2–3 writes (the ingress
position adds one navigation of the top-level entry, which every command
reads anyway, and one field write). All proportional to the stay length
or constant; nothing reads the whole calendar, all bookings, or the
journal.

Invariants by construction: every night of a stay is checked before any
is written, in one event, so a stay either decrements all nights or none
and `available` never drops below 0; capacity is written only by
`init-night!`; a booking's `:total` and `:seq` are written once; a cancel
restores exactly `quantity` on exactly the booking's `[checkin,
checkout)` once, because the second cancel is rejected before any write.

## Query Topologies

- `availability` `[*p *rt *ci *co :> *result]`
  - Input 1: property and room type exist, 30-night stay, all nights
    configured → 2 point reads + 1 range seek (30 iterations); all
    meaningful.
  - Input 2: room type exists, no nights configured in the range → 2
    point reads + 1 range seek returning an empty submap; the range read
    is meaningful because the empty answer produces the required vector
    of nils (it is not a padded read).
  - Input 3: unknown property or room type → 1 or 2 point reads
    (`:exists?` field nil), result nil; the range read is skipped by
    `<<if`.
  - Variable (2 or 3 reads), handled by `<<if`; `*result` emitted exactly
    once.

## Partitioning efficiency

Optimal placement: every read and command is scoped to one property and
the README requires per-property ordering and atomic multi-night
reservations, so `f(property-id) → one task`: `(hash-by :property-id)` on
the depot, default hash routing on `$$properties`. Properties are many; a
popular property is a serial command stream by spec. Seeks/op = tasks
touched = 1.

Dominant read: `get-availability` (query `availability`).

### N = 1 task (single-task baseline)
| Data category | Frequency proportion | Seeks/op | Iterator reads/op |
|---|---|---|---|
| 3-night stay, configured, small property | 0.50 | 1 | 3 |
| 30-night stay, property with 10⁵ nights and 10⁶ bookings | 0.25 | 1 | 30 |
| range with unconfigured gaps | 0.15 | 1 | 2 |
| unknown property or room type | 0.10 | 1 | 0 |
Weighted seeks = 1.0   |   Weighted iterator reads = 9.3

### N = 16 tasks
| Data category | Frequency proportion | Seeks/op | Iterator reads/op |
|---|---|---|---|
| 3-night stay, configured, small property | 0.50 | 1 | 3 |
| 30-night stay, property with 10⁵ nights and 10⁶ bookings | 0.25 | 1 | 30 |
| range with unconfigured gaps | 0.15 | 1 | 2 |
| unknown property or room type | 0.10 | 1 | 0 |
Weighted seeks = 1.0   |   Weighted iterator reads = 9.3

### N = 128 tasks
| Data category | Frequency proportion | Seeks/op | Iterator reads/op |
|---|---|---|---|
| 3-night stay, configured, small property | 0.50 | 1 | 3 |
| 30-night stay, property with 10⁵ nights and 10⁶ bookings | 0.25 | 1 | 30 |
| range with unconfigured gaps | 0.15 | 1 | 2 |
| unknown property or room type | 0.10 | 1 | 0 |
Weighted seeks = 1.0   |   Weighted iterator reads = 9.3

Flat across N; iterator reads depend only on the requested range.

## Design Decisions

- Subindexing: `:room-types`, `:room-types.*.nights`, `:bookings`,
  `:events`, `:requests` are subindexed (unbounded per property). The
  top-level record and the per-room-type record (one handle) are not.
- Colocation: depot `(hash-by :property-id)` = PState key =
  `+group-by *p` routing. The only partitioner is the `+group-by` hop,
  which lands on the task that already holds the property.
- Ordering and yielding: ordered per-property batching (one loop event
  per property per microbatch, sorted by durable `:ingress-seq`), with
  `yield-if-overtime` between commands. `depot.microbatch.max.records`
  = 1000 bounds per-microbatch group memory.
- Entry creation: `create-property!` writes only `:next-seq`, and
  `create-room-type!` writes only `:exists?`; no entry that owns
  subindexed children is ever written whole, so outcomes of commands
  rejected before creation survive creation.
- Range reads: nights are Long keys, so a stay is one contiguous range
  read for reserve, cancel, and availability.
- Seq assignment: only accepted reserve/cancel advance `:next-seq`;
  seqs are contiguous from 1; a booking keeps its `:reserved` seq forever
  and the cancel outcome reports the `:cancelled` event's own seq.
- Rejected reservations are never written to `:bookings`, so
  `:no-such-booking` and `get-booking → nil` hold for them.
- Nothing is deleted.

### Synchronization and client contract

`create-module` creates one `(atom 0)` in its closure shared by every
`:wrap-client`; each command increments it before appending;
`wait-for-processing!` calls
`(rtest/wait-for-microbatch-processed-count ipc module-name "core" @cnt)`.
Module-level cumulative counting keeps this valid across a second client
and `update-module!`. The client holds no business state.

## State primitive selection

- `$$properties` (PState): durable; ≤ 35 writes per command, bounded by
  the 30-night stay limit. `:ingress-seq` lives here because it must
  roll back with the attempt on retry; a TaskGlobal counter would not.
- Per-microbatch grouped vectors (`+vec-agg` output): transient batch
  state inside one microbatch attempt, bounded by
  `depot.microbatch.max.records`; not a TaskGlobal, not durable.
- No TaskGlobal.
- Client atom: sync bookkeeping only.

## Resource usage analysis

### Disk usage (PStates)
- Top-level entry: ≤ 128 B + ~100 B. Room-type entry: ≤ 128 B + ~20 B.
- Night record: 8 B key + 3 Longs → ~40 B; 10⁵ configured nights ≈ 4 MB
  per property.
- Booking record: ids ≤ 256 B + 6 scalars → ~350 B; 10⁶ bookings ≈
  350 MB spread by property hash.
- Event record: ~450 B. Request record: payload ≤ 450 B + outcome ≤ 400 B.
- Growth per accepted reserve ≈ 1.2 KB; per cancel ≈ 900 B; per rejected
  command ≈ 850 B.

### Memory usage (TaskGlobals)
None. Transient per-microbatch memory: the grouped `[pos cmd]` vectors,
≤ `depot.microbatch.max.records` (1000) records per task per microbatch
at ≤ ~450 B each ≈ 450 KB, released when the attempt commits.

### Minimization
- `:cancelled` events repeat the booking's fields (README requires them
  in the event shape).
- Request payloads are verbatim by the `=` replay contract.
- `:ingress-seq` is one Long per property; it cannot be folded into
  `:next-seq`, which must stay contiguous over accepted reserve/cancel
  only. `:exists?` is one Boolean per room type.
- No other duplication.

## Design difficulty log

- **Decision:** ordered per-property batching — pre-agg assigns a
  durable `:ingress-seq` position per command, `+group-by` property with
  `+vec-agg`, explicit sort by position, one `loop<-` per property per
  microbatch applying each command completely, `yield-if-overtime`
  between commands. **Basis:** README per-property issue ordering and
  atomic multi-night reservation; skill cooperative-multitasking rule (a
  popular property queues thousands of commands per microbatch);
  `dataflow.md` states yielding gives up arrival ordering across events;
  `+vec-agg` ordering is undocumented. Alternatives: per-record
  processing without yield (unbounded synchronous stretch), per-record
  processing with yield (a second reservation could read availability
  between the first's check and its night writes), grouping without a
  durable position (order unverifiable), a durable inbox or TaskGlobal
  queue (extra state; TaskGlobal does not roll back on retry).
  **Outcome:** ordering and cooperative yielding both hold at the cost
  of one `+group-by` hop and one top-level read and field write per
  command. Needs build-time verification of post-agg
  `local-transform>`/`loop<-` acceptance and `+group-by`-to-PState
  routing agreement at 2 and 4 tasks, plus a test that issues reserve,
  overlapping reserve, cancel, reserve for one property in one
  microbatch and checks seqs and availability against a sequential
  oracle.
- **Decision:** `create-property!` writes only `:next-seq`;
  `create-room-type!` writes only `:exists?`; no entry owning subindexed
  children is written whole. **Basis:** `paths.md` (`termval` replaces
  the map), `pstate-schema.md` (orphaned subindexed children), and the
  README's requirement that a `:no-such-property` outcome stored before
  creation remains readable and replay-stable. An empty-map `termval`
  for room types was replaced by a Boolean field so existence never
  depends on how an empty fixed-keys value persists. **Outcome:**
  pre-creation outcomes and `:ingress-seq` survive creation. Verify
  with: `reserve!` on an unknown property (rejected) →
  `create-property!` → original outcome still `:no-such-property`,
  replay still rejected, conflicting payload increments the counter,
  both when the commands share a microbatch and when a barrier separates
  them.
- **Decision:** nights as nested Long-keyed subindexed maps (Option A)
  over composite keys (B) or per-night point reads (C). **Basis:** a
  stay is a contiguous Long range: 1 seek + ≤ 30 iterations (≈ 0.65 ms)
  versus 30 seeks (≈ 15 ms); `pstate-schema.md` guarantees numeric sort
  order for Long keys, not for serialized vectors. **Outcome:** reserve,
  cancel, and availability each read a stay with one range seek.
- Depot, topology, and outcome handling are determined directly by the
  shared command conventions and retry requirements.
