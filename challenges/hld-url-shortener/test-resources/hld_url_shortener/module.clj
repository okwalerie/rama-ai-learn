;; IMPORTANT: Before modifying this file, re-read PLAN.md and check pending todos.
;; Adhere to all previously decided design decisions.

(ns hld-url-shortener.module
  "Reference implementation for the hld-url-shortener challenge.

   One depot hashed by alias, one microbatch topology, one PState keyed by
   alias holding the link record, the subindexed per-request outcomes and
   the subindexed set of counted click-ids. Every write and every read is a
   single-task, fixed-work operation on hash(alias)."
  (:require
   [com.rpl.rama :refer :all]
   [com.rpl.rama.path :refer :all]
   [com.rpl.rama.test :as rtest]
   [rama-challenges.harness :as harness]
   [hld-url-shortener.protocol :as proto]))

(defrecord CreateLink [alias request-id target-url expires-at])
(defrecord DeleteLink [alias])
(defrecord BlockLink [alias])
(defrecord UnblockLink [alias])
(defrecord Click [alias click-id])

(defmodule UrlShortenerModule [setup topologies]
  (declare-depot setup *alias-events (hash-by :alias))

  (let [mb (microbatch-topology topologies "core")]
    (declare-pstate mb $$links
      {String (fixed-keys-schema
               {:link      (fixed-keys-schema {:target-url String
                                               :expires-at Long
                                               :deleted?   Boolean
                                               :blocked?   Boolean
                                               :clicks     Long})
                :outcomes  (map-schema String clojure.lang.Keyword {:subindex? true})
                :click-ids (set-schema String {:subindex? true})})})

    (<<sources mb
      (source> *alias-events :> %mb)
      (%mb :> *event)
      (<<subsource *event
        ;; create-link!: first outcome per (alias, request-id) is final;
        ;; the alias is created at most once, ever.
        (case> CreateLink :> {:keys [*alias *request-id *target-url *expires-at]})
        (local-select> [(keypath *alias :outcomes *request-id)] $$links :> *recorded)
        (<<if (nil? *recorded)
          (local-select> [(keypath *alias :link)] $$links :> *link)
          (<<if (nil? *link)
            (local-transform> [(keypath *alias :link)
                               (termval {:target-url *target-url
                                         :expires-at *expires-at
                                         :deleted?   false
                                         :blocked?   false
                                         :clicks     0})]
                              $$links))
          (ifexpr (nil? *link) :created :rejected :> *outcome)
          (local-transform> [(keypath *alias :outcomes *request-id)
                             (termval *outcome)]
                            $$links))

        ;; delete-link!: irreversible flag; no effect on a missing alias.
        (case> DeleteLink :> {:keys [*alias]})
        (local-select> [(keypath *alias :link)] $$links :> *link)
        (<<if (some? *link)
          (local-transform> [(keypath *alias :link :deleted?) (termval true)]
                            $$links))

        ;; block-link!: reversible flag; no effect on a missing alias.
        (case> BlockLink :> {:keys [*alias]})
        (local-select> [(keypath *alias :link)] $$links :> *link)
        (<<if (some? *link)
          (local-transform> [(keypath *alias :link :blocked?) (termval true)]
                            $$links))

        ;; unblock-link!: clear the flag; no effect on a missing alias.
        (case> UnblockLink :> {:keys [*alias]})
        (local-select> [(keypath *alias :link)] $$links :> *link)
        (<<if (some? *link)
          (local-transform> [(keypath *alias :link :blocked?) (termval false)]
                            $$links))

        ;; record-click!: dropped without trace when the alias does not
        ;; exist; deduplicated per (alias, click-id); counted regardless of
        ;; the link's status. Fixed work: two point lookups.
        (case> Click :> {:keys [*alias *click-id]})
        (local-select> [(keypath *alias :link :clicks)] $$links :> *clicks)
        (<<if (some? *clicks)
          (local-select> [(keypath *alias :click-ids)
                          (subselect (set-elem *click-id))]
                         $$links :> *seen)
          (<<if (empty? *seen)
            (local-transform> [(keypath *alias :click-ids)
                               NONE-ELEM (termval *click-id)]
                              $$links)
            (local-transform> [(keypath *alias :link :clicks)
                               (termval (inc *clicks))]
                              $$links)))))))

(defn- resolve-status
  "Pure status derivation from stored facts and the caller's now."
  [{:keys [deleted? blocked? expires-at]} now]
  (cond
    deleted? :deleted
    blocked? :blocked
    (and (some? expires-at) (>= now expires-at)) :expired
    :else :active))

(defn make-client
  "Creates a protocol client. `counter` is harness-only synchronization
   bookkeeping shared by every wrapper produced by one create-module call."
  [ipc counter]
  (let [module-name (get-module-name UrlShortenerModule)
        events (foreign-depot ipc module-name "*alias-events")
        links  (foreign-pstate ipc module-name "$$links")
        append! (fn [record]
                  (foreign-append! events record)
                  (swap! counter inc)
                  nil)]
    (reify proto/UrlShortener
      (create-link! [_ alias request-id target-url expires-at]
        (append! (->CreateLink alias request-id target-url expires-at)))
      (delete-link! [_ alias]
        (append! (->DeleteLink alias)))
      (block-link! [_ alias]
        (append! (->BlockLink alias)))
      (unblock-link! [_ alias]
        (append! (->UnblockLink alias)))
      (record-click! [_ alias click-id]
        (append! (->Click alias click-id)))
      (get-create-outcome [_ alias request-id]
        (case (foreign-select-one [(keypath alias :outcomes request-id)] links)
          :created  {:outcome :created}
          :rejected {:outcome :rejected :reason :alias-taken}
          nil))
      (resolve-alias [_ alias now]
        (if-let [link (foreign-select-one [(keypath alias :link)] links)]
          {:status     (resolve-status link now)
           :target-url (:target-url link)
           :expires-at (:expires-at link)}
          {:status :missing}))
      (get-click-count [_ alias]
        (long (foreign-select-one [(keypath alias :link :clicks) (nil->val 0)] links)))

      harness/Synchronizable
      (wait-for-processing! [_]
        (rtest/wait-for-microbatch-processed-count ipc module-name "core" @counter)))))

(defn create-module
  []
  (let [counter (atom 0)]
    {:module      UrlShortenerModule
     :wrap-client (fn [ipc] (make-client ipc counter))}))
