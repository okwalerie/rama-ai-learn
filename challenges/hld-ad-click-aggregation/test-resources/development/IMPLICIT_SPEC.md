# Implicit Spec

<!-- Phase 0 artifact for hld-ad-click-aggregation. Requirements only — no
PState, depot, or topology design. Inputs: README.md,
src/hld_ad_click_aggregation/protocol.clj, lib/harness Synchronizable
docstring, skills/rama SKILL.md. -->

## Derived domain facts

These follow from the stated rules and every later phase may rely on them.

- **Click identity is `[campaign-id request-id]`.** Two clicks with the same
  `request-id` under different campaigns are unrelated. Identity is by string
  value, never by timestamp, geo, device, or arrival order.
- **Window arithmetic is fixed.** `window-start = ts - (ts mod 60)`,
  `E = window-start + 60`. Window is closed iff `W >= window-start + 180`.
  Boundary: `ts = 59` → window `0`; `ts = 60` → window `60`. Window `0` closes
  exactly at `W = 180` and is open at `W = 179`. Window `60` closes at `W = 240`
  (worked example steps 6 and 8).
- **Lateness is monotone.** The watermark only moves forward, so a window that
  is closed stays closed forever and a timestamp judged `:late` once would be
  `:late` on every later arrival. The reverse never happens: a window never
  re-opens.
- **A fresh campaign cannot produce `:late`.** With `W = 0` every window has
  `E + 120 >= 180 > 0`. Every new request-id on a never-advanced campaign is
  counted. The first `:late` in a campaign requires `W >= 180`.
- **Timestamps are unbounded relative to the watermark.** `ts` may be any
  non-negative 64-bit integer, arbitrarily far ahead of `W` (step 4:
  `ts = 5000` at `W = 0` → window `4980`). No operation may do work
  proportional to numeric distance between timestamps, window starts, or
  watermark values.
- **Disposition priority is total.** `:late` beats `fraud?`, which beats
  `not valid?`. A click with `fraud? true, valid? false` is `:fraud`. A click
  in a closed window is `:late` regardless of its flags. Exactly one
  disposition per first arrival.
- **Idempotence check precedes disposition.** A replay is decided before the
  lateness check, so a replay of a counted click that arrives after closure
  does not create a `:late` record and does not touch the window (step 10).
  A replay carrying different `timestamp`, `geo`, `device`, `spend`, or flags
  is still a replay: nothing changes.
- **Counters are pure sums over first arrivals.** For any window and any
  breakdown entry: `:clicks = :billed-clicks + :invalid-clicks + :fraud-clicks`;
  `:billed-spend` is the sum of `spend` over `:billed` first arrivals only;
  every key is present (zero, never absent). Window `:totals` equals the
  key-wise sum of all `:breakdown` entries. Every breakdown entry has
  `:clicks >= 1`. All counters are monotone non-decreasing over time.
- **Counting is order-independent; disposition is order-dependent.** The final
  counters of a window do not depend on the order counted clicks arrived. The
  disposition of a click does depend on whether an `advance-watermark!` on the
  same campaign was applied before or after it, and on which arrival of a
  request-id was first. Hence per-campaign write order is part of correctness.
- **Windows and audit records are never removed.** Closure changes nothing
  stored. Closed windows remain readable via `get-window` / `get-windows`
  indefinitely (step 9: window `60` readable after `W = 240`). Audit records
  for every first arrival, including `:late`, persist forever.
- **A window exists for reads iff it has ≥ 1 counted click.** Late-only windows
  and never-touched windows return `nil` / are absent from `get-windows`
  (step 11: `get-window "cmp-1" 120` → `nil`).
- **`spend` semantics.** `spend = 0` on a `:billed` click is billed with
  `:billed-spend + 0`. `spend` on `:invalid`, `:fraud`, or `:late` clicks is
  recorded in the audit record but never summed anywhere. Sums use 64-bit
  arithmetic; overflow is out of scope.
