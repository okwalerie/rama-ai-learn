# Imported source provenance

Imported sources remain under their upstream namespaces so the challenge
adapters and tests exercise the original module rather than a rewritten
topology. The who-to-follow source has one documented Rama 1.9 compatibility
line edit; timed-notifications uses the shared test-tick selector.

| Challenge | Upstream snapshot | Revision | Integration adapter | Notes |
|---|---|---|---|---|
| who-to-follow | `nlb/who_to_follow.clj` | next-level-backends-with-rama-clj `1b2e0430539962f1b8798b157e3491ccb8b821fe` | `test-resources/who_to_follow/module.clj` | Adapter maps protocol calls to upstream depots/PStates and adds test-barrier counting. |

The NLB public posts were also read for the two matching examples:
[recommendation engine](https://blog.redplanetlabs.com/2025/04/08/next-level-backends-with-rama-recommendation-engine-in-80-loc/)
and [timed notifications](https://blog.redplanetlabs.com/2025/04/16/next-level-backends-with-rama-fault-tolerant-timed-notifications-in-25-loc/).
No other mounted repositories were consulted.

The original source tests were consulted for observable behavior. The private
challenge tests are new, use independent expected values, and pass with 2 and
4 tasks. No topology redesign was performed. The challenge README/protocols
state behavior rather than providing source-answer links or implementation
topology instructions.

Known limits: these imports preserve their source semantics, including
unspecified tie ordering for recommendations and spend ranking, the
recommendation scan's 15-account-per-task cursor page, and timed-notification
behavior being checked only under simulated time. The imported source has
one deliberate Rama 1.9 compatibility edit: replace
`(key (-> *m rseq first) :> *max-id)` with `(key (last *m) :> *max-id)`.
This is a one-line equivalent for the last entry of the sorted map and avoids
calling `rseq` on Rama's `VectorBackedSortedMap`, which is not
`clojure.lang.Reversible`. The remainder of the imported topology is retained.
No restart/replay guarantee, efficiency measurement, or production-latency
claim was established here.
