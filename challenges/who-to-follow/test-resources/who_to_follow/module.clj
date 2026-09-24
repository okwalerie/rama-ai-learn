(ns who-to-follow.module
  (:require [com.rpl.rama.test :as rtest]
            [rama-challenges.harness :as harness]
            [who-to-follow.protocol :as protocol]
            [nlb.who-to-follow :as source])
  (:use [com.rpl.rama]
        [com.rpl.rama.path]))

(defn make-client [ipc pending]
  (let [name (get-module-name source/WhoToFollowModule)
        follows (foreign-depot ipc name "*follows-depot")
        tick (foreign-depot ipc name "*who-to-follow-tick")
        results (foreign-pstate ipc name "$$who-to-follow")]
    (reify protocol/WhoToFollow
      (follow! [_ from to] (foreign-append! follows (source/->Follow from to)) nil)
      (refresh! [_] (swap! pending inc) (foreign-append! tick nil) nil)
      (recommendations [_ account-id] (or (foreign-select-one (keypath account-id) results) []))
      harness/Synchronizable
      (wait-for-processing! [_]
        (rtest/wait-for-microbatch-processed-count ipc name "who-to-follow" @pending)))))

(defn create-module []
  (let [pending (atom 0)]
    {:module source/WhoToFollowModule
     :wrap-client #(make-client % pending)}))
