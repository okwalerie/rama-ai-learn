# Plan — hld-file-sync (subsystem: file-sync-metadata, whole spec)

Private reference-implementation plan (Phase 1). Inputs: README.md,
`src/hld_file_sync/protocol.clj`, `test-resources/IMPLICIT_SPEC.md`,
`test-resources/DECOMPOSITION.json`. Design only; no topology code.

## Reads

All reads are scoped by `ns-id`, which is the depot partition key and the
top-level PState key, so every read routes to exactly one task.

| Read | Access method | Path / query | RocksDB reads |
|---|---|---|---|
| `get-outcome ns rid` | `foreign-select-one` | `[(keypath ns :requests rid)]` on `$$namespaces` → request record or nil; client converts to the outcome map | 2 (top-level entry, subindexed request) |
| `get-block-size ns hash` | `foreign-select-one` | `[(keypath ns :blocks hash)]` → `Long` or nil | 2 |
| `get-file-version ns fid v` | `foreign-select-one` | `[(keypath ns :files fid :versions v)]` → version record or nil; client `assoc`s `:file-id`, `:version` | 3 (entry, file, version) |
| `get-file ns fid` | query topology `file-head` | `(|hash *ns)`; read `[(keypath *ns :files *fid :head)]` → `Long` or nil; if non-nil read `[(keypath *ns :files *fid :conflict-of)]` (same entry, cached) and `[(keypath *ns :files *fid :versions *head)]`; emit only the plain version record plus `*head`/`*conflict-of` — never the file entry, which holds the `:versions` subindex handle and cannot leave the task; `(|origin)` | 3 distinct entries when the file exists, 2 when it does not |
| `get-changes ns after limit` | `foreign-select` | client returns `[]` without a call when `after = Long/MAX_VALUE` (no seq can exceed it; `(inc after)` would overflow); else `[(keypath ns :journal) (sorted-map-range-from (inc after) {:max-amt limit}) ALL]` → `[seq entry]` pairs ascending (seqs are contiguous so map order is seq order); client builds each public entry as `(assoc entry :seq seq)` because the stored entry does not carry `:seq` | 1 top-level + 1 seek + `limit` iterator reads |

Decision per read (Phase 1 Step 1 questions): every read except
`get-file` is one path on one PState on one partition → foreign select.
`get-file` needs two dependent reads on the same partition (head number,
then that version) → query topology, one roundtrip. Leading `(|hash *ns)`
is evaluated client-side so the invocation goes straight to the task.

Validation: every query validates its arguments against the README
bounds on the client and throws `IllegalArgumentException` before any
network call (`limit` 1..500, `after-seq ≥ 0`, `version ≥ 1`, id forms).

`file-id` structural rule (exact, shared by queries and commits): client
form = 1..128 characters containing no `~`; generated form = a leading
`~` followed by 1..128 further characters of any kind (the request-id
may itself contain `~`, so `"~~x"` is valid and `"~"` alone is not);
anything else (e.g. `"a~b"`, or `~` + 129 characters) throws. A commit
with `parent-version nil` additionally requires client form. Nothing is
inferred from a valid id's shape: an absent file follows the
missing-file rules.

## Writes

One depot, `*commands`, `(hash-by :ns-id)`. Two record types, each a
`defrecord` carrying `ns-id` and `request-id`:

| Command | Depot record |
|---|---|
| `register-blocks!` | `(->RegisterBlocks ns-id request-id blocks)` — `blocks` is the validated vector of `{:hash :size}` |
| `commit-file!` | `(->CommitFile ns-id request-id file-id path blocklist parent-version)` |

The client appends with the default `:ack` (depot durable and replicated)
and returns `nil`. Structural validation (types, bounds, `~` rules,
duplicate hash with differing size) runs before the append and throws
`IllegalArgumentException`; nothing is appended.

Both records `=`-compare as payloads: same request-id and namespace by
construction, so record equality is exactly "identical payload", and a
`RegisterBlocks` never equals a `CommitFile` (cross-type reuse is a
conflicting attempt).

## PState Design

One PState, `$$namespaces`, owned by the microbatch topology `core`:

