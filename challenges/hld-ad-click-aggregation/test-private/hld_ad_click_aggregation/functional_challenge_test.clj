(ns hld-ad-click-aggregation.functional-challenge-test
  "Runs the functional private suite against the implementation under test,
   deploying explicitly with 2 tasks and with 4 tasks."
  (:require
   [clojure.test :refer [deftest testing]]
   [hld-ad-click-aggregation.functional-test-support :as support]))

(deftest functional-2-tasks-challenge-test
  (testing "hld-ad-click-aggregation functional suite, 2 tasks"
    (support/test-module-functional
     (requiring-resolve 'hld-ad-click-aggregation.module/create-module) 2)))

(deftest functional-4-tasks-challenge-test
  (testing "hld-ad-click-aggregation functional suite, 4 tasks"
    (support/test-module-functional
     (requiring-resolve 'hld-ad-click-aggregation.module/create-module) 4)))
