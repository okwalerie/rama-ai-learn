(ns hld-ad-click-aggregation.nfr-test
  "NFR tests: each click is counted exactly once under a forced stream retry,
   and window reads and click writes do not grow with the number of clicks
   already counted into the window (pre-aggregation, not read-time
   aggregation of raw clicks)."
  (:require [clojure.test :refer [deftest is testing]]
            [com.rpl.rama.test :as rtest]
            [hld-ad-click-aggregation.protocol :as p]
            [rama-challenges.harness :as harness]
            [rama-challenges.nfr :as nfr]))

(defn- exercise [tasks f]
  (let [{:keys [module wrap-client]}
        ((requiring-resolve 'hld-ad-click-aggregation.module/create-module))]
    (with-open [ipc (rtest/create-ipc)]
      (rtest/launch-module! ipc module {:tasks tasks :threads tasks})
      (f (wrap-client ipc) (wrap-client ipc)))))

(defn- counters [clicks billed invalid fraud spend]
  {:clicks clicks :billed-clicks billed :invalid-clicks invalid
   :fraud-clicks fraud :billed-spend spend})

(defn- retried!
  "Runs write f and waits, failing the first stream completion so a stream
   design retries it. A microbatch design sees no forced failure."
  [c f]
  (nfr/failed-streaming #(do (f) (harness/wait-for-processing! c))))

(deftest forced-stream-retry-counts-each-click-once
  (doseq [tasks [2 4]]
    (testing (str tasks " tasks")
      (exercise tasks
        (fn [c r]
          (retried! r #(p/record-click! c "A" "r1" 61 "US" "m" 5 true false))
          (retried! r #(p/record-click! c "A" "r1" 61 "US" "m" 5 true false))
          (retried! r #(p/record-click! c "A" "r2" 62 "US" "m" 7 false false))
          (retried! r #(p/record-click! c "A" "r3" 63 "DE" "d" 9 true true))
          (retried! r #(p/advance-watermark! c "A" 300))
          (retried! r #(p/record-click! c "A" "late" 64 "US" "m" 1 true false))
          (retried! r #(p/record-click! c "A" "r4" 250 "US" "m" 2 true false))
          (is (= {:window-start 60
                  :totals (counters 3 1 1 1 5)
                  :breakdown {["US" "m"] (counters 2 1 1 0 5)
                              ["DE" "d"] (counters 1 0 0 1 0)}}
                 (p/get-window r "A" 60))
              "each click counted once into totals and breakdown")
          (is (= [60 240] (mapv :window-start (p/get-windows r "A" 0 600))))
          (is (= (counters 1 1 0 0 2) (:totals (p/get-window r "A" 240))))
          (is (= [:billed 0] ((juxt :disposition :watermark) (p/get-request r "A" "r1"))))
          (is (= [:late 300] ((juxt :disposition :watermark) (p/get-request r "A" "late"))))
          (is (= 300 (p/get-watermark r "A"))))))))

(defn- fill!
  "Counts n billed clicks, all on one [geo device] pair, into window ws."
  [c campaign ws n tag]
  (doseq [i (range n)]
    (p/record-click! c campaign (str tag "-" i) (+ ws (mod i 60)) "US" "m" 1 true false)))

(defn- sample [c r ws tag]
  (let [measure-write (fn [f] (nfr/capture-rocks-ops #(do (f) (harness/wait-for-processing! r))))]
    {:get-window (nfr/capture-rocks-ops #(p/get-window r "A" ws))
     :get-windows (nfr/capture-rocks-ops #(p/get-windows r "A" ws (+ ws 60)))
     :record-click (measure-write #(p/record-click! c "A" (str "probe-" tag) ws "US" "m" 1 true false))
     :record-replay (measure-write #(p/record-click! c "A" (str "probe-" tag) ws "US" "m" 1 true false))}))

(deftest window-work-independent-of-clicks-in-window
  (doseq [tasks [2 4]]
    (testing (str tasks " tasks")
      (exercise tasks
        (fn [c r]
          ;; Same campaign, same pair: a quiet window and a hot window.
          (fill! c "A" 600 10 "quiet")
          (fill! c "A" 1200 1000 "hot")
          ;; The hot window's pair is also hot in another campaign.
          (fill! c "B" 1200 1000 "other")
          (harness/wait-for-processing! r)
          (let [quiet (sample c r 600 "quiet")
                hot (sample c r 1200 "hot")]
            (println "Ad-click window work" tasks "tasks" quiet "->" hot)
            (is (= (counters 11 11 0 0 11) (:totals (p/get-window r "A" 600))))
            (is (= {:window-start 1200
                    :totals (counters 1001 1001 0 0 1001)
                    :breakdown {["US" "m"] (counters 1001 1001 0 0 1001)}}
                   (p/get-window r "A" 1200)))
            (is (pos? (get-in quiet [:record-click :writes])))
            (doseq [op (keys quiet)
                    metric [:reads :iterators :iterator-reads :writes]]
              (let [before (get-in quiet [op metric])
                    after (get-in hot [op metric])]
                (is (<= after (+ 10 before))
                    (str op " " metric " grew with clicks counted into the window (11 -> 1001): "
                         before " -> " after))))))))))
