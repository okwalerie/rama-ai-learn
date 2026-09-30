(ns collaborative-document-editor.module
  (:require [collaborative-document-editor.protocol :as p]
            [com.rpl.rama :refer :all]
            [com.rpl.rama.path :refer :all]
            [nlb.collaborative-document-editor :as upstream]
            [rama-challenges.harness :as harness])
  (:import [java.util UUID]
           [nlb.collaborative_document_editor Edit]))

;; The request ID lets a replayed stream record be recognized; the upstream
;; Edit is stored and transformed unchanged.
(defrecord EditRequest [id request-id edit])

;; Upstream's stream topology with a per-document applied-request check. A
;; stream retry replays the depot record after the first attempt's writes.
(defmodule RetrySafeCollaborativeDocumentEditorModule
  [setup topologies]
  (declare-depot setup *edit-depot (hash-by :id))
  (let [topology (stream-topology topologies "core")]
    (declare-pstate topology $$docs {Long String})
    (declare-pstate topology $$edits
      {Long (vector-schema Edit {:subindex? true})})
    (declare-pstate topology $$applied-requests
      {Long (set-schema UUID {:subindex? true})})
    (<<sources topology
      (source> *edit-depot :> {:keys [*id *request-id *edit]})
      (local-select> [(keypath *id) (view contains? *request-id)]
        $$applied-requests :> *applied?)
      (filter> (not *applied?))
      (get *edit :version :> *version)
      (local-select> [(keypath *id) (view count)]
        $$edits :> *latest-version)
      (<<if (= *latest-version *version)
        (vector *edit :> *final-edits)
       (else>)
        (local-select>
          [(keypath *id) (srange *version *latest-version)]
          $$edits :> *missed-edits)
        (upstream/transform-edit *edit *missed-edits :> *final-edits))
      (local-select> [(keypath *id) (nil->val "")]
        $$docs :> *latest-doc)
      (upstream/apply-edits *latest-doc *final-edits :> *new-doc)
      (local-transform> [(keypath *id) (termval *new-doc)]
        $$docs)
      (local-transform>
        [(keypath *id) END (termval *final-edits)]
        $$edits)
      (local-transform> [(keypath *id) NONE-ELEM (termval *request-id)]
        $$applied-requests)))
  (<<query-topology topologies "doc+version"
    [*id :> *ret]
    (|hash *id)
    (local-select> (keypath *id) $$docs :> *doc)
    (local-select> [(keypath *id) (view count)] $$edits :> *version)
    (hash-map :doc *doc :version *version :> *ret)
    (|origin)))

(defn create-module []
  {:module RetrySafeCollaborativeDocumentEditorModule
   :wrap-client
   (fn [ipc]
     (let [module-name (get-module-name RetrySafeCollaborativeDocumentEditorModule)
           depot (foreign-depot ipc module-name "*edit-depot")
           query (foreign-query ipc module-name "doc+version")]
       (reify p/CollaborativeDocumentEditor
         (edit! [_ {:keys [id version offset action]}]
           (foreign-append! depot
             (->EditRequest id (UUID/randomUUID)
               (upstream/->Edit id version offset
                 (if (instance? collaborative_document_editor.protocol.AddText action)
                   (upstream/->AddText (:content action))
                   (upstream/->RemoveText (:amount action)))))
             :ack))
         (doc+version [_ id]
           (foreign-invoke-query query id))
         harness/Synchronizable
         (wait-for-processing! [_]))))})
