(ns collaborative-document-editor.module
  "MUTANT recompute-doc-on-read: upstream module inlined unchanged except the
  current document text is never materialized; doc+version replays the whole
  edit log from the empty string on every read (O(history) per read)."
  (:use [com.rpl.rama]
        [com.rpl.rama.path])
  (:require [collaborative-document-editor.protocol :as p]
            [rama-challenges.harness :as harness]))

(defrecord AddText [content])
(defrecord RemoveText [amount])

(defrecord Edit [id version offset action])

(defn add-action-adjustment [{:keys [action]}]
  (if (instance? AddText action)
    (-> action :content count)
    (-> action :amount -)))

(defn transform-remove-against-add [removes offset action]
  (into []
    (mapcat
      (fn [edit]
        (let [start (:offset edit)
              remove-amount (-> edit :action :amount)
              end (+ start remove-amount)
              add-amt (-> action :content count)]
          (cond (>= offset end) [edit]
                (<= offset start) [(update edit :offset #(+ % add-amt))]
                :else
                (let [remove1 (- offset start)
                      remove2 (- remove-amount remove1)
                      start2 (+ offset add-amt)]
                  [(->Edit (:id edit) (:version edit) start2 (->RemoveText remove2))
                   (->Edit (:id edit) (:version edit) start (->RemoveText remove1))])))))
     removes))

(defn transform-remove-against-remove [removes offset action]
  (into []
    (mapcat
      (fn [edit]
        (let [start (:offset edit)
              remove-amount (-> edit :action :amount)
              end (+ start remove-amount)
              missed-remove-amount (-> action :amount)]
          (cond (>= offset end)
                [edit]

                (<= offset start)
                (let [overlap (-> (- (+ offset missed-remove-amount) start)
                                  (max 0)
                                  (min remove-amount))]
                  (if (< overlap remove-amount)
                    [(-> edit
                         (update :offset #(-> % (- missed-remove-amount) (+ overlap)))
                         (update-in [:action :amount] #(- % overlap)))]))

                :else
                (let [overlap-end (min end (+ offset missed-remove-amount))
                      overlap (- overlap-end offset)]
                  [(update-in edit [:action :amount] #(- % overlap))])))))
     removes))

(defn transform-edit [edit missed-edits]
  (if (instance? AddText (:action edit))
    (let [new-offset (reduce
                       (fn [offset missed-edit]
                         (if (<= (:offset missed-edit) offset)
                           (+ offset (add-action-adjustment missed-edit))
                           offset))
                       (:offset edit)
                       missed-edits)]
      [(assoc edit :offset new-offset)])
    (reduce
      (fn [removes {:keys [offset action]}]
        (if (instance? AddText action)
          (transform-remove-against-add removes offset action)
          (transform-remove-against-remove removes offset action)))
      [edit]
      missed-edits)))

(defn apply-edits [doc edits]
  (reduce
    (fn [doc {:keys [offset action]}]
      (if (instance? AddText action)
        (setval (srange offset offset) (:content action) doc)
        (setval (srange offset (+ offset (:amount action))) "" doc)))
    doc
    edits))

(defmodule CollaborativeDocumentEditorModule
  [setup topologies]
  (declare-depot setup *edit-depot (hash-by :id))
  (let [topology (stream-topology topologies "core")]
    ;; MUTATION: no materialized $$docs PState; the document is replayed
    ;; from the full edit log on every read.
    (declare-pstate
      topology
      $$edits
      {Long (vector-schema Edit {:subindex? true})})
    (<<sources topology
      (source> *edit-depot :> {:keys [*id *version] :as *edit})
      (local-select> [(keypath *id) (view count)]
        $$edits :> *latest-version)
      (<<if (= *latest-version *version)
        (vector *edit :> *final-edits)
       (else>)
        (local-select>
          [(keypath *id) (srange *version *latest-version)]
          $$edits :> *missed-edits)
        (transform-edit *edit *missed-edits :> *final-edits))
      (local-transform>
        [(keypath *id) END (termval *final-edits)]
        $$edits)))
  (<<query-topology topologies "doc+version"
    [*id :> *ret]
    (|hash *id)
    (local-select> [(keypath *id) (view count)] $$edits :> *version)
    (<<if (= 0 *version)
      (identity nil :> *doc)
     (else>)
      (local-select> [(keypath *id) (srange 0 *version)] $$edits
        {:allow-yield? true} :> *all-edits)
      (apply-edits "" *all-edits :> *doc))
    (hash-map :doc *doc :version *version :> *ret)
    (|origin)))

(defn create-module []
  {:module CollaborativeDocumentEditorModule
   :wrap-client
   (fn [ipc]
     (let [module-name (get-module-name CollaborativeDocumentEditorModule)
           depot (foreign-depot ipc module-name "*edit-depot")
           query (foreign-query ipc module-name "doc+version")]
       (reify p/CollaborativeDocumentEditor
         (edit! [_ {:keys [id version offset action]}]
           (foreign-append! depot
             (->Edit id version offset
               (if (instance? collaborative_document_editor.protocol.AddText action)
                 (->AddText (:content action))
                 (->RemoveText (:amount action))))
             :ack))
         (doc+version [_ id]
           (foreign-invoke-query query id))
         harness/Synchronizable
         (wait-for-processing! [_]))))})
