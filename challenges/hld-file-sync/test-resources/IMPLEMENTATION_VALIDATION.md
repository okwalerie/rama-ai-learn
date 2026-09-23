# Implementation Validation — hld-file-sync (private reference, build phase)

Reviewed: `test-resources/hld_file_sync/module.clj` (462 lines) against
`test-resources/PLAN.md`, README.md, and `src/hld_file_sync/protocol.clj`.
Line numbers refer to the module as of this build. Each check was traced
in code, not assumed.

## Redundant conditionals
- `<<if (instance? RegisterBlocks *cmd)` (line 310) — the two branches do
  different work (block registration vs. commit). `<<cond` on `*req`
  (line 307) — three distinct actions (original / replay / conflict).
  `<<if *copy?` (line 345) guards one extra write only in the copy case.
  PASS.

## Consecutive keypath
- Every path is a single `(keypath *ns :field ...)`; no adjacent keypaths
  (lines 290, 292, 306, 324, 330, 335, 338, 346–358, 362, 370). PASS.

## Select-compute-transform
- `:ingress-seq` (290–292): the value `*pos` is needed downstream (it is
  the sort key), so the read is required; `(term inc)` would not expose
  it. `:next-seq` (338, 358): the new seq is part of the version record,
  journal key and outcome, so the read is required. Request counter (370):
  `termval` of the already-read record, no extra read. PASS.

## Unnecessary nil->val
- `(nil->val 0)` appears only before `inc` (290) and before `(inc prev-seq)`
  (338); absent `:head` / `:conflict-of` / request records navigate to
  nil and are handled by the pure helpers. PASS.

## :allow-yield?
- No topology read iterates a subindexed structure: block sizes are k ≤ 1024
  point reads in `read-known-sizes` (226–241) with `(yield-if-overtime)`
  per iteration; head/version/request reads are point reads. The only
  range read is the client-side `foreign-select` for `get-changes`
  (452–455), bounded by `limit ≤ 500`, and foreign reads yield on the
  source task implicitly. PASS.

## Non-subindexed collections without size limits
- `blocklist` (vector-schema String) ≤ 1024 by `check-blocklist!` (100–103).
  `:need-blocks` ≤ distinct hashes of a blocklist ≤ 1024. `blocks` in the
  stored `RegisterBlocks` payload ≤ 1024 by `check-blocks!` (86–98). The
  `+vec-agg` group per namespace is transient and bounded by
  `depot.microbatch.max.records` 200 (248). Every growing collection
  (`:blocks`, `:files`, `:versions`, `:journal`, `:requests`) is
  `{:subindex? true}` (256–282). PASS.

## Stream topology idempotency / partial failure
- No stream topology; the single microbatch topology gives exactly-once
  PState updates, and `:ingress-seq` lives in the PState so it rolls back
  with a failed attempt (290–292). N/A — PASS.

## Single depot append per client operation
- `register-blocks!` (397–403) and `commit-file!` (405–417): one
  `foreign-append!` each, after validation, then `nil`. PASS.

## Application-state caches survive restart
- No TaskGlobal, no in-memory cache. The client atom (`cnt`, 465) holds
  only the append count for `wait-for-processing!`; a fresh client after
  restart starts a new count and the cumulative processed count remains
  valid (testing.md). PASS.

## No reimplementation of built-in operations
- Helpers use `sort-by`, `distinct`, `reduce`, `subs`; no hand-rolled
  aggregator, partitioner or navigator. PASS.

## Plan conformance
- Depot `*commands` `(hash-by :ns-id)`, records `RegisterBlocks` /
  `CommitFile` (23–25, 247): matches.
- One PState `$$namespaces` with the plan's typed nested schema (251–282):
  matches field for field, including `ICommand`/`IOutcome` polymorphism.
- Ordered per-namespace batching (284–372): pre-agg stamp before the
  `+group-by` partitioner, `sort-pairs-by-position` (116–120) after the
  aggregator, one `loop<-` per namespace with `(yield-if-overtime)` at the
  top of every iteration, every branch unifying into one `continue>`
  (372): matches. Build-time verification (a): `local-transform>` and
  `loop<-` in the post-agg of a microbatch `<<batch` compile and run
  (suite green); (b) `+group-by` routing agrees with the PState partition
  at 2 and 4 tasks (all reads through foreign selects return the written
  data at both counts); (c) `sort-pairs-by-position` is a `defn`.
