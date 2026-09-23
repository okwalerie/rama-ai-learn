# Plan Validation

<!-- Phase 2 artifact for hld-search-autocomplete. Adversarial review of PLAN.md against README.md, protocol.clj, IMPLICIT_SPEC.md. -->

## Query topologies
- `suggest-q`: live locale → 2 reads (gen, prefix set range k), both meaningful; no generation →
  1 read. Variable, handled by `<<if`. N == M. PASS.
- `phrase-q`: 3 reads live / 2 without generation, all meaningful. N == M. PASS after fix 3
  (the phrase record must not carry a subindexed handle across the network).
- `get-generation`: 1 seek on `$$locale-auth` routed `hash(locale)` (owner-authoritative). PASS.

## PState schemas
- `$$locale-auth` (locale, hash(locale), owner-only truth), `$$locale-gen` (locale, `|all`, read
  metadata) and `$$index` (locale, hash(pk)): three partitioners/authorities → split justified;
  authority and metadata are distinct typed fields, never written by the same path. Inside `$$index`, `:blocked` and `:gens` share key and partitioner → one PState. PASS.
- No `Object`; records are `fixed-keys-schema`. PASS.
- Subindexed: `:blocked` (≤10,000), `:gens`, `:phrases` (≤110,000/gen), session sets (≤1,000,000),
  `:prefixes` and each candidate set (thousands). PASS.

## Partitioning
- `hash([locale, first-2-chars])`: ~200 × ≤1,332 keys; hottest key a few % of 500,000/s. Alternatives
  `hash(locale)` (200 keys, idle tasks) and `hash([locale, prefix])` (64 hops/event) rejected with
  numbers. PASS.
- `|all` for `$$locale-gen`: 200 longs, written a few times/day. PASS.
- Tables N = 1/16/128, proportions 0.70+0.25+0.05 = 1.0, weighted seeks 1.95, totals (one task per
  suggest, replicated gen read local). Flat. PASS.

## Topologies
Microbatch only ("freshness within minutes" aspiration). Session increments and index moves are
non-idempotent → exactly-once. No stream. PASS.

## Production readiness
- Concurrent clients: per-locale depot partition A; acceptance decided only at A against
  `$$locale-auth`; per-phrase order preserved because every accepted op leaves A on one branch
  through one `(|hash *pk)` and one `(|hash *pk1)` (stream.md "Partition ordering": messages from
  one task to another are processed in send order) — fixes 1, 7, 8. PASS after fixes.
- Client restart: no business state in wrapper. PASS.
- Worker restart: exactly-once replay; `$$locale-auth` is a PState so the prime phase restores it
  and a replayed batch makes identical acceptance decisions; session-set and generation guards. PASS.
- Scale: all unbounded collections subindexed; generation keying makes publish O(entries). PASS.

## Internal depots / cross-topology / stream / TaskGlobals
None. PASS.

