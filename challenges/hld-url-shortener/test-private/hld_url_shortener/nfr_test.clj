(ns hld-url-shortener.nfr-test
  "NFR test: creates, lifecycle flags and click counting apply exactly once
   under a forced stream retry."
  (:require [clojure.test :refer [deftest is testing]]
            [com.rpl.rama.test :as rtest]
            [hld-url-shortener.protocol :as p]
            [rama-challenges.harness :as harness]
            [rama-challenges.nfr :as nfr]))

(defn- retried!
  "Runs write f and waits, failing the first stream completion so a stream
   design retries it. A microbatch design sees no forced failure."
  [c f]
  (nfr/failed-streaming #(do (f) (harness/wait-for-processing! c))))

(deftest forced-stream-retry-counts-each-click-once
  (doseq [tasks [2 4]]
    (testing (str tasks " tasks")
      (let [{:keys [module wrap-client]}
            ((requiring-resolve 'hld-url-shortener.module/create-module))]
        (with-open [ipc (rtest/create-ipc)]
          (rtest/launch-module! ipc module {:tasks tasks :threads tasks})
          (let [c (wrap-client ipc)
                r (wrap-client ipc)
                target "https://example.com/a"]
            (retried! r #(p/create-link! c "a" "q1" target 100))
            (retried! r #(p/create-link! c "a" "q2" "https://example.com/b" nil))
            (is (= {:outcome :created} (p/get-create-outcome r "a" "q1")))
            (is (= {:outcome :rejected :reason :alias-taken} (p/get-create-outcome r "a" "q2")))
            (retried! r #(p/record-click! c "a" "k1"))
            (is (= 1 (p/get-click-count r "a")) "a retried click must count once")
            (retried! r #(p/record-click! c "a" "k1"))
            (is (= 1 (p/get-click-count r "a")) "a retried duplicate must not count")
            (retried! r #(p/block-link! c "a"))
            (retried! r #(p/record-click! c "a" "k2"))
            (is (= {:status :blocked :target-url target :expires-at 100} (p/resolve-alias r "a" 5)))
            (retried! r #(p/unblock-link! c "a"))
            (is (= {:status :active :target-url target :expires-at 100} (p/resolve-alias r "a" 5)))
            (retried! r #(p/delete-link! c "a"))
            (retried! r #(p/record-click! c "a" "k3"))
            (retried! r #(p/record-click! c "missing" "k1"))
            (retried! r #(p/create-link! c "missing" "q1" target nil))
            (retried! r #(p/record-click! c "missing" "k1"))
            (is (= {:status :deleted :target-url target :expires-at 100} (p/resolve-alias r "a" 5)))
            (is (= 3 (p/get-click-count r "a")))
            (is (= 1 (p/get-click-count r "missing"))
                "a click dropped before creation leaves no trace, even when retried")))))))
