(ns hld-enterprise-rag.protocol
  "Protocol definition for the hld-enterprise-rag challenge.

   Everything is scoped by tenant: doc-ids, user-ids, and group-ids are
   only meaningful within one tenant. Three independent, monotonic revision
   streams exist, each guarded by 'strictly greater wins, equal or lower is
   a no-op':
     - document content (put-document! and delete-document! share it)
     - document ACL      (put-document-acl!)
     - user membership   (put-user-groups!)

   A chunk is {:chunk-id <str> :text <str> :tokens [<str> ...]} with at
   most 32 tokens; a document has at most 32 chunks with distinct
   chunk-ids. Tokens are pre-tokenized strings compared by exact equality.

   See README.md for eligibility, scoring, ordering, and worked numbers.")

(defprotocol EnterpriseRag
  "Permission-aware keyword retrieval over revisioned, chunked documents."

  (put-document! [this tenant doc-id content-revision chunks]
    "Write. Iff `content-revision` is strictly greater than the document's
     current content revision (or the document has no content revision
     yet), replace the document's chunks with `chunks` (a vector of 1..32
     chunk maps) and make the document live. Old chunks, including any
     chunk-ids absent from the new vector, disappear. Otherwise no-op.
     Never touches the document's ACL.")

  (delete-document! [this tenant doc-id content-revision]
    "Write. Iff `content-revision` is strictly greater than the document's
     current content revision (or no content revision exists), tombstone
     the document: it has no live
     chunks and its content revision becomes `content-revision`. Otherwise
     no-op. Never touches the document's ACL. A later put-document! with a
     higher revision recreates the document under its retained ACL.")

  (put-document-acl! [this tenant doc-id acl-revision groups]
    "Write. Iff `acl-revision` is strictly greater than the document's
     current ACL revision (or none is set), replace the document's ACL with
     `groups` (a set of group-id strings, possibly empty). Otherwise no-op.
     Never touches content or tombstone state; setting an ACL on a
     tombstoned document does not make it live.")

  (put-user-groups! [this tenant user-id membership-revision groups]
    "Write. Iff `membership-revision` is strictly greater than the user's
     current membership revision (or none is set), replace the user's group
     set with `groups` (a set of group-id strings, possibly empty).
     Otherwise no-op. Never requires reindexing any document.")

  (get-document [this tenant doc-id]
    "Read. Returns nil if neither content nor ACL has ever been written for
     the document, otherwise
       {:doc-id <str>
        :content-revision <int or nil>   ; nil if no content write yet
        :live? <boolean>                 ; true iff it currently has chunks
        :chunk-count <int>               ; 0 when not live
        :acl-revision <int or nil>       ; nil if no ACL write yet
        :groups <set or nil>}            ; nil if no ACL write yet")

  (get-user-groups [this tenant user-id]
    "Read. Returns nil if the user has never had a membership write,
     otherwise {:membership-revision <int> :groups <set>}.")

  (query [this tenant user-id query-tokens k]
    "Read. `query-tokens` is a vector of token strings (duplicates are
     ignored); `k` is an integer >= 0. A chunk is a candidate iff its
     document is live, the document's ACL is set and intersects the user's
     current groups (an unset ACL or an unknown user means not eligible),
     and its score is > 0, where score = number of distinct query tokens
     that appear in the chunk's tokens. Authorization is applied before
     truncation to k: ineligible chunks never occupy result slots. Returns
     at most k result maps
       {:doc-id <str> :chunk-id <str> :score <int> :text <str>
        :content-revision <int> :acl-revision <int>}
     sorted by :score descending, then :doc-id ascending, then :chunk-id
     ascending (string comparison). k = 0 returns an empty vector."))
