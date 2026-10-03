(ns hld-metrics-pipeline.nfr-test
  "NFR test: admission counters, raw samples and rollups apply exactly once
   under a forced stream retry."
  (:require [clojure.test :refer [deftest is testing]]
            [com.rpl.rama.test :as rtest]
            [hld-metrics-pipeline.protocol :as p]
            [rama-challenges.harness :as harness]
            [rama-challenges.nfr :as nfr]))

(defn- retried!
  "Runs write f and waits, failing the first stream completion so a stream
   design retries it. A microbatch design sees no forced failure."
  [c f]
  (nfr/failed-streaming #(do (f) (harness/wait-for-processing! c))))

(deftest forced-stream-retry-folds-each-sample-once
  (doseq [tasks [2 4]]
    (testing (str tasks " tasks")
      (let [{:keys [module wrap-client]}
            ((requiring-resolve 'hld-metrics-pipeline.module/create-module))]
        (with-open [ipc (rtest/create-ipc)]
          (rtest/launch-module! ipc module {:tasks tasks :threads tasks})
          (let [c (wrap-client ipc)
                r (wrap-client ipc)
                s ["t" "cpu" {"host" "a"}]
                ingest! (fn [ts v] (retried! r #(apply p/ingest-sample! c (conj s ts v))))]
            (retried! r #(apply p/advance-clock! c (conj s 1000)))
            (ingest! 900 5)
            (ingest! 900 7)
            (ingest! 2000 1)
            (ingest! 600 1)
            (ingest! 950 3)
            (ingest! 3 3)
            (retried! r #(apply p/advance-clock! c (conj s 1020)))
            (is (= {:clock 1020 :accepted 2 :rejected-future 1
                    :rejected-expired 2 :rejected-duplicate 1}
                   (apply p/get-series-info r s))
                "each retried ingest updates exactly one counter once")
            (is (= [{:timestamp 900 :value 5} {:timestamp 950 :value 3}]
                   (apply p/query-raw r (conj s 0 3000))))
            (is (= [{:start 900 :count 2 :sum 8 :min 3 :max 5}]
                   (apply p/query-rollup r (conj s 60 0 3000)))
                "each accepted sample folds into its rollup once")))))))
