# Oracle plan correction — September 23, 2026

Requested review read the protocol, README, PLAN and PLAN_VALIDATION. This is
design advice, not implemented/tested evidence. Fresh plan-validation must
apply these corrections before BUILD.

## Bounded chunks preserve ordered host processing

Retain steps1–5 unchanged: replay guard before mutations; compute T once;
live/stale lease handling; politeness check. Carry host info in memory.
At step6 no lease remains, so pending contains queued URLs only.

Read a sorted subset of up to64 pending URLs using:

```clojure
(local-select> [(keypath *host :pending)
                (sorted-set-range-from-start 64)] $$hosts :> *chunk)
;; only after consuming a wholly blocked chunk:
(local-select> [(keypath *host :pending)
                (sorted-set-range-from *last-blocked
                  {:max-amt 64 :inclusive? false})] $$hosts :> *chunk)
```

Do not append ALL/FIRST: empty subset must still emit a branchable value.
Consume in memory, retire each disallowed URL (status blocked, remove pending,
decrement queued); stop at first allowed URL and grant immediately. Do not
evaluate/mutate the prefetched tail. Fetch another chunk only after all entries
were blocked and queued remains positive. Persist final info and one outcome.
Grant remains pending. No yield, asynchronous operation, or partition hop
between initial checks and final writes. list-pending remains caller-limit
exclusive range followed by ALL, including leased URLs.

Replace categorical claim "no Rama API yields while preserving same-key order"
with: this handler has no order-preserving suspension protocol; variable-length
skip-path yielding lets later host events overtake it. Provisional lease changes
empty/busy outcomes and is not a solution.

## Honest cost model

For S blocked URLs, B=64, grant G in0/1, stale requeue E in0/1:
- Fixed claim-history/info reads.
- Pending range seeks <=ceil((S+1)/B); entries <=S+B; memory O(B).
- Robots evaluations S+G, each at most100 rules.
- Logical transforms 2S+G+E+2 (blocked record+pending deletion, grant,
  stale URL reset, final info+claim outcome).

For1000 blocked+one grant:16seeks and <=1024entries, roughly13ms traversal
at0.5ms/seek+5us/entry, NOT total latency. 2003logical transforms remain.
32-entry chunks would cost32seeks/~21ms traversal. No sub50ms total latency
claim is justified. Original per-entry algorithm meets O(S) public contract;
chunk size is reference design, not an acceptance requirement.

Consider :subindex-options {:track-size? false} for urls/pending/claims, since
none queries collection size and queued is maintained. Default size tracking
adds a read on writes. Whole termval/set-elem deletion no-read leaf optimization
does not prove nested navigation has zero reads. Measure during BUILD; do not
price each buffered microbatch transform as a separate flush.

## Trace corrections

After blocking /a and leasing /b from {/a,/b,/c}, queued=1; stale requeue
makes2, not3. Regrant at40 sets last-claim-at40; later completion preserves40,
not10. Correct all repeated estimates/traces in both artifacts together.

## Architecture-neutral executable checks

At both2/4tasks, capture operation+barrier after setup, excluding verification
reads. Publish broad envelopes: claim work <=A+C*S independent of allowed tail
and history; pagination <=A+C*limit independent of cursor depth/retired history.
Do not require layout/topology/chunk size/exact counts. Hook lacks opaque-value
size, retain explicit review limitation.

- Fixed S, grow tail; vary S=0,1,31,32,33,63,64,65,1000. Check all-blocked empty
  and first-allowed stop; disallowed URLs after first allowed remain queued.
- Verify skipped statuses/nil lease fields/exact queued delta/single grant.
- Replay with huge tick leaves original outcome and pre-expiry completion valid.
- Sequential no-barrier claims: grant then busy; all-blocked empty then empty.
- Stale requeue/not-ready and policy replacement ordering.
- Pagination limits1/middle/100 and start/deep/tail/missing cursors; grow retired
  and pending populations; leased URL included, no retired/duplicate/omitted URLs.
