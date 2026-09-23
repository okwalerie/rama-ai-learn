# Full-Spec Review — hld-file-sync (private reference package, 2026-09-23)

Verdict: pass
Summary: One fresh session reviewed the whole package — README.md,
`src/hld_file_sync/protocol.clj`, `test-resources/IMPLICIT_SPEC.md`,
`DECOMPOSITION.json`, `PLAN.md`, `PLAN_VALIDATION.md`, the entire
reference module (`test-resources/hld_file_sync/module.clj`, 467 lines,
unchanged by this session) and the entire private suite — against every
public clause of the README and protocol. The hunt found no reference
correctness defect. It found three private-test defects that asserted
more than the README guarantees (cross-client ordering, an ingress
task-count requirement, an empty-tail read bound that forbade a valid
O(limit) design), three coverage gaps (1024 distinct hashes through a
full command chain without barriers, a repeated identical conflicting
body across a module update, replay of a rejected-before-creation
original after an update once success would be possible), one
misleading test description, one README ambiguity about the scope of
`wait-for-processing!`, and several unasserted contract clauses
(unknown-namespace point queries, several structural bounds, cross-type
conflicts on rejected originals, size conservation, duplicate paths,
1024-entry command cost, command return value). All were fixed in the
tests or README, re-verified by fresh-JVM runs of
`clojure -X:test-private-harness` (final: 2 tests, 710 assertions,
0 failures, 0 errors) and by two module mutants that the updated suite
rejects (70 and 40 failures). Two further full hunt passes after the
fixes found nothing new.

## Items found and fixed

| Location | Why it violated the spec | Fix applied | Evidence it holds |
|---|---|---|---|
| `functional_test_support.clj` "writes continue after the update with contiguous seqs" | Required a commit issued through client `c2` to be processed before one issued through `c`; README "Ordering" guarantees only "Commands issued by one client against the same namespace are processed in the order the client issued them" | Barrier after the `c2` commit before `c` issues the stale one; the conflicting/replay/size-mismatch commands that follow have no cross-client dependency; reads still go through the other client | Suite green at 2 and 4 tasks; expectations unchanged in substance (v3 then copy `~c4`) |
| `performance_test_support.clj` former "commands for independent namespaces are processed on more than one task" | Asserted `:depot-read` events on ≥ 2 tasks — an ingress/partition-scheme constraint absent from the README, and it did not establish business independence (every namespace used size 1) | Assertion removed; per-task depot reads are printed as a diagnostic only; each of `2·tasks` namespaces registers hash `x` with a distinct size and is read back, plus an absent namespace and the untouched fixture namespace | Green; output shows the diagnostic (`{0 2, 1 2, 2 2, 3 2}` at 4 tasks) without any assertion on it |
| `performance_test_support.clj` "get-changes cost …", empty tail | `(< reads 40)` at `limit` 500 forbade a valid design that point-reads each candidate seq ("proportional to `limit`, not to the journal length or to `after-seq`") | Bound is now `< 40 + 2·limit` for every empty tail (limit 50 and 500); a fixed-limit empty tail is captured on the small (8-entry) and big (1502-entry) journals and `big ≤ 2·small + 30` detects history dependence; no early-tail optimisation is required | Reference tail = 2 reads at every size; a journal scan (1502 iterator reads) fails both the 140 bound and the ratio |
| `functional_test_support.clj` new "1024 distinct hashes …" (ns `wide`) | Boundary coverage used one repeated hash, so a 1024-entry blocklist read only one block; README bounds "1 to 1024 entries", "0 to 1024 entries; repeats allowed; order significant" | Register 1024 distinct hashes with distinct sizes 1000..2023, create with all 1024, update with the reversed list, stale copy with the 512 even hashes, replay the create and the stale commit, two files with the same path — all without intermediate barriers; sizes (1547776, 773632), versions, records, and the 5-entry journal are computed from the inputs | Green; last-writer-wins mutant fails here (final-file lines 453–478) and the replay mutant fails at 460/462 |
| `functional_test_support.clj` "the same conflicting body counts once per attempt" | README: `:conflicting-attempts` "increases by one per conflicting attempt" — unverified that an identical conflicting body counts each time and that the counter survives `update-module!` | Original A, body B twice → 2; replay A through the other client → 2; `update-module!`; B again → 3; file stays at version 1, no version 2, journal unchanged | Green; replay mutant fails at final-file lines 552–594 |
| `functional_test_support.clj` "writes continue after the update …" (`rc2`/`q1`) | README: "a replayed or conflicting command is never re-evaluated against current namespace state" — unverified after a module update once the business conditions would now permit success | After the update, replay `q1` (`parent-version 1` on `g`, whose head is now 1): outcome stays `:rejected :no-such-file` with `:conflicting-attempts 1`, `g` stays at version 1, journal length 1 | Green; replay mutant fails at final-file lines 586–594 |
| `functional_test_support.clj` "within one batch (no intermediate barriers)" | Description claimed single-microbatch processing that back-to-back appends do not prove | Renamed "issued back-to-back without intermediate barriers (may span microbatches)"; no test or document now claims one microbatch | Text only |
| `README.md` "Shared state" | Tests barrier through a client other than the writer; the harness docstring ("Waits for all pending topology processing") implies that is valid, but the README did not say so, so a per-client counter could read as compliant | Added: "`wait-for-processing!` on any client of one `create-module` result waits for every command issued through any client of that result, including clients created later" | New `fresh-client` scenario: a client created after every other write reads outcomes/files/journal of earlier scenarios and issues + barriers its own commit (seq 6) |
| `functional_test_support.clj` structural blocks | Enumerated invalid-input classes omitted members: block without `:hash`, non-map block entry, `ns-id` over 128 on each command, blocklist entry not a String / over 128, nil `file-id`, nil `path`, empty `ns-id` and nil `file-id` on queries, negative version | One assertion per omitted member | Green (all throw `IllegalArgumentException`, nothing appended) |
| `functional_test_support.clj` "valid boundary values are accepted" | README missing-file rules for an unknown namespace (`get-file`, `get-file-version`, `get-outcome`, `get-block-size` → nil) and `version` at `Long/MAX_VALUE` were unasserted; "returns `nil` and never returns a business result" was unasserted | Assertions added, including `(nil? (p/register-blocks! …))` and `(nil? (p/commit-file! …))` | Green |
| `functional_test_support.clj` "rejected originals stay rejected …" | "Within one namespace a `request-id` is shared across all command types" was only tested against an accepted original | `register-blocks!` reusing the rejected id `k4` → `:conflicting-attempts 2`, still `:need-blocks`, file unchanged | Green; replay mutant fails at final-file lines 360–363 |
| `functional_test_support.clj` "create, update, and rejections in README order" | Invariant "Every hash referenced by any stored version is registered … with the size used to compute the version's `:size-bytes`" was not cross-checked through `get-block-size` | `size-bytes` of version 1 equals the sum of `get-block-size` over its blocklist with repeats | Green |
| `performance_test_support.clj` new "… at the 1024-entry input maximum" | "proportional to the length of the submitted list" was measured only at 5 entries | Register 1024 new blocks and commit a 1024-hash blocklist in the big namespace; reads `< 100 + 3·1024`, register writes `< 100 + 2·1024`, commit writes `< 60`; outcome, block size and stored blocklist checked outside the capture | Measured: register 2073 reads / 1036 writes, commit 1036 reads / 5 writes |

