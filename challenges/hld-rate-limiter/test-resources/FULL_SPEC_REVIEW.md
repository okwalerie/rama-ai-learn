# Full-Spec Review

<!-- Full-spec review artifact for hld-rate-limiter (September 23, 2026). One fresh
     adversarial session over README.md, protocol.clj, IMPLICIT_SPEC.md, DECOMPOSITION.json,
     test-resources/hld_rate_limiter/module.clj (M, md5 705cd4a10c867b9e17a50b5b0a52aaf3,
     unchanged by this phase), test-private functional (F) and performance (P) support,
     and ORACLE_IMPLEMENTATION_REVIEW.md. -->

Verdict: pass
Summary: Hunted every protocol rule, every README scope clause, every bounded-work budget and
the whole IMPLICIT_SPEC entity matrix against the module and the private suite, at 2 and 4
tasks with the barrier always taken through the opposite wrapper. No module defect was found
by trace or by execution; the module was not edited. One concrete suite gap was found and
fixed: the public input grammar's boundary classes (maximum version, maximum tick, maximum
refill so `(T - at) * refill` reaches ~10^18, cost equal to the maximum capacity, 64-char
ids using every grammar class, 16 endpoints, and separator-bearing ids that collide in
composite-key designs) had no assertions. A new `testing` block (F:308) closes it with
hand-derived expectations and no invalid inputs. The suite is green after the fix
(4 tests, 360 assertions, 0 failures, 0 errors); a second full hunt found nothing new.

## Items found and fixed
| Location | Why it violated the spec | Fix applied | Evidence it holds |
|---|---|---|---|
| F (no block before this phase covered the grammar table) | README "Input grammar and bounds": `version` "Long in `[1, 2^31)`", `refill` "Long in `[0, 10^6]`", `now` "Long tick in `[0, 10^12)`", `cost` "Long in `[1, 10^6]`", ids "1..64 chars", config "with 1..16 endpoints", and "With these bounds `(T - at) * refill` fits in a Long." No test sent version 2147483647, now 999999999999, refill 1000000 with maximal elapsed, cost 1000000 against capacity 1000000, a 64-char id, an endpoint using `.`/`_`/`-`, or 16 endpoints. A suite that omits the extreme member of every bound class is a gap. | Added F:308 "public grammar boundaries": version 2^31-1 installs and 2^31-2 is ignored; status/decision at tick 10^12-1; cost 10^6 drains capacity 10^6 to `{:user 0 :endpoint 0}`; elapsed 10^12-1 times refill 10^6 clamps exactly to 1000000 (`=`, so a double or overflow result fails); 16-endpoint config with a 64-char endpoint `a/b_c.d-zzz...`, 64-char user `U_x...-9` and 64-char request `R-0...`; debit on one endpoint leaves the other 15 full; users `a-b`/`c`, `a`/`b-c`, `a_b`/`c` record independent decisions and cross lookups are nil; endpoints `x/y`, `x`, `y` are independent and `x/`, `/y` are nil. | `/tmp/rl_fsr_run1.log`: 360 assertions (306 + 27 x 2 task counts), 0 failures, 0 errors. Every expectation is hand-derived from `available(T) = min(capacity, tokens + (T - at) * refill)`. |

## Hunt record: every operation and clause, module lines and covering tests

Protocol `check!` rules, in order:
- Rule 1 "already has a recorded decision: no effect ... even if endpoint, cost, or now differ": M:140-141 looks the decision up by `(keypath *user-id :decisions *request-id)` before any evaluation and guards on `nil?` (a denied map is still non-nil). F:134 (both wrappers, changed args), F:275 (changed payable body across a version), P:195 (retry work at 32 and 512).
- Rule 2 "T = max(now, user clock). The clock is NOT changed here": M:52. F:126 (now < clock), F:118 (large now on a denial leaves clock 3), F:254 (T = 11 and T = 1000 recorded on shadow denials, clock stays 10).
- Rule 3 `:no-config` map: M:57-58. F:75, F:87 (replays after config), F:203 ("no config means no shadow"), F:308 (separator ids).
- Rule 4 `:unknown-endpoint` with `:allowed shadow?` and `:remaining nil`: M:61-64. F:241 (enforcing, allowed=false), F:203/F:254 (shadow, allowed=true).
- Rule 5 `:cost-exceeds-capacity` "cost > capacity of either bucket" before sufficiency: M:77-81. F:118 (endpoint), F:241 (user only), F:254 (shadow at T = 1000), P:217 (denied budget).
- Rule 5 allow "BOTH buckets debited by cost at T, user clock = T": M:83-91 one `assoc` of clock and both buckets, written by one `termval` at M:148. F:100, F:148 (same tick), F:167 (cost equal to available), F:308 (max cost, max tick).
- Rule 5 deny `:insufficient-tokens` "NO bucket debited": M:93-97 returns nil limiter. F:111 (endpoint short, user not debited), F:159 (user short, endpoint not debited), F:403 (16 interleaved checks from two wrappers: exactly 3 allowed, remaining set exact, status 1/91).
- "Every would-allow false decision ... has no other effect: no debit and no clock change": M:147 writes the limiter only when `*new-limiter` is non-nil, which only the debit branch returns. F:118, F:159, F:241, F:254, F:275, P:217.
- Shadow: "computes `:would-allow` and debits buckets exactly as enforcing mode would, but `:allowed` is always `true`": M:63, M:78, M:85, M:94. F:203, F:254; `:no-config` remains allowed=false at F:203.

