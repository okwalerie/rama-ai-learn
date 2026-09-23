# Plan

<!-- Phase 1 artifact for hld-web-crawler (subsystem crawl-frontier-core, whole spec). Design only; no code.
     Revised by the Phase 2 correction pass of 2026-09-23 (bounded-chunk claim path, honest cost model, corrected traces). -->

Owner key: `host` (lowercase). Dedup, the lex-ordered queue, policy, clock, lease, fence
counter, and claim history of a host are read and written together by every operation,
and `get-url` can derive the host from the canonical URL, so all state for a host lives on
task `hash(host)` and every operation is one hop. Canonicalization is a pure function run
in the wrapper (allowed by the README); the module only ever sees canonical URLs.

## Reads

| Read | Method | Path (PState `$$hosts`) | Partition | Cost |
|---|---|---|---|---|
| `get-claim host cid` | `foreign-select-one` | `[(keypath host :claims cid)]` → nil or recorded map | `hash(host)` | 1 seek |
| `get-url url` | wrapper canonicalizes (invalid ⇒ nil, no read); `foreign-select-one` | `[(keypath host :urls canonical)]` → nil or `{:status :fence :lease-expires-at}`; wrapper adds `:url` and `:host` | `hash(host)` | 1 seek |
| `get-host host` | `foreign-select-one` | `[(keypath host :info)]` → nil ⇒ spec defaults; else the map (`:queued` is a maintained counter) | `hash(host)` | 1 seek |
| `list-pending host from limit` | `foreign-select` | `[(keypath host :pending) (sorted-set-range-from from {:max-amt limit :inclusive? false}) ALL]` (`(sorted-set-range-from-start limit) ALL` when `from` is nil); `ALL` explodes the subset so the result is a list of URLs in ascending order, not `[#{…}]` | `hash(host)` | 1 seek + ≤limit iters |

Every read is one PState on one partition ⇒ no query topologies. `:pending` holds exactly
the `:queued` ∪ `:leased` URLs, so the range scan needs no filtering. Both range navigators
(`sorted-set-range-from` with `{:max-amt n :inclusive? bool}` and `sorted-set-range-from-start n`)
are documented in `references/paths.md` "Sorted set navigators" as one seek plus sequential scan
on subindexed sets.

## Writes

One depot `*host-events`, `(hash-by :host)`:

| Write | Record | Processing (on `hash(host)`) |
|---|---|---|
| `discover!` | wrapper canonicalizes, drops invalid, dedups within the call keeping first-occurrence order, groups by host, appends one `Discover{host urls}` per host | read `:info` once, `(nil->val default-info)` (delay 1, rules [], clock 0, fence 0, queued 0, last-claim-at nil, lease nil); for each url in order: read `urls[url]` (1 seek); present ⇒ skip; else `urls[url] := {:status :queued}` (`keypath`+`termval`, no read), `[.. :pending] NONE-ELEM (termval url)`, `queued += 1` (in memory); write `:info` once at the end. |
| `set-host-policy!` | `SetHostPolicy{host delay rules}` | read `:info` `(nil->val default-info)`; write `delay`, `rules` only via `multi-path` (other fields untouched). |
| `claim!` | `Claim{host claim-id now}` | **Steps 1–5 (fixed work, info held in memory):** read `claims[cid]` (1 seek); recorded ⇒ no-op, no writes. Read `:info` `(nil->val default-info)` (1 seek). `T := max(now, clock)`; `clock := T`. Lease live (`T < expires-at`) ⇒ record `:busy`, persist info. Stale lease ⇒ `urls[u] := {:queued}`, `queued += 1`, `lease := nil`. Not ready (`last-claim-at` present and `T < last-claim-at + delay`) ⇒ record `:not-ready`, persist info. **Step 6 (bounded chunks):** at this point no lease exists, so `:pending` contains only `:queued` URLs. Read a sorted subset of up to B = 64 pending URLs: `(local-select> [(keypath *host :pending) (sorted-set-range-from-start 64)] $$hosts :> *chunk)` — no `ALL`/`FIRST` after the navigator, so an empty queue emits an empty subset rather than zero tuples and the `:empty` branch is reachable. Consume the chunk in memory: evaluate rules in order until the first allowed URL; every disallowed URL before it is retired (`urls[u] := {:blocked}`, `[.. :pending (set-elem u)] NONE>`, `queued -= 1`, both writes read-free). Stop at the first allowed URL and grant it; entries after it are neither evaluated nor written. If every entry of the chunk was blocked and the in-memory `queued` is still > 0, read the next chunk with `(sorted-set-range-from *last-blocked {:max-amt 64 :inclusive? false})` and repeat; a short or empty all-blocked chunk with `queued = 0` ⇒ record `:empty`, persist info (`last-claim-at` unchanged). **Step 7 (grant):** `fence += 1`, `urls[u] := {:leased fence T+30}`, `lease := {u fence T+30}`, `last-claim-at := T`, `queued -= 1`; the granted URL stays in `:pending`; persist info once and the claim outcome once. No yield, no async operation, and no partition hop anywhere between the step-1 guard and the final writes. |
| `complete!` | `Complete{host fence outcome now}` | read `:info` (1 seek). `C := max(now, clock)`. Lease present, `fence` equal, `C < expires-at` ⇒ `urls[u] := {:done|:failed}`, `[.. :pending (set-elem u)] NONE>`, `lease := nil`, `clock := C`, persist info. Otherwise no write at all. |

