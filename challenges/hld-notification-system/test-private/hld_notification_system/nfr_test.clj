(ns hld-notification-system.nfr-test
  "NFR test: submissions, delivery transitions, dead letters and device
   invalidation apply exactly once under a forced stream retry."
  (:require [clojure.test :refer [deftest is testing]]
            [com.rpl.rama.test :as rtest]
            [hld-notification-system.protocol :as p]
            [rama-challenges.harness :as harness]
            [rama-challenges.nfr :as nfr]))

(defn- retried!
  "Runs write f and waits, failing the first stream completion so a stream
   design retries it. A microbatch design sees no forced failure."
  [c f]
  (nfr/failed-streaming #(do (f) (harness/wait-for-processing! c))))

(deftest forced-stream-retry-dead-letters-once
  (doseq [tasks [2 4]]
    (testing (str tasks " tasks")
      (let [{:keys [module wrap-client]}
            ((requiring-resolve 'hld-notification-system.module/create-module))]
        (with-open [ipc (rtest/create-ipc)]
          (rtest/launch-module! ipc module {:tasks tasks :threads tasks})
          (let [c (wrap-client ipc)
                r (wrap-client ipc)]
            (retried! r #(p/register-device! c "u" "d1" "tok1"))
            (retried! r #(p/register-device! c "u" "d2" "tok2"))
            (retried! r #(p/submit! c "s1" "u" "news" "hello" 100 0))
            (retried! r #(p/submit! c "s1" "u" "news" "changed" 100 0))
            (is (= [:dispatched "hello" #{"d1" "d2"}]
                   ((juxt :status :payload (comp set keys :deliveries)) (p/get-submission r "s1"))))
            (retried! r #(p/report-attempt! c "s1" "d1" 1 :transient-failure 0))
            (retried! r #(p/report-attempt! c "s1" "d1" 2 :permanent-failure 10))
            (retried! r #(p/report-attempt! c "s1" "d2" 1 :invalid-token 0))
            (retried! r #(p/report-attempt! c "s1" "d2" 1 :invalid-token 0))
            (is (= {:token "tok1" :generation 1 :state :failed :attempts 2 :next-attempt-at nil}
                   (get-in (p/get-submission r "s1") [:deliveries "d1"]))
                "a retried transient report must not count a second attempt")
            (is (= [{:submission-id "s1" :device-id "d1" :reason :permanent-failure :at 10}]
                   (p/get-dead-letters r "u"))
                "exactly one dead letter per failed delivery")
            (is (= {"d1" {:token "tok1" :generation 1 :valid? true}
                    "d2" {:token "tok2" :generation 1 :valid? false}}
                   (p/get-devices r "u"))
                "a retried invalid-token report still invalidates the device")
            (retried! r #(p/submit! c "s2" "u" "news" "again" 100 20))
            (retried! r #(p/report-attempt! c "s2" "d1" 1 :accepted 20))
            (retried! r #(p/record-receipt! c "s2" "d1" :read))
            (is (= #{"d1"} (set (keys (:deliveries (p/get-submission r "s2"))))))
            (is (= :read (get-in (p/get-submission r "s2") [:deliveries "d1" :state])))
            (is (= ["s2" "s1"] (p/get-recent-submissions r "u"))
                "each accepted submission appears once in the recent list")))))))
