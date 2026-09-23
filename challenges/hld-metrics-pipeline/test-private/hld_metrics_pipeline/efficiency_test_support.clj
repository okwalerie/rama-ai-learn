(ns hld-metrics-pipeline.efficiency-test-support
  "Efficiency private tests for hld-metrics-pipeline (README 'Efficiency
   contract').

   Method: RocksDB operation events (reads, iterator seeks, iterator steps)
   are captured around each operation with `rtest/with-event-hook`. Costs
   are compared between a small state and a grown state in which only
   irrelevant data was added — other tenants, other series, out-of-range
   samples and buckets on the same series — while the relevant result is
   unchanged. A design whose work is proportional to the queried series'
   in-range data stays flat; a design that scans other series, the whole
   raw map, or the whole bucket map grows by hundreds of operations. The
   bounds are deliberately generous (2x + 20) so any reasonable topology or
   storage layout passes; no exact counts and no topology type are required.

   Correctness of every result is asserted OUTSIDE the event captures.

   Limitation: the events carry no payload sizes, so a design that stores a
   whole series as one non-subindexed blob and filters in memory costs one
   read regardless of size and is not caught here; it is caught only if
   the blob read grows the iterator/read counts (e.g. blob per tenant)."
  (:require
   [clojure.test :refer [is testing]]
   [com.rpl.rama.test :as rtest]
   [rama-challenges.harness :as harness]
   [hld-metrics-pipeline.protocol :as p]))

