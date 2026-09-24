# Who to follow

Build a backend that records directed follow relationships and periodically
recommends accounts followed by the accounts you follow.

## Contract

- Account IDs are `Long` values. Inputs satisfy this domain; validation of
  malformed IDs is not required.
- `follow!` adds the relationship `from → to`. Repeated relationship appends
  do not create duplicate graph membership. This package has no unfollow
  operation.
- A recommendation refresh scans follow relationships and ranks candidates
  by how many accounts the requesting account follows that also follow the
  candidate. Do not recommend the requesting account itself.
- Select the 1,000 most popular candidates before removing accounts already
  followed by the requester. Return at most 300 candidates from that ranked
  set. Ties have no specified order.
- A user's recommendation list is empty until a refresh computes it. New
  follow data is reflected by a subsequent refresh, not synchronously by
  `follow!`.

## Bounded processing

Refreshes may spread work over multiple calls. For N accounts with outgoing
follows, all accounts must be revisited within ceil(N / 15) + 2 completed
refresh calls on a stable graph; computing more per call is allowed. Production
refreshes recur automatically. Recommendation computation is eventual, not a
per-write latency guarantee. Tests use explicit refresh and processing barriers;
they do not measure wall-clock performance. Honor the public
`rama-challenges.shared/REPLACE-TICK-DEPOTS` setting: when bound before
`create-module`, allow manual `refresh!` calls without automatic timers.

## Interface

Implement `who-to-follow.protocol/WhoToFollow` and provide
`who-to-follow.module/create-module`, returning `:module` and `:wrap-client`.
The wrapper must implement `rama-challenges.harness/Synchronizable` so tests
can wait for a requested refresh. See `src/who_to_follow/protocol.clj` for the
call signatures.
