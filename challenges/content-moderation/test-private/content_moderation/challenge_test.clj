(ns content-moderation.challenge-test
  (:require [clojure.test :refer [deftest is testing]]
            [com.rpl.rama.test :as rtest]
            [content-moderation.module :as module]
            [content-moderation.protocol :as p]
            [content-moderation.test-support :as support]
            [rama-challenges.harness :as harness]))

(deftest protocol-acceptance
  (doseq [tasks [2 4]]
    (support/check-contract module/create-module tasks)))

(deftest retry-does-not-duplicate-posts
  (let [{:keys [module wrap-client]} (module/create-module)]
    (with-open [ipc (rtest/create-ipc)]
      (rtest/launch-module! ipc module {:tasks 2 :threads 2})
      (let [client (wrap-client ipc)
            failed? (atom false)
            post (p/->Post 1 10 "retry")]
        (testing "a post remains singular after a forced streaming-completion failure"
          (rtest/with-event-hook
            (fn [event-type _data]
              (when (and (= event-type :streaming-complete)
                         (compare-and-set! failed? false true))
                :fail))
            (p/post! client post))
          (harness/wait-for-processing! client)
          (is (= {:posts [post] :next-offset nil}
                 (p/get-posts client 10 0 10))))))))
