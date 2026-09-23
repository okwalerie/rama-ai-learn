# HLD Rate Limiter Challenge

Build the durable core of a per-user API rate limiter: two-dimensional
token buckets (one per user, one per user+endpoint), versioned per-user
configuration, idempotent request decisions, and a shadow mode that
accounts identically but never rejects.

## Source and attribution

This challenge is adapted from "Design a Distributed Rate Limiter" by
the Handbook Academy contributors:
https://hld.handbook.academy/curriculum/case-studies/rate-limiter/

Prose adapted from that case study is licensed under Creative Commons
Attribution-ShareAlike 4.0 International (CC BY-SA 4.0):
https://creativecommons.org/licenses/by-sa/4.0/ . This README and the
protocol docstrings are derived works published under the same license.

Modifications and exclusions relative to the source:

- Only the token bucket algorithm, with exact integer refill per logical
  tick. Sliding windows, GCRA, and leaky bucket are excluded.
- Limits are per user and per user+endpoint only. Per-IP limits,
  fleet-wide load shedding, and concurrent-request limits are excluded.
- No gateway fleet, no local chunk borrowing, no Redis, no circuit
  breaker, no fail-open outage simulation, no HTTP headers.
- Configuration is owned by each user and versioned; there is no
  global rule store.
- Time is logical: callers supply non-negative tick values. The module
  must not consult wall-clock time.

## Scope

- **Two buckets per decision.** A request against endpoint `e` for
  user `u` debits both `u`'s user bucket and `u`'s bucket for `e`. The
  debit is atomic: both buckets are debited or neither is.
- **Exact integer refill.** A bucket with state `(tokens, at)` and
  parameters `(capacity, refill)` has
  `available(T) = min(capacity, tokens + (T - at) * refill)` for any
  `T >= at`. A successful debit of `cost` at `T` sets
  `tokens = available(T) - cost` and `at = T`. A denied request leaves
  the bucket unchanged.
- **Versioned config.** `set-config!` with a version strictly greater
  than the user's current version replaces the config and resets every
  bucket (user and all endpoints) to full. Versions less than or equal
  to the current version are ignored entirely.
- **Idempotent decisions.** The first decision for (user, request-id)
  is recorded and replayed for every retry, even after the user's
  config changes. A retried request-id never touches buckets and never
  advances the user's clock.
- **Monotone logical clock per user.** The effective tick of a new
  request is `T = max(now, user clock)`. `T` is always evaluated and
  recorded in the decision, but the clock advances to `T` only when the
  decision debits (`:would-allow true`, in enforcing or shadow mode).
- **Rejected checks record only.** A decision with `:would-allow false`
  (any reason) records its outcome and has no other effect: no debit,
  no clock change.
- **Shadow mode.** When the user's config has `:shadow? true`, the
  decision computes `:would-allow` and debits buckets exactly as
  enforcing mode would, but `:allowed` is always `true`.

## Input grammar and bounds

| Input | Constraint |
|---|---|
| `user-id`, `request-id` | String, 1..64 chars of `[A-Za-z0-9_-]` |
| `endpoint` | String, 1..64 chars of `[a-z0-9/_.-]` |
| `version` | Long in `[1, 2^31)` |
| `config` | `{:shadow? boolean :user {:capacity c :refill r} :endpoints {endpoint {:capacity c :refill r}}}` with 1..16 endpoints |
| `capacity` | Long in `[1, 10^6]` |
| `refill` | Long in `[0, 10^6]` tokens per tick |
| `cost` | Long in `[1, 10^6]` |
| `now` | Long tick in `[0, 10^12)` |

With these bounds `(T - at) * refill` fits in a Long. Callers only send
inputs that satisfy the grammar; the only "invalid" inputs tests send
are the explicit retry and stale-version cases described in the
protocol.

## Workload

- 5,000,000 users with configs. Config changes are rare (under 10/s).
- Decisions peak at roughly 1,000,000 per second fleet-wide; a hot user
  may issue thousands of requests per second.
