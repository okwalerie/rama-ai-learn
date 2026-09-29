# Provenance and adaptation

Source repository: `next-level-backends-with-rama-clj`, pinned HEAD
`1b2e0430539962f1b8798b157e3491ccb8b821fe`. Exact source and upstream test
copies are retained under `test-resources/upstream/nlb/`;
`content_moderation.clj` and its matching test are unmodified. The source
uses a stream topology and `get-posts` / `get-posts-helper` queries.

Adaptation: README and protocol establish a standalone consumer contract;
they do not prescribe internal depots or PStates. The private
`content-moderation.module` defines its own module adapted from the upstream
source. Stream topologies are at-least-once: a retry after a committed
`AFTER-ELEM` write appends the same post twice. The private module therefore
processes both depots in a `core` microbatch topology, which applies each
depot record exactly once under retry. Depots, PState schemas, per-record
logic, and both query topologies are otherwise unchanged; it stores the
public protocol records directly. `Synchronizable` counts successful appends
and waits on `wait-for-microbatch-processed-count` for `core`.
The upstream `get-posts-helper` uses `srange`, which throws if the requested
offset exceeds the feed length. The private adapter checks the feed count and
returns an empty terminal page in that case; the query itself is unchanged.
Pagination scans the append-only underlying feed and excludes muted authors;
its work depends on skipped entries and there is no fixed-work guarantee.
The imported implementation's odd/non-positive limit behavior is not
promised. There is no classifier, report workflow, post removal, or ranking.

Harness status: see `test-private/EVIDENCE.md`.
