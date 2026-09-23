# Implicit Spec

<!-- Phase 0 artifact for hld-web-crawler. Requirements only; no design. -->

Domain: host-addressed crawl frontier. Entities: **URL** (keyed by
canonical string; owned by its host), **host** (policy, clock, queue,
lease, fence counter, last-claim-at), **claim** (host + claim-id). Time
is logical ticks supplied with claims and completions, clamped
monotone per host. Harness runs 2 or 4 tasks and a second client.

Ordering: sequential writes from one client to the same logical owner
(host) process in invocation order; writes from different clients
serialize arbitrarily but a write that returned before a
`wait-for-processing!` barrier processes before any write issued after
it. Latency figures below are production aspirations, never acceptance
thresholds; tests enforce only bounded-work contracts.
A `discover!` batch preserves element order only within each host.

## Operations

### discover! [urls]

- **Latency**: observable after wait; not latency critical.
- **Throughput**: ~50,000 URLs/s. Batches of ≤ 100.
- **Invariants**:
  - Canonicalization is exactly as specified: scheme case-insensitive,
    host lowercased, fragment dropped, empty path → `/`, path and query
    preserved byte-for-byte (no percent-decoding, no trailing-slash
    edits, no sorting of query params, `?` with empty query preserved).
  - Exact dedup on the canonical string, forever, across statuses
    (`:done`, `:failed`, `:blocked` URLs are never re-queued by
    discovery).
  - `get-host :queued` increases by exactly the number of newly queued
    URLs of that host.
  - Processing retries must not queue a URL twice or double-count
    `:queued`.
- **Data growth**: 1B URLs; per host up to 1M pending; access by URL
  point lookup, by host smallest-pending, by host lex range.
- **Concurrency**: the same URL in two concurrent batches: exactly one
  queues it. Batches for different hosts are independent. Within one
  batch, elements of one host keep batch order; elements of different
  hosts have no defined relative order. Two sequential batches from one
  client keep order per host.
- **Edge cases**: `HTTPS://Example.COM` → `https://example.com/`;
  `https://example.com#frag` → `https://example.com/`;
  `https://example.com?` → `https://example.com/?`;
  `https://example.com/a?b=1#c` → `https://example.com/a?b=1`;
  `http://example.com/` invalid; `https://user@example.com/` invalid;
  `https://example.com:443/` invalid; non-ASCII host or path invalid;
  2049-char URL invalid; path with space invalid; `https://` alone
  invalid (empty host); batch of 100 identical URLs → one queued.

### set-host-policy! [host delay rules]

- **Latency**: observable after wait.
- **Throughput**: low (~1,000/s).
- **Invariants**: replaces the whole policy (configurable, not
  static); affects only claims processed afterwards; never retroactive:
  does not re-evaluate already leased, done, failed, or blocked URLs;
  does not change last-claim-at, the lease, the clock, or the fence
  counter.
- **Edge cases**: rules [] with delay 5 (only politeness changes);
  disallow `/` (everything blocked, every claim drains the queue into
  `:blocked` and reports `:empty`); allow `/a` + disallow `/a` (allow
  wins on tie); disallow `/a` + allow `/a/b` (URL `/a/b/c` allowed,
  `/a/c` blocked); rule prefix matching into the query (`/a?x` matches
  `/a?x=1`); policy set for a host with no URLs (get-host reflects it).

### claim! [host claim-id now]

- **Latency**: observable after wait.
- **Throughput**: ~50,000/s.
- **Invariants**:
  - Exactly one recorded outcome per (host, claim-id); retries replay
    it and neither issue a fence nor advance the clock nor touch the
    lease.
  - At most one live lease per host at any time.
  - Fences strictly increase per host by exactly 1 per grant; a
    replayed claim never re-issues or skips a fence.
  - Granted URL is the smallest allowed pending URL by String order,
    after requeueing a stale lease.
  - `:busy` when T < lease expiry; equality is stale (requeue).
  - `:not-ready` uses start-to-start delay from last-claim-at; `:empty`
    and denied claims never update last-claim-at.
  - Blocked URLs are retired permanently; a later policy that would
    allow them does not resurrect them.
  - A stale lease's URL that is requeued is again eligible immediately
    in the same claim (it may be re-granted with a new fence).
  - Processing retries must not grant twice.
- **Data growth**: claims per host unbounded; point lookup by
  (host, claim-id).
- **Concurrency**: claims for one host from one client process in
  invocation order; from different clients they are serialized in some
  order; exactly one can be `:granted` while a lease is live. Claims
  for different hosts are independent.
