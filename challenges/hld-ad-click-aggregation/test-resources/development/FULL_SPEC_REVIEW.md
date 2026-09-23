# Full-Spec Review

Verdict: pass
Summary: Fresh, whole-contract pass over README.md, the `AdClickAggregation`
protocol, IMPLICIT_SPEC.md, DECOMPOSITION.json, the entire reference module
and both private test namespaces, at the end of the build. Every protocol
operation, every disposition/lateness/replay/counter rule, the write-ordering
and multi-client rules, the signed-64-bit domain, durability across restart,
and the published efficiency contract were traced from spec clause to module
form to test assertion. The module needed no change. The efficiency suite
asserted more than the README publishes — bounded work for `get-watermark`
and `advance-watermark!` (the README's "Efficiency contract" names neither),
and absolute RocksDB operation counts (`< 75`, `< 130`) that encode the
reference's layout — and those assertions were removed. Growth checks for
the four published bounds (`record-click!`, `get-request`, `get-window`,
`get-windows`) and every correctness check were kept. The suite was re-run
from the package directory after the change and is green. The README was not
touched: the public spec is source-derived and was not strengthened to
justify any private assertion.

## Items found and fixed
| Location | Why it violated the spec | Fix applied | Evidence it holds |
|---|---|---|---|
| `efficiency_test_support.clj`, `testing "get-watermark reads a constant amount"` and the `wm-1`/`wm-2` captures | README "Efficiency contract" lists bounds only for `record-click!`, `get-request`, `get-window`, `get-windows` and the cross-campaign clause. No bound is published for `get-watermark` against same-campaign growth (the growth phase adds 150 windows and ~260 requests to the same campaign). The assertion imposed an unpublished requirement. | Removed the cost captures and the growth assertion. `get-watermark` value checks (1000, 1001, 1002, 10^12, 0 for other campaigns) retained. | `grep -n "wm-1\|wm-2"` → no matches; suite green (below). |
| `efficiency_test_support.clj`, `testing "advance-watermark! does bounded work"` (`noop-*`, `adv-*` captures) | Same clause: README publishes no work bound for `advance-watermark!`; the "Watermark" section only requires monotonic no-op semantics. | Removed the captures and both growth assertions; the stale (500) and effective (1001 / 1002) advances are still issued and their observable effects still asserted. | `grep -n "noop\|adv-1\|adv-2"` → no matches; `(is (= 1001 ...))`, `(is (= 1002 ...))` retained. |
| `efficiency_test_support.clj`, `testing "an advance that closes every window costs no more than one that closes none"` | Same clause. Constant cost for an advance that closes many windows is an IMPLICIT_SPEC derivation, not a README requirement. | Renamed to "... changes nothing stored and touches no other campaign" and dropped the `jump` cost assertion. Retained: watermark = 10^12, `[6000,6180)` unchanged, 153 windows still listed, a later click into window 6060 is `:late`, other campaign's watermark and window untouched (README "Watermark", "Reads of one campaign must not read state belonging to any other campaign", worked example steps 8-9). | Block present at lines 213-227; suite green. |
| `efficiency_test_support.clj`, `(is (< req-2 130))`, `(is (< win-2 75))`, `(is (< range-2 75))` | README bounds are relative ("must not read anything proportional to", "reads one window", "proportional to the windows ... inside the requested range"). An absolute RocksDB-event ceiling is a layout-specific number derived from the reference, which a different valid iterator/index design could exceed without violating the README. | Removed the three absolute assertions. Kept the small-state vs grown-state growth checks (`after <= 2 * before + 20`) for exactly the four published bounds, with failure messages naming the added data. | `grep -n "< req-2\|< win-2\|< range-2"` → no matches; growth checks at lines 186-203. |
| `efficiency_test_support.clj` ns docstring | Claimed bounds "so any reasonable topology or storage layout passes" while asserting absolute counts and unpublished operations. | Docstring now states which bounds are asserted and why, that `get-watermark`/`advance-watermark!` are correctness-only, and the two instrumentation limits honestly: events carry no payload sizes (a whole-campaign blob filtered in memory is not caught), and the growth phase mixes cross-campaign and same-campaign growth, so the isolation clause is exercised jointly with the per-operation bounds rather than alone. | Lines 1-36. |

## Spec-to-evidence trace (nothing new found)
- **Disposition table, order 1-4** (README "Disposition"; protocol `record-click!`): `disposition` L44-52 in `module.clj`; functional "disposition precedence" (`f1` fraud+invalid → `:fraud`), "exact lateness cutoff" (`f` closed+fraud → `:late`, flags stored).
- **"A value `<=` the current watermark is a no-op"**: `<<if (> *watermark *cur)` L201; functional "watermark monotonicity", worked example step 12.
- **"closed once `watermark >= E + 120`"**: L49; functional "exact lateness cutoff" at 179/180 and 239/240; the BUILD mutation `>=`→`>` produced `54 failures, 0 errors` of 402 (TEST_VALIDATION.md), evidence the boundary is pinned at both task counts.
- **"A replayed `request-id` ... has no effect at all"**: replay probe L209 precedes the watermark read; functional "replays with conflicting bodies" (same phase, cross-client, after closure, replay of a `:late` record), worked example step 10.
- **Counters shape, `:clicks` sum, `:billed-spend` over `:billed` only**: `count-row` L76-86, `+counters` combiner L90-93 (all five keys always present); functional `consistent-window?`, "disposition precedence" (`spend 0` billed; fraud/invalid spend never summed).
- **Audit record with observed watermark, immutable**: `audit-record` L54-65 written once by `termval` L214-217; functional "audit records are immutable across watermark changes".
- **Worked example steps 1-13 verbatim**: functional "README worked example, verbatim", split across both wrappers.
- **`get-windows` half-open, ascending, `[]` when none**: paged `sorted-map-range-from` loop L236-245, `sort-windows` L253, `single-window-end` clamp L34-42; functional "window membership at 60-unit boundaries", "signed 64-bit limits" (`TOP-ALIGNED` bounds, `Long/MAX_VALUE` watermark).
- **"Writes addressed to the same campaign must take effect in the order the client invoked them"**: depot `hash-by :campaign-id` L179, no repartition before the writes, pre-agg `termval`s depth-first per record; functional "same-campaign writes take effect in client order inside one phase" (both directions).
- **2 and 4 tasks, second client writes, `Synchronizable`**: both drivers deploy `{:tasks 2}` and `{:tasks 4}` explicitly; functional suite writes through `ca`, `cb`, and a wrapper `cc` created after 301 processed records; `satisfies? harness/Synchronizable` asserted; `wait-for-processing!` L290-292 waits on the shared per-IPC append total.
- **Durable Rama state**: all business state in `$$campaigns`; functional "durable state survives a simulated worker restart" (`rtest/update-module!` then reads, a replay, a late click).
- **Published efficiency bounds**: `record-click!` = 1 replay probe + 1 watermark read + 1 audit write + 2 combiner leaf updates (L209-228); `get-request`/`get-watermark` = one `foreign-select-one` (L280-282); `get-window` = one paged read of exactly one key plus its breakdown (L283-285, `first-page` → 1); `get-windows` = pages bounded by slots in range (L128-154). IMPLEMENTATION_VALIDATION.md "Runtime traces" records the measured flat costs; not re-measured here.
- **Considered, not a violation**: `append-counts` is keyed per `create-module` result, so wrappers built from two separate `create-module` calls on one IPC would not share a barrier. README scopes multi-client to "Multiple clients wrapping the same deployed module" via one `:wrap-client`, which the harness and tests use; no change made.

## Suite result after the fix (`clojure -X:test-private-harness`, package directory)
```
Testing hld-ad-click-aggregation.efficiency-challenge-test
Testing hld-ad-click-aggregation.functional-challenge-test
Ran 4 tests containing 388 assertions.
0 failures, 0 errors.
```
1m39s wall. 402 → 388 = 7 removed cost assertions × 2 task counts. The
`rpl.shaded.kafka.log.Log` ERROR lines during the run are depot log segment
recovery from the deliberate `update-module!` restart scenario, not test
errors. BUILD-phase evidence preserved unchanged in TEST_VALIDATION.md and
RECOVERY.md: `402 assertions, 0 failures, 0 errors` on the original suite, and
`54 failures, 0 errors` under the `>=`→`>` late-cutoff mutation. TEST_VALIDATION.md's
"Efficiency contract" bullets describing the `< 75` / `< 130` ceilings and the
advance/watermark growth checks describe the pre-review suite; this file is
the current description.

PHASE_VALIDATION:pass
