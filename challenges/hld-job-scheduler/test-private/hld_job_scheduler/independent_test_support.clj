(ns hld-job-scheduler.independent-test-support
  (:require [clojure.test :refer [is testing]]
            [com.rpl.rama.test :as rtest]
            [rama-challenges.harness :as h]
            [hld-job-scheduler.protocol :as p]))

(defn- measured [f]
  (let [operations (atom 0)]
    (rtest/with-event-hook
      (fn [kind data]
        (case kind
          (:rocks-read :rocks-iterator :rocks-iterator-read)
          (swap! operations inc)
          :rocks-commit (swap! operations + (:write-batch-count data))
          nil))
      (f)
      @operations)))

(defn- probe [client reader stage]
  (let [claim-id (str "probe-" stage)]
    {:execution (measured #(p/get-execution reader "target"))
     :node (measured #(p/get-node reader "target" "root"))
     :claim (measured #(p/get-claim reader "target" "old-0"))
     :claimable (measured #(p/get-claimable-nodes reader "target"))
     :denial (measured #(do (p/claim! client "target" "blocked" "w" claim-id)
                            (h/wait-for-processing! client)))
     :replay (measured #(do (p/claim! client "target" "root" "changed" "old-0")
                            (h/wait-for-processing! client)))
     :clock (measured #(do (p/advance-clock! client "target" 0)
                           (h/wait-for-processing! client)))
     :completion (measured #(do (p/complete! client "target" "root" "w" 99 "wrong")
                                (h/wait-for-processing! client)))}))

(defn- populate! [client start end]
  (doseq [i (range start end)]
    (p/submit-execution! client (str "other-" i) {"only" #{}})
    (p/claim! client "target" "blocked" "w" (str "old-" i)))
  (h/wait-for-processing! client))

(defn test-storage-work-and-boundaries [create-module]
  (doseq [tasks [2 4]]
    (testing (str tasks " tasks")
      (let [{:keys [module wrap-client]} (create-module)]
        (with-open [ipc (rtest/create-ipc)]
          (rtest/launch-module! ipc module {:tasks tasks :threads tasks})
          (let [c (wrap-client ipc)
                reader (wrap-client ipc)]
            (p/submit-execution! c "target" {"root" #{} "blocked" #{"root"}})
            (populate! c 0 256)
            (let [low (probe c reader "low")]
              (populate! c 256 1024)
              (let [high (probe c reader "high")]
                (testing "fixed DAG and responses after unrelated population and historical decisions grow"
                  (is (= {"root" :ready "blocked" :pending}
                         (:node-statuses (p/get-execution reader "target"))))
                  (is (= ["root"] (p/get-claimable-nodes reader "target")))
                  (is (= {:claim-id "old-0" :node-id "blocked" :worker-id "w"
                          :clock 0 :granted? false :reason :dependencies-incomplete}
                         (p/get-claim reader "target" "old-0")))
                  (is (= :dependencies-incomplete (:reason (p/get-claim reader "target" "probe-high")))))
                (testing "aggregate point, iterator and write operations do not track unrelated history"
                  (doseq [operation (keys low)]
                    (let [before (get low operation) after (get high operation)]
                      (is (<= after (max 12 (+ 8 (* 3 before))))
                          (str operation " storage work grew with 256→1024 executions/decisions: "
                               before "→" after " (" tasks " tasks)"))))
                  (is (pos? (reduce + (vals low))) "the instrumentation must observe storage operations")
                  (is (pos? (reduce + (vals high))) "the instrumentation must observe storage operations"))
                (println "INDEPENDENT_STORAGE_WORK" {:tasks tasks :at-256 low :at-1024 high})))

            (testing "maximum safe clock, claim scope, and unknown no-op writes"
              (let [limit (- Long/MAX_VALUE 10)]
                (p/advance-clock! c "never" limit)
                (p/complete! c "never" "root" "w" 1 "ignored")
                (p/submit-execution! c "never" {"root" #{}})
                (p/advance-clock! c "target" limit)
                (p/claim! c "target" "root" "w" "limit")
                (p/claim! c "never" "root" "w" "limit")
                (h/wait-for-processing! c)
                (is (= 0 (:clock (p/get-execution reader "never"))))
                (is (= limit (:clock (p/get-execution reader "target"))))
                (is (= Long/MAX_VALUE (:lease-expiry (p/get-claim reader "target" "limit"))))
                (is (= 10 (:lease-expiry (p/get-claim reader "never" "limit"))))
                (p/complete! c "target" "root" "w" 1 "at-limit")
                (h/wait-for-processing! c)
                (is (= limit (:clock (p/get-execution reader "target"))))
                (is (= "at-limit" (:result (p/get-node reader "target" "root"))))
                (is (= 10 (:expiry (:lease (p/get-node reader "never" "root")))))))))))))