- Verification (d) chose the plan's documented fallback: `submap` with
  `:allow-yield?` was replaced by the `read-known-sizes` loop of point
  reads with `(yield-if-overtime)` because a yielding select reads a
  stable snapshot and could miss blocks registered earlier in the same
  microbatch (the register→commit-without-barrier ordering test depends
  on seeing them). Cost is identical (k point reads). Allowed by PLAN.md
  "fallback is a `loop<-` of point reads".
- Entry creation by field writes only: file `:head` (348), `:conflict-of`
  only on copies (345–347), `:next-seq` (358), `:ingress-seq` (292),
  request record (362, 370 — no subindexed child). No `termval` of the
  namespace or file entry anywhere. matches.
- Query topology `file-head` (374–383): `(|hash *ns)`, `:head` read, `<<if`
  nil branch, version read, `head-record` builds a plain map, `(|origin)`.
  The `:conflict-of` read in the plan is unnecessary because the version
  record already carries provenance; the emitted record is identical.
- Client (388–460): every query validates bounds before any call
  (`check-long!` requires `Long`), `get-changes` returns `[]` for
  `Long/MAX_VALUE` without a call, `render-outcome` is structural,
  `get-outcome` selects only `[:command :outcome :conflicting-attempts]`
  so the stored payload is never shipped.
- Divergence fixed this build: `register-plan` (127–145) previously scanned
  the accumulated vector per block (quadratic in the submitted list); it
  now uses a `#{}` membership set, preserving first-occurrence order.
PASS.

## Spec checks beyond the plan
- Validation order: structural checks (397–401, 405–411) run before the
  append, so a malformed retry never reaches the topology and an existing
  outcome is untouched (tested).
- Replay/conflict before business validation: `<<cond` on the stored
  request record (307) precedes every business read (tested with a
  `:need-blocks` replay after registration).
- Rejection order `:file-exists`, `:no-such-file`, `:unknown-parent`,
  `:need-blocks`: `commit-precheck` (147–154) then `missing-hashes` in
  `commit-plan` (174–189).
- Conflict path ≤ 1024 with the full suffix: `conflict-path` (166–172).
- Custom record schema survives `update-module!`: outcomes and payloads
  stored before the update are read back unchanged after it (tested at 2
  and at 4 tasks / 2 workers).

## Storage granularity (blob review)
No stored value grows with history: the largest field is a ≤ 1024-hash
blocklist (or the same list inside the stored `CommitFile` payload) that
the README makes an atomic value. Journal entries omit blocklists
(189–191). Event counts cannot detect blobs; this review is the check.

## Known operational observation
The first query-topology call after `rtest/update-module!` logs
`ModuleAssignmentInfoNotFoundException ... clearing caches; rethrowing`
from Rama's client-side cluster manager and then returns the correct
result on the internal retry (~120 ms). Reproduced deterministically in a
fresh JVM (`get-file` via `foreign-invoke-query` immediately after the
update); plain foreign selects/appends after the update do not log it. It
is Rama's stale-instance cache redirect, not a module defect, and no
assertion is affected.

## Verdict
**pass** — every check traced above holds; the one divergence found
(quadratic `register-plan`) was fixed in place and the suite is green.

## Full-spec-review addendum (2026-09-23)

The reviewing session re-read the whole module against the whole README
and changed nothing in it (sha256 prefix `9048c319e3c63ca0` before and
after the session's mutant runs). Two observations recorded for
accuracy, neither a defect:

- Measured cost at the 1024-entry input maximum (big namespace, 4 tasks
  and 2 tasks identical): `register-blocks!` with 1024 new hashes =
  2073 RocksDB reads / 1036 writes; `commit-file!` with a 1024-hash
  blocklist = 1036 reads / 5 writes. The register figure is ≈ 2 reads
  per hash (the known-size read plus the top-level navigation of each
  write-only block transform), higher than the plan's "2 + k seeks"
  estimate but still linear in the submitted list as the README
  requires.
- Read visibility during `yield-if-overtime` inside the per-namespace
  loop was re-checked against `references/microbatch.md` ("readers
  outside the owning topology … see only committed state"); no external
  reader can observe a half-applied command.

The storage-granularity (blob) review above remains the only check for
whole-collection values; RocksDB event counts cannot detect them.
