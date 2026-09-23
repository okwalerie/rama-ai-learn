# Oracle design review: five challenge references

Requested read-only review of the five PLAN.md files, September 23, 2026.
This records actionable engineering findings, not implementation validation.

## Autocomplete: revise distributed ordering

Microbatch commit atomicity does not order independent event paths. A
snapshot continuation after `|all` risks N-fold initialization; independent
broadcast and fanout paths risk checking searches against the wrong generation.
A future-generation novel search accepted too early can survive publication.

Recommended correction: at the locale owner, synchronously update the
authoritative generation and stamp accepted searches and policy operations.
Route initialization and accepted operations through one ordered owner-to-data
path, followed by ordered data-to-prefix updates. Keep replicated generation
metadata on a terminal metadata-only branch; never overwrite authoritative
generation with delayed older broadcasts. Verify publication, search, block,
unblock, empty snapshots, and novel phrases within a single microbatch.

Read only phrase scalar fields when computing scores; never materialize the
subindexed session collection. Queries explicitly route to placement tasks;
implicit locale-only partition routing would be incorrect.

## Notifications: arbitrate contenders consistently

Concurrent calls from one client `submit(a,u); submit(b,u)` and another
`submit(b,v); submit(a,v)` can independently choose winners b/U and a/V at
different submission tasks. Under a serial-history interpretation of the
public ordering contract, this creates a cycle. Per-recipient first-hop
sequence numbers repair history display order, not winner arbitration.

Recommended correction: use a common same-microbatch contender order extending
recipient order, such as source-task and depot position; choose each ID's
earliest contender before persisting winners. Existing submissions always win.
Keep recipient snapshots and define recent acceptance order consistently with
that order. Test the crossed-submission case and device refresh before stale
token invalidation. If a weaker contract is intended, make it explicit rather
than relying on undocumented arrival ordering.

## Smaller corrections

- Crawler `get-host` must add `:host`, omit internal `:clock`, and provide
  complete public defaults even after partial discovery state.
- Shortener creation outcomes must expand both `:created` and `:rejected`
  into the exact protocol maps.
- Run every functional and scaling scenario at both 2 and 4 tasks. Use
  two wrappers and invoke barriers through the other wrapper.
- Keep performance acceptance about bounded work, never internal topology
  names, exact PState layouts, exact RocksDB counts, or wall-clock targets.

URL shortener and rate limiter had no architectural blockers in this review.
