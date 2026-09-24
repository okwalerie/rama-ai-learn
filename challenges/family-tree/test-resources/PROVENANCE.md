# Provenance and integration adaptations

- Source: `/home/user/workspace/repos/next-level-backends-with-rama-clj/src/nlb/family_tree.clj` and `test/nlb/family_tree_test.clj`, commit `1b2e0430539962f1b8798b157e3491ccb8b821fe` in `next-level-backends-with-rama-clj`.
- Blog: https://blog.redplanetlabs.com/2025/03/26/next-level-backends-with-rama-graphs/
- Exact, unmodified upstream files are retained in `test-resources/upstream/nlb/`. The upstream test is provenance only; the challenge acceptance suite uses the protocol, not that test's direct depot/query calls.
- `test-resources/family_tree/module.clj` is the private harness adapter. It deploys the original `nlb.family-tree/FamilyTreeModule`, converts public `family-tree.protocol/Person` to the upstream `nlb.family-tree/Person`, and exposes original depot and query names via the protocol. Stream appends request full `:ack`, so `Synchronizable` is a no-op. No topology, schema, or query code was changed.
- Source behavior retained rather than silently repaired: empty ancestor aggregation is `nil`; descendant query for a leaf or unknown node with positive generations emits `{0 0}`; ancestor generation zero emits immediate parents; shared descendant paths count separately; a record replacement does not retract prior child links. The supported domain is finite acyclic graphs, append-once IDs, roots before children, and nonnegative bounded generation counts. No performance claim is made.

`deps.edn` keeps the original solution private: `:test-private-harness` uses `:replace-paths` and includes the upstream source and adapter; normal `:paths` and `:test-private` include the candidate implementation path but never upstream or the private adapter.