Protocol `set-config!`:
- "version >= the given version: no effect at all": M:38 strict `>`. F:180 (equal, different content), F:186 (lower), F:308 (2^31-2 below 2^31-1).
- "EVERY bucket ... reset to full. Buckets for endpoints absent from the new config are discarded. The user clock is unchanged": M:39-45 rebuilds the buckets from the new config only and copies the clock. F:186 (removed endpoint nil, capacity change becomes full at the new capacity, clock 2000 kept, history survives), F:224 (drained refill-0 buckets restored by version 5), F:275.
- IMPLICIT_SPEC "Config and buckets change together": one `termval` of the whole limiter at M:134. Reviewed; no reader can observe a split state because reads are single `foreign-select-one` calls (M:195, M:198).

Reads:
- `get-decision` "or nil when that pair has not been processed. The map never changes once recorded. Fixed read work.": M:192-193, P:143-173 (earliest, latest, missing at 32 and 512 within `read-budget`, zero growth), F:275 (immutability across a version change).
- `get-config` "as most recently installed, or nil": M:194-196. F:75, F:180, F:186, F:308.
- `get-status` "WITHOUT changing the clock or any bucket": M:162-173 is a pure projection in the wrapper; P:239 asserts reads write zero entries. F:100 ("status is pure"), F:144 (overshoot clamp, refill 0), F:308 (max product clamp).

README "Bounded-work contracts" and "Enforced work budgets": P:137-237 measures every listed operation inside `capture-ops` at 32 and 512 decisions with the published `read-budget`, `write-budget` and `growth-allowance`; P:239 measures the 400-owner distribution with same-wrapper config/check pairs, alternating wrappers, `:threads tasks`, per-metric ceilings and the 0.5x..1.5x band on written entries and aggregate read work. Decisions are stored per entry in a subindexed map (M:104-122), so the README's disclosed review-only limitation (an opaque whole-history value would pass the counts) does not apply to this module; this was confirmed by inspection, not by a test, exactly as README states.

Ordering and synchronization: one depot hashed by `:user-id` (M:101) so every write for one user is serialized on one task in append order; the shared counter (M:184, M:207) makes a barrier through either wrapper cover writes returned through both (M:203). F takes every barrier through the other wrapper; P:239 alternates wrappers per owner.

Fault tolerance: the only topology is microbatch (M:103), so a retried batch cannot double-debit or double-reset; each write is additionally read-guarded (M:38, M:141). No wall-clock use anywhere in M.

Boundary and malformed inputs: README states "Callers only send inputs that satisfy the grammar; the only 'invalid' inputs tests send are the explicit retry and stale-version cases". The suite sends exactly those two classes (F:134, F:180, F:186, F:275, F:308) and now covers every bound's extreme member (F:308). No input outside the public grammar was added.

## Second hunt (after the fix)
Re-read the spec from the top against M and the amended F/P. Nothing new: every operation, every numbered rule, every README scope bullet, every IMPLICIT_SPEC matrix cell and every budget maps to a module line and a covering assertion above. The new block was attacked as new surface: its expectations were re-derived by hand (user bucket 0 at tick 10^12-1 with elapsed 0 denies cost 1 while the untouched endpoint reports 1000000; 10^12-1 times 10^6 plus 0 clamps to 1000000), and the assertion count grew by exactly 27 per task count.

## Execution
```
cd challenges/hld-rate-limiter
clojure -J-Xmx1600m -X:test-private-harness      (foreground, /tmp/rl_fsr_run1.log)
Ran 4 tests containing 360 assertions.
0 failures, 0 errors.
real 1m10.9s   EXIT=0
```
Module md5 before and after this phase: 705cd4a10c867b9e17a50b5b0a52aaf3 (no module edits).
No mutation runs were repeated; the four valid mutants recorded in TEST_VALIDATION.md stand.

Decision: pass. Basis: full adversarial pass over the whole spec found one suite gap (grammar
boundary classes), fixed with hand-derived assertions, re-verified green at 2 and 4 tasks; a
second full pass found nothing new; module unchanged. Outcome: hld-rate-limiter full-spec
review complete.

## Independent worker verification

After the fresh Fable review exited, the worker independently inspected the
changed public budgets, measured earliest/latest/missing decision loop, and
functional boundary scenarios. The canonical command was rerun from this
package: `clojure -J-Xmx1600m -X:test-private-harness`.
Result: **4 tests, 360 assertions, 0 failures, 0 errors**, exit 0
(`/tmp/hld-rate-independent-final.log`). Both 2/4-task entrypoints ran.

PHASE_VALIDATION:pass
