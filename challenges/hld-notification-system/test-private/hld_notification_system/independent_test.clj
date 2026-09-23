(ns hld-notification-system.independent-test
  (:require [clojure.test :refer [deftest is]]
            [com.rpl.rama.test :as rtest]
            [hld-notification-system.protocol :as p]
            [rama-challenges.harness :as h]))

(defn- factory []
  ((requiring-resolve 'hld-notification-system.module/create-module)))

(defn- measure [f]
  (let [ops (atom {:reads 0 :iterators 0 :writes 0})]
    (rtest/with-event-hook
      (fn [kind data]
        (case kind
          :rocks-read (swap! ops update :reads inc)
          :rocks-iterator-read (swap! ops update :reads inc)
          :rocks-iterator (swap! ops update :iterators inc)
          :rocks-commit (swap! ops update :writes + (:write-batch-count data))
          nil))
      (f))
    @ops))

(defn- bounded-pair [label small large]
  ;; Relative bounds deliberately leave room for batching and alternate layouts.
  ;; An O(history) scan/rewrite between 240 and 1040 entries cannot hide here.
  (is (pos? (+ (:reads small) (:iterators small) (:writes small)))
      (str label " instrumentation must actually observe work: " small))
  (doseq [key [:reads :iterators :writes]]
    (is (<= (key large) (+ (key small) 150))
        (str label " " key " grew with history: " small " -> " large))))

(defn- cost-snapshot [c wait user sid n]
  (let [page (p/get-recent-submissions c user)
        dead (p/get-dead-letters c user)
        sub (p/get-submission c (str "hist" (dec n)))]
    (is (= 100 (count page)))
    (is (= (set (map #(str "hist" %) (range (- n 100) n))) (set page)))
    (is (= 100 (count dead)))
    (is (= 100 (count (set (map :submission-id dead)))))
    (is (every? (set (map #(str "hist" %) (range (if (= n 240) 0 240) n)))
                (map :submission-id dead)))
    (is (= 8 (count (:deliveries sub))))
    (is (= :failed (get-in sub [:deliveries "d0" :state])))
    (let [point (measure #(p/get-submission c (str "hist" (dec n))))
          recent (measure #(p/get-recent-submissions c user))
          dlq (measure #(p/get-dead-letters c user))]
      (let [submit (measure #(do (p/submit! c sid user "news" "probe" 500 10)
                                 (wait)))]
        (is (= 8 (count (:deliveries (p/get-submission c sid)))))
        (let [attempt (measure #(do (p/report-attempt! c sid "d0" 1 :accepted 10) (wait)))
              receipt (measure #(do (p/record-receipt! c sid "d0" :read) (wait)))]
          (is (= {:token "Token0" :generation 1 :state :read
                  :attempts 1 :next-attempt-at nil}
                 (get-in (p/get-submission c sid) [:deliveries "d0"])))
          {:point point :recent recent :dlq dlq :submit submit
           :attempt attempt :receipt receipt})))))

(defn- exercise-cost [tasks]
  (let [{:keys [module wrap-client]} (factory)]
    (with-open [ipc (rtest/create-ipc)]
      (rtest/launch-module! ipc module {:tasks tasks :threads tasks})
      (let [a (wrap-client ipc) b (wrap-client ipc)
            wait #(h/wait-for-processing! b)
            user "history"]
        (doseq [d (range 8)]
          (p/register-device! a user (str "d" d) (str "Token" d)))
        (wait)
        (let [snapshots
              (loop [from 0 sizes [240 1040] results []]
                (if-let [n (first sizes)]
                  (do
                    (doseq [i (range from n)]
                      (p/submit! a (str "hist" i) user "news" "" 100 0))
                    (wait)
                    ;; Reports have different submission owners; final page order
                    ;; is not assumed across tasks. Every entry is still required.
                    (doseq [i (range from n)]
                      (p/report-attempt! a (str "hist" i) "d0" 1 :permanent-failure 1))
                    (wait)
                    (recur n (next sizes)
                           (conj results (cost-snapshot a wait user (str "probe" n) n))))
                  results))
              [small large] snapshots]
          (println "INDEPENDENT-OPS" tasks "tasks, 240 entries:" small
                   "1040 entries:" large)
          (doseq [op [:point :recent :dlq :submit :attempt :receipt]]
            (bounded-pair (str tasks " tasks " op) (op small) (op large))))))))

(deftest independent-cost-two-tasks (exercise-cost 2))
(deftest independent-cost-four-tasks (exercise-cost 4))

(defn- exercise-update [tasks]
  (let [{:keys [module wrap-client]} (factory)]
    (with-open [ipc (rtest/create-ipc)]
      (rtest/launch-module! ipc module {:tasks tasks :threads tasks})
      (let [a (wrap-client ipc)]
        (p/register-device! a "restart" "d" "Old")
        (p/submit! a "old" "restart" "news" "before" 100 5)
        (h/wait-for-processing! a)
        (p/report-attempt! a "old" "d" 1 :transient-failure 5)
        (h/wait-for-processing! a)
        (rtest/update-module! ipc module)
        (let [b (wrap-client ipc)]
          (is (= {:token "Old" :generation 1 :state :pending :attempts 1
                  :next-attempt-at 15}
                 (get-in (p/get-submission b "old") [:deliveries "d"])))
          (p/report-attempt! b "old" "d" 1 :invalid-token 99)
          (p/report-attempt! b "old" "d" 2 :permanent-failure 14)
          (h/wait-for-processing! b)
          (is (= 15 (get-in (p/get-submission a "old") [:deliveries "d" :next-attempt-at])))
          (p/register-device! b "restart" "d" "New")
          (h/wait-for-processing! a)
          (p/report-attempt! b "old" "d" 2 :invalid-token 15)
          (h/wait-for-processing! a)
          (is (= :invalid-token (get-in (p/get-submission b "old") [:deliveries "d" :state])))
          (is (= {:token "New" :generation 2 :valid? true}
                 (get (p/get-devices a "restart") "d")))
          (p/submit! b "old" "restart" "news" "loser" 100 20)
          (p/submit! b "new" "restart" "news" "after" 100 20)
          (h/wait-for-processing! a)
          (is (= "before" (:payload (p/get-submission b "old"))))
          (is (= {:token "New" :generation 2 :state :pending :attempts 0
                  :next-attempt-at 20}
                 (get-in (p/get-submission a "new") [:deliveries "d"])))
          (is (= ["new" "old"] (p/get-recent-submissions b "restart"))))))))

(deftest independent-update-two-tasks (exercise-update 2))
(deftest independent-update-four-tasks (exercise-update 4))