- **Unknown campaign reads are well-defined.** `get-watermark` → `0`,
  `get-request` → `nil`, `get-window` → `nil`, `get-windows` → `[]`.
- **Wall-clock time is never consulted.** All time semantics come from
  `timestamp` and the explicit watermark.

## Global requirements

- **All state is durable Rama state.** Watermarks, audit records, and window
  counters must survive worker restart without depot replay. Nothing a read
  depends on may live only in client memory, a TaskGlobal, or a JVM atom.
- **Multiple clients share one truth.** Tests create a second `wrap-client` on
  the same running cluster. Reads through any client return the same results as
  reads through the writing client. A client created after writes were made
  sees them. Client-local bookkeeping may exist only to support
  `wait-for-processing!` and must never affect read results.
- **`wait-for-processing!` semantics.** On return, every write previously
  issued through that client is applied and visible to reads through any
  client. A second client that issues its own writes after another client's
  writes were already processed must genuinely wait for its own writes, not
  return early because prior work was already counted.
- **Per-campaign total order.** `advance-watermark!` and `record-click!` on
  the same campaign take effect in the order the issuing client invoked them,
  even though they are different operation kinds (`advance-watermark! c 240`
  then `record-click! c "r6" 90 ...` → `:late`; the reverse order → `:billed`).
  Writes from two clients to the same campaign are applied in some single
  serial order, each judged against the state at the moment it is applied. No
  ordering is required across campaigns.
- **Exactly-once effect per write under retries.** If processing of a
  `record-click!` is retried, the audit record is written at most once, and the
  window totals and the `[geo device]` breakdown entry each gain the click at
  most once. The audit record, the totals update, and the breakdown update for
  one click must be applied together: no read may ever observe an audit record
  with `:disposition :billed` whose window does not reflect it, or totals that
  disagree with the sum of the breakdown. `advance-watermark!` applied twice is
  naturally a no-op the second time.
- **Task-count independence.** The harness deploys with 2 or 4 tasks at random.
  Results must be identical for either count. The work bounds below are per
  operation and must not depend on task count. Aggregate write and read
  throughput across many campaigns must grow with task count; no step may
  funnel every campaign through a single task.
- **Campaign isolation.** No read of campaign `A` may read state belonging to
  campaign `B`. No write to campaign `A` may modify or read state of `B`.
- **Never delete data.** No operation deletes a window, a breakdown entry, an
  audit record, or a watermark. The spec has no retention rule.
- **Unbounded collections.** Campaigns; request-ids per campaign (one audit
  record each, including `:late`); windows per campaign (grows with the
  event-time span of counted clicks, never pruned); distinct `[geo device]`
  pairs per window (bounded in practice by geo × device cardinality, unbounded
  in principle). Bounded: one watermark per campaign; five counters per
  totals / breakdown entry.
- **Access patterns that dominate.** Point lookup by `[campaign request-id]`
  (every click, every audit read); point lookup by `[campaign window-start]`
  (every counted click, `get-window`); ordered range by `window-start` within
  one campaign (`get-windows`); point lookup by `campaign` (watermark, every
  click).
- **Write skew.** Click volume is heavily skewed toward a few hot campaigns.
  Because writes within a campaign are serialized, the per-click work bound is
  also the bound on a single campaign's sustainable click rate.

## Operations

### `advance-watermark! [campaign-id watermark]` — write

- **Latency.** Asynchronous; effects visible after `wait-for-processing!`. A
  no-op advance must be near-constant cost.
- **Throughput.** One call per campaign per event-time tick from an upstream
  pipeline; scales with the number of live campaigns. Stale and repeated
  advances are common and must be cheap.
- **Invariants.**
  - Watermark never decreases. `watermark <= current` changes nothing
    observable.
  - After an advance to `w > current`: `get-watermark` → `w`; every window with
    `window-start + 180 <= w` is closed for all subsequent new request-ids;
    every existing window's counters and breakdown are unchanged; every audit
    record is unchanged.
  - Advancing never creates, alters, or removes a window or an audit record.
