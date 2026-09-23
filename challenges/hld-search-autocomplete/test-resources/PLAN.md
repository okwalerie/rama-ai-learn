# Plan

<!-- Phase 1 artifact for hld-search-autocomplete (subsystem autocomplete-core, whole spec). Design only; no code. -->

Owner key for ordering: `locale`. Placement key for data: `pk = [locale, first 2 chars of
the phrase/prefix]` (1 char when the string has length 1). Every prefix of length ≥2 of a
phrase shares the phrase's first two characters, so all but one of a phrase's ≤64 prefix
indexes are local to `hash(pk)`; the length-1 prefix lives on `pk1 = [locale, first char]`.
`suggest` therefore routes to exactly one task. State is keyed by generation so a publish
never touches the previous generation's trend data.

Two generation fields with distinct authority: `$$locale-auth` (owner-authoritative, lives
only on the locale's depot task A = `hash(locale)`, written synchronously by publish before
anything is routed) and `$$locale-gen` (replicated read metadata on every task, written by
a terminal `|all` branch, monotone). Every phrase operation is accepted and stamped with the
generation at A, then travels one ordered path A → X (`hash(pk)`) → Y (`hash(pk1)`).

## Reads

| Read | Method | Reads | Partition | Cost |
|---|---|---|---|---|
| `suggest locale prefix k` | query topology `suggest-q` | local `$$locale-gen[locale]` (replicated metadata, 1 seek); nil ⇒ `[]`. Else local `$$index [locale :gens g :prefixes prefix] (sorted-set-range-from-start k)` (1 seek + k iters); elements decode to `{:phrase :score}` already in (score desc, phrase asc) order | `hash(pk(locale, prefix))` | 2 seeks + ≤k iters |
| `get-phrase locale phrase` | query topology `phrase-q` | local `$$locale-gen[locale]`; local `$$index [(keypath locale :blocked) (subselect (set-elem phrase))]` (membership as a value); if gen non-nil local `[locale :gens g :phrases phrase]` — a plain `{:base :sessions}` record (session sets live in the sibling `:sessions` map, so no subindexed handle is ever returned) | `hash(pk(locale, phrase))` | 2–3 seeks |
| `get-generation locale` | `foreign-select-one` | `$$locale-auth [(keypath locale)]` (owner-authoritative; routed by `hash(locale)`) | `hash(locale)` | 1 seek |

Suggest work is independent of candidate count and corpus size: the per-prefix candidate
index is a subindexed sorted set whose key encodes `(score desc, phrase asc)`, so the top-k
is the first k elements — no scan, no in-query sort.

## Writes