## Minimality
Sketch: depot by locale, one microbatch topology, per-(gen, prefix) sorted candidate sets keyed
`K(score, phrase)`, phrase records with session sets, owner generation + replicated copy, block
set. Plan equals sketch. `$$locale-auth`: delete → acceptance would be decided on X against a
broadcast copy (fix 7 scenario). Replica `$$locale-gen`: delete → `suggest`/`get-phrase` need a
hop to `hash(locale)` (2 hops vs 1 at 500,000/s) for a value written a few times/day. Generation keying: delete → publish
must delete previous trend data ("must not depend on the size of the previous generation's trend
data"). Option B/C rejected with counts (64 seeks/event; unbounded refill after block). PASS.

## Throughput
Event = 3 seeks (1 at A: `$$locale-auth`; 2 at X: phrase record, session membership — X no longer
seeks a generation) + ≤128 blind puts/deletes (`NONE-ELEM` insert, `set-elem`+`NONE>` delete: no
read, paths.md) + 2 hops. Suggest = 2 seeks + k iterations. No constructed design does fewer seeks while
keeping suggest independent of candidate count. PASS.

## Spec coverage

### publish-snapshot! generation guard, atomic switch, reset, block persistence
- Source: "generation strictly greater ... replaces the whole corpus and resets all trend counts.
  Equal or lower generations are ignored."; publish "must not depend on the size of the previous
  generation's trend data".
- Trace (plan as written): locale `en`, gen 1 live. Batch contains publish g=2 {["apple" 5]} then
  search(2, s1, "apple") then publish g=2 again. At A the guard reads `$$locale-gen[en]`, updated
  only by the asynchronous `|all` message → second publish also passes; its entry rewrites
  `{:base 5 :sessions 0}` after the event set `sessions 1` and inserted `K(15,"apple")` →
  count lost, stale `K(15)` beside `K(5)` in every prefix set. FAIL → fix 2.
- Trace (fan-out): plan line 33 runs `(b) explode` after `(a) |all`, so every task explodes the
  entries: N× writes and, worse, entries reach X from N upstream tasks — no FIFO relative to A's
  events; an entry from task Z can land after A's event and reset `sessions`. FAIL → fix 1.
- Trace (independent paths, plan before fix 7): A wrote its local `$$locale-gen`, branched `|all`,
  and sent entries/events by `|hash`; X validated events against its own `$$locale-gen` copy. Two
  independent A→X messages (`|all` metadata, `|hash` event) carried the decision; a search stamped
  nowhere could be admitted on X under whichever generation the copy showed. FAIL → fix 7.
- After fixes 7–8: A reads `$$locale-auth[en]` (1 seek), writes `2` synchronously, emits a terminal
  `|all` `max`-write to `$$locale-gen` only, and explodes entries from `<acc>` on A only into
  `PhraseOp :init gen 2`; searches are accepted at A iff `gen = auth` and stamped `2`; all ops leave
  A on one branch through one `(|hash *pk)`. X applies the stamp; it never reads a generation.
  Second publish g=2 → A sees 2 → no-op, no broadcast. Empty entries → auth 2, broadcast 2, no ops.
  Blocked phrase in snapshot → record written, no index entry, `get-phrase :in-corpus? true`. PASS.

### Single-microbatch mixed sequence (publication, search, block, unblock, empty snapshot, novel)
- Source: same-owner invocation order; "Equal or lower generations are ignored"; "Events whose
  generation is not the locale's current generation are ignored without trace"; empty snapshot
  "the generation still advances, and counts still reset"; blocked "never appear in suggest".
- Trace, one client, locale `en`, one microbatch, depot order at A (auth starts nil):
  1. `publish 2 [["apple" 5]]` → auth 2; broadcast `max(·,2)`; op `:init apple 5 g2` → X("ap").
  2. `search 2 s1 apple` → 2 = 2 → `:count g2` → X("ap"): sessions 1, K(5)→K(15) in "ap".."apple",
     Y("a") likewise.
  3. `block apple` → stamp 2 → X("ap"): blocked, remove K(15) from all prefixes, Y remove.
  4. `search 2 s2 apple` → `:count g2` → sessions 2 (blocked ⇒ no index writes).
  5. `unblock apple` → stamp 2 → X("ap"): unblocked; candidate (base 5, sessions 2) ⇒ insert
     K(25) everywhere, Y insert.
  6. `search 3 s1 zzz` → 3 ≠ 2 → dropped at A; no op, no trace.
  7. `publish 3 []` → auth 3; broadcast `max(·,3)`; no ops.
  8. `search 3 s1 zzz` → 3 = 3 → `:count g3` → X("zz"): novel `{0,1}`, insert K(10) in "zz"/"zzz",
     Y("z") insert.
  9. `publish 2 [["x" 1]]` → 2 ≤ 3 → no-op; nothing sent.
  All ops for pk("ap") arrive at X in steps' order (one A→X edge); Y("a") likewise (one X→Y edge).
  The two broadcasts reach each task in send order and the `max` guard makes even a reordering
  harmless; neither touches `$$locale-auth`. Committed result: `get-generation en` → 3;
  `suggest en "ap" 5` → `:gens 3` has no "ap" ⇒ `[]`; `get-phrase en apple` → `{3 false 0 0 0 false}`;
  `get-phrase en zzz` → `{3 false 0 1 10 false}`; `suggest en "z" 1` → `[{zzz 10}]`. Generation-2
  data is unreferenced, untouched. Step 6 left no trace, so step 8 is the first count. PASS.

### record-search! dedup, generation filter, index moves for all prefixes
- Source: "counts a (phrase, session) pair at most once per (locale, generation)"; "Events whose
  generation is not the locale's current generation are ignored without trace"; suggest reflects
  "every prefix of the phrase".
- Trace: gen 2, "apple pie" base 5 blocked? no. s1 → accepted at A (2 = 2), stamped 2 → X:
  sessions 1, old 5 → new 15: remove `K(5)`, insert `K(15)` in 8 local prefixes ("ap".."apple pie")
  and via X→Y in "a". s1 again → member → no-op. s2 with gen 1 → dropped at A (auth 2 ≠ 1), no
  trace; s3 with gen 3 before any publish 3 → dropped at A likewise (a future generation can never
  be admitted early). Novel "apex" s1 → record
  `{0,1}`, no old key, insert `K(10)`. Blocked phrase → sessions still increment, no index writes.
  Score bound 11×10^6 < 10^8 keeps `K` fixed-width. PASS.

### block-phrase! / unblock-phrase!
- Trace: block top-1 "apple" → stamped with auth at A → X: `:blocked ∪= "apple"`, remove
  `K(score,"apple")` from local prefixes and Y; suggest "ap" k=2 returns former #2, #3 (full ordered
  set remains). Unblock → reinsert at current score. Block a non-candidate → set only. Block with
  auth nil → set only. Publish later → entry skipped for index because `:blocked` is read on the
  same X. Block then search for the same phrase in one batch: same A→X edge, applied in order. PASS.