- **Scale.** One integer per campaign. A single advance may close zero, one, or
  arbitrarily many windows.
- **Work bound.** Constant per call. Must not do work proportional to the
  number of windows closed, windows or requests in the campaign, or the numeric
  distance `w - current` (a jump from `0` to `10^12` costs the same as a jump
  to `180`). Must not touch other campaigns.
- **Concurrency.** Two advances to the same campaign in flight: the larger
  wins, the smaller is a no-op regardless of application order. Interleaved
  with `record-click!` on the same campaign: strict per-campaign order from the
  issuing client.
- **Edge cases.**
  - Fresh campaign, `advance-watermark! c 0`: no-op; campaign still reads as
    unknown (`get-watermark` → `0`, windows and requests absent).
  - Fresh campaign, `advance-watermark! c 1000`: `get-watermark` → `1000`;
    `get-windows c 0 N` → `[]`; `get-request` → `nil` for any id.
  - Advance exactly to `window-start + 180`: window closed. To
    `window-start + 179`: open (steps 6 and 8).
  - Advance that closes a window containing counted clicks: `get-window` still
    returns it with identical contents (step 9).
  - Advance that skips past many windows at once (`0` → `10^9`): every window
    with start `<= 10^9 - 180` is closed; nothing else changes.
  - Advance to the current value: no-op (step 12 with a smaller value; equal
    value is the same case).

### `record-click! [campaign-id request-id timestamp geo device spend valid? fraud?]` — write

- **Latency.** Asynchronous; visible after `wait-for-processing!`.
- **Throughput.** The dominant write. Volume = impressions × click-through
  across all campaigns; bursty; heavily skewed to hot campaigns. Replays from
  at-least-once upstream delivery are routine, not exceptional.
- **Invariants.**
  - Replay (`request-id` already recorded for this campaign): no observable
    change anywhere, regardless of arguments, regardless of window state.
  - New request-id: exactly one audit record is created with all ten keys,
    `:window-start = ts - (ts mod 60)`, `:watermark` = the campaign watermark
    at the moment the write is applied, and `:disposition` decided by the
    ordered rules against that watermark.
  - `:late`: no window changes. Window at `:window-start` is not created if it
    did not exist, and unchanged if it did.
  - `:fraud` / `:invalid` / `:billed`: the window at `:window-start` gains
    `:clicks + 1` and the matching disposition counter `+ 1` in both `:totals`
    and the `[geo device]` breakdown entry; `:billed` additionally adds `spend`
    to `:billed-spend` in both. Nothing else in the window changes. The
    breakdown entry is created on first use with all five keys.
  - The audit record is immutable thereafter.
- **Scale.** Duplicate detection is against the entire history of request-ids
  in the campaign (unbounded, never pruned).
- **Work bound.** Bounded per click, independent of how many clicks, windows,
  or campaigns exist: the replay check, the watermark read, the audit write,
  the totals update, and the breakdown update must each be constant-cost with
  respect to campaign size and window size. The replay check must not scan
  request-ids. Must not touch other campaigns.
- **Concurrency.** Two clicks with the same `[campaign request-id]` from
  different clients: the first applied wins, the second is a replay, whichever
  client sent it. Many clicks into the same window and same `[geo device]` from
  different clients: every one is counted exactly once; no lost updates.
  Clicks for different campaigns are independent.
