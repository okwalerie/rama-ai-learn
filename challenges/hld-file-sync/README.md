# HLD File Sync Challenge

Build the metadata service of a Dropbox-style file sync system: a
namespace-scoped, content-addressed block index; versioned files with
stable identifiers; a `need-blocks` negotiation so a commit can only
reference blocks the namespace already knows; conflict copies instead of
last-writer-wins when a client commits against a stale parent version;
and a per-namespace change journal that clients page through with a
cursor.

This challenge stores **metadata only**. Block bytes, chunking, delta
sync, transport, permissions/sharing, retention/garbage collection,
directories, rename-by-move, deletion, and notification/long-poll
infrastructure are out of scope. A block is an opaque `hash` string with
a byte `size`; the module never sees content.

## Protocol

Your implementation must satisfy the `FileSyncModule` protocol defined in
`src/hld_file_sync/protocol.clj`. The README is the authoritative
contract; the protocol docstrings summarize it.

## Types and bounds

| Term | Type | Bounds |
|---|---|---|
| `ns-id`, `request-id`, `hash` | non-empty `String` | ≤ 128 characters |
| `file-id` (client form) | non-empty `String` | ≤ 128 characters; must not contain `~` |
| `file-id` (generated form) | `String` | `~` followed by a valid `request-id`; 2 to 129 characters |
| `path` | non-empty `String` | ≤ 1024 characters |
| `size` (bytes) | `Long` | `0 ≤ size ≤ 1,000,000,000` |
| `blocks` (register) | vector of `{:hash String :size Long}` | 1 to 1024 entries |
| `blocklist` (commit) | vector of `hash` | 0 to 1024 entries; repeats allowed; order significant |
| `parent-version` | `nil` or `Long` | `≥ 1` when non-nil |
| `version`, `seq` | `Long` | positive |
| `after-seq` | `Long` | `≥ 0` |
| `limit` | `Long` | `1 ≤ limit ≤ 500` |

Paths are labels carried by each version. They are **not** required to be
unique within a namespace, and no directory semantics apply. Identity is
the `file-id`, which is stable across all versions of a file.

A `file-id` argument is structurally valid only in one of the two forms
above; anything else (for example `a~b`, or `~` followed by more than
128 characters) throws. Client form is the only form accepted by a
create (`parent-version` `nil`). Generated form is the form of every
conflict-copy id the module returns; it is accepted by every query and
by any commit with a non-nil `parent-version`, so a returned copy can be
edited and can itself be the target of a further conflict. A valid id
of either form that names no file follows the missing-file rules
(`:no-such-file` on commit, `nil` on query); nothing is inferred from
its shape.

## Command conventions

Every mutating operation is a **command**. A command:

- takes a client-chosen `request-id` as its first argument after `this`;
- returns `nil` and never returns a business result;
- returns only after the command has been durably accepted for
  processing;
- produces exactly one durable **outcome**, readable with
  `get-outcome` after `wait-for-processing!`.

Request-ids are scoped to a namespace: the same `request-id` used in two
different namespaces names two unrelated requests. Within one namespace a
`request-id` is shared across all command types (a `register-blocks!`
and a `commit-file!` may not reuse one id).

**Outcome shape.** `get-outcome` returns `nil` if the namespace has never
processed that `request-id`, otherwise a map:

```clojure
{:status               :accepted | :rejected
 :command              :register-blocks | :commit-file
 :reason               <keyword>        ; present only when :rejected
 :conflicting-attempts <Long>           ; 0 initially, see below
 ...}                                   ; command-specific keys listed per command
```

**Validation classes.**

1. *Structural* violations (wrong type, out of the bounds above, empty
   or nil where non-empty is required, duplicate `:hash` with differing
   `:size` inside one `register-blocks!` call) make the client method
   throw `IllegalArgumentException` synchronously. Nothing is appended
   and no outcome is created.
2. *Business rejections* are well-formed commands the domain refuses.
   They produce a durable `:rejected` outcome with a `:reason`, and no
   other state changes.
3. *Accepted* commands change state exactly as specified and produce a
   durable `:accepted` outcome.

**Validation order.** Structural validation runs first, before the
`request-id` is consulted. A malformed command therefore leaves an
unused `request-id` unused, and leaves an already-used `request-id`'s
outcome (including its `:conflicting-attempts`) unchanged. Queries are
validated against the same bounds and throw `IllegalArgumentException`
on violation without changing any state. Replay and conflict handling
(next paragraph) runs after structural validation and before any
business validation: a replayed or conflicting command is never
re-evaluated against current namespace state.

