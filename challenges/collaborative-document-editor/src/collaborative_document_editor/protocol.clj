(ns collaborative-document-editor.protocol)

(defrecord AddText [content])
(defrecord RemoveText [amount])
(defrecord Edit [id version offset action])

(defprotocol CollaborativeDocumentEditor
  "Text editing by Long document ID. Edits carry the operation count the
  caller observed, an offset, and an AddText or RemoveText action. An edit
  based on an older count is transformed against that document's intervening
  stored operations. A removal crossing an insertion splits around it;
  versions count stored transformed operations, not submissions."
  (edit! [this edit]
    "Accepts Edit with Long :id, an observed :version (0..current version),
    nonnegative character :offset, and AddText string content or RemoveText
    nonnegative character :amount. The range must be valid in the caller's
    observed document. Concurrent overlapping removals and a stale insertion
    strictly inside an intervening removed span have no correctness guarantee.")
  (doc+version [this document-id]
    "After processing, returns {:doc text :version stored-operation-count};
    an unknown ID returns {:doc nil :version 0}."))
