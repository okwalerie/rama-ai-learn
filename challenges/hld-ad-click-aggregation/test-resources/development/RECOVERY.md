# Build checkpoint

Status: **HANDOFF — BUILD + FULL-SPEC REVIEW COMPLETE; Oracle test-fairness fixes outstanding.**
2026-09-23.

History: Claude CLI `claude-fable-5-1` completed public drafting, Phase 0,
decompose, Phase 1 and Phase 2 (minor-fail corrected in PLAN.md). The first
build session hit the session quota. The resumed build session (this
checkpoint) completed the reference, the private tests, and both validation
artifacts.

## What exists now

- Reference: `test-resources/hld_ad_click_aggregation/module.clj` (paged
  range read; `get-window` end clamp; single-combiner counters — see
  IMPLEMENTATION_VALIDATION.md "Plan conformance" for the three justified
  divergences from PLAN.md text).
- Private tests: `test-private/hld_ad_click_aggregation/`
  `functional_test_support.clj`, `functional_challenge_test.clj`,
  `efficiency_test_support.clj`, `efficiency_challenge_test.clj` — explicit
  2- and 4-task deployments, two wrappers both writing with the
  `Synchronizable` barrier, spec-derived expectations.
- `test-resources/development/IMPLEMENTATION_VALIDATION.md` — pass.
- `test-resources/development/TEST_VALIDATION.md` — pass, with the mutation
  check and the measured efficiency numbers.

## Receipts

- `clojure -X:test-private-harness` (restored original):
  `Ran 4 tests containing 402 assertions. 0 failures, 0 errors.` (1m53s;
  first green run 1m29s).
- Negative control (lateness `>=` → `>`): `54 failures, 0 errors` out of 402;
  original restored and verified identical by `diff`, then re-run green.
- Rama 1.9.0 finding recorded in the module and in
  IMPLEMENTATION_VALIDATION.md: `sorted-map-range` on a subindexed map
  iterates to the end of the map (104 iterator reads for a 3-window range
  with 100 windows above it); `sorted-map-range-from` + `:max-amt` stops
  exactly. The reference pages with the latter.

## Full-spec review (same day, fresh session)

- `test-resources/development/FULL_SPEC_REVIEW.md` — pass. Module unchanged.
  Efficiency suite trimmed to the README-published bounds (removed
  `get-watermark` / `advance-watermark!` cost assertions and the absolute
  `< 75` / `< 130` ceilings; growth checks and all correctness checks kept).
- `clojure -X:test-private-harness` after the trim:
  `Ran 4 tests containing 388 assertions. 0 failures, 0 errors.` (1m39s).

## Final Oracle review and ownership handoff

Oracle reviewed the complete public contract, reference, private suites and
artifacts after the fresh Fable review. It found no reference correctness
blocker, but two actionable private-test fairness issues remain UNFIXED:

1. `efficiency_test_support.clj`, `within-growth?` and the small-state vs
   grown-state fixture: a valid fixed-page iterator (e.g. page size 128)
   can fail `after <= 2 * before + 20` when the baseline has only three
   windows. Compare two already-populated irrelevant-state sizes (hundreds
   vs several thousand) with fixed outputs, and describe the test as a
   scaling heuristic. Do not add arbitrary reference counts to the public
   contract or restore absolute ceilings.
2. `functional_test_support.clj`, `launch-with` and the module-update test:
   update uses a second `create-module` result, imposing undocumented stable
   module/internal identities across factory calls. Retain the original
   deployed module and use `(rtest/update-module! ipc module)` instead.

Oracle additionally recommends growing the target window's distinct
breakdown pairs before measuring write cost (current growth is unrelated
windows/requests/campaigns), and comparing the complete 153-window expected
vector instead of only its count. These improvements are not implemented.
Read-event instrumentation cannot detect oversized non-subindexed blobs;
module update is not a full process-loss test; retries were not injected.

The parent requested an ownership handoff immediately after this review.
No further implementation or validation was started. Both Fable sessions
exited 0; no active Claude/JVM process or orb service remains. The final
executed suite is still 4 tests / 388 assertions / 0 failures / 0 errors,
not evidence that the outstanding fairness issues have been fixed.
All implementation ownership is relinquished to the parent. Public
README/protocol are ready for final parent reconciliation; no semantic
contradiction was found. No evaluation solve, substitution or push occurred.
Only owned package files are checkpointed; shared template is excluded.

Source: https://hld.handbook.academy/curriculum/case-studies/ad-click-aggregation/
The whole case study was read; README contains attribution and CC BY-SA adapted
prose notice. Two Oracle public-contract reviews informed the public draft.