- **Edge cases.**
  - Fresh campaign, any `ts`: never `:late`; a `:billed` click on a fresh
    campaign yields audit `:watermark 0` (step 1).
  - `ts = 0` → window `0`. `ts = 59` → window `0`. `ts = 60` → window `60`.
  - `ts` far ahead of `W` (step 4): counted normally into a far-future window;
    that window is returned by `get-windows` ranges that include it.
  - `spend = 0` with `valid? true, fraud? false`: `:billed`; `:billed-clicks`
    increments; `:billed-spend` unchanged.
  - `fraud? true, valid? false`: `:fraud`, not `:invalid`.
  - Closed window with `fraud? true`: `:late`, not `:fraud`; audit record
    still stores `:fraud? true`.
  - Click applied when `W == window-start + 180` exactly: `:late`. When
    `W == window-start + 179`: counted (step 7 at `W = 239` into window `60`).
  - Several counted clicks with the same `[geo device]` into one window: one
    breakdown entry accumulates all of them (step 7).
  - First counted click into a window creates it with `:totals` equal to the
    single breakdown entry.
  - Replay with different arguments (different `ts`, `spend`, flags): no
    effect; `get-request` returns the original arguments.
  - Replay after window closure (step 10): no effect; audit `:watermark` stays
    at the original value.
  - Replay of a `:late` record: no effect; no second `:late` record.
  - Same `request-id` in another campaign (step 13): independent first
    arrival, counted in that campaign only.
  - Late click into a window that never had counted clicks: `get-window` for
    that window stays `nil`; `get-request` returns the `:late` record with its
    `:window-start`.

### `get-watermark [campaign-id]` — read

- **Latency.** Single-digit milliseconds; one integer.
- **Throughput.** Monitoring and pipeline coordination reads; low relative to
  clicks but must not interfere with them.
- **Invariants.** Returns the current watermark; `0` for a campaign that has
  never been written or has only received advances `<= 0`. Monotone
  non-decreasing over the campaign's life. Unaffected by `record-click!`.
- **Scale.** One value per campaign.
- **Work bound.** Constant; reads only this campaign.
- **Edge cases.** Unknown campaign → `0`. Campaign with clicks but no advance
  → `0`. Campaign with only a stale advance (`advance-watermark! c 0`) → `0`.

### `get-request [campaign-id request-id]` — read

- **Latency.** Single-digit milliseconds; constant amount of data.
- **Throughput.** Audit and dispute lookups, reconciliation jobs that may issue
  many point lookups; each must be independent and cheap.
- **Invariants.**
  - Returns `nil` iff no `record-click!` with this `[campaign request-id]` has
    been applied.
  - Otherwise returns exactly the first arrival's ten fields:
    `:request-id :timestamp :window-start :geo :device :spend :valid? :fraud?
    :disposition :watermark`. Never changes after creation.
  - `:watermark` is the campaign watermark at apply time of the first arrival,
    not the current watermark (step 10: `:watermark 0` while
    `get-watermark` → `240`).
  - `:window-start` is present for every disposition, including `:late`.
- **Scale.** One record per distinct request-id per campaign; unbounded total.
- **Work bound.** Constant; must not read anything proportional to the number
  of other requests, windows, or campaigns.
- **Edge cases.**
  - Unknown campaign → `nil`. Known campaign, unknown request-id → `nil`.
  - Request-id recorded in a different campaign only → `nil` for this campaign.
  - `:late` record (step 9): full map with `:disposition :late` and the
    watermark that closed it.
  - Record for a click whose window has since closed: unchanged.

### `get-window [campaign-id window-start]` — read

- **Latency.** Single-digit milliseconds; one window including its full
  breakdown.
- **Throughput.** Dashboard and billing reads; frequent for recent windows of
  hot campaigns; must not interfere with click ingestion.
- **Invariants.**
  - `nil` iff no click has been counted into the window (`:billed`,
    `:invalid`, or `:fraud` first arrival with that `:window-start`).
  - Otherwise `{:window-start s :totals <counters> :breakdown {[geo device]
    <counters> ...}}` with exactly one breakdown entry per distinct
    `[geo device]` pair counted into the window, all five counter keys present
    in every counters map, `:totals` equal to the key-wise sum of the
    breakdown, and `:clicks` equal to the sum of the three disposition counts.
  - Late clicks and replays never appear.
  - Closed and open windows are returned identically; closure is not visible in
    the result.
- **Scale.** Breakdown size = distinct `[geo device]` pairs counted into the
  window; typically tens to low thousands, unbounded in principle. The result
  always includes the whole breakdown.
- **Work bound.** Reads one window of this campaign; cost proportional to that
  window's breakdown size plus a constant. Must not touch other windows or
  campaigns.
