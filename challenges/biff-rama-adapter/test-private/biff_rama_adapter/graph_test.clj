(ns biff-rama-adapter.graph-test
  (:require [clojure.test :refer [deftest is testing]]
            [biff-rama-adapter.test-support :as ts]))

(def columns
  {:user/email  {}
   :user/score  {}
   :user/pet-id {:ref :pet/id}
   :pet/species {}
   :pet/owner   {:ref :user/id}})

(deftest generated-resolvers
  (ts/with-cluster [ipc biff-module 4]
    (let [node (ts/start-system! ipc biff-module :columns columns)]
      (try
        (ts/transact! node [[:put :user {:user/id 1 :user/email "ada@x" :user/score 3 :user/pet-id 10}]
                            [:put :user {:user/id 2 :user/email "bob@x"}]
                            [:put :pet {:pet/id 10 :pet/species :iguana :pet/owner 1}]])
        (testing "scalar attributes, -id refs as both value and join, plain refs as joins"
          (is (= {:user/email "ada@x"
                  :user/pet-id 10
                  :user/pet {:pet/species :iguana
                             :pet/owner {:user/email "ada@x"}}}
                 (ts/graph-query node {:user/id 1}
                                 [:user/email
                                  :user/pet-id
                                  {:user/pet [:pet/species
                                              {:pet/owner [:user/email]}]}]))))
        (testing "batched inputs; absent attributes and entities are unresolved"
          (is (= [{:user/id 1 :user/email "ada@x" :user/score 3}
                  {:user/id 2 :user/email "bob@x"}
                  {:user/id 3}]
                 (ts/graph-query node [{:user/id 1} {:user/id 2} {:user/id 3}]
                                 [:user/id [:? :user/email] [:? :user/score]])))
          (is (thrown? Throwable (ts/graph-query node {:user/id 2} [:user/score]))))
        (testing "graph queries read one snapshot"
          (let [seen (ts/in-snapshot
                      node
                      (fn [ctx]
                        (ts/transact! node [[:patch :user {:user/id 1 :user/email "ada2@x"}]
                                            [:patch :pet {:pet/id 10 :pet/species :rock}]])
                        (ts/graph-query {:system ctx} {:user/id 1}
                                        [:user/email {:user/pet [:pet/species]}])))]
            (is (= {:user/email "ada@x" :user/pet {:pet/species :iguana}} seen)))
          (is (= {:user/email "ada2@x" :user/pet {:pet/species :rock}}
                 (ts/graph-query node {:user/id 1} [:user/email {:user/pet [:pet/species]}]))))
        (testing "deleted entities stop resolving"
          (ts/transact! node [[:delete :pet 10]])
          (is (= {:user/pet-id 10 :user/pet {}}
                 (ts/graph-query node {:user/id 1} [:user/pet-id {:user/pet [[:? :pet/species]]}])))
          (is (thrown? Throwable (ts/graph-query node {:user/id 1} [{:user/pet [:pet/species]}]))))
        (finally (ts/stop-system! node))))))