- **Edge cases**:
  - Claim at T = last-claim-at + delay (allowed); T = last-claim-at +
    delay − 1 (not-ready).
  - Claim at T = lease expires-at (stale → requeue) vs T = expires-at −
    1 (busy).
  - Stale lease requeued but delay not yet elapsed → `:not-ready`, URL
    stays `:queued`, lease cleared.
  - Claim with now smaller than the host clock (clamped; e.g. an
    out-of-order worker cannot make a live lease stale by supplying a
    small now, nor un-expire one by supplying a large one earlier).
  - First claim on a host with URLs but no policy: delay 1, granted.
  - Claim after a successful complete!: `:not-ready` until delay
    elapses (measured from last-claim-at, which complete! does not
    change), then granted with fence + 1. Note the clock may have been
    advanced by the completion's now.
  - 1,000 consecutive blocked head URLs then one allowed: granted, the
    1,000 are `:blocked`, `:queued` decreased by 1,001.
  - Claim on unknown host: `:empty`; get-host shows defaults, the claim
    is recorded.

### complete! [host fence outcome now]

- **Latency**: observable after wait.
- **Throughput**: ~50,000/s.
- **Invariants**:
  - With C = max(now, host clock): applies iff the host's current lease
    has that fence AND C < lease expires-at.
  - On success: retires the URL as `:done`/`:failed`, clears the lease,
    sets host clock = C; never touches last-claim-at or the fence
    counter.
  - On rejection (no lease, wrong fence, or C ≥ expires-at): changes
    NOTHING, including the clock. The expired lease stays recorded
    until the next claim requeues it.
  - Idempotent: a second completion with the same fence finds no lease
    → no effect.
  - Fixed processing work.
- **Concurrency**: complete racing a claim from another client: if the
  complete is processed first while the lease is live, the URL is
  retired and the claim proceeds to the next URL; if the claim is
  processed first and the lease was stale, it is requeued/regranted
  with a new fence and the completion (old fence) is ignored; if the
  claim is first and the lease is live, the claim is `:busy` and the
  completion then applies. Only these results are acceptable. From one
  client, claim then complete with the granted fence always applies
  while the lease is live at the completion's C.
