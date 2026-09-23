(ns hld-job-scheduler.functional-test-support
  (:require [clojure.test :refer [is testing]]
            [com.rpl.rama.test :as rtest]
            [rama-challenges.harness :as h]
            [hld-job-scheduler.protocol :as p]))

(defn- exercise [create-module tasks f]
  (let [{:keys [module wrap-client]} (create-module)]
    (with-open [ipc (rtest/create-ipc)]
      (rtest/launch-module! ipc module {:tasks tasks :threads tasks})
      (f ipc module (wrap-client ipc) wrap-client))))

(defn test-scheduler [create-module]
  (doseq [tasks [2 4]]
    (testing (str tasks " tasks")
      (exercise create-module tasks
        (fn [ipc module c wrap]
          (let [b (wrap ipc)
                dag {"z" #{} "a" #{} "mid" #{"z"} "end" #{"mid" "a"}}]
            (testing "invalid DAGs are atomic; submission preserves unknown claim denials"
              (p/claim! c "later" "z" "w0" "pre")
              (doseq [[id bad] {"empty" {} "cycle" {"a" #{"b"} "b" #{"a"}}
                                "self" {"a" #{"a"}} "missing" {"a" #{"x"}}
                                "large" (into {} (map (fn [i] [(str i) #{}]) (range 33)))}]
                (p/submit-execution! c id bad))
              (h/wait-for-processing! c)
              (is (= {:claim-id "pre" :node-id "z" :worker-id "w0" :clock 0
                      :granted? false :reason :unknown-execution} (p/get-claim b "later" "pre")))
              (doseq [id ["empty" "cycle" "self" "missing" "large"]]
                (is (nil? (p/get-execution b id)))))

            (p/submit-execution! c "later" dag)
            (p/claim! c "later" "z" "changed" "pre")
            (h/wait-for-processing! c)
            (is (= {:claim-id "pre" :node-id "z" :worker-id "w0" :clock 0
                    :granted? false :reason :unknown-execution}
                   (p/get-claim b "later" "pre")))
            (is (= ["a" "z"] (p/get-claimable-nodes b "later")))
            (is (= {"z" :ready "a" :ready "mid" :pending "end" :pending}
                   (:node-statuses (p/get-execution b "later"))))

            (testing "claim precedence, lease boundary, fencing and stable effect identity"
              (p/claim! b "later" "absent" "w" "unknown-node")
              (p/claim! b "later" "mid" "w" "blocked")
              (p/claim! b "later" "z" "w1" "first")
              (h/wait-for-processing! b)
              (is (= :unknown-node (:reason (p/get-claim c "later" "unknown-node"))))
              (is (= :dependencies-incomplete (:reason (p/get-claim c "later" "blocked"))))
              (is (= {:claim-id "first" :node-id "z" :worker-id "w1" :clock 0
                      :granted? true :token 1 :lease-expiry 10}
                     (p/get-claim c "later" "first")))
              (is (= :running (:status (p/get-node c "later" "z"))))
              (p/claim! c "later" "z" "w2" "held")
              (p/advance-clock! c "later" 9)
              (p/complete! c "later" "z" "w2" 1 "wrong")
              (h/wait-for-processing! c)
              (is (= :lease-held (:reason (p/get-claim b "later" "held"))))
              (is (= :running (:status (p/get-node b "later" "z"))))
              (p/advance-clock! b "later" 10)
              (p/complete! b "later" "z" "w1" 1 "late")
              (p/claim! b "later" "z" "w2" "second")
              (p/complete! b "later" "z" "w1" 1 "stale")
              (p/claim! b "later" "a" "other" "first")
              (h/wait-for-processing! b)
              (is (= {:node-id "z" :effect-id ["later" "z"] :dependencies #{}
                      :status :running :attempts 2 :lease {:worker-id "w2" :token 2 :expiry 20}
                      :result nil} (p/get-node c "later" "z")))
              (is (= 10 (:clock (p/get-claim c "later" "second"))))
              (is (= {:claim-id "first" :node-id "z" :worker-id "w1" :clock 0
                      :granted? true :token 1 :lease-expiry 10}
                     (p/get-claim c "later" "first")))
              (is (= 0 (:attempts (p/get-node c "later" "a"))))
              (p/complete! c "later" "z" "w2" 2 {:ok true})
              (p/complete! c "later" "z" "w2" 2 {:ok false})
              (p/claim! c "later" "z" "w3" "done")
              (p/claim! c "later" "a" "w3" "a1")
              (h/wait-for-processing! c)
              (is (= {:ok true} (:result (p/get-node b "later" "z"))))
              (is (= :already-succeeded (:reason (p/get-claim b "later" "done"))))
              (is (= ["mid"] (p/get-claimable-nodes b "later")))
              (p/claim! b "later" "mid" "changed" "blocked")
              (p/complete! b "later" "a" "w3" 1 {:value "a"})
              (p/claim! b "later" "mid" "w4" "m1")
              (p/complete! b "later" "mid" "w4" 1 "m")
              (p/claim! b "later" "end" "w5" "e1")
              (p/complete! b "later" "end" "w5" 1 "e")
              (h/wait-for-processing! b)
              (is (= :success (:status (p/get-execution c "later"))))
              (is (= :success (:status (p/get-node c "later" "a"))))
              (is (= {:value "a"} (:result (p/get-node c "later" "a"))))
              (is (= {:claim-id "blocked" :node-id "mid" :worker-id "w" :clock 0
                      :granted? false :reason :dependencies-incomplete}
                     (p/get-claim c "later" "blocked")))
              (is (= [] (p/get-claimable-nodes c "later"))))

            (testing "immutable DAG and durable business state on unchanged module update"
              (p/submit-execution! c "later" {"replacement" #{}})
              (p/advance-clock! c "later" 5)
              (h/wait-for-processing! c)
              (rtest/update-module! ipc module)
              (is (= dag (:dag (p/get-execution b "later"))))
              (is (= 10 (:clock (p/get-execution b "later"))))
              (is (= {:claim-id "pre" :node-id "z" :worker-id "w0" :clock 0
                      :granted? false :reason :unknown-execution}
                     (p/get-claim b "later" "pre")))
              (p/submit-execution! b "after-update" {"x" #{}})
              (p/advance-clock! b "after-update" 4)
              (p/claim! b "after-update" "x" "w" "after")
              (h/wait-for-processing! b)
              (is (= 4 (:clock (p/get-claim c "after-update" "after")))))))))))

(defn test-growth-and-boundaries [create-module]
  (doseq [tasks [2 4]]
    (testing (str tasks " tasks, bounded DAG vs unrelated population")
      (exercise create-module tasks
        (fn [ipc module c wrap]
          (let [b (wrap ipc)
                chain (into {} (map (fn [i] [(str "n" i)
                                             (if (zero? i) #{} #{(str "n" (dec i))})]) (range 32)))]
            (p/submit-execution! c "chain" chain)
            (doseq [i (range 40)]
              (p/submit-execution! c (str "other" i) {"x" #{}}))
            (h/wait-for-processing! c)
            (is (= 32 (count (:dag (p/get-execution b "chain")))))
            (is (= ["n0"] (p/get-claimable-nodes b "chain")))
            (doseq [i (range 80)]
              (p/claim! b "chain" "n31" "worker" (str "deny" i)))
            (h/wait-for-processing! b)
            (is (= :dependencies-incomplete (:reason (p/get-claim c "chain" "deny79"))))
            (is (= ["n0"] (p/get-claimable-nodes c "chain")))
            (p/advance-clock! c "chain" 73)
            (p/claim! c "chain" "n0" "worker" "first")
            (h/wait-for-processing! c)
            (is (= 73 (:clock (p/get-claim b "chain" "first"))))
            (is (= 83 (:lease-expiry (p/get-claim b "chain" "first"))))
            (p/advance-clock! b "chain" 83)
            (p/complete! b "chain" "n0" "worker" 1 "expired")
            (h/wait-for-processing! b)
            (is (= :ready (:status (p/get-node c "chain" "n0"))))
            (is (= 1 (:attempts (p/get-node c "chain" "n0"))))
            (is (= 83 (get-in (p/get-node c "chain" "n0") [:lease :expiry])))
            (p/claim! c "chain" "n0" "worker" "retry")
            (p/complete! c "chain" "n0" "worker" 1 "fenced")
            (p/complete! c "chain" "n0" "worker" 2 "ok")
            (h/wait-for-processing! c)
            (is (= "ok" (:result (p/get-node b "chain" "n0"))))
            (is (= ["chain" "n0"] (:effect-id (p/get-node b "chain" "n0"))))
            (is (= ["n1"] (p/get-claimable-nodes b "chain")))
            (rtest/update-module! ipc module)
            (is (= :dependencies-incomplete (:reason (p/get-claim c "chain" "deny0"))))
            (is (= 2 (:attempts (p/get-node c "chain" "n0"))))))))))
