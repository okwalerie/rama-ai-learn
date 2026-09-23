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
          (is (= 6 (count (p/get-journal a "USD-tenant" 0 500)))))))))

(deftest ledger-two-tasks
  (run-ledger (requiring-resolve 'hld-payment-system.module/create-module) 2))

(deftest ledger-four-tasks
  (run-ledger (requiring-resolve 'hld-payment-system.module/create-module) 4))

(defn- read-cost [f]
  (let [counts (atom {:rocks-read 0 :rocks-iterator-read 0})]
    (rtest/with-event-hook
      (fn [event-type _]
        (when (contains? @counts event-type)
          (swap! counts update event-type inc)))
      (f)
      (reduce + (vals @counts)))))

(deftest bounded-own-history
  (let [{:keys [module wrap-client]} ((requiring-resolve 'hld-payment-system.module/create-module))]
    (with-open [ipc (rtest/create-ipc)]
      (rtest/launch-module! ipc module {:tasks 4 :threads 4})
      (let [c (wrap-client ipc)]
        (p/create-tenant! c "t" "deep" "USD")
        (p/create-account! c "a" "deep" "a" :customer)
        (p/create-account! c "m" "deep" "m" :merchant)
        (harness/wait-for-processing! c)
        (doseq [i (range 520)] (p/fund! c (str "f" i) "deep" "a" 1))
        (harness/wait-for-processing! c)
        (is (= 520 (p/get-balance c "deep" "a")))
        (is (= (vec (range 501 508))
               (mapv :seq (p/get-journal c "deep" 500 7))))
        (is (= 500 (count (p/get-journal c "deep" 0 500))))
        ;; Hook counts RocksDB operations, not opaque serialized value bytes.
        ;; Layout inspection is necessary to rule out a single rewritten blob.
        (let [page-cost (read-cost #(p/get-journal c "deep" 500 7))
              balance-cost (read-cost #(p/get-balance c "deep" "a"))
              outcome-cost (read-cost #(p/get-outcome c "deep" "f519"))]
          (is (< 0 page-cost 80) (str "bounded page reads: " page-cost))
          (is (< 0 balance-cost 40) (str "bounded balance reads: " balance-cost))
          (is (< 0 outcome-cost 40) (str "bounded outcome reads: " outcome-cost)))
        (let [write-cost (read-cost #(do (p/charge! c "last-charge" "deep" "a" "m" 7)
                                         (harness/wait-for-processing! c)))]
          (is (< 0 write-cost 100) (str "bounded charge reads: " write-cost))
          (is (= 513 (p/get-balance c "deep" "a")))
          (is (= 521 (:seq (p/get-charge c "deep" "last-charge")))))))))
