# HLD URL Shortener Challenge

Build the durable core of a URL shortener: custom-alias links with an
immutable destination and optional expiry, lifecycle controls (delete,
block/unblock), a status-aware `resolve-alias`, and a lifetime click counter
fed by trusted click observations.

## Source and attribution

This challenge is adapted from "Design a URL Shortener (TinyURL /
bit.ly)" by the Handbook Academy contributors:
https://hld.handbook.academy/curriculum/case-studies/url-shortener/

Prose adapted from that case study is licensed under Creative Commons
Attribution-ShareAlike 4.0 International (CC BY-SA 4.0):
https://creativecommons.org/licenses/by-sa/4.0/ . This README and the
protocol docstrings are derived works published under the same license.

Modifications and exclusions relative to the source:

- Only caller-supplied custom aliases exist. Generated short codes,
  base62 counters, pre-allocated ID batches, and hash-based codes are
  excluded.
- No HTTP layer: no redirect status codes, no CDN or cache tiers, no
  singleflight, no multi-region replication.
- Click analytics reduced to a lifetime per-alias count from trusted,
  already-authorized click observations. No geo/referrer/user-agent
  enrichment, no dashboards.
- Abuse scanning reduced to a manual, reversible block flag.
- Time is logical: callers supply non-negative tick values. The module
  must not consult wall-clock time.

## Scope

- **Aliases are permanently reserved.** Once an alias has ever been
  created it can never be created again, even after deletion or expiry.
- **Mapping and expiry are immutable.** A link's destination and expiry
  never change after creation.
- **Creates are idempotent per (alias, request-id).** The first outcome
  for a pair, whether `:created` or `:rejected`, is durably recorded and
  replayed to any later create with the same pair. A retry that carries
  a different destination or expiry does not change anything: the first
  request wins.
- **Delete is irreversible. Block is reversible.**
- **Resolve** reports exactly one status with precedence
  `:missing` > `:deleted` > `:blocked` > `:expired` > `:active`.
  A link is expired when `now >= expires-at`.
- **Clicks** are trusted observations from an authorized edge. They are
  deduplicated per (alias, click-id) and counted for the alias's
  lifetime regardless of the link's current status, because the
  observation may arrive after the link expired, was blocked, or was
  deleted. Observations for aliases that do not exist are dropped and
  leave no trace.

## Input grammar and bounds

| Input | Constraint |
|---|---|
| `alias` | String, 1..32 chars of `[a-z0-9-]` |
| `request-id`, `click-id` | String, 1..64 chars of `[A-Za-z0-9-]` |
| `target-url` | String, 8..2048 printable ASCII chars, starts with `https://` |
| `expires-at`, `now` | `nil` (expires-at only) or Long tick in `[0, 10^12)` |

Callers only send inputs that satisfy the grammar. The only "invalid"
inputs tests send are the explicit retry and stale cases described in
the protocol.

## Workload

- 10,000,000 aliases. Creates peak at roughly 350 per second.
- Resolves peak at roughly 100,000 per second across aliases; a single
  viral alias can receive thousands of resolves per second.
- Click observations peak at roughly 100,000 per second. One alias may
  accumulate up to 1,000,000 observations over its lifetime.
- An alias may see up to 1,000 distinct create request IDs.

## Bounded-work contracts (enforced)

Tests do not measure wall-clock latency. They enforce the work bounds
below by construction (large histories, hot keys) and by inspection.

- `resolve-alias`, `get-click-count`, and `get-create-outcome` do fixed
  read work: independent of how many clicks or create requests the
  alias has accumulated.
- `record-click!` processing is fixed work per observation, independent
  of the alias's click history size.
- Work and storage are balanced across tasks; the harness launches the
  module with 2 or 4 tasks, and tests run with both.

## Enforced work budgets

Private tests count RocksDB operations through the Rama test event hook
on the module's own PStates (internal `$$__` PStates are excluded) and
enforce the following at both 2 and 4 tasks. They never inspect
topology types, depot layouts, PState names or layouts, and never
measure time. Depot reads are not counted, so stream and microbatch
designs are measured identically.

- Each read (`resolve-alias`, `get-click-count`, `get-create-outcome`)
  issues at most 4 point reads, at most 2 iterator seeks and at most 8
  iterator reads, and writes no entries, measured on an alias with 32
  clicks and 32 create requests and again on an alias with 512 of each.
- One `record-click!` observation (the write plus the barrier that
  processes it) issues at most 8 point reads, at most 2 iterator seeks,
  at most 8 iterator reads and at most 8 written entries, at both
  history sizes. A duplicate observation stays within the same budget.
- Bounded growth: from 32 to 512 history entries, each operation's
  counts may grow by at most 2 point reads, 1 iterator seek, 4 iterator
  reads and 2 written entries. Fixed-work designs grow by zero.
- Observed RocksDB work distribution: 400 aliases each receive one
  create and one click through the same client, with aliases alternating
  between two clients, and are then read back with `get-click-count` and
  `resolve-alias`. The total written entries are between 1x and 8x the
  alias count. Reading every alias back issues at most 8x point reads,
  4x iterator seeks and 16x iterator reads (alias-count multiples) and
  writes nothing; the three read metrics are separate ceilings so a
  bounded iterator-based design is not rejected for avoiding point
  reads. Every task performs between half and one-and-a-half times the
  mean share of written entries and of aggregate read work (point reads
  + iterator seeks + iterator reads).

The event hook reports operation counts only, not value sizes or bytes,
so the tests observe RocksDB work distribution, not storage size.
Storing an alias's whole click or request history as one opaque value
would pass the counts above while violating the fixed-work contract;
that design is rejected by review, not by an executable test.

## Production latency aspirations (NOT acceptance thresholds)

- Reads (`resolve-alias`, `get-click-count`, `get-create-outcome`):
  about 50 ms.

These figures describe the production target the design should meet.
No private test asserts them.

## Protocol

Your implementation must satisfy `hld-url-shortener.protocol/UrlShortener`.
See `src/hld_url_shortener/protocol.clj`. Every docstring rule is part
of the contract.

Write methods (`!` suffix) return `nil`; their outcomes are observed
through the read methods after `wait-for-processing!`. Read methods must
not wait and must not mutate state.

## Contract: `create-module`

Your namespace must provide a `create-module` function returning:

```clojure
{:module       <RamaModule instance>
 :wrap-client  (fn [ipc] -> <UrlShortener implementation>)}
```

- `:module` — the Rama module to deploy
- `:wrap-client` — given a started IPC cluster, returns a reified
  `UrlShortener` implementation

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
  (alias) are processed in invocation order.
- Write calls from different clients may be serialized in any order,
  but every write that returned before a `wait-for-processing!` barrier
  is processed before any write issued after that barrier returns.
- Tests run every scenario with both 2 and 4 tasks, and some scenarios
  drive the module through a second client. Every rule holds in every
  configuration.

## Namespace

Your solution must be in namespace `hld-url-shortener.module`.

## File Location

Write your solution to:
```
implementations/hld-url-shortener/src/hld_url_shortener/module.clj
```

## nREPL

Start an nREPL with the test classpath:

```bash
clj -M:nrepl
```