- **Concurrency.** Reads see a consistent window: `:totals` and `:breakdown`
  reflect the same set of applied clicks; a read never observes a click in one
  and not the other.
- **Edge cases.**
  - Unknown campaign → `nil`. Window start with no counted clicks → `nil`
    (step 11: `120`).
  - Window that received only `:late` clicks → `nil`.
  - Window with a single counted click: totals equal the one breakdown entry.
  - Window with counted clicks all `:invalid` or `:fraud`: `:billed-clicks 0`,
    `:billed-spend 0`, `:clicks > 0`.
  - Far-future window (step 4, `4980`): returned like any other.
  - Read after closure (step 9): identical to read before closure.

### `get-windows [campaign-id start end]` — read

- **Latency.** Single-digit milliseconds for small result sets; one request
  must not require client↔cluster roundtrips proportional to the number of
  windows returned.
- **Throughput.** Dashboard time-range reads over one campaign; ranges may be
  wide (a day of 60-unit windows) or span a huge numeric range with few windows.
- **Invariants.**
  - Returns a vector of exactly the maps `get-window` would return for every
    window with `start <= window-start < end` that has ≥ 1 counted click,
    ascending by `:window-start`.
  - Each element is identical to the corresponding `get-window` result at the
    same instant.
  - `[]` for unknown campaign, `start == end`, or no qualifying windows.
  - Half-open: a window whose start equals `end` is excluded; a window whose
    start equals `start` is included (step 11: `0 120` returns window `60`;
    `0 6000` returns `60` and `4980`).
- **Scale.** Result size = non-empty windows of this campaign in the range.
  The numeric range may be vastly larger than the number of windows
  (`0 .. 10^12` with two windows).
- **Work bound.** Proportional to this campaign's non-empty windows inside
  `[start, end)` plus a constant, plus the breakdown sizes of those windows.
  Must be able to locate `start` directly rather than scan from the campaign's
  first window; must not iterate empty 60-unit slots across the numeric range;
  must not touch windows outside the range or other campaigns.
- **Concurrency.** Consistent per window as in `get-window`. A click applied
  concurrently with the range read appears in the result or not, never
  partially.
- **Edge cases.**
  - `start == end` → `[]`. `end` equal to a window's start excludes it; `end`
    equal to a window's start `+ 60` includes it.
  - Range covering only late-only windows → `[]`.
  - Range spanning both closed and open windows: all included, in order.
  - Sparse windows across a wide range (step 11): only the populated ones.
  - Range entirely above every populated window → `[]`.

### `wait-for-processing!` — synchronization (harness `Synchronizable`)

- **Invariant.** On return, all writes issued through this client before the
  call are applied, in per-campaign order, and visible to reads from any
  client.
- **Edge cases.** Called with no prior writes (returns immediately). Called on
  a second client after another client wrote and waited. Called on a second
  client after it issued its own writes while the first client's writes were
  already processed — must still block until its own writes are applied.

## Entity State × Write Matrix

Read operations: `wm` = `get-watermark`, `req` = `get-request` (for the
relevant request-id), `win` = `get-window` (for the relevant window),
`wins` = `get-windows` (over a range containing the relevant window).

### Entity: Campaign

States: **never-written** (watermark 0, no requests, no windows);
**written** (at least one applied write; watermark ≥ 0; may have no windows).

