(ns time-series-module-hard.nfr-test
  "NFR test: a multi-year range query reads far fewer stored entries than the
   points it aggregates, as README requires for ranges up to multiple years."
  (:require
   [clojure.test :refer [deftest is testing]]
   [rama-challenges.harness :refer [with-module] :as harness]
   [rama-challenges.nfr :as nfr]
   [time-series-module-hard.protocol :as p]))

(defn- minute-timestamp [minute-bucket]
  (+ (* minute-bucket 60000) 30000))

(def ^:private minutes-per-year (* 365 24 60))

(deftest multi-year-range-reads-coarse-buckets
  (with-module [client (requiring-resolve 'time-series-module-hard.module/create-module)]
    (let [url "years.com"
          start 63
          end (+ start (* 3 minutes-per-year))
          n 400
          step (quot (- end start) n)
          ;; Every point lands in a distinct day: a design with no bucket
          ;; coarser than a day reads one entry per point.
          data (vec (for [i (range n)] [(inc (mod i 100)) (+ start (* i step))]))]
      (is (< 1440 step) "fixture: one point per day")
      (doseq [[latency bucket] data]
        (p/record-latency! client url latency (minute-timestamp bucket)))
      (harness/wait-for-processing! client)
      (testing "correct aggregate over three years"
        (is (= {:cardinality n
                :total (reduce + (map first data))
                :min-latency-millis 1
                :max-latency-millis 100}
               (select-keys (p/get-stats-for-range client url start end)
                            [:cardinality :total :min-latency-millis :max-latency-millis]))))
      (testing "reads far fewer stored entries than points"
        (let [ops (nfr/capture-rocks-ops #(p/get-stats-for-range client url start end))]
          (println "Multi-year range query" ops)
          (is (< (:reads ops) 10) (str ops))
          (is (< (:iterators ops) 10) (str ops))
          (is (< (+ (:reads ops) (:iterator-reads ops)) (quot n 4))
              (str "a three-year range must read coarse buckets, not one entry per day: " ops))))
      (testing "a range crossing year boundaries off bucket edges stays correct"
        (let [lo (+ start (* 7 step) 17)
              hi (- end (* 5 step) 3)
              inside (filter #(<= lo (second %) (dec hi)) data)]
          (is (= [(count inside) (reduce + (map first inside))]
                 ((juxt :cardinality :total) (p/get-stats-for-range client url lo hi)))))))))
