(ns timed-notifications.functional-challenge-test
  (:require [clojure.test :refer [deftest is testing]]
            [com.rpl.rama.test :as rtest]
            [rama-challenges.harness :as harness]
            [rama-challenges.shared :as shared]
            [timed-notifications.protocol :as p]
            )
  (:import [com.rpl.rama.helpers TopologyUtils]))

(defn- run-case [tasks]
  (with-redefs [shared/REPLACE-TICK-DEPOTS true]
    (let [{:keys [module wrap-client]} ((requiring-resolve 'timed-notifications.module/create-module))]
      (with-open [ipc (rtest/create-ipc)
                  sim-time (TopologyUtils/startSimTime)]
        (rtest/launch-module! ipc module {:tasks tasks :threads 2})
        (let [client (wrap-client ipc)]
          (is (nil? (p/schedule-post! client "alice" 1500 "late")))
          (p/schedule-post! client "alice" 800 "early")
          (p/schedule-post! client "alice" 2000 "later")
          (p/schedule-post! client "bob" 800 "bob early")
          (p/tick! client)
          (harness/wait-for-processing! client)
          (is (= [] (p/feed client "alice")))
          (TopologyUtils/advanceSimTime 799)
          (p/tick! client)
          (harness/wait-for-processing! client)
          (is (= [] (p/feed client "alice")))
          (TopologyUtils/advanceSimTime 1)
          (p/tick! client)
          (harness/wait-for-processing! client)
          (testing "due time is inclusive and accounts stay partitioned"
            (is (= ["early"] (p/feed client "alice")))
            (is (= ["bob early"] (p/feed client "bob"))))
          (rtest/update-module! ipc module)
          (testing "same-module update preserves delivered state; this is not crash recovery"
            (is (= ["early"] (p/feed client "alice"))))
          (TopologyUtils/advanceSimTime 699)
          (p/tick! client)
          (harness/wait-for-processing! client)
          (is (= ["early"] (p/feed client "alice")))
          (TopologyUtils/advanceSimTime 1)
          (p/tick! client)
          (harness/wait-for-processing! client)
          (is (= ["early" "late"] (p/feed client "alice")))
          (TopologyUtils/advanceSimTime 500)
          (p/tick! client)
          (harness/wait-for-processing! client)
          (is (= ["early" "late" "later"] (p/feed client "alice")))
          (testing "later ticks do not deliver an item twice"
            (p/tick! client)
            (harness/wait-for-processing! client)
            (is (= ["early" "late" "later"] (p/feed client "alice")))))))))

(deftest notifications-at-two-and-four-tasks
  (doseq [tasks [2 4]] (run-case tasks)))
