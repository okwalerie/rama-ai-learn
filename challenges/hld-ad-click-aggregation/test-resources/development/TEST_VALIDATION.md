# Test Validation

<!-- Phase 6 artifact for hld-ad-click-aggregation (build phase). Reviewed:
test-private/hld_ad_click_aggregation/{functional,efficiency}_test_support.clj and the two
*_challenge_test.clj drivers. Authority: README.md + protocol docstrings; IMPLICIT_SPEC.md
used as the edge-case checklist. Default verdict was major-fail until each check was
walked through. Decision / Basis / Outcome summaries only. -->

## Minimize IPC launches
- Four launches total, each required by README "The module runs with both 2 and 4 tasks
  in private validation": `functional-2-tasks-challenge-test`,
  `functional-4-tasks-challenge-test`, `efficiency-2-tasks-challenge-test`,
  `efficiency-4-tasks-challenge-test`. Each deploys explicitly with
  `{:tasks n :threads 2}` (no random task count).
- Functional and efficiency are separate launches because the efficiency suite compares
  operation counts between a controlled small state and a controlled grown state; the
  functional suite's ~25 campaigns of writes would be uncontrolled background growth on
  the same cluster. Within each suite every scenario uses disjoint campaign ids and
  shares one cluster. PASS.
- Wall clock of the whole suite: ~1.5-2 min (`clojure -X:test-private-harness`:
  1m29s first green run, 1m53s final run).

## Implicit spec coverage
Each IMPLICIT_SPEC.md edge case / matrix row → the `testing` block in
`functional_test_support.clj` (F) or `efficiency_test_support.clj` (E) that exercises it.

Derived facts / boundaries
- Click identity `[campaign request-id]` → F "README worked example" step 13, F
  "campaign isolation" (`same` in `iso-a` and `iso-b` with different bodies).
- Window arithmetic `ts 59 → 0`, `ts 60 → 60`, `119 → 60`, `120 → 120` → F "window
  membership at 60-unit boundaries".
- Window `0` closes exactly at `W = 180`, open at `179`; window `60` at `240` / `239` →
  F "exact lateness cutoff" (clicks `a` at 179, `c` at 180, `e` at 239, `f` at 240), F
  worked example steps 6-9.
- Lateness monotone / no re-open → F "exact lateness cutoff" (`h` into window 360 after
  the jump to 600), F "audit records are immutable" (`k2` late after 10^12).
- Fresh campaign never `:late` → F worked example steps 1-4 (`:watermark 0`), F "campaign
  isolation" (`iso-b` at watermark 0).
- Timestamps far ahead of the watermark → F worked example step 4 (`4980`), F "audit
  records are immutable" (`k3` at 10^12 into window 999999999960), F "signed 64-bit
  limits".
- Disposition priority `:late` > `fraud?` > `not valid?` → F "disposition precedence"
  (`f1`: fraud + invalid → `:fraud`), F "exact lateness cutoff" (`f`: closed + fraud →
  `:late` with `:fraud? true` stored), F "two wrappers writing the same window"
  (`a-0`, `b-77`, `a-7`).
- Idempotence check precedes disposition → F "replays with conflicting bodies" (`x`
  replayed after closure keeps `:billed`/`:watermark 0`; `y` late record replayed with a
  far-future timestamp changes nothing).
- Counters are pure sums; all five keys present; totals = Σ breakdown; every entry
  `:clicks ≥ 1` → `consistent-window?` in F "two wrappers writing the same window" and
  F "a window with more than 100 distinct pairs"; exact literal maps everywhere else.
- Windows and audit records never removed; closed windows readable → F worked example
  step 9-11, F "audit records are immutable" (window 960 after 10^12), F "exact lateness
  cutoff" (`wins* 0 660` lists closed windows 0, 60, 240).
- Window exists iff ≥ 1 counted click → F "exact lateness cutoff" (window 360 nil after
  a late click; `wins* 360 480` empty), F worked example step 11 (`120` nil).
- `spend` semantics → F "disposition precedence" (`b0` spend 0 billed; fraud/invalid
  spend 100 never summed).
- Unknown campaign reads → F "unknown campaign reads" (all four reads, both wrappers,
  plus known campaign / unknown request-id).

Global requirements
- Durable state / no client atoms → F "durable state survives a simulated worker
  restart" (`rtest/update-module!` then reads + further writes incl. a replay and a late
  click); F "a wrapper created after the writes" (fresh wrapper reads 300 clicks it never
  wrote).
- Multiple clients share one truth → every F block alternates writes via `ca` / reads
  via `cb` and the reverse; both wrappers write in "two wrappers writing the same
  window".
- `wait-for-processing!` second-wrapper barrier → F "a wrapper created after the writes;
  second-wrapper write barrier" (A writes 301 records and syncs; new wrapper C writes
  200 records, syncs, and immediately reads all 200; then C's advance closes A's window
  960 exactly and A/B observe it). Wait with no prior writes → `(sync! cb)` at the top of
  the suite.
