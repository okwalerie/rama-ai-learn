(ns hld-job-scheduler.nfr-test
  "NFR tests: claims, clock advances and completions apply exactly once under
   a forced stream retry, and their effective (granting, expiring,
   completing) paths do not grow with the execution's own claim history."
  (:require [clojure.test :refer [deftest is testing]]
            [com.rpl.rama.test :as rtest]
            [hld-job-scheduler.protocol :as p]
            [rama-challenges.harness :as h]
            [rama-challenges.nfr :as nfr]))

(defn- exercise [tasks f]
  (let [{:keys [module wrap-client]}
        ((requiring-resolve 'hld-job-scheduler.module/create-module))]
    (with-open [ipc (rtest/create-ipc)]
      (rtest/launch-module! ipc module {:tasks tasks :threads tasks})
      (f (wrap-client ipc) (wrap-client ipc)))))

(defn- retried!
  "Runs write f and waits, failing the first stream completion so a stream
   design retries it. A microbatch design sees no forced failure."
  [c f]
  (nfr/failed-streaming #(do (f) (h/wait-for-processing! c))))

(deftest forced-stream-retry-decides-once
  (doseq [tasks [2 4]]
    (testing (str tasks " tasks")
      (exercise tasks
        (fn [c reader]
          (retried! c #(p/submit-execution! c "x" {"a" #{} "b" #{"a"}}))
          (retried! c #(p/claim! c "x" "a" "w1" "c1"))
          (is (= {:claim-id "c1" :node-id "a" :worker-id "w1" :clock 0
                  :granted? true :token 1 :lease-expiry 10}
                 (p/get-claim reader "x" "c1")))
          (retried! c #(p/claim! c "x" "a" "w1" "c1"))
          (retried! c #(p/claim! c "x" "a" "w2" "c2"))
          (is (= :lease-held (:reason (p/get-claim reader "x" "c2"))))
          (is (= {:worker-id "w1" :token 1 :expiry 10} (:lease (p/get-node reader "x" "a"))))
          (is (= 1 (:attempts (p/get-node reader "x" "a")))
              "a retried grant must not issue a second token")
          (retried! c #(p/advance-clock! c "x" 10))
          (retried! c #(p/claim! c "x" "a" "w2" "c3"))
          (is (= {:claim-id "c3" :node-id "a" :worker-id "w2" :clock 10
                  :granted? true :token 2 :lease-expiry 20}
                 (p/get-claim reader "x" "c3")))
          (retried! c #(p/complete! c "x" "a" "w1" 1 "stale"))
          (retried! c #(p/complete! c "x" "a" "w2" 2 "done"))
          (retried! c #(p/claim! c "x" "b" "w3" "c4"))
          (retried! c #(p/complete! c "x" "b" "w3" 1 {:ok true}))
          (is (= {:node-id "a" :effect-id ["x" "a"] :dependencies #{} :status :success
                  :attempts 2 :lease nil :result "done"}
                 (p/get-node reader "x" "a")))
          (is (= [:success 1 {:ok true}]
                 ((juxt :status :attempts :result) (p/get-node reader "x" "b"))))
          (is (= [:success 10] ((juxt :status :clock) (p/get-execution reader "x")))))))))

(defn- clock [reader] (:clock (p/get-execution reader "target")))

(defn- populate!
  "Grows the target execution's own history: denial decisions, and granted
   decisions on a churn node whose lease is expired by a clock advance each
   time, so tokens, attempts and lease churn grow too. Unrelated executions
   grow alongside."
  [c reader start end]
  (doseq [i (range start end)]
    (p/submit-execution! c (str "other-" i) {"only" #{}})
    (p/claim! c "target" "blocked" "w" (str "old-" i))
    (when (even? i)
      (p/claim! c "target" "churn" "w" (str "churn-" i))
      (p/advance-clock! c "target" (* 11 (inc i)))))
  (h/wait-for-processing! c)
  (is (= :granted-and-expired
         (let [n (p/get-node reader "target" "churn")]
           (when (and (= :ready (:status n)) (= (quot end 2) (:attempts n)))
             :granted-and-expired)))))

(defn- probe [c reader node]
  (let [measure (fn [f] (nfr/capture-rocks-ops #(do (f) (h/wait-for-processing! c))))
        c0 (clock reader)
        grant (measure #(p/claim! c "target" node "w" (str "grant-" node)))
        _ (is (:granted? (p/get-claim reader "target" (str "grant-" node))))
        expire (measure #(p/advance-clock! c "target" (+ c0 11)))
        _ (is (= :ready (:status (p/get-node reader "target" node))) "the lease expired")
        _ (p/claim! c "target" node "w2" (str "regrant-" node))
        _ (h/wait-for-processing! c)
        complete (measure #(p/complete! c "target" node "w2" 2 "result"))]
    (is (= [:success 2 "result"]
           ((juxt :status :attempts :result) (p/get-node reader "target" node))))
    {:grant grant :expire expire :complete complete}))

(deftest effective-paths-bounded-by-dag-not-claim-history
  (doseq [tasks [2 4]]
    (testing (str tasks " tasks")
      (exercise tasks
        (fn [c reader]
          (p/submit-execution! c "target" {"root" #{} "blocked" #{"root"} "churn" #{}
                                           "low" #{} "high" #{}})
          (populate! c reader 0 256)
          (let [low (probe c reader "low")]
            (populate! c reader 256 1024)
            (let [high (probe c reader "high")]
              (println "Job scheduler effective-path work" tasks "tasks" low "->" high)
              (doseq [op (keys low)]
                (is (pos? (get-in low [op :writes])) (str op " measured no writes"))
                (doseq [metric [:reads :iterators :iterator-reads :writes]]
                  (let [before (get-in low [op metric])
                        after (get-in high [op metric])]
                    (is (<= after (+ 24 (* 2 before)))
                        (str op " " metric " grew with the execution's claim history: "
                             before " -> " after))))))))))))
