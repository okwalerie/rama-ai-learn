(ns hld-hotel-reservation.contract-test
  (:require [clojure.test :refer :all]
            [com.rpl.rama.test :as rtest]
            [hld-hotel-reservation.protocol :as p]
            [rama-challenges.harness :as harness]))

(defn implementation []
  (requiring-resolve 'hld-hotel-reservation.module/create-module))

(defn exercise [tasks f]
  (let [{:keys [module wrap-client]} ((implementation))]
    (with-open [ipc (rtest/create-ipc)]
      (rtest/launch-module! ipc module {:tasks tasks :threads tasks})
      (f ipc wrap-client (wrap-client ipc)))))

(defn barrier [client]
  (harness/wait-for-processing! client))

(deftest half-open-atomic-and-journal
  (doseq [tasks [2 4]]
    (testing (str tasks " tasks")
      (exercise tasks
        (fn [ipc wrap a]
          (let [b (wrap ipc)]
            (p/create-property! a "p" "hotel")
            (p/create-room-type! a "r" "hotel" "king")
            (doseq [[n rate] [[10 50] [11 60] [12 70] [13 80]]]
              (p/init-night! a (str "n" n) "hotel" "king" n 2 rate))
            (barrier a)
            (is (= [50 60 70 80] (mapv :rate (p/get-availability b "hotel" "king" 10 14))))
            (p/reserve! a "first" "hotel" "king" "alice" 10 13 2)
            (p/reserve! a "missing" "hotel" "king" "bob" 11 15 1)
            (p/reserve! a "full" "hotel" "king" "bob" 11 14 1)
            (p/reserve! a "edge" "hotel" "king" "bob" 13 14 2)
            (barrier b)
            (is (= {:status :accepted :command :reserve :booking-id "first" :total 360
                    :nights 3 :seq 1 :conflicting-attempts 0}
                   (p/get-outcome b "hotel" "first")))
            (is (= [14] (:nights (p/get-outcome b "hotel" "missing"))))
            (is (= :night-not-configured (:reason (p/get-outcome b "hotel" "missing"))))
            (is (= [11 12] (:nights (p/get-outcome b "hotel" "full"))))
            (is (= [0 0 0 0] (mapv :available (p/get-availability b "hotel" "king" 10 14))))
            (p/set-rate! a "rate" "hotel" "king" 11 200)
            (p/cancel-booking! a "wrong" "hotel" "first" "bob")
            (p/cancel-booking! a "cancel" "hotel" "first" "alice")
            (p/cancel-booking! a "again" "hotel" "first" "alice")
            (p/reserve! a "later" "hotel" "king" "bob" 10 13 1)
            (barrier a)
            (is (= [1 1 1 0] (mapv :available (p/get-availability b "hotel" "king" 10 14))))
            (is (= :not-guest (:reason (p/get-outcome b "hotel" "wrong"))))
            (is (= :booking-cancelled (:reason (p/get-outcome b "hotel" "again"))))
            (is (= 3 (:seq (p/get-outcome b "hotel" "cancel"))))
            (is (= {:booking-id "first" :guest-id "alice" :room-type "king"
                    :checkin 10 :checkout 13 :quantity 2 :total 360 :state :cancelled :seq 1}
                   (p/get-booking b "hotel" "first")))
            (is (= 320 (:total (p/get-booking b "hotel" "later"))))
            (is (= [[1 :reserved "first"] [2 :reserved "edge"]
                    [3 :cancelled "cancel"] [4 :reserved "later"]]
                   (mapv (juxt :seq :type :request-id) (p/get-booking-events b "hotel" 0 10))))
            (is (= [2 3] (mapv :seq (p/get-booking-events b "hotel" 1 2))))
            (is (= [] (p/get-booking-events b "hotel" Long/MAX_VALUE 1)))))))))

(deftest precreation-retry-validation-and-second-wrapper
  (doseq [tasks [2 4]]
    (testing (str tasks " tasks")
      (exercise tasks
        (fn [ipc wrap a]
          (let [b (wrap ipc)]
            (p/reserve! a "early" "p" "rt" "g" 1 3 1)
            (p/create-property! a "create" "p")
            (p/init-night! a "early-night" "p" "rt" 1 1 7)
            (p/create-room-type! a "type" "p" "rt")
            (p/init-night! a "n1" "p" "rt" 1 1 7)
            (p/init-night! a "n2" "p" "rt" 2 1 9)
            (barrier b)
            (is (= {:status :rejected :command :reserve :reason :no-such-property
                    :conflicting-attempts 0}
                   (p/get-outcome b "p" "early")))
            (is (= :no-such-room-type (:reason (p/get-outcome b "p" "early-night"))))
            (p/reserve! b "early" "p" "rt" "g" 1 3 1)
            (p/reserve! b "early" "p" "rt" "g" 1 3 2)
            (is (thrown? IllegalArgumentException
                         (p/reserve! b "early" "p" "rt" "g" 1 3 0)))
            (is (thrown? IllegalArgumentException
                         (p/reserve! b "invalid" "p" "rt" "g" 1 32 1)))
            (p/reserve! a "good" "p" "rt" "g" 1 3 1)
            (barrier a)
            (is (= 1 (:conflicting-attempts (p/get-outcome a "p" "early"))))
            (is (= :no-such-property (:reason (p/get-outcome a "p" "early"))))
            (is (nil? (p/get-outcome a "p" "invalid")))
            (is (= 16 (:total (p/get-booking b "p" "good"))))
            (is (= [0 0] (mapv :available (p/get-availability a "p" "rt" 1 3))))
            (is (nil? (p/get-booking b "p" "early")))
            (is (nil? (p/get-availability a "p" "absent" 1 3)))
            (is (thrown? IllegalArgumentException
                         (p/get-booking-events b "p" 0 501)))))))))

(deftest long-stay-retries-and-update
  (doseq [tasks [2 4]]
    (testing (str tasks " tasks")
      (let [{:keys [module wrap-client]} ((implementation))]
        (with-open [ipc (rtest/create-ipc)]
          (rtest/launch-module! ipc module {:tasks tasks :threads tasks})
          (let [a (wrap-client ipc)]
            (p/create-property! a "cp" "p")
            (p/create-room-type! a "cr" "p" "r")
            (doseq [n (range 30)]
              (p/init-night! a (str "i" n) "p" "r" n 100 1000000000))
            (p/reserve! a "large" "p" "r" "guest" 0 30 100)
            (p/reserve! a "reject" "p" "r" "other" 0 30 1)
            (barrier a)
            (is (= 3000000000000 (:total (p/get-booking a "p" "large"))))
            (is (= (vec (range 30)) (:nights (p/get-outcome a "p" "reject"))))
            (rtest/update-module! ipc module)
            (let [b (wrap-client ipc)]
              (is (= 0 (:available (p/get-night b "p" "r" 29))))
              (let [independent ((:wrap-client ((implementation))) ipc)]
                (is (= 3000000000000 (:total (p/get-booking independent "p" "large")))))
              (p/reserve! b "large" "p" "r" "guest" 0 30 100)
              (p/reserve! b "large" "p" "r" "other" 0 30 1)
              (p/cancel-booking! b "cancel" "p" "large" "guest")
              (p/reserve! b "reject" "p" "r" "other" 0 30 1)
              (p/reserve! b "after" "p" "r" "other" 29 30 1)
              (barrier b)
              (is (= 1 (:conflicting-attempts (p/get-outcome b "p" "large"))))
              (is (= :insufficient-capacity (:reason (p/get-outcome b "p" "reject"))))
              (is (= 99 (:available (p/get-night b "p" "r" 29))))
              (is (= :cancelled (:state (p/get-booking b "p" "large"))))
              (is (= 1 (:seq (p/get-booking b "p" "large"))))
              (is (= [1 2 3] (mapv :seq (p/get-booking-events b "p" 0 5)))))))))))

(deftest late-night-rejection-and-property-isolation
  (doseq [tasks [2 4]]
    (testing (str tasks " tasks")
      (exercise tasks
        (fn [_ _ a]
          (doseq [property ["left" "right"]]
            (is (nil? (p/create-property! a "same" property)))
            (p/create-room-type! a "rt" property "standard"))
          (p/init-night! a "l1" "left" "standard" 10 2 10)
          (p/init-night! a "l2" "left" "standard" 11 0 20)
          (p/init-night! a "l3" "left" "standard" 12 2 30)
          (p/init-night! a "r1" "right" "standard" 10 1 7)
          (p/reserve! a "no" "left" "standard" "g" 10 13 1)
          (p/reserve! a "same-booking" "right" "standard" "g" 10 11 1)
          (barrier a)
          (is (= [11] (:nights (p/get-outcome a "left" "no"))))
          (is (= 2 (:available (p/get-night a "left" "standard" 10))))
          (is (= 2 (:available (p/get-night a "left" "standard" 12))))
          (is (= 0 (:available (p/get-night a "right" "standard" 10))))
          (is (= [1] (mapv :seq (p/get-booking-events a "right" 0 1))))
          (is (= [] (p/get-booking-events a "left" 0 1))))))))
