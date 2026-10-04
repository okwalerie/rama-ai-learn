(ns top-users-module.module
  "MUTANT global-user-totals: every user's cumulative total lives in one
  global PState on task 0, so all purchase processing funnels through one
  partition."
  (:require [com.rpl.rama.aggs :as aggs]
            [com.rpl.rama.test :as rtest]
            [rama-challenges.harness :as harness]
            [top-users-module.protocol :as protocol]
            [rama.gallery.top-users-module :as source])
  (:use [com.rpl.rama]
        [com.rpl.rama.path]))

(defgenerator user-spend-subbatch
  [microbatch]
  (batch<- [*user-id *total-spend-cents]
           (microbatch :> {:keys [*user-id *purchase-cents]})
           ;; MUTATION: was (|hash *user-id) over a hash-partitioned PState
           (|global)
           (+compound $$user-total-spend
                      {*user-id (aggs/+sum *purchase-cents
                                           :new-val> *total-spend-cents)})))

(defmodule TopUsersModule
  [setup topologies]
  (declare-depot setup *purchase-depot (hash-by :user-id))
  (let [mb (microbatch-topology topologies "topusers")]
    ;; MUTATION: totals declared :global? true
    (declare-pstate mb $$user-total-spend {Long Long} {:global? true})
    (declare-pstate mb $$top-spending-users java.util.List {:global? true})
    (<<sources mb
      (source> *purchase-depot :> %microbatch)
      (<<batch
        (user-spend-subbatch %microbatch :> *user-id *total-spend-cents)
        (vector *user-id *total-spend-cents :> *tuple)
        (|global)
        (aggs/+top-monotonic [source/TOP-AMOUNT]
                             $$top-spending-users
                             *tuple
                             :+options {:id-fn first
                                        :sort-val-fn last})))))

(defn make-client [ipc count*]
  (let [name (get-module-name TopUsersModule)
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
    {:module TopUsersModule
     :wrap-client #(make-client % count*)}))