### Work contract (public)

`discover!` is fixed work per URL (one point seek plus read-free writes), independent of queue
size. `claim!` is fixed work plus amortized fixed work per blocked URL skipped (each URL is
retired as `:blocked` at most once, ever); it reads bounded chunks from the head of a sorted
structure and never scans the queue. `complete!` is fixed work. Nothing counts by scanning.

### Claim cost model (design estimate, not a latency claim)

For S blocked URLs skipped in one claim, chunk size B = 64, grant G ∈ {0,1}, stale requeue E ∈ {0,1}:

- Fixed: 1 seek `claims[cid]` + 1 seek `:info`.
- Pending range seeks ≤ ⌈(S+1)/B⌉; entries iterated ≤ B·⌈(S+1)/B⌉ (≤ S + B); memory O(B).
- Robots evaluations S + G, each over ≤ 100 rules (in-memory string prefix checks).
- Logical PState transforms 2S + G + E + 2 (per blocked URL: status record + pending deletion;
  grant: URL status; stale URL reset; final `:info` + claim outcome).

Worked case, S = 1,000 blocked then one grant: 16 range seeks and ≤ 1,024 iterated entries. At the
skill's constants (0.5 ms/seek, 5 µs/entry) that is roughly 13 ms of **traversal**, plus 2 fixed
seeks. It excludes 1,001 robots evaluations and 2,003 logical transforms, whose cost this plan does
not price; therefore **no total-latency figure is claimed** for this case, and the 50 ms read
aspiration is not asserted for it. A 32-entry chunk would cost 32 seeks (~21 ms traversal) for the
same S; a per-entry read (B = 1) would cost 1,001 seeks (~500 ms traversal). B = 64 is a reference
design choice; the public contract is only the O(S) amortized bound above, and tests must not
depend on the chunk size, seek count, or PState layout. Whether microbatch buffers the 2,003
transforms into fewer physical writes is to be measured during BUILD, not assumed.

## PState Design

One PState: every piece of state shares key `host` and partitioner `hash(host)`.

```clojure
(declare-pstate mb $$hosts
  {String (fixed-keys-schema
    {:info    (fixed-keys-schema
                {:delay         Long
                 :rules         (vector-schema (fixed-keys-schema {:path-prefix String :allow? Boolean})) ; ≤100 (grammar)
                 :clock         Long
                 :fence         Long
                 :last-claim-at Long                                   ; nil until first grant
                 :queued        Long
                 :lease         (fixed-keys-schema {:url String :fence Long :expires-at Long})}) ; nil when none
     :urls    (map-schema String                                       ; canonical url -> state, all urls ever seen
                (fixed-keys-schema {:status Keyword                    ; :queued | :leased | :done | :failed | :blocked
                                    :fence Long :lease-expires-at Long}) ; non-nil iff :leased
                {:subindex-options {:track-size? false}})
     :pending (set-schema String {:subindex-options {:track-size? false}}) ; :queued ∪ :leased, sorted = String order
     :claims  (map-schema String
                (fixed-keys-schema {:status Keyword :reason Keyword :url String :fence Long :lease-expires-at Long})
                {:subindex-options {:track-size? false}})})})
```