- Per-campaign total order across write kinds within one phase → F "same-campaign
  writes take effect in client order" (advance→click late; click→advance→click billed
  then late; stale advance in the middle; advance that closes a window exactly at
  `start + 180` mid-phase), F worked example steps 8-10 in one phase.
- Exactly-once under retries → not forceable from tests; argued in
  IMPLEMENTATION_VALIDATION.md (microbatch). No lost updates with two writers → F "two
  wrappers writing the same window" (200 clicks, three pairs, spec-derived expectation).
- Task-count independence → both suites run at 2 and at 4 tasks with identical
  expectations.
- Campaign isolation → F "campaign isolation" (advance on A leaves B's watermark and
  dispositions alone; A's window invisible under B), E (12 other campaigns untouched by
  R's 10^12 advance).
- Unbounded collections → F "a window with more than 100 distinct pairs" (130 breakdown
  entries), F barrier block (300 + 200 requests), E (153 windows, ~260 requests on one
  campaign).

Operation edge cases
- `advance-watermark!`: fresh campaign `0` no-op and still unknown (F "watermark
  monotonicity"); fresh campaign `1000` sets only the watermark (F "watermark
  monotonicity", `wins* []`, `req* nil`); equal / smaller no-op (F "watermark
  monotonicity", F worked example step 12); exactly `start + 180` closes, `+ 179` open
  (F "exact lateness cutoff"); closing a populated window leaves it readable (F worked
  example step 9); jump past many windows (F "exact lateness cutoff" 600, F "audit
  records are immutable" 10^12, E jump closing 153 windows); advance to
  `Long/MAX_VALUE` (F "signed 64-bit limits"); never creates a window (F "watermark
  monotonicity").
- `record-click!`: fresh campaign `ts 0` (F "window membership", F "campaign
  isolation"); far-future `ts` (above); `spend 0` billed (F "disposition precedence");
  `fraud? true, valid? false` → `:fraud` (F "disposition precedence"); closed + fraud →
  `:late` (F "exact lateness cutoff" `f`); `W == start + 180` late / `+ 179` counted (F
  "exact lateness cutoff"); many clicks same pair (F "two wrappers", F barrier block);
  first click creates the window with totals = the entry (every `one-click-window`
  assertion); replay with different args (F "replays"); replay after closure (F worked
  example step 10, F "replays"); replay of a late record (F "replays" `y`); replay
  inside the same phase (F "replays" `z`); same id in another campaign (F worked example
  step 13); late click into a never-populated window (F "exact lateness cutoff" `h`).
- `get-watermark`: unknown → 0; clicks but no advance → 0 (F "window membership");
  only a stale advance → 0 (F "watermark monotonicity"); `Long/MAX_VALUE` (F "signed
  64-bit limits").
- `get-request`: unknown campaign / unknown id / id recorded only in another campaign
  (F "unknown campaign reads", F "campaign isolation" `l` vs `same`); `:late` record with
  the closing watermark (F "exact lateness cutoff" `c`, `h`); record unchanged after
  closure (F "audit records are immutable").
- `get-window`: unknown campaign nil; no-counted-click nil; late-only window nil; single
  click; all-invalid / all-fraud window (F "disposition precedence" window 1200);
  far-future window (F worked example step 4); read after closure identical (F "audit
  records are immutable"); highest 60-aligned start `Long/MAX_VALUE - 7` must not
  overflow (F "signed 64-bit limits").
- `get-windows`: `start == end` (F "window membership", F "signed 64-bit limits"); `end`
  equal to a window start excludes / `+ 60` includes (F "window membership"); late-only
  range `[]` (F "exact lateness cutoff" `360 480`); closed and open windows in order (F
  "exact lateness cutoff" `0 660`); sparse wide range (F worked example step 11, F
  "audit records are immutable" `0 1000000000020`); range above every window (F "window
  membership" `180 6000`, F "signed 64-bit limits"); range up to the highest aligned
  bound (F "signed 64-bit limits" `0 TOP-ALIGNED`, F "a window with more than 100
  distinct pairs").
- `wait-for-processing!`: no prior writes; second wrapper after another wrote and
  waited; second wrapper's own writes (F barrier block). `Synchronizable` asserted with
  `satisfies?`.

Entity × write matrix: every Campaign / Request / Window / Breakdown-entry row maps to
at least one assertion above (never-written × advance 0 / advance > 0 / click → F
"watermark monotonicity", F "campaign isolation"; written × stale / larger advance →
F "watermark monotonicity", F "audit records are immutable"; written × click open /
closed / replayed → F "exact lateness cutoff", F "replays"; request absent / counted /
late × click / advance → F "replays", F "audit records are immutable"; window
empty-open / populated-open / empty-closed / populated-closed × click / replay / advance
→ F "exact lateness cutoff" (`h`, `i`, `f`, `g`), F barrier block (`after`, `after2`);
breakdown entry absent / present × counted / late / replay / advance → F "disposition
precedence", F "exact lateness cutoff", F "two wrappers writing the same window").

Efficiency contract (README) → E:
- Costs of `get-request`, `get-watermark`, `get-window`, `get-windows` (3 windows),
  counted / late / replayed `record-click!` (+ sync) and stale / effective
  `advance-watermark!` (+ sync) are compared between a small state and a grown state
  where only irrelevant data was added: 100 populated windows above the range and 50
  below on the same campaign, 100 late-only audit records on the same campaign, and 12
  other campaigns × 40 clicks. Relevant results are asserted equal before and after
  growth (outside the captures) with expectations from the spec-derived helper
  `expected-windows`, independent of the module.
- Bound: `after <= 2 * before + 20` RocksDB read-side ops (reads + iterator seeks +
  iterator steps) — generous for any layout, but a scan of the campaign's request map
  (+260), its whole window map (+150), or other campaigns (+480) fails it. No exact
  counts, no topology type or PState naming is assumed; only the event kinds are counted.
- Additionally `get-window` and `get-windows` `< 75` ops with 150 out-of-range windows
  on the campaign, and `get-request` `< 130` with ~260 other requests (half the
  irrelevant entry count: a whole-map scan costs at least that many iterator steps).
- An advance that closes all 153 windows must cost no more than the growth bound over a
  plain advance (work must not scale with windows closed or numeric distance).
- Actual reference numbers (4 tasks, RocksDB read-side ops, small state → grown
  state): `get-request` 2 → 2, `get-watermark` 1 → 1, `get-window` 15 → 15,
  `get-windows` (3 windows) 29 → 31, counted click 8 → 8, late click 5 → 5, replay
  2 → 2, stale advance 1 → 1, effective advance 3 → 3; the 10^12 jump closing 153
  windows cost 3. The pre-fix draft (single `sorted-map-range` read) measured
  `get-window` 12 → 112 and `get-windows` 26 → 127 at 2 tasks and failed the suite,
  which is what led to the paged range read in the reference.

## Synchronization
Walked every write→read sequence in both files: each `click*` / `adv*` group is
followed by `(sync! <the writing wrapper>)` before any `wm*` / `req*` / `win*` /
`wins*`. The only reads without an immediately preceding sync are reads of state already
synced by another wrapper (e.g. `cc` reading `bar-a` right after `ca` synced; `cb` in
"unknown campaign reads"), which is exactly the multi-client guarantee under test. In E,
`(sync! c)` follows every write group, including inside the write captures. PASS.

## Test namespaces compile
- `load-file` of both support namespaces at the REPL → no errors; the drivers resolve
  the implementation with `requiring-resolve` of
  `hld-ad-click-aggregation.module/create-module`, so the same tests run against agent
  implementations (`:test-private`) and the reference (`:test-private-harness`).
- `clj-kondo --lint test-private` → `errors: 0, warnings: 0`.
- Tests depend only on the public protocol namespace, the harness, and
  `com.rpl.rama.test`. PASS.

## Mutation check (deliberately wrong variant)
Variant: in `disposition`, `(>= wm (+ ws WINDOW-SIZE LATENESS-ALLOWANCE))` replaced by
`(> wm ...)` — the plausible off-by-one "closed once the watermark passes `E + 120`"
instead of "reaches it". Suite result (`clojure -X:test-private-harness`, unchanged
tests):

```
Ran 4 tests containing 402 assertions.
54 failures, 0 errors.
```

(27 distinct assertions × 2 task counts, in the worked example, "exact lateness
cutoff", "replays with conflicting bodies", "signed 64-bit limits", "same-campaign
writes", and the barrier and restart blocks — every place a click lands exactly at
`W == start + 180`.) Original restored (`diff` against the saved copy: identical) and the
suite re-run green (below).

## Actual suite output (reference, restored original)

```
Testing hld-ad-click-aggregation.efficiency-challenge-test
Testing hld-ad-click-aggregation.functional-challenge-test
Ran 4 tests containing 402 assertions.
0 failures, 0 errors.
```

## Limitations
- RocksDB events carry no payload sizes, so a design that stores a whole campaign (all
  requests and windows) as one non-subindexed blob and filters in memory shows a
  constant read count and is not caught by the efficiency suite; only scan growth
  visible as reads / iterator steps is caught.
- Microbatch retry / exactly-once cannot be forced from tests; retry safety is argued in
  IMPLEMENTATION_VALIDATION.md.
- Cross-client concurrent interleavings are not tested (the README requires none); the
  two-writer scenario only checks that every write is counted once after both
  synchronize.
- Spend / count overflow is out of scope per the README input assumptions.

## Verdict

`pass` — every protocol method, every IMPLICIT_SPEC edge case and matrix row, the
efficiency contract, multi-client visibility with both wrappers writing, explicit 2- and
4-task deployment, the signed-64-bit boundary, and the restart scenario are exercised
with independently derived expectations.

PHASE_VALIDATION:pass
