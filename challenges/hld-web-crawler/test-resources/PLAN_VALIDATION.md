# Plan Validation

<!-- Phase 2 artifact for hld-web-crawler. Fresh correction pass of 2026-09-23 against README.md,
     protocol.clj, IMPLICIT_SPEC.md and the oracle follow-up (ORACLE_PLAN_FOLLOWUP.md). Supersedes the
     prior validation, whose seek-cost arithmetic and reference traces were wrong. -->

## Query topology
None. `get-claim`, `get-url`, `get-host` are one `foreign-select-one` (1 seek); `list-pending` is one
`foreign-select` of `(sorted-set-range-from from {:max-amt limit :inclusive? false}) ALL` (1 seek +
≤limit iterations). Every example N == M (no wasted reads). Both navigators and the `max-amt-or-opts`
argument are documented in `references/paths.md` "Sorted set navigators". PASS.

## PState schemas
- One PState `$$hosts`; every field shares (String host, hash(host)), so there is no group of two
  or more PStates to justify. PASS.
- No `Object`. `:info`, URL entries, lease, claim outcomes: `fixed-keys-schema`; `:fence`,
  `:lease-expires-at`, `:url`, `:reason` are nullable fields on shared shapes, not shape variation.
  PASS.
- `:rules` ≤100 enforced by the README grammar ("Vector of 0..100") → inline vector. `:urls`,
  `:pending`, `:claims` unbounded per host → subindexed. PASS.
- Subindex option `{:subindex-options {:track-size? false}}` is the documented form in
  `references/pstate-schema.md` "Size Tracking" and `references/pstate-schema-clojure-api.md`; no
  operation calls `(view count)` on those collections (`:queued` is maintained in `:info`). PASS.

## Partitioning
- All writes `hash(host)`: keyspace 40M hosts → negligible hash variance. Hot key: one host may
  hold 1M pending URLs (≈130 MB `:urls` + 100 MB `:pending` on one task) and its claims serialize
  on its single lease/clock regardless of placement; the plan states this with numbers and explains
  why placement state cannot help (every host operation must serialize on the host). No `|all`.
  PASS.
- Tables for N = 1/16/128 present; categories queued-or-leased / retired / unseen-or-invalid with
  proportions 0.6 + 0.3 + 0.1 = 1.0; seeks/op counted as totals across tasks (a single-partition
  point read is 1 seek at every N). Recomputed weighted seeks: 0.6·1 + 0.3·1 + 0.1·1 = 1.0 at every N;
  weighted iterator reads 0. Flat in N. Justifications cite only the README workload (40M hosts,
  1M per host, 50k/s). PASS.
- Alternative placement (global URL-hashed dedup) constructed and costed: 2 hops per URL at
  50,000 URLs/s versus 1, no read gain. PASS.

## Topologies
- Microbatch only. No write requires single-digit-millisecond visibility: the protocol says
  effects "become visible to the read methods after `wait-for-processing!`". PASS.
- No stream topology; no concern claims to need stream semantics. PASS.
- The topology choice is justified on spec requirements (non-idempotent fence/clock/counter writes,
  no visibility deadline), not on synchronization convenience. PASS.

## Production readiness
- Concurrent clients: all writes for a host land in one depot partition and process serially on
  one task; IMPLICIT_SPEC's three acceptable claim/complete interleavings are exactly the
  depot-order outcomes (traced under `complete!` below). PASS.
- Client restart: the wrapper holds only pure canonicalization and the harness sync counter
  (harness-only, not business state). PASS.
- Worker restart mid-batch: microbatch replays the batch exactly once; in addition the claim-id
  guard, the `urls[u]` presence check, and the lease-fence match make re-execution a no-op. PASS.
- Scale: 1B URLs in subindexed `:urls`/`:pending`; `:queued` maintained, never counted. PASS.
- No stream topology → no non-idempotent stream writes to resolve. PASS.