- **Edge cases**: fence 0 (never matches); fence from a previous lease
  after a new grant (ignored: "stale completion cannot release a new
  lease"); completion of an expired lease with the matching fence and
  C ≥ expires-at (rejected: URL stays `:leased`, lease stays, clock
  unchanged; the next claim requeues it); exact boundary C =
  expires-at (rejected, same as above); C = expires-at − 1 (accepted);
  completion with now < host clock (C = clock; accepted iff clock <
  expires-at, and the clock does not move); completion with now far
  ahead of the clock but before expiry (accepted; clock jumps to now,
  so a later claim with a smaller now is evaluated at that clock);
  `:failed` outcome (URL retired as `:failed`, not requeued);
  completion for a host with no lease (ignored, clock unchanged).

### get-claim [host claim-id]

- **Latency**: production aspiration ~50 ms (not enforced); point read,
  fixed work (enforced).
- **Invariants**: nil until processed; immutable afterwards; never
  waits or mutates.

### get-url [url]

- **Latency**: production aspiration ~50 ms (not enforced); point read
  after canonicalization, fixed work (enforced).
- **Invariants**: reflects the canonical form; nil for invalid or
  unseen; `:fence`/`:lease-expires-at` non-nil iff `:leased`.
- **Edge cases**: query with non-canonical host casing → same record;
  URL blocked by robots → `:blocked` with host; URL whose lease is
  expired → still `:leased` (with the old fence and expiry) until the
  next claim, even after a rejected completion.

### get-host [host]

- **Latency**: production aspiration ~50 ms (not enforced); fixed work;
  `:queued` maintained, not counted (enforced).
- **Invariants**: `:queued` = number of `:queued` URLs (excludes
  `:leased`, `:done`, `:failed`, `:blocked`); `:fence` = last issued
  fence; `:lease` nil after a successful completion; `:lease` still
  present while expired (until next claim), including after a rejected
  completion.
- **Edge cases**: never-seen host → defaults; host whose only URL was
  blocked → `:queued 0`, `:lease nil`, `:fence 0`.

### list-pending [host from-url limit]

- **Latency**: production aspiration ~50 ms (not enforced); work bounded
  by limit (enforced).
- **Invariants**: ascending String order over `:queued` ∪ `:leased`;
  strictly after cursor; stable pagination when no writes intervene;
  never includes retired URLs.
- **Edge cases**: from-url not itself pending (still a valid cursor:
  results are the pending URLs greater than it); host with 1M pending
  URLs and limit 100 (bounded work); empty host → [].

## Entity State × Write Matrix

### Entity: URL (canonical string)

States: **unseen**, **queued**, **leased(f)** (live or stale), **done**,
**failed**, **blocked**. Reads: `get-url`, `get-host` (`:queued`,
`:lease`), `list-pending`, `get-claim`.

```
unseen x discover! (valid)
  - get-url: :queued, host h; get-host(h): :queued +1; list-pending: included
unseen x discover! (invalid form)
  - get-url: nil; get-host: unchanged
unseen x claim! / complete! / set-host-policy!
  - get-url: nil (no URL is created by these)
queued x discover!
  - unchanged (dedup)
queued x claim! (this URL is smallest, allowed, host free and ready)
  - get-url: :leased, fence f, lease-expires-at T+30
  - get-host: :queued -1, :lease {this f T+30}, :fence f, :last-claim-at T
  - list-pending: still included; get-claim: :granted this URL
queued x claim! (this URL is smallest but disallowed)
  - get-url: :blocked; get-host: :queued -1; list-pending: excluded
  - get-claim: whatever the next allowed URL yields
queued x claim! (a smaller URL exists, or host busy/not-ready)
  - get-url: unchanged :queued
queued x complete! (any fence)
  - get-url: unchanged (completion targets the leased URL only)
queued x set-host-policy! (now disallowing it)
  - get-url: :queued until it reaches the head in a claim, then :blocked
leased(f) live x claim! (T < expires-at)
  - get-url: unchanged; get-claim: :denied :busy; get-host unchanged
                                        (clock advanced to T)
leased(f) stale x claim! (T >= expires-at, delay elapsed, still smallest, allowed)
  - get-url: :leased with fence f+1, new expiry T+30
  - get-host: :lease {url f+1 T+30}, :fence f+1, :last-claim-at T
leased(f) stale x claim! (T >= expires-at, delay not elapsed)
  - get-url: :queued (fence nil); get-host: :lease nil, :queued +1
  - get-claim: :denied :not-ready
leased(f) live x complete! (fence f, C = max(now, clock) < expires-at, :fetched | :failed)
  - get-url: :done | :failed, fence nil; get-host: :lease nil, :queued unchanged;
    clock = C
  - list-pending: excluded
leased(f) x complete! (fence f, C >= expires-at, including C = expires-at)
  - unchanged: still :leased with fence f; lease, clock, last-claim-at unchanged
leased(f) x complete! (fence != f, any now)
  - unchanged (clock unchanged)
leased(f) x discover! (same URL)
  - unchanged
leased(f) x set-host-policy! (disallowing it)
  - unchanged; a live-lease completion still applies; it is never :blocked
    while leased (no retroactive effect)
done/failed/blocked x discover!
  - unchanged (seen forever)
done/failed/blocked x claim!
  - never selected; unchanged
done/failed/blocked x complete!
  - unchanged
```

### Entity: host

States: **unknown** (no URLs, no policy, no claims), **idle** (no lease),
**leased-live**, **leased-stale**. Sub-state: clock, last-claim-at,
fence counter, queued count. Reads: `get-host`, `get-claim`,
`list-pending`.

```
unknown x discover! (URL for it)
  - get-host: :queued n, defaults otherwise
unknown x set-host-policy!
  - get-host: :delay/:rules set, :queued 0
unknown x claim!
  - get-claim: :denied :empty; get-host: :fence 0, :last-claim-at nil
                                        (clock advanced to T)
idle x claim! (ready, allowed URL exists)
  - get-host: :lease set, :fence +1, :last-claim-at T; get-claim :granted
idle x claim! (T < last-claim-at + delay)
  - get-host: unchanged except clock; get-claim :denied :not-ready
idle x claim! (queue empty or all blocked)
  - get-host: :queued 0, :last-claim-at unchanged; get-claim :denied :empty
idle x complete! (any fence, any now)
  - unchanged (clock unchanged)
idle x set-host-policy!
  - get-host: policy replaced; later claims use it
leased-live x claim!
  - get-claim :denied :busy; get-host unchanged except clock
leased-live x complete! (matching fence, C < expires-at)
  - get-host: :lease nil; host becomes idle; :last-claim-at unchanged;
    clock = C
leased-live x complete! (matching fence, C >= expires-at)
  - unchanged: the lease is now stale by the completion's own C, stays
    recorded, clock unchanged
leased-live x complete! (other fence)
  - unchanged
leased-live x set-host-policy!
  - policy replaced; lease untouched
leased-live x discover!
  - :queued +k; lease untouched
leased-stale x claim!
  - lease requeued first; then :not-ready | :granted (new fence) | :empty
    (empty only if the requeued URL became :blocked and nothing else remains)
leased-stale x complete! (matching fence, any now)
  - rejected: URL stays :leased, :lease stays, clock unchanged; only the
    next claim! requeues it
leased-stale x set-host-policy!
  - policy replaced; stale lease untouched
```

### Entity: claim (host + claim-id)

States: **unrecorded**, **recorded**. Reads: `get-claim`, `get-host`.

```
unrecorded x claim!
  - get-claim: outcome per rules; get-host: per outcome
recorded x claim! (same id, any now)
  - get-claim: unchanged; get-host: unchanged (no fence, no clock change,
    no lease change)
recorded x discover!/complete!/set-host-policy!
  - get-claim: unchanged (outcomes are immutable history)
```
