# Collaborative document editor

Implement a shared text document service keyed by Long document ID. An edit
contains `:id`, caller-observed `:version`, character `:offset`, and an
action: `AddText` with `:content`, or `RemoveText` with a character `:amount`.
Offsets and lengths are character counts as used by Clojure string operations.
The document starts empty. `doc+version(id)` returns
`{:doc current-text :version stored-operation-count}` after processing;
an unknown document returns `{:doc nil :version 0}`.

Versions count stored transformed operations. The version in a submitted edit
is the number of operations the caller had observed. Stale operations are
transformed against intervening operations, then applied; the returned
version increases by the number of transformed operations stored. A removal
may split into multiple operations. Clients must submit an observed version
from 0 through the current version and valid nonnegative offsets and remove
lengths for the document they observed. Edits on distinct IDs are independent.
At the same insertion offset an earlier accepted insertion comes first; a
stale removal crossing an inserted span leaves the new text intact. An
entirely subsumed stale removal contributes zero operations and does not
increment the version.

Concurrent edit transformation is the behavior of the pinned reference, not
a general-purpose operational transformation (OT) standard. Except for fully
subsumed removals, concurrent overlapping removals are outside this contract.
So is a stale insertion strictly inside a span removed by an intervening
edit: for example, inserting at offset 3 into `"abcdefgh"` after `[2,5)`
was removed maps to offset 0 in the reference, not to the deletion boundary.
No latency, maximum document size, or edit-history compaction guarantee is
made: retained history and transformation work grow with edit count.

Implement `collaborative-document-editor.protocol/CollaborativeDocumentEditor`
and `collaborative-document-editor.module/create-module` with the shared Rama
challenge wrapper contract. All write effects become visible after
`wait-for-processing!`.
