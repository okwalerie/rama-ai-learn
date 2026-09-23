# Plan Validation

<!-- Phase 2 artifact for hld-ad-click-aggregation, subsystem `click-accounting`
(first and only subsystem in DECOMPOSITION.json). Inputs: README.md,
protocol.clj, IMPLICIT_SPEC.md, PLAN.md, lib/harness Synchronizable
docstring, skills/rama references. Spec text is authoritative; no timing
requirements beyond the README's are assumed. -->

## Query topology: `windows-in-range`
- Input examples present: yes (three, PLAN "Query Topologies").
- Example 1 (windows 60 and 4980, range `0 6000`): N=3, M=3. N == M? yes.
- Example 2 (range `0 120`): N=2, M=2. N == M? yes.
- Example 3 (unknown campaign): N=1, M=1 (the range seek decides `[]`). N == M? yes.
- M values: 3, 2, 1. All same? no.
- Marked variable with dynamic approach? yes — breakdown reads hang off each emitted window row, so exactly k breakdown seeks for k populated windows; `+vec-agg` collects. PASS.
- Fallback noted in plan (`MAP-KEYS` + per-window `:totals` point read, +k seeks) still meets the README bound "work proportional to the windows … inside the requested range". PASS.

## PState schemas
- Groups by (key type, partitioner): all state is `(String campaign-id, hash-by campaign-id)` and the plan already holds it in ONE PState `$$campaigns` with a fixed-keys value (`:watermark`, `:requests`, `:windows`). No split to justify. PASS.
- Any `Object` type? no. Leaf types: `Long`, `String`, `Boolean`, `clojure.lang.Keyword`, `GeoDevice` record key. PASS.
- Uniform record-like values use fixed-keys-schema? yes — audit record (10 fixed keys, always all written via one `termval`), counters (5 `Long`s), window value (`:totals`, `:breakdown`). PASS.
- Heterogeneous instances at one position? none — every audit record has the same ten fields (disposition is a Keyword field, not a shape change). PASS.
- Subindexing of collections that can exceed 100: `:requests` (unbounded request-ids), `:windows` (unbounded windows), `:breakdown` (IMPLICIT_SPEC: "unbounded in principle", no enforced cap) — all `map-schema` with subindex options. Non-subindexed: campaign fixed-keys value, counters (5), audit record (10) — fixed by schema. PASS.

