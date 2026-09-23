# HLD Notification System Challenge

Build the durable core of a push notification platform: a per-recipient
device registry with token generations, per-category preferences,
idempotent submissions fanned out to devices, guarded delivery attempts
with bounded retries, a dead-letter record for permanent failures, and
monotone client receipts.

## Source and attribution

This challenge is adapted from "Design a Notification System (Push,
SMS, Email at Scale)" by the Handbook Academy contributors:
https://hld.handbook.academy/curriculum/case-studies/notification-system/

Prose adapted from that case study is licensed under Creative Commons
Attribution-ShareAlike 4.0 International (CC BY-SA 4.0):
https://creativecommons.org/licenses/by-sa/4.0/ . This README and the
protocol docstrings are derived works published under the same license.

Modifications and exclusions relative to the source:

- Push channel only. SMS, email, in-app inbox, fallback waterfalls, and
  provider quotas are excluded.
- No external sends. Provider outcomes are reported to the module by an
  explicit attempt command that carries the outcome and the logical
  time of the attempt.
- No templates or rendering: the payload is an opaque string.
- No quiet hours, digests, frequency caps, scheduling, or send-time
  optimization. Preferences are a per-category enabled flag.
- Retry schedule is fixed (10 ticks, then 20 ticks, at most 3
  attempts) instead of exponential backoff with jitter.
- Time is logical: callers supply non-negative tick values. The module
  must not consult wall-clock time.

## Scope

- **Devices.** A recipient has at most 8 devices. Registering a device
  assigns generation 1; re-registering the same device-id increments
  its generation, replaces its token, and marks it valid.
- **Preferences.** Per (user, category) enabled flag, default enabled.
- **Submissions.** Globally keyed by submission-id; the first submission
  wins and retries are ignored. At submit time the module evaluates the
  recipient's preference for the category and fans out one delivery per
  currently valid device, snapshotting each device's token and
  generation.
- **Attempts.** Delivery outcomes arrive via `report-attempt!` with an
  attempt number and the tick of the attempt. Attempts are guarded:
  only the next expected attempt number, only while the delivery is
  pending, only once the retry delay has elapsed. Transient failures
  retry after 10 ticks, then 20 ticks; the third failure is permanent.
  Permanent failures and exhausted retries are dead-lettered.
- **Invalid token.** Marks the delivery terminal and invalidates the
  device only when the device's current generation equals the
  delivery's snapshotted generation.
- **TTL.** A submission expires at `submitted-at + ttl`. An attempt
  report that passes the attempt-number and due-tick guards for a
  pending delivery, but carries a tick `>= expires-at`, expires the
  delivery instead of applying the outcome. A report with a wrong
  attempt number or an early tick is ignored even when its tick is
  past expiry: a stale report can never expire a delivery.
- **Receipts.** Client receipts (`:delivered`, `:read`) raise a
  delivery in state `:accepted`, `:delivered`, or `:read`
  monotonically along `:accepted < :delivered < :read`. Receipts for a
  `:pending` delivery are ignored, as are receipts for terminal
  deliveries. An accepted, delivered, or read delivery never retries.

## Input grammar and bounds

| Input | Constraint |
|---|---|
| `user-id`, `device-id`, `submission-id` | String, 1..64 chars of `[A-Za-z0-9_-]` |
| `token` | String, 1..256 chars of `[A-Za-z0-9]` |
| `category` | String, 1..32 chars of `[a-z_]` |
| `payload` | String, 0..4096 chars |
| `ttl` | Long in `[1, 10^6]` ticks |
| `now` | Long tick in `[0, 10^12)` |
| `attempt-no` | Long in `[1, 3]` |
| `outcome` | `:accepted`, `:transient-failure`, `:permanent-failure`, `:invalid-token` |
| `receipt` | `:delivered`, `:read` |

Callers only send inputs that satisfy the grammar; the only "invalid"
inputs tests send are the explicit retry, stale, and out-of-order cases
described in the protocol.

## Workload

- 100,000,000 recipients, on average 1.8 devices each.
- Submissions peak at roughly 100,000 per second; attempt reports and
  receipts peak at roughly 300,000 per second combined.
- A recipient may accumulate up to 100,000 submissions and up to
  100,000 dead-letter entries over time.
- A recipient has at most 16 categories with explicit preferences.

## Bounded-work contracts (enforced)

Tests do not measure wall-clock latency. They enforce the work bounds
below by construction (large histories, hot keys) and by inspection.

- `submit!` processing is fixed work bounded by the 8-device limit,
  independent of the recipient's submission history.
- `report-attempt!` and `record-receipt!` processing is fixed work per
  delivery.
- `get-submission`, `get-devices`, `get-preferences` do fixed read work.
- `get-recent-submissions` and `get-dead-letters` do read work bounded
  by the 100-entry page, independent of the recipient's total history.
- Work and storage are balanced across tasks; the harness launches the
  module with 2 or 4 tasks, and tests run with both.

## Production latency aspirations (NOT acceptance thresholds)

- Point reads (`get-submission`, `get-devices`, `get-preferences`) and
  page reads (`get-recent-submissions`, `get-dead-letters`): about
  50 ms.

These figures describe the production target the design should meet.
No private test asserts them.

## Protocol

Your implementation must satisfy
`hld-notification-system.protocol/NotificationSystem`. See
`src/hld_notification_system/protocol.clj`. Every docstring rule is
part of the contract.

Write methods (`!` suffix) return `nil`; their outcomes are observed
through the read methods after `wait-for-processing!`. Read methods must
not wait and must not mutate state.

## Contract: `create-module`

Your namespace must provide a `create-module` function returning:

```clojure
{:module       <RamaModule instance>
 :wrap-client  (fn [ipc] -> <NotificationSystem implementation>)}
```

- `:module` — the Rama module to deploy
- `:wrap-client` — given a started IPC cluster, returns a reified
  `NotificationSystem` implementation

You choose all internal names (depots, PStates, topologies) freely. All
business state must live in the module: the client wrapper holds no
business data.

## Synchronization: `Synchronizable`

Your `wrap-client` must reify `rama-challenges.harness/Synchronizable`.
See the docstring on that protocol for implementation requirements.
Tests call `(harness/wait-for-processing! client)` after writes, before
reads. Tests never rely on reads observing a write before that call.

## Ordering, clients, and tasks

- Sequential write calls from one client to the same logical owner
  (recipient user, or submission for attempt and receipt reports) are processed in invocation order.
- Write calls from different clients may be serialized in any order,
  but every write that returned before a `wait-for-processing!` barrier
  is processed before any write issued after that barrier returns.
- Tests run every scenario with both 2 and 4 tasks, and some scenarios
  drive the module through a second client. Every rule holds in every
  configuration.

## Namespace

Your solution must be in namespace `hld-notification-system.module`.

## File Location

Write your solution to:
```
implementations/hld-notification-system/src/hld_notification_system/module.clj
```

## nREPL

Start an nREPL with the test classpath:

```bash
clj -M:nrepl
```