**Idempotency and conflicts.** The first processed command carrying a
given `request-id` in a namespace is the *original*. The *payload* of
a command is its command type together with every argument other than
`this` and `request-id`; two payloads are identical when they are equal
under Clojure `=`. A later command with the same `request-id` and an
identical payload is a *replay*: it has no effect and the outcome is
unchanged. A later command with the same `request-id` but a different
payload is a *conflicting attempt*: it has no effect on namespace state
and does not alter any field of the original outcome except
`:conflicting-attempts`, which increases by one per conflicting attempt.
A replay of a rejected original stays rejected; a replay of an accepted
commit creates no second version and no second conflict copy.

**Ordering.** Commands issued by one client against the same namespace
are processed in the order the client issued them. Commands against
different namespaces have no relative ordering guarantee.

**Durability.** Outcomes, block registrations, every file version, and
every journal entry are retained for the lifetime of the module. Nothing
is ever deleted or overwritten.

## Commands

### `register-blocks! [this request-id ns-id blocks]`

Registers block metadata in the namespace. A hash already registered
with the same size is not an error.

Rejections, checked in this order:

| `:reason` | Condition |
|---|---|
| `:size-mismatch` | some `:hash` is already registered in `ns-id` with a different `:size` |

The command is atomic: on rejection no hash from the call is registered.
Accepted outcome adds `:registered` (Long), the number of hashes newly
registered by this command (0 if all were already known).

### `commit-file! [this request-id ns-id file-id path blocklist parent-version]`

Records a new version of file `file-id` whose content is the ordered
`blocklist`. `parent-version` is `nil` to create a file and otherwise the
head version the client last saw.

Rejections, checked in this order:

| `:reason` | Condition |
|---|---|
| `:file-exists` | `parent-version` is `nil` and `file-id` exists in `ns-id` |
| `:no-such-file` | `parent-version` is non-nil and `file-id` does not exist in `ns-id` |
| `:unknown-parent` | `parent-version` is greater than the file's current head version |
| `:need-blocks` | some hash in `blocklist` is not registered in `ns-id` |

A `:need-blocks` outcome carries `:need-blocks`: a vector of the missing
hashes, each listed once, in order of first occurrence in `blocklist`.
Because a same-id retry is a replay, a client that then registers the
blocks must resubmit the commit under a **new** `request-id`.

If none of the above applies, exactly one of these happens:

- **New head version** (`parent-version` is `nil`, or equals the current
  head version): the file's head becomes `head + 1` (or `1` on create)
  with the given `path`, `blocklist`, and `size-bytes`.
- **Conflict copy** (`parent-version` is non-nil and less than the
  current head): the original file is **not** modified. Instead a new
  file is created with `file-id' = (str "~" request-id)`, version `1`,
  the submitted `blocklist`, `:conflict-of file-id` (the id the stale
  commit targeted, whether that id is client form or itself a copy), and
  `path' = (str prefix suffix)` where
  `suffix = (str " (conflicted copy " request-id ")")` and `prefix` is
  `path` truncated to its first `1024 − (count suffix)` characters (the
  whole of `path` when it is already that short), so `path'` never
  exceeds 1024 characters. The generated id is collision-free because
  request-ids are unique within the namespace, however deeply copies of
  copies nest. Last-writer-wins is never applied. The conflict copy is
  an ordinary file afterwards: it can be updated with `parent-version
  1`, and a stale commit against it produces a further copy.

`size-bytes` is the sum of the sizes of the blocks in `blocklist`,
counting every repeated hash each time it occurs; an empty `blocklist`
gives `0`.

Every accepted commit (either kind) appends exactly one entry to the
namespace journal. Accepted outcome adds:

```clojure
{:file-id       <id of the file that received the version>
 :version       <Long>
 :seq           <namespace journal seq>
 :size-bytes    <Long>
 :conflict-copy? <boolean>   ; true only if this command created a new copy
 :conflict-of   <provenance of the file that received the version>}
```

`:conflict-of` is the immutable provenance of a file: `nil` for a file
created by a client (`parent-version` `nil`), otherwise the `file-id`
targeted by the stale commit that created it. It is identical in every
version record, journal entry, and outcome concerning that file,
including later head versions of a copy. `:conflict-copy?` is `true`
only in the outcome of the command that created a copy; an ordinary
update of a copy reports `:conflict-copy? false` with the copy's
`:conflict-of` unchanged.

## Queries

### `get-outcome [this ns-id request-id]`

Outcome map as defined above, or `nil`.

### `get-block-size [this ns-id hash]`

The registered size (`Long`), or `nil` if the hash is not registered in
the namespace.

