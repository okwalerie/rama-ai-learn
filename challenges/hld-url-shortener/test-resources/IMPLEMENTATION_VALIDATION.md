# Implementation Validation

<!-- Phase 4 artifact for hld-url-shortener (BUILD phase, resumed from checkpoint 21e6676).
     Reviewed: test-resources/hld_url_shortener/module.clj against PLAN.md, README.md,
     protocol.clj and IMPLICIT_SPEC.md. Line numbers refer to module.clj as committed at 21e6676 (re-anchored during the full-spec
     review; the file has not changed). -->

## Redundant conditionals
Check: every branch of an `<<if` doing the same operation with only a variable differing.
- `CreateLink` branch (lines 44-59): the `<<if (nil? *link)` writes the link record only in the
  nil branch; the outcome write below it is a single unconditional `local-transform>` driven by
  `(ifexpr (nil? *link) :created :rejected)`. No duplicated branch bodies.
- `DeleteLink`/`BlockLink`/`UnblockLink` (62-80): one `<<if (some? *link)` each with a single write.
- `Click` (85-97): nested `<<if`, each level guards distinct writes. PASS.

## Consecutive keypath
Check: `(keypath *a) (keypath *b)`. Every path in the module is a single `keypath` with all
segments inline (`(keypath *alias :outcomes *request-id)`, `(keypath *alias :link :clicks)`,
`(keypath *alias :click-ids) (subselect (set-elem *click-id))`, `(keypath *alias :click-ids) NONE-ELEM`).
The last two combine keypath with a set navigator, not a second keypath. PASS.

## Select-compute-transform
Check: `local-select>` → computation → `local-transform>` with `termval` replaceable by an aggregator.
- Click count (86, 95-97): `*clicks` is read first because its presence is the existence test
  that gates the drop-without-trace rule; the increment `termval (inc *clicks)` reuses that
  already-read value, so no second read occurs. Replacing it with `+compound` and `(aggs/+sum 1)`
  would still require the existence read, giving the same seek count; not a saving. PASS.
- Create (44-59): the outcome value depends on `*link`'s presence, which is read for the
  existence check; the write is a plain `termval`. PASS.

## Unnecessary nil->val
Check: `nil->val` only where the next navigator needs non-nil.
The single use is client-side (`get-click-count`, line 142): `[(keypath alias :link :clicks) (nil->val 0)]`,
required so a missing alias yields `0` rather than nil per the protocol. No topology use. PASS.

## :allow-yield?
Check: iteration over subindexed structures of >~100 entries needs `{:allow-yield? true}`.
The only reads touching the subindexed `:outcomes` map and `:click-ids` set are point lookups
(`(keypath *alias :outcomes *request-id)` line 45; `(subselect (set-elem *click-id))` line 89).
No `ALL`, `MAP-VALS`, range or other iterating navigator anywhere in the module. PASS.

## Non-subindexed collections without size limits
Check: every unbounded inner collection is subindexed.
Schema (lines 28-36): `:outcomes` `(map-schema String Keyword {:subindex? true})` (≤1,000 per alias,
no code cap); `:click-ids` `(set-schema String {:subindex? true})` (≤1,000,000 per alias). `:link`
is a five-field `fixed-keys-schema` with no collection. PASS.

## Stream topology idempotency
No stream topology; the only topology is microbatch `core` (line 27). Microbatch replay is
exactly-once. Additionally each write is read-guarded: outcome recorded (45-46), link present
(47-48, 63-64, 70-71, 77-78), click-id member (88-91), so re-executing an event is a no-op. PASS.

## Partial failure in stream topologies
No stream topology. Every event's writes are on one task, in one microbatch transaction. PASS.

## Single depot append per client operation
Lines 115-118: `append!` performs exactly one `foreign-append!`; every `!` method (120-129) calls it
once. The `swap! counter inc` after the append is harness synchronization bookkeeping, not a
depot write, and runs only after the append returned. PASS.