## Items examined and dismissed (no change)

| Location | Question | Basis for dismissal |
|---|---|---|
| module 226–241, 299–372 (yields inside the per-namespace loop) | Could a query or foreign read observe a half-applied command during `yield-if-overtime`? | `references/microbatch.md` "Read visibility": readers outside the owning topology see only committed state; only one loop event per namespace per microbatch writes that namespace |
| module 186 (`(str "~" request-id)`) | Can a generated id collide with an existing file? | Client ids contain no `~`; non-nil-parent commits never create a file except the copy named by their own request-id; a request-id is used at most once as an original, so `~rid` exists only if rid created it |
| module 364 (`(= (get *req :payload) *cmd)`) | Does record equality survive PState serialization and inter-worker transport? | Replays are recognised at 4 tasks / 2 workers and after `update-module!` (idempotency, `wide`, `sh`, `rc2` scenarios) |
| module 449–450 | `after-seq = Long/MAX_VALUE` | Returns `[]` before `(inc after-seq)`; tested at `MAX` and `MAX-1` |
| module 318–325 (block write loop) | Register costs ≈ 2 RocksDB reads per new hash (2073 for 1024) versus the plan's estimate of `2 + k` seeks | Each write-only `[(keypath *ns :blocks *h)]` transform re-navigates the top-level entry; the cost stays linear in the submitted list, which is the README guarantee. Recorded as a plan-estimate correction, not a defect; the module is unchanged |
| module 248 (`depot.microbatch.max.records` 200) | Bounded per-microbatch group memory | Retained plan decision; 1024-entry commands processed within the bound in the new tests |

## Spec coverage trace (whole README, after the fixes)

