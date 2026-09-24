(ns family-tree.module
  "Protocol adapter for the unchanged upstream family-tree module."
  (:require [com.rpl.rama :refer :all]
            [family-tree.protocol :as p]
            [nlb.family-tree :as upstream]
            [rama-challenges.harness :as harness]))

(defn create-module []
  {:module upstream/FamilyTreeModule
   :wrap-client
   (fn [ipc]
     (let [name (get-module-name upstream/FamilyTreeModule)
           depot (foreign-depot ipc name "*people-depot")
           ancestors-query (foreign-query ipc name "ancestors")
           descendants-query (foreign-query ipc name "descendants-count")]
       (reify p/FamilyTree
         (add-person! [_ person]
           (foreign-append! depot
                            (upstream/->Person (:id person) (:parent1 person)
                                               (:parent2 person) (:name person))
                            :ack))
         (ancestors [_ id generations]
           (foreign-invoke-query ancestors-query id generations))
         (descendants-count [_ id generations]
           (foreign-invoke-query descendants-query id generations))
         harness/Synchronizable
         (wait-for-processing! [_]))))})
