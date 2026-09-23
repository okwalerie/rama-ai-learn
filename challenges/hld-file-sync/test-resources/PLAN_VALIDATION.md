# Plan Validation — hld-file-sync (phase 2, private, fresh pass)

Inputs: README.md, `src/hld_file_sync/protocol.clj`,
`test-resources/IMPLICIT_SPEC.md`, `test-resources/PLAN.md` (post
phase-1 repair), skill `references/batch.md`, `microbatch.md`,
`dataflow.md`, `aggregators.md`, `paths.md`, `pstate-schema.md`,
`operate.md`, `testing.md`, `lib/harness`. Supersedes the previous
validation in full.

Verdict: **minor-fail** — one localized defect fixed directly in
`PLAN.md` (see "Findings and fixes applied"). The architecture (one
depot by namespace, one microbatch topology with ordered per-namespace
batching, one PState with subindexed blocks/files/versions/journal/
requests, one query topology) stands.

## Rama semantics relied on (cited)

| Claim | Source | Consequence |
|---|---|---|
| `%mb` emits per task in depot append order; microbatches are sequential and the next starts only after all tasks commit | `microbatch.md` "Source binding", "Sequential processing" | Ingress positions are assigned in append order; no second attempt or later microbatch overlaps a namespace loop |
| Reads inside the owning topology see the attempt's uncommitted writes; outside readers see committed state only | `microbatch.md` "Read visibility" | Consecutive pre-agg stamps see each other; a command sees earlier commands of the same loop; foreign reads never see a half-applied command |
| Pre-agg attaches sequentially; `local-transform>` is allowed in microbatch pre-agg; `+group-by` auto hash-partitions and needs no explicit partitioner; post-agg scope = group keys + aggregator outputs, no partitioners | `batch.md` "Pre-agg", "Constraints" | Stamp happens before the only partitioner; post-agg receives exactly `*ns`, `*pairs` |
| `+vec-agg` collects into a vector; element order undocumented | `aggregators.md` | Explicit sort by position is required and present |
| Yielding gives up arrival ordering across events; yielded reads run on a stable snapshot | `dataflow.md` "Yielding and ordering", ":allow-yield? true" | Only one event per namespace per microbatch exists, so yields inside it admit no same-namespace event |
| `keypath` + `termval` = write-only; `termval` on a map replaces it and drops unmentioned keys; deleting a parent of a subindexed structure orphans elements | `paths.md` "No-Read Optimizations"; `pstate-schema.md` "Deleting Subindexed Structures" | Entries that own subindexed children are never written whole |
| `keypath` on an absent key navigates to nil; fixed-keys fields start absent | `pstate-schema.md` | Existence = `:head` non-nil; absent `:conflict-of` reads as nil |
| `depot.microbatch.max.records` is a dynamic per-depot option; `set-launch-depot-dynamic-option!` sets per-depot options at launch | `microbatch.md` "Config"; `operate.md` "Dynamic Options" | Batch bound is a real, correctly scoped knob |
| `sorted-map-range-from start {:max-amt n}` scans forward n entries; `ALL` on a map yields `[k v]` | `paths.md` | Journal page = 1 seek + `limit` iterations with seqs recovered from keys |
| Processed count is cumulative per module and persists across `update-module!` | `testing.md` | Shared client atom is valid for a second client and after an update |

## ETL structure review (ordered per-namespace batching)

- Decision: ingress stamp in pre-agg. Basis: `PLAN.md` lines 195–205: `(%mb :> *cmd)` → read `:ingress-seq` → `local-transform>` → `+group-by`. No partitioner, yield, or async boundary precedes the stamp; the depot task for `(hash-by :ns-id)` is the PState task for `ns-id`. Outcome: stamps are assigned synchronously, in append order, on the owning task. PASS.
- Decision: grouping sorts by assigned position. Basis: lines 208–211, `sort-pairs-by-position` applied to the `+vec-agg` output; the plan states `+vec-agg` order is not assumed. Outcome: PASS.
- Decision: one sequential loop per namespace. Basis: `+group-by *ns` emits one row per key; the post-agg `loop<-` (lines 212–223) consumes the sorted vector; every command's replay/conflict/reject/accept branches unify into the request-record write and one `continue>` (lines 336–339). Outcome: a command completes fully, including its outcome, before the next starts. PASS.
- Decision: position is distinct from business order and not part of the fingerprint. Basis: `:ingress-seq` is a separate field from `:next-seq` (schema lines 67–68; "never exposed" line 249); the payload compared with `=` is the depot record, which carries no position (lines 54–57, 200–205). Outcome: replay/conflict decisions are unaffected by positions. PASS.
- Decision: bounded buffering. Basis: `depot.microbatch.max.records` = 200 per partition (line 250); worst-case group memory ≈ 26 MB computed from README bounds; grouped vectors are transient batch state. Outcome: no lifetime history is buffered; the API and option name match the references. PASS.
- Decision: no next batch until commit, no exposure before commit. Basis: `microbatch.md` "Sequential processing", "Read visibility"; single task per namespace so no cross-task partial-commit window applies. Outcome: PASS.
- Decision: long yielding reads cannot interleave same-namespace commands. Basis: the only writer of a namespace in a microbatch is its one loop event; yields admit other namespaces' loops, query topologies, and foreign reads, none of which write this namespace; the next microbatch waits. Outcome: PASS.
- Build-time verifications (post-agg `local-transform>`/`loop<-`, `+group-by` routing at 2 and 4 tasks, `submap` with `:allow-yield?`) each have a documented fallback that uses only pre-agg constructs (`materialize>` + second `<<batch`, `loop<-` of point reads). Outcome: no verdict depends on an unverified construct.

