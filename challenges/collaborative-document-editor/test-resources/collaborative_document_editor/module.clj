(ns collaborative-document-editor.module
  (:require [collaborative-document-editor.protocol :as p]
            [com.rpl.rama :refer [get-module-name foreign-depot foreign-query
                                  foreign-append! foreign-invoke-query]]
            [nlb.collaborative-document-editor :as upstream]
            [rama-challenges.harness :as harness]))

(defn create-module []
  {:module upstream/CollaborativeDocumentEditorModule
   :wrap-client
   (fn [ipc]
     (let [module-name (get-module-name upstream/CollaborativeDocumentEditorModule)
           depot (foreign-depot ipc module-name "*edit-depot")
           query (foreign-query ipc module-name "doc+version")]
       (reify p/CollaborativeDocumentEditor
         (edit! [_ {:keys [id version offset action]}]
           (foreign-append! depot
             (upstream/->Edit id version offset
               (if (instance? collaborative_document_editor.protocol.AddText action)
                 (upstream/->AddText (:content action))
                 (upstream/->RemoveText (:amount action))))
             :ack))
         (doc+version [_ id]
           (foreign-invoke-query query id))
         harness/Synchronizable
         (wait-for-processing! [_]))))})
