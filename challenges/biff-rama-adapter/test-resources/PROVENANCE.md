# Source provenance

Contract sources: `jacobobryant/biff` at `9ce0f9775682b2e3b163025ee668a490733004e1`
(2026-10-30), Biff libraries 2.0.5 from Clojars.

| Upstream document | Used for |
|---|---|
| `docs/db-adapters.md` | Required adapter surface: lifecycle module, KV functions, `wrap-db-snapshot`, fx handlers, `on-tx` on every node, resolver generation. |
| `libs/core/docs/schema.md` | KV, `wrap-db-snapshot` and `on-tx` signatures and semantics. |
| `libs/sqlite/src/com/biffweb/sqlite/impl/{system,pool,kv,resolver}.clj` | Shape of an existing adapter: `-id` ref columns returned as value plus join, batch resolvers, `wrap-db-snapshot` as `(fn [f]) => (fn [ctx])`. |
| `libs/xtdb/README.md` | Columns map format (`{:user/pet-id {:ref :pet/id}}`) and the `:<table>/id` alias convention. |

No upstream code is copied. The transaction op language (`:put`, `:patch`,
`:delete`) is this challenge's own; it is a deliberately small subset of
XTDB's `:put-docs`/`:patch-docs`/`:delete-docs` with no conditional ops, so
transactions never read before they write. Authorization rules, schema
enforcement and unique constraints from the upstream adapters are out of
scope.

`biff_rama_adapter/module.clj` is the reference implementation. It versions
each entity by microbatch ID and publishes the newest transaction basis
that every task has committed; reads select the newest version at or below
one published basis. A plain stream topology passes the read-your-writes
tests but fails the snapshot and atomicity tests, because Rama makes each
task's writes visible independently.
