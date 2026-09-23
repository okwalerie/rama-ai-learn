# HLD Web Crawler Challenge

Build the durable core of a crawl frontier: exact URL deduplication
under a restricted HTTPS canonicalization, per-host queues in
deterministic lexicographic order, caller-configured robots rules, a
start-to-start per-host politeness delay, and single-lease claims with
fencing tokens so that stale workers can never release a newer or an
expired lease.

## Source and attribution

This challenge is adapted from "Design a Web Crawler (Googlebot-style)"
by the Handbook Academy contributors:
https://hld.handbook.academy/curriculum/case-studies/web-crawler/

Prose adapted from that case study is licensed under Creative Commons
Attribution-ShareAlike 4.0 International (CC BY-SA 4.0):
https://creativecommons.org/licenses/by-sa/4.0/ . This README and the
protocol docstrings are derived works published under the same license.

Modifications and exclusions relative to the source:

- No fetching, parsing, DNS, rendering, or storage of content. Workers
  claim URLs from the frontier and report completion; the module never
  contacts hosts.
- No content deduplication (SimHash), no Bloom filters, no priority
  scoring (OPIC/PageRank), no re-crawl scheduling. URL dedup is exact.
- The frontier is host-addressed: a worker asks for the next URL of a
  specific host. There is no global "next ready host" scheduler.
- robots rules are path-prefix allow/disallow lists supplied by the
  caller through `set-host-policy!`; there is no robots.txt fetching or
  parsing. Policies may be replaced at any time and affect only claims
  processed afterwards; they never have retroactive effects.
- Canonicalization is restricted to HTTPS URLs with ASCII hostnames.
- Time is logical: callers supply non-negative tick values. The module
  must not consult wall-clock time.

## Scope

- **Canonicalization.** A URL is valid iff it matches
  `https://HOST[PATH][?QUERY][#FRAGMENT]` where HOST is 1..253 chars of
  `[A-Za-z0-9.-]` (no port, no userinfo), PATH starts with `/` and
  contains printable ASCII (0x21..0x7E) other than `?` and `#`, QUERY
  contains printable ASCII other than `#`, and the whole input is at
  most 2048 chars. The scheme is matched case-insensitively. Canonical
  form: `https://` + lowercase HOST + PATH (or `/` when absent) +
  (`?` + QUERY verbatim when a `?` is present) with the fragment
  removed. Invalid URLs are ignored wherever they appear.
- **Exact dedup.** A canonical URL is seen at most once, ever. Later
  discoveries of the same canonical URL are ignored.
- **Lex order.** Within a host, the next URL to hand out is the
  smallest pending canonical URL by `String.compareTo`.
- **Robots.** A host policy has a delay and up to 100 rules
  `{:path-prefix String :allow? boolean}`. Policies are configurable
  (`set-host-policy!` replaces the whole policy) but never retroactive:
  URLs already leased, done, failed, or blocked are unaffected, and the
  lease, clock, fence counter, and last-claim-at are unchanged. A URL's
  path-and-query
  (everything after the host) is matched against every rule's prefix;
  the longest matching prefix decides; if both an allow and a disallow
  rule share that longest prefix, allow wins; no matching rule means
  allowed. Rules are evaluated when a URL reaches the head of its host
  queue during a claim; a disallowed URL is retired as `:blocked` and
  the next URL is considered.
- **Politeness.** Start-to-start: a host can be claimed again only when
  the effective tick is at least `last-claim-at + delay`. The default
  delay is 1 tick.
- **Leases.** At most one live lease per host. A lease lasts 30 ticks.
  A lease whose `expires-at <= now` is stale: it remains recorded until
  the next claim for the host requeues the leased URL. Every granted
  claim carries a fresh, strictly increasing per-host fencing token.