### suggest exactness and bounded work
- Source: "ordered by score descending then phrase ascending"; "must not scan matching phrases".
- Trace: prefix "ap", k=3, sets contain `K(15,"apple pie")`, `K(10,"apex")`, `K(10,"apple")` →
  ascending String order of K: 99999984|apple pie, 99999989|apex, 99999989|apple → decode →
  [{apple pie 15} {apex 10} {apple 10}]. Tie broken lexicographically by the encoded suffix. 1
  seek + 3 iterations regardless of thousands of candidates. Locale without generation → []. PASS.

### get-phrase / get-generation
- Trace: gen 2, blocked novel phrase with 3 sessions → `{2 false 0 3 30 true}`; no generation →
  `{nil false 0 0 0 b}`. PASS after fix 3.

### Query partition alignment
`$$index` top-level key `locale` exists on many tasks (one per pk). `suggest-q`/`phrase-q` must
route with the identical `pk` function used by the topology (`[locale (subs s 0 (min 2 (count s)))]`)
and never `|hash locale`. Stated in fix 4. PASS after fix.

### Ordering, barrier, 2/4 tasks
One depot partition per locale; acceptance and stamping at the owner; one branch, one partitioner
per hop ⇒ A→X→Y FIFO per phrase (stream.md "Partition ordering"); shared cumulative counter; no
task-count dependence (the owner task and the pk tasks are whatever `hash` picks at 2 or 4). PASS
after fixes 1, 2, 5, 7, 8.

## FAIL items (localized; fixed directly in PLAN.md)
1. **Fan-out after `|all`.** Anchor before `|all`; the replicate write is a `<<branch`; explode +
   `|hash pk` continue from the anchor so entries originate only at A.
2. **Guard reads an asynchronously updated copy.** A writes `$$locale-gen[locale]` locally with
   `local-transform>` before branching to `|all` (the broadcast rewrites the same value).
3. **Phrase record carries a subindexed handle.** Split `:session-ids` out of the phrase record
   into a sibling `:sessions (map-schema String (set-schema String {:subindex? true}) {:subindex? true})`;
   `phrase-q` and `record-search!` read the plain `{:base :sessions}` record (1 seek) and test
   membership with `set-elem` (1 seek). Same seek count as before.
4. **Query routing.** State the shared `pk` function and require `(|hash *pk)` in both queries.
5. **Sync counter semantics.** Increment after a successful `foreign-append!`, before return.
6. **Output shape.** `get-phrase` six keys explicit; `suggest` maps exactly `{:phrase :score}`.
7. **Acceptance decided on a replicated copy over independent paths.** Searches were validated on
   X against `$$locale-gen`, which reached X by a `|all` message independent of the `|hash` event
   message, and the broadcast could in principle overwrite a newer value. Fix: split authority from
   metadata — `$$locale-auth` (owner only, `hash(locale)`, written synchronously) decides every
   record at A; the `|all` branch is terminal, writes only `$$locale-gen` with `max`, and never
   touches `$$locale-auth`; X/Y apply the generation stamped on the op.
8. **Multiple owner-to-data paths.** Publish entries, searches and block/unblock each crossed their
   own `|hash pk`. Fix: one `PhraseOp` record on one branch through one `(|hash *pk)` and one
   `(|hash *pk1)`, so per-phrase order is a single FIFO edge per hop.

## Decision / Basis / Outcome
- Decision: minor-fail. Basis: placement by `[locale, 2 chars]`, encoded-order candidate sets, and
  generation-keyed state are sound; the ordering defects (1, 2, 7, 8) are confined to the owner-side
  write path and the generation PState split, and the handle-transfer defect (3) to one schema
  edit; the partitioning, index and query design are unchanged. Outcome: PLAN.md edited for items
  1–8; proceed to build.

PHASE_VALIDATION:minor-fail
