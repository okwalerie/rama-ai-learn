# Top users module

Build an analytics backend that maintains the highest-spending users in an
e-commerce service.

## Contract

- User IDs and purchase amounts are `Long` values; amounts are nonnegative
  cents. This is the input domain; negative purchases are outside the
  contract.
- Every purchase adds its amount to that user's cumulative spend.
- Purchase events have no ID and are not deduplicated. Re-appending a purchase
  counts again.
- The result contains distinct users, ordered by descending cumulative spend,
  with at most 500 entries. Equal-spend tie ordering is unspecified.
- Purchases and derived ranking are processed asynchronously. Wait for
  processing before checking the result.

## Interface

Implement `top-users-module.protocol/TopUsers` and provide
`top-users-module.module/create-module`, returning `:module` and
`:wrap-client`. See `src/top_users_module/protocol.clj` for call signatures.

Tests validate cumulative updates, ranking, distinct user membership, the
500-user cap, and repeat purchase accumulation. They do not measure latency,
throughput, or recovery behavior.