- **Completion.** `complete!` carries the host, the fence, the outcome,
  and the worker's `now`. It has an effect only when the fence equals
  the host's current lease fence AND `max(now, host clock) <
  lease expires-at`. A successful completion retires the URL, clears
  the lease, and advances the host clock to `max(now, host clock)`. A
  rejected completion (wrong fence, no lease, or expired lease,
  including the exact boundary `max(now, host clock) = expires-at`)
  changes nothing, including the clock. A stale lease stays in place
  until the next claim requeues it.
- **Host clock.** The effective tick of a claim is
  `max(now, latest effective tick seen for the host)`; a claim always
  advances the clock to that tick. A successful completion advances it
  likewise; a rejected completion never touches it.

## Input grammar and bounds

| Input | Constraint |
|---|---|
| `urls` | Vector of 1..100 strings, each at most 2048 chars |
| `host` | String, 1..253 chars of `[A-Za-z0-9.-]`, matched case-insensitively |
| `claim-id` | String, 1..64 chars of `[A-Za-z0-9_-]` |
| `delay` | Long in `[1, 10^6]` ticks |
| `rules` | Vector of 0..100 `{:path-prefix p :allow? b}`, `p` 1..256 printable ASCII chars starting with `/` |
| `fence` | Long |
| `outcome` | `:fetched` or `:failed` |
| `now` | Long tick in `[0, 10^12)` |
| `limit` | Long in `[1, 100]` |

Callers only send inputs that satisfy the grammar, except for the
explicit invalid-URL, retry, and stale-fence cases described in the
protocol.

## Workload

- 40,000,000 hosts; 1,000,000,000 pending URLs overall. A single host
  may hold up to 1,000,000 pending URLs.
- Discovery peaks at roughly 50,000 URLs per second; claims and
  completions at roughly 50,000 per second each.
- Up to 1,000 consecutive head-of-queue URLs of a host may be blocked
  by robots rules.

## Bounded-work contracts (enforced)

Tests do not measure wall-clock latency. They enforce the work bounds
below by construction (large queues, hot hosts) and by inspection.

- `discover!` processing is fixed work per URL, independent of the
  host's queue size.
- `claim!` processing is fixed work plus amortized fixed work per
  blocked URL skipped (each URL is retired as `:blocked` at most once).
  It must not scan the host's queue.
- `complete!` processing is fixed work.
- `get-claim`, `get-url`, and `get-host` do fixed read work;
  `get-host`'s `:queued` count must not be computed by scanning.
- `list-pending` does read work bounded by `limit`, independent of
  queue size.
- Work and storage are balanced across tasks; the harness launches the
  module with 2 or 4 tasks, and tests run with both.

## Production latency aspirations (NOT acceptance thresholds)

- Reads (`get-claim`, `get-url`, `get-host`, `list-pending`): about
  50 ms.

These figures describe the production target the design should meet.
No private test asserts them.

## Protocol

Your implementation must satisfy `hld-web-crawler.protocol/CrawlFrontier`.
See `src/hld_web_crawler/protocol.clj`. Every docstring rule is part
of the contract.

Write methods (`!` suffix) return `nil`; their outcomes are observed
through the read methods after `wait-for-processing!`. Read methods must
not wait and must not mutate state.

## Contract: `create-module`

Your namespace must provide a `create-module` function returning:

```clojure
{:module       <RamaModule instance>
 :wrap-client  (fn [ipc] -> <CrawlFrontier implementation>)}
```

- `:module` — the Rama module to deploy
- `:wrap-client` — given a started IPC cluster, returns a reified
  `CrawlFrontier` implementation

You choose all internal names (depots, PStates, topologies) freely. All
business state must live in the module: the client wrapper holds no
business data. Canonicalization may run in the client wrapper or in the
module, but every stored and returned URL is canonical.

## Synchronization: `Synchronizable`

Your `wrap-client` must reify `rama-challenges.harness/Synchronizable`.
See the docstring on that protocol for implementation requirements.
Tests call `(harness/wait-for-processing! client)` after writes, before
reads. Tests never rely on reads observing a write before that call.

## Ordering, clients, and tasks

- Sequential write calls from one client to the same logical owner
  (host) are processed in invocation order.
- Write calls from different clients may be serialized in any order,
  but every write that returned before a `wait-for-processing!` barrier
  is processed before any write issued after that barrier returns.
- Tests run every scenario with both 2 and 4 tasks, and some scenarios
  drive the module through a second client. Every rule holds in every
  configuration.

A single `discover!` batch preserves element order only within each
host: elements for one host are processed in batch order, while the
relative order of elements for different hosts is unspecified.

## Namespace

Your solution must be in namespace `hld-web-crawler.module`.

## File Location

Write your solution to:
```
implementations/hld-web-crawler/src/hld_web_crawler/module.clj
```

## nREPL

Start an nREPL with the test classpath:

```bash
clj -M:nrepl
```