## Partitioning
- Depot `*campaign-events` `(hash-by :campaign-id)`; PState `$$campaigns` top-level key = campaign-id; query leads with `(|hash *c)`. Keyspace = campaigns (large). Hot-campaign skew: the spec itself serializes all writes of one campaign (README "Writes addressed to the same campaign must take effect in the order the client invoked them"), so per-key serialization is a requirement, not a design defect; the plan states this with an IMPLICIT_SPEC citation ("Write skew"). PASS.
- No `|all`, no `|global`. PASS.
- Table filled for N = 1, 16, 128: yes. Rows: single-window present (0.5), 24-window range (0.3), sparse 2-window range (0.1), absent/unknown/empty (0.1). Sum = 1.0 for each N. Common input represented. PASS.
- Weighted sums present; recomputed: seeks 0.5·2 + 0.3·25 + 0.1·3 + 0.1·1 = 9.9; iterator reads 25.5 + 367.2 + 10.2 + 0 = 402.9. Matches plan. PASS.
- Seeks counted as totals across tasks: every operation dispatches to exactly one task (leading `|hash`, no `|all`), so 1 task × seeks is the total. Flat 9.9 at N = 1, 16, 128 — does not grow. PASS.
- Justifications drawn only from the spec (campaign isolation, per-campaign ordering, efficiency contract); no assumptions about external components. PASS.
- Placement alternative considered: campaign split across tasks by `[c rid]` / `[c ws]`, costed (3 hops + 3 tasks' seeks per click, k scattered seeks per range read, extra sequencing mechanism) and rejected on that arithmetic. Stored placement state cannot beat a zero-hop single-task operation. PASS.

## Topologies
- Microbatch unless justified? yes — `click-accounting` is microbatch. PASS.
- Writes needing single-digit-ms visibility: none. README: "All `!` methods are asynchronous writes. Tests call `(harness/wait-for-processing! client)` after a group of writes and before any read." No stream topology. PASS.
- Stream topology concerns: none. PASS.
- Any choice made on test-synchronization grounds? no — microbatch chosen for exactly-once counters and per-campaign ordering; the `Synchronizable` design was chosen after, not as a driver. PASS.

## Production readiness
- Multiple concurrent clients: all writes of a campaign land in one depot partition and are applied by one task in append order (microbatch.md: "each task emits its local depot partition's records in the order they were appended"); reads go to durable `$$campaigns`. Yes. PASS.
- Client restart: read results depend only on `$$campaigns`; the client-side counter affects only `wait-for-processing!` for writes issued before the restart (README: "transient synchronization counters are allowed"). Yes. PASS.
- Worker restart mid-topology: microbatch retries the whole batch with exactly-once PState semantics; audit + totals + breakdown of one click commit in one attempt. Yes. PASS.
- Large scale: three unbounded collections all subindexed; per-click and per-read work bounded as traced below. Yes. PASS.
- Stream non-idempotent writes: no stream topology. PASS.
- Stream multi-partition partial failure: n/a. PASS.

## Internal depot usage
- None. PASS.

## Cross-topology correctness
- Single topology; no internal depot flow. PASS.

## Stream topology correctness
- No `depot-partition-append!`, no stream topology. PASS.

## In-memory state efficiency
- No TaskGlobals. Client-side registry is one `Long` per IPC instance. Nothing a read depends on lives in memory. PASS.

## Minimality — adversarial simplification

Simplest sketch: one hash-by-campaign depot carrying both event kinds; one microbatch topology that on each record checks `:requests`, reads the watermark, writes the audit record, and bumps counters; one PState keyed by campaign holding watermark + subindexed requests + subindexed sorted windows with subindexed breakdown; one query topology for range reads; client counter for `wait-for-processing!`. The plan is exactly this sketch — no mechanism to diff.

### `*campaign-events` depot (single, both event kinds)
- Delete it: clients cannot write. Merge into two depots: loses "an `advance-watermark!` followed by a `record-click!` on the same campaign is judged against the advanced watermark" (README) since separate depot partitions have no relative order. Keep. PASS.

### `click-accounting` microbatch topology
- Delete/merge: it is the only writer. Splitting into two topologies (one per event kind) loses per-campaign ordering between kinds. Stream instead of microbatch: counter increments are non-idempotent; IMPLICIT_SPEC "Exactly-once effect per write under retries" would need a dedup mechanism that microbatch provides by construction. Keep. PASS.

### `$$campaigns` PState
- Delete `:watermark`: `get-watermark` and disposition rule 1 impossible. Delete `:requests`: replay rule and `get-request` impossible. Delete `:windows`/`:breakdown`: `get-window`/`get-windows` impossible. Merge `:totals` into a read-time sum over the breakdown: constructed cost = b iterations per window on every `get-window`/`get-windows` versus 5 longs written per click; the plan's rejection includes this arithmetic. Keep. PASS.

### `windows-in-range` query topology
- Delete it: `get-window` would need two client roundtrips (totals + breakdown) and `get-windows` k+1 roundtrips, violating IMPLICIT_SPEC "one request must not require client↔cluster roundtrips proportional to the number of windows returned". Bypass with a raw `foreign-select` over `sorted-map-range` + `subselect`: cannot descend into each window's subindexed `:breakdown` handle in one foreign path (pstate-schema.md: subindexed structures "cannot be transferred over network boundaries"). Keep. A separate single-window topology is correctly rejected: same seeks as a one-window range. PASS.

### Per-cluster append counter (client side)
- Delete it: `wait-for-processing!` has nothing to wait on. Make it per-client: violates IMPLICIT_SPEC "A second client that issues its own writes after another client's writes were already processed must genuinely wait for its own writes". Durable-marker alternative constructed in plan (depot record + PState + poll) and rejected on cost. Keep. PASS.

## Throughput — adversarial

- `record-click!` (dominant): 1 seek (`:requests` probe) + 1 seek (campaign value for `:watermark`) + audit write (no read) + totals RMW + breakdown-entry RMW. Lower-cost sketch A: fold `:watermark` into the request-probe seek — impossible, different keys. Sketch B: non-subindexed breakdown (saves one seek on read, none on write) — write becomes proportional to b, violating IMPLICIT_SPEC "the breakdown update must … be constant-cost with respect to … window size". Sketch C: aggregate counters per microbatch via `+compound` so that m clicks into the same window/pair in one microbatch cost one RMW per distinct key instead of m — the plan already uses `+compound`, which gives this coalescing. No cheaper design constructed. PASS.
- `get-window`: 1 range seek (totals inline) + 1 breakdown seek + b iterations. Sketch: single seek with an unsubindexed breakdown — rejected above. PASS.
- `get-windows`: 1 + k seeks + Σb iterations. Sketch C in plan (flattened `[ws GeoDevice]` map, one range covering all breakdowns) would save up to k seeks but relies on undocumented composite-key sort order; plan records the arithmetic and the risk. The gain is bounded by k seeks and the fallback is documented; not a FAIL. PASS.
- `advance-watermark!`: 1 read, ≤1 write. Minimal. PASS.
- `get-watermark`, `get-request`: 1 seek each. Minimal. PASS.

## Spec coverage — trace every operation and constraint

### `advance-watermark!`
- Source: "Monotonic: a value <= the current watermark is a no-op."; IMPLICIT_SPEC: "Constant per call. Must not do work proportional to the number of windows closed …".
- Trace: campaign `cmp-1`, watermark 0. `advance-watermark! 239` → read `[(keypath "cmp-1" :watermark) (nil->val 0)]` = 0, 239 > 0 → `termval 239`. `advance-watermark! 240` → 240 > 239 → write. `advance-watermark! 100` → 100 > 240 false → no write; `get-watermark` → 240 (step 12). Jump `0 → 10^12`: one read, one write; nothing iterates windows (PLAN: "closure stores nothing"). Fresh campaign `w = 0`: `0 > 0` false → no write, campaign absent → `get-watermark` 0, `get-windows` `[]`.
- Fault tolerance: durable field; microbatch retry re-applies `termval` with the same value; no in-memory state. Holds.
- Race: two advances in flight from two clients land in one depot partition; whichever applies first, the larger value ends up stored (`>` check). Holds.
- Flaws: none found, with reasoning: one read + conditional write, constant, campaign-local.
- Verdict: PASS.

### `record-click!` — replay rule
- Source: "A replayed `request-id` … has no effect at all, regardless of its arguments and regardless of whether the window has since closed."; IMPLICIT_SPEC "Idempotence check precedes disposition".
- Trace: step 10 — `r1` exists in `:requests` (written at step 1 with `:watermark 0`). Step 1 of the click path reads `(keypath "cmp-1" :requests "r1")` → non-nil → stop before the watermark read and before any counter update. Window 60 unchanged; audit unchanged. Same rid in `cmp-2` (step 13): different top-level key → nil → fresh click.
- Same rid twice inside one microbatch: pre-agg is depth-first per record on the task; the first record's `local-transform>` of the audit record executes before the second record is emitted from `%mb`, so the second sees it. Holds.
- Fault tolerance: retry of a batch re-reads durable `:requests`; exactly-once PState semantics prevent double audit writes. Holds.
- Race: two clients send the same `[c rid]`; both land in one partition; first applied wins. Holds.
- Verdict: PASS.

### `record-click!` — disposition order and watermark snapshot
- Source: "checks run in this order against the watermark `W` observed when the write is applied: 1 `W >= E + 120` → `:late` … 2 `fraud?` → `:fraud` … 3 `valid?` false → `:invalid` … 4 otherwise → `:billed`".
- Trace: step 7 — `W = 239`, `ts = 61`, `ws = 60`, `239 >= 180`? no → `valid? true, fraud? false` → `:billed`, audit `:watermark 239`. Step 9 — `W = 240`, `ts = 90`, `240 >= 180` → `:late`, audit record with all ten keys including `:window-start 60 :watermark 240` (matches README's listed record). `fraud? true, valid? false` in an open window → `:fraud`. Fresh campaign → `W = 0`, never `:late`.
- Ordering: `advance-watermark! 240` then `record-click! r6` from one client are consecutive records in one depot partition; `%mb` emits them in append order and the watermark `termval` is a pre-agg `local-transform>` executed depth-first before `r6` is emitted. Holds.
- Fault tolerance: audit record and watermark durable; retry recomputes identical disposition from identical durable state. Holds.
- Verdict: PASS.

### `record-click!` — counting into totals and breakdown together
- Source: IMPLICIT_SPEC "The audit record, the totals update, and the breakdown update for one click must be applied together"; README worked example step 5 map.
- Trace: steps 1–3 produce window 60 totals `{3 1 1 1 30}` and three breakdown entries; `+compound` with `+sum` of deltas `(1, d-billed, d-invalid, d-fraud, d-spend)` under both `:totals` and `{(->GeoDevice geo device) …}` yields exactly the step-5 map, all five keys present (auto-initialized zeros). Step 7 adds `(1,1,0,0,25)` to both totals and `["US" "mobile"]` → `{4 2 1 1 55}` and `{2 2 0 0 55}`. `:late` (step 9) never reaches the aggregator → window 60 unchanged, window with only late clicks never created.
- Fault tolerance: all writes of one microbatch attempt commit atomically per task; retry is exactly-once. Holds.
- Race: many clicks from many clients into the same pair are serialized on one task and summed. Holds.
- Flaws found: **structural, not semantic.** PLAN "Topologies" step 5 places `+compound` inside `<<if (not= :late *disp)` inside the `RecordClick` case of `<<subsource`. A microbatch body is a batch block: the first aggregator marks the pre-agg → agg transition and the pre-agg "must combine to a single branch" (batch.md "Constraints"); an aggregator nested inside a conditional branch is not the single tail and is not guaranteed to compile. The `AdvanceWatermark` branch would also flow into the aggregator's continuation with unbound variables. Same PState, same reads, same cost — the fix is a rewrite of step 5 only: each `<<subsource` case binds the same output vars (`*counted?`, `*ws`, `*gd`, deltas), then `(filter> *counted?)`, then one tail `+compound`.
- Verdict: FAIL (minor). Fix applied to `PLAN.md` step 5 below.

### `record-click!` — work bound
- Source: "must do bounded work per click, independent of how many clicks, windows, or campaigns exist."
- Trace: campaign with 10^7 request-ids and 10^5 windows, window with 10^4 breakdown pairs: 1 point seek into `:requests`, 1 seek for the campaign value, `termval` audit write, RMW of `:totals` (point key), RMW of one breakdown entry (point key inside subindexed map). No scan, no size read (`track-size? false`). 4 seeks at any N.
- Verdict: PASS.

### `get-watermark`
- Source: "Returns the campaign's current watermark; 0 for an unknown campaign."
- Trace: unknown campaign → `keypath` nil → `nil->val 0` → 0. After step 8 → 240. Campaign with clicks but no advance → field absent → 0. One seek, one task.
- Verdict: PASS.

### `get-request`
- Source: "must not read anything proportional to the number of other requests, windows, or campaigns."; returns the immutable ten-key record or nil.
- Trace: `(keypath "cmp-1" :requests "r6")` → point read in subindexed map → record from step 9; `"cmp-1" "zzz"` → nil; unknown campaign → nil. Record never rewritten (replay stops at step 1). One seek.
- Verdict: PASS.

### `get-window`
- Source: "Returns nil if no click has been counted … otherwise {:window-start :totals :breakdown …}"; "`get-window` reads one window."
- Trace: `get-window "cmp-1" 60` → query `(cmp-1, 60, 120)`: `sorted-map-range 60 120` emits window 60 with totals; breakdown seek yields 3 entries → converted to `[geo device]` keys → `(first result)` = step-5 map. `get-window "cmp-1" 120` → range `[120,180)` empty → `[]` → nil. Unknown campaign → `sorted-map-range` on nil → 0 emits → nil. Late-only window never exists → nil. 2 seeks + b iterations, one task.
- Concurrency: query executes on the task that owns the campaign between events; a microbatch commits atomically, so totals and breakdown reflect the same set of clicks.
- Verdict: PASS.

### `get-windows`
- Source: "every window with start <= window-start < end … ascending by :window-start"; "work proportional to the windows and returned breakdown entries inside the requested range".
- Trace: step 11 — `0 6000`: one range seek at key 0, iterates 60 then 4980 (only populated keys; empty 60-unit slots are not stored), stops before 6000; two breakdown seeks; `+vec-agg` then sort → `[win60 win4980]`. `0 120`: iterates 60 only, stops at 120 (end exclusive) → `[win60]`. `0 10^12` with two windows: same 3 seeks. `start == end` → empty range → `[]`. Campaign B state never touched (different top-level key, possibly different task).
- Verdict: PASS.

### Campaign isolation / task-count independence
- Source: "Reads of one campaign must not read state belonging to any other campaign."; "The module runs with both 2 and 4 tasks in private validation."
- Trace: every read and write navigates through `(keypath c …)` first; nothing enumerates top-level keys. At 2 or 4 tasks, `hash-by :campaign-id` only changes which task owns a campaign; depot partition i and PState partition i are on the same task, so the topology needs no partitioner. Results are byte-identical across task counts.
- Verdict: PASS.

### Durable state / never delete
- Source: "All authoritative business state must be durable Rama state (depots and PStates)"; IMPLICIT_SPEC "Never delete data."
- Trace: watermark, audit records, windows, breakdown all in `$$campaigns`; no TaskGlobal; no `NONE>`/`+NONE`. Worker restart: topology resumes from persisted microbatch offset; PStates intact.
- Verdict: PASS.

### `wait-for-processing!` (Synchronizable, second client)
- Source: "Multiple clients wrapping the same deployed module must observe the same business state after the writing client synchronizes."; IMPLICIT_SPEC second-client edge case.
- Trace: client A appends 5 records, waits on count 5 (processed). Client B (same IPC, created later) appends 2: shared registry counter becomes 7; B waits for processed-count ≥ 7 — blocks until its own writes are applied. A per-client counter would have waited for 2 and returned immediately (the edge case the plan cites). Client B with no writes: waits for 5, already satisfied, returns. One topology, one depot, so a single wait covers everything.
- Verdict: PASS.

## Self-consistency check
The only non-PASS entry is the aggregator-placement item under "counting into totals and breakdown together". It does not change the schema, depot, partitioning, cost tables, or any traced result; it is a localized rewrite of one processing step. No other entry contains a hedged "gap" or "tradeoff".

## Verdict
PHASE_VALIDATION:minor-fail — fixed directly in `PLAN.md` (Topologies, `RecordClick` step 5 and the "Only one topology" note).
