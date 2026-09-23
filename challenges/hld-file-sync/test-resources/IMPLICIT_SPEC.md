# Implicit Spec — hld-file-sync (private, phase 0)

Private challenge-author artifact. Encrypted during challenge runs. It
records requirements, adversarial cases, and review concerns derived from
README.md; it does not prescribe storage, partitioning, or topology
choices. Entries under "Contract decisions" give decision, basis, and
outcome only.

## Contract decisions

| Decision | Basis | Outcome |
|---|---|---|
| Request-ids scoped per namespace | Idempotency keys are naturally tenant-scoped; keeps outcome and domain state in one scope | `get-outcome` takes `ns-id`; same id in two namespaces is two requests |
| Conflicting attempt observed via `:conflicting-attempts` counter on the original outcome | Durable, deterministic, no synchronous read-before-write in the client | Original fields never change; counter increments once per differing-payload attempt |
| Structural errors throw; business rejections are durable | Keeps the durable outcome set to domain reasons; malformed input never consumes an id | `IllegalArgumentException`, no append |
| Structural validation precedes request-id handling; replay/conflict precedes business validation | A malformed retry must not alter an existing outcome; a replay must not be re-evaluated against current state | Unused id stays unused; `:conflicting-attempts` unchanged by malformed input; queries throw on bad bounds |
| Payload = command type + all args except `this`/`request-id`, compared with `=` | Cross-command-type reuse of an id must count as a conflict | Same args under a different command are a conflicting attempt |
| Conflict copy id is `(str "~" request-id)`; path is `path` truncated + `" (conflicted copy " request-id ")"` capped at 1024 | Request-ids are unique per namespace, so the id is collision-free for any nesting depth without concatenating the parent id; generated values stay within the input bounds | Client ids ≤128 without `~`; generated ids `~` + request-id (2..129); either form accepted by queries and non-nil-parent commits; a valid nonexistent id follows missing-file rules |
| `:conflict-of` is immutable provenance; `:conflict-copy?` marks only the creating command | Provenance must survive edits of the copy; the flag must distinguish creation from later updates | Copy edits report `:conflict-copy? false` with `:conflict-of` unchanged in every record |
| Single `parent-version` instead of version vectors | Bounded scope; a scalar head version gives the same stale/fresh decision for one server | `parent > head` rejected, `parent < head` conflict copy, `parent = head` new version |
| `:need-blocks` checked after existence/parent checks but before the stale decision | A conflict copy also needs all blocks; existence errors are cheaper and more fundamental | Fixed check order in README |
| No delete, rename-by-move, path uniqueness, or directories | Bounded scope | Paths are labels only |
| Journal entries omit `blocklist` | Bounds page output size (≤ 500 entries) independent of blocklist length | Clients use `get-file-version` for content |
| Journal seqs contiguous per namespace | Lets clients and tests detect gaps/duplication | Exactly one seq per accepted commit |

## Operations

### `register-blocks!`

- Latency: hundreds of milliseconds acceptable; outcome must be visible
  after the barrier.
- Throughput: proportional to upload volume; bursts of up to 1024 hashes
  per call.
- Invariants: a hash has exactly one size per namespace forever; atomic
  all-or-nothing per call; re-registration is a no-op.
- Data growth: block index grows without bound per namespace; point
  lookup by hash dominates.
- Concurrency: two registrations of the same hash with equal size in
  either order both succeed; with different sizes the first processed
  wins and the second is rejected as a whole.
- Edge cases: 1024 entries all new; 1024 entries all known; size 0;
  size at upper bound; same hash repeated in one call with equal sizes
  (allowed, counted once in `:registered`); same hash repeated with
  different sizes (structural throw, not durable); hash known in another
  namespace with a different size (independent, accepted).

### `commit-file!`

- Latency: hundreds of milliseconds acceptable.
- Throughput: one per file change; proportional to blocklist length.
- Invariants: head version increases by exactly one per accepted
  non-conflict commit; conflict copies never modify the original; every
  referenced hash is registered; `size-bytes` counts repeats; exactly one
  journal entry per accepted commit; at most one conflict copy per
  request-id.
- Data growth: versions per file unbounded; files per namespace
  unbounded; journal unbounded.
- Concurrency: two clients committing against the same head with
  different request-ids — the first processed becomes head+1 and the
  second becomes a conflict copy (its parent is now stale). Order of
  processing equals issue order within a namespace.
