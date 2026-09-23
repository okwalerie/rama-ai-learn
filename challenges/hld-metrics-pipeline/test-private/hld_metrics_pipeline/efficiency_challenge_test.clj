(ns hld-metrics-pipeline.efficiency-challenge-test
  "Runs the efficiency private suite against the implementation under test,
   deploying explicitly with 2 tasks and with 4 tasks."
  (:require
   [clojure.test :refer [deftest testing]]
   [hld-metrics-pipeline.efficiency-test-support :as support]))

(deftest efficiency-2-tasks-challenge-test
  (testing "hld-metrics-pipeline efficiency contract, 2 tasks"
    (support/test-module-efficiency
     (requiring-resolve 'hld-metrics-pipeline.module/create-module) 2)))

(deftest efficiency-4-tasks-challenge-test
  (testing "hld-metrics-pipeline efficiency contract, 4 tasks"
    (support/test-module-efficiency
     (requiring-resolve 'hld-metrics-pipeline.module/create-module) 4)))
