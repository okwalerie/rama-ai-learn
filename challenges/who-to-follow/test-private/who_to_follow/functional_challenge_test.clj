(ns who-to-follow.functional-challenge-test
  (:require [clojure.test :refer [deftest is testing]]
            [com.rpl.rama.test :as rtest]
            [rama-challenges.harness :as harness]
            [rama-challenges.shared :as shared]
            [who-to-follow.protocol :as p]
            ))

(defn- run-case [tasks]
  (with-redefs [shared/REPLACE-TICK-DEPOTS true]
    (let [{:keys [module wrap-client]} ((requiring-resolve 'who-to-follow.module/create-module))]
      (with-open [ipc (rtest/create-ipc)]
        (rtest/launch-module! ipc module {:tasks tasks :threads 2})
        (let [a (wrap-client ipc) b (wrap-client ipc)]
          (is (nil? (p/follow! a 1 2)))
          (p/follow! a 1 3) (p/follow! a 1 4) (p/follow! a 1 5)
          (p/follow! a 2 1) (p/follow! a 2 3) (p/follow! a 2 6) (p/follow! a 2 7)
          (p/follow! a 2 8) (p/follow! a 2 9) (p/follow! a 2 10)
          (p/follow! a 3 1) (p/follow! a 3 8) (p/follow! a 3 9) (p/follow! a 3 11) (p/follow! a 3 12)
          (p/follow! a 4 8) (p/follow! a 4 3) (p/follow! a 4 10) (p/follow! a 4 13) (p/follow! a 4 14)
          (p/follow! a 5 1) (p/follow! a 5 3) (p/follow! a 5 10)
          (p/follow! a 6 12) (p/follow! a 7 12) (p/follow! a 8 12) (p/follow! a 9 12)
          (p/follow! a 1 2) ; repeated edge remains one set member
          (doseq [id (range 1000 1128)]
            (p/follow! a id 2)
            (p/follow! a id 3))
          ;; 12 exceeds ceil(142 account keys / 15 keys per task per tick),
          ;; even if the keys are maximally skewed onto one task.
          (dotimes [_ 12]
            (is (nil? (p/refresh! a)))
            (harness/wait-for-processing! a))
          (rtest/update-module! ipc module)
          (testing "same-module update preserves live state; this is not crash recovery"
            (is (= #{6 7 8 9 10 11 12 13 14}
                   (set (p/recommendations b 1)))))
          (testing "expected popularity and self/followed exclusion"
            ;; Independent oracle: merge out-neighbors of followed IDs {2,3,4,5},
            ;; remove self and already-followed IDs; tied candidates have no order.
            (is (= #{6 7 8 9 10 11 12 13 14} (set (p/recommendations b 1))))
            (is (not (contains? (set (p/recommendations b 1)) 3)))
            (is (not (contains? (set (p/recommendations b 1)) 1)))
            (is (= [] (p/recommendations b 999)))
            (is (= #{1 6 7 8 9 10 11 12} (set (p/recommendations b 1127)))))
          (testing "the scan cursor reaches accounts beyond its first 15-key page"
            (dotimes [_ 12]
              (p/refresh! a)
              (harness/wait-for-processing! a))
            (is (contains? (set (p/recommendations b 1127)) 1)))
          (testing "a new refresh updates results from new graph events"
            (p/follow! b 5 8)
            (dotimes [_ 12]
              (p/refresh! b)
              (harness/wait-for-processing! b))
            ;; Account 1 follows 5, so 5's new edge raises candidate 8's shared-follow count.
            (is (= 8 (first (p/recommendations a 1))))))))))

(deftest recommendations-at-two-and-four-tasks
  (doseq [tasks [2 4]] (run-case tasks)))
