(ns hld-feature-flag-service.private-challenge-test
  (:require [clojure.test :refer [deftest]]
            [hld-feature-flag-service.private-test-support :as support]))

(deftest feature-flags-two-tasks
  (support/test-module (requiring-resolve 'hld-feature-flag-service.module/create-module) 2))

(deftest feature-flags-four-tasks
  (support/test-module (requiring-resolve 'hld-feature-flag-service.module/create-module) 4))