`:info` groups the scalars so `get-host` and the per-claim/complete read are one seek
returning a plain map (rules ≤100 × ~270 B stay inline). `:urls` is the dedup set and the
`get-url` index in one structure (up to 1,000,000+ per host ⇒ subindexed). `:pending` is a
separate sorted set because "smallest pending" and `list-pending` need a range structure
over pending URLs only, whereas `:urls` also holds retired ones; the lease's fence/expiry
are copied into the leased URL's entry so `get-url` is one seek instead of a two-read query.

`{:subindex-options {:track-size? false}}` is the documented form of subindexing with size
tracking disabled (`references/pstate-schema.md` "Size Tracking": tracking is on by default and
"adds an extra disk read on every write"; `references/pstate-schema-clojure-api.md`). No operation
queries the size of `:urls`, `:pending`, or `:claims` — `:queued` is a maintained counter in `:info`
— so tracking would only add a read to every `discover!`, claim, and completion write. The saving is
to be confirmed by measurement during BUILD; correctness does not depend on it.

Alternatives costed:
- **Global `$$urls` hashed by URL for dedup** + host-keyed queue: `discover!` becomes 2 hops
  per URL (dedup task, then host task) at 50,000 URLs/s; `get-url` stays 1 seek. Rejected:
  doubles the hop count of the highest-volume write for no read gain.
- **`:pending` as a map url → status with `:queued` scan for the head**: the head search
  would iterate over leased/blocked entries. With the set holding only queued ∪ leased, and
  the lease already resolved (busy ⇒ stopped, stale ⇒ requeued) before step 6, the first
  element of `:pending` is the head. Chosen design keeps the head read at one bounded range seek.
- **Per-entry head reads (B = 1)** for the blocked skip: same O(S) contract, but one seek per
  blocked URL (1,001 seeks ≈ 500 ms traversal for S = 1,000) versus 16 seeks with B = 64.
  Rejected on seek count; see cost model.

## Depots

- `*host-events`: `(hash-by :host)`; record types `Discover` (per-host URL vector),
  `SetHostPolicy`, `Claim`, `Complete`. One depot so a client's sequential writes for one
  host are one depot partition in invocation order; per-host element order of a
  `discover!` batch is preserved inside its `Discover` record; different hosts of one batch
  become independent records (relative order unspecified, as the spec allows).

## Topologies and PStates

- `core`: **microbatch** (default microbatch; no write requires millisecond visibility or
  an ack return — claim outcomes are read via `get-claim` after `wait-for-processing!`).
  Owns `$$hosts`.
  - Concerns: dedup/enqueue, policy replace, claim (clock, lease, robots skip, fence,
    outcome record), complete. None needs stream semantics.
  - Non-idempotent writes: `queued` counter, fence increment, clock advance, blocked
    retirements. Microbatch replays a batch exactly once; in addition the claim-id guard
    makes a re-executed claim a no-op (never re-issues or skips a fence), dedup makes a
    re-executed discover a no-op, and a completed lease is gone so a re-executed complete
    is rejected.
  - Same-host records in a batch run sequentially in depot order on one task and see prior
    writes, so a stale-lease requeue is immediately eligible in the same claim, and of two
    concurrent claims exactly one is granted.

No stream topology, no internal depot, no tick depot (time is caller-supplied).

## Query Topologies

None. Every read is one `foreign-select`/`foreign-select-one` on one partition.

## Partitioning efficiency

Optimal placement: `f(host) → hash(host) mod N`. 40M hosts ⇒ negligible hash variance.
Per-key skew: one host may hold 1,000,000 pending URLs (≈150 MB subindexed on one task,
acceptable) and its claims are serialized on one task by the single-lease rule anyway;
claims/completions at 50,000/s spread across hosts. Storing placement state cannot improve
this because every operation on a host must serialize on that host's lease and clock.

