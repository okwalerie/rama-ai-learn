(ns top-users-module.functional-challenge-test
  (:require [clojure.test :refer [deftest is testing]]
            [com.rpl.rama.test :as rtest]
            [rama-challenges.harness :as harness]
            [top-users-module.protocol :as p]))

(defn- run-case [tasks]
  (let [{:keys [module wrap-client]} ((requiring-resolve 'top-users-module.module/create-module))]
    (with-open [ipc (rtest/create-ipc)]
      (rtest/launch-module! ipc module {:tasks tasks :threads 2})
      (let [a (wrap-client ipc) b (wrap-client ipc)]
        (doseq [[id cents] [[10 300] [20 200] [30 100] [40 50]]]
          (p/purchase! a id cents))
        (harness/wait-for-processing! a)
        (rtest/update-module! ipc module)
        (testing "same-module update preserves live state; this is not crash recovery"
          (is (= [[10 300] [20 200] [30 100] [40 50]] (p/top-users b))))
        (testing "distinct spend totals establish rank; purchase records accumulate"
          (is (= [[10 300] [20 200] [30 100] [40 50]] (p/top-users b))))
        (p/purchase! b 40 200)
        (p/purchase! b 20 25)
        (harness/wait-for-processing! b)
        (is (= [[10 300] [40 250] [20 225] [30 100]] (p/top-users a)))
        (testing "repeat appends count again, not deduplicated by an invented ID"
          (p/purchase! a 10 1)
          (p/purchase! a 10 1)
          (harness/wait-for-processing! a)
          (is (= 302 (second (first (p/top-users b))))))))))

(defn- run-cap-case [tasks]
  (let [{:keys [module wrap-client]} ((requiring-resolve 'top-users-module.module/create-module))]
    (with-open [ipc (rtest/create-ipc)]
      (rtest/launch-module! ipc module {:tasks tasks :threads 2})
      (let [client (wrap-client ipc)]
        (doseq [id (range 501)] (p/purchase! client id (inc id)))
        (harness/wait-for-processing! client)
        (let [result (p/top-users client)]
          (is (= 500 (count result)))
          (is (= [500 501] (first result)))
          (is (= [1 2] (last result))))))))

(deftest cumulative-ranking-at-two-and-four-tasks
  (doseq [tasks [2 4]] (run-case tasks) (run-cap-case tasks)))
