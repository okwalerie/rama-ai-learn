(ns hld-ticketing-system.nfr-test
  (:require [clojure.test :refer :all]
            [com.rpl.rama.test :as rtest]
            [rama-challenges.harness :as harness]
            [rama-challenges.nfr :as nfr]
            [hld-ticketing-system.protocol :as p]))

(defn descriptor []
  (if-let [f (requiring-resolve 'hld-ticketing-system.module/create-module)]
    (f)
    (throw (IllegalStateException. "Missing create-module"))))

(defn launch-opts [tasks]
  {:tasks tasks :threads (+ 2 (rand-int (dec tasks)))})

(defmacro with-clients [[clients n tasks] & body]
  `(let [descriptor# (descriptor)]
     (with-open [ipc# (rtest/create-ipc)]
       (rtest/launch-module! ipc# (:module descriptor#) (launch-opts ~tasks))
       (let [~clients (vec (repeatedly ~n #((:wrap-client descriptor#) ipc#)))]
         ~@body))))

(defn accepted [command & [fields]]
  (merge {:status :accepted :command command :conflicting-attempts 0} fields))

(defn rejected [command reason & [fields]]
  (merge {:status :rejected :command command :reason reason
          :conflicting-attempts 0} fields))

(defn retried!
  "Runs command f then waits, forcing the first stream completion to fail."
  [client f]
  (nfr/failed-streaming #(do (f) (harness/wait-for-processing! client))))

(deftest forced-stream-retry-applies-commands-exactly-once
  (doseq [tasks [2 4]]
    (with-clients [[a b] 2 tasks]
      (p/create-event! a "create" "e")
      (harness/wait-for-processing! b)
      (retried! b #(p/add-seats! a "add" "e" ["a" "b" "c" "d"]))
      (retried! b #(p/hold-seats! a "h1" "e" "u" ["a" "b"] 100))
      (retried! b #(p/confirm-hold! a "pay1" "e" "h1" "u" "p1"))
      (retried! b #(p/hold-seats! a "h2" "e" "v" ["c"] 100))
      (retried! b #(p/release-hold! a "rel2" "e" "h2" "v"))
      (retried! b #(p/confirm-hold! a "late2" "e" "h2" "v" "p2"))
      (retried! b #(p/confirm-hold! a "other1" "e" "h1" "u" "p3"))
      (retried! b #(p/hold-seats! a "h3" "e" "w" ["c" "d"] 100))
      (is (= (accepted :add-seats {:added 4}) (p/get-outcome b "e" "add")))
      (is (= (accepted :hold-seats {:hold-id "h1" :deadline 100}) (p/get-outcome b "e" "h1")))
      (is (= (accepted :confirm-hold {:seat-ids ["a" "b"] :payment-ref "p1"})
             (p/get-outcome b "e" "pay1")))
      (is (= (accepted :release-hold {:seat-ids ["c"]}) (p/get-outcome b "e" "rel2")))
      (is (= (rejected :confirm-hold :hold-released {:compensation-seq 1})
             (p/get-outcome b "e" "late2")))
      (is (= (rejected :confirm-hold :hold-confirmed {:compensation-seq 2})
             (p/get-outcome b "e" "other1")))
      (is (= (accepted :hold-seats {:hold-id "h3" :deadline 100}) (p/get-outcome b "e" "h3")))
      (is (= [{:seq 1 :request-id "late2" :hold-id "h2" :user-id "v"
               :payment-ref "p2" :reason :hold-released}
              {:seq 2 :request-id "other1" :hold-id "h1" :user-id "u"
               :payment-ref "p3" :reason :hold-confirmed}]
             (p/get-compensations b "e" 0 500))
          "each compensating rejection appends exactly one record")
      (is (= {"a" {:state :confirmed :hold-id "h1" :user-id "u"}
              "b" {:state :confirmed :hold-id "h1" :user-id "u"}
              "c" {:state :held :hold-id "h3" :user-id "w"}
              "d" {:state :held :hold-id "h3" :user-id "w"}}
             (p/get-seats b "e" ["a" "b" "c" "d"])))
      (is (= :released (:state (p/get-hold b "e" "h2")))))))

(defn race!
  "Each client issues its command concurrently; returns after all appends."
  [clients f]
  (let [start (promise)
        fs (mapv (fn [i c] (future @start (f i c))) (range) clients)]
    (deliver start true)
    (run! deref fs)))

(deftest concurrent-clients-never-double-sell
  (doseq [tasks [2 4]]
    (with-clients [clients 4 tasks]
      (let [b (first clients)]
        (p/create-event! b "create" "e")
        (harness/wait-for-processing! b)
        (doseq [round (range 6)]
          ;; Same three seats, different orders: exactly one winner.
          (let [seats (mapv #(str "x" round "-" %) (range 3))
                rid #(str "same-" round "-" %)]
            (p/add-seats! b (str "add-" round) "e" seats)
            (harness/wait-for-processing! b)
            (race! clients (fn [i c]
                             (p/hold-seats! c (rid i) "e" (str "u" i)
                                            (vec (take 3 (drop i (cycle seats)))) 1000)))
            (harness/wait-for-processing! b)
            (let [outcomes (mapv #(p/get-outcome b "e" (rid %)) (range 4))
                  winners (keep-indexed #(when (= :accepted (:status %2)) %1) outcomes)]
              (is (= 1 (count winners)) (str "round " round ": " outcomes))
              (is (every? #(= :seat-unavailable (:reason %))
                          (remove #(= :accepted (:status %)) outcomes)))
              (when (= 1 (count winners))
                (let [w (first winners)]
                  (is (= (zipmap seats (repeat {:state :held :hold-id (rid w)
                                                :user-id (str "u" w)}))
                         (p/get-seats b "e" seats)))))))
          ;; Ring of overlapping pairs: accepted holds must be disjoint and
          ;; every held seat must belong to an accepted hold that names it.
          (let [ring (mapv #(str "r" round "-" %) (range 4))
                sets (mapv #(vector (ring %) (ring (mod (inc %) 4))) (range 4))
                rid #(str "ring-" round "-" %)]
            (p/add-seats! b (str "add-ring-" round) "e" ring)
            (harness/wait-for-processing! b)
            (race! clients (fn [i c] (p/hold-seats! c (rid i) "e" (str "u" i) (sets i) 1000)))
            (harness/wait-for-processing! b)
            (let [won (filterv #(= :accepted (:status (p/get-outcome b "e" (rid %)))) (range 4))
                  view (p/get-seats b "e" ring)]
              (is (seq won))
              (is (apply distinct? (mapcat sets won))
                  (str "round " round ": overlapping holds both accepted " won))
              (doseq [i won, s (sets i)]
                (is (= (rid i) (get-in view [s :hold-id]))))
              (doseq [[s {:keys [state hold-id]}] view
                      :when (= :held state)]
                (is (some #(= hold-id (rid %)) won) (str s " held by a rejected hold"))))))
        ;; Last seat: four clients race to confirm/steal the single remaining unit.
        (p/add-seats! b "add-last" "e" ["last"])
        (harness/wait-for-processing! b)
        (race! clients (fn [i c] (p/hold-seats! c (str "last-" i) "e" (str "u" i) ["last"] 1000)))
        (harness/wait-for-processing! b)
        (let [won (filterv #(= :accepted (:status (p/get-outcome b "e" (str "last-" %)))) (range 4))]
          (is (= 1 (count won)))
          (race! clients (fn [i c] (p/confirm-hold! c (str "pay-" i) "e"
                                                    (str "last-" (first won)) (str "u" i) (str "p" i))))
          (harness/wait-for-processing! b)
          (is (= [(str "pay-" (first won))]
                 (filterv #(= :accepted (:status (p/get-outcome b "e" %)))
                          (map #(str "pay-" %) (range 4)))))
          (is (= {:state :confirmed :hold-id (str "last-" (first won))
                  :user-id (str "u" (first won))}
                 (get (p/get-seats b "e" ["last"]) "last"))))))))

(deftest mutating-commands-bounded-by-own-history
  (doseq [tasks [2 4]]
    (with-clients [[a b] 2 tasks]
      (p/create-event! a "create" "e")
      (harness/wait-for-processing! b)
      (let [measure (fn [f] (nfr/capture-rocks-ops #(do (f) (harness/wait-for-processing! b))))
            [small large]
            (mapv
              (fn [[tag start end deadline churn]]
                ;; Growing event history: seats, expired holds, compensations.
                (doseq [ids (partition-all 1000 (range start end))]
                  (p/add-seats! a (str "bulk-" (first ids)) "e" (mapv #(str "s" %) ids)))
                (doseq [i (range start end)]
                  (p/hold-seats! a (str "noise-" i) "e" "u" [(str "s" i)] deadline))
                (p/advance-clock! a (str "tick-" tag) "e" deadline)
                (doseq [i (range start end)]
                  (p/confirm-hold! a (str "late-" i) "e" (str "noise-" i) "u" "p"))
                ;; Per-seat churn on the seats the measured commands touch.
                (let [target (mapv #(str "t" tag "-" %) (range 8))
                      fresh (mapv #(str "f" tag "-" %) (range 10))]
                  (p/add-seats! a (str "targets-" tag) "e" target)
                  (dotimes [k churn]
                    (p/hold-seats! a (str "churn-" tag "-" k) "e" "c" target 1000000)
                    (p/release-hold! a (str "unchurn-" tag "-" k) "e" (str "churn-" tag "-" k) "c"))
                  (harness/wait-for-processing! b)
                  (let [samples
                        {:add-seats (measure #(p/add-seats! a (str "add-" tag) "e" fresh))
                         :hold-seats (measure #(p/hold-seats! a (str "hold-" tag) "e" "buyer" target 1000000))
                         :confirm-hold (measure #(p/confirm-hold! a (str "pay-" tag) "e" (str "hold-" tag) "buyer" "ref"))
                         :hold-fresh (measure #(p/hold-seats! a (str "hold2-" tag) "e" "buyer" (subvec fresh 0 8) 1000000))
                         :release-hold (measure #(p/release-hold! a (str "rel-" tag) "e" (str "hold2-" tag) "buyer"))}]
                    ;; Every measured command took its effective path.
                    (is (= (accepted :add-seats {:added 10}) (p/get-outcome b "e" (str "add-" tag))))
                    (is (= (accepted :hold-seats {:hold-id (str "hold-" tag) :deadline 1000000})
                           (p/get-outcome b "e" (str "hold-" tag))))
                    (is (= (accepted :confirm-hold {:seat-ids target :payment-ref "ref"})
                           (p/get-outcome b "e" (str "pay-" tag))))
                    (is (= (accepted :release-hold {:seat-ids (subvec fresh 0 8)})
                           (p/get-outcome b "e" (str "rel-" tag))))
                    (is (= [end] (mapv :seq (p/get-compensations b "e" (dec end) 1)))
                        "fixture produced the compensation history")
                    samples)))
              [["a" 0 256 10 5] ["b" 256 1280 20 50]])]
        (println "Ticketing mutating work" tasks "tasks" small "->" large)
        (doseq [op (keys small)]
          (is (pos? (get-in small [op :writes])) (str op " measured no writes"))
          (doseq [metric [:reads :iterators :iterator-reads :writes]]
            (is (<= (get-in large [op metric]) (+ 24 (* 2 (get-in small [op metric]))))
                (str op " " metric " grew with own history: " (small op) " -> " (large op)))))))))

(deftest compensation-pages-read-each-returned-record
  (doseq [tasks [2 4]]
    (with-clients [[a b] 2 tasks]
      (p/create-event! a "create" "e")
      (p/add-seats! a "add" "e" ["s"])
      (p/hold-seats! a "h" "e" "u" ["s"] 1)
      (p/advance-clock! a "tick" "e" 1)
      (doseq [i (range 300)] (p/confirm-hold! a (str "late-" i) "e" "h" "u" (str "p" i)))
      (harness/wait-for-processing! b)
      (doseq [limit [5 20 100]]
        (let [[page ops] (nfr/capture-rocks-ops-with-result #(p/get-compensations b "e" 150 limit))
              touched (+ (:reads ops) (:iterator-reads ops))]
          (is (= (vec (range 151 (+ 151 limit))) (mapv :seq page)))
          (is (<= limit touched (+ (* 2 limit) 8))
              (str "limit " limit ": a compensation page must read about one stored entry per "
                   "returned record, not the log as one value; " ops)))))))