Dominant read: `get-url` (point read after each claim/complete). Categories by URL state.

### N = 1 task (single-task baseline)
| Data category | Frequency proportion | Seeks/op | Iterator reads/op |
|---|---|---|---|
| queued or leased URL (host with large queue) | 0.6 | 1 | 0 |
| retired URL (done/failed/blocked)            | 0.3 | 1 | 0 |
| unseen or invalid URL                        | 0.1 | ≤1 | 0 |
Weighted seeks = 1.0   |   Weighted iterator reads = 0

### N = 16 tasks
| Data category | Frequency proportion | Seeks/op | Iterator reads/op |
|---|---|---|---|
| queued or leased URL (host with large queue) | 0.6 | 1 | 0 |
| retired URL (done/failed/blocked)            | 0.3 | 1 | 0 |
| unseen or invalid URL                        | 0.1 | ≤1 | 0 |
Weighted seeks = 1.0   |   Weighted iterator reads = 0

### N = 128 tasks
| Data category | Frequency proportion | Seeks/op | Iterator reads/op |
|---|---|---|---|
| queued or leased URL (host with large queue) | 0.6 | 1 | 0 |
| retired URL (done/failed/blocked)            | 0.3 | 1 | 0 |
| unseen or invalid URL                        | 0.1 | ≤1 | 0 |
Weighted seeks = 1.0   |   Weighted iterator reads = 0

Flat in N. `list-pending` is 1 seek + ≤limit iterations at every N; `get-host` and
`get-claim` are 1 seek.

## Design Decisions

- **Subindexing**: `:urls`, `:pending`, `:claims` (all unbounded per host), with size tracking
  disabled because no operation reads their size. `:info` is a fixed record with a
  grammar-bounded rules vector (≤100), inline.
- **Colocation**: depot `(hash-by :host)` equals the PState key; no repartitioning; every
  write is a single-task transaction, so lease/clock/fence/queued updates are atomic.
- **Canonicalization in the wrapper**: pure function; stored and returned URLs are
  canonical; `get-url` on an invalid URL returns nil without a read.
- **Ordering**: one depot partition per host ⇒ one client's writes for a host process in
  invocation order; cross-client claims/completions for a host serialize in append order,
  yielding exactly the acceptable outcomes enumerated in IMPLICIT_SPEC.
- **Retry safety**: microbatch exactly-once plus per-event guards (claim-id recorded, URL
  seen, lease fence matched) ⇒ no double grant, no double enqueue, no fence skip.
- **Rejected completion touches nothing**: the branch performs no `local-transform>`.
- **Bounded chunks, no yield, in the claim skip path.** The governing rule is dataflow.md
  "Yielding and ordering": "Do NOT yield on a path where correctness depends on same-key events
  processing in order". Same-host claims depend on order: a later claim queued behind a
  suspended claim would run before the lease is written, find the head allowed, and grant a
  second live lease (or report `:granted`/`:empty` where the serial history says `:busy`).
  This handler has no order-preserving suspension protocol — its skip path is variable-length
  and the fast path of a following event has no matching yield point — so yielding lets later
  host events overtake it. Writing a provisional lease before a yield is not a solution: a
  concurrent claim would see `:busy` even when the queue turns out to be empty, changing the
  serial outcomes IMPLICIT_SPEC requires. Instead the skip path bounds its synchronous work by
  reading 64-entry sorted chunks (≤ ⌈(S+1)/64⌉ seeks, ≤ S + 64 entries) rather than one seek per
  blocked URL. `:allow-yield?` is not used: it would reintroduce the same ordering hazard on a
  read that is already bounded to 64 entries. See "Claim cost model" for what is and is not
  claimed about latency.
- **Output shape**: the wrapper constructs every returned map explicitly — `get-claim` with exactly
  the keys of its status, `get-url` with five keys and `get-host` with seven (explicit nils, `:rules`
  as the stored vector of `{:path-prefix :allow?}` maps, `[]` default); stored records are never
  returned as-is.
