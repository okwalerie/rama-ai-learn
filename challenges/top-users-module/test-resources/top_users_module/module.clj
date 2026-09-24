(ns top-users-module.module
  "Reference adapter; candidate adapters live under implementations/."
  (:require [com.rpl.rama.test :as rtest]
            [rama-challenges.harness :as harness]
            [top-users-module.protocol :as protocol]
            [rama.gallery.top-users-module :as source])
  (:use [com.rpl.rama]
        [com.rpl.rama.path]))

(defn make-client [ipc count*]
  (let [name (get-module-name source/TopUsersModule)
        depot (foreign-depot ipc name "*purchase-depot")
        top (foreign-pstate ipc name "$$top-spending-users")]
    (reify protocol/TopUsers
      (purchase! [_ user-id purchase-cents]
        (swap! count* inc)
        (foreign-append! depot (source/->Purchase user-id purchase-cents)))
      (top-users [_] (or (foreign-select-one STAY top) []))
      harness/Synchronizable
      (wait-for-processing! [_]
        (rtest/wait-for-microbatch-processed-count ipc name "topusers" @count*)))))

(defn create-module []
  (let [count* (atom 0)]
    {:module source/TopUsersModule
     :wrap-client #(make-client % count*)}))
