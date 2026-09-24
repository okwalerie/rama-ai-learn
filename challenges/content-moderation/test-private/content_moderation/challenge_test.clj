(ns content-moderation.challenge-test
  (:require [clojure.test :refer [deftest]]
            [content-moderation.module :as module]
            [content-moderation.test-support :as support]))

(deftest protocol-acceptance
  (doseq [tasks [2 4]]
    (support/check-contract module/create-module tasks)))
