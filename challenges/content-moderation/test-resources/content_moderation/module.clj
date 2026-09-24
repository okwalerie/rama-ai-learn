(ns content-moderation.module
  "Private adapter around the unmodified upstream example module."
  (:require [com.rpl.rama :refer [foreign-append! foreign-depot foreign-invoke-query foreign-pstate foreign-query foreign-select-one get-module-name]]
            [com.rpl.rama.path :refer [keypath view]]
            [content-moderation.protocol :as p]
            [nlb.content-moderation :as upstream]
            [rama-challenges.harness :as harness]))

(defn create-module []
  {:module upstream/ContentModerationModule
   :wrap-client
   (fn [ipc]
     (let [module-name (get-module-name upstream/ContentModerationModule)
           posts (foreign-depot ipc module-name "*post-depot")
           mutes (foreign-depot ipc module-name "*mute-depot")
           stored-posts (foreign-pstate ipc module-name "$$posts")
           query (foreign-query ipc module-name "get-posts")]
       (reify p/ContentModeration
         (post! [_ post]
           (foreign-append! posts (upstream/->Post (:from-user-id post)
                                                   (:to-user-id post)
                                                   (:content post))))
         (mute! [_ reader author]
           (foreign-append! mutes (upstream/->Mute reader author)))
         (unmute! [_ reader author]
           (foreign-append! mutes (upstream/->Unmute reader author)))
         (get-posts [_ reader offset limit]
           ;; The original srange throws when start > end. Clamp only that
           ;; out-of-range case; all ordinary pages use the original query.
           (if (> offset (foreign-select-one [(keypath reader) (view count)] stored-posts))
             {:posts [] :next-offset nil}
             (update (foreign-invoke-query query reader offset limit)
                     :posts
                     (fn [results] (mapv #(p/map->Post (into {} %)) results)))))

         harness/Synchronizable
         (wait-for-processing! [_]
           ;; The upstream core stream is fully processed by ACKed appends.
           nil))))})
