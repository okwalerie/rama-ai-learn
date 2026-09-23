# HLD Ad-Click Aggregation Challenge

Build a per-campaign click accounting module: every click is recorded
exactly once with a frozen disposition, counted into 60-unit tumbling
windows with a joint geo/device breakdown, and billed only when valid and
not fraudulent. Window closure is driven by an explicit per-campaign
watermark.

## Attribution

This challenge is adapted from the case study
["Design Ad-Click Aggregation"](https://hld.handbook.academy/curriculum/case-studies/ad-click-aggregation/)
by The HLD Handbook contributors
([handbook-academy/engineering-handbook](https://github.com/handbook-academy/engineering-handbook)),
licensed under [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/).
The prose in this README is adapted from that case study and is likewise
offered under CC BY-SA 4.0.

### What was changed

- Event time and lateness are modelled with an explicit, monotonic,
  per-campaign watermark instead of wall-clock processing time. The 1-minute
  tumbling window becomes a 60-unit window; the lateness allowance is 120
  units past window end.
- Exactly-once semantics are expressed as campaign-scoped request-ID
  idempotence with an immutable first disposition, not as a
  producer/checkpoint/transactional-sink chain.
- Invalid-traffic detection is out of scope; `valid?` and `fraud?` arrive as
  caller-supplied flags. The case study's principle is kept: tagged clicks
  are counted and audited but never billed.
- Breakdown dimensions are reduced to the joint `[geo device]` pair. Spend
  is an integer.

### What was excluded

Kafka/Flink mechanics, rules/velocity/ML fraud layers, impressions and CTR,
budget pacing, dashboards and OLAP stores, batch reconciliation against raw
logs, retention periods, privacy/attribution constraints, and all capacity
numbers.

## Domain model

**Campaign scope.** Every write and read is scoped by `campaign-id`. Request
IDs are unique within a campaign; the same `request-id` under two campaigns
is two independent clicks.

**Watermark.** Each campaign has an integer watermark, initially `0`.
`advance-watermark!` is monotonic: a value `<=` the current watermark is a
no-op.

**Window.** A click with timestamp `ts` belongs to the window starting at
`ts - (ts mod 60)` with end `E = start + 60`. The window is **open** while
`watermark < E + 120` and **closed** once `watermark >= E + 120`. Timestamps
ahead of the watermark are admitted normally.

**Disposition.** For a new `request-id`, checks run in this order against
the watermark `W` observed when the write is applied:

| Order | Condition | Disposition | Counted | Billed |
|---|---|---|---|---|
| 1 | `W >= E + 120` (window closed) | `:late` | no | no |
| 2 | `fraud?` true | `:fraud` | yes | no |
| 3 | `valid?` false | `:invalid` | yes | no |
| 4 | otherwise | `:billed` | yes | yes |

A replayed `request-id` (any later `record-click!` with the same campaign and
request ID) has no effect at all, regardless of its arguments and regardless
of whether the window has since closed. The first disposition is permanent.

**Counters.** A counters map is
`{:clicks :billed-clicks :invalid-clicks :fraud-clicks :billed-spend}`.
`:clicks` is the sum of the three counted dispositions; `:billed-spend` sums
`spend` over `:billed` clicks only. Each window has `:totals` and a
`:breakdown` keyed by `[geo device]` with the same counters.

**Audit.** `get-request` returns the immutable record of the first arrival,
including the watermark observed at that moment. Late clicks are audited
exactly like counted ones; they simply never touch a window.

## Worked example

Campaign `"cmp-1"`, watermark `0`.

1. `record-click! "cmp-1" "r1" 100 "US" "mobile" 30 true false` → `:billed`, window `60`.
2. `record-click! "cmp-1" "r2" 110 "US" "desktop" 20 false false` → `:invalid`.
3. `record-click! "cmp-1" "r3" 119 "DE" "mobile" 50 true true` → `:fraud`.
4. `record-click! "cmp-1" "r4" 5000 "US" "mobile" 10 true false` → `:billed`, window `4980`
   (future timestamps are admitted).
5. `get-window "cmp-1" 60` →
   ```clojure
   {:window-start 60
    :totals {:clicks 3 :billed-clicks 1 :invalid-clicks 1 :fraud-clicks 1 :billed-spend 30}
    :breakdown {["US" "mobile"]  {:clicks 1 :billed-clicks 1 :invalid-clicks 0 :fraud-clicks 0 :billed-spend 30}
                ["US" "desktop"] {:clicks 1 :billed-clicks 0 :invalid-clicks 1 :fraud-clicks 0 :billed-spend 0}
                ["DE" "mobile"]  {:clicks 1 :billed-clicks 0 :invalid-clicks 0 :fraud-clicks 1 :billed-spend 0}}}
   ```
6. `advance-watermark! "cmp-1" 239` → window `60` (end `120`) is still open because `239 < 240`.
7. `record-click! "cmp-1" "r5" 61 "US" "mobile" 25 true false` → `:billed`.
   Window `60` totals become `{:clicks 4 :billed-clicks 2 :invalid-clicks 1 :fraud-clicks 1 :billed-spend 55}`;
   breakdown `["US" "mobile"]` becomes `{:clicks 2 :billed-clicks 2 :invalid-clicks 0 :fraud-clicks 0 :billed-spend 55}`.
8. `advance-watermark! "cmp-1" 240` → window `60` is closed.
9. `record-click! "cmp-1" "r6" 90 "US" "mobile" 40 true false` → `:late`.
   Window `60` is unchanged. `get-request "cmp-1" "r6"` →
   `{:request-id "r6" :timestamp 90 :window-start 60 :geo "US" :device "mobile" :spend 40 :valid? true :fraud? false :disposition :late :watermark 240}`.
10. `record-click! "cmp-1" "r1" 100 "US" "mobile" 30 true false` (replay after closure) → no effect.
    `get-request "cmp-1" "r1"` still has `:disposition :billed` and `:watermark 0`; window `60` is unchanged.
11. `get-windows "cmp-1" 0 6000` → the window `60` map followed by the window `4980` map.
    `get-windows "cmp-1" 0 120` → only window `60`. `get-window "cmp-1" 120` → `nil`.
12. `advance-watermark! "cmp-1" 100` → no-op; `get-watermark "cmp-1"` → `240`.
13. `record-click! "cmp-2" "r1" 100 "US" "mobile" 30 true false` → `:billed` in campaign
    `"cmp-2"`; campaign scoping means this is a new click, not a replay.

## Efficiency contract

Bounds concern application-level records examined or updated, allowing
input/output-size costs and ordinary lookup and ranking overhead.

- `record-click!` must do bounded work per click, independent of how many
  clicks, windows, or campaigns exist.
- `get-request` must not read anything proportional to the number of other
  requests, windows, or campaigns.
- `get-window` reads one window. `get-windows` must do work proportional to
  the windows and returned breakdown entries inside the requested range,
  not to windows outside it or to other campaigns.
- Reads of one campaign must not read state belonging to any other campaign.

## Input assumptions

- `campaign-id`, `request-id`, `geo`, and `device` are non-empty strings.
- `timestamp`, `watermark`, `spend`, and range bounds are nonnegative
  signed 64-bit integers. Timestamps are at most `Long/MAX_VALUE - 180`,
  leaving room for window-end and allowed-lateness arithmetic. Window bounds
  are multiples of 60 with `start <= end`. Inputs guarantee cumulative
  counts and spend totals fit signed 64-bit integers.
- Tests supply valid input except for the explicitly specified cases: late
  clicks, replayed request IDs, and stale watermark advances.

## Write ordering and synchronization

All `!` methods are asynchronous writes. Tests call
`(harness/wait-for-processing! client)` after a group of writes and before
any read. Writes addressed to the same campaign must take effect in the
order the client invoked them (an `advance-watermark!` followed by a
`record-click!` on the same campaign is judged against the advanced
watermark). No ordering is required between different campaigns.

All authoritative business state must be durable Rama state (depots and
PStates); transient synchronization counters are allowed. The module runs
with both 2 and 4 tasks in private validation. Multiple clients wrapping
the same deployed module must observe the same business state after the
writing client synchronizes. Tests alternate synchronized client phases;
no cross-client concurrent ordering or snapshot isolation is required.

## Protocol

Your implementation must satisfy the `AdClickAggregation` protocol defined
in `src/hld_ad_click_aggregation/protocol.clj`. The docstrings there are
part of the contract.

## Contract: `create-module`

Your namespace must provide a `create-module` function returning:

```clojure
{:module       <RamaModule instance>
 :wrap-client  (fn [ipc] -> <AdClickAggregation implementation>)}
```

- `:module` — the Rama module to deploy
- `:wrap-client` — given a started IPC cluster, returns a reified `AdClickAggregation` implementation

You choose all internal names (depots, PStates, topologies) freely.

## Synchronization: `Synchronizable`

Your `wrap-client` must reify `rama-challenges.harness/Synchronizable`. See
the docstring on that protocol for implementation requirements. Tests call
`(harness/wait-for-processing! client)` after writes, before reads.

## Namespace

Your solution must be in namespace `hld-ad-click-aggregation.module`.

## File Location

Write your solution to:
```
implementations/hld-ad-click-aggregation/src/hld_ad_click_aggregation/module.clj
```

## nREPL

Start an nREPL with the test classpath:

```bash
clj -M:nrepl
```
