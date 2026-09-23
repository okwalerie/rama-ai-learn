(ns hld-rate-limiter.performance-test-support
  "Bounded-work and work-distribution tests for hld-rate-limiter.
   Budgets are published in README.md (\"Enforced work budgets\"). Counts
   come from the com.rpl.rama.test event hook at the RocksDB level on the
   module's own PStates; nothing here depends on topology type, depot
   layout, PState names or wall-clock time. The hook reports operation
   counts only (never value sizes), so these tests observe RocksDB work,
   not bytes or storage."
  (:require
   [clojure.test :refer [is testing]]
   [com.rpl.rama.test :as rtest]
   [hld-rate-limiter.protocol :as p]
   [rama-challenges.harness :as harness]))

(defn- internal-pstate?
  [{:keys [name]}]
  (and (string? name) (.startsWith ^String name "$$__")))

(def ^:private zero-ops {:reads 0 :iterators 0 :iterator-reads 0 :writes 0})

(defn- record-op
  "Folds one hook event into an ops map; nil when the event is not a
   RocksDB operation on a non-internal PState."
  [ops event-type data]
  (when-not (internal-pstate? data)
    (case event-type
      :rocks-read          (update ops :reads inc)
      :rocks-iterator      (update ops :iterators inc)
      :rocks-iterator-read (update ops :iterator-reads inc)
      :rocks-commit        (update ops :writes + (long (:write-batch-count data 0)))
      nil)))

(defn capture-ops
  "Runs f and returns RocksDB operation counts on non-internal PStates:
   {:reads n :iterators n :iterator-reads n :writes n}. :writes is the
   number of entries committed, summed over every commit."
  [f]
  (let [state (atom zero-ops)]
    (rtest/with-event-hook
      (fn [event-type data]
        (swap! state (fn [ops] (or (record-op ops event-type data) ops))))
      (f))
    @state))

(defn- per-task-ops
  "Runs f and returns the same counts as capture-ops, keyed by task id:
   {task {:reads n :iterators n :iterator-reads n :writes n}}."
  [f]
  (let [state (atom {})]
    (rtest/with-event-hook
      (fn [event-type data]
        (swap! state update (:task-id data)
               (fn [ops] (let [ops (or ops zero-ops)]
                           (or (record-op ops event-type data) ops)))))
      (f))
    @state))

;; Fixed ceilings, per operation, at every history size. Reads write nothing.
(def read-budget  {:reads 4 :iterators 2 :iterator-reads 8 :writes 0})
(def write-budget {:reads 8 :iterators 2 :iterator-reads 8 :writes 8})

;; Bounded growth: going from 32 to 512 recorded decisions may add at most
;; this much to each metric. Fixed-work designs add nothing; a design that
;; scans or pages the decision history grows by far more than this.
(def growth-allowance {:reads 2 :iterators 1 :iterator-reads 4 :writes 2})

;; Distribution ceilings, as multiples of the user count, for 400 users each
;; receiving one set-config! and one check! and then being read back twice.
;; Point reads, iterator seeks and iterator reads are separate metrics so a
;; bounded iterator-based design is not rejected for avoiding point reads.
(def distribution-ceiling {:reads 8 :iterators 4 :iterator-reads 16 :writes 8})

(defn- within-budget?
  [ops budget]
  (every? (fn [[k limit]] (<= (long (get ops k 0)) (long limit))) budget))

(defn- bounded-growth?
  [small large budget]
  (every? (fn [k] (<= (long (get large k 0))
                      (+ (long (get small k 0)) (long (get growth-allowance k 0)))))
          (keys budget)))

(def big-config {:shadow? false
                 :user {:capacity 1000000 :refill 0}
                 :endpoints {"e" {:capacity 1000000 :refill 0}
                             "f" {:capacity 10 :refill 1}}})

(defn- build-history!
  "Installs big-config for user and records n cost-1 decisions at tick 1."
  [client user n]
  (p/set-config! client user 1 big-config)
  (doseq [i (range n)]
    (p/check! client user (str "req-" i) "e" 1 1)))

(defn- allowed-decision
  "Independently derived decision of the i-th (0-based) cost-1 debit at tick
   1 against big-config: both buckets started full at 1,000,000 with refill 0."
  [i]
  {:allowed true :would-allow true :reason nil :tick 1 :config-version 1
   :remaining {:user (- 1000000 (inc i)) :endpoint (- 1000000 (inc i))}})

(defn- histogram
  "Per-task vector of (metric-fn ops) for tasks 0..tasks-1."
  [ops-by-task tasks metric-fn]
  (mapv #(long (metric-fn (get ops-by-task % zero-ops))) (range tasks)))

(defn- read-work
  "Aggregate observable read work: point reads + iterator seeks + iterator reads."
  [ops]
  (+ (long (:reads ops 0)) (long (:iterators ops 0)) (long (:iterator-reads ops 0))))

(defn- balanced?
  "Every task holds between 0.5x and 1.5x of the mean share."
  [hist]
  (let [total (reduce + hist)
        mean (/ total (count hist))]
    (and (pos? total)
         (every? #(and (>= % (* 0.5 mean)) (<= % (* 1.5 mean))) hist))))

(defn- total-ops
  [ops-by-task]
  (reduce (fn [acc ops] (merge-with + acc ops)) zero-ops (vals ops-by-task)))

(defn test-module-performance
  [create-module-fn tasks]
  (let [{:keys [module wrap-client]} (create-module-fn)]
    (with-open [ipc (rtest/create-ipc)]
      ;; One thread per task: RocksDB events are attributed to the task
      ;; thread, so per-partition distribution is only observable when every
      ;; task has its own thread.
      (rtest/launch-module! ipc module {:tasks tasks :threads tasks})
      (let [a (wrap-client ipc)
            b (wrap-client ipc)
            sync-a! (fn [] (harness/wait-for-processing! b))
            sync-b! (fn [] (harness/wait-for-processing! a))]

        (testing "read and write work is bounded and does not grow with per-user decision history"
          (build-history! a "small" 32)
          (build-history! b "large" 512)
          (sync-a!)
          (sync-b!)

          (testing "independently derived results at both history sizes"
            (doseq [[user n reader] [["small" 32 b] ["large" 512 a]]]
              (testing user
                (is (= {:tick 1 :config-version 1
                        :user {:capacity 1000000 :available (- 1000000 n)}
                        :endpoint {:capacity 1000000 :available (- 1000000 n)}}
                       (p/get-status reader user "e" 1)))
                (is (= {:tick 5 :config-version 1
                        :user {:capacity 1000000 :available (- 1000000 n)}
                        :endpoint {:capacity 10 :available 10}}
                       (p/get-status reader user "f" 5)))
                (is (= (allowed-decision 0) (p/get-decision reader user "req-0")))
                (is (= (allowed-decision (dec n)) (p/get-decision reader user (str "req-" (dec n)))))
                (is (nil? (p/get-decision reader user (str "req-" n))))
                (is (= {:version 1 :config big-config} (p/get-config reader user))))))

          (doseq [[label read-fn]
                  [["earliest decision" (fn [user _] (p/get-decision b user "req-0"))]
                   ["latest decision"   (fn [user n] (p/get-decision b user (str "req-" (dec n))))]
                   ["missing decision"  (fn [user n] (p/get-decision b user (str "req-" n)))]
                   ["get-config"        (fn [user _] (p/get-config b user))]
                   ["get-status"        (fn [user _] (p/get-status b user "f" 5))]]]
            (testing label
              (let [small (capture-ops #(read-fn "small" 32))
                    large (capture-ops #(read-fn "large" 512))]
                (is (within-budget? small read-budget)
                    (str label " at 32 decisions exceeds budget: " small))
                (is (within-budget? large read-budget)
                    (str label " at 512 decisions exceeds budget: " large))
                (is (bounded-growth? small large read-budget)
                    (str label " work grew with history: 32 -> " small ", 512 -> " large)))))

          (testing "check! write work"
            (let [small (capture-ops (fn []
                                       (p/check! a "small" "req-extra" "e" 1 2)
                                       (sync-a!)))
                  large (capture-ops (fn []
                                       (p/check! a "large" "req-extra" "e" 1 2)
                                       (sync-a!)))]
              (is (within-budget? small write-budget)
                  (str "check! at 32 decisions exceeds budget: " small))
              (is (within-budget? large write-budget)
                  (str "check! at 512 decisions exceeds budget: " large))
              (is (bounded-growth? small large write-budget)
                  (str "check! work grew with history: 32 -> " small ", 512 -> " large))
              (is (= {:allowed true :would-allow true :reason nil :tick 2 :config-version 1
                      :remaining {:user 999967 :endpoint 999967}}
                     (p/get-decision b "small" "req-extra")))
              (is (= {:allowed true :would-allow true :reason nil :tick 2 :config-version 1
                      :remaining {:user 999487 :endpoint 999487}}
                     (p/get-decision b "large" "req-extra")))))

          (testing "retried check! write work is bounded at both history sizes"
            (let [small (capture-ops (fn []
                                       (p/check! b "small" "req-extra" "e" 1 3)
                                       (sync-b!)))
                  large (capture-ops (fn []
                                       (p/check! b "large" "req-extra" "e" 1 3)
                                       (sync-b!)))]
              (is (within-budget? small write-budget) (str "retry at 32: " small))
              (is (within-budget? large write-budget) (str "retry at 512: " large))
              (is (bounded-growth? small large write-budget)
                  (str "retry work grew with history: 32 -> " small ", 512 -> " large))
              (is (= {:allowed true :would-allow true :reason nil :tick 2 :config-version 1
                      :remaining {:user 999967 :endpoint 999967}}
                     (p/get-decision a "small" "req-extra")) "retry replays the original")
              (is (= {:allowed true :would-allow true :reason nil :tick 2 :config-version 1
                      :remaining {:user 999487 :endpoint 999487}}
                     (p/get-decision a "large" "req-extra")))
              (is (= {:tick 2 :config-version 1
                      :user {:capacity 1000000 :available 999487}
                      :endpoint {:capacity 1000000 :available 999487}}
                     (p/get-status a "large" "e" 0)) "clock still 2; retry did not debit")))

          (testing "denied check! write work is bounded at both history sizes"
            (let [small (capture-ops (fn []
                                       (p/check! b "small" "req-denied" "f" 11 3)
                                       (sync-b!)))
                  large (capture-ops (fn []
                                       (p/check! b "large" "req-denied" "f" 11 3)
                                       (sync-b!)))]
              (is (within-budget? small write-budget) (str "denied at 32: " small))
              (is (within-budget? large write-budget) (str "denied at 512: " large))
              (is (bounded-growth? small large write-budget)
                  (str "denied work grew with history: 32 -> " small ", 512 -> " large))
              (is (= {:allowed false :would-allow false :reason :cost-exceeds-capacity
                      :tick 3 :config-version 1 :remaining {:user 999967 :endpoint 10}}
                     (p/get-decision a "small" "req-denied")))
              (is (= {:allowed false :would-allow false :reason :cost-exceeds-capacity
                      :tick 3 :config-version 1 :remaining {:user 999487 :endpoint 10}}
                     (p/get-decision a "large" "req-denied")))
              (is (= {:tick 2 :config-version 1
                      :user {:capacity 1000000 :available 999487}
                      :endpoint {:capacity 1000000 :available 999487}}
                     (p/get-status a "large" "e" 0)) "denial did not advance the clock"))))

        (testing "observed RocksDB work is distributed across tasks with many owners"
          ;; Each owner's set-config! and check! go through the SAME wrapper so
          ;; the check is processed after the config (one-client ordering);
          ;; owners alternate between the two wrappers.
          (let [owners (mapv #(str "owner-" %) (range 400))
                n (count owners)
                write-ops (per-task-ops
                           (fn []
                             (doseq [[i owner] (map-indexed vector owners)]
                               (let [c (if (even? i) a b)]
                                 (p/set-config! c owner 1 big-config)
                                 (p/check! c owner "r1" "e" 1 1)))
                             (sync-a!)
                             (sync-b!)))
                read-ops (per-task-ops
                          (fn []
                            (is (every? #(= (allowed-decision 0) (p/get-decision a % "r1")) owners))
                            (is (every? #(= {:tick 1 :config-version 1
                                             :user {:capacity 1000000 :available 999999}
                                             :endpoint {:capacity 1000000 :available 999999}}
                                            (p/get-status b % "e" 0))
                                        owners))))
                write-hist (histogram write-ops tasks :writes)
                read-hist (histogram read-ops tasks read-work)
                write-total (total-ops write-ops)
                read-total (total-ops read-ops)]
            (is (<= n (:writes write-total) (* (:writes distribution-ceiling) n))
                (str "PState entries written for " n " config+check pairs: " write-total))
            (is (balanced? write-hist)
                (str "PState write entries not spread across tasks: " write-hist))
            (is (within-budget? read-total
                                (into {} (map (fn [[k m]] [k (* m n)])
                                              (dissoc distribution-ceiling :writes))))
                (str "read work for " (* 2 n) " reads exceeds ceilings: " read-total))
            (is (zero? (:writes read-total))
                (str "reads wrote entries: " read-total))
            (is (balanced? read-hist)
                (str "read work (point reads + iterator seeks + iterator reads) not spread across tasks: "
                     read-hist))))))))