One depot `*locale-events`, `(hash-by :locale)`, so a client's writes to one locale are in
one depot partition in invocation order. The depot task A is the locale **owner**: it holds
`$$locale-auth[locale]`, decides acceptance for every record synchronously (no async
boundary between the read of `$$locale-auth` and its write), and stamps every accepted
phrase operation with the generation it was accepted under. All accepted operations —
snapshot entries, searches, block/unblock — are emitted as one record type `PhraseOp
{op locale gen phrase base session-id}` on **one** branch and cross **one** partitioner
`(|hash *pk)` to X, then (for the length-1 prefix) **one** partitioner `(|hash *pk1)` to Y.
Messages from one task to another are processed in send order (stream.md "Partition
ordering", all topology types), so a single owner-to-data path gives per-phrase FIFO
A → X → Y: X and Y never consult a generation; they apply the stamped one.

Owner-side dataflow (direct emit from `%mb`, no `<<batch`: there is no aggregation, and
plain dataflow permits a terminal `<<branch`; records are emitted per task in depot order,
microbatch.md):

```clojure
(%mb :> *rec)
(local-select> [(keypath *locale)] $$locale-auth :> *auth)          ; nil when no snapshot
(<<cond
  (case> (instance? Publish *rec))
  (<<if (or (nil? *auth) (> *gen *auth))
    (local-transform> [(keypath *locale) (termval *gen)] $$locale-auth)  ; authoritative, sync
    (anchor> <acc>)
    (<<branch <acc>                                                  ; TERMINAL metadata branch
      (|all)
      (local-transform> [(keypath *locale) (nil->val 0) (term (fn [g] (max g *gen)))]
                        $$locale-gen))                               ; monotone; never $$locale-auth
    (ops/explode *entries :> [*phrase *base])                        ; continues from <acc>, on A only
    (->PhraseOp :init *locale *gen *phrase *base nil :> *op))

  (case> (instance? Search *rec))
  (filter> (= *gen *auth))                                           ; stale/future/no-gen: dropped at A
  (->PhraseOp :count *locale *gen *phrase nil *session-id :> *op)

  (case> (instance? Block *rec))
  (->PhraseOp :block *locale *auth *phrase nil nil :> *op)           ; gen may be nil

  (default>)
  (->PhraseOp :unblock *locale *auth *phrase nil nil :> *op))
(pk *locale *phrase :> *pk)
(|hash *pk)                                                          ; the single A→X edge
;; X: dispatch on op, then for the length-1 prefix:
(|hash *pk1)                                                         ; the single X→Y edge
```

A rejected publish (`gen <= auth`) and a rejected search emit nothing: no broadcast, no
routing, no trace. The `|all` branch is terminal and metadata-only; the explode continues
from `<acc>` on A, so entries are sent exactly once and only from A.

| Write | Record | Processing |
|---|---|---|
| `publish-snapshot!` | `Publish{locale generation entries}` | at A: read `$$locale-auth[locale]` (1 seek); `generation <= auth` ⇒ no-op (a retried or older publish later in the same batch sees the synchronously written value). Else `local-transform> $$locale-auth[locale] := g` (authoritative), terminal `<<branch` `|all` `$$locale-gen[locale] := max(old, g)` (replicated metadata only), then from the anchor `ops/explode entries` ⇒ `PhraseOp :init` ⇒ `|hash pk` ⇒ X: write `[locale :gens g :phrases phrase] := {:base b :sessions 0}`; if phrase ∉ `:blocked` insert key `K(b, phrase)` into `[locale :gens g :prefixes p]` for every prefix p of length ≥2, then `|hash pk1` ⇒ Y: insert `K(b, phrase)` into `[locale :gens g :prefixes p1]`. Work ∝ entries × phrase length; nothing from generation g−1 is read or deleted. |
| `record-search!` | `Search{locale generation session-id phrase}` | at A: read `$$locale-auth[locale]` (1 seek); `≠ generation` (including nil) ⇒ drop (no trace). Else `PhraseOp :count` stamped `gen` ⇒ `|hash pk` ⇒ X: read `[.. :gens gen :phrases phrase]` (1 seek, plain `{:base :sessions}` record); read `[.. :gens gen :sessions phrase (subselect (set-elem session-id))]` (1 seek); non-empty ⇒ drop. Else `[.. :sessions phrase] NONE-ELEM (termval session-id)`, `sessions := sessions + 1` (`termval {:base 0 :sessions 1}` for a novel phrase). If not blocked: `old = base + 10(sessions−1)` (a key exists iff base > 0 or sessions−1 > 0), `new = old + 10`: for each prefix of length ≥2 remove `K(old, phrase)` (if existed) and insert `K(new, phrase)`; `|hash pk1` ⇒ Y: same for the length-1 prefix. X never reads a generation: the op's stamp is the generation under which A accepted it, and A's FIFO guarantees the `:init` entries of that generation precede it on X. |
| `block-phrase!` / `unblock-phrase!` | `Block{locale phrase}` / `Unblock{locale phrase}` | at A: stamp `gen := auth` (may be nil) ⇒ `PhraseOp :block`/`:unblock` ⇒ `|hash pk` ⇒ X: toggle `[locale :blocked phrase]` (no-op if unchanged). If `gen` non-nil and the phrase is a candidate in `gen` (`[.. :gens gen :phrases phrase]` with base > 0 or sessions > 0): block ⇒ remove `K(score, phrase)` from all local prefixes, `|hash pk1` ⇒ Y remove; unblock ⇒ insert likewise. Blocked phrases keep counting; only the index entries change. |

`K(score, phrase)` = zero-padded 8-digit `(99,999,999 − score)` + `"|"` + phrase. Scores are
≤ 10^6 + 10 × 10^6 < 10^8, so ascending String order of K is score desc, then phrase asc
(`String.compareTo` on ASCII). Per event: 3 seeks (1 at A, 2 at X) + ≤128 subindexed puts/removes + 2 hops (A→X, X→Y).

## PState Design

```clojure
;; Owner-authoritative generation: exists only on hash(locale) (the depot task A). Written
;; synchronously by an accepted publish; read by A for every record; never written by |all.
(declare-pstate mb $$locale-auth {String Long})

;; Replicated read metadata: written on every task by the terminal |all branch of an
;; accepted publish with a max guard; read by suggest-q / phrase-q on the placement task.
;; Distinct PState from $$locale-auth (different partitioner and different authority).
(declare-pstate mb $$locale-gen {String Long})

;; Partitioned by hash(pk); one PState because :blocked and :gens share key `locale` and partitioner.
(declare-pstate mb $$index
  {String (fixed-keys-schema                                            ; locale
    {:blocked (set-schema String {:subindex? true})                     ; block list, generation-independent (≤10,000)
     :gens    (map-schema Long                                          ; generation -> that generation's data
                (fixed-keys-schema
                  {:phrases  (map-schema String                         ; phrase -> plain trend record (read whole, 1 seek)
                               (fixed-keys-schema {:base Long :sessions Long})
                               {:subindex? true})
                   :sessions (map-schema String                         ; phrase -> counted session-ids (kept apart so
                               (set-schema String {:subindex? true})    ; the phrase record never carries a handle)
                               {:subindex? true})
                   :prefixes (map-schema String                         ; prefix -> sorted candidate index
                               (set-schema String {:subindex? true})    ; elements K(score, phrase)
                               {:subindex? true})})
                {:subindex? true})})})
```

Options costed for the suggest index:
- **Option A (chosen)**: per-(gen, prefix) subindexed sorted set of all candidates keyed
  `K(score, phrase)`. Suggest = 1 seek + k iters. Event = 2 puts per prefix (remove/insert)
  × 64 = 128 puts, all local except the length-1 prefix. Block removal is one delete per
  prefix and the k-th result never shrinks (the full ordered candidate set remains).
- **Option B**: per-prefix top-10 vector. Suggest = 1 seek. Event = 64 read-modify-writes
  (64 seeks) versus A's 128 blind puts (no seeks). Blocking a member shrinks the vector
  below k with no bounded way to refill (up to 10,000 blocked phrases per locale). Rejected
  on correctness under block and on 64 seeks/event.
- **Option C**: A plus a top-10 vector cache per prefix. Saves ≈ k × 5 µs per suggest but
  adds 64 seeks per event (6.4M seeks/s at 100,000 events/s). Rejected on total cost.

Generation-keyed `:gens` makes publish independent of the previous generation's size: the
old entries become unreferenced. Superseded generations are retained (see log).

## Depots

- `*locale-events`: `(hash-by :locale)`; record types `Publish`, `Search`, `Block`,
  `Unblock`. The owner task does one seek per record (acceptance + stamp) and forwards;
  all per-phrase work is on `hash(pk)`, so a hot locale does not concentrate index work on
  one task.

## Topologies and PStates

- `core`: **microbatch** (default microbatch; no write needs millisecond visibility or an
  ack return; freshness "within minutes" is the stated aspiration). Owns `$$locale-gen`
  and `$$index`.
  - Concerns: generation switch + corpus load, session-deduplicated counting with index
    maintenance, block list. None needs stream semantics.
  - Non-idempotent writes: `sessions` increment, index remove/insert pairs. Microbatch
    replays exactly once; the session-id set also makes a re-executed event a no-op, and the
    generation guard makes a re-executed publish a no-op (counts are never reset twice).
  - Atomic switch: a publish's authoritative write, its `|all` metadata write and all its
    index writes commit in one microbatch, so `suggest` never mixes generations.
  - One owner-to-data path: every accepted operation is a `PhraseOp` on one branch through
    one `(|hash *pk)`; the metadata broadcast is a terminal branch that emits nothing
    downstream (no N-fold explode) and writes only `$$locale-gen` with `max` (a delayed or
    re-sent older broadcast can neither lower it nor touch `$$locale-auth`).
- Query topologies `suggest-q`, `phrase-q` (read-only, single partition each).

No stream topology, no internal depot, no tick depot.

## Query Topologies

Both queries route with `(|hash *pk)` where `*pk` is computed by the same function the topology uses,
`pk(locale, s) = [locale (subs s 0 (min 2 (count s)))]`; never `|hash locale` — `$$index[locale]`
exists on every placement task and only the pk task holds the requested prefix or phrase.

- `suggest-q [locale prefix k]`: `|hash pk(locale, prefix)`; read gen; `<<if` gen nil ⇒
  emit `[]` (1 read, 1 meaningful); else read `(sorted-set-range-from-start k)` on the
  prefix's set (2 reads, 2 meaningful; an absent prefix set is the empty-result case and is
  still the only way to learn it). Variable count handled by the conditional; `|origin`.
  Example: live locale, prefix "app", k 5 ⇒ 2 reads. Example: locale without snapshot ⇒ 1.
