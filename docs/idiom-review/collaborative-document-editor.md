# collaborative-document-editor: stream retry review

## Finding

The upstream stream topology is not retry-safe. A forced stream retry replays
the depot record after the first attempt stored its operations, so the
replayed edit is transformed against itself. A retried insertion is inserted
a second time (for example `"abcXY!def"` version 3 becomes `"abcXY!!def"`
version 4). A retried removal is fully subsumed by its own stored operations,
so it contributes zero operations and document text and version stay correct.

## Change

- `test-resources/upstream/` is unchanged, byte for byte.
- The reference adapter owns `RetrySafeCollaborativeDocumentEditorModule`. It
  reuses upstream records, `transform-edit`, and `apply-edits`. The client tags
  each append with a random request ID. The topology skips a request ID that
  was already applied to that document, and records the ID after storing the
  document and operations.
- The private `stream-retry-applies-each-edit-once` test forces one failed
  `:streaming-complete` for each edit, using the `failed-streaming` idiom from
  the auction-module performance support. It runs at 2 and 4 tasks and covers
  a stale insertion, a stale removal split around insertions, a fully subsumed
  stale removal, and later edits.
- Result against the previous adapter: 4 failures (stale insertion and later
  edits, at both task counts). The stale removal cases pass on both adapters,
  for the reason given above.

## README

The README makes no false retry or state guarantee, so it is unchanged. This
is the minimal sentence proposed for it:

> Each accepted edit is applied exactly once, even when stream processing is
> retried.
