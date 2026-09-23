(ns hld-job-scheduler.functional-test
  (:require [clojure.test :refer [deftest]]
            [hld-job-scheduler.functional-test-support :as support]
            [hld-job-scheduler.module :as impl]))

(deftest scheduler-contract
  (support/test-scheduler impl/create-module))

(deftest scheduler-growth-and-boundaries
  (support/test-growth-and-boundaries impl/create-module))
