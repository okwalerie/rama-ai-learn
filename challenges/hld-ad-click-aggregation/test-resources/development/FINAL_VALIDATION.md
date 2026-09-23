# Final handoff validation (2026-09-23)

The reference module is unchanged from the transferred archive (SHA-256
`7ca80f9303587896beda9fe373c61099aedd292650bd87d6911487cf487bbed2`).
README, protocol and deps match the archive's contract manifest. Only the
existing private harness was changed: the update uses the original deployed
module rather than invoking the factory again; the range-growth fixture
compares 700 and 2900 already-populated out-of-range windows with the same
three-window result; a separate counted-write fixture grows the target
window from 128 to 512 distinct pairs; and the post-closure read compares
the full expected vector of all 153 window maps, not only its length.

From `challenges/hld-ad-click-aggregation`:

```
clojure -J-Xmx1600m -X:test-private-harness
Testing hld-ad-click-aggregation.efficiency-challenge-test
Testing hld-ad-click-aggregation.functional-challenge-test
Ran 4 tests containing 400 assertions.
0 failures, 0 errors.
```

Restored-after-mutations run: exit 0, wall 101.21 s. Both drivers resolve
`hld-ad-click-aggregation.module/create-module`; `:test-private-harness`
includes `test-resources` for the reference, whereas `:test-private` does
not. Each driver explicitly runs 2 and 4 task deployments. `clj-kondo
--lint` of both support namespaces reported 0 errors and 0 warnings.

Negative controls, with the original reference restored after each:

1. Replace bounded `sorted-map-range-from` max amount `*page` with `3000`.
   Read output remained correct, but out-of-range reads scaled from 727 to
   2948 events (700 to 2900 irrelevant windows). Targeted efficiency run:
   2 tests, 80 assertions, **4 failures, 0 errors** (range and single-window
   growth for both task counts), exit 1.
2. Change returned `:billed-spend` to zero only for window 12000 (one of the
   153 stored maps, outside the narrow range). Targeted efficiency run:
   2 tests, 80 assertions, **2 failures, 0 errors** (full-vector equality
   for both task counts), exit 1. The former count-only check would pass.

The first growth comparison is a heuristic, not a complete proof of the
README's architecture-neutral asymptotic contract: an unusually large but
fixed iterator page could cross even these fixture sizes. RocksDB event
counts omit value payload bytes, so a single huge non-subindexed blob is not
excluded. The module update exercises retained Rama state but does not
inject process loss or retry. These limitations are not hidden by the green
suite. No benchmark solving evaluation or deployment was run.