- A user may accumulate up to 1,000,000 recorded decisions.

## Bounded-work contracts (enforced)

Tests do not measure wall-clock latency. They enforce the work bounds
below by construction (large histories, hot keys) and by inspection.

- `check!` processing is fixed work per request, independent of how
  many decisions the user has accumulated.
- `get-decision`, `get-config`, and `get-status` do fixed read work.
- Work and storage are balanced across tasks; the harness launches the
  module with 2 or 4 tasks, and tests run with both.

## Enforced work budgets

Private tests count RocksDB operations through the Rama test event hook
on the module's own PStates (internal `$$__` PStates are excluded) and
enforce the following at both 2 and 4 tasks. They never inspect
topology types, depot layouts, PState names or layouts, and never
measure time. Depot reads are not counted, so stream and microbatch
designs are measured identically.

- Each read (`get-decision` for the earliest, the latest and a missing
  request-id, `get-config`, `get-status`) issues at most 4 point reads,
  at most 2 iterator seeks and at most 8 iterator reads, and writes no
  entries, measured on a user with 32 recorded decisions and again on a
  user with 512.
- One new `check!` (the write plus the barrier that processes it)
  issues at most 8 point reads, at most 2 iterator seeks, at most 8
  iterator reads and at most 8 written entries, at both history sizes.
  A retried request-id and a denied check each stay within the same
  budget at both history sizes.
- Bounded growth: from 32 to 512 recorded decisions, each operation's
  counts may grow by at most 2 point reads, 1 iterator seek, 4 iterator
  reads and 2 written entries. Fixed-work designs grow by zero.
- Observed RocksDB work distribution: 400 users each receive one
  `set-config!` and one `check!` through the same client, with users
  alternating between two clients, and are then read back with
  `get-decision` and `get-status`. The total written entries are between
  1x and 8x the user count. Reading every user back issues at most 8x
  point reads, 4x iterator seeks and 16x iterator reads (user-count
  multiples) and writes nothing; the three read metrics are separate
  ceilings so a bounded iterator-based design is not rejected for
  avoiding point reads. Every task performs between half and
  one-and-a-half times the mean share of written entries and of
  aggregate read work (point reads + iterator seeks + iterator reads).

The event hook reports operation counts only, not value sizes or bytes,
so the tests observe RocksDB work distribution, not storage size.
Storing a user's whole decision history as one opaque value would pass
the counts above while violating the fixed-work contract; that design
is rejected by review, not by an executable test.

## Production latency aspirations (NOT acceptance thresholds)

- Reads (`get-decision`, `get-config`, `get-status`): about 50 ms.

These figures describe the production target the design should meet.
No private test asserts them.

## Protocol

Your implementation must satisfy `hld-rate-limiter.protocol/RateLimiter`.
See `src/hld_rate_limiter/protocol.clj`. Every docstring rule is part
of the contract.

Write methods (`!` suffix) return `nil`; their outcomes are observed
through the read methods after `wait-for-processing!`. Read methods must
not wait and must not mutate state.

## Contract: `create-module`

Your namespace must provide a `create-module` function returning:

```clojure
{:module       <RamaModule instance>
 :wrap-client  (fn [ipc] -> <RateLimiter implementation>)}
```

- `:module` — the Rama module to deploy
- `:wrap-client` — given a started IPC cluster, returns a reified
  `RateLimiter` implementation

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
  (user) are processed in invocation order.
- Write calls from different clients may be serialized in any order,
  but every write that returned before a `wait-for-processing!` barrier
  is processed before any write issued after that barrier returns.
- Tests run every scenario with both 2 and 4 tasks, and some scenarios
  drive the module through a second client. Every rule holds in every
  configuration.

## Namespace

Your solution must be in namespace `hld-rate-limiter.module`.

## File Location

Write your solution to:
```
implementations/hld-rate-limiter/src/hld_rate_limiter/module.clj
```

## nREPL

Start an nREPL with the test classpath:

```bash
clj -M:nrepl
```