```clojure
(declare-pstate mb $$namespaces
  {String                                   ; ns-id
   (fixed-keys-schema
     {:next-seq Long                        ; last journal seq issued (0 initially)
      :ingress-seq Long                     ; last ingress position assigned (ordering only; see ETL structure)
      :blocks   (map-schema String Long {:subindex? true})            ; hash -> size
      :files    (map-schema String                                    ; file-id ->
                  (fixed-keys-schema
                    {:head        Long                                ; current head version
                     :conflict-of String                              ; nil for client-created files
                     :versions    (map-schema Long                    ; version ->
                                    (fixed-keys-schema
                                      {:path        String
                                       :blocklist   (vector-schema String)   ; ≤ 1024, bound enforced by structural validation
                                       :size-bytes  Long
                                       :request-id  String
                                       :seq         Long
                                       :conflict-of String})
                                    {:subindex? true})})
                  {:subindex? true})
      :journal  (map-schema Long                                      ; seq ->
                  (fixed-keys-schema
                    {:file-id String :version Long :path String
                     :size-bytes Long :request-id String :conflict-of String})
                  {:subindex? true})
      :requests (map-schema String                                    ; request-id ->
                  (fixed-keys-schema
                    {:command              clojure.lang.Keyword       ; :register-blocks | :commit-file
                     :payload              ICommand                   ; the depot record (RegisterBlocks | CommitFile)
                     :outcome              IOutcome                   ; see below
                     :conflicting-attempts Long})
                  {:subindex? true})})})
```

Outcome polymorphism uses `definterface` + `defrecord` (never `Object`,
never a union fixed-keys map):

```clojure
(definterface ICommand)
(defrecord RegisterBlocks [ns-id request-id blocks] ICommand)
(defrecord CommitFile [ns-id request-id file-id path blocklist parent-version] ICommand)

(definterface IOutcome)
(defrecord RegisterAccepted [registered] IOutcome)
(defrecord CommitAccepted [file-id version seq size-bytes conflict-copy? conflict-of] IOutcome)
(defrecord Rejected [reason need-blocks] IOutcome)   ; need-blocks nil unless reason = :need-blocks
```

The client renders `{:status :command :reason :conflicting-attempts ...}`
from the stored record (structural conversion only).

Why one PState: every collection here is keyed by `ns-id` with the same
partitioner, so the skill rule "one PState per (key, partitioner)" applies.
The top-level value is small (two Longs plus five subindex handles); each
growing collection (`:blocks`, `:files`, per-file `:versions`, `:journal`,
`:requests`) is subindexed, so no operation reads or rewrites an unbounded
collection as a value. Only the scalar fields `:next-seq`,
`:ingress-seq`, and a file's `:head` are ever rewritten, each through
its own field path; no fixed-keys map that owns a subindexed child is
ever written whole, not even at creation — the namespace entry is
created implicitly by its first field-path or nested write, and a file
entry is created by writing its `:head` field (plus `:conflict-of` only
for a conflict copy, where it carries a non-nil value)
(`paths.md`: `termval` replaces the map and would drop the `:versions`
handle, orphaning every stored version; a namespace entry written whole
would also erase `:requests` outcomes already stored under the key).

Per-read costing alternatives that were considered:

- **Versions keyed by composite `[file-id version]`** in one subindexed
  map (2 reads for `get-file-version` instead of 3). Rejected: composite
  keys sort by serialized bytes, which the skill only guarantees to match
  in-memory order for scalars; nesting `file-id → version` keeps Long
  keys and costs one extra cached read per version lookup. Version
  lookups are point reads either way.
- **Denormalized head record inside the file entry** (`get-file` becomes
  one path, 2 reads). Rejected: doubles the stored blocklist (≤ 128 KB per
  version) on every commit for a saving of one read; `get-file` stays a
  single roundtrip via the query topology.
- **`:conflict-of` duplicated into each version record**: chosen, so
  `get-file-version` is one path with no second read; cost ≤ 129 bytes per
  version.
- **Journal omits `blocklist`**: required by the README; page payload is
  bounded by `limit`.

Storage granularity review: no field ever holds a whole collection.
Blocklists are bounded (≤ 1024 hashes) by structural validation, so they
are plain vectors.

## Depots

- `*commands`: `(hash-by :ns-id)`, records `RegisterBlocks`, `CommitFile`.
  Both types share one depot because the README orders all commands of a
  namespace (register then commit without a barrier must see the blocks)
  and because request-ids are shared across command types (replay/conflict
  detection needs one ordered stream per namespace).

