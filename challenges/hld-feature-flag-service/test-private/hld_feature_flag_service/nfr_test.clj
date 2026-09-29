(ns hld-feature-flag-service.nfr-test
  "NFR test: revisioned configuration writes apply exactly once under a
   forced stream retry."
  (:require [clojure.test :refer [deftest is testing]]
            [com.rpl.rama.test :as rtest]
            [hld-feature-flag-service.protocol :as p]
            [rama-challenges.harness :as harness]
            [rama-challenges.nfr :as nfr]))

(defn- retried!
  "Runs write f and waits, failing the first stream completion so a stream
   design retries it. A microbatch design sees no forced failure."
  [c f]
  (nfr/failed-streaming #(do (f) (harness/wait-for-processing! c))))

(defn- config [revision serve]
  {:revision revision :killed? false :off-value "off" :default-value "default"
   :rules [{:attribute "plan" :operator :eq :value "pro" :serve serve}]
   :rollout nil})

(deftest forced-stream-retry-keeps-highest-revision
  (doseq [tasks [2 4]]
    (testing (str tasks " tasks")
      (let [{:keys [module wrap-client]}
            ((requiring-resolve 'hld-feature-flag-service.module/create-module))]
        (with-open [ipc (rtest/create-ipc)]
          (rtest/launch-module! ipc module {:tasks tasks :threads tasks})
          (let [c (wrap-client ipc)
                r (wrap-client ipc)
                put! #(retried! r (fn [] (p/put-flag-config! c "t" "prod" "f" %)))]
            (put! (config 1 "one"))
            (is (= (config 1 "one") (p/get-flag-config r "t" "prod" "f")))
            (put! (config 3 "three"))
            (put! (config 2 "two"))
            (put! (config 3 "stale-equal"))
            (is (= (config 3 "three") (p/get-flag-config r "t" "prod" "f"))
                "retried writes never let an equal or lower revision replace the stored one")
            (is (= {:value "three" :revision 3 :reason :rule :rule-index 0}
                   (select-keys (p/evaluate r "t" "prod" "f" "s" {"plan" "pro"})
                                [:value :revision :reason :rule-index])))
            (is (nil? (p/get-flag-config r "t" "dev" "f")))))))))