## Query topology: `file-head`
- Input examples present: yes.
- Example 1 (existing file, head 7): N=3, M=3. N == M? yes.
- Example 2 (absent file / unknown namespace): N=2, M=2 (the nil `:head` answers the query). N == M? yes.
- M values 3, 2 → variable; handled by `<<if` on the head read; one emit per branch; only plain values are emitted (never the file entry with its `:versions` handle). PASS.

## PState schemas
- One PState `$$namespaces` keyed by `ns-id`, hash partitioned; no second PState shares that key. PASS.
- Object type: none. Uniform records use fixed-keys-schema: yes. Polymorphic `:payload`/`:outcome` use `definterface` + `defrecord`: yes.
- Inner collections > 100 subindexed: `:blocks`, `:files`, `:files.*.versions`, `:journal`, `:requests` — yes. Non-subindexed `blocklist` (≤ 1024) and `:need-blocks` (≤ 1024 distinct) are bounded by client structural validation (README "Types and bounds"). PASS.
- Field-level blob review: no field stores a whole collection; the largest field is a ≤ 1024-hash blocklist (≈ 130 KB worst case) that the README itself makes an atomic value. PASS.

## Partitioning
- Every write: `(hash-by :ns-id)`. Namespaces are many; a hot namespace cannot be split without breaking README "Ordering". PASS.
- Table present for N = 1, 16, 128; four categories, proportions sum to 1.00; seeks/op counted as tasks touched (1); weighted seeks 1.0 flat. Recomputed: 0.55+0.30+0.10+0.05 = 1.00 × 1 = 1.0 at every N. PASS.
- Stored-placement scheme: not needed; the spec forces one task per namespace. PASS.

## Topologies
- Microbatch unless justified: one microbatch topology `core`. No single-digit-millisecond write exists (README: visibility after `wait-for-processing!`). No stream topology. No test-synchronization reasoning in the plan. PASS.

## Production readiness
- Concurrent clients: each command is one depot record; the namespace task serializes them in append order; replay/conflict is decided on durable `:requests`. PASS.
- Client restart: the client holds IPC handles and a sync atom created in `create-module`; no business state. PASS.
- Worker restart mid-topology: microbatch exactly-once; ingress positions and business writes roll back and reapply together. PASS.
- Large scale: all growing collections subindexed; every read is bounded by inputs. PASS.
- Stream non-idempotent writes: none (no stream topology).

## Internal depot usage / cross-topology / stream correctness / in-memory state
None / single topology / no stream / no TaskGlobal (client atom holds one Long).

