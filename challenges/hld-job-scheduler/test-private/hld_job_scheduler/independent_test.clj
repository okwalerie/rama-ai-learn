(ns hld-job-scheduler.independent-test
  (:require [clojure.test :refer [deftest]]
            [hld-job-scheduler.independent-test-support :as support]
            [hld-job-scheduler.module :as impl]))

(deftest independent-storage-work-and-boundaries
  (support/test-storage-work-and-boundaries impl/create-module))