- **Synchronization (harness only)**: one counter created in `create-module` and shared by
  all wrappers, incremented once per depot append immediately after that `foreign-append!` returns
  successfully (a `discover!` with h hosts appends h records and adds h, all before the method
  returns; a call with no valid URL appends nothing and adds 0); `wait-for-processing!` calls
  `rtest/wait-for-microbatch-processed-count ipc module "core" @counter`. Cross-client
  barriers hold at 2 and 4 tasks; the wrapper holds no business state.

## State primitive selection

- `$$hosts` (PState): durable source of truth; per-source-event write volume O(1) per URL
  discovered / per claim (plus one retirement per blocked URL, each at most once ever).
  Bounded by inputs.
- No TaskGlobals; no external systems. Robots evaluation runs on the inline rules vector.

## Resource usage analysis

### Disk usage (PStates)
- `:urls`: key ≤2,048 B (typical ~80 B) + ~30 B value + subindex overhead ≈ 130 B typical.
  1B URLs ≈ 130 GB total, ≈1 GB per task at N = 128; a 1M-URL host ≈ 130 MB on one task.
- `:pending`: ~100 B per pending URL; 1B pending ≈ 100 GB total, /N.
- `:claims`: ≤64 B key + ~120 B value ≈ 200 B; grows at 50,000/s ≈ 10 MB/s cluster-wide.
- `:info`: ~100 B + rules ≤27 KB; 40M hosts ≈ 4–8 GB total, /N.
- Depot `*host-events`: `Discover` records ≤100 URLs (≤200 KB), subject to retention.

### Memory usage (TaskGlobals)
None. The claim skip path holds at most one 64-entry chunk in memory per event.

### Minimization
- A pending URL appears in both `:urls` (status) and `:pending` (sorted set): the second
  copy (~100 B) buys a one-seek bounded head read and a bounded `list-pending`; merging them
  would force head selection to skip retired entries (unbounded). Justified.
- Lease fence/expiry are duplicated into the leased URL's `:urls` entry (24 B, one URL per
  host) to make `get-url` one seek. Justified.
- `:queued` is a maintained counter because the spec forbids counting by scan; it also lets
  subindex size tracking be disabled on all three collections.

## Design difficulty log

- Decision: host-keyed single PState with `:urls` (dedup + status), `:pending` (sorted
  set), `:claims`, `:info`. Basis: every write serializes on the host's lease/clock, and
  `get-url` derives the host from the URL, so host-keyed dedup costs 1 hop versus 2 for a
  URL-hashed dedup set at 50,000 URLs/s. Outcome: all operations 1 hop; reads 1 seek
  (`list-pending` 1 seek + limit).
- Decision: `:pending` holds queued ∪ leased; the lease is resolved (busy or requeued) before
  step 6, so the head is the first element and the skip path reads 64-entry sorted chunks.
  Basis: `claim!` must not scan the queue; blocked skips are amortized because a URL is
  retired at most once; per-entry reads cost 1,001 seeks for the 1,000-blocked case versus 16.
  Outcome: reference trace host h, pending {/a,/b,/c}, delay 5, disallow `/a`: claim at 10
  blocks `/a` (queued 2), grants `/b` fence 1 (queued 1, lease expires 40, last-claim-at 10);
  claim at 40 requeues `/b` (queued 2), regrants `/b` fence 2 (queued 1, expires 70,
  last-claim-at 40); completion fence 2 at 69 retires `/b` (queued 1, lease nil, clock 69,
  last-claim-at 40, fence counter 2). The loop deliberately does not yield (see Design
  Decisions); the earlier ~10 ms and ~500 ms figures are superseded by the cost model above.
- Decision: microbatch only. Basis: fence, clock, and counter writes are non-idempotent;
  no write needs ms visibility. Outcome: retries never re-issue a fence or double-count
  `:queued`.
- Decision: canonicalize and group by host in the wrapper. Basis: README permits it; it is
  pure; it makes the depot partitioner the host and preserves per-host batch order in one
  record. Outcome: h appends per batch, no module-side parsing.
- Decision: disable subindex size tracking on `:urls`, `:pending`, `:claims`. Basis:
  pstate-schema.md documents an extra disk read per write when tracking is on, and no
  operation queries those sizes. Outcome: fewer reads per `discover!`/claim/complete write;
  magnitude to be measured during BUILD.
