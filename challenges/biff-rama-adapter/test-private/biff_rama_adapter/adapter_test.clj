(ns biff-rama-adapter.adapter-test
  (:require [clojure.test :refer [deftest is testing]]
            [biff-rama-adapter.test-support :as ts]))

(def columns
  {:user/email  {}
   :user/score  {}
   :user/pet-id {:ref :pet/id}
   :pet/species {}
   :pet/owner   {:ref :user/id}})

(deftest biff-module-shape
  (let [{:keys [module biff-module]} (ts/factory)
        plain (biff-module)
        with-columns (biff-module :biff.rama/columns columns)]
    (is (some? module))
    (is (= :biff.rama/module (:biff.core/id plain)))
    (is (fn? (:biff.core/start plain)))
    (is (fn? (:biff.core/stop plain)))
    (is (every? fn? (map (:biff.fx/handlers plain) [:biff.rama.fx/transact :biff.rama.fx/entity])))
    (is (empty? (:biff.graph/resolvers plain)))
    (is (seq (:biff.graph/resolvers with-columns)))))

(deftest kv-contract
  (ts/with-cluster [ipc biff-module 2]
    (let [node (ts/start-system! ipc biff-module)]
      (try
        (is (nil? (ts/kv-get node :app/things "missing")))
        (is (= [] (vec (ts/kv-list node :app/things))))
        (let [value {:n 1 :tags #{:a :b} :at #inst "2026-01-02T03:04:05.000-00:00"
                     :id #uuid "0195ad3c-0000-7000-8000-000000000001" :xs [1 "two" 3.0]}]
          (is (nil? (ts/kv-set! node :app/things "b/2" value)))
          (is (= value (ts/kv-get node :app/things "b/2"))))
        (ts/kv-set! node :app/things "a/1" 1)
        (ts/kv-set! node :app/things "b/1" "one")
        (ts/kv-set! node :app/things "c" false)
        (ts/kv-set! node :app/other "a/1" :other)
        (testing "keys are sorted, filtered by prefix and isolated per namespace"
          (is (= ["a/1" "b/1" "b/2" "c"] (vec (ts/kv-list node :app/things))))
          (is (= ["b/1" "b/2"] (vec (ts/kv-list node :app/things "b/"))))
          (is (= ["a/1" "b/1" "b/2" "c"] (vec (ts/kv-list node :app/things nil))))
          (is (= [] (vec (ts/kv-list node :app/things "z"))))
          (is (= ["a/1"] (vec (ts/kv-list node :app/other))))
          (is (= :other (ts/kv-get node :app/other "a/1")))
          (is (= 1 (ts/kv-get node :app/things "a/1"))))
        (testing "false is a value, nil deletes, set replaces"
          (is (false? (ts/kv-get node :app/things "c")))
          (ts/kv-set! node :app/things "a/1" 2)
          (is (= 2 (ts/kv-get node :app/things "a/1")))
          (ts/kv-set! node :app/things "b/1" nil)
          (is (nil? (ts/kv-get node :app/things "b/1")))
          (is (= ["a/1" "b/2" "c"] (vec (ts/kv-list node :app/things))))
          (ts/kv-set! node :app/things "never-set" nil)
          (is (= ["a/1" "b/2" "c"] (vec (ts/kv-list node :app/things)))))
        (testing "invalid namespaces and keys are rejected"
          (is (thrown? Throwable (ts/kv-set! node :unqualified "k" 1)))
          (is (thrown? Throwable (ts/kv-set! node :app/things :not-a-string 1)))
          (is (thrown? Throwable (ts/kv-get node "app/things" "k")))
          (is (thrown? Throwable (ts/kv-list node :app/things :prefix))))
        (testing "a second node sees the same store"
          (let [other (ts/start-system! ipc biff-module)]
            (try
              (is (= ["a/1" "b/2" "c"] (vec (ts/kv-list other :app/things))))
              (finally (ts/stop-system! other)))))
        (finally (ts/stop-system! node))))))

(deftest transaction-semantics
  (ts/with-cluster [ipc biff-module 4]
    (let [node (ts/start-system! ipc biff-module)]
      (try
        (is (nil? (ts/entity node :user 1)))
        (ts/transact! node [[:put :user {:user/id 1 :user/email "a@x" :user/score 1}]
                            [:put :pet {:pet/id 1 :pet/species :rock}]])
        (testing "reads after transact returns see the transaction"
          (is (= {:user/id 1 :user/email "a@x" :user/score 1} (ts/entity node :user 1)))
          (is (= {:pet/id 1 :pet/species :rock} (ts/entity node :pet 1)))
          (is (nil? (ts/entity node :user 2)) "tables are separate id spaces"))
        (testing "put replaces the whole entity"
          (ts/transact! node [[:put :user {:user/id 1 :user/email "b@x"}]])
          (is (= {:user/id 1 :user/email "b@x"} (ts/entity node :user 1))))
        (testing "patch merges; nil removes an attribute; patch creates a missing entity"
          (ts/transact! node [[:patch :user {:user/id 1 :user/score 7}]])
          (is (= {:user/id 1 :user/email "b@x" :user/score 7} (ts/entity node :user 1)))
          (ts/transact! node [[:patch :user {:user/id 1 :user/email nil}]])
          (is (= {:user/id 1 :user/score 7} (ts/entity node :user 1)))
          (ts/transact! node [[:patch :user {:user/id "u-3" :user/score 3}]])
          (is (= {:user/id "u-3" :user/score 3} (ts/entity node :user "u-3"))))
        (testing "delete removes; a later put recreates"
          (ts/transact! node [[:delete :pet 1]])
          (is (nil? (ts/entity node :pet 1)))
          (ts/transact! node [[:delete :pet 404]])
          (ts/transact! node [[:patch :pet {:pet/id 1 :pet/species :iguana}]])
          (is (= {:pet/id 1 :pet/species :iguana} (ts/entity node :pet 1))))
        (testing "ops in one transaction apply in order"
          (ts/transact! node [[:put :user {:user/id 9 :user/email "first"}]
                              [:patch :user {:user/id 9 :user/score 1}]
                              [:delete :user 9]
                              [:put :user {:user/id 9 :user/email "last"}]
                              [:patch :user {:user/id 9 :user/score 2}]])
          (is (= {:user/id 9 :user/email "last" :user/score 2} (ts/entity node :user 9)))
          (ts/transact! node [[:put :user {:user/id 10 :user/email "gone"}] [:delete :user 10]])
          (is (nil? (ts/entity node :user 10))))
        (testing "ids may be any EDN value"
          (let [id [:composite #uuid "0195ad3c-0000-7000-8000-000000000002" 3]]
            (ts/transact! node [[:put :user {:user/id id :user/email "c"}]])
            (is (= {:user/id id :user/email "c"} (ts/entity node :user id)))))
        (testing "invalid transactions throw and write nothing"
          (doseq [bad [[]
                       [[:put :user {:user/email "no-id"}]]
                       [[:put :user {:user/id 50 :pet/species :rock}]]
                       [[:put :user {:user/id 50 :email "unqualified"}]]
                       [[:put :user/qualified {:user/id 50}]]
                       [[:upsert :user {:user/id 50}]]
                       [[:delete :user nil]]
                       [[:put :user {:user/id 50 :user/email "ok"}] [:patch :pet {:species :x}]]
                       [[:put :user {:user/id 50 :user/email "ok"}] [:put :user]]]]
            (is (thrown? Throwable (ts/transact! node bad)) (pr-str bad)))
          (is (nil? (ts/entity node :user 50)))
          (is (nil? (ts/entity node :pet nil))))
        (finally (ts/stop-system! node))))))

(deftest on-tx-notifications
  (ts/with-cluster [ipc biff-module 4]
    (let [a (ts/start-system! ipc biff-module)
          b-ref (promise)
          b (ts/start-system! ipc biff-module
                              :on-tx-read (fn [ctx] (ts/entity @b-ref ctx :user 77)))]
      (deliver b-ref b)
      (try
        (Thread/sleep 1000)
        (testing "an idle system is not notified"
          (let [ca @(:on-tx-calls a) cb @(:on-tx-calls b)]
            (Thread/sleep 1500)
            (is (= ca @(:on-tx-calls a)))
            (is (= cb @(:on-tx-calls b)))))
        (let [before-a @(:on-tx-calls a)
              before-b @(:on-tx-calls b)]
          (ts/transact! a [[:put :user {:user/id 77 :user/email "seen"}]])
          (testing "the writing node is notified before transact returns"
            (is (< before-a @(:on-tx-calls a))))
          (testing "every other node is notified, and reads in on-tx see the transaction"
            (is (ts/eventually 10000 #(< before-b @(:on-tx-calls b))))
            (is (ts/eventually 10000 #(some #{{:user/id 77 :user/email "seen"}} @(:on-tx-reads b))))
            (is (= {:user/id 77 :user/email "seen"} (ts/entity b :user 77)))))
        (testing "a stopped node is no longer notified"
          (ts/stop-system! b)
          (Thread/sleep 500)
          (let [cb @(:on-tx-calls b)]
            (ts/transact! a [[:put :user {:user/id 78}]])
            (Thread/sleep 1500)
            (is (= cb @(:on-tx-calls b)))))
        (finally (ts/stop-system! a))))))

(deftest snapshot-isolation
  (ts/with-cluster [ipc biff-module 4]
    (let [node (ts/start-system! ipc biff-module)]
      (try
        (ts/transact! node [[:put :user {:user/id 1 :user/score 1}]
                            [:put :user {:user/id 2 :user/score 1}]])
        (let [seen (ts/in-snapshot
                    node
                    (fn [ctx]
                      (let [a1 (ts/entity node ctx :user 1)]
                        (ts/transact! node [[:put :user {:user/id 1 :user/score 2}]
                                            [:put :user {:user/id 2 :user/score 2}]
                                            [:put :user {:user/id 3 :user/score 2}]])
                        {:a1 a1
                         :b (ts/entity node ctx :user 2)
                         :c (ts/entity node ctx :user 3)
                         :a2 (ts/entity node ctx :user 1)})))]
          (testing "reads inside one snapshot ignore later transactions"
            (is (= {:a1 {:user/id 1 :user/score 1}
                    :b {:user/id 2 :user/score 1}
                    :c nil
                    :a2 {:user/id 1 :user/score 1}}
                   seen)))
          (testing "a snapshot started after transact returns includes it"
            (is (= [2 2 2] (ts/in-snapshot node (fn [ctx] (mapv #(:user/score (ts/entity node ctx :user %)) [1 2 3])))))
            (is (= {:user/id 3 :user/score 2} (ts/entity node :user 3)))))
        (finally (ts/stop-system! node))))))

(defn- spread
  "Every transaction rewrites every account under one tag. Balances sum to
   `total`, so a state mixing two tags or a different sum is torn."
  [n total tag ^java.util.Random rnd]
  (let [cuts (sort (repeatedly (dec n) #(.nextInt rnd (inc total))))
        balances (map - (concat cuts [total]) (cons 0 cuts))]
    (vec (map-indexed (fn [i b] [:put :account {:account/id i :account/balance b :account/tag tag}])
                      balances))))

(deftest atomic-multi-partition-transactions
  (ts/with-cluster [ipc biff-module 4]
    (let [node (ts/start-system! ipc biff-module)
          n 16
          total 1600]
      (try
        (ts/transact! node (spread n total [:init] (java.util.Random. 0)))
        (let [stop? (atom false)
              torn (atom [])
              snapshots (atom 0)
              tags (atom #{})
              writers (doall
                       (for [w (range 4)]
                         (future
                           (let [rnd (java.util.Random. (inc w))]
                             (dotimes [k 6]
                               (ts/transact! node (spread n total [w k] rnd)))))))
              readers (doall
                       (for [_ (range 3)]
                         (future
                           (while (not @stop?)
                             (let [docs (ts/in-snapshot node (fn [ctx] (mapv #(ts/entity node ctx :account %) (range n))))]
                               (swap! snapshots inc)
                               (swap! tags conj (:account/tag (first docs)))
                               (when-not (and (every? some? docs)
                                              (apply = (map :account/tag docs))
                                              (= total (reduce + (map :account/balance docs))))
                                 (swap! torn conj docs)))))))]
          (run! deref writers)
          (reset! stop? true)
          (run! deref readers)
          (is (< 10 @snapshots))
          (is (< 2 (count @tags)) "readers should overlap several transactions")
          (is (empty? @torn) (str (count @torn) " torn snapshots, e.g. " (pr-str (first @torn))))
          (testing "once writes stop, plain reads see the last whole transaction"
            (let [docs (mapv #(ts/entity node :account %) (range n))]
              (is (apply = (map :account/tag docs)))
              (is (= total (reduce + (map :account/balance docs)))))))
        (finally (ts/stop-system! node))))))
