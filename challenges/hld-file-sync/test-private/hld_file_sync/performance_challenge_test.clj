(ns hld-file-sync.performance-challenge-test
  "Runs the private storage-work tests against hld-file-sync.module/create-module."
  (:require
   [clojure.test :refer [deftest testing]]
   [hld-file-sync.performance-test-support :as support]))

(deftest performance-challenge-test
  (testing "FileSyncModule (storage-work private tests)"
    (support/test-module-performance
     (requiring-resolve 'hld-file-sync.module/create-module))))