- Edge cases: empty blocklist (create with size 0); blocklist of 1024
  identical hashes (size = 1024 × size); parent-version equal to head
  after a conflict copy was created for another request (head unchanged
  → still fresh); parent-version 1 on a conflict copy (updates the copy;
  `:conflict-copy? false`, `:conflict-of` unchanged, version 2);
  create with a generated-form id (structural: nil parent requires
  client form); commit with non-nil parent against a valid but absent
  generated-form id (`:no-such-file`); stale commit against a copy
  (nested copy `~r2` with `:conflict-of "~r1"`); `need-blocks` where the
  same missing hash appears several times (listed once, first-occurrence
  order); a stale commit that also lacks blocks (`:need-blocks` wins, no
  copy created).

### `get-outcome`, `get-block-size`, `get-file`, `get-file-version`

- Latency: low tens of milliseconds; constant storage reads.
- Edge cases: unknown namespace → nil; version greater than head →
  nil (version `0` or negative is out of the README bounds and throws
  `IllegalArgumentException` instead); conflict copy head record has
  `:conflict-of` set and `:version 1`;
  original file's record unchanged after a conflict.

### `get-changes`

- Latency: proportional to `limit`.
- Invariants: strictly ascending contiguous seqs; every accepted commit
  appears exactly once; a conflict copy appears with its own file-id and
  `:conflict-of`.
- Edge cases: `after-seq 0` on an empty namespace → `[]`; `after-seq`
  beyond the last seq → `[]`; `limit` 1; `limit` 500 with more available
  (exactly 500 returned, next call continues from the 500th seq).

## Entity state × write matrix

Entities: **block** (unregistered / registered with size s), **file**
(absent / exists with head h, possibly a conflict copy), **request-id**
(unused / original accepted / original rejected).

Block × `register-blocks!`
- unregistered × register(size s): registered(s). get-block-size → s.
  get-outcome → accepted, `:registered` counts it.
- registered(s) × register(size s): no change. get-block-size → s.
  `:registered` excludes it.
- registered(s) × register(size t≠s): whole call rejected
  `:size-mismatch`. get-block-size → s for all hashes in the call that
  were already known; nil for hashes in the call that were new (nothing
  from the call was registered).

File × `commit-file!`
- absent × parent nil: created at v1. get-file → v1 record;
  get-file-version v1 → record; get-changes shows one new entry;
  get-outcome accepted `:version 1 :conflict-copy? false`.
- absent × parent non-nil: `:no-such-file`. get-file → nil; journal
  unchanged.
- exists(h) × parent nil: `:file-exists`. get-file unchanged.
- exists(h) × parent = h: head h+1. get-file → new record;
  get-file-version h → old record retained; journal +1.
- exists(h) × parent < h: conflict copy created; get-file(file-id)
  unchanged (still h); get-file("~" request-id) → v1 with
  `:conflict-of file-id`; journal +1 with the copy's id; outcome
  `:conflict-copy? true`.
- copy(`:conflict-of` o, head h) × parent = h: head h+1 on the copy;
  outcome `:conflict-copy? false`, `:conflict-of o`; get-file-version
  of every version of the copy carries `:conflict-of o`.
- exists(h) × parent > h: `:unknown-parent`; nothing changes.
- any × blocklist with unregistered hash (after the checks above):
  `:need-blocks`; nothing changes; outcome lists missing hashes.

Request-id × any command
- unused × command: processed as original.
- original accepted × same payload: replay; all reads identical to
  before the replay; journal unchanged; `:conflicting-attempts`
  unchanged.
- original accepted × different payload: no state change;
  `:conflicting-attempts` +1; all other outcome fields identical.
- original rejected × same payload: still rejected, nothing changes
  (in particular a `:need-blocks` rejection does not re-evaluate after
  blocks are registered).
- original rejected × different payload: `:conflicting-attempts` +1,
  still rejected with the original reason.

## Adversarial cases for private tests

Boundary
- 1024-hash blocklist with all hashes distinct and registered; size sum
  near 1024 × 10^9 (fits in Long).
- Blocklist with 1024 copies of one hash: `size-bytes` = 1024 × size;
  `need-blocks` lists it once if missing.
- Zero-size blocks and empty blocklist produce `size-bytes 0`.
- `limit` 500 pages across a journal of 1200 entries: three pages, seqs
  1..500, 501..1000, 1001..1200, then `[]`.
