(ns hld-enterprise-rag.module
  (:require [clojure.set :as set]
            [com.rpl.rama :refer :all]
            [com.rpl.rama.path :refer :all]
            [com.rpl.rama.ops :as ops]
            [com.rpl.rama.test :as rtest]
            [hld-enterprise-rag.protocol :as p]
            [rama-challenges.harness :as harness]))

(defrecord DocKey [tenant doc-id])
(defrecord UserKey [tenant user-id])
(defrecord TokenKey [tenant token])
(defrecord ChunkRef [doc-id chunk-id])

(defn accepted? [old revision field]
  (or (nil? (get old field)) (> revision (get old field))))

(defn chunks-map [chunks]
  (into {} (map (fn [{:keys [chunk-id text tokens]}]
                  [chunk-id {:text text :tokens (set tokens)}]) chunks)))

(defn references [key chunks]
  (set (for [[chunk-id {:keys [tokens]}] chunks
             token tokens]
         [(->TokenKey (:tenant key) token)
          (->ChunkRef (:doc-id key) chunk-id)])))

(defn posting-diff [key old-chunks new-chunks]
  (let [old (references key old-chunks)
        new (references key new-chunks)]
    (concat (map #(conj % :remove) (set/difference old new))
            (map (fn [[token-key ref]]
                   [token-key ref :add (get new-chunks (:chunk-id ref))]) new))))

(defmodule EnterpriseRagModule [setup topologies]
  (declare-depot setup *writes (hash-by :key))
  (let [mb (microbatch-topology topologies "entities")]
    (declare-pstate mb $$docs
      {DocKey (fixed-keys-schema
                {:content-revision Long :chunk-count Long
                 :acl-revision Long :groups (set-schema String)})})
    ;; Content is separate so ACL updates and metadata reads never load chunk payloads.
    (declare-pstate mb $$chunks
      {DocKey {String (fixed-keys-schema {:text String :tokens (set-schema String)})}})
    (declare-pstate mb $$users
      {UserKey (fixed-keys-schema
                 {:membership-revision Long :groups (set-schema String)})})
    (declare-pstate mb $$postings
      {TokenKey (map-schema ChunkRef
                            (fixed-keys-schema {:text String :tokens (set-schema String)})
                            {:subindex-options {:track-size? false}})})
    (<<sources mb
      (source> *writes :> %records)
      (%records :> *event)
      (get *event :kind :> *kind)
      (get *event :key :> *key)
      (get *event :revision :> *revision)
      (get *event :groups :> *groups)
      (<<if (= *kind :membership)
        (local-select> (keypath *key) $$users :> *old-user)
        (<<if (accepted? *old-user *revision :membership-revision)
          (local-transform> [(keypath *key)
                             (termval {:membership-revision *revision
                                       :groups *groups})] $$users))
       (else>)
        (local-select> (keypath *key) $$docs :> *old)
        (<<if (= *kind :acl)
          (<<if (accepted? *old *revision :acl-revision)
            (assoc *old :acl-revision *revision :groups *groups :> *updated)
            (local-transform> [(keypath *key) (termval *updated)] $$docs))
         (else>)
          (<<if (accepted? *old *revision :content-revision)
            (<<if (= *kind :put)
              (get *event :chunks :> *input-chunks)
              (chunks-map *input-chunks :> *new-chunks)
             (else>)
              (identity {} :> *new-chunks))
            (assoc *old :content-revision *revision
                        :chunk-count (long (count *new-chunks)) :> *updated)
            (local-transform> [(keypath *key) (termval *updated)] $$docs)
            (local-select> (keypath *key) $$chunks :> *old-chunks)
            (local-transform> [(keypath *key) (termval *new-chunks)] $$chunks)
            (posting-diff *key *old-chunks *new-chunks :> *diff)
            (ops/explode *diff :> [*token-key *ref *op *payload])
            (|hash *token-key)
            (<<if (= *op :add)
              (local-transform> [(keypath *token-key *ref) (termval *payload)] $$postings)
             (else>)
              (local-transform> [(keypath *token-key *ref) NONE>] $$postings))))))))

(defn query-results [docs postings groups tokens k]
  (let [q (set tokens)
        chunks (into {} postings)]
    (->> chunks
         (keep (fn [[ref chunk]]
                 (let [doc (get docs (:doc-id ref))
                       score (count (set/intersection q (:tokens chunk)))]
                   (when (and chunk (:content-revision doc) (:acl-revision doc)
                              (seq (set/intersection groups (:groups doc)))
                              (pos? score))
                     {:doc-id (:doc-id ref) :chunk-id (:chunk-id ref)
                      :score score :text (:text chunk)
                      :content-revision (:content-revision doc)
                      :acl-revision (:acl-revision doc)}))))
         (sort-by (juxt (comp - :score) :doc-id :chunk-id))
         (take k)
         vec)))

(defn create-module []
  (let [counts (atom {})]
    {:module EnterpriseRagModule
     :wrap-client
     (fn [ipc]
       (let [name (get-module-name EnterpriseRagModule)
             depot (foreign-depot ipc name "*writes")
             docs (foreign-pstate ipc name "$$docs")
             users (foreign-pstate ipc name "$$users")
             postings (foreign-pstate ipc name "$$postings")
             append! (fn [event]
                       (foreign-append! depot (update event :revision long) :append-ack)
                       (swap! counts update ipc (fnil inc 0)))]
         (reify
           harness/Synchronizable
           (wait-for-processing! [_]
             (rtest/wait-for-microbatch-processed-count ipc name "entities" (get @counts ipc 0)))
           p/EnterpriseRag
           (put-document! [_ tenant doc-id revision chunks]
             (append! {:kind :put :key (->DocKey tenant doc-id)
                       :revision revision :chunks chunks}))
           (delete-document! [_ tenant doc-id revision]
             (append! {:kind :delete :key (->DocKey tenant doc-id) :revision revision}))
           (put-document-acl! [_ tenant doc-id revision groups]
             (append! {:kind :acl :key (->DocKey tenant doc-id)
                       :revision revision :groups groups}))
           (put-user-groups! [_ tenant user-id revision groups]
             (append! {:kind :membership :key (->UserKey tenant user-id)
                       :revision revision :groups groups}))
           (get-document [_ tenant doc-id]
             (let [doc (foreign-select-one
                        [(keypath (->DocKey tenant doc-id))
                         (submap [:content-revision :chunk-count :acl-revision :groups])]
                        docs)]
               (when (or (:content-revision doc) (:acl-revision doc))
                 {:doc-id doc-id :content-revision (:content-revision doc)
                  :live? (pos? (or (:chunk-count doc) 0))
                  :chunk-count (or (:chunk-count doc) 0)
                  :acl-revision (:acl-revision doc) :groups (:groups doc)})))
           (get-user-groups [_ tenant user-id]
             (foreign-select-one (keypath (->UserKey tenant user-id)) users))
           (query [_ tenant user-id tokens k]
             (if (or (zero? k) (empty? tokens))
               []
               (let [membership (foreign-select-one (keypath (->UserKey tenant user-id)) users)
                     groups (:groups membership)]
                 (if (empty? groups)
                   []
                   (let [matches (mapcat #(foreign-select [(keypath (->TokenKey tenant %)) ALL]
                                                         postings)
                                         (set tokens))
                         refs (set (map first matches))
                         doc-map (into {} (map (fn [doc-id]
                                                 [doc-id (foreign-select-one
                                                          [(keypath (->DocKey tenant doc-id))
                                                           (submap [:content-revision :acl-revision :groups])]
                                                          docs)])
                                               (set (map :doc-id refs))))]
                     (query-results doc-map matches groups tokens k)))))))))}))
