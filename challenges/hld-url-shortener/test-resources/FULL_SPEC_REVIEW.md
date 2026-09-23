# Full-Spec Review

<!-- Full-spec review of hld-url-shortener (whole module against the whole spec), September 23,
     2026. Inputs read in full: README.md, src/hld_url_shortener/protocol.clj, IMPLICIT_SPEC.md,
     DECOMPOSITION.json (one subsystem, whole spec), test-resources/hld_url_shortener/module.clj,
     test-private/hld_url_shortener/*.clj, the rama SKILL.md and
     references/phase-full-spec-review.md. Line numbers refer to the files as they are on disk
     at the end of this review. -->

Verdict: pass
Summary: Hunted every protocol method, every README "Scope" rule, the grammar table, the
ordering/barrier rules, the bounded-work contracts and the enforced budgets against the module
and the private suite, at 2 and 4 tasks and through two wrappers. No module defect was found:
every write is a read-guarded, single-task, fixed-work microbatch transaction on hash(alias)
and every read is one point read. Two test gaps were found and closed inside existing blocks
(upper grammar bounds; the cross-client delete-racing-create case the implicit spec names),
and three stale artifact claims were corrected (an unsupported "three identical runs" claim,
drifted module line numbers, and a package status note). The suite ran green twice after the
fixes at 258 assertions. The one review-only limitation (opaque whole-history values are
invisible to the event hook) is recorded honestly rather than papered over.

## Contract walk (quoted clause → module → test)

Module lines: M = `test-resources/hld_url_shortener/module.clj`. Tests: F =
`functional_test_support.clj`, P = `performance_test_support.clj`, both run at 2 and 4 tasks.

| Spec clause (verbatim) | Module | Test |
|---|---|---|
| "Aliases are permanently reserved. Once an alias has ever been created it can never be created again, even after deletion or expiry." | M47-59: link read; existing link ⇒ `:rejected`; the `:link` record is never removed by any branch. | F "exact expiry boundary" (`exp` r2 after expiry), F "delete is irreversible" (`exp` r3 after delete), F "concurrent creates" (`dc-race` r2 after delete). |
| "Mapping and expiry are immutable. A link's destination and expiry never change after creation." | M49-55 is the only write of `:target-url`/`:expires-at`, guarded by `(nil? *link)`. | F "create, resolve, outcome, first request wins" (r1 replay with changed body, r2 with other URL/expiry). |
| "Creates are idempotent per (alias, request-id). The first outcome for a pair, whether `:created` or `:rejected`, is durably recorded and replayed... A retry that carries a different destination or expiry does not change anything: the first request wins." | M45-46: recorded outcome short-circuits everything. M57-59 records `:created`/`:rejected` in the subindexed `:outcomes` map. | F same block (r2 rejected, replayed with a changed body still `:rejected`). |
| "Delete is irreversible. Block is reversible." / "No effect when alias does not exist" | M62-66 sets `:deleted? true` only; no branch clears it. M69-80 set/clear `:blocked?`. All guarded by `(some? *link)`. | F "delete is irreversible and outranks block and expiry"; F "block outranks expired; unblock restores; idempotent"; F "block/unblock/delete on a missing alias leave no trace". |
| "Resolve reports exactly one status with precedence `:missing` > `:deleted` > `:blocked` > `:expired` > `:active`. A link is expired when `now >= expires-at`." | M99-106 `resolve-status` cond in that order; M136-140 returns `{:status :missing}` on nil link, else exactly `:status/:target-url/:expires-at`. | F "exact expiry boundary" (99 active, 100 expired, max-tick expired), "grammar boundaries" (expires-at 0 born expired), "grammar upper bounds" (expires-at = 10^12-1: active at max-1, expired at max), precedence blocks above. |
| "Clicks ... are deduplicated per (alias, click-id) and counted for the alias's lifetime regardless of the link's current status ... Observations for aliases that do not exist are dropped and leave no trace." | M86-97: `:clicks` nil ⇒ no write at all; `set-elem` membership ⇒ no-op; else `NONE-ELEM` insert + `termval (inc *clicks)`. | F "clicks: dropped when missing, deduplicated, status-independent" (c1 dropped then counted after create; duplicates from both clients; expired/blocked/deleted all counted); F "distinct concurrent observations" (60 sends, 45 distinct). |
| Grammar table: alias 1..32 `[a-z0-9-]`; ids 1..64 `[A-Za-z0-9-]`; url 8..2048 starting `https://`; ticks in `[0, 10^12)` | Strings and Longs stored as given; no truncation or parsing. | F "grammar boundaries" (lower bounds) and F "grammar upper bounds" (32/64/64/2048, max tick) — **the upper-bound block was added by this review** (see below). |
| "Sequential write calls from one client to the same logical owner (alias) are processed in invocation order." | M25 single depot `(hash-by :alias)`; M115-118 synchronous `foreign-append!`; no partitioner hop in the topology, so depot order is processing order. | F "one-client invocation order without intermediate barriers" (7 writes, no barrier), "order" r1/r2/r3. |
| "Write calls from different clients may be serialized in any order, but every write that returned before a `wait-for-processing!` barrier is processed before any write issued after that barrier returns." | M150 one counter per `create-module`, shared by all wrappers; M117 increments after the append returned; M146 waits on the cumulative processed count. | Every F/P block barriers through the OTHER wrapper; F "many request-ids" relies on the barrier to order req-0 before 39 cross-client creates; F "concurrent creates"/`dc-race` accept either order with no barrier. |
| "`resolve-alias`, `get-click-count`, and `get-create-outcome` do fixed read work" / "`record-click!` processing is fixed work per observation" | M130-142 three `foreign-select-one` point reads; M86-97 point lookups on subindexed structures only; no `ALL`/range navigator in the module. | P "read and write work is bounded..." at 32 and 512 entries: observed 1/1/2 point reads for the reads, 6 reads + 2 written entries for a click, 3 reads + 0 writes for a duplicate, zero growth, zero iterator work; within `read-budget`/`write-budget`/`growth-allowance`. |
| "Work and storage are balanced across tasks; the harness launches the module with 2 or 4 tasks" | Depot and PState both keyed by alias; no `|global`, no `|all`. | P "observed RocksDB work is distributed..." (400 owners, both wrappers): every task within 0.5x..1.5x on written entries and on aggregate read work; reads write nothing; totals inside the published ceilings. |
| "Read methods must not wait and must not mutate state." / "the client wrapper holds no business data" | M130-142 issue only `foreign-select-one`; wrapper fields are `ipc`, foreign handles, harness counter. | F "reads do not mutate" (repeated reads, count unchanged); P `:writes 0` on every read capture. |
| "Write methods (`!` suffix) return `nil`" | M115-118 `append!` returns nil. | F "write methods return nil". |
| Fault tolerance (SKILL: "Every write must produce correct results even if processing retries.") | Single microbatch topology (exactly-once replay) and every write is additionally read-guarded (M45, M48, M64, M71, M78, M87, M91). | Not executable under IPC; verified by trace. |

## Items found and fixed
| Location | Why it violated the spec | Fix applied | Evidence it holds |
|---|---|---|---|
| F "grammar boundaries" (before this review) | README grammar table bounds every input on both ends ("1..32", "1..64", "8..2048", "[0, 10^12)"); the suite exercised only the lower bounds (1-char alias, 8-char URL, tick 0). Per the review rules a class with one member omitted is a gap, and a module that truncated or re-encoded a 2048-char URL or a 32-char alias would have passed. | Added F "grammar upper bounds": 32-char alias, 64-char request-id and click-id (duplicate click sent twice), 2048-char URL round-tripped exactly, `expires-at` = 10^12-1 active at max-1 and expired at max, a 63-char id and a 31-char alias suffix read back as unknown/missing. | `/tmp/fsr_run1.log` and `/tmp/fsr_run2.log`: `Ran 4 tests containing 258 assertions. 0 failures, 0 errors.` at 2 and 4 tasks. |
| F "concurrent creates from two clients" | IMPLICIT_SPEC (delete-link! Concurrency): "delete racing a create for the same alias from different clients ... Either order is acceptable; only the two sequential outcomes are." TEST_VALIDATION claimed coverage via the two sequential blocks only; the race itself was never issued, so a module that produced a third state (e.g. lost the create) was not rejected. | Added alias `dc-race` to the same block: create via `a`, delete via `b`, no barrier; assert outcome `:created` and resolve ∈ {`:active`, `:deleted`} with the creation values; then a barriered delete is final and a later create is rejected. | Same two green runs. |
| TEST_VALIDATION.md "Execution" | Claimed "run three times in this session with the identical result" but cited two logs. Inspection of `/tmp`: `/tmp/baseline_run2.log` (14:23, 232 assertions, green) and `/tmp/final_run.log` (14:30, 232, green) are the only green runs on that revision; `/tmp/baseline_run.log` (14:12) is the previous revision with 220 assertions and 1 failure. | Rewrote the section to state exactly two logged green runs, name the third log for what it is, and add this review's two 258-assertion runs. Decision/Basis/Outcome line corrected to match. | The log excerpts quoted in the section. |
| IMPLEMENTATION_VALIDATION.md | Every module line citation was drifted by 3-9 lines from the file on disk (e.g. "Click (80-92)" is M85-97; "get-click-count line 133" is M142), so the code-trace evidence pointed at the wrong forms. | Re-anchored all citations to the committed file; the module itself is unchanged (`git status` clean on module.clj). | Citations re-checked against `cat -n` of M. |
| DEVELOPMENT_STATUS.md | Still stated for the shortener that "No build-validation artifacts, fresh full-spec review, mutation evidence ... exists" and listed 216 assertions; both stale. | Added a dated, shortener-only update at the top; the other four packages' sections are untouched. | — |

## Review-only limitations (stated, not hidden)
- The `com.rpl.rama.test` event hook reports operation counts only (`:rocks-read`,
  `:rocks-iterator`, `:rocks-iterator-read`, `:rocks-commit` with `:write-batch-count`); event
  data carries no value-size or byte field. A design that stores an alias's whole click or
  request history as one opaque value would pass every executable budget while violating
  "fixed read work". README states this and it is rejected by review only. No hook API was
  invented to close it.
- The reference module's own storage layout was checked by inspection: `:outcomes` and
  `:click-ids` are subindexed (M35-36), so no per-alias value grows with history; `:link` is
  five scalars.
- Retry safety is verified by trace (microbatch exactly-once plus read guards), not by an
  IPC fault-injection test.
- Prior BUILD evidence relied on: M1..M5 and M6b mutation runs and the V1 bounded-iterator
  variant (`/tmp/mutations.log`, `/tmp/m6b_run.log`, `/tmp/v1_run.log`) were NOT re-executed
  in this review; the assertions they tripped are unchanged and the two new blocks only add
  assertions.

## Execution (this review, from `challenges/hld-url-shortener`, unmutated module)
```
clojure -J-Xmx1600m -X:test-private-harness
Ran 4 tests containing 258 assertions.
0 failures, 0 errors.
```
Run 1 `/tmp/fsr_run1.log` (76 s, after the two test additions); run 2 `/tmp/fsr_run2.log`
(final, after all artifact edits, 76 s): `Ran 4 tests containing 258 assertions. 0 failures, 0 errors.` EXIT=0. `LeaderNotFoundException`
lines in the logs are IPC shutdown noise.

Decision: pass. Basis: a second full pass over the whole spec after the fixes found nothing
new; the module is unchanged and matched clause by clause; both gaps are closed inside existing
blocks and the suite is green at both task counts. Outcome: full-spec review complete.

## Independent integration correction and check

The worker's final inspection found one Oracle follow-up still incomplete:
winner/last/missing outcome correctness was asserted outside capture-ops, while
only req-7 was measured. The measured loop now includes winning, final rejected,
and missing requests at both 32 and 512 entries. This closes the observable
missing-history-scan gap without prescribing an index layout.

After this localized test-only correction, the worker independently ran:
`clojure -J-Xmx1600m -X:test-private-harness` from the challenge directory.
Result: **4 tests, 270 assertions, 0 failures, 0 errors**, exit 0
(`/tmp/hld-url-independent-final2.log`). The earlier independent run before this
correction passed 258 assertions. Reference module unchanged.
