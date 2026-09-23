(ns hld-ticketing-system.ticketing-test
  (:require [clojure.test :refer :all]
            [com.rpl.rama.test :as rtest]
            [rama-challenges.harness :as harness]
            [hld-ticketing-system.protocol :as p]))

(defn descriptor []
  (if-let [f (requiring-resolve 'hld-ticketing-system.module/create-module)]
    (f)
    (throw (IllegalStateException. "Missing create-module"))))

(defmacro with-ticketing [[a b tasks] & body]
  `(let [descriptor# (descriptor)]
     (with-open [ipc# (rtest/create-ipc)]
       (rtest/launch-module! ipc# (:module descriptor#) {:tasks ~tasks :threads 2})
       (let [~a ((:wrap-client descriptor#) ipc#)
             ~b ((:wrap-client descriptor#) ipc#)]
         ~@body))))

(deftest event-lifecycle
  (doseq [tasks [2 4]]
    (with-ticketing [a b tasks]
      (p/create-event! a "create" "show")
      (harness/wait-for-processing! b)
      (is (= 0 (p/get-clock b "show")))
      (is (= {:status :accepted :command :create-event :conflicting-attempts 0}
             (p/get-outcome b "show" "create"))))))

(defn accepted [command & [fields]]
  (merge {:status :accepted :command command :conflicting-attempts 0} fields))

(defn rejected [command reason & [fields]]
  (merge {:status :rejected :command command :reason reason
          :conflicting-attempts 0} fields))

(deftest precreation-and-structural-retries
  (doseq [tasks [2 4]]
    (with-ticketing [a b tasks]
      (p/hold-seats! a "early" "e" "u" ["s"] 7)
      (p/create-event! a "create" "e")
      (harness/wait-for-processing! b)
      (is (= (rejected :hold-seats :no-such-event) (p/get-outcome b "e" "early")))
      (is (= 0 (p/get-clock b "e")))
      (p/hold-seats! b "early" "e" "u" ["s"] 7)
      (p/hold-seats! b "early" "e" "u" ["s"] 8)
      (is (thrown? IllegalArgumentException
                   (p/hold-seats! b "early" "e" "u" (vec (repeat 9 "s")) 8)))
      (is (thrown? IllegalArgumentException
                   (p/hold-seats! b "fresh" "e" "u" ["s" "s"] 8)))
      (p/add-seats! a "fresh" "e" ["s"])
      (harness/wait-for-processing! b)
      (is (= (assoc (rejected :hold-seats :no-such-event) :conflicting-attempts 1)
             (p/get-outcome a "e" "early")))
      (is (= (accepted :add-seats {:added 1}) (p/get-outcome b "e" "fresh")))
      (is (nil? (p/get-hold b "e" "early")))
      (is (thrown? IllegalArgumentException (p/get-seats b "e" [])))
      (is (thrown? IllegalArgumentException (p/get-compensations b "e" -1 1)))
      (is (thrown? IllegalArgumentException (p/get-compensations b "e" 0 501))))))

(deftest ordered-holds-and-expiry-fencing
  (doseq [tasks [2 4]]
    (with-ticketing [a b tasks]
      (p/create-event! a "create" "e")
      (p/add-seats! a "add" "e" ["a" "b" "c" "d"])
      (p/hold-seats! a "h1" "e" "owner" ["a" "b"] 7)
      (p/hold-seats! a "h2" "e" "other" ["c" "b"] 11)
      (p/advance-clock! a "tick" "e" 7)
      (p/hold-seats! a "h3" "e" "other" ["a" "b"] 12)
      (p/confirm-hold! a "late" "e" "h1" "owner" "paid")
      (harness/wait-for-processing! b)
      (is (= (accepted :hold-seats {:hold-id "h1" :deadline 7}) (p/get-outcome b "e" "h1")))
      (is (= (rejected :hold-seats :seat-unavailable {:unavailable-seats ["b"]})
             (p/get-outcome b "e" "h2")))
      (is (= (accepted :hold-seats {:hold-id "h3" :deadline 12}) (p/get-outcome b "e" "h3")))
      (is (= (rejected :confirm-hold :hold-expired {:compensation-seq 1})
             (p/get-outcome b "e" "late")))
      (is (= [{:seq 1 :request-id "late" :hold-id "h1" :user-id "owner"
               :payment-ref "paid" :reason :hold-expired}]
             (p/get-compensations b "e" 0 1)))
      (is (= {:state :held :hold-id "h3" :user-id "other"}
             (get (p/get-seats b "e" ["a" "a" "d"]) "a")))
      (is (= {:state :available :hold-id nil :user-id nil}
             (get (p/get-seats b "e" ["d"]) "d")))
      (is (= :expired (:state (p/get-hold b "e" "h1"))))
      (p/release-hold! a "stale-release" "e" "h1" "owner")
      (harness/wait-for-processing! b)
      (is (= {"a" {:state :held :hold-id "h3" :user-id "other"}
              "b" {:state :held :hold-id "h3" :user-id "other"}}
             (p/get-seats b "e" ["a" "b"])))
      (p/confirm-hold! a "good" "e" "h3" "other" "p1")
      (p/confirm-hold! a "same" "e" "h3" "other" "p1")
      (p/confirm-hold! a "different" "e" "h3" "other" "p2")
      (p/confirm-hold! a "wrong" "e" "h3" "intruder" "p3")
      (p/release-hold! a "sold-release" "e" "h3" "other")
      (p/confirm-hold! a "late" "e" "h1" "owner" "paid")
      (p/confirm-hold! a "late2" "e" "h1" "owner" "paid")
      (harness/wait-for-processing! b)
      (is (= (rejected :release-hold :hold-expired) (p/get-outcome b "e" "stale-release")))
      (is (= (accepted :confirm-hold {:seat-ids ["a" "b"] :payment-ref "p1"})
             (p/get-outcome b "e" "good")))
      (is (= (rejected :confirm-hold :hold-confirmed) (p/get-outcome b "e" "same")))
      (is (= (rejected :confirm-hold :hold-confirmed {:compensation-seq 2})
             (p/get-outcome b "e" "different")))
      (is (= (rejected :confirm-hold :not-owner) (p/get-outcome b "e" "wrong")))
      (is (= (rejected :release-hold :hold-confirmed) (p/get-outcome b "e" "sold-release")))
      (is (= [1 2 3] (mapv :seq (p/get-compensations b "e" 0 500))))
      (is (= [2 3] (mapv :seq (p/get-compensations b "e" 1 2))))
      (is (= [1] (mapv :seq (p/get-compensations b "e" 0 1))))
      (is (= [2] (mapv :seq (p/get-compensations b "e" 1 1))))
      (is (= [3] (mapv :seq (p/get-compensations b "e" 2 1))))
      (is (= [] (p/get-compensations b "e" 3 1)))
      (is (= [] (p/get-compensations b "e" Long/MAX_VALUE 1)))
      (is (= {:state :confirmed :hold-id "h3" :user-id "other"}
             (get (p/get-seats b "e" ["a"]) "a"))))))

(deftest release-and-boundaries
  (doseq [tasks [2 4]]
    (with-ticketing [a b tasks]
      (p/create-event! a "create" "e")
      (p/add-seats! a "add" "e" ["a" "b" "c"])
      (p/hold-seats! a "zero" "e" "u" ["a"] 0)
      (p/hold-seats! a "h" "e" "u" ["a" "b"] 11)
      (p/release-hold! a "release" "e" "h" "u")
      (p/hold-seats! a "new" "e" "u2" ["b" "c"] 12)
      (p/confirm-hold! a "released-payment" "e" "h" "u" "ref")
      (p/advance-clock! a "equal" "e" 12)
      (harness/wait-for-processing! b)
      (is (= (rejected :hold-seats :deadline-passed) (p/get-outcome b "e" "zero")))
      (is (= (accepted :release-hold {:seat-ids ["a" "b"]}) (p/get-outcome b "e" "release")))
      (is (= (rejected :confirm-hold :hold-released {:compensation-seq 1})
             (p/get-outcome b "e" "released-payment")))
      (is (= :expired (:state (p/get-hold b "e" "new"))))
      (is (= :released (:state (p/get-hold b "e" "h"))))
      (is (= {:state :available :hold-id nil :user-id nil}
             (get (p/get-seats b "e" ["b"]) "b")))
      (is (= (rejected :advance-clock :clock-regression)
             (do (p/advance-clock! a "regress" "e" 11)
                 (harness/wait-for-processing! b)
                 (p/get-outcome b "e" "regress")))))))

(deftest large-input-and-atomicity
  (doseq [tasks [2 4]]
    (with-ticketing [a b tasks]
      (let [ids (mapv #(str "s" %) (range 1000))
            group (subvec ids 0 8)]
        (p/create-event! a "create" "e")
        (p/add-seats! a "bulk" "e" ids)
        (p/add-seats! a "collision" "e" ["extra" "s999"])
        (p/hold-seats! a "eight" "e" "u" group 1)
        (p/hold-seats! a "blocked" "e" "v" ["extra" "s8" "s7"] 2)
        (harness/wait-for-processing! b)
        (is (= (accepted :add-seats {:added 1000}) (p/get-outcome b "e" "bulk")))
        (is (= (rejected :add-seats :seat-exists) (p/get-outcome b "e" "collision")))
        (is (= (rejected :hold-seats :no-such-seat) (p/get-outcome b "e" "blocked")))
        (is (= {:state :held :hold-id "eight" :user-id "u"}
               (get (p/get-seats b "e" ["s7"]) "s7")))
        (is (= {"extra" nil} (p/get-seats b "e" ["extra"])))
        (p/hold-seats! a "last-blocker" "e" "v"
                       ["s8" "s9" "s10" "s11" "s12" "s13" "s14" "s7"] 2)
        (harness/wait-for-processing! b)
        (is (= (rejected :hold-seats :seat-unavailable {:unavailable-seats ["s7"]})
               (p/get-outcome b "e" "last-blocker")))
        (is (= {:state :available :hold-id nil :user-id nil}
               (get (p/get-seats b "e" ["s8"]) "s8")))
        (let [page (p/get-seats b "e" (subvec ids 0 64))]
          (is (= 64 (count page)))
          (is (= :held (get-in page ["s0" :state])))
          (is (= :available (get-in page ["s63" :state]))))))))

(deftest cross-command-conflict-and-rejected-replay
  (doseq [tasks [2 4]]
    (with-ticketing [a b tasks]
      (p/create-event! a "create" "e")
      (p/add-seats! a "add" "e" ["s"])
      (p/hold-seats! a "h" "e" "owner" ["s"] 7)
      (p/hold-seats! a "blocked" "e" "owner" ["s"] 11)
      (harness/wait-for-processing! b)
      (p/advance-clock! a "tick" "e" 7)
      (harness/wait-for-processing! b)
      (p/hold-seats! a "blocked" "e" "owner" ["s"] 11)
      (harness/wait-for-processing! b)
      (is (= (rejected :hold-seats :seat-unavailable {:unavailable-seats ["s"]})
             (p/get-outcome b "e" "blocked")))
      (is (= {:state :available :hold-id nil :user-id nil}
             (get (p/get-seats b "e" ["s"]) "s")))
      (p/hold-seats! a "next" "e" "other" ["s"] 12)
      (p/hold-seats! a "h" "e" "owner" ["s"] 7)
      (p/hold-seats! a "blocked" "e" "owner" ["s"] 11)
      (p/advance-clock! a "h" "e" 9007199254740992)
      (harness/wait-for-processing! b)
      (is (= (assoc (accepted :hold-seats {:hold-id "h" :deadline 7})
                    :conflicting-attempts 1)
             (p/get-outcome b "e" "h")))
      (is (= (rejected :hold-seats :seat-unavailable {:unavailable-seats ["s"]})
             (p/get-outcome b "e" "blocked")))
      (is (= 7 (p/get-clock b "e")))
      (is (= :held (get-in (p/get-seats b "e" ["s"]) ["s" :state])))
      (is (= "next" (get-in (p/get-seats b "e" ["s"]) ["s" :hold-id]))))))

(deftest update-retains-durable-records
  (doseq [tasks [2 4]]
    (let [{:keys [module wrap-client]} (descriptor)]
      (with-open [ipc (rtest/create-ipc)]
        (rtest/launch-module! ipc module {:tasks tasks :threads 2})
        (let [a (wrap-client ipc)
              b (wrap-client ipc)]
          (p/create-event! a "create" "e")
          (p/add-seats! a "add" "e" ["s"])
          (p/hold-seats! a "h" "e" "u" ["s"] 7)
          (p/advance-clock! a "tick" "e" 7)
          (p/confirm-hold! a "late" "e" "h" "u" "p1")
          (harness/wait-for-processing! b)
          (is (= 1 (:compensation-seq (p/get-outcome b "e" "late"))))
          (rtest/update-module! ipc module)
          (is (= 7 (p/get-clock b "e")))
          (is (= :expired (:state (p/get-hold b "e" "h"))))
          (is (= (rejected :confirm-hold :hold-expired {:compensation-seq 1})
                 (p/get-outcome b "e" "late")))
          (is (= [{:seq 1 :request-id "late" :hold-id "h" :user-id "u"
                   :payment-ref "p1" :reason :hold-expired}]
                 (p/get-compensations b "e" 0 1)))
          (p/confirm-hold! a "late2" "e" "h" "u" "p2")
          (p/confirm-hold! a "late" "e" "h" "u" "p1")
          (p/confirm-hold! a "late" "e" "h" "u" "changed")
          (harness/wait-for-processing! b)
          (is (= 2 (:compensation-seq (p/get-outcome b "e" "late2"))))
          (is (= (assoc (rejected :confirm-hold :hold-expired {:compensation-seq 1})
                        :conflicting-attempts 1)
                 (p/get-outcome b "e" "late")))
          (is (= [1 2] (mapv :seq (p/get-compensations b "e" 0 500)))))))))

(deftest event-isolation
  (doseq [tasks [2 4]]
    (with-ticketing [a b tasks]
      (p/hold-seats! a "hold" "absent" "u" ["s"] 7)
      (p/create-event! a "same" "north")
      (p/create-event! a "same" "south")
      (p/add-seats! a "seats" "north" ["s"])
      (p/add-seats! a "seats" "south" ["s"])
      (p/hold-seats! a "hold" "north" "u" ["s"] 7)
      (p/hold-seats! a "hold" "south" "u" ["s"] 11)
      (p/advance-clock! a "tick" "north" 7)
      (p/confirm-hold! a "late" "north" "hold" "u" "ref")
      (harness/wait-for-processing! b)
      (is (= (rejected :hold-seats :no-such-event) (p/get-outcome b "absent" "hold")))
      (is (nil? (p/get-clock b "absent")))
      (is (nil? (p/get-seats b "absent" ["s"])))
      (is (nil? (p/get-hold b "absent" "hold")))
      (is (= [] (p/get-compensations b "absent" 0 1)))
      (is (= 7 (p/get-clock b "north")))
      (is (= 0 (p/get-clock b "south")))
      (is (= :expired (:state (p/get-hold b "north" "hold"))))
      (is (= :active (:state (p/get-hold b "south" "hold"))))
      (is (= [1] (mapv :seq (p/get-compensations b "north" 0 1))))
      (is (= [] (p/get-compensations b "south" 0 1)))
      (p/advance-clock! a "tick" "south" 11)
      (p/confirm-hold! a "late" "south" "hold" "u" "ref")
      (harness/wait-for-processing! b)
      (is (= [1] (mapv :seq (p/get-compensations b "south" 0 1)))))))

(defn rocks-cost [f]
  (let [counts (atom {:reads 0 :writes 0})]
    (rtest/with-event-hook
      (fn [kind data]
        (case kind
          :rocks-read (swap! counts update :reads inc)
          :rocks-iterator (swap! counts update :reads inc)
          :rocks-iterator-read (swap! counts update :reads inc)
          :rocks-commit (swap! counts update :writes + (:write-batch-count data))
          nil))
      (f)
      @counts)))

(deftest bounded-own-history-work
  (doseq [tasks [2 4]]
    (with-ticketing [a b tasks]
      (p/create-event! a "create" "e")
      (p/add-seats! a "target" "e" ["target"])
      (p/hold-seats! a "h" "e" "u" ["target"] 1)
      (p/advance-clock! a "tick" "e" 1)
      (harness/wait-for-processing! b)
      ;; Fixed touched/output sizes, growing same-event seats, holds, requests
      ;; and compensation history. No unpublished absolute operation ceiling.
      (let [[small large]
            (mapv
              (fn [[start end deadline old-clock]]
                (doseq [ids (partition-all 1000 (range start end))]
                  (p/add-seats! a (str "bulk-" (first ids)) "e"
                                (mapv #(str "s" %) ids)))
                (doseq [i (range start end)]
                  (p/hold-seats! a (str "noise-" i) "e" "u" [(str "s" i)] deadline)
                  (p/confirm-hold! a (str "late-" i) "e" "h" "u" (str "p-" i)))
                (harness/wait-for-processing! b)
                (let [samples
                      {:seat (rocks-cost #(is (= {"target" {:state :available :hold-id nil :user-id nil}}
                                                (p/get-seats b "e" ["target"]))))
                       :page (rocks-cost #(is (= [end] (mapv :seq (p/get-compensations b "e" (dec end) 1)))))
                       :hold (rocks-cost #(is (= :expired (:state (p/get-hold b "e" "h")))))
                       :outcome (rocks-cost #(is (= :accepted (:status (p/get-outcome b "e" "h")))))
                       :clock (rocks-cost #(is (= old-clock (p/get-clock b "e"))))
                       :advance (rocks-cost #(do (p/advance-clock! a (str "tick-" deadline) "e" deadline)
                                                 (harness/wait-for-processing! b)))}]
                  (is (= deadline (p/get-clock b "e")))
                  (is (= :expired (:state (p/get-hold b "e" (str "noise-" (dec end))))))
                  samples))
              [[0 256 10 1] [256 1280 20 10]])]
        (println "Ticketing paired work" tasks "tasks" small "->" large)
        (is (pos? (get-in small [:clock :reads])))
        (is (pos? (get-in small [:advance :writes])))
        (doseq [operation [:seat :page :hold :outcome :clock :advance]
                metric [:reads :writes]]
          (is (<= (get-in large [operation metric])
                  (+ 24 (* 2 (get-in small [operation metric]))))
              (str operation " " metric " grew with own history: " small " -> " large)))))))
