(ns hld-payment-system.private-challenge-test
  (:require [clojure.test :refer [deftest is testing]]
            [com.rpl.rama.test :as rtest]
            [hld-payment-system.protocol :as p]
            [rama-challenges.harness :as harness]))

;; The solver alias deliberately omits test-resources from its classpath.
(defn- run-ledger [create-module tasks]
  (let [{:keys [module wrap-client]} (create-module)]
    (with-open [ipc (rtest/create-ipc)]
      (rtest/launch-module! ipc module {:tasks tasks :threads tasks})
      (let [a (wrap-client ipc)
            b (wrap-client ipc)
            outcome #(p/get-outcome b %1 %2)]
        (testing (str tasks " tasks: precreation rejection, order, cached retries")
          (p/fund! a "early" "USD-tenant" "alice" 7)
          (p/create-tenant! a "tenant" "USD-tenant" "USD")
          (p/create-account! a "alice-create" "USD-tenant" "alice" :customer)
          (p/create-account! a "shop-create" "USD-tenant" "shop" :merchant)
          (p/fund! a "f1" "USD-tenant" "alice" 307)
          (p/charge! a "c1" "USD-tenant" "alice" "shop" 300)
          (p/refund! a "r1" "USD-tenant" "c1" 7)
          (harness/wait-for-processing! b)
          (is (= {:status :rejected :command :fund :reason :no-such-tenant
                  :conflicting-attempts 0} (outcome "USD-tenant" "early")))
          (is (= {:status :accepted :command :fund :seq 1 :balance 307
                  :conflicting-attempts 0} (outcome "USD-tenant" "f1")))
          (is (= {:status :accepted :command :charge :charge-id "c1" :seq 2
                  :customer-balance 7 :conflicting-attempts 0} (outcome "USD-tenant" "c1")))
          (is (= {:status :accepted :command :refund :refund-id "r1" :charge-id "c1"
                  :seq 3 :refunded-total 7 :conflicting-attempts 0}
                 (outcome "USD-tenant" "r1")))
          (is (= {:charge-id "c1" :customer-id "alice" :merchant-id "shop"
                  :amount 300 :refunded-total 7 :seq 2}
                 (p/get-charge b "USD-tenant" "c1")))
          (is (= [14 -307] [(p/get-balance b "USD-tenant" "alice")
                              (p/get-balance b "USD-tenant" "clearing")]))
          (is (= 293 (p/get-balance b "USD-tenant" "shop")))
          (is (= {:account-id "shop" :kind :merchant :balance 293}
                 (p/get-account b "USD-tenant" "shop")))
          (is (= [{:seq 1 :request-id "f1" :type :fund :charge-id nil
                   :postings [{:account-id "clearing" :delta -307}
                              {:account-id "alice" :delta 307}]}
                  {:seq 2 :request-id "c1" :type :charge :charge-id "c1"
                   :postings [{:account-id "alice" :delta -300}
                              {:account-id "shop" :delta 300}]}
                  {:seq 3 :request-id "r1" :type :refund :charge-id "c1"
                   :postings [{:account-id "shop" :delta -7}
                              {:account-id "alice" :delta 7}]}]
                 (p/get-journal b "USD-tenant" 0 500)))
          (is (= [] (p/get-journal b "USD-tenant" Long/MAX_VALUE 1)))
          (p/fund! b "early" "USD-tenant" "alice" 7)
          (p/fund! b "early" "USD-tenant" "alice" 8)
          (p/charge! b "c1" "USD-tenant" "alice" "shop" 300)
          (p/refund! b "c1" "USD-tenant" "c1" 1)
          (harness/wait-for-processing! a)
          (is (= 1 (:conflicting-attempts (outcome "USD-tenant" "early"))))
          (is (= 1 (:conflicting-attempts (outcome "USD-tenant" "c1"))))
          (is (= 3 (count (p/get-journal a "USD-tenant" 0 500))))
          (is (thrown? IllegalArgumentException
                       (p/charge! a "c1" "USD-tenant" "alice" "shop" 0)))
          (is (thrown? IllegalArgumentException
                       (p/create-account! b "bad" "USD-tenant" "clearing" :customer)))
          (p/create-account! b "barrier" "USD-tenant" "new" :customer)
          (harness/wait-for-processing! a)
          (is (= 1 (:conflicting-attempts (outcome "USD-tenant" "c1"))))
          (is (nil? (outcome "USD-tenant" "bad")))
          (p/create-account! b "bad" "USD-tenant" "new2" :customer)
          (harness/wait-for-processing! a)
          (is (= :accepted (:status (outcome "USD-tenant" "bad")))))
        (testing "refund boundaries, independent currency ledgers and update"
          (p/refund! a "r2" "USD-tenant" "c1" 293)
          (p/refund! a "r3" "USD-tenant" "c1" 1)
          (p/create-tenant! b "tenant" "EUR-tenant" "EUR")
          (p/create-account! b "alice-create" "EUR-tenant" "alice" :customer)
          (p/fund! b "f1" "EUR-tenant" "alice" 1000000000000)
          (harness/wait-for-processing! a)
          (is (= :refund-exceeds-charge (:reason (outcome "USD-tenant" "r3"))))
          (is (= 300 (:refunded-total (p/get-charge a "USD-tenant" "c1"))))
          (is (= 2 (:seq (p/get-charge a "USD-tenant" "c1"))))
          (is (= 1000000000000 (p/get-balance a "EUR-tenant" "alice")))
          (is (= {:tenant-id "EUR-tenant" :currency "EUR"}
                 (p/get-tenant a "EUR-tenant")))
          (is (= [1 2 3 4] (mapv :seq (p/get-journal a "USD-tenant" 0 500))))
          (rtest/update-module! ipc module)
          (is (= 307 (p/get-balance b "USD-tenant" "alice")))
          (is (= 0 (p/get-balance b "USD-tenant" "shop")))
          (is (= 300 (:refunded-total (p/get-charge b "USD-tenant" "c1"))))
          (is (= :no-such-tenant (:reason (outcome "USD-tenant" "early"))))
          (is (= {:seq 2 :request-id "c1" :type :charge :charge-id "c1"
                  :postings [{:account-id "alice" :delta -300}
                             {:account-id "shop" :delta 300}]}
                 (second (p/get-journal b "USD-tenant" 0 500))))
          (p/charge! b "c1" "USD-tenant" "alice" "shop" 301)
          (p/charge! b "c1" "USD-tenant" "alice" "shop" 302)
          (p/fund! b "early" "USD-tenant" "alice" 7)
          (harness/wait-for-processing! a)
          (is (= 3 (:conflicting-attempts (outcome "USD-tenant" "c1"))))
          (is (= 1 (:conflicting-attempts (outcome "USD-tenant" "early"))))
          (is (= 2 (:seq (outcome "USD-tenant" "c1"))))
          (p/fund! a "f2" "USD-tenant" "alice" 1)
          (harness/wait-for-processing! b)
          (is (= 5 (:seq (outcome "USD-tenant" "f2"))))
          (is (= 1 (:seq (outcome "EUR-tenant" "f1")))))
        (testing "rejection order and cached failure after changing funds"
          (p/charge! a "over" "USD-tenant" "alice" "shop" 1000)
          (p/charge! a "wrong" "USD-tenant" "shop" "alice" 1)
          (p/charge! a "missing" "USD-tenant" "absent" "shop" 1)
          (p/refund! a "unknown" "USD-tenant" "missing" 1)
          (harness/wait-for-processing! b)
          (is (= :insufficient-funds (:reason (outcome "USD-tenant" "over"))))
          (is (= :wrong-account-kind (:reason (outcome "USD-tenant" "wrong"))))
          (is (= :no-such-account (:reason (outcome "USD-tenant" "missing"))))
          (is (= :no-such-charge (:reason (outcome "USD-tenant" "unknown"))))
          (p/fund! b "topup" "USD-tenant" "alice" 1000)
          (p/charge! b "over" "USD-tenant" "alice" "shop" 1000)
          (harness/wait-for-processing! a)
          (is (= :insufficient-funds (:reason (outcome "USD-tenant" "over"))))
          (is (= 6 (count (p/get-journal a "USD-tenant" 0 500)))))
        (testing "competing reasons and cross-command idempotency"
          ;; A missing account wins over a wrong kind or insufficient funds.
          (p/charge! a "mixed-charge" "USD-tenant" "shop" "absent" 1000000)
          (p/create-account! a "other-shop" "USD-tenant" "other-shop" :merchant)
          (p/charge! a "drain" "USD-tenant" "alice" "other-shop" 1000)
          (p/refund! a "mixed-refund" "USD-tenant" "c1" 301)
          (p/create-account! a "duplicate-account" "USD-tenant" "shop" :customer)
          (harness/wait-for-processing! b)
          (is (= :no-such-account (:reason (outcome "USD-tenant" "mixed-charge"))))
          ;; The charge was fully refunded, and shop is empty: exceeds wins.
          (is (= :refund-exceeds-charge (:reason (outcome "USD-tenant" "mixed-refund"))))
          (is (= :account-exists (:reason (outcome "USD-tenant" "duplicate-account"))))
          (p/fund! b "duplicate-account" "USD-tenant" "alice" 9)
          (p/charge! b "mixed-refund" "USD-tenant" "alice" "shop" 1)
          (p/refund! b "drain" "USD-tenant" "c1" 1)
          ;; Fund and refund have identical argument vectors here, but different types.
          (p/refund! b "f2" "USD-tenant" "alice" 1)
          (harness/wait-for-processing! a)
          (is (= 1 (:conflicting-attempts (outcome "USD-tenant" "duplicate-account"))))
          (is (= :refund-exceeds-charge (:reason (outcome "USD-tenant" "mixed-refund"))))
          (is (= 1 (:conflicting-attempts (outcome "USD-tenant" "mixed-refund"))))
          (is (= 1 (:conflicting-attempts (outcome "USD-tenant" "drain"))))
          (is (= 1 (:conflicting-attempts (outcome "USD-tenant" "f2"))))
          (is (= [1 2 3 4 5 6 7] (mapv :seq (p/get-journal b "USD-tenant" 0 500)))))))))

(deftest ledger-two-tasks
  (run-ledger (requiring-resolve 'hld-payment-system.module/create-module) 2))

(deftest ledger-four-tasks
  (run-ledger (requiring-resolve 'hld-payment-system.module/create-module) 4))

(defn- storage-cost [f]
  (let [counts (atom {:reads 0 :writes 0})]
    (rtest/with-event-hook
      (fn [event-type data]
        (case event-type
          (:rocks-read :rocks-iterator :rocks-iterator-read)
          (swap! counts update :reads inc)
          :rocks-commit
          (swap! counts update :writes + (:write-batch-count data))
          nil))
      (f)
      @counts)))

(defn- run-growth [tasks]
  (let [{:keys [module wrap-client]} ((requiring-resolve 'hld-payment-system.module/create-module))]
    (with-open [ipc (rtest/create-ipc)]
      (rtest/launch-module! ipc module {:tasks tasks :threads tasks})
      (let [c (wrap-client ipc)]
        (p/create-tenant! c "t" "deep" "USD")
        (p/create-tenant! c "other-t" "other" "EUR")
        (p/create-account! c "a" "deep" "a" :customer)
        (p/create-account! c "w" "deep" "w" :customer)
        (p/create-account! c "m" "deep" "m" :merchant)
        (p/create-account! c "other-a" "other" "a" :customer)
        (p/fund! c "reader-fund" "deep" "a" 7)
        (p/fund! c "seed" "deep" "w" 5000)
        (p/charge! c "seed-charge" "deep" "w" "m" 10)
        (harness/wait-for-processing! c)
        (let [measure
              (fn [stage]
                (let [rid (str "probe-" stage)
                      observe (fn [f] (let [value (volatile! nil)
                                            cost (storage-cost #(vreset! value (f)))]
                                        [@value cost]))
                      [page page-cost] (observe #(p/get-journal c "deep" 150 7))
                      [balance balance-cost] (observe #(p/get-balance c "deep" "a"))
                      [account account-cost] (observe #(p/get-account c "deep" "a"))
                      [charge charge-cost] (observe #(p/get-charge c "deep" "seed-charge"))
                      [outcome outcome-cost] (observe #(p/get-outcome c "deep" "seed-charge"))
                      [tenant tenant-cost] (observe #(p/get-tenant c "deep"))
                      fund-cost (storage-cost #(do (p/fund! c (str rid "-fund") "deep" "w" 1)
                                                  (harness/wait-for-processing! c)))
                      charge-write-cost (storage-cost #(do (p/charge! c (str rid "-charge") "deep" "w" "m" 2)
                                                          (harness/wait-for-processing! c)))
                      refund-cost (storage-cost #(do (p/refund! c (str rid "-refund") "deep" (str rid "-charge") 1)
                                                    (harness/wait-for-processing! c)))
                      costs {:page page-cost :balance balance-cost :account account-cost
                             :charge charge-cost :outcome outcome-cost :tenant tenant-cost
                             :fund fund-cost :charge-write charge-write-cost :refund refund-cost}]
                  (is (= (vec (range 151 158)) (mapv :seq page)))
                  (is (= (mapv #(str "charge-" %) (range 147 154))
                         (mapv :request-id page)))
                  (is (= {:account-id "a" :kind :customer :balance 7} account))
                  (is (= 3 (:seq charge)))
                  (is (= :accepted (:status outcome)))
                  (is (= "USD" (:currency tenant)))
                  (is (= 7 balance))
                  (is (= :accepted (:status (p/get-outcome c "deep" (str rid "-refund")))))
                  costs))]
          (let [samples (for [[stage own-size] [[0 200] [1 1000]]]
                          (do
                            (doseq [i (range (if (zero? stage) 0 200) own-size)]
                              (p/create-account! c (str "account-" i) "deep" (str "m-" i) :merchant)
                              (p/charge! c (str "charge-" i) "deep" "w" (str "m-" i) 1)
                              (p/fund! c (str "other-fund-" i) "other" "a" 1))
                            (harness/wait-for-processing! c)
                            (is (= own-size (p/get-balance c "other" "a")))
                            (measure stage)))
                [small large] (doall samples)]
            ;; Finite growth heuristic, deliberately not an exact topology/seek budget.
            ;; Hooks cannot see bytes deserialized or rewritten in an opaque value.
            (println "Payment storage growth" tasks "tasks, 200→1000 own charges/accounts and unrelated funds" small large)
            (doseq [op (keys small)
                    :let [lo (get small op) hi (get large op)]]
              (is (pos? (:reads lo)) (str tasks " tasks " op " no observed reads: " lo))
              (when (#{:fund :charge-write :refund} op)
                (is (pos? (:writes lo)) (str tasks " tasks " op " no observed writes: " lo)))
              (doseq [metric [:reads :writes]]
                (is (<= (get hi metric) (+ 60 (* 1.25 (get lo metric))))
                    (str tasks " tasks " op " " metric " grows with own/unrelated history: " lo " → " hi))))))))))

(deftest bounded-growth-two-tasks
  (run-growth 2))

(deftest bounded-growth-four-tasks
  (run-growth 4))