```
never-written x advance-watermark! w, w == 0
  - wm:   0 (no-op; indistinguishable from never-written)
  - req:  nil for every id
  - win:  nil for every window
  - wins: []

never-written x advance-watermark! w, w > 0
  - wm:   w
  - req:  nil for every id
  - win:  nil for every window
  - wins: []
  (state -> written; windows with start + 180 <= w are closed before ever
   receiving a click)

never-written x record-click! r ts ... (any flags)
  - wm:   0
  - req:  record with :watermark 0 and :disposition :fraud / :invalid /
          :billed per flags (never :late, since W = 0)
  - win:  window ts - (ts mod 60) created with one counted click
  - wins: [that window] for ranges containing it
  (state -> written)

written x advance-watermark! w, w <= current
  - wm:   unchanged
  - req:  unchanged for every id
  - win:  unchanged for every window
  - wins: unchanged

written x advance-watermark! w, w > current
  - wm:   w
  - req:  unchanged for every id (stored :watermark values do not move)
  - win:  unchanged for every window (closure stores nothing)
  - wins: unchanged

written x record-click! new id, window open at current W
  - wm:   unchanged
  - req:  new record, :watermark = current W, disposition per flags
  - win:  window gains the click in totals and its [geo device] entry
  - wins: window present (created if this is its first counted click)

written x record-click! new id, window closed at current W
  - wm:   unchanged
  - req:  new record, :disposition :late, :watermark = current W
  - win:  unchanged (nil stays nil)
  - wins: unchanged

written x record-click! replayed id (any args, any window state)
  - wm:   unchanged
  - req:  original record unchanged
  - win:  unchanged
  - wins: unchanged
```

### Entity: Request (campaign × request-id)

States: **absent** (never recorded); **counted** (recorded with `:billed`,
`:invalid`, or `:fraud`); **late** (recorded with `:late`).

```
absent x record-click! r, window open, valid? true, fraud? false
  - wm:   unchanged
  - req:  {... :disposition :billed :watermark W}
  - win:  window :clicks + 1, :billed-clicks + 1, :billed-spend + spend in
          totals and in [geo device] entry
  - wins: window present
  (state -> counted)

absent x record-click! r, window open, fraud? true (valid? either)
  - wm:   unchanged
  - req:  {... :disposition :fraud :watermark W}
  - win:  :clicks + 1, :fraud-clicks + 1; :billed-spend unchanged
  - wins: window present
  (state -> counted)

absent x record-click! r, window open, fraud? false, valid? false
  - wm:   unchanged
  - req:  {... :disposition :invalid :watermark W}
  - win:  :clicks + 1, :invalid-clicks + 1; :billed-spend unchanged
  - wins: window present
  (state -> counted)

absent x record-click! r, window closed (any flags)
  - wm:   unchanged
  - req:  {... :disposition :late :watermark W}, flags stored as supplied
  - win:  unchanged
  - wins: unchanged
  (state -> late)

absent x advance-watermark! (any)
  - wm:   advanced if larger
  - req:  nil
  - win:  unaffected by this request
  - wins: unaffected by this request
  (state stays absent; a later first arrival may now be :late)

counted x record-click! r (same or different args, window open or closed)
  - wm:   unchanged
  - req:  original record, original :watermark
  - win:  unchanged
  - wins: unchanged
  (state stays counted)

counted x advance-watermark! (any, including one that closes the window)
  - wm:   advanced if larger
  - req:  unchanged (stored :watermark is the original apply-time value)
  - win:  unchanged; still includes this click
  - wins: unchanged
  (state stays counted)

late x record-click! r (any args)
  - wm:   unchanged
  - req:  original :late record
  - win:  unchanged
  - wins: unchanged
  (state stays late; there is no second :late record)

late x advance-watermark! (any)
  - wm:   advanced if larger
  - req:  unchanged
  - win:  unchanged
  - wins: unchanged
  (state stays late)
```

### Entity: Window (campaign × window-start)

States: **empty-open** (no counted click, `W < start + 180`); **populated-open**
(≥ 1 counted click, `W < start + 180`); **empty-closed** (no counted click,
`W >= start + 180`); **populated-closed** (≥ 1 counted click,
`W >= start + 180`).