## Internal depot usage / Cross-topology correctness / Stream topology correctness
No internal depots, one topology, no stream topology, no `depot-partition-append!`. PASS.

## In-memory state efficiency
No TaskGlobals. The only per-event memory is one ≤64-entry chunk of URL strings in the claim skip
path, discarded when the event ends. PASS.

## Minimality — adversarial simplification

Sketch: one depot hashed by host; one microbatch topology; one host-keyed PState holding a URL→status
map, a sorted set of pending URLs, a claim-id→outcome map, and a scalar record. The plan equals the
sketch. Mechanism blocks:

### `:pending` sorted set (duplicate of pending keys, ~100 B/URL)
- **Delete it**: head selection must skip retired entries inside `:urls` — unbounded when 1,000
  consecutive heads are `:blocked` or a host has many `:done` URLs below its smallest pending one.
  Violates "It must not scan the host's queue" and "`list-pending` does read work bounded by
  `limit`, independent of queue size". FAIL to delete → keep.
- **Merge**: merging into `:urls` recreates the scan. Keep.

### Lease fence/expiry copied into the leased URL's `:urls` entry (24 B, one URL per host)
- **Delete it**: `get-url` needs `:info` as a second seek to fill `:fence`/`:lease-expires-at`
  ("non-nil iff `:leased`"). One seek versus two on the dominant read. Keep.

### `:queued` maintained counter
- **Delete it**: `get-host :queued` "is not computed by scanning" — counting is prohibited. Keep.

### `:claims` map
- **Delete it**: rule 1 "(host, claim-id) already recorded: no effect" and `get-claim` "Immutable
  once recorded" both need durable per-claim history. Keep.

### Wrapper-side canonicalization and per-host grouping
- **Delete it** (canonicalize in the module): allowed by the README either way; module-side parsing
  would require either a URL-hashed hop or the wrapper to still derive the host for partitioning.
  Wrapper-side is the smaller mechanism. Keep.

### Bounded 64-entry chunk read in the claim skip path
- **Delete it** (per-entry head reads): same O(S) contract, 1,001 seeks instead of 16 for the
  1,000-blocked case (≈500 ms versus ≈13 ms traversal at skill constants). The spec's bounded-work
  contract is met either way, but the skill's I/O rule ("always choose fewer I/O operations")
  makes the chunk the optimal design, so it is not over-engineering. Keep; chunk size is a design
  parameter, not a contract.

### Harness sync counter
- **Delete it**: `wait-for-processing!` is required by the README `Synchronizable` contract.
  Harness-only; holds no business data. Keep.

PASS.

## Throughput — adversarial

