# Implicit Spec

<!-- Phase 0 artifact for hld-url-shortener. Requirements only; no design. -->

Domain: custom-alias URL shortener core. Entities: **link** (keyed by
alias), **create request** (keyed by alias + request-id), **click
observation** (keyed by alias + click-id). Time is logical ticks
supplied by callers. Harness runs 2 or 4 tasks and a second client;
every rule below must hold in every configuration.

Ordering: sequential writes from one client to the same logical owner
(alias) process in invocation order; writes from different clients
serialize arbitrarily but a write that returned before a
`wait-for-processing!` barrier processes before any write issued after
it. Latency figures below are production aspirations, never acceptance
thresholds; tests enforce only bounded-work contracts.

## Operations

### create-link! [alias request-id target-url expires-at]

- **Latency**: outcome must be observable after `wait-for-processing!`;
  no sub-millisecond requirement. Hundreds of ms acceptable.
- **Throughput**: ~350/s peak, driven by users publishing links. Low
  relative to reads.
- **Invariants**:
  - An alias is created at most once, ever. Deletion, expiry, or block
    never free it.
  - The first recorded outcome for (alias, request-id) is final. A retry
    with a different target-url/expires-at changes nothing and still
    reads back the original outcome.
  - Rejections are recorded as durably as creations.
  - Of concurrent creates for the same alias with different
    request-ids, exactly one `:created`; every other `:rejected
    :alias-taken`. No partial link (e.g. reserved alias with no
    target-url) is ever observable.
  - Processing retries (topology replay) must not produce a second
    `:created` or flip an outcome.
- **Data growth**: aliases unbounded (10M). Request-ids per alias up to
  1,000. Access is point lookup by alias and by (alias, request-id).
- **Concurrency**: creates for different aliases are independent. Same
  alias from one client: invocation order. Same alias from different
  clients: serialized in some order the module chooses.
- **Edge cases**: expires-at equal to 0 (link born expired: resolve-alias
  returns `:expired` for every now ≥ 0); expires-at nil (never expires);
  alias of length 1; target-url of exactly 8 chars ("https://"); request
  for an alias that was deleted (rejected); same request-id used for two
  different aliases (independent, both may be `:created`).

### delete-link! [alias]

- **Latency**: observable after wait; not latency critical.
- **Throughput**: rare (well under 10/s).
- **Invariants**: irreversible; alias remains reserved; click count
  remains readable and still increments on new observations; target-url
  and expires-at remain readable through `resolve-alias`.
- **Concurrency**: delete racing a create for the same alias from
  different clients: if the delete is processed first the alias does
  not exist so delete has no effect and the create succeeds; if the
  create is first the link is created then deleted. Either order is
  acceptable; only the two sequential outcomes are. From one client,
  create then delete always yields `:deleted`.
- **Edge cases**: delete of missing alias (no effect, alias still
  `:missing`, still creatable); double delete; delete of a blocked link
  (resolve-alias becomes `:deleted`; the block flag is irrelevant afterwards);
  delete of an expired link.

### block-link! / unblock-link! [alias]

- **Latency**: observable after wait.
- **Throughput**: rare.
- **Invariants**: flag is boolean and reversible; idempotent; never
  affects click counting; never affects reservation.
- **Concurrency**: block and unblock interleaved for one alias: the last
  processed wins. From one client that is the last invoked; across
  clients the module's processing order is the truth.
- **Edge cases**: block on missing alias (no effect; a later create
  starts unblocked); unblock on never-blocked alias; block on deleted
  alias (no observable effect because `:deleted` has precedence, and it
  stays unobservable forever since delete is irreversible); block on an
  expired link (resolve-alias becomes `:blocked`, since blocked outranks
  expired).

### record-click! [alias click-id]

- **Latency**: not latency critical; counts observable after wait.
- **Throughput**: ~100,000/s peak; one viral alias can receive thousands
  per second. This is the highest-volume write.
- **Invariants**:
  - Exactly-once counting per (alias, click-id) for existing aliases,
    including under processing retries.
  - Counted regardless of status (active/expired/blocked/deleted).
  - Dropped without trace for missing aliases.
  - The count never decreases.
- **Data growth**: up to 1,000,000 observations per alias lifetime;
  the set of counted click-ids per alias is unbounded and must not be
  scanned per observation or per count read.
- **Concurrency**: many concurrent observations for one alias: each
  distinct click-id adds exactly 1; duplicates add 0; final count equals
  the number of distinct click-ids processed after the alias existed.
- **Edge cases**: click-id reused across different aliases (independent
  counts); observation processed before the create for that alias
  (dropped, count 0 after create); observation after delete (counted).

### get-create-outcome [alias request-id]

- **Latency**: production aspiration ~50 ms (not enforced); single point read, fixed work.
- **Invariants**: returns nil until the pair is processed; then the
  fixed recorded outcome forever. Must not wait, must not mutate.
- **Edge cases**: unknown alias (nil); known alias, unknown request-id
  (nil); pair processed but link since deleted (`:created` still).

### resolve-alias [alias now]

- **Latency**: production aspiration ~50 ms (not enforced); the hot path. Read work fixed (enforced).
- **Throughput**: ~100,000/s peak, hot-key skewed.
- **Invariants**: exactly one status by precedence
  `:missing` > `:deleted` > `:blocked` > `:expired` > `:active`;
  `:expired` iff expires-at non-nil and now ≥ expires-at; map shape is
  exactly as documented, with `:target-url` and `:expires-at` echoing
  creation values for every existing alias. Never waits, never mutates,
  never records a click.
