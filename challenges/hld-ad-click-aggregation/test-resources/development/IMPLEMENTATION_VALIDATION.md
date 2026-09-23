# Implementation Validation

<!-- Phase 4 artifact for hld-ad-click-aggregation (build phase). Reviewed source:
test-resources/hld_ad_click_aggregation/module.clj. Authority: README.md + protocol
docstrings + PLAN.md + IMPLICIT_SPEC.md. Default verdict was major-fail until each
check was traced. Decision / Basis / Outcome summaries only. -->

Line numbers refer to `test-resources/hld_ad_click_aggregation/module.clj` at the time
of review.

## Redundant conditionals
- `<<subsource` (L198-220): the two cases do different work (watermark read +
  conditional `termval` vs. replay probe + disposition + audit write). Not redundant.
  PASS.
- `<<if (> *watermark *cur)` (L201): the branch has no else; a stale advance must do no
  write. Not collapsible. PASS.
- `<<if (nil? *existing)` (L210-220): the then-branch does four operations, the else
  binds the placeholder row only. Required by the replay rule. PASS.
- `<<if *more?` (L242-243): guards `continue>` only. PASS.

## Consecutive keypath
- Every path uses one multi-arity `keypath`: `(keypath *campaign-id :watermark)`,
  `(keypath *campaign-id :requests *request-id)`, `(keypath *campaign-id :windows)`,
  `(keypath *campaign-id :windows *ws :breakdown)`, and the client paths L280, L282.
  No consecutive keypath forms. PASS.

## Select-compute-transform
- Watermark advance (L200-203): `local-select>` then conditional `termval`. The value
  is needed for the comparison and the write must be conditional; an aggregator
  (`aggs/+max`) would write on every event including stale ones, and the plan mandates
  "stale/equal advance: one read, no write". Same I/O (1 read, ≤1 write). PASS
  (plan-mandated).
- Audit write (L214-217): write-only `termval` after the replay probe the spec requires
  anyway; no read of the record before the write. PASS.
- Counters (L225-228): `+compound` with the `+counters` combiner, one read-modify-write
  per distinct `[campaign window]` totals entry and per distinct
  `[campaign window pair]` breakdown entry per microbatch. PASS.

## Unnecessary nil->val
- `(nil->val 0)` before `(> *watermark *cur)` (L200) and before `disposition` (L211):
  `>`/`>=` cannot take nil. Needed.
- `(nil->val 0)` in the client `get-watermark` (L280): protocol requires `0` for an
  unknown campaign. Needed.
- No `nil->val` on `:requests` probe (nil means "absent", which is the signal), on the
  range navigators (`sorted-map-range-from` over nil yields nothing; `subselect` yields
  `[]`), or on the breakdown read. PASS.

## :allow-yield?
- Paged window read (L237-240): iterates up to `*page` (≤ 1024) subindexed entries →
  `{:allow-yield? true}` present. PASS.
- Breakdown read (L248-249): iterates all `[geo device]` pairs of one window, unbounded
  in principle → `{:allow-yield? true}` present. PASS.
- ETL reads (L200, L209, L211): single values; no yield option (correct — yielding in the
  ETL would also break the per-campaign apply order the spec requires). PASS.

## Non-subindexed collections without size limits
- `:requests`, `:windows`, `:breakdown` are all `map-schema` with subindex options
  (L186-193). The campaign value (3 fixed keys), the audit record (10 fixed keys), the
  counters (5 fixed keys), and the window value (2 fixed keys) are fixed by schema. No
  unbounded non-subindexed collection exists. PASS.

## Stream topology idempotency
- No stream topology. All writes happen in the microbatch topology `click-accounting`,
  whose attempts are exactly-once per microbatch. Counter combines, the audit `termval`,
  and the watermark `termval` therefore apply once per depot record. PASS.

## Partial failure in stream topologies
- No stream topology; the ETL has no partitioner after the source, so each record's
  writes are one atomic segment on one task in one microbatch attempt. A reader never
  sees the audit record without its counter update or totals without the breakdown
  entry. PASS.

## Single depot append per client operation
- `advance-watermark!` → one `foreign-append!` via `append!` (L269-275);
  `record-click!` → one (L276-278). Reads append nothing. PASS.