- `discover!`: 1 point seek per URL (dedup) + 3 read-free writes (`keypath`+`termval`,
  `NONE-ELEM`, in-memory counter) + 1 `:info` read/write per host-record. Lower-cost sketch:
  none — the dedup check is a mandatory point read, and with size tracking disabled the two
  subindexed writes carry no extra count read (`pstate-schema.md`: tracking "adds an extra disk
  read on every write"). PASS.
- `claim!`: 2 fixed seeks + ≤⌈(S+1)/64⌉ range seeks. A design that inlines `:claims` into `:info` to
  save one seek would make `:info` unbounded (claims per host are unbounded) — violates fixed read
  work for `get-host`. A design with B = 1 costs 64× the seeks at large S. PASS.
- `complete!`: 1 seek + ≤4 read-free writes. Minimal. PASS.
- Reads: 1 seek each; `list-pending` 1 seek + ≤limit. Minimal. PASS.

## Spec coverage — trace every operation and constraint

Reference scenario used below: host `h` = `example.com`, pending {`/a`, `/b`, `/c`} (full canonical
URLs abbreviated), policy delay 5, rules `[{:path-prefix "/a" :allow? false}]`.

### discover! — canonicalization, exact dedup, per-host order, counter
- **Source**: "A canonical URL is seen at most once, ever"; "Duplicates within one call count
  once"; "Element order is preserved only within each host"; "`get-host :queued` increases by
  exactly the number of newly queued URLs of that host".
- **Trace**: batch [`HTTPS://Example.COM/a#x`, `https://example.com/a`, `http://x/`,
  `https://example.com`, `https://Other.org/z`] → wrapper: `https://example.com/a` once, invalid
  dropped, `https://example.com/`, `https://other.org/z`; appends `Discover{example.com [/a /]}`
  and `Discover{other.org [/z]}`. Module on `hash(example.com)`: `:info` nil → default; `urls[/a]`
  absent → `{:queued}`, pending ∪= `/a`, queued 1; `urls[/]` absent → queued 2; write `:info`
  {queued 2, clock 0, fence 0, …}. Re-discover `/a` after it is `:done` → present → skipped,
  queued unchanged. Batch of 100 identical URLs → wrapper dedups to one element → queued +1.
- **Fault tolerance**: worker restart → microbatch replays the batch; `urls[u]` presence check
  makes replayed URLs skips; `:queued` is recomputed from the replayed event on the pre-batch
  PState snapshot, so no double count. Single-partition write → no partial multi-partition state.
- **Races**: two clients discovering the same URL: both records land in the host's depot
  partition; the second sees the first's `urls[u]` → skip. No partitioner hops → no reordering.
- **Flaws found**: none found, with reasoning: dedup and counter live in one atomic single-task
  event keyed by the same host.
- **Verdict**: PASS.

### set-host-policy! — replace, non-retroactive
- **Source**: "never re-evaluates leased, done, failed, or blocked URLs and never changes the
  lease, clock, fence counter, or last-claim-at".
- **Trace**: with lease {`/b` 1 40}, `set-host-policy! h 5 [disallow "/b"]` writes only `:delay`
  and `:rules` via `multi-path`; `urls[/b]` still `:leased`; a later `complete! h 1 :fetched 20`
  still applies (`:done`, never `:blocked`). Rules `[]` delay 5 → only politeness changes. Policy
  on a URL-less host → `:info` created with defaults plus the policy; `get-host` shows it with
  `:queued 0`.
- **Fault tolerance**: idempotent overwrite; replay writes the same values.
- **Races**: a policy and a claim from different clients serialize in depot order; the claim
  evaluates whichever rules are stored when it runs ("affects only claims processed afterwards").
- **Flaws found**: none found.
- **Verdict**: PASS.

### claim! rules 1–7 — replay guard, clock, busy, stale requeue, politeness, robots skip, grant
- **Source**: protocol rules 1–7 verbatim; README "It must not scan the host's queue"; "each
  URL is retired as `:blocked` at most once".
- **Trace (corrected)**:
  - c1 now 10: `claims[c1]` absent; T = max(10, 0) = 10, clock 10; no lease; no last-claim-at;
    chunk = {`/a`,`/b`,`/c`} (one range seek, 3 entries). `/a` disallowed → `urls[/a] := {:blocked}`,
    pending − `/a`, queued 3→2. `/b` allowed → stop; `/c` not evaluated. Grant: fence 0→1,
    `urls[/b] := {:leased 1 40}`, lease {`/b` 1 40}, last-claim-at 10, queued 2→1; record
    `{:granted /b 1 40}`.
  - c2 now 12: T = 12, clock 12; lease live (12 < 40) → record `:busy`; nothing else changes.
  - c3 now 40: T = 40 ≥ 40 → stale: `urls[/b] := {:queued}`, queued 1→2, lease nil. last-claim-at
    10, 40 ≥ 10 + 5 → ready. Chunk = {`/b`,`/c`}; `/b` allowed → fence 1→2, lease {`/b` 2 70},
    last-claim-at 40, queued 2→1; record `{:granted /b 2 70}`.
  - c1 retried with now 99 → `claims[c1]` recorded → no-op: fence stays 2, clock stays 40, lease
    untouched, `get-claim h c1` still `{:granted /b 1 40}`.
  - Boundaries: T = last-claim-at + delay − 1 → `:not-ready`; T = last-claim-at + delay → proceeds.
    T = expires-at − 1 → `:busy`; T = expires-at → requeue.
  - Stale requeue then not-ready (delay 100, claim at 40): `/b` requeued (queued +1, lease nil),
    then `:not-ready`; `get-url /b` → `:queued`, fence nil.
  - Unknown host: `:info` defaulted; chunk empty subset → `:empty`; `last-claim-at` nil; clock T
    persisted; `get-host` shows defaults plus the clock (clock is not a returned field).
  - 1,000 consecutive blocked heads then one allowed: 16 chunk seeks, ≤1,024 entries, 1,000
    `:blocked` retirements, queued −1,001, one grant; entries after the granted URL — including
    any disallowed ones — remain `:queued`. A later claim never re-reads the retired 1,000.
  - All blocked, none allowed (rules disallow `/`): chunks consumed until queued reaches 0 →
    `:empty`; `last-claim-at` unchanged; the next claim finds an empty subset immediately.
- **Fault tolerance**: replayed batch re-runs c1 against the pre-batch snapshot → identical
  result; `:claims` guard covers cross-batch retries. Single task → no partial state.
- **Races**: c-from-client-A and c-from-client-B for `h` in one batch: sequential on one task,
  second sees the first's lease → `:busy`. No hops.
- **Scan check**: every pending read is a bounded range (≤64 entries); no `ALL`, no `MAP-VALS`,
  no `(view count)` over `:pending` or `:urls`.
- **Flaws found**: none found, with reasoning: each step of rules 1–7 maps to one cited action in
  the PLAN `claim!` row, and the corrected trace values (queued 1 after grant, 2 after requeue,
  last-claim-at 40 after regrant) are consistent across both artifacts.
- **Verdict**: PASS.

### Cooperative multitasking versus same-host ordering (skip loop)
- **Source**: SKILL.md "topology code must use cooperative multitasking"; dataflow.md "Do NOT
  yield on a path where correctness depends on same-key events processing in order"; IMPLICIT_SPEC
  "exactly one can be `:granted` while a lease is live".
- **Trace**: c1 (1,000 blocked heads) and c2 for `h` in one batch, c2 behind c1. If c1 suspended
  mid-skip, c2 would run before c1's lease write, find `/b` allowed, and grant fence 1; c1 would
  then grant fence 2 — two live leases and `get-claim c2 = :granted` where the serial history says
  `:busy`. A provisional lease written before the yield makes c2 `:busy` even when c1 ends `:empty`
  — also not the serial outcome. The plan therefore does not yield and instead bounds synchronous
  work with 64-entry chunks: worst case in this spec (S = 1,000) is 16 seeks + ≤1,024 entries ≈ 13
  ms traversal, plus 1,001 robots evaluations and 2,003 logical transforms that are not priced.
- **Honesty check**: the plan makes no total-latency claim for this path and states the 50 ms
  aspiration is not asserted for it. The categorical claim "no Rama API yields while preserving
  same-key order" from the prior validation is removed; the plan now states only that this handler
  has no order-preserving suspension protocol.
- **Flaws found**: none found for correctness; residual cost uncertainty is recorded under
  "Unresolved concerns".
- **Verdict**: PASS.

### complete! — fence match, boundary, rejection touches nothing
- **Source**: "effect iff … fence equals the host's current lease fence AND `max(now, host clock)
  < lease expires-at`"; "A rejected completion … changes nothing, including the clock".
- **Trace (corrected)**: state after c3: lease {`/b` 2 70}, clock 40, last-claim-at 40, fence
  counter 2, queued 1.
  - `complete! h 1 :fetched 50` → fence mismatch → no `local-transform>`; clock 40.
  - `complete! h 2 :fetched 70` → C = 70 ≥ 70 → nothing; `get-url /b` still `:leased` fence 2 expiry
    70; `get-host :lease` still present.
  - `complete! h 2 :fetched 69` → C = 69 < 70 → `urls[/b] := {:done}`, pending − `/b`, lease nil,
    clock 69; last-claim-at stays 40; fence counter stays 2; queued stays 1 (`/c`).
  - Second identical completion → no lease → nothing.
  - `complete!` with now 5 (< clock 69) after a new lease at 75 expiring 105: C = max(5, 75) = 75 <
    105 → applies; clock stays 75 (no decrease).
  - Completion for a host with no lease, fence 0 → nothing.
- **Fault tolerance**: replay of an applied completion finds no lease → no-op; single task.
- **Races** (IMPLICIT_SPEC's three interleavings): complete first while live → retired, then the
  claim proceeds to `/c`; claim first with stale lease → requeue/regrant fence 3, old-fence completion
  ignored; claim first with live lease → `:busy`, then completion applies. All are depot-order
  outcomes on one task.
- **Flaws found**: none found.
- **Verdict**: PASS.

### Host clock
- **Source**: "A new claim at supplied `now` uses effective tick T = max(now, clock) and sets clock
  = T. A successful complete! sets clock = max(now, clock). A rejected complete! changes nothing".
- **Trace**: clock 0 → claim now 10 → 10; claim now 3 → T = 10, clock 10 (no decrease); completion
  now 30 accepted → 30; completion now 99 rejected (C ≥ expiry) → 30. Denied claims (`:busy`,
  `:not-ready`, `:empty`) still persist clock = T (rule 2 precedes rules 3–6).
- **Flaws found**: none found.
- **Verdict**: PASS.

### get-claim / get-url / get-host / list-pending — fixed work and shapes
- **Source**: "Fixed read work" (×3); "`:queued` is not computed by scanning"; "Read work bounded
  by limit"; `get-url` "`:fence`/`:lease-expires-at` non-nil iff `:leased`".
- **Trace**: `get-url "HTTPS://EXAMPLE.com/b"` → canonical `https://example.com/b`, host
  `example.com`, 1 seek → `{:url … :host example.com :status :leased :fence 2 :lease-expires-at 70}`
  while leased; after the accepted completion → `{… :status :done :fence nil :lease-expires-at nil}`.
  `get-url "http://x/"` → nil without a read. `get-host` unseen → seven-key default map; after the
  trace → `{:host example.com :delay 5 :rules [{:path-prefix "/a" :allow? false}] :queued 1 :fence 2
  :last-claim-at 40 :lease nil}`. `list-pending h nil 100` while `/b` leased → [`/b`, `/c`]
  (leased included, `/a` excluded); `list-pending h "/b" 1` → [`/c`]; cursor `/aa` (not pending)
  → [`/b`, `/c`]; host with 1M pending, limit 100 → 1 seek + 100 iterations.
- **Flaws found**: none found.
- **Verdict**: PASS.

### Ordering, barrier, 2/4 tasks, second client
- **Source**: "Sequential write calls from one client to the same logical owner (host) are
  processed in invocation order"; "every write that returned before a `wait-for-processing!`
  barrier is processed before any write issued after that barrier returns".
- **Trace**: one client `discover!`, `claim!`, `complete!` for `h` → three appends to the same depot
  partition in order. Two clients → shared counter incremented per successful append; a
  `discover!` spanning 3 hosts adds 3; `wait-for-processing!` waits for the cumulative count of
  microbatch-processed records, which covers appends from both clients at 2 and 4 tasks.
- **Flaws found**: none found.
- **Verdict**: PASS.

## Architecture-neutral executable checks (for the test phase; not imposed here)

These describe what the private tests may assert about work. They must not assert chunk size,
seek counts, PState names, or topology layout.

- At both 2 and 4 tasks, capture the work of one operation plus its barrier after setup, excluding
  verification reads. Publish broad envelopes: claim work ≤ A + C·S independent of the allowed
  tail length and of retired history; pagination work ≤ A + C·limit independent of cursor depth
  and retired history. Constants A, C are calibrated, not derived from the design.
- Fix S and grow the allowed tail; vary S over {0, 1, 31, 32, 33, 63, 64, 65, 1000}. These values
  probe any bounded-batch implementation's boundaries generically; they do not encode B = 64.
  Check all-blocked → `:empty`, first-allowed → stop, and that disallowed URLs after the first
  allowed remain `:queued`.
- Verify skipped statuses, nil lease fields on non-leased URLs, the exact `:queued` delta, and a
  single grant per claim.
- Replay a recorded claim-id with a huge `now`: original outcome preserved, and a pre-expiry
  completion with the original fence still applies.
- Sequential no-barrier claims from one client: grant then `:busy`; all-blocked → `:empty` then
  `:empty`.
- Stale requeue followed by `:not-ready`; policy replacement ordering relative to claims.
- Pagination with limit 1 / middle / 100 and start / deep / tail / missing cursors while growing
  both retired and pending populations; leased URL included, no retired, duplicated, or omitted
  URLs.
- Known limitation: the work-measuring hook lacks opaque-value sizing, so envelopes bound
  operation counts, not bytes.

## FAIL items found by this pass (localized; fixed directly in PLAN.md)
1. **Seek-cost arithmetic and skip-path shape.** The prior plan read one pending element per
   blocked URL (1,001 seeks for S = 1,000) and left the revision as an open note. Fixed: step 6 now
   reads 64-entry sorted subsets with `sorted-set-range-from-start` / `sorted-set-range-from`
   `{:max-amt 64 :inclusive? false}` (no `ALL`/`FIRST`, so an empty subset still emits), consumes in
   memory, stops at the first allowed URL, and fetches another chunk only when a whole chunk was
   blocked and queued > 0.
2. **Unsupported latency statements.** Replaced the "~10 ms"/"~500 ms"/"~50 ms" narratives with the
   cost model (seeks, entries, robots evaluations, logical transforms) and an explicit statement
   that no total-latency figure is claimed.
3. **Categorical API claim.** "No Rama API yields while preserving same-key order" removed; the
   ordering rationale now rests on dataflow.md's rule and on this handler having no
   order-preserving suspension protocol.
4. **Trace arithmetic.** Queued count after blocking `/a` and leasing `/b` from {`/a`,`/b`,`/c`} is 1,
   so the stale requeue makes 2 (not 3); the regrant at 40 sets `last-claim-at` 40, which the later
   completion preserves (not 10). Corrected in both artifacts.
5. **Schema option.** Subindexed collections whose size is never queried now declare
   `{:subindex-options {:track-size? false}}`, a documented option; the benefit is to be measured in
   BUILD, not assumed.

Items 1–5 of the previous validation (sync counter, `ALL` on range paths, default `:info`, no yield,
output shape) remain applied in PLAN.md.

## Unresolved concerns (carried into BUILD)
- The cost of 2,003 logical transforms for the S = 1,000 case is unpriced; measure whether
  microbatch buffering coalesces them and whether the path stays acceptable under the 5 ms event
  target without yielding.
- The write-side saving from disabling size tracking is documented but not measured here.
- Chunk consumption inside a single event needs a dataflow shape (`loop<-` over chunks with an
  in-memory split at the first allowed URL) that keeps zero-emit branches from skipping the
  `:empty` and final-info writes; verify during BUILD (syntax.md: 0-tuple emits skip downstream code).

## Decision / Basis / Outcome
- Decision: minor-fail. Basis: the architecture (host-keyed single PState, pending sorted set,
  microbatch, one hop per operation) survives adversarial tracing of every protocol rule; the
  defects were a per-entry seek loop with wrong cost arithmetic, unsupported latency claims, a
  categorical API statement, and two trace numbers — all localized to the claim row, cost text,
  and traces. Outcome: PLAN.md corrected for items 1–5 above; proceed to build with the listed
  concerns open.

PHASE_VALIDATION:minor-fail
