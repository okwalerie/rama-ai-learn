(ns hld-hotel-reservation.reference-test
  "Reference-specific failure injection; not imposed on solver append APIs."
  (:require [clojure.test :refer :all]
            [com.rpl.rama :as rama]
            [com.rpl.rama.test :as rtest]
            [hld-hotel-reservation.module :as module]
            [hld-hotel-reservation.protocol :as p]
            [rama-challenges.harness :as harness]))

(deftest failed-append-does-not-poison-barrier
  (doseq [tasks [2 4]]
    (testing (str tasks " tasks")
      (let [{:keys [module wrap-client]} (module/create-module)]
        (with-open [ipc (rtest/create-ipc)]
          (rtest/launch-module! ipc module {:tasks tasks :threads tasks})
          (let [client (wrap-client ipc)]
            (with-redefs [rama/foreign-append! (fn [& _]
                                                 (throw (ex-info "failed before acceptance" {})))]
              (is (thrown? clojure.lang.ExceptionInfo
                           (p/create-property! client "id" "p"))))
            (is (nil? (p/create-property! client "id" "p")))
            (harness/wait-for-processing! client)
            (is (= :accepted (:status (p/get-outcome client "p" "id"))))))))))

(deftest paused-queue-preserves-one-client-order
  ;; Reference-specific control: the public suite must not require a solver's
  ;; topology name. Cross the reference's 1000-record microbatch boundary.
  (doseq [tasks [2 4]]
    (testing (str tasks " tasks")
      (let [{:keys [module wrap-client]} (module/create-module)]
        (with-open [ipc (rtest/create-ipc)]
          (rtest/launch-module! ipc module {:tasks tasks :threads tasks})
          (let [client (wrap-client ipc)
                name (rama/get-module-name module)]
            (rtest/pause-microbatch-topology! ipc name "core")
            (try
              (p/create-property! client "create" "p")
              (p/create-room-type! client "type" "p" "r")
              (p/init-night! client "night" "p" "r" 0 1 7)
              (dotimes [_ 997]
                (p/create-property! client "create" "p"))
              (p/reserve! client "first" "p" "r" "g" 0 1 1)
              (p/cancel-booking! client "cancel" "p" "first" "g")
              (p/set-rate! client "rate" "p" "r" 0 11)
              (p/reserve! client "second" "p" "r" "g" 0 1 1)
              (finally (rtest/resume-microbatch-topology! ipc name "core")))
            (harness/wait-for-processing! client)
            (is (= :accepted (:status (p/get-outcome client "p" "create"))))
            (is (= 0 (:conflicting-attempts (p/get-outcome client "p" "create"))))
            (is (= :cancelled (:state (p/get-booking client "p" "first"))))
            (is (= 7 (:total (p/get-booking client "p" "first"))))
            (is (= 11 (:total (p/get-booking client "p" "second"))))
            (is (= 0 (:available (p/get-night client "p" "r" 0))))
            (is (= [[1 :reserved "first"] [2 :cancelled "cancel"]
                    [3 :reserved "second"]]
                   (mapv (juxt :seq :type :request-id)
                         (p/get-booking-events client "p" 0 5))))))))))