## Application-state caches survive restart
No TaskGlobals, no in-process caches. The client wrapper holds only `ipc`, foreign handles and the
harness sync counter (no business data). PASS.

## No reimplementation of built-in operations
`resolve-status` (99-106) is a pure domain function (status precedence from the protocol), not a
duplicate of a Rama op. `ifexpr`, `inc`, `nil->val`, `set-elem`, `NONE-ELEM`, `subselect` are all
built-ins used directly. PASS.

## Plan conformance
PLAN.md vs module, item by item:
- Depot `*alias-events` `(hash-by :alias)`, five record types each carrying `:alias` (18-22): matches.
- One microbatch topology `core` owning `$$links` with the planned schema (27-36): matches, including
  the subindex flags and the `:link` fixed-keys record.
- Create processing: outcome read, link read, conditional link write, outcome write (44-59): matches
  the Writes table. Delete/Block/Unblock: read link, conditional flag write (62-80): matches.
- Click: link read (via `:clicks`), membership via `(subselect (set-elem *click-id))`, `NONE-ELEM`
  insert, `termval` of the already-read count (85-97): matches, including the Phase-2 navigator fix.
- Reads: three `foreign-select-one` point reads on `$$links` with the planned paths (130-142);
  `:rejected` expanded to the exact protocol map, `:expires-at` always present (Phase-2 output-shape
  fix): matches.
- Synchronization: shared counter created once in `create-module` (150), incremented after a
  successful append (117), `wait-for-microbatch-processed-count` on `core` (146): matches the
  Phase-2 sync fix.
No divergences. PASS.

## Spec checks beyond the plan
- Expiry boundary `now >= expires-at` (line 105), nil expiry never expires (104-105). PASS.
- `:missing` when no link (140), regardless of any `:outcomes` entries: a rejected-only alias
  cannot exist because rejection implies the link exists. PASS.
- Reads never append or transform; wrapper is stateless for business data. PASS.
- Click on missing alias: `*clicks` nil → no write of any kind, so a later identical click-id after
  creation is new (protocol "no trace"). PASS.

## Verdict
pass — every check above passed by code trace; the implementation matches the validated plan and
the spec with no divergence.

Decision: pass. Basis: all checks traced with line citations, no gap acknowledged anywhere above.
Outcome: proceed to test validation.

PHASE_VALIDATION:pass

## BUILD-phase resumption addendum (September 23, 2026, 14:30 UTC)

The module (`test-resources/hld_url_shortener/module.clj`) is byte-identical to the
version validated above (`cmp` against the pre-mutation copy after every variant run;
`git diff --stat` on the file is empty). No module edit was needed for the Oracle fixes;
all required fixes were in the private tests and README.

Additional checks against the corrected tests:
- Reads write nothing: `read-budget` now carries `:writes 0`; observed `:writes 0` for all
  three reads at 32 and 512 entries, at 2 and 4 tasks (`clojure -M:test-private-harness -i`
  evidence run, 14:24 UTC).
- Observed per-operation RocksDB work (identical at 2 and 4 tasks and at both history sizes):
  `get-click-count` 1 point read; `resolve-alias` 1 point read; `get-create-outcome`
  2 point reads; `record-click!` (write + barrier) 6 point reads, 2 written entries;
  duplicate `record-click!` 3 point reads, 0 written entries. No iterator seeks or iterator
  reads anywhere. Growth from 32 to 512 entries is zero on every metric.
- Distribution (400 owners, create+click per owner through the same wrapper, alternating
  wrappers): written entries per task [632 598] at 2 tasks, [289 310 337 281] at 4 tasks;
  read work per task [410 390] and [190 204 220 186]. Every task within 0.5x..1.5x of mean.

Decision: pass. Basis: module unchanged from the validated version; observed counts match
the code trace above (one seek per top-level read, fixed write work). Outcome: final.
