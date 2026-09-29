(ns hld-payment-system.nfr-test
  "NFR tests: exactly-once money movement under an engine (stream) retry, and
   journal pages that walk a subindexed structure."
  (:require [clojure.test :refer [deftest is testing]]
            [com.rpl.rama.test :as rtest]
            [hld-payment-system.protocol :as p]
            [rama-challenges.harness :as harness]
            [rama-challenges.nfr :as nfr]))

(defn- create-module []
  ((requiring-resolve 'hld-payment-system.module/create-module)))

(defn- forced [c f]
  ;; Wraps only the command under test and its wait, so a forced stream
  ;; failure hits that command. A microbatch design sees zero forced retries.
  (nfr/with-forced-stream-retry 2 #(do (f) (harness/wait-for-processing! c))))

(defn- run-retry [tasks]
  (let [{:keys [module wrap-client]} (create-module)]
    (with-open [ipc (rtest/create-ipc)]
      (rtest/launch-module! ipc module {:tasks tasks :threads tasks})
      (let [c (wrap-client ipc)
            outcome #(p/get-outcome c "T" %)]
        (p/create-tenant! c "t" "T" "USD")
        (p/create-account! c "alice" "T" "alice" :customer)
        (p/create-account! c "shop" "T" "shop" :merchant)
        (p/fund! c "f1" "T" "alice" 1000)
        (harness/wait-for-processing! c)
        (testing (str tasks " tasks: fund, charge and refund under forced retry apply once")
          (forced c #(p/charge! c "c1" "T" "alice" "shop" 300))
          (forced c #(p/refund! c "r1" "T" "c1" 100))
          (forced c #(p/fund! c "f2" "T" "alice" 50))
          (p/fund! c "f3" "T" "alice" 1)
          (harness/wait-for-processing! c)
          (let [journal (p/get-journal c "T" 0 500)]
            (is (= [1 2 3 4 5] (mapv :seq journal))
                "exactly one journal row per accepted command, seqs contiguous")
            (is (= ["f1" "c1" "r1" "f2" "f3"] (mapv :request-id journal))))
          (is (= 851 (p/get-balance c "T" "alice")))
          (is (= 200 (p/get-balance c "T" "shop")))
          (is (= -1051 (p/get-balance c "T" "clearing")))
          (is (= 100 (:refunded-total (p/get-charge c "T" "c1"))))
          (is (= {:status :accepted :command :charge :charge-id "c1" :seq 2
                  :customer-balance 700 :conflicting-attempts 0}
                 (outcome "c1")))
          (is (= {:status :accepted :command :refund :refund-id "r1" :charge-id "c1"
                  :seq 3 :refunded-total 100 :conflicting-attempts 0}
                 (outcome "r1")))
          (is (= {:status :accepted :command :fund :seq 4 :balance 850
                  :conflicting-attempts 0}
                 (outcome "f2")))
          (is (= 5 (:seq (outcome "f3")))))))))

(deftest exactly-once-under-forced-stream-retry
  (doseq [tasks [2 4]] (run-retry tasks)))

(defn- run-journal-iterators [tasks]
  (let [{:keys [module wrap-client]} (create-module)]
    (with-open [ipc (rtest/create-ipc)]
      (rtest/launch-module! ipc module {:tasks tasks :threads tasks})
      (let [c (wrap-client ipc)]
        (p/create-tenant! c "t" "T" "USD")
        (p/create-account! c "alice" "T" "alice" :customer)
        (doseq [i (range 120)]
          (p/fund! c (str "f" i) "T" "alice" 1))
        (harness/wait-for-processing! c)
        (doseq [limit [5 20]]
          (let [[page iter-reads]
                (let [[ret ops] (nfr/capture-rocks-ops-with-result
                                 #(p/get-journal c "T" 40 limit))]
                  [ret (:iterator-reads ops)])]
            (is (= (vec (range 41 (+ 41 limit))) (mapv :seq page)))
            (is (< 0 iter-reads (+ limit 4))
                (str tasks " tasks limit " limit
                     ": get-journal must walk a subindexed journal, reading about limit rows; saw "
                     iter-reads " iterator reads"))))))))

(deftest journal-page-walks-subindexed-journal
  (doseq [tasks [2 4]] (run-journal-iterators tasks)))
