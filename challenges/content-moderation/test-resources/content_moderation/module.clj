(ns content-moderation.module
  "Private reference adapted from the upstream example module. Feed and mute
  processing uses a microbatch topology so retries apply each depot record
  exactly once; the queries are unchanged from upstream."
  (:require [com.rpl.rama :refer :all]
            [com.rpl.rama.path :refer :all]
            [com.rpl.rama.aggs :as aggs]
            [com.rpl.rama.test :as rtest]
            [content-moderation.protocol :as p]
            [rama-challenges.harness :as harness])
  (:import [content_moderation.protocol Mute Post Unmute]))

(defmodule ContentModerationModule
  [setup topologies]
  (declare-depot setup *post-depot (hash-by :to-user-id))
  (declare-depot setup *mute-depot (hash-by :user-id))
  (let [topology (microbatch-topology topologies "core")]
    (declare-pstate
      topology
      $$posts
      {Long (vector-schema Post {:subindex? true})})
    (declare-pstate
      topology
      $$mutes
      {Long (set-schema Long {:subindex? true})})
    (<<sources topology
      (source> *post-depot :> %microbatch)
      (%microbatch :> {:keys [*to-user-id] :as *post})
      (local-transform> [(keypath *to-user-id) AFTER-ELEM (termval *post)]
        $$posts)

      (source> *mute-depot :> %microbatch)
      (%microbatch :> *data)
      (<<subsource *data
        (case> Mute :> {:keys [*user-id *muted-user-id]})
        (local-transform> [(keypath *user-id) NONE-ELEM (termval *muted-user-id)]
          $$mutes)

        (case> Unmute :> {:keys [*user-id *unmuted-user-id]})
        (local-transform> [(keypath *user-id) (set-elem *unmuted-user-id) NONE>]
          $$mutes))))
  (<<query-topology topologies "get-posts-helper"
    [*user-id *start-offset *end-offset :> *posts]
    (|hash *user-id)
    (local-select> [(keypath *user-id) (srange *start-offset *end-offset) ALL]
      $$posts :> {:keys [*from-user-id] :as *post})
    (local-select> [(keypath *user-id) (view contains? *from-user-id)]
      $$mutes :> *muted?)
    (filter> (not *muted?))
    (|origin)
    (aggs/+vec-agg *post :> *posts))
  (<<query-topology topologies "get-posts" [*user-id *from-offset *limit :> *ret]
    (|hash *user-id)
    (loop<- [*query-offset *from-offset
             *posts []
             :> *posts *next-offset]
      (local-select> [(keypath *user-id) (view count)] $$posts :> *num-posts)
      (- *limit (count *posts) :> *fetch-amount)
      (min *num-posts (+ *query-offset *fetch-amount) :> *end-offset)
      (invoke-query "get-posts-helper" *user-id *query-offset *end-offset
        :> *fetched-posts)
      (reduce conj *posts *fetched-posts :> *new-posts)
      (<<cond
        (case> (= *end-offset *num-posts))
        (:> *new-posts nil)

        (case> (= (count *new-posts) *limit))
        (:> *new-posts *end-offset)

        (default>)
        (continue> *end-offset *new-posts)))
    (|origin)
    (hash-map :posts *posts :next-offset *next-offset :> *ret)))

(defn create-module []
  {:module ContentModerationModule
   :wrap-client
   (fn [ipc]
     (let [module-name (get-module-name ContentModerationModule)
           posts (foreign-depot ipc module-name "*post-depot")
           mutes (foreign-depot ipc module-name "*mute-depot")
           stored-posts (foreign-pstate ipc module-name "$$posts")
           query (foreign-query ipc module-name "get-posts")
           appended (atom 0)]
       (reify p/ContentModeration
         (post! [_ post]
           (foreign-append! posts post)
           (swap! appended inc))
         (mute! [_ reader author]
           (foreign-append! mutes (p/->Mute reader author))
           (swap! appended inc))
         (unmute! [_ reader author]
           (foreign-append! mutes (p/->Unmute reader author))
           (swap! appended inc))
         (get-posts [_ reader offset limit]
           ;; The original srange throws when start > end. Clamp only that
           ;; out-of-range case; all ordinary pages use the original query.
           (if (> offset (foreign-select-one [(keypath reader) (view count)] stored-posts))
             {:posts [] :next-offset nil}
             (foreign-invoke-query query reader offset limit)))

         harness/Synchronizable
         (wait-for-processing! [_]
           (rtest/wait-for-microbatch-processed-count
             ipc module-name "core" @appended)))))})
