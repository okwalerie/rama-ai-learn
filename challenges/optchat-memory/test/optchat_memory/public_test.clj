(ns optchat-memory.public-test
  (:require [clojure.test :refer [deftest]]
            [optchat-memory.module :as module]
            [optchat-memory.test-support :as support]
            [rama-challenges.harness :refer [with-module]]))
(deftest examples
  (with-module [client module/create-module]
    (support/basic-examples client)))
