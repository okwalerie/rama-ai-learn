(ns hld-feature-flag-service.private-harness-test
  (:require [clojure.test :refer [deftest]]
            [hld-feature-flag-service.private-test-support :as support]
            [hld-feature-flag-service.module :as reference]))

(deftest reference-two-tasks
  (support/test-module reference/create-module 2))

(deftest reference-four-tasks
  (support/test-module reference/create-module 4))
