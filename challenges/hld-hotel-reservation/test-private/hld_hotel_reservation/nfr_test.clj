(ns hld-hotel-reservation.nfr-test
  "NFR tests: exactly-once commands under a forced stream retry, journal pages
   that read each returned entry, measured reserve/cancel/configuration paths
   as the property's own history grows, and concurrent clients racing for the
   same nights."
  (:require [clojure.test :refer :all]
            [com.rpl.rama.test :as rtest]
            [hld-hotel-reservation.protocol :as p]
            [rama-challenges.harness :as harness]
            [rama-challenges.nfr :as nfr]))

(defn- exercise [tasks n f]
  (let [{:keys [module wrap-client]}
        ((requiring-resolve 'hld-hotel-reservation.module/create-module))]
    (with-open [ipc (rtest/create-ipc)]
      (rtest/launch-module! ipc module {:tasks tasks :threads tasks})
      (f (vec (repeatedly n #(wrap-client ipc)))))))

(defn- retried!
  "Runs command f and waits, failing the first stream completion so a stream
   design retries it. A microbatch design sees no forced failure."
  [c f]
  (nfr/failed-streaming #(do (f) (harness/wait-for-processing! c))))

(deftest forced-stream-retry-applies-commands-exactly-once
  (doseq [tasks [2 4]]
    (testing (str tasks " tasks")
      (exercise tasks 2
        (fn [[a b]]
          (p/create-property! a "p" "p")
          (p/create-room-type! a "r" "p" "r")
          (harness/wait-for-processing! a)
          (doseq [n (range 10 14)]
            (retried! b #(p/init-night! a (str "n" n) "p" "r" n 3 10)))
          (retried! b #(p/set-rate! a "rate" "p" "r" 12 20))
          (retried! b #(p/reserve! a "b1" "p" "r" "g" 10 14 2))
          (retried! b #(p/reserve! a "b2" "p" "r" "h" 11 13 1))
          (retried! b #(p/reserve! a "full" "p" "r" "h" 12 14 1))
          (retried! b #(p/cancel-booking! a "c1" "p" "b1" "g"))
          (retried! b #(p/cancel-booking! a "c1-again" "p" "b1" "g"))
          (retried! b #(p/reserve! a "b1" "p" "r" "g" 10 14 2))
          (retried! b #(p/reserve! a "b1" "p" "r" "g" 10 14 1))
          (is (= {:status :accepted :command :reserve :booking-id "b1" :total 100
                  :nights 4 :seq 1 :conflicting-attempts 1}
                 (p/get-outcome b "p" "b1")))
          (is (= {:status :accepted :command :reserve :booking-id "b2" :total 30
                  :nights 2 :seq 2 :conflicting-attempts 0}
                 (p/get-outcome b "p" "b2")))
          (is (= [:rejected :insufficient-capacity [12]]
                 ((juxt :status :reason :nights) (p/get-outcome b "p" "full"))))
          (is (= [:accepted 3] ((juxt :status :seq) (p/get-outcome b "p" "c1"))))
          (is (= :booking-cancelled (:reason (p/get-outcome b "p" "c1-again"))))
          (is (= [3 2 2 3] (mapv :available (p/get-availability b "p" "r" 10 14)))
              "each accepted reservation debits once and the cancellation restores once")
          (is (= [[1 :reserved "b1"] [2 :reserved "b2"] [3 :cancelled "c1"]]
                 (mapv (juxt :seq :type :request-id) (p/get-booking-events b "p" 0 500)))
              "one journal event per accepted reserve or cancel, seqs contiguous"))))))

(deftest journal-page-reads-each-returned-event
  ;; A page must read its entries from a structure it can range over, not
  ;; deserialize the whole journal as one value (one read for any page).
  (doseq [tasks [2 4]]
    (testing (str tasks " tasks")
      (exercise tasks 1
        (fn [[a]]
          (p/create-property! a "p" "p")
          (p/create-room-type! a "r" "p" "r")
          (p/init-night! a "n" "p" "r" 0 1000 1)
          (doseq [i (range 300)]
            (p/reserve! a (str "b" i) "p" "r" "g" 0 1 1))
          (harness/wait-for-processing! a)
          (doseq [limit [5 20]]
            (let [[page ops] (nfr/capture-rocks-ops-with-result
                              #(p/get-booking-events a "p" 200 limit))
                  touched (+ (:reads ops) (:iterator-reads ops))]
              (is (= (vec (range 201 (+ 201 limit))) (mapv :seq page)))
              (is (<= limit touched (+ (* 2 limit) 8))
                  (str "limit " limit ": a page must read about one stored entry per returned event; "
                       ops)))))))))

(defn- populate!
  "Grows the property's own nights, bookings, journal and outcomes. Night n
   (1..) gets capacity 1 and one booking; the measured nights are elsewhere."
  [a start end]
  (doseq [n (range start end)]
    (p/init-night! a (str "night-" n) "p" "r" n 1 5)
    (p/reserve! a (str "booking-" n) "p" "r" "g" n (inc n) 1))
  (harness/wait-for-processing! a))

(defn- sample [a tag]
  (let [measure (fn [f] (nfr/capture-rocks-ops #(do (f) (harness/wait-for-processing! a))))
        rid #(str % "-" tag)
        samples
        {:reserve-1 (measure #(p/reserve! a (rid "one") "p" "r" "g" 0 1 1))
         :cancel-1 (measure #(p/cancel-booking! a (rid "cancel-one") "p" (rid "one") "g"))
         :reserve-30 (measure #(p/reserve! a (rid "long") "p" "long" "g" 0 30 2))
         :cancel-30 (measure #(p/cancel-booking! a (rid "cancel-long") "p" (rid "long") "g"))
         :init-night (measure #(p/init-night! a (rid "init") "p" "extra" (if (= tag "small") 0 1) 5 5))
         :set-rate (measure #(p/set-rate! a (rid "rate") "p" "r" 0 (if (= tag "small") 8 9)))}]
    ;; Every measured command took its accepted (effective) path.
    (doseq [r ["one" "cancel-one" "long" "cancel-long" "init" "rate"]]
      (is (= :accepted (:status (p/get-outcome a "p" (rid r)))) (rid r)))
    (is (= 30 (:nights (p/get-outcome a "p" (rid "long")))))
    samples))

(deftest reserve-and-cancel-bounded-by-stay-not-history
  (doseq [tasks [2 4]]
    (testing (str tasks " tasks")
      (exercise tasks 1
        (fn [[a]]
          (p/create-property! a "p" "p")
          (doseq [rt ["r" "long" "extra"]]
            (p/create-room-type! a rt "p" rt))
          (p/init-night! a "base" "p" "r" 0 3 7)
          (doseq [n (range 30)]
            (p/init-night! a (str "long-" n) "p" "long" n 4 1))
          (harness/wait-for-processing! a)
          (populate! a 1 241)
          (let [small (sample a "small")]
            (populate! a 241 1201)
            (let [large (sample a "large")]
              (println "Hotel command work" tasks "tasks" small "->" large)
              (doseq [op (keys small)]
                (is (pos? (get-in small [op :writes])) (str op " measured no writes"))
                (doseq [metric [:reads :iterators :iterator-reads :writes]]
                  (let [before (get-in small [op metric])
                        after (get-in large [op metric])]
                    (is (<= after (+ 24 (* 2 before)))
                        (str op " " metric " grew with the property's own history: "
                             before " -> " after))))))))))))

(defn- race!
  "Each client issues (f i client) concurrently; returns when all have returned."
  [clients f]
  (let [start (promise)
        fs (mapv (fn [i c] (future @start (f i c))) (range) clients)]
    (deliver start true)
    (run! deref fs)))

(deftest concurrent-clients-never-overbook
  (doseq [tasks [2 4]]
    (testing (str tasks " tasks")
      (exercise tasks 4
        (fn [clients]
          (let [a (first clients)]
            (p/create-property! a "p" "p")
            (p/create-room-type! a "r" "p" "r")
            (harness/wait-for-processing! a)
            (doseq [round (range 6)]
              (let [base (* 10 round)
                    rid #(str "race-" round "-" %)]
                (doseq [n (range base (+ base 6))]
                  (p/init-night! a (str "n" n) "p" "r" n 1 10))
                (harness/wait-for-processing! a)
                ;; The last room on each night; overlapping 3-night stays.
                (race! clients (fn [i c] (p/reserve! c (rid i) "p" "r" (str "g" i)
                                                     (+ base i) (+ base i 3) 1)))
                (harness/wait-for-processing! a)
                (let [outcomes (mapv #(p/get-outcome a "p" (rid %)) (range 4))
                      won (filterv #(= :accepted (:status (outcomes %))) (range 4))
                      nights (fn [i] (range (+ base i) (+ base i 3)))
                      avail (mapv :available (p/get-availability a "p" "r" base (+ base 6)))]
                  (is (seq won) (str "round " round ": no reservation accepted " outcomes))
                  (is (apply distinct? (mapcat nights won))
                      (str "round " round ": overlapping stays both accepted " won))
                  (is (every? #(= :insufficient-capacity (:reason (outcomes %)))
                              (remove (set won) (range 4))))
                  (is (= (mapv (fn [n] (if (some #(some #{n} (nights %)) won) 0 1))
                               (range base (+ base 6)))
                         avail)
                      (str "round " round ": availability disagrees with accepted stays")))))
            ;; Everyone wants the same stay: exactly one wins.
            (doseq [n (range 100 103)] (p/init-night! a (str "n" n) "p" "r" n 1 10))
            (harness/wait-for-processing! a)
            (race! clients (fn [i c] (p/reserve! c (str "same-" i) "p" "r" (str "g" i) 100 103 1)))
            (harness/wait-for-processing! a)
            (let [won (filterv #(= :accepted (:status (p/get-outcome a "p" (str "same-" %))))
                               (range 4))]
              (is (= 1 (count won)) (str "exactly one of four identical stays: " won))
              (is (= [0 0 0] (mapv :available (p/get-availability a "p" "r" 100 103))))
              ;; Concurrent cancels of the winner by every client restore once.
              (race! clients (fn [i c] (p/cancel-booking! c (str "cancel-" i) "p"
                                                          (str "same-" (first won))
                                                          (str "g" (first won)))))
              (harness/wait-for-processing! a)
              (is (= [1 1 1] (mapv :available (p/get-availability a "p" "r" 100 103))))
              (is (= 1 (count (filter #(= :accepted (:status (p/get-outcome a "p" (str "cancel-" %))))
                                      (range 4))))))))))))