(def ROCKS-EVENTS #{:rocks-read :rocks-iterator :rocks-iterator-read})

(defn capture
  "Runs f, returning {:result r :cost n :events [...]} where :cost is the
   number of RocksDB read-side operations observed during f."
  [f]
  (let [events (atom [])
        r (rtest/with-event-hook
            (fn [kw data] (swap! events conj (assoc data :event-type kw)))
            (f))
        evs @events]
    {:result r
     :events evs
     :cost (count (filter #(ROCKS-EVENTS (:event-type %)) evs))}))

(defn within-growth?
  "Generous, architecture-neutral growth bound."
  [before after]
  (<= after (+ (* 2 before) 20)))

(defn info* [c [t m l]] (p/get-series-info c t m l))
(defn raw* [c [t m l] start end] (p/query-raw c t m l start end))
(defn rollup* [c [t m l] w start end]
  (assert (and (zero? (mod start w)) (zero? (mod end w))))
  (p/query-rollup c t m l w start end))
(defn advance* [c [t m l] clock] (p/advance-clock! c t m l clock))
(defn ingest* [c [t m l] ts v] (p/ingest-sample! c t m l ts v))
(defn sync! [c] (harness/wait-for-processing! c))

(defn expected-rollup
  "Spec-derived expectation: buckets of width w from accepted (ts v) pairs,
   complete and retained at clock, with start in [start end), ascending."
  [samples w clock start end]
  (->> samples
       (group-by (fn [[ts _]] (- ts (mod ts w))))
       (filter (fn [[s _]] (and (>= s start) (< s end)
                                (<= (+ s w) clock)
                                (> (+ s w 7200) clock))))
       (sort-by key)
       (mapv (fn [[s vs]]
               (let [xs (map second vs)]
                 {:start s :count (count xs) :sum (reduce + xs)
                  :min (reduce min xs) :max (reduce max xs)})))))

(defn expected-raw
  [samples clock start end]
  (->> samples
       (filter (fn [[ts _]] (and (>= ts start) (< ts end) (> (+ ts 300) clock))))
       (sort-by first)
       (mapv (fn [[ts v]] {:timestamp ts :value v}))))

(defn test-module-efficiency
  [create-module-fn tasks]
  (let [{:keys [module wrap-client]} (create-module-fn)]
    (with-open [ipc (rtest/create-ipc)]
      (rtest/launch-module! ipc module {:tasks tasks :threads 2})
      (let [c (wrap-client ipc)
            R ["rel" "requests" {"host" "web-1" "zone" "a"}]
            ;; 100 old buckets, one sample each, built by walking the clock
            old-starts (vec (range 92760 98760 60))
            old-samples (mapv (fn [s] [s 1]) old-starts)
            ;; relevant complete buckets + relevant raw window
            rel-bucket-samples [[98760 10] [98820 20] [98880 30]]
            rel-raw-samples (mapv (fn [ts] [ts (- ts 98990)]) (range 98990 99000))
            base-samples (concat old-samples rel-bucket-samples rel-raw-samples)
            raw-range [98990 99000]
            rollup-range [98760 98940]
            hour-range [90000 97200]]

        ;; ---- build the small state -------------------------------------
        (doseq [[ts v] old-samples]
          (advance* c R ts)
          (ingest* c R ts v))
        (advance* c R 99000)
        (doseq [[ts v] (concat rel-bucket-samples rel-raw-samples)]
          (ingest* c R ts v))
        (sync! c)

        (let [exp-raw (expected-raw base-samples 99000 98990 99000)
              exp-roll (expected-rollup base-samples 60 99000 98760 98940)
              exp-hour (expected-rollup base-samples 3600 99000 90000 97200)]
          (is (= 10 (count exp-raw)))
          (is (= 3 (count exp-roll)))
          (is (= 2 (count exp-hour)) "buckets 90000 and 93600 complete and retained")
          (is (= {:clock 99000 :accepted 113 :rejected-future 0
                  :rejected-expired 0 :rejected-duplicate 0}
                 (info* c R)))
          (is (= exp-raw (apply raw* c R raw-range)))
          (is (= exp-roll (apply rollup* c R 60 rollup-range)))
          (is (= exp-hour (apply rollup* c R 3600 hour-range)))

          (let [raw-1  (:cost (capture #(apply raw* c R raw-range)))
                roll-1 (:cost (capture #(apply rollup* c R 60 rollup-range)))
                hour-1 (:cost (capture #(apply rollup* c R 3600 hour-range)))
                info-1 (:cost (capture #(info* c R)))
                ing-1  (:cost (capture #(do (ingest* c R 98989 99) (sync! c))))
                adv-1  (:cost (capture #(do (advance* c R 99001) (sync! c))))]
            (is (= exp-raw (apply raw* c R raw-range)) "unchanged by the probe writes")
            (is (= exp-roll (apply rollup* c R 60 rollup-range)))
            (is (= 114 (:accepted (info* c R))))

            (testing "query-rollup does not iterate the out-of-range buckets of the series"
              ;; 100 retained, complete, out-of-range 60-buckets exist on R;
              ;; a whole-map scan costs at least that many iterator steps.
              (is (< roll-1 (quot (count old-starts) 2))
                  (str "rollup over 3 buckets cost " roll-1
                       " rocks ops with " (count old-starts)
                       " out-of-range buckets on the series")))

            ;; ---- grow irrelevant state -----------------------------------
            ;; (a) out-of-range retained raw samples on R (never inside the
            ;;     relevant raw window or the relevant 60-buckets)
            (let [grow-raw (concat (range 98703 98760) (range 98940 98989))]
              (doseq [ts grow-raw] (ingest* c R ts 1))
              ;; (b) many other series: same tenant / other tenants / other
              ;;     labels, each with its own retained samples and buckets
              (doseq [tenant ["rel" "t2" "t3" "t4" "t5" "t6"]
                      metric ["requests" "latency"]
                      labels [{"host" "web-1" "zone" "a"} {"host" "web-2" "zone" "b"}]
                      :let [S [tenant metric labels]]
                      :when (not= S R)]
                (advance* c S 99001)
                (doseq [ts (range 98950 99001)] (ingest* c S ts ts)))
              (sync! c)

              (let [all-samples (concat base-samples [[98989 99]] (map (fn [ts] [ts 1]) grow-raw))
                    exp-raw2 (expected-raw all-samples 99001 98990 99000)
                    exp-roll2 (expected-rollup all-samples 60 99001 98760 98940)
                    exp-hour2 (expected-rollup all-samples 3600 99001 90000 97200)]
                (is (= exp-raw exp-raw2) "growth left the relevant raw result unchanged")
                (is (= exp-roll exp-roll2) "growth left the relevant rollup result unchanged")
                (is (= exp-hour exp-hour2))
                (is (= exp-raw2 (apply raw* c R raw-range)))
                (is (= exp-roll2 (apply rollup* c R 60 rollup-range)))
                (is (= exp-hour2 (apply rollup* c R 3600 hour-range)))
                (is (= (+ 114 (count grow-raw)) (:accepted (info* c R))))
                (is (= 51 (:accepted (info* c ["t6" "latency" {"host" "web-2" "zone" "b"}]))))

                (let [raw-2  (:cost (capture #(apply raw* c R raw-range)))
                      roll-2 (:cost (capture #(apply rollup* c R 60 rollup-range)))
                      hour-2 (:cost (capture #(apply rollup* c R 3600 hour-range)))
                      info-2 (:cost (capture #(info* c R)))
                      ing-2  (:cost (capture #(do (ingest* c R 99001 7) (sync! c))))
                      adv-2  (:cost (capture #(do (advance* c R 99002) (sync! c))))]
                  (testing "query-raw work is proportional to in-range data of the series"
                    (is (within-growth? raw-1 raw-2)
                        (str "query-raw rocks ops grew from " raw-1 " to " raw-2
                             " after adding out-of-range samples and other series")))
                  (testing "query-rollup (60) work is proportional to in-range buckets"
                    (is (within-growth? roll-1 roll-2)
                        (str "query-rollup(60) rocks ops grew from " roll-1 " to " roll-2)))
                  (testing "query-rollup (3600) work is proportional to in-range buckets"
                    (is (within-growth? hour-1 hour-2)
                        (str "query-rollup(3600) rocks ops grew from " hour-1 " to " hour-2)))
                  (testing "get-series-info reads a constant amount"
                    (is (within-growth? info-1 info-2)
                        (str "get-series-info rocks ops grew from " info-1 " to " info-2)))
                  (testing "ingest-sample! does bounded work independent of series size"
                    (is (within-growth? ing-1 ing-2)
                        (str "ingest rocks ops grew from " ing-1 " to " ing-2)))
                  (testing "advance-clock! that expires nothing does bounded work"
                    (is (within-growth? adv-1 adv-2)
                        (str "advance rocks ops grew from " adv-1 " to " adv-2)))
                  ;; the probe writes were applied correctly
                  (is (= 99002 (:clock (info* c R))))
                  (is (= (conj exp-raw {:timestamp 99001 :value 7})
                         (raw* c R 98990 99002))))))

            (testing "advance-clock! work is proportional to what it expires, not to the clock jump"
              ;; R now holds ~220 retained raw samples and ~105 buckets.
              ;; Jumping the clock by 10^12 expires them all; the cost must not
              ;; scale with the numeric distance.
              (let [{:keys [cost]} (capture #(do (advance* c R 1000000000000) (sync! c)))
                    n-retained (+ 114 (count (concat (range 98703 98760) (range 98940 98989))) 1)]
                (is (< cost (* 8 (+ n-retained 110)))
                    (str "advance expiring ~" n-retained " samples and ~105 buckets cost "
                         cost " rocks ops"))
                (is (= [] (raw* c R 0 1000000000000)))
                (is (= [] (rollup* c R 60 0 1000000000800)))
                (is (= [] (rollup* c R 3600 0 1000000000800)))
                (is (= (+ 114 (count (concat (range 98703 98760) (range 98940 98989))) 1)
                       (:accepted (info* c R)))
                    "expiry never touches the counters")
                ;; other series untouched by R's advance
                (is (= 51 (count (raw* c ["t6" "latency" {"host" "web-2" "zone" "b"}]
                                       0 1000000000000))))))))))))