## Topologies and PStates

- `core`: **microbatch** (default). Reasons stream is not required: no
  single-digit-millisecond visibility (README: visibility after
  `wait-for-processing!`), no ack-return (commands return `nil`), and
  every write is non-idempotent (seq counters, journal append, head
  increment, `:conflicting-attempts`). Microbatch gives exactly-once
  PState updates on retry, which is the only way to keep "exactly one
  journal entry per accepted commit" under worker failure.
  - Processing concerns: replay/conflict resolution, block registration,
    commit validation, version/journal materialization, outcome
    materialization. All are per-namespace, same task, one event —
    none needs stream latency.
  - Owns `$$namespaces` (schema above).
  - ETL structure: one `<<batch` block implementing **ordered
    per-namespace batching** (next subsection). A namespace's commands
    of one microbatch are applied by ONE loop event per namespace, in
    depot append order, each command completely (including its outcome)
    before the next; the loop yields cooperatively between commands and
    inside the bounded 1024-block reads and writes.

### Ordered per-namespace batching (ETL structure)

The README applies a namespace's commands in issue order, one at a
time, and the skill's cooperative-multitasking rule forbids unbounded
synchronous work on a task: a busy namespace can queue thousands of
commands in one microbatch, and a single command performs up to 1024
point reads and writes. A `yield-if-overtime` inside plain per-record
processing would let the next same-namespace record start between one
commit's `need-blocks` check and its version write (`dataflow.md`
"Yielding and ordering"). Resolution: make a namespace's whole batch of
commands one event, ordered by a durable ingress position, and yield
only inside it.

