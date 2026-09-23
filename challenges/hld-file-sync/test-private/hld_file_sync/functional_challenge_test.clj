(ns hld-file-sync.functional-challenge-test
  "Runs the private functional tests against hld-file-sync.module/create-module
   (the agent implementation under :test-private, the reference under
   :test-private-harness)."
  (:require
   [clojure.test :refer [deftest testing]]
   [hld-file-sync.functional-test-support :as support]))

(deftest functional-challenge-test
  (testing "FileSyncModule (functional private tests, 2 and 4 tasks)"
    (support/test-module-functional
     (requiring-resolve 'hld-file-sync.module/create-module))))
