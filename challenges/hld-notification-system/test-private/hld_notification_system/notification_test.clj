(ns hld-notification-system.notification-test
  (:require [clojure.test :refer [deftest is]]
            [com.rpl.rama.test :as rtest]
            [hld-notification-system.protocol :as p]
            [rama-challenges.harness :as h]))

(defn module-factory []
  ((requiring-resolve 'hld-notification-system.module/create-module)))

(defn exercise [tasks]
  (let [{:keys [module wrap-client]} (module-factory)]
    (with-open [ipc (rtest/create-ipc)]
      (rtest/launch-module! ipc module {:tasks tasks :threads tasks})
      (let [a (wrap-client ipc) b (wrap-client ipc)
            barrier #(h/wait-for-processing! b)]
        (p/register-device! a "alice" "phone" "Token1")
        (p/register-device! a "bob" "phone" "Token2")
        (barrier)
        (is (= {:token "Token1" :generation 1 :valid? true}
               (get (p/get-devices b "alice") "phone")))
        (p/submit! a "global" "alice" "news" "first" 100 5)
        (barrier)
        (p/submit! b "global" "bob" "news" "loser" 200 9)
        (barrier)
        (is (= "alice" (:user-id (p/get-submission b "global"))))
        (is (= ["global"] (p/get-recent-submissions a "alice")))
        (is (= [] (p/get-recent-submissions a "bob")))
        (is (= {:token "Token1" :generation 1 :state :pending :attempts 0
                :next-attempt-at 5}
               (get-in (p/get-submission a "global") [:deliveries "phone"])))
        (p/report-attempt! a "global" "phone" 1 :transient-failure 5)
        (barrier)
        (is (= 15 (get-in (p/get-submission b "global") [:deliveries "phone" :next-attempt-at])))
        (p/report-attempt! b "global" "phone" 1 :permanent-failure 105)
        (p/report-attempt! b "global" "phone" 2 :transient-failure 14)
        (barrier)
        (is (= {:token "Token1" :generation 1 :state :pending :attempts 1
                :next-attempt-at 15}
               (get-in (p/get-submission a "global") [:deliveries "phone"])))
        (p/report-attempt! a "global" "phone" 2 :transient-failure 15)
        (barrier)
        (is (= 35 (get-in (p/get-submission b "global") [:deliveries "phone" :next-attempt-at])))
        (p/report-attempt! a "global" "phone" 3 :transient-failure 35)
        (barrier)
        (is (= {:submission-id "global" :device-id "phone" :reason :retries-exhausted :at 35}
               (first (p/get-dead-letters b "alice"))))
        (is (= :failed (get-in (p/get-submission b "global") [:deliveries "phone" :state])))))))

(deftest two-tasks (exercise 2))
(deftest four-tasks (exercise 4))

(defn exercise-lifecycle [tasks]
  (let [{:keys [module wrap-client]} (module-factory)]
    (with-open [ipc (rtest/create-ipc)]
      (rtest/launch-module! ipc module {:tasks tasks :threads tasks})
      (let [a (wrap-client ipc) b (wrap-client ipc)
            barrier #(h/wait-for-processing! b)]
        (is (= {} (p/get-devices a "nobody")))
        (is (= {} (p/get-preferences a "nobody")))
        (is (nil? (p/get-submission a "missing")))
        (is (= [] (p/get-recent-submissions a "nobody")))
        (is (= [] (p/get-dead-letters a "nobody")))
        (p/submit! a "empty" "nobody" "news" "" 1 0)
        (barrier)
        (is (= {:submission-id "empty" :user-id "nobody" :category "news"
                :payload "" :submitted-at 0 :expires-at 1
                :status :no-devices :deliveries {}}
               (p/get-submission b "empty")))
        (doseq [n (range 8)] (p/register-device! a "hot" (str "d" n) (str "Token" n)))
        (p/register-device! a "hot" "ninth" "Overflow")
        (p/register-device! a "hot" "d0" "Refresh")
        (p/set-preference! a "hot" "disabled" false)
        (p/submit! a "suppressed" "hot" "disabled" "" 1 20)
        (p/submit! a "before" "hot" "news" "first" 40 20)
        (barrier)
        (is (= 8 (count (p/get-devices b "hot"))))
        (is (= {:token "Refresh" :generation 2 :valid? true}
               (get (p/get-devices b "hot") "d0")))
        (is (nil? (get (p/get-devices b "hot") "ninth")))
        (is (= {"disabled" false} (p/get-preferences b "hot")))
        (is (= :suppressed (:status (p/get-submission b "suppressed"))))
        (is (= {} (:deliveries (p/get-submission b "suppressed"))))
        (is (= 8 (count (:deliveries (p/get-submission b "before")))))
        (is (= 2 (get-in (p/get-submission b "before") [:deliveries "d0" :generation])))
        (is (= ["before" "suppressed"] (p/get-recent-submissions b "hot")))
        (p/record-receipt! a "before" "d0" :read)
        (p/report-attempt! a "before" "d0" 1 :accepted 20)
        (barrier)
        (is (= :accepted (get-in (p/get-submission b "before") [:deliveries "d0" :state])))
        (p/record-receipt! b "before" "d0" :read)
        (p/record-receipt! b "before" "d0" :delivered)
        (p/report-attempt! b "before" "d0" 2 :permanent-failure 30)
        (p/report-attempt! b "before" "d1" 1 :accepted 60)
        (barrier)
        (is (= {:token "Refresh" :generation 2 :state :read :attempts 1
                :next-attempt-at nil}
               (get-in (p/get-submission a "before") [:deliveries "d0"])))
        (is (= {:token "Token1" :generation 1 :state :expired :attempts 0
                :next-attempt-at nil}
               (get-in (p/get-submission a "before") [:deliveries "d1"])))
        (p/report-attempt! a "before" "d2" 1 :invalid-token 20)
        (p/register-device! a "hot" "d3" "NewGeneration")
        (barrier)
        (p/report-attempt! b "before" "d3" 1 :invalid-token 21)
        (p/report-attempt! b "before" "d4" 1 :permanent-failure 22)
        (p/report-attempt! b "before" "d4" 1 :permanent-failure 23)
        (barrier)
        (is (false? (get-in (p/get-devices a "hot") ["d2" :valid?])))
        (is (true? (get-in (p/get-devices a "hot") ["d3" :valid?])))
        (is (= 2 (get-in (p/get-devices a "hot") ["d3" :generation])))
        (is (= [{:submission-id "before" :device-id "d4"
                 :reason :permanent-failure :at 22}]
               (p/get-dead-letters a "hot")))
        (p/set-preference! b "hot" "disabled" true)
        (p/submit! b "after" "hot" "disabled" "last" 1 99)
        (barrier)
        (is (= {"disabled" true} (p/get-preferences a "hot")))
        (is (= :dispatched (:status (p/get-submission a "after"))))
        (is (= 7 (count (:deliveries (p/get-submission a "after")))))
        (is (= "NewGeneration" (get-in (p/get-submission a "after") [:deliveries "d3" :token])))
        (p/report-attempt! a "after" "d0" 1 :accepted 100)
        (barrier)
        (is (= :expired (get-in (p/get-submission b "after") [:deliveries "d0" :state])))))))

(deftest lifecycle-two-tasks (exercise-lifecycle 2))
(deftest lifecycle-four-tasks (exercise-lifecycle 4))

(defn exercise-collisions-and-pages [tasks]
  (let [{:keys [module wrap-client]} (module-factory)]
    (with-open [ipc (rtest/create-ipc)]
      (rtest/launch-module! ipc module {:tasks tasks :threads tasks})
      (let [a (wrap-client ipc) b (wrap-client ipc)
            barrier #(h/wait-for-processing! a)]
        (p/register-device! a "u" "device" "Utoken")
        (p/register-device! b "v" "device" "Vtoken")
        (barrier)
        ;; Opposite owner orders expose per-ID arbitration that cannot be
        ;; explained by a single serial history if winners cross.
        (p/submit! a "crossx" "u" "news" "ux" 100 80)
        (p/submit! a "crossy" "u" "news" "uy" 100 10)
        (p/submit! b "crossy" "v" "news" "vy" 100 50)
        (p/submit! b "crossx" "v" "news" "vx" 100 20)
        (barrier)
        (let [x (p/get-submission a "crossx")
              y (p/get-submission a "crossy")
              owners [(:user-id x) (:user-id y)]]
          (is (contains? #{["u" "u"] ["v" "v"] ["u" "v"]} owners))
          (is (= (cond-> [] (= "u" (:user-id y)) (conj "crossy")
                         (= "u" (:user-id x)) (conj "crossx"))
                 (p/get-recent-submissions b "u")))
          (is (= (cond-> [] (= "v" (:user-id x)) (conj "crossx")
                         (= "v" (:user-id y)) (conj "crossy"))
                 (p/get-recent-submissions b "v")))
          (is (= (if (= "u" (:user-id x)) "ux" "vx") (:payload x)))
          (is (= (if (= "u" (:user-id y)) "uy" "vy") (:payload y))))
        ;; One owner's first contender must win even without a barrier.
        (p/submit! a "repeat" "u" "news" "earlier" 100 90)
        (p/submit! a "repeat" "u" "news" "later" 100 1)
        (barrier)
        (is (= "earlier" (:payload (p/get-submission b "repeat"))))
        (doseq [n (range 102)]
          (p/submit! a (str "page" n) "u" "news" "" 100 (- 200 n)))
        (barrier)
        (is (= (mapv #(str "page" %) (range 101 1 -1))
               (p/get-recent-submissions b "u")))))))

(deftest collisions-and-pages-two-tasks (exercise-collisions-and-pages 2))
(deftest collisions-and-pages-four-tasks (exercise-collisions-and-pages 4))

(defn exercise-dead-page [tasks]
  (let [{:keys [module wrap-client]} (module-factory)]
    (with-open [ipc (rtest/create-ipc)]
      (rtest/launch-module! ipc module {:tasks tasks :threads tasks})
      (let [a (wrap-client ipc) b (wrap-client ipc)]
        (p/register-device! a "deaduser" "device" "Token")
        (h/wait-for-processing! b)
        (doseq [n (range 101)]
          (p/submit! a (str "dead" n) "deaduser" "news" "" 200 0))
        (h/wait-for-processing! b)
        (doseq [n (range 101)]
          (p/report-attempt! b (str "dead" n) "device" 1 :permanent-failure n)
          (h/wait-for-processing! a))
        (is (= (mapv (fn [n] {:submission-id (str "dead" n) :device-id "device"
                            :reason :permanent-failure :at n}) (range 100 0 -1))
               (p/get-dead-letters b "deaduser")))))))

(deftest dead-page-two-tasks (exercise-dead-page 2))
(deftest dead-page-four-tasks (exercise-dead-page 4))
