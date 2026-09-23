(ns hld-url-shortener.functional-challenge-test
  "Runs functional private tests against the implementation at 2 and 4 tasks."
  (:require
   [clojure.test :refer [deftest testing]]
   [hld-url-shortener.functional-test-support :as support]))

(deftest functional-challenge-test-2-tasks
  (testing "UrlShortener functional tests, 2 tasks"
    (support/test-module-functional
     (requiring-resolve 'hld-url-shortener.module/create-module) 2)))

(deftest functional-challenge-test-4-tasks
  (testing "UrlShortener functional tests, 4 tasks"
    (support/test-module-functional
     (requiring-resolve 'hld-url-shortener.module/create-module) 4)))