```clojure
(<<sources mb
  (source> *commands :> %mb)
  (<<batch
    ;; pre-agg: runs on the depot partition task (hash of ns-id) in depot
    ;; append order, synchronously, before any partitioner or yield
    (%mb :> *cmd)
    (get *cmd :ns-id :> *ns)
    (local-select> [(keypath *ns :ingress-seq) (nil->val 0)] $$namespaces :> *prev)
    (inc *prev :> *pos)
    (local-transform> [(keypath *ns :ingress-seq) (termval *pos)] $$namespaces)
    (vector *pos *cmd :> *pair)
    ;; agg: one row per namespace; +group-by hash-partitions by *ns, the
    ;; same routing the PState uses for its top-level key
    (+group-by *ns
      (aggs/+vec-agg *pair :> *pairs))
    ;; post-agg: one loop event per namespace per microbatch
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
  assigned in append order and are unique per namespace; reads inside
  the owning topology see the attempt's own writes (`microbatch.md`
  "Read visibility").
- `+group-by` needs no explicit partitioner and emits one row per key;
  post-agg has only `*ns` and `*pairs` in scope and allows no
  partitioner (`batch.md`, `aggregators.md`). `+vec-agg` element order
  is not documented, hence the explicit sort by position.
- Yields inside the loop (`yield-if-overtime` between commands and in
  the ≤ 1024-hash block-write loop, `{:allow-yield? true}` on the
  ≤ 1024-key `submap` reads) let other events on the task run: other
  namespaces' loops, query topologies, foreign reads. None touches this
  namespace's uncommitted writes: the only writer of a namespace within
  a microbatch is this loop, external readers see committed state only,
  and the next microbatch starts only after this one completes on all
  tasks (`microbatch.md`). A retry rolls back and reapplies the ingress
  positions and the business writes together (exactly-once).
- `:ingress-seq` is a durable per-namespace Long written by field path
  only; it counts every command (replays, conflicts, rejections
  included), is independent of the journal `:next-seq`, and is never
  exposed.
- Batch bound: `(set-launch-depot-dynamic-option! setup "*commands"
  "depot.microbatch.max.records" 200)` caps records per depot partition
  per microbatch. A commit record can reach ~130 KB (1024 hashes × 128 B
  plus a 1 KB path), so 200 records bound the grouped vectors at ≈ 26 MB
  worst case per task and a few MB typically, never lifetime history.
  Group memory is transient batch state, not a TaskGlobal and not a
  durable inbox.
- Cost: one `+group-by` hop per command (a network partitioner landing on
  the same task as the depot partition), plus one extra navigation of
  the namespace's top-level entry and one field write in pre-agg. The
  earlier "zero partitioners" claim is withdrawn.
- Limitation, stated plainly: the per-namespace loop is bounded by the
  batch size (≤ 200 commands × ≤ 1024 seeks and writes ≈ ≤ ~100 s only
  if every record is maximal, ≈ 1 s for typical commits) and yields
  every ~5 ms. A batch of 200 maximal commits could approach
  `topology.microbatch.phase.timeout.seconds` (default value to be
  confirmed at build time); the option is set per depot and may be
  lowered further if measured. No input cap beyond the README's is
  imposed.
- Build-time verifications: (a) `local-transform>` and `loop<-` are
  accepted in the post-agg of a microbatch `<<batch`; fallback is
  `(materialize> *ns *ordered :> $$grouped)` and running the loop in the
  pre-agg of a second `<<batch` that reads `($$grouped :> *ns *ordered)`.
  (b) `+group-by` routing lands on the PState partition of `*ns` at 2
  and 4 tasks. (c) `sort-pairs-by-position` is a plain `defn`.
  (d) `submap` with `{:allow-yield? true}` on a subindexed map; fallback
  is a `loop<-` of point reads with `(yield-if-overtime)`.

Per-command processing (`apply-command`, all on the namespace's task,
inside the loop above):

1. `(local-select> [(keypath *ns :requests *rid)] $$namespaces :> *req)`.
   If non-nil: if `(= (:payload *req) *cmd)` → no-op (replay); else
   rewrite the request record with `:conflicting-attempts` incremented
   (`termval` of the already-read record, no extra read). Stop (unify to
   the loop's `continue>`). This precedes every business check, so a
   `:need-blocks` rejection is never re-evaluated.
2. `RegisterBlocks`: distinct hashes `h₁..hₖ` (k ≤ 1024). Read known
   sizes with one `local-select>` of
   `[(keypath *ns :blocks) (submap *hashes)]` with `{:allow-yield? true}`
   (k point seeks into the subindexed map; fallback `loop<-` over hashes
   with one point read each and `(yield-if-overtime)` if `submap` is not
   accepted on a subindexed map by the build).
   If any known size differs → `Rejected :size-mismatch`, no block
   written. Else write each unknown hash with
   `[(keypath *ns :blocks *h) (termval *size)]` (write-only) in a
   `loop<-` with `(yield-if-overtime)` per iteration, and
   `RegisterAccepted registered = count of unknown`.
3. `CommitFile`: read `[(keypath *ns :files *fid :head)]` → `*head`
   (nil ⇒ file absent) and, when present, `[(keypath *ns :files *fid
   :conflict-of)]` → `*co` (same RocksDB entry, cached). Do NOT read the
   file entry as a whole: its value carries the `:versions` subindex
   handle, and it must never be re-written with `termval`. Checks in
   README order:
   `:file-exists` (parent nil, file present), `:no-such-file`,
   `:unknown-parent` (`parent > head`). Then `:need-blocks`: known
   sizes via `(submap distinct-hashes)` with `{:allow-yield? true}` as
   above; missing = hashes with nil size, in first-occurrence order; if
   any → `Rejected :need-blocks missing`. Else `size-bytes` = Σ size
   over the blocklist with repeats.
   Then exactly one of:
   - **new head** (parent nil or `= head`): `v = (inc head)` or 1;
     `seq = (inc next-seq)`. On create (`:head` absent) write the single
     scalar field `[(keypath *ns :files *fid :head) (termval 1)]` — never
     the file entry whole, and no `:conflict-of` write: an absent
     fixed-keys field navigates to nil (`pstate-schema.md`), which is the
     client-created provenance — and then the version. On update write the
     head field only: `[(keypath *ns :files *fid :head) (termval v)]`
     (write-only, `:versions` and `:conflict-of` untouched; `co` =
     `*co`). Then
     version `[(keypath *ns :files *fid :versions v) (termval rec)]`
     (rec carries `:conflict-of co`), journal
     `[(keypath *ns :journal seq) (termval entry)]`, and
     `[(keypath *ns :next-seq) (termval seq)]`.
   - **conflict copy** (parent `< head`): `fid' = (str "~" rid)`;
     `suffix = (str " (conflicted copy " rid ")")` (19 + `(count rid)`
     characters, ≤ 147); `prefix = (subs path 0 (min (count path) (- 1024 (count suffix))))`;
     `path' = (str prefix suffix)` (≤ 1024, whole path kept when short).
     Create file `fid'` by field writes
     `[(keypath *ns :files fid' :head) (termval 1)]` and
     `[(keypath *ns :files fid' :conflict-of) (termval fid)]`
     before its version 1 (`:conflict-of fid`, the targeted id whether
     client form or itself a copy), journal entry with file-id `fid'`,
     `[(keypath *ns :next-seq) (termval seq)]`; the targeted file's entry
     and versions are not written.
   Outcome `CommitAccepted` with `:conflict-copy?` true only in the
   copy case.