## Application-state caches survive restart
- No TaskGlobals, no caches. The only client-side memory is `append-counts`, a
  synchronization counter that no read consults (README: "transient synchronization
  counters are allowed"). Runtime evidence: functional test "durable state survives a
  simulated worker restart" reads watermark, audit records, and windows after
  `rtest/update-module!`, then writes more and waits on the same counter. PASS.

## No reimplementation of built-in operations
- `window-start`, `single-window-end`, `disposition`, `audit-record`, `count-row`,
  `build-window`, `sort-windows`, `slots-left`, `first-page`, `page-rows`: domain
  arithmetic and row shaping; no Rama built-in provides these. Iteration uses
  `ops/explode` (L244) and `loop<-` (L236), not hand-rolled recursion. `+counters`
  (L89-92) is a `combiner` over the built-in `merge-with +`; five separate `aggs/+sum`
  leaves would navigate the same entry five times (see Plan conformance). PASS.

## Plan conformance
Compared field by field with PLAN.md:
- Records `AdvanceWatermark`, `RecordClick`, `GeoDevice`; depot `*campaign-events`
  `(hash-by :campaign-id)` — match (L21-23, L179).
- `$$campaigns` schema: identical to the plan's schema (L182-193), including
  `track-size? false` on all three subindexed maps.
- ETL: `<<subsource` dispatch, replay probe before the watermark read, pure disposition
  fn, write-only audit `termval`, every case binding `*row`, single tail `filter>` +
  `+compound` — matches the corrected plan step 5 (L195-228).
- Client: `foreign-select-one` for watermark and audit record, one query topology for
  both window reads, `:append-ack` appends, per-cluster counter registry created per
  `create-module` result — match (L258-302).

Three divergences, each traced and justified on the grounds the phase rubric allows:

1. **Counter aggregation leaf** (L89-92, L225-228). Plan: five `aggs/+sum` leaves per
   entry. Implementation: one custom combiner `+counters` whose delta is the full
   five-key counters map, so totals and the breakdown entry are each one
   read-modify-write per click. Basis: five leaves under the same fixed-keys entry are
   five navigations into a subindexed entry on the dominant write path; the combiner
   keeps the plan's semantics (auto-initialised zeros, all five keys always present —
   `ZERO-COUNTERS` init) with one navigation. Same schema, same durable state.
   Measured at 2 tasks in standalone JVMs (see "Runtime traces"): one counted click
   costs 7-8 `:rocks-read` events end-to-end with the combiner. The plan's literal
   shape — ten `aggs/+sum` leaves (five under `:totals`, five under the breakdown
   entry) fed by five delta vars — was built as `AltFiveLeafModule` and deployed on the
   same IPC settings: the worker crashed on the first microbatch with
   `java.lang.NoSuchMethodError: rpl.rama.platform.bytecode.SemiFunction.invokeBasic(17
   × Object)` (Rama's generated continuation exceeded its supported arity), the
   watchdog shut the module down, and no click was ever processed. The combiner leaf
   is therefore required for correctness on Rama 1.9.0, not only cheaper.
2. **Range read shape** (L106-154, L236-245). Plan: one `sorted-map-range *start *end`
   seek. Implementation: `loop<-` over `sorted-map-range-from *from {:max-amt *page}`
   pages (first page = min(16, slots in range), then doubling up to 1024, always capped
   by the slots left below `end`). Basis: measured on this Rama build (1.9.0) that
   `sorted-map-range` on a subindexed map seeks to `start` but iterates to the END of the
   map before filtering: a 3-window range `[6000, 6180)` cost 104 `:rocks-iterator-read`
   events with 100 populated windows above 12000, and `[3000, 3120)` cost 154 with 153
   windows in the map; String keys behave the same (`(sorted-map-range "a" "b")` over
   `:requests` cost 154). `sorted-map-range-from` with `:max-amt` and
   `sorted-map-range-to` stop exactly (4 and 100 reads respectively). The plan's shape
   therefore violates the README bound "work proportional to the windows … inside the
   requested range, not to windows outside it"; the paged shape restores it: cost is
   (pages) seeks + (windows in range + at most the final page size) iterator reads, and
   `get-window` is 1 seek + 1 read. `:totals` is still read inline during the page
   (`multi-path FIRST [LAST :totals]`), so no per-window totals seek is added. The plan's
   listed fallback (`MAP-KEYS` + point reads) was not needed.
3. **`get-window` end bound** (L33-41, L283-285). Plan: `(c, w, w + 60)`. Implementation:
   `single-window-end` clamps to `Long/MAX_VALUE` when `w > Long/MAX_VALUE - 60`.
   Basis: correctness — `(+ w 60)` throws `long overflow` at the highest 60-aligned start
   `Long/MAX_VALUE - 7`, which the README's numeric domain admits as a window bound
   (observed at the REPL before the fix). The clamp is exact: no timestamp exceeds
   `Long/MAX_VALUE - 180`, so no window can start in `(w, Long/MAX_VALUE)`.

PASS (every divergence is a correctness or measured-performance fix that preserves the
plan's design; schema, partitioning, topology type, ordering and synchronization design
are unchanged).

## Spec traces beyond the plan
- Numeric domain: `disposition` computes `ws + 180` with `ws ≤ Long/MAX_VALUE - 187`
  (timestamps ≤ `Long/MAX_VALUE - 180`, `Long/MAX_VALUE mod 60 = 7`), so no overflow;
  watermark may be `Long/MAX_VALUE` and compares correctly (functional test "signed
  64-bit limits"). `slots-left` computes `end - from` with `from ≥ 0`, never overflows;
  `next-from = last-ws + 1` with `last-ws < end ≤ Long/MAX_VALUE - 7`.
- Loop termination (L135-154): another page is requested only when the page was full and
  its last key is below `end`; `next-from` strictly increases, so the loop ends after at
  most ⌈k / 16⌉ + log₂ pages for k windows in range, and after exactly one page when the
  range has ≤ 16 windows. Verified with `?<-` that statements after `continue>` still run
  (a loop body that continues AND emits the page's rows).
- Unknown campaign / empty range: page read over nil → `subselect` → `[]` → zero rows,
  `more? false` → loop emits nothing → `+vec-agg` → `[]` → `get-window` returns `nil`
  (README step 11; functional test "unknown campaign reads").
- Replay inside one microbatch: the audit `termval` is a pre-agg `local-transform>`
  executed depth-first per record, so a second arrival in the same batch sees the first
  (functional test "replays with conflicting bodies", request `z`).
- Per-campaign order across event kinds within one batch: the watermark `termval` (L202)
  is likewise pre-agg and depth-first, so a following `RecordClick` in the same batch
  reads the advanced value (functional test "same-campaign writes take effect in client
  order").

## Runtime traces (REPL, 2 tasks, event hook counting `:rocks-read` /
## `:rocks-iterator` / `:rocks-iterator-read`)
- Draft module before the range fix: `get-window` 12 → 112 ops and `get-windows` (3
  windows) 26 → 127 ops after adding 150 out-of-range windows (efficiency suite failure
  that motivated divergence 2).
- Counted click end-to-end: 8 `:rocks-read`, 0 iterator events (standalone JVM, three
  clicks: 8, 8, 7). Stale advance: 1 `:rocks-read`. Effective advance: 3 `:rocks-read`.
  `get-request`: 2 `:rocks-read`. `get-watermark`: 1 `:rocks-read`.
- Final module, efficiency suite instrumented at 4 tasks (reads + iterator seeks +
  iterator steps, small state → grown state with 150 extra windows, 100 late audit
  records and 12 other campaigns): `get-request` 2 → 2, `get-watermark` 1 → 1,
  `get-window` 15 → 15, `get-windows` over 3 windows 29 → 31, counted click 8 → 8,
  late click 5 → 5, replay 2 → 2, stale advance 1 → 1, advance 3 → 3, advance to 10^12
  closing 153 windows 3.
- `sorted-map-range` vs `sorted-map-range-from` on the deployed `$$campaigns` (153
  windows in the map): `(sorted-map-range 6000 6180)` 104 iterator reads,
  `(sorted-map-range 3000 3120)` 154, `(sorted-map-range 6180 12000)` 101 (empty
  result), `(sorted-map-range-from 6000 {:max-amt 3})` 4, `(sorted-map-range-to 3120)`
  4; String keys on `:requests`: `(sorted-map-range "a" "b")` 154 for 100 matching keys,
  `(sorted-map-range-to "b")` 100.
- Namespace loads cleanly (`load-file` at the REPL, `requiring-resolve` in the suite);
  `clj-kondo --lint` on the module reports only "unresolved symbol" noise for Rama
  macros / dataflow vars (no Rama hooks in `.clj-kondo/config.edn`) and two
  `:refer :all` style warnings mandated by the repo's CLAUDE.md style rule.

## Verdict

`pass` — every check traced above holds; the three divergences from the plan text are a
correctness fix (overflow), a measured I/O fix (range scan), and a same-I/O-or-better
aggregation leaf, none of which changes the validated design.

PHASE_VALIDATION:pass