| README clause (verbatim) | Module | Tests (functional unless noted) |
|---|---|---|
| "Types and bounds" table; "anything else … throws" | 54–111, 396–448 | 65–130, 137–170 |
| "Client form is the only form accepted by a create" | 412–413 | 108, 283 |
| "A valid id of either form that names no file follows the missing-file rules" | 147–154, 374–383 | 156–157, 222–223 |
| "returns `nil` and never returns a business result" | 403, 417 | 138–141 |
| "produces exactly one durable outcome, readable with `get-outcome`" | 362, 419–425 | every scenario |
| "Outcome shape" | 27–49, 208–216 | helpers 28–41, `longs?` 215 |
| "Structural violations … throw … Nothing is appended and no outcome is created" | 397–401, 405–413 | 65–136 |
| "Business rejections … no other state changes" | 316, 333, 343 | 185–190, 207–239 |
| "Validation order … leaves an already-used `request-id`'s outcome … unchanged" | client-side order | 162–170 |
| "Replay and conflict handling … before any business validation" | 307–371 | 345–364, 373–408, 571–594 |
| "identical payload is a replay … no effect" | 364–366 | 313–318, 334–343, 453–478, 546–553 |
| "different payload is a conflicting attempt … `:conflicting-attempts`, which increases by one per conflicting attempt" | 368–371 | 319–332, 360–363, 543–555, 584–587 |
| "A replay of a rejected original stays rejected" | 364–366 | 345–364, 387–408, 588–594 |
| "Commands issued by one client … processed in the order the client issued them" | 286–298, 299–372 | 410–431, 433–478 |
| "Durability … Nothing is ever deleted or overwritten" | field-path writes 346–358, 362, 370 | 373–408, 557–569, 599–615 |
| `register-blocks!`: `:size-mismatch`, atomic, `:registered` | 310–327 | 137–161, 172–201 |
| `commit-file!` rejections "checked in this order" | 147–154, 174–181 | 207–239 |
| "`:need-blocks` … each listed once, in order of first occurrence" | 156–159 | 228–229, 245–247; performance 120 |
| "New head version … `head + 1` (or `1` on create)" | 188, 348–358 | 207–239, 480–531 |
| "Conflict copy … `file-id' = (str "~" request-id)` … `path'` … never exceeds 1024" | 166–172, 186–187, 345–347 | 241–306 |
| "`size-bytes` is the sum … counting every repeated hash" | 161–164 | 214, 245, 230–232, 446–449 |
| "Every accepted commit … appends exactly one entry to the namespace journal" | 354–357 | 236–239, 266–274, 471–478 |
| Accepted outcome keys; "`:conflict-copy?` is true only …"; "`:conflict-of` … identical in every version record, journal entry, and outcome" | 34–43, 335, 351, 355 | 254–298 |
| `get-outcome`, `get-block-size`, `get-file`, `get-file-version` shapes and nil cases | 419–443, 374–383 | 137–161, 207–239 |
| `get-changes`: "strictly greater than `after-seq`, ascending, at most `limit`", "empty vector … unknown", contiguous seqs | 445–455 | 480–531 |
| Invariants (all seven) | as above | 207–306, 433–478, 526–531 |
| Resource guarantees (all four bullets, "as the namespace's own history grows") | subindexed schema 251–282, 226–241, 445–455 | performance 77–215 |
| "Tests exercise both 2 and 4 tasks" | — | `test-module-functional`, `test-module-performance` |
| "Shared state" incl. the new `wait-for-processing!` sentence | 461–467, 457–459 | 533–615 |
| "Contract: `create-module`", "Synchronization" | 461–467, 457–459 | `run-all`, `run-at` |

## Verification runs (all fresh JVMs, foreground, from the package directory)

| Run | Result | Wall clock |
|---|---|---|
| functional namespace after the first fix set | 1 test, 530 assertions, 0 failures, 0 errors | 79 s |
| performance namespace after its fix set | 1 test, 154 assertions, 0 failures, 0 errors | 49 s |
| mutant A: `copy? false` in `commit-plan` (last-writer-wins) | 530 assertions, **70 failures**, 0 errors (first at line 245 of the final file); module restored, `cmp` identical, sha256 `9048c319e3c63ca0…` before and after | ~80 s |
| mutant B: replay case `(case> false)` (replay counted as a conflict) | 530 assertions, **40 failures**, 0 errors at final-file lines 316–405, 460–462, 552–594; module restored, `cmp` identical | ~80 s |
| full suite after all fixes | 2 tests, 706 assertions, 0 failures, 0 errors | 97 s |
| full suite after the final nil-return assertions | 2 tests, 710 assertions, 0 failures, 0 errors | 104 s |

The mutant runs preceded the final two-line edit at line 138, so their printed line numbers are one lower than the final-file numbers reported above for lines ≥ 139. An earlier mutant-A attempt was discarded: the edit left a `;;` comment
that swallowed a closing bracket, so the run errored on load (1 error,
0 assertions) and proved nothing. `clj-kondo --lint test-private`: 0
errors, 0 warnings.

## Limitations stated plainly

- RocksDB event counts cannot detect a whole collection stored as one
  value; the only check for that is the manual schema/path review in
  IMPLEMENTATION_VALIDATION.md (no field holds an unbounded collection).
- "Without intermediate barriers" means back-to-back appends; it does not
  prove that the commands shared one microbatch.
- InProcessCluster at 2 and 4 tasks is not a multi-node cluster; retry,
  replication and worker-failure behaviour rest on Rama's microbatch
  guarantees, not on a fault-injection test.
- Ingress distribution across tasks is reported, not asserted.
- Two mutants were run this session (plus the build phase's earlier
  last-writer-wins run); the suite's sensitivity to other plausible bugs
  is argued from its independent expectations, not measured.
- Independent parent verification and the Oracle implementation review
  of this reviewed state have not happened; they follow this session.