- `phrase-q [locale phrase]`: `|hash pk`; read gen and blocked (2); if gen non-nil read the
  phrase entry (3rd). Example: live locale ⇒ 3 reads, 3 meaningful; no generation ⇒ 2.
  Variable, handled by `<<if`; `|origin`.

## Partitioning efficiency

Optimal placement for `suggest`: all prefixes of one phrase on as few tasks as possible,
with the keyspace large enough to balance: `f(locale, s) → hash([locale, s[0:2]])`. Realized
keys ≈ 200 locales × ≤1,332 two-char combinations (tens of thousands) ⇒ good balance at
N = 128. Per-key skew: the hottest 2-char group of a hot locale takes a few percent of
500,000 suggests/s on one task, which is within capacity. Alternatives: `hash(locale)`
(200 keys ⇒ ~37 % of 128 tasks idle, hot locale entirely on one task) and
`hash([locale, full prefix])` (64 hops per event instead of 2). Both rejected on the
numbers above.

Dominant read: `suggest`.

### N = 1 task (single-task baseline)
| Data category | Frequency proportion | Seeks/op | Iterator reads/op |
|---|---|---|---|
| live locale, prefix with ≥ k candidates | 0.70 | 2 | 10 |
| live locale, sparse prefix (< k)         | 0.25 | 2 | 3 |
| locale without a generation              | 0.05 | 1 | 0 |
Weighted seeks = 1.95   |   Weighted iterator reads = 7.75

