(ns hld-metrics-pipeline.functional-challenge-test
  "Runs the functional private suite against the implementation under test,
   deploying explicitly with 2 tasks and with 4 tasks."
  (:require
   [clojure.test :refer [deftest testing]]
   [hld-metrics-pipeline.functional-test-support :as support]))

(deftest functional-2-tasks-challenge-test
  (testing "hld-metrics-pipeline functional suite, 2 tasks"
    (support/test-module-functional
     (requiring-resolve 'hld-metrics-pipeline.module/create-module) 2)))

(deftest functional-4-tasks-challenge-test
  (testing "hld-metrics-pipeline functional suite, 4 tasks"
    (support/test-module-functional
     (requiring-resolve 'hld-metrics-pipeline.module/create-module) 4)))