```
empty-open x record-click! new id in this window (counted disposition)
  - wm:   unchanged
  - req:  new counted record
  - win:  {:window-start s :totals <one click> :breakdown {[geo device] <one click>}}
  - wins: window now present, in sorted position
  (state -> populated-open)

empty-open x record-click! replayed id whose first arrival was elsewhere
  - wm:   unchanged
  - req:  original record (may point at a different window)
  - win:  nil
  - wins: absent
  (state stays empty-open)

empty-open x advance-watermark! w, w < start + 180
  - wm:   w
  - req:  unaffected
  - win:  nil
  - wins: absent
  (state stays empty-open)

empty-open x advance-watermark! w, w >= start + 180
  - wm:   w
  - req:  unaffected
  - win:  nil
  - wins: absent
  (state -> empty-closed; the window can never become populated)

populated-open x record-click! new id in this window (counted disposition)
  - wm:   unchanged
  - req:  new counted record
  - win:  totals and the [geo device] entry updated; a new pair adds an entry
  - wins: window present with updated contents
  (state stays populated-open)

populated-open x record-click! replayed id
  - wm:   unchanged
  - req:  original record
  - win:  unchanged
  - wins: unchanged
  (state stays populated-open)

populated-open x advance-watermark! w, w < start + 180
  - wm:   w
  - req:  unaffected
  - win:  unchanged
  - wins: unchanged
  (state stays populated-open; step 6)

populated-open x advance-watermark! w, w >= start + 180
  - wm:   w
  - req:  unaffected
  - win:  unchanged
  - wins: unchanged
  (state -> populated-closed; step 8)

empty-closed x record-click! new id in this window (any flags)
  - wm:   unchanged
  - req:  new :late record with :window-start s
  - win:  nil
  - wins: absent
  (state stays empty-closed)

empty-closed x record-click! replayed id
  - wm:   unchanged
  - req:  original record
  - win:  nil
  - wins: absent
  (state stays empty-closed)

empty-closed x advance-watermark! (any)
  - wm:   advanced if larger
  - req:  unaffected
  - win:  nil
  - wins: absent
  (state stays empty-closed)

populated-closed x record-click! new id in this window (any flags)
  - wm:   unchanged
  - req:  new :late record
  - win:  unchanged (step 9)
  - wins: unchanged
  (state stays populated-closed)

populated-closed x record-click! replayed id
  - wm:   unchanged
  - req:  original record with original :watermark (step 10)
  - win:  unchanged
  - wins: unchanged
  (state stays populated-closed)

populated-closed x advance-watermark! (any)
  - wm:   advanced if larger
  - req:  unaffected
  - win:  unchanged; remains readable forever
  - wins: unchanged
  (state stays populated-closed)
```

### Entity: Breakdown entry (campaign × window-start × [geo device])

States: **absent** (no counted click with this pair in this window);
**present** (≥ 1 counted click; `:clicks >= 1`).

```
absent x record-click! new id, this pair, counted disposition
  - wm:   unchanged
  - req:  new counted record with this geo and device
  - win:  :breakdown gains {[geo device] <one click>}; :totals gains the same
  - wins: window reflects the new entry
  (state -> present)

absent x record-click! new id, this pair, :late
  - wm:   unchanged
  - req:  :late record with this geo and device
  - win:  :breakdown unchanged; entry not created
  - wins: unchanged
  (state stays absent)

absent x record-click! replayed id (any pair)
  - wm:   unchanged
  - req:  original record
  - win:  unchanged
  - wins: unchanged
  (state stays absent)

absent x advance-watermark! (any)
  - wm:   advanced if larger
  - req:  unaffected
  - win:  entry still absent
  - wins: unchanged
  (state stays absent)

present x record-click! new id, this pair, counted disposition
  - wm:   unchanged
  - req:  new counted record
  - win:  entry counters and :totals each gain the click (step 7)
  - wins: window reflects the update
  (state stays present)

present x record-click! new id, this pair, :late
  - wm:   unchanged
  - req:  :late record
  - win:  entry unchanged
  - wins: unchanged
  (state stays present)

present x record-click! replayed id
  - wm:   unchanged
  - req:  original record
  - win:  entry unchanged
  - wins: unchanged
  (state stays present)

present x advance-watermark! (any)
  - wm:   advanced if larger
  - req:  unaffected
  - win:  entry unchanged; never removed
  - wins: unchanged
  (state stays present)
```