### N = 16 tasks
| Data category | Frequency proportion | Seeks/op | Iterator reads/op |
|---|---|---|---|
| live locale, prefix with ≥ k candidates | 0.70 | 2 | 10 |
| live locale, sparse prefix (< k)         | 0.25 | 2 | 3 |
| locale without a generation              | 0.05 | 1 | 0 |
Weighted seeks = 1.95   |   Weighted iterator reads = 7.75

### N = 128 tasks
| Data category | Frequency proportion | Seeks/op | Iterator reads/op |
|---|---|---|---|
| live locale, prefix with ≥ k candidates | 0.70 | 2 | 10 |
| live locale, sparse prefix (< k)         | 0.25 | 2 | 3 |
| locale without a generation              | 0.05 | 1 | 0 |
Weighted seeks = 1.95   |   Weighted iterator reads = 7.75

Flat in N. The `$$locale-gen` read is on the same task as the index read (replicated), so
no read fans out.

## Design Decisions

- **Subindexing**: `:blocked` (≤10,000), `:gens` (grows per publish), `:phrases`
  (≤110,000 per generation), `:session-ids` (≤1,000,000 per phrase), `:prefixes` and each
  prefix's candidate set (thousands per single-character prefix). Nothing unbounded is
  inline.
- **Colocation**: prefixes of length ≥2 are local to the phrase's task; exactly one hop per
  event/publish entry/block for the length-1 prefix.
- **Ordering**: depot partition per locale ⇒ one client's publish/event/block sequence is
  applied in invocation order at the owner task A, which is the only place acceptance is
  decided (`$$locale-auth`, read and written synchronously per record). Accepted operations
  leave A on one branch through one partitioner, so A→X is a single FIFO edge per pk task
  and X→Y a single FIFO edge per pk1 task (stream.md "Partition ordering"). Entries originate
  only at A (anchor before the terminal `|all`). X and Y apply the stamped generation and
  never read `$$locale-gen`, so no data-path decision depends on broadcast timing.
- **Authority vs metadata**: `$$locale-auth` is the truth (owner only, monotone by the
  guard); `$$locale-gen` is a monotone replicated copy used only by reads. The broadcast is
  metadata-only and terminal: it cannot fan out entries, cannot roll back `$$locale-auth`,
  and with `max` cannot roll back the replica either.
- **Generation keying** removes any dependence of publish on previous trend data and makes
  Y's merges safe without a generation check (they target the event's own generation).
- **Retry safety**: microbatch exactly-once; session-id set and generation guard are
  additional idempotence guards.