## Minimality — adversarial simplification
Sketch: one depot hashed by `ns-id`, one microbatch topology, one PState keyed by `ns-id` with subindexed blocks/files/journal/requests, foreign selects for point reads, one query topology for `get-file`. Diff against the plan: the ordered-batching mechanism (ingress stamp, `+group-by`/`+vec-agg`, sort, loop) is the only addition.
- Ordered batching: delete it and either commands run without yield (a 200-command batch of 1024-hash commits holds the task for seconds, violating the skill's cooperative-multitasking rule) or with per-record yield (two same-namespace commits interleave between the `:need-blocks` check and the head write, breaking README "processed in the order the client issued them" and atomicity). Merge/bypass: a TaskGlobal queue does not roll back on retry; a durable inbox PState adds a second write path with no saving. Keep.
- `*commands` depot: two depots would break register-then-commit ordering and cross-type request-id conflicts. Keep.
- `core`: stream would duplicate seqs/journal on retry. Keep.
- `file-head`: two client selects = two roundtrips; a denormalized head record costs a ≤ 128 KB copy per commit. Keep.
- `:next-seq`, `:ingress-seq`, `:payload`: each backs a README requirement (contiguous seqs, ordering, `=` replay). Keep.

## Throughput — adversarial
- `get-file` 3 point reads on one task; the only cheaper design duplicates the blocklist per commit (costed and rejected in the plan). `get-changes` 1 seek + `limit`. `commit-file!` k point seeks over the block map (hashes are not contiguous, so no range read exists). `register-blocks!` k seeks + ≤ k writes. The ingress stamp adds one navigation of an entry every command reads anyway plus one field write. No lower-cost construction found.

## Spec coverage — trace every operation and constraint

### `register-blocks!`
- Source: "A hash already registered with the same size is not an error ... atomic: on rejection no hash from the call is registered ... `:registered` ... the number of hashes newly registered".
- Trace: ns "n1", blocks `[{a 3} {b 5} {a 3} {c 7}]`, `b` known with 5. Distinct `a b c`; `submap` → `{b 5}`; no mismatch; write `a→3`, `c→7`; `RegisterAccepted 2`. Then `[{b 6} {d 1}]` → `b` 5 ≠ 6 → `Rejected :size-mismatch`; `d` not written; `get-block-size d` → nil.
- Fault tolerance: durable PState; retry reapplies once; one partition. Race: two clients register `a` with 3 and 4 back-to-back → first wins, second rejected whole.
- Verdict: PASS.

### `commit-file!` — create / new head
- Source: "the file's head becomes `head + 1` (or `1` on create) with the given `path`, `blocklist`, and `size-bytes`."
- Trace (after fix): file "f" absent, parent nil, blocklist `[a a c]` (3, 3, 7): `:head` read → nil, no `:file-exists`; blocks known; `size-bytes` 13; `seq` 1; write `:head` 1 (single field), version 1 record (`:conflict-of nil`), journal 1, `:next-seq` 1. Second commit parent 1, blocklist `[c]`: `:head` 1 = parent → v2; write `:head` 2 (field only, `:versions` untouched), version 2, journal 2. `get-file-version "f" 1` still returns the original record.
- Verdict: PASS.

### `commit-file!` — conflict copy and provenance
- Source: "the original file is **not** modified. Instead a new file is created with `file-id' = (str "~" request-id)`, version `1` ... `:conflict-of file-id` ... `path' = (str prefix suffix)` ... `prefix` is `path` truncated to its first `1024 − (count suffix)` characters".
- Trace: "f" head 2; commit rid "r7" (2 chars), parent 1, 1024-char path, blocklist `[a]`: suffix `" (conflicted copy r7)"` = 21 chars; prefix = first 1003 chars; `path'` = 1024 chars; file `~r7` created by field writes `:head` 1 and `:conflict-of "f"`; version 1 with `:conflict-of "f"`; journal 3 with file-id `~r7`; outcome `{:file-id "~r7" :version 1 :seq 3 :size-bytes 3 :conflict-copy? true :conflict-of "f"}`; "f" untouched (`get-file "f"` → `:version 2 :seq 2`). 128-char rid → id of 129 chars, accepted by every query and by non-nil-parent commits (structural rule lines 30–37). Nested: stale commit "r8" against `~r7` after it advanced → `~r8` with `:conflict-of "~r7"`. Update of a copy at its head → `:conflict-copy? false`, `:conflict-of` unchanged.
- Verdict: PASS.

### Rejection order and `:need-blocks`
- Source: table order `:file-exists`, `:no-such-file`, `:unknown-parent`, `:need-blocks`; "a vector of the missing hashes, each listed once, in order of first occurrence".
- Trace: parent 5 > head 3 → `:unknown-parent` with no block read. Stale commit (parent 1, head 3) with blocklist `[x a y x]`, `x`, `y` unregistered → `:need-blocks [x y]`, no copy, journal unchanged.
- Verdict: PASS.

### Reject-before-create → create → original read → replay → conflict
- Source: "Replay and conflict handling ... runs after structural validation and before any business validation"; "Nothing is ever deleted or overwritten".
- Trace: fresh namespace "n2". (1) commit rid "c1" parent 1 on file "g" → `:head` nil → `:no-such-file`; request record `c1` written under "n2"; `:ingress-seq` is 1 (top-level entry now exists with only `:ingress-seq` and `:requests`). (2) register + commit rid "c2" parent nil on "g" → accepted; writes `:head` field, version, journal, `:next-seq` — no whole-entry write (lines 121–128), so `c1` survives. (3) `get-outcome "n2" "c1"` → `:rejected :no-such-file`, `:conflicting-attempts 0`. (4) replay of `c1` (identical payload) → step 1 finds the record, `=` true → no-op, still rejected although "g" now exists. (5) `c1` with a different blocklist → counter 1, no version, no journal entry. Same result when all five commands share one microbatch (positions 1..5 in one loop) or are separated by barriers.
- Verdict: PASS.

### Idempotency and conflicts (accepted originals)
- Trace: rid "r1" accepted create (journal 1). Replay → no write; journal length 1. Different blocklist → counter 1, no version. `register-blocks!` with rid "r1" → different record type, never `=` → counter 2. PASS.

### Validation order (structural before request-id)
- Trace: 1025-char path with a used rid → client throws, no append, outcome unchanged. `"a~b"` throws; `"~~x"` accepted (generated form whose request-id is `"~x"`); `"~"` alone throws. PASS.

### Ordering
- Source: "Commands issued by one client against the same namespace are processed in the order the client issued them."
- Trace: register (pos 1) then commit (pos 2) without barrier, same microbatch → sorted `[1 2]`; the commit's `submap` read sees the blocks written by the register in the same loop event (owning-topology visibility). Two commits parent 1 back-to-back → first v2, second copy; seqs 2, 3. PASS.

### Durability / nothing deleted or overwritten
- Trace: only `:head`, `:next-seq`, `:ingress-seq` (field paths) and the request record (no subindexed child) are rewritten. No entry owning a subindexed child is written whole at any time, including creation. PASS.

### `get-outcome`, `get-block-size`, `get-file-version`
- Constant reads (2, 2, 3); version > head → nil; version 0 throws (README bounds). Rendering is structural only. PASS.

### `get-changes`
- Source: entries "with `:seq` strictly greater than `after-seq`, ascending, at most `limit`", shape includes `:seq`.
- Trace: journal 1..1200, after 500, limit 500 → `sorted-map-range-from 501 {:max-amt 500}` + `ALL` → 500 `[seq entry]` pairs → client `assoc :seq` → seqs 501..1000. `after-seq = Long/MAX_VALUE` → `[]` without a call. Unknown namespace → nil navigation → `[]`. PASS.

### Resource guarantees (own history)
- Trace: namespace with 10⁶ versions of one file, 10⁶ blocks, 10⁶ journal entries: `get-file` 3 reads; `get-file-version` 3; `get-changes` after 999,000 = 1 seek + `limit`; commit 3 + k seeks + ingress navigation. No navigation over `MAP-VALS`/`ALL` of an unbounded collection. PASS.

### Shared state
- Trace: second `wrap-client` shares the module-level atom; after `wait-for-processing!` it reads the same committed PState. PASS.

## Findings and fixes applied (decision / basis / outcome)

| # | Finding | Basis | Fix applied to PLAN.md |
|---|---|---|---|
| 1 | Client-form file creation wrote `[(keypath *ns :files *fid :conflict-of) (termval nil)]`, an unnecessary write whose effect depends on nil-field write semantics | `pstate-schema.md`: absent fixed-keys fields navigate to nil; README `:conflict-of` is nil for client-created files | Creation writes the single field `:head`; `:conflict-of` is written only for conflict copies (non-nil). Write count text updated |

## Test plan requirements (for phase 5)

- Deploy at both 2 and 4 tasks (explicit launches in addition to the harness's choice); second `:wrap-client` on the same IPC sees commits made through the first; `rtest/update-module!` with the same module value after writes, then all reads unchanged and a further commit's seq continues contiguously; explicit `wait-for-processing!` before every read, no sleeps.
- Independent expectations: expected values computed by the test from its own inputs (distinct block sizes 3, 5, 11; repeated hashes; paths of distinct lengths including the 1024 boundary; 128-char request-ids), never read back from the module.
- Reject-before-create scenario exactly as traced above, both within one microbatch and across barriers; rejected outcomes must be preserved unchanged.
- Deep-page scaling: `get-changes` at a high `after-seq` on a 1200-entry journal must not cost more than at `after-seq` 0; assert by page contents and by relative timing bands, not absolute counts.
- Efficiency capture: per-command cost includes one fixed extra top-level navigation and field write (ingress stamp). Scan detection compares cost across history sizes (10 vs 10⁴ blocks/versions) and must remain sensitive to linear growth; do not relax it to absorb the fixed constant.
- Field-level no-blob review: assert that no stored value grows with history (journal entries omit blocklists; version records ≤ 1024 hashes).
- Do NOT assert topology names, microbatch counts, internal PState names, or timing windows tighter than a wide band.
- Targeted mutants: last-writer-wins on stale parent; `need-blocks` sorted instead of first-occurrence; replay re-evaluated after registration; `size-bytes` counting repeats once; suffix truncation from the wrong end; conflict copy id `~parent~rid`; version history lost after an update; outcome lost after a namespace's first accepted commit.