- **Edge cases**: now = expires-at (expired); now = expires-at − 1
  (active); expires-at nil with huge now (active); alias whose create was
  rejected only (missing); resolve-alias during an in-flight create before
  wait (may be missing; tests wait first).

### get-click-count [alias]

- **Latency**: production aspiration ~50 ms (not enforced); fixed work (enforced).
- **Invariants**: equals the number of distinct counted observations;
  0 for missing aliases; monotone non-decreasing over time; unaffected
  by delete/block/expiry. Never waits, never mutates.
- **Edge cases**: alias with 1,000,000 observations (still fixed work);
  alias created but never clicked (0).

## Entity State × Write Matrix

### Entity: link (keyed by alias)

States: **missing** (never created), **active** (created, not deleted,
not blocked, not expired at the reading now), **expired** (created,
expires-at ≤ now, not blocked, not deleted), **blocked** (flag set, not
deleted), **deleted**. Note expired/active depend on the reader's now;
blocked and deleted are stored facts. Reads: `resolve-alias`,
`get-click-count`, `get-create-outcome`.

```
missing x create-link! (new pair)
  - resolve-alias: :active or :expired (by expires-at vs now) with creation values
  - get-click-count: 0 (nothing counted before existence)
  - get-create-outcome: {:outcome :created}
missing x create-link! (pair already recorded as :rejected earlier — impossible,
                        a rejection implies the alias exists)
missing x delete-link! / block-link! / unblock-link!
  - resolve-alias: {:status :missing}
  - get-click-count: 0
  - get-create-outcome: nil for every pair
missing x record-click!
  - resolve-alias: :missing
  - get-click-count: 0 (dropped, no trace)
  - get-create-outcome: nil

active x create-link! (new request-id)
  - resolve-alias: unchanged :active, original target-url
  - get-click-count: unchanged
  - get-create-outcome(new pair): {:outcome :rejected :reason :alias-taken}
  - get-create-outcome(original pair): {:outcome :created}
active x create-link! (retry of original pair, any payload)
  - resolve-alias: unchanged (first request wins)
  - get-click-count: unchanged
  - get-create-outcome: {:outcome :created}
active x delete-link!
  - resolve-alias: :deleted with creation values
  - get-click-count: unchanged
  - get-create-outcome: unchanged
active x block-link!
  - resolve-alias: :blocked
  - get-click-count: unchanged
  - get-create-outcome: unchanged
active x unblock-link!
  - resolve-alias: :active (no-op)
  - others unchanged
active x record-click! (new click-id)
  - resolve-alias: unchanged
  - get-click-count: +1
  - get-create-outcome: unchanged
active x record-click! (duplicate click-id)
  - get-click-count: unchanged; everything else unchanged

expired x create-link! (any request-id)
  - resolve-alias: :expired (alias reserved forever)
  - get-create-outcome(new pair): :rejected :alias-taken
expired x delete-link!
  - resolve-alias: :deleted (deleted outranks expired)
  - get-click-count: unchanged
expired x block-link!
  - resolve-alias: :blocked (blocked outranks expired)
expired x unblock-link!
  - resolve-alias: :expired
expired x record-click! (new click-id)
  - get-click-count: +1 (late trusted observation)
  - resolve-alias: :expired

blocked x create-link!
  - get-create-outcome(new pair): :rejected :alias-taken; resolve-alias :blocked
blocked x delete-link!
  - resolve-alias: :deleted; the block flag becomes permanently unobservable
blocked x block-link!
  - resolve-alias: :blocked (idempotent)
blocked x unblock-link!
  - resolve-alias: :active or :expired depending on expires-at vs now
blocked x record-click! (new click-id)
  - get-click-count: +1; resolve-alias :blocked

deleted x create-link!
  - get-create-outcome(new pair): :rejected :alias-taken
  - resolve-alias: :deleted
deleted x delete-link!
  - no change
deleted x block-link! / unblock-link!
  - resolve-alias: :deleted (no observable change, ever)
  - get-click-count: unchanged
deleted x record-click! (new click-id)
  - get-click-count: +1; resolve-alias :deleted
```

### Entity: create request (keyed by alias + request-id)

States: **unrecorded**, **recorded :created**, **recorded :rejected**.
Reads: `get-create-outcome`, `resolve-alias`.

```
unrecorded x create-link!
  - get-create-outcome: :created if alias was missing, else :rejected
  - resolve-alias: :active/:expired if :created, else unchanged
recorded :created x create-link! (any payload)
  - get-create-outcome: :created; resolve-alias unchanged
recorded :rejected x create-link! (any payload)
  - get-create-outcome: :rejected :alias-taken; resolve-alias unchanged
any x delete-link!/block-link!/unblock-link!/record-click!
  - get-create-outcome: unchanged (outcomes are immutable history)
```

### Entity: click observation (keyed by alias + click-id)

States: **absent**, **counted**. (There is no "dropped" state: dropped
observations leave no trace.) Reads: `get-click-count`.

```
absent x record-click! (alias exists, any status)
  - get-click-count: +1; observation becomes counted
absent x record-click! (alias missing)
  - get-click-count: 0; observation stays absent
counted x record-click!
  - get-click-count: unchanged
counted x create-link!/delete-link!/block-link!/unblock-link!
  - get-click-count: unchanged (counts are never reset)
```