4. Write the request record
   `[(keypath *ns :requests *rid) (termval {...:conflicting-attempts 0})]`.
   Every branch of steps 1–4 ends here and unifies into the outer
   loop's single `continue>`.

`:next-seq` is read by field path `[(keypath *ns :next-seq)]` (nil ⇒ 0;
same top-level RocksDB entry as the other navigations of the event, so
no extra seek) and written by field path
`[(keypath *ns :next-seq) (termval seq)]`. The top-level entry is never
written as a whole, at any time.

Per-command I/O bounds: register = 2 + k seeks, ≤ k+2 writes;
commit = 3 + k seeks (top-level entry, request, file entry — `:head` and
`:conflict-of` are two navigations of one entry — plus k distinct
hashes), ≤ 7 writes (version, file `:head` and, on copy creation,
`:conflict-of`, journal, `:next-seq`, request, ingress position).
Both are proportional to the submitted list, independent of namespace
history. No read ever touches `:journal`, all files, or all blocks.

Malformed records cannot reach the topology (client validation), so no
record can throw deterministically and stall the microbatch.

## Query Topologies

- `file-head` `[*ns *fid :> *result]`
  - Input 1: existing file with head 7 and 7 versions → 3 total reads
    (entry, file `:head`/`:conflict-of`, version 7), 3 meaningful.
    `*result` is built from plain values (`*head`, `*conflict-of`, the
    version record, `*fid`); the file entry itself is never emitted.
  - Input 2: file absent or namespace unknown → 2 total reads (entry,
    file `:head` returning nil), 2 meaningful (the nil answers the
    query); the version read is skipped by `<<if`, so no wasted read.
  - Fixed or variable: variable (2 or 3), handled by `<<if` on the file
    lookup; both branches emit `*result` exactly once (`(|origin)` then
    unify), nil in the absent case.

## Partitioning efficiency

Optimal placement: the dominant reads (`get-outcome`, `get-file`,
`get-block-size`, `get-changes`) each want all of one namespace's data on
one task, and the README's per-namespace ordering and atomic-command rules
require all of a namespace's commands to be applied on one task. So
`f(ns-id) → one task`, implemented as `(hash-by :ns-id)` for the depot
and the default hash partitioner for `$$namespaces` (foreign reads route
by the first key). Namespaces are many (one per user or team), so hash
variance is negligible, and a single hot namespace is bounded by the
per-namespace ordering requirement — no partitioner can spread one
namespace's serial command stream without breaking the spec.

Dominant read: `get-file` (query `file-head`). Seeks/op is the number of
tasks touched (1) — a single-partition read at every N.

### N = 1 task (single-task baseline)
| Data category | Frequency proportion | Seeks/op | Iterator reads/op |
|---|---|---|---|
| existing file, small namespace | 0.55 | 1 | 0 |
| existing file, namespace with 10⁶ versions/blocks | 0.30 | 1 | 0 |
| missing file in existing namespace | 0.10 | 1 | 0 |
| unknown namespace | 0.05 | 1 | 0 |
Weighted seeks = 1.0   |   Weighted iterator reads = 0

### N = 16 tasks
| Data category | Frequency proportion | Seeks/op | Iterator reads/op |
|---|---|---|---|
| existing file, small namespace | 0.55 | 1 | 0 |
| existing file, namespace with 10⁶ versions/blocks | 0.30 | 1 | 0 |
| missing file in existing namespace | 0.10 | 1 | 0 |
| unknown namespace | 0.05 | 1 | 0 |
Weighted seeks = 1.0   |   Weighted iterator reads = 0

