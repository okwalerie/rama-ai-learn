(ns hld-hotel-reservation.work-test
  (:require [clojure.test :refer :all]
            [com.rpl.rama.test :as rtest]
            [hld-hotel-reservation.protocol :as p]
            [rama-challenges.harness :as harness]))

(defn work [f]
  (let [counts (atom {:reads 0 :iterations 0 :writes 0})]
    (rtest/with-event-hook
      (fn [kind data]
        (case kind
          :rocks-read (swap! counts update :reads inc)
          :rocks-iterator-read (swap! counts update :iterations inc)
          :rocks-commit (swap! counts update :writes + (:write-batch-count data))
          nil))
      (f)
      @counts)))

(defn populate! [a start end]
  ;; Same-property nights, bookings, journal events, and request outcomes grow
  ;; together. The observed stay and booking remain fixed at night 0 / booking-1.
  (doseq [n (range start (inc end))]
    (p/init-night! a (str "night-" n) "p" "r" n 1 5)
    (p/reserve! a (str "booking-" n) "p" "r" "g" n (inc n) 1))
  (harness/wait-for-processing! a))

(defn sample [a size]
  (let [results (atom {})
        available (if (= size 240) 3 2)
        measure (fn [label f]
                  (let [value (atom nil)
                        counts (work #(reset! value (f)))]
                    (swap! results assoc label counts)
                    @value))]
    (is (= {:night 0 :capacity 3 :rate 7 :available available}
           (measure :night #(p/get-night a "p" "r" 0))))
    (is (= [{:night 0 :capacity 3 :rate 7 :available available}]
           (measure :availability #(p/get-availability a "p" "r" 0 1))))
    (is (= {:booking-id "booking-1" :guest-id "g" :room-type "r"
            :checkin 1 :checkout 2 :quantity 1 :total 5 :state :confirmed :seq 1}
           (measure :booking #(p/get-booking a "p" "booking-1"))))
    (is (= :accepted (:status (measure :outcome #(p/get-outcome a "p" "booking-1")))))
    (is (= [2 3] (mapv :seq (measure :first-page #(p/get-booking-events a "p" 1 2)))))
    (is (= [(- size 1) size]
           (mapv :seq (measure :deep-page #(p/get-booking-events a "p" (- size 2) 2)))))
    ;; A fresh request, fixed one-night stay; no growing-history work is needed.
    (let [rid (str "bounded-" size)]
      (measure :reservation #(do (p/reserve! a rid "p" "r" "g" 0 1 1)
                                (harness/wait-for-processing! a)))
      (is (= 7 (:total (p/get-booking a "p" rid)))))
    @results))

(deftest bounded-own-history
  ;; These are aggregate storage hooks across tasks, not per-task latency or
  ;; bytes deserialized. A source/layout audit remains necessary for opaque values.
  (doseq [tasks [2 4]]
    (testing (str tasks " tasks")
      (let [{:keys [module wrap-client]} ((requiring-resolve 'hld-hotel-reservation.module/create-module))]
        (with-open [ipc (rtest/create-ipc)]
          (rtest/launch-module! ipc module {:tasks tasks :threads tasks})
          (let [a (wrap-client ipc)]
            (p/create-property! a "p" "p")
            (p/create-room-type! a "r" "p" "r")
            (p/init-night! a "base" "p" "r" 0 3 7)
            (doseq [n (range 20)]
              (p/create-property! a (str "other-" n) (str "other-" n)))
            (harness/wait-for-processing! a)
            (populate! a 1 240)
            (let [small (sample a 240)]
              (populate! a 241 1200)
              (let [large (sample a 1201)]
                ;; The first one-night reservation also emitted an event, so the
                ;; second population has 1201 journal entries at its end.
                (println "Hotel paired work" tasks "tasks" small "->" large)
                (doseq [label [:night :availability :booking :outcome
                               :first-page :deep-page :reservation]
                        metric [:reads :iterations :writes]]
                  (let [before (get-in small [label metric])
                        after (get-in large [label metric])]
                    (is (<= after (+ (* 2 before) 24))
                        (str label " " metric " grew with history: " before " -> " after))))))))))))
