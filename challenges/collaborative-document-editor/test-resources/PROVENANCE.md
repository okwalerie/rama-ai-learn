# Provenance and adaptation

Source: `next-level-backends-with-rama-clj` at commit
`1b2e0430539962f1b8798b157e3491ccb8b821fe`. The files under
`upstream/nlb/` are byte-for-byte copies of its
`src/nlb/collaborative_document_editor.clj` and
`test/nlb/collaborative_document_editor_test.clj`. The module has a stream
topology and a `doc+version` query. The source and its tests are not altered.

The challenge-specific `collaborative-document-editor.module/create-module`
adapter translates public protocol records to upstream records, uses a full
ack for stream writes, and delegates queries to the upstream module. This is
integration code, not a replacement implementation. The private harness runs
both the direct upstream tests and the candidate-facing protocol tests; the
ordinary private alias uses only candidate source and does not expose upstream.

Limits: stored transformed operations, rather than submitted edits, define
the version. General overlapping removals remain outside this package's
qualification. Stale insertions strictly inside a deleted span have a demonstrated defect. Executed
source-level reproducer: `transform-edit (Edit 1 0 3 (AddText "X"))` against
`[(Edit 1 0 2 (RemoveText 3))]` returned insertion offset `0`, yielding
`"Xabfgh"` when applied to `"abfgh"`. The expected deletion-boundary
placement is offset `2`. The public contract excludes this case rather than
claiming general OT correctness. Conversely, `[10,16)` against `[8,12)`
correctly returns `[8,12)` (length 4), as confirmed by direct execution; it
is not evidence of a bug.