### N = 128 tasks
| Data category | Frequency proportion | Seeks/op | Iterator reads/op |
|---|---|---|---|
| existing file, small namespace | 0.55 | 1 | 0 |
| existing file, namespace with 10⁶ versions/blocks | 0.30 | 1 | 0 |
| missing file in existing namespace | 0.10 | 1 | 0 |
| unknown namespace | 0.05 | 1 | 0 |
Weighted seeks = 1.0   |   Weighted iterator reads = 0

Flat across N. `get-changes` is likewise 1 task, 1 seek, `limit`
iterator reads at every N.

## Design Decisions

- Subindexing: `:blocks`, `:files`, `:files.*.versions`, `:journal`,
  `:requests` are subindexed — each grows without bound per namespace
  (README "Data growth"). `blocklist` (≤ 1024, structural bound) and the
  top-level fixed-keys record are not.
- Colocation: depot `(hash-by :ns-id)` = PState top-level key =
  `+group-by *ns` routing. The only partitioner is the `+group-by` hop,
  which lands on the task that already holds the namespace.
- Ordering and yielding: one depot, one microbatch topology, ordered
  per-namespace batching (one loop event per namespace per microbatch,
  sorted by durable `:ingress-seq`) → per-namespace issue order =
  processing order; journal seqs are assigned in that order and are
  contiguous because only accepted commits consume one.
  `yield-if-overtime` between commands and in the block-write loop,
  `{:allow-yield? true}` on the `submap` reads;
  `depot.microbatch.max.records` = 200 bounds per-microbatch group
  memory.
- Entry creation: the namespace entry and file entries are created by
  field-path writes only; nothing that owns subindexed children is ever
  written whole, so a `:requests` outcome stored before a namespace's
  first accepted command survives every later write.
- No last-writer-wins: `parent < head` always creates a copy; the targeted
  file's entry is not written.
- Nothing is deleted or overwritten except the scalar fields `:head`
  (per file), `:next-seq` and `:ingress-seq` (per namespace), each
  written by field path, and the request record's counter (a record
  with no subindexed child, so rewriting it whole is safe).
- The seq of a version and its journal entry are written in the same
  event, so "every journal seq corresponds to exactly one stored version".

### Synchronization and client contract

`create-module` creates one `(atom 0)` in its closure and every
`:wrap-client` instance shares it. Each command increments it before the
append; `wait-for-processing!` calls
`(rtest/wait-for-microbatch-processed-count ipc module-name "core" @cnt)`.
Because the processed count is module-level and cumulative, a second
`wrap-client` on the same cluster and a `update-module!` redeploy keep the
counter valid. The atom holds no business data — only the number of
appends issued through this `create-module` result.

## State primitive selection

- `$$namespaces` (PState): durable, partitioned by `ns-id`. Write volume
  per command: register ≤ k+2 entries (k = distinct hashes submitted);
  commit ≤ 7 entries. Both bounded by inputs the application controls.
  `:ingress-seq` lives here because it must roll back with the attempt
  on retry; a TaskGlobal counter would not.
- Per-microbatch grouped vectors (`+vec-agg` output): transient batch
  state inside one microbatch attempt, bounded by
  `depot.microbatch.max.records`; not a TaskGlobal, not durable.
- No TaskGlobal: nothing needs in-memory state; every read is a bounded
  disk read.
- Client atom (sync bookkeeping only, not business state).

## Resource usage analysis

### Disk usage (PStates)
- Top-level entry per namespace: ~64 B key + ~120 B value (Long + five
  subindex handles). Negligible.
- `:blocks` entry: hash ≤ 128 B + 8 B → ~150 B. 10⁶ blocks per namespace
  ≈ 150 MB; namespaces are spread across tasks by hash.
- `:files` entry: file-id ≤ 129 B + `{:head :conflict-of}` ≤ 150 B →
  ~300 B.
- version record: path ≤ 1 KB + blocklist ≤ 1024 × 128 B (typ. ~40 B
  hashes → ~40 KB worst-case, ~4 KB typical) + scalars → typically ≤ 5 KB.