- **Output shape**: the wrapper constructs `get-phrase` with all six keys explicitly and `suggest`
  entries as exactly `{:phrase :score}` decoded from `K`; stored records are never returned as-is.
- **Synchronization (harness only)**: one counter created in `create-module`, shared by all
  wrappers, incremented immediately after each `foreign-append!` returns successfully (inside the
  `!` method, so only actual appends are counted); `wait-for-processing!` calls
  `rtest/wait-for-microbatch-processed-count ipc module "core" @counter`. Cross-client
  barriers hold at 2 and 4 tasks; no business state in the wrapper.

## State primitive selection

- `$$locale-auth` (PState, `hash(locale)`): 200 entries; read once per record at the owner
  task; the acceptance decision must survive restart, so durable.
- `$$locale-gen` (PState, `|all`): 200 entries per task; written a few times per day per
  locale; read locally on every suggest. Durable so a restart cannot lose the current
  generation.
- `$$index` (PState, `hash(pk)`): durable source of truth; per-event write volume ≤128
  index puts + 1 session element + 1 counter, bounded by the 64-char phrase grammar.
- No TaskGlobals: a per-task generation cache would only save a RocksDB read of a 200-entry
  PState that is always cache-resident; not worth non-durable state. No external systems.

## Resource usage analysis

### Disk usage (PStates)
- `:phrases` per generation per locale: ≤110,000 × ~60 B ≈ 7 MB plus session sets
  (~80 B per session; a 1M-session phrase ≈ 80 MB, on one task).
- `:prefixes` per generation per locale: ≤110,000 phrases × ≤64 prefixes × ~50 B
  ≈ 350 MB worst case; ×200 locales ≈ 70 GB per generation cluster-wide, /N per task.
- `:blocked`: ≤10,000 × ~50 B per locale.
- `$$locale-auth`: 200 × ~20 B cluster-wide; `$$locale-gen`: 200 × ~20 B per task.
- Depot `*locale-events`: `Publish` records ≤ ~500 KB; `Search` ~150 B.
- Superseded generations are not reclaimed by this plan (they are never read; see log).

### Memory usage (TaskGlobals)
None.

### Minimization
- A phrase's score appears in up to 64 prefix index entries; this duplication is what
  makes `suggest` independent of candidate count (required). Each entry is ~50 B.
- `base` and `sessions` are stored once per (generation, phrase); scores are derived.
- No other duplication.

## Design difficulty log

- Decision: per-(generation, prefix) sorted candidate sets keyed `K(score, phrase)`, placed
  by `[locale, first 2 chars]`. Basis: suggest must be independent of candidate count and
  exact under block/unblock; a top-10 vector cannot refill after a block; the 2-char
  placement keeps 63 of 64 prefix updates local while giving tens of thousands of keys
  for balance. Outcome: suggest = 2 seeks + k iters on one task; event = 3 seeks +
  ≤128 puts + 1 hop; 12.8M puts/s cluster-wide at peak (≈100,000/s per task at N = 128).
- Decision: generation-keyed trend and index data; `$$locale-gen` replicated `|all`.
  Basis: publish must not depend on previous trend size and the switch must be atomic;
  microbatch commits the `|all` write and all index writes together. Outcome: old
  generations become unreferenced garbage (≈350 MB per locale per generation worst case,
  cluster-wide). Reclamation is outside the spec's requirements and is left to a later
  background mechanism; this is the plan's main remaining operational uncertainty.
- Decision: owner-authoritative generation with stamping. Basis: validating a search on X
  against a replicated copy makes correctness depend on the relative delivery of two
  independent A→X messages (`|all` metadata vs `|hash` event) and on the broadcast never
  being reordered; a novel search stamped for a future generation could otherwise be
  admitted before its publish. Deciding at A against `$$locale-auth` and carrying the
  generation on the op makes every data-side write a function of the op alone. Outcome:
  one extra seek at A per record (replaces the generation seek X used to do), two distinct
  typed generation fields, one owner-to-data branch.
- Decision: microbatch only. Basis: session counting and index moves are non-idempotent;
  no ms-visibility requirement. Outcome: exactly-once without dedup bookkeeping beyond
  the session set the spec already requires.
- Decision: encode order in the set element rather than a `[score phrase]` vector key.
  Basis: subindex order is lexicographic on the serialized key; a fixed-width decimal
  string guarantees score-desc/phrase-asc order equal to `String.compareTo`. Outcome:
  no custom comparator; decoding is a split at position 8.
