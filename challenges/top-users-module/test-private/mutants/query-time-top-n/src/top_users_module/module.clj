(ns top-users-module.module
  "MUTANT query-time-top-n: only per-user totals are maintained; every
  top-users read runs a query topology that scans every user's total on every
  task and sorts them."
  (:require [com.rpl.rama.aggs :as aggs]
            [com.rpl.rama.test :as rtest]
            [rama-challenges.harness :as harness]
            [top-users-module.protocol :as protocol]
            [rama.gallery.top-users-module :as source])
  (:use [com.rpl.rama]
        [com.rpl.rama.path]))

(defn top-n [tuples]
  (->> tuples (sort-by (comp - second)) (take source/TOP-AMOUNT) vec))

(defmodule TopUsersModule
  [setup topologies]
  (declare-depot setup *purchase-depot (hash-by :user-id))
  (let [mb (microbatch-topology topologies "topusers")]
    (declare-pstate mb $$user-total-spend {Long Long})
    (<<sources mb
      (source> *purchase-depot :> %microbatch)
      (%microbatch :> {:keys [*user-id *purchase-cents]})
      (+compound $$user-total-spend
                 {*user-id (aggs/+sum *purchase-cents)})))
  ;; MUTATION: no precomputed top list; rank at read time over all users.
  (<<query-topology topologies "top-users" [:> *res]
    (|all)
    (local-select> ALL $$user-total-spend :> *tuple)
    (|origin)
    (aggs/+vec-agg *tuple :> *all)
    (top-n *all :> *res)))

(defn make-client [ipc count*]
  (let [name (get-module-name TopUsersModule)
        depot (foreign-depot ipc name "*purchase-depot")
        q (foreign-query ipc name "top-users")]
    (reify protocol/TopUsers
      (purchase! [_ user-id purchase-cents]
        (swap! count* inc)
        (foreign-append! depot (source/->Purchase user-id purchase-cents)))
      (top-users [_] (or (foreign-invoke-query q) []))
      harness/Synchronizable
      (wait-for-processing! [_]
        (rtest/wait-for-microbatch-processed-count ipc name "topusers" @count*)))))

(defn create-module []
  (let [count* (atom 0)]
    {:module TopUsersModule
     :wrap-client #(make-client % count*)}))