- journal entry: ≤ 1.3 KB (path, ids, scalars).
- request record: payload (a commit record repeats path + blocklist, so
  typically ≤ 5 KB, worst case ~130 KB) + outcome ≤ 300 B.
- Depot: same size as the request payloads; retained by Rama's default
  policy.
- Growth: one version + one journal entry + one request record per
  accepted commit; one request record per rejected command; per task ≈
  (total commands × ~10 KB) / N.

### Memory usage (TaskGlobals)
None. Transient per-microbatch memory: the grouped `[pos cmd]` vectors,
≤ `depot.microbatch.max.records` (200) records per task per microbatch;
≈ 26 MB only if every record is a maximal 1024-hash commit, a few MB
typically; released when the attempt commits.

### Minimization
- `:conflict-of` is duplicated into version records (≤ 129 B) to make
  `get-file-version` a single path; accepted.
- The request payload is stored verbatim because replay detection is
  defined by `=` on the payload; hashing it would violate the contract.
- `:ingress-seq` is one Long per namespace; it cannot be folded into
  `:next-seq`, which must stay contiguous over accepted commits only.
- No other duplication; journal entries omit blocklists as required.

## Design difficulty log

- **Decision:** ordered per-namespace batching — pre-agg assigns a
  durable `:ingress-seq` position per command, `+group-by` namespace
  with `+vec-agg`, explicit sort by position, one `loop<-` per namespace
  per microbatch applying each command completely, `yield-if-overtime`
  between commands and inside the ≤ 1024-hash block-write loop,
  `{:allow-yield? true}` on the ≤ 1024-key `submap` reads. **Basis:**
  README per-namespace issue ordering and atomic commands; skill
  cooperative-multitasking rule (a busy namespace queues thousands of
  commands per microbatch; one command does up to 1024 seeks and
  writes); `dataflow.md` states yielding gives up arrival ordering
  across events; `+vec-agg` ordering is undocumented. Alternatives:
  per-record processing without yield (unbounded synchronous stretch),
  per-record processing with yield (a second commit could read the head
  between the first's checks and its head write), grouping without a
  durable position (order unverifiable), a durable inbox or TaskGlobal
  queue (extra state; TaskGlobal does not roll back on retry).
  **Outcome:** ordering and cooperative yielding both hold at the cost
  of one `+group-by` hop and one top-level read and field write per
  command; a batch of maximal commits can be long (stated above), and
  `depot.microbatch.max.records` is the knob. Needs build-time
  verification of post-agg `local-transform>`/`loop<-` acceptance,
  `+group-by`-to-PState routing agreement at 2 and 4 tasks, `submap`
  with `:allow-yield?`, plus a test that issues register, commit, stale
  commit for one namespace in one microbatch and checks seqs and the
  conflict copy against a sequential oracle.
- **Decision:** namespace and file entries are created by field-path
  writes only, never `termval` of the entry. **Basis:** `paths.md`
  (`termval` replaces the map), `pstate-schema.md` (orphaned subindexed
  children); a namespace's first command may be a rejected commit whose
  `:requests` entry already lives under the key. **Outcome:** pre-first-
  accept outcomes and `:ingress-seq` survive; version history is never
  orphaned. Verify with: `commit-file!` with a non-nil parent on an
  unknown file in a fresh namespace (rejected `:no-such-file`) → first
  accepted register and commit in the namespace → original outcome still
  rejected, replay still rejected, conflicting payload increments the
  counter, both within one microbatch and across a barrier.
- **Decision:** versions nested `file → version` (3 reads per
  `get-file-version`) over a composite `[file-id version]` key (2 reads).
  **Basis:** `pstate-schema.md` guarantees in-memory sort order for
  scalars, not serialized vectors; the extra read is a cached top-level
  navigation; no range read over versions is required. **Outcome:**
  point reads either way; nesting keeps Long keys.
- **Decision:** `get-file` as a query topology rather than a head record
  duplicated in the file entry. **Basis:** duplication copies a
  blocklist (≤ 128 KB) per commit to save one read; the query topology
  keeps one roundtrip. **Outcome:** lower write amplification; 3 reads
  on one task.
- One PState (same key, same partitioner) and microbatch (every write
  non-idempotent; barrier-based visibility) are determined directly by
  the skill rules and the README.
