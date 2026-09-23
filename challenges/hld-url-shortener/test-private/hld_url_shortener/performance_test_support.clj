(ns hld-url-shortener.performance-test-support
  "Bounded-work and work-distribution tests for hld-url-shortener.
   Budgets are published in README.md (\"Enforced work budgets\"). Counts
   come from the com.rpl.rama.test event hook at the RocksDB level on the
   module's own PStates; nothing here depends on topology type, depot
   layout, PState names or wall-clock time. The hook reports operation
   counts only (never value sizes), so these tests observe RocksDB work,
   not bytes or storage."
  (:require
   [clojure.test :refer [is testing]]
   [com.rpl.rama.test :as rtest]
   [hld-url-shortener.protocol :as p]
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

;; Bounded growth: going from 32 to 512 history entries may add at most this
;; much to each metric. Fixed-work designs add nothing; a design that scans
;; or pages the history grows by far more than this.
(def growth-allowance {:reads 2 :iterators 1 :iterator-reads 4 :writes 2})

;; Distribution ceilings, as multiples of the alias count, for 400 aliases
;; each receiving one create and one click and then being read back twice.
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

(defn- build-history!
  "Creates alias with n create requests (first one wins) and n clicks."
  [client alias n]
  (p/create-link! client alias "req-0" (str "https://h.example/" alias) nil)
  (doseq [i (range 1 n)]
    (p/create-link! client alias (str "req-" i) (str "https://h.example/" alias "/" i) nil))
  (doseq [i (range n)]
    (p/record-click! client alias (str "click-" i))))

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

        (testing "read and write work is bounded and does not grow with per-alias history"
          (build-history! a "small" 32)
          (build-history! b "large" 512)
          (sync-a!)
          (sync-b!)

          (testing "independently derived results at both history sizes"
            (doseq [[alias n reader] [["small" 32 b] ["large" 512 a]]]
              (testing alias
                (is (= n (p/get-click-count reader alias)))
                (is (= {:outcome :created} (p/get-create-outcome reader alias "req-0")))
                (is (= {:outcome :rejected :reason :alias-taken}
                       (p/get-create-outcome reader alias (str "req-" (dec n)))))
                (is (nil? (p/get-create-outcome reader alias (str "req-" n))))
                (is (= {:status :active :target-url (str "https://h.example/" alias) :expires-at nil}
                       (p/resolve-alias reader alias 0))))))

          (doseq [[label read-fn]
                  [["get-click-count"    (fn [alias _] (p/get-click-count b alias))]
                   ["resolve-alias"      (fn [alias _] (p/resolve-alias b alias 0))]
                   ["winning outcome"    (fn [alias _] (p/get-create-outcome b alias "req-0"))]
                   ["last outcome"       (fn [alias n] (p/get-create-outcome b alias (str "req-" (dec n))))]
                   ["missing outcome"    (fn [alias n] (p/get-create-outcome b alias (str "req-" n)))]]]
            (testing label
              (let [small (capture-ops #(read-fn "small" 32))
                    large (capture-ops #(read-fn "large" 512))]
                (is (within-budget? small read-budget)
                    (str label " at 32 entries exceeds budget: " small))
                (is (within-budget? large read-budget)
                    (str label " at 512 entries exceeds budget: " large))
                (is (bounded-growth? small large read-budget)
                    (str label " work grew with history: 32 -> " small ", 512 -> " large)))))

          (testing "record-click! write work"
            (let [small (capture-ops (fn []
                                       (p/record-click! a "small" "click-extra")
                                       (sync-a!)))
                  large (capture-ops (fn []
                                       (p/record-click! a "large" "click-extra")
                                       (sync-a!)))]
              (is (within-budget? small write-budget)
                  (str "record-click! at 32 entries exceeds budget: " small))
              (is (within-budget? large write-budget)
                  (str "record-click! at 512 entries exceeds budget: " large))
              (is (bounded-growth? small large write-budget)
                  (str "record-click! work grew with history: 32 -> " small ", 512 -> " large))
              (is (= 33 (p/get-click-count b "small")))
              (is (= 513 (p/get-click-count b "large")))))

          (testing "duplicate click write work is bounded at both history sizes"
            (let [small (capture-ops (fn []
                                       (p/record-click! b "small" "click-extra")
                                       (sync-b!)))
                  large (capture-ops (fn []
                                       (p/record-click! b "large" "click-extra")
                                       (sync-b!)))]
              (is (within-budget? small write-budget) (str "duplicate click at 32: " small))
              (is (within-budget? large write-budget) (str "duplicate click at 512: " large))
              (is (bounded-growth? small large write-budget)
                  (str "duplicate click work grew with history: 32 -> " small ", 512 -> " large))
              (is (= 33 (p/get-click-count a "small")))
              (is (= 513 (p/get-click-count a "large"))))))

        (testing "observed RocksDB work is distributed across tasks with many owners"
          ;; Each owner's create and click go through the SAME wrapper so the
          ;; click is processed after the create (one-client ordering);
          ;; owners alternate between the two wrappers.
          (let [owners (mapv #(str "owner-" %) (range 400))
                n (count owners)
                write-ops (per-task-ops
                           (fn []
                             (doseq [[i owner] (map-indexed vector owners)]
                               (let [c (if (even? i) a b)]
                                 (p/create-link! c owner "r1" (str "https://o.example/" owner) nil)
                                 (p/record-click! c owner "c1")))
                             (sync-a!)
                             (sync-b!)))
                read-ops (per-task-ops
                          (fn []
                            (is (every? #(= 1 (p/get-click-count a %)) owners))
                            (is (every? #(= :active (:status (p/resolve-alias b % 0))) owners))))
                write-hist (histogram write-ops tasks :writes)
                read-hist (histogram read-ops tasks read-work)
                write-total (total-ops write-ops)
                read-total (total-ops read-ops)]
            (is (<= n (:writes write-total) (* (:writes distribution-ceiling) n))
                (str "PState entries written for " n " create+click pairs: " write-total))
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
