# Provenance and adaptation

Source repository: `next-level-backends-with-rama-clj`, pinned HEAD
`1b2e0430539962f1b8798b157e3491ccb8b821fe`. Exact source and upstream test
copies are retained under `test-resources/upstream/nlb/`;
`content_moderation.clj` and its matching test are unmodified. The source
uses a stream topology and `get-posts` / `get-posts-helper` queries.

Adaptation: README and protocol establish a standalone consumer contract;
they do not prescribe internal depots or PStates. The private
`content-moderation.module/create-module` wraps the original module, translates
the public Post record to/from the upstream Post record, and implements
`Synchronizable` as a no-op because its stream depot appends use full ACK.
The upstream `get-posts-helper` uses `srange`, which throws if the requested
offset exceeds the feed length. The private adapter checks the feed count and
returns an empty terminal page in that case; the upstream source stays intact.
Pagination scans the append-only underlying feed and excludes muted authors;
its work depends on skipped entries and there is no fixed-work guarantee.
The imported implementation's odd/non-positive limit behavior is not
promised. There is no classifier, report workflow, post removal, or ranking.

Harness status: see `test-private/EVIDENCE.md`.