- ids at 128 characters; path at 1024 characters.
- Path max bound: stale commit with a 1024-character path and a
  128-character request-id: stored `:path` is exactly 1024 characters,
  ends with the full `" (conflicted copy " request-id ")"` suffix, and
  starts with the first `1024 − 147` characters of the input path; a
  short path is kept whole plus the suffix.
- 129-character returned id: stale commit with a 128-character
  request-id yields file-id `"~" + request-id` (129 chars); `get-file`
  and `get-file-version` accept it; a commit with `parent-version 1`
  against it is accepted (version 2, `:conflict-copy? false`,
  `:conflict-of` preserved); a commit with `parent-version nil` against
  it throws.
- Nesting without collision: stale commit r1 against file f → `~r1`
  (`:conflict-of "f"`); stale commit r2 against `~r1` → `~r2`
  (`:conflict-of "~r1"`); stale commit r3 against f → `~r3`; all four
  files distinct, f unchanged throughout; `get-changes` shows each copy
  once with its own `:conflict-of`.
- Valid generated-form id that names no file: `get-file` → nil;
  `get-file-version` → nil; commit with non-nil parent →
  `:no-such-file`.

Retry / idempotency
- Replay of an accepted create: no second version, journal length
  unchanged.
- Replay of a stale commit: exactly one conflict copy, journal unchanged.
- Same request-id, different blocklist after an accepted commit:
  `:conflicting-attempts 1`, head and journal unchanged; a third attempt
  with yet another payload → `2`.
- Malformed command reusing an existing request-id (e.g. 1025-character
  path): throws; the original outcome and its `:conflicting-attempts`
  are unchanged; journal unchanged.
- Malformed command with a fresh request-id: throws; `get-outcome` for
  that id stays nil; the same id then succeeds as an original.
- Replay of a rejected `:need-blocks` commit after blocks were
  registered is not re-evaluated (replay precedes business checks).
- Out-of-bounds query arguments (`limit 0`, `limit 501`, `after-seq -1`,
  empty `ns-id`, a `file-id` such as `"a~b"`): `IllegalArgumentException`,
  no state change.
- Two clients from `:wrap-client` on the same cluster: a commit through
  one is visible through the other after `wait-for-processing!`.
- Same request-id reused across command types in one namespace
  (`register-blocks!` then `commit-file!`): the second is a conflicting
  attempt of the first; no commit happens.
- Same request-id in two namespaces: both processed independently.
- `:need-blocks` rejection replayed after blocks were registered: still
  rejected; a new request-id succeeds.

Ordering
- Register then commit in the same namespace without an intermediate
  barrier: commit sees the blocks (issue order = processing order).
- Two commits against head 1 from different request-ids issued
  back-to-back: first → v2, second → conflict copy; journal seqs in
  issue order.
- Interleaved commits to two files in one namespace: journal seqs
  interleave in issue order and remain contiguous.
- Journal seqs never skip when a commit is rejected between accepted
  ones.

Conservation / consistency
- For every journal entry, `get-file-version` of that (file-id, version)
  exists and has the same `:size-bytes`, `:path`, `:request-id`.
- Number of journal entries equals total number of stored versions across
  all files in the namespace.
- After a conflict, the original head's `:seq` is unchanged and the copy's
  `:seq` is the next contiguous value.
- Every hash in every stored version resolves via `get-block-size` to the
  size used in that version's `size-bytes`.

## Efficiency review concerns

- `get-file` on a file with thousands of versions must not read or
  deserialize the whole version history.
- All bounds must hold as one namespace's own history grows (files,
  versions, blocks, journal), not only as other namespaces grow.
- The client wrapper must hold no process-local business state.
- `get-changes` must start at `after-seq` directly rather than scanning
  from the beginning of the journal; cost must not depend on
  `after-seq`.
- `commit-file!` block existence checks must be bounded by blocklist
  length (≤ 1024 point reads) and must not load the namespace's whole
  block index.
- A namespace's block index, file map, version history, and journal must
  each be stored so that a single large namespace does not force
  whole-collection reads or serialization on any operation.
- The outcome store must not grow the per-request cost with the number of
  prior requests in the namespace.
- Journal entries must omit blocklists so page payloads stay bounded by
  `limit`.
- Retry safety: reprocessing a command after a failure must not produce
  a second version, a second conflict copy, or a duplicated journal seq.
