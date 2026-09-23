(ns hld-rate-limiter.performance-challenge-test
  "Runs bounded-work private tests against the implementation at 2 and 4 tasks."
  (:require
   [clojure.test :refer [deftest testing]]
   [hld-rate-limiter.performance-test-support :as support]))

(deftest performance-challenge-test-2-tasks
  (testing "RateLimiter bounded-work tests, 2 tasks"
    (support/test-module-performance
     (requiring-resolve 'hld-rate-limiter.module/create-module) 2)))

(deftest performance-challenge-test-4-tasks
  (testing "RateLimiter bounded-work tests, 4 tasks"
    (support/test-module-performance
     (requiring-resolve 'hld-rate-limiter.module/create-module) 4)))
