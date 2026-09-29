(ns unbalanced-social-graph.balance-test
  "Task-balance tests for the README property: reading a celebrity's
   followers must spread across every task, a small account's followers must
   stay together, and a follow must not cost more because the target is
   popular.

   The fanout consumer reads PStates directly, but tests can only use the
   protocol, so the per-task read work of get-followers stands in for fanout
   balance."
  (:require
   [clojure.test :refer [deftest is testing]]
   [com.rpl.rama.test :as rtest]
   [rama-challenges.harness :as harness]
   [rama-challenges.nfr :as nfr]
   [unbalanced-social-graph.protocol :as p]))

(defn- read-work [ops] (+ (:reads ops 0) (:iterator-reads ops 0)))

(defn- follow-all!
  "follower-ids follow target, issued from several threads."
  [client target follower-ids]
  (->> (partition-all 250 follower-ids)
       (mapv (fn [ids] (future (doseq [a ids] (p/follow! client a target)))))
       (run! deref))
  (harness/wait-for-processing! client))

(defn- run [tasks]
  (let [{:keys [module wrap-client]}
        ((requiring-resolve 'unbalanced-social-graph.module/create-module))]
    (binding [harness/*task-count* tasks]
      (with-open [ipc (rtest/create-ipc)]
        (rtest/launch-module! ipc module {:tasks tasks :threads tasks})
        (let [client (wrap-client ipc)
              celebrity 1
              small 2
              fans (range 1000000 (+ 1000000 (* 1100 tasks)))]
          (follow-all! client celebrity fans)
          (follow-all! client small (range 2000000 2000030))
          (testing (str tasks " tasks: a celebrity's followers are read evenly from every task")
            (let [[followers per-task] (let [ret (atom nil)
                                             per (nfr/capture-per-task-ops
                                                  #(reset! ret (p/get-followers client celebrity)))]
                                         [@ret per])
                  work (mapv #(read-work (get per-task % {})) (range tasks))
                  mean (/ (reduce + work) (double tasks))]
              (println "Celebrity get-followers read work per task" tasks "tasks" work)
              (is (= (set fans) followers))
              (is (every? pos? work)
                  (str "every task must hold part of a celebrity's followers: " work))
              (is (<= (apply max work) (* 1.5 mean))
                  (str "celebrity follower reads are skewed across tasks: " work))))
          (when (= 4 tasks)
            (testing "a small account's followers are read from few tasks"
              (let [[followers per-task] (let [ret (atom nil)
                                               per (nfr/capture-per-task-ops
                                                    #(reset! ret (p/get-followers client small)))]
                                           [@ret per])
                    touched (filterv #(pos? (read-work (get per-task % {}))) (range tasks))]
                (println "Small-account get-followers read work per task" per-task)
                (is (= (set (range 2000000 2000030)) followers))
                (is (<= (count touched) 2)
                    (str "a 30-follower account must not be scattered across every task: " per-task))
                (is (<= (reduce + (map #(read-work (get per-task % {})) (range tasks))) (+ 60 10))
                    (str "reading 30 followers must cost about 30 entries: " per-task)))))
          (testing (str tasks " tasks: a follow into a celebrity costs about the same as into a small account")
            (let [measure (fn [a target]
                            (nfr/capture-rocks-ops #(do (p/follow! client a target)
                                                        (harness/wait-for-processing! client))))
                  to-small (measure 3000000 small)
                  to-celebrity (measure 3000001 celebrity)]
              (println "Follow cost" tasks "tasks" to-small "->" to-celebrity)
              (doseq [metric [:reads :iterators :iterator-reads :writes]]
                (is (<= (metric to-celebrity) (+ 24 (* 2 (metric to-small))))
                    (str metric " of a follow grew with the target's follower count: "
                         to-small " -> " to-celebrity))))))))))

(deftest celebrity-followers-balanced-across-tasks
  (run 2)
  (run 4))