### `get-file [this ns-id file-id]`

The head version record, or `nil` if the file does not exist:

```clojure
{:file-id String :version Long :path String :blocklist [String ...]
 :size-bytes Long :request-id String :seq Long :conflict-of (String | nil)}
```

### `get-file-version [this ns-id file-id version]`

The record for that specific version (same shape), or `nil` if the file
or the version does not exist. Version history is complete: every
version `1..head` is retrievable forever.

### `get-changes [this ns-id after-seq limit]`

Journal entries of the namespace with `:seq` strictly greater than
`after-seq`, ascending, at most `limit`:

```clojure
{:seq Long :file-id String :version Long :path String
 :size-bytes Long :request-id String :conflict-of (String | nil)}
```

Returns an empty vector when nothing follows `after-seq` or the namespace
is unknown. Journal seqs in a namespace start at `1` and each accepted
commit receives the previous seq plus one, in processing order, so a
client that passes the last `:seq` it saw never misses or repeats an
entry. `blocklist` is intentionally omitted from journal entries; fetch
it with `get-file-version`.

## Invariants

- A `file-id` never changes; a file's `:version` only increases by one per
  accepted commit against it.
- A file's `:conflict-of` never changes after creation.
- Every stored `:path` is at most 1024 characters.
- Every version's `:size-bytes` equals the size sum of its blocklist with
  repeats counted.
- Every hash referenced by any stored version is registered in that
  namespace with the size used to compute the version's `:size-bytes`.
- A conflict copy exists for every commit that was accepted against a
  stale parent, and at most one conflict copy exists per `request-id`.
- Journal seqs are contiguous and each corresponds to exactly one stored
  version.

## Resource guarantees

The work done by any operation must be bounded by the sizes of its own
inputs and outputs and must not grow with the amount of unrelated
history in the namespace or the module. These bounds also hold as the
namespace's **own** history grows, not only as other namespaces grow:
no operation may read, deserialize, or rewrite an unbounded
per-namespace collection (all files, a file's whole version history,
the block index, or the journal) as one value.

- `commit-file!` and `register-blocks!`: proportional to the length of
  the submitted list.
- `get-file`, `get-file-version`, `get-block-size`, `get-outcome`: a
  constant number of storage reads, independent of how many versions,
  files, or blocks the namespace holds.
- `get-changes`: proportional to `limit`, not to the journal length or to
  `after-seq`.

Tests exercise both 2 and 4 tasks; all guarantees must hold for both.

**Shared state.** All business state lives in the deployed module.
Several clients produced by `:wrap-client` against the same cluster
observe the same state: a command issued through one client is visible,
after `wait-for-processing!`, through every other. `wait-for-processing!`
on any client of one `create-module` result waits for every command
issued through any client of that result, including clients created
later. The client wrapper keeps no process-local business state (no
in-memory copies of files, blocks, journal positions, or outcomes); it
holds only handles to the cluster.

## Contract: `create-module`

Your namespace must provide a `create-module` function returning:

```clojure
{:module       <RamaModule instance>
 :wrap-client  (fn [ipc] -> <FileSyncModule implementation>)}
```

- `:module` — the Rama module to deploy
- `:wrap-client` — given a started IPC cluster, returns a reified
  `FileSyncModule` implementation

You choose all internal names (depots, PStates, topologies) freely.

## Synchronization: `Synchronizable`

Your `wrap-client` must reify `harness/Synchronizable`. See the docstring
on that protocol for implementation requirements. Tests call
`(harness/wait-for-processing! client)` after writes, before reads.

## Namespace

Your solution must be in namespace `hld-file-sync.module`.

## File Location

Write your solution to:
```
implementations/hld-file-sync/src/hld_file_sync/module.clj
```

## nREPL

Start an nREPL with the test classpath:

```bash
clj -M:nrepl
```

## Attribution and license

This challenge is adapted from "Design a File Sync Service (Dropbox /
Google Drive)", module 8.14 of *The HLD Handbook* by handbook-academy
(<https://hld.handbook.academy/curriculum/case-studies/file-sync/>,
source repository <https://github.com/handbook-academy/engineering-handbook>),
licensed under [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/).

Changes: the prose above is a rewritten, bounded specification. It keeps
the block index, `need-blocks` negotiation, stable file identifiers,
versioned journal with cursor paging, and dual-copy conflict handling; it
replaces version vectors with a single parent-version check, fixes exact
identifiers for conflict copies, and removes block storage, chunking,
delta sync, sharing/permissions, retention, LAN sync, and all
infrastructure discussion. The adapted prose in this README remains
licensed under CC BY-SA 4.0.
