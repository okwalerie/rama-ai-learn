(ns hld-ad-click-aggregation.efficiency-test-support
  "Efficiency private tests for hld-ad-click-aggregation (README 'Efficiency
   contract').

   Method: RocksDB operation events (reads, iterator seeks, iterator steps)
   are captured around each operation with `rtest/with-event-hook`. Costs
   are compared between a small state and a grown state in which only
   irrelevant data was added — other campaigns, out-of-range windows on the
   same campaign, and late-only audit records on the same campaign — while
   the relevant result is unchanged. A design whose work is proportional to
   the requested window(s) and the touched request stays flat; a design
   that scans a campaign's request-ids, its whole window map, or other
   campaigns grows by hundreds of operations. Only growth is asserted, and
   only for the bounds the README publishes (`record-click!`, `get-request`,
   `get-window`, `get-windows`); the bound (after <= 2 * before + 20) is
   deliberately generous so any layout that meets the published bound
   passes, including iterator- or index-based range reads that touch a few
   extra keys around the range. No absolute operation counts, no topology
   type, and no PState layout are required.

   `get-watermark` and `advance-watermark!` costs are NOT asserted: the
   README's efficiency contract publishes no bound for them, and the growth
   phase below adds same-campaign data the README does not bound them
   against. Their behaviour is checked for correctness only.

   Correctness of every result is asserted OUTSIDE the event captures, with
   expectations derived from the README rules in this file.

   Limitations: the events carry no payload sizes, so a design that stores
   a whole campaign (all requests and windows) as one non-subindexed blob
   and filters in memory costs a constant number of reads regardless of
   size and is not caught here; only growth visible as reads / iterator
   seeks / iterator steps is caught. The growth phase adds other campaigns
   and same-campaign data at once, so the README's cross-campaign isolation
   clause is exercised jointly with the per-operation bounds, not in
   isolation."
  (:require
   [clojure.test :refer [is testing]]
   [com.rpl.rama.test :as rtest]
   [rama-challenges.harness :as harness]
   [hld-ad-click-aggregation.protocol :as p]))

(def ROCKS-EVENTS #{:rocks-read :rocks-iterator :rocks-iterator-read})

(defn capture
  "Runs f, returning {:result r :cost n} where :cost is the number of
   RocksDB read-side operations observed during f."
  [f]
  (let [events (atom [])
        r (rtest/with-event-hook
            (fn [kw _data] (swap! events conj kw))
            (f))]
    {:result r
     :cost (count (filter ROCKS-EVENTS @events))}))

(defn within-growth?
  "Generous, architecture-neutral growth bound."
  [before after]
  (<= after (+ (* 2 before) 20)))

(defn click* [c cid rid ts geo device spend valid? fraud?]
  (p/record-click! c cid rid ts geo device spend valid? fraud?))
(defn adv* [c cid w] (p/advance-watermark! c cid w))
(defn sync! [c] (harness/wait-for-processing! c))

(defn counters [clicks billed invalid fraud spend]
  {:clicks clicks :billed-clicks billed :invalid-clicks invalid
   :fraud-clicks fraud :billed-spend spend})

(defn spec-disposition [wm ws valid? fraud?]
  (cond (>= wm (+ ws 180)) :late
        fraud?             :fraud
        (not valid?)       :invalid
        :else              :billed))

(defn expected-windows
  "Spec-derived {window-start window-map} for first-arrival clicks
   [ts geo device spend valid? fraud?] applied at watermark `wm`."
  [wm clicks]
  (reduce
   (fn [acc [ts geo device spend valid? fraud?]]
     (let [ws (- ts (mod ts 60))
           disp (spec-disposition wm ws valid? fraud?)]
       (if (= :late disp)
         acc
         (let [d (counters 1 (if (= :billed disp) 1 0) (if (= :invalid disp) 1 0)
                           (if (= :fraud disp) 1 0) (if (= :billed disp) spend 0))
               add (fn [m] (merge-with + (or m (counters 0 0 0 0 0)) d))]
           (-> acc
               (update-in [ws :window-start] (fnil identity ws))
               (update-in [ws :totals] add)
               (update-in [ws :breakdown [geo device]] add))))))
   {}
   clicks))

(defn windows-in [exp start end]
  (->> (keys exp) (filter #(and (>= % start) (< % end))) sort (mapv exp)))

(def PAIRS [["US" "m"] ["DE" "m"] ["FR" "d"]])

(defn base-clicks
  "Three relevant windows (6000, 6060, 6120), three pairs each, one click per
   pair: [rid ts geo device spend valid? fraud?]."
  []
  (vec (for [ws [6000 6060 6120]
             [k [geo device]] (map-indexed vector PAIRS)]
         [(str "r-" ws "-" k) (+ ws k) geo device (inc k) true false])))

(defn test-module-efficiency
  [create-module-fn tasks]
  (let [{:keys [module wrap-client]} (create-module-fn)]
    (with-open [ipc (rtest/create-ipc)]
      (rtest/launch-module! ipc module {:tasks tasks :threads 2})
      (let [c (wrap-client ipc)
            R "eff-r"
            base (base-clicks)
            probe-1 ["probe-1" 6061 "US" "m" 1 true false]
            probe-2 ["probe-2" 6062 "US" "m" 1 true false]
            rows (fn [& groups] (map rest (apply concat groups)))]

        ;; ---- small state --------------------------------------------------
        (adv* c R 1000)
        (doseq [[rid ts geo device spend valid? fraud?] base]
          (click* c R rid ts geo device spend valid? fraud?))
        (sync! c)

        (let [exp-1 (expected-windows 1000 (rows base))]
          (is (= [6000 6060 6120] (sort (keys exp-1))))
          (is (= 1000 (p/get-watermark c R)))
          (is (= (get exp-1 6060) (p/get-window c R 6060)))
          (is (= (windows-in exp-1 6000 6180) (p/get-windows c R 6000 6180)))
          (is (= "r-6000-0" (:request-id (p/get-request c R "r-6000-0"))))

          (let [req-1   (:cost (capture #(p/get-request c R "r-6000-0")))
                win-1   (:cost (capture #(p/get-window c R 6060)))
                range-1 (:cost (capture #(p/get-windows c R 6000 6180)))
                click-1 (:cost (capture #(do (apply click* c R probe-1) (sync! c))))
                late-1  (:cost (capture #(do (click* c R "late-1" 100 "US" "m" 1 true false) (sync! c))))
                rep-1   (:cost (capture #(do (apply click* c R probe-1) (sync! c))))
                exp-1b  (expected-windows 1000 (rows base [probe-1]))]
            ;; stale then effective advance: correctness only (no published bound)
            (adv* c R 500)
            (adv* c R 1001)
            (sync! c)
            (is (= 1001 (p/get-watermark c R)))
            (is (= (get exp-1b 6060) (p/get-window c R 6060)) "probe click counted once")
            (is (= :late (:disposition (p/get-request c R "late-1"))))
            (is (nil? (p/get-window c R 60)))

            ;; ---- grow irrelevant state -----------------------------------
            ;; (a) 100 populated windows above the relevant range on R
            (doseq [i (range 100)]
              (click* c R (str "gw-a-" i) (+ 12000 (* 60 i)) "US" "m" 1 true false))
            ;; (b) 50 populated windows below the relevant range on R (open at 1001)
            (doseq [i (range 50)]
              (click* c R (str "gw-b-" i) (+ 3000 (* 60 i)) "DE" "m" 1 true false))
            ;; (c) 100 late-only audit records on R (windows 0 and 60 are closed)
            (doseq [i (range 100)]
              (click* c R (str "late-" i) i "US" "m" 1 true false))
            ;; (d) 12 other campaigns, each with 40 clicks into window 6000
            (doseq [j (range 12) i (range 40)]
              (click* c (str "eff-o-" j) (str "o-" i) (+ 6000 i) "FR" "d" 3 true false))
            (sync! c)

            (let [grow-a (for [i (range 100)] [(+ 12000 (* 60 i)) "US" "m" 1 true false])
                  grow-b (for [i (range 50)] [(+ 3000 (* 60 i)) "DE" "m" 1 true false])
                  exp-2 (expected-windows 1001 (concat (rows base [probe-1]) grow-a grow-b))
                  exp-o (expected-windows 0 (for [i (range 40)] [(+ 6000 i) "FR" "d" 3 true false]))]
              (is (= 153 (count exp-2)) "150 irrelevant windows were added")
              (is (= (get exp-1b 6060) (get exp-2 6060)) "growth left the relevant window unchanged")
              (is (= (get exp-2 6060) (p/get-window c R 6060)))
              (is (= (windows-in exp-2 6000 6180) (p/get-windows c R 6000 6180)))
              (is (= 3 (count (p/get-windows c R 6000 6180))))
              (is (= (get exp-o 6000) (p/get-window c "eff-o-11" 6000)))
              (is (= 0 (p/get-watermark c "eff-o-11")))
              (is (= :late (:disposition (p/get-request c R "late-99"))))
              (is (nil? (p/get-window c R 0)) "late-only windows do not exist")

              (let [req-2   (:cost (capture #(p/get-request c R "r-6000-0")))
                    win-2   (:cost (capture #(p/get-window c R 6060)))
                    range-2 (:cost (capture #(p/get-windows c R 6000 6180)))
                    click-2 (:cost (capture #(do (apply click* c R probe-2) (sync! c))))
                    late-2  (:cost (capture #(do (click* c R "late-x" 101 "US" "m" 1 true false) (sync! c))))
                    rep-2   (:cost (capture #(do (apply click* c R probe-2) (sync! c))))
                    exp-2b  (expected-windows 1001 (concat (rows base [probe-1 probe-2]) grow-a grow-b))]
                (testing "get-request reads nothing proportional to other requests, windows, or campaigns"
                  (is (within-growth? req-1 req-2)
                      (str "get-request rocks ops grew from " req-1 " to " req-2
                           " after adding ~260 requests, 150 windows and 12 campaigns")))
                (testing "get-window reads one window, not the campaign's other windows"
                  (is (within-growth? win-1 win-2)
                      (str "get-window rocks ops grew from " win-1 " to " win-2
                           " after adding 150 other windows to the campaign")))
                (testing "get-windows work is proportional to windows inside the range"
                  (is (within-growth? range-1 range-2)
                      (str "get-windows over 3 windows: rocks ops grew from " range-1 " to " range-2
                           " after adding 150 out-of-range windows to the campaign")))
                (testing "record-click! does bounded work independent of campaign size"
                  (is (within-growth? click-1 click-2)
                      (str "counted click rocks ops grew from " click-1 " to " click-2))
                  (is (within-growth? late-1 late-2)
                      (str "late click rocks ops grew from " late-1 " to " late-2))
                  (is (within-growth? rep-1 rep-2)
                      (str "replay rocks ops grew from " rep-1 " to " rep-2)))
                ;; stale then effective advance: correctness only (no published bound)
                (adv* c R 500)
                (adv* c R 1002)
                (sync! c)
                ;; the probe writes were applied correctly, exactly once
                (is (= 1002 (p/get-watermark c R)))
                (is (= (get exp-2b 6060) (p/get-window c R 6060)))
                (is (= :late (:disposition (p/get-request c R "late-x"))))

                (testing "an advance that closes every window changes nothing stored and touches no other campaign"
                  ;; R now has 153 populated windows; a jump to 10^12 closes them all
                  ;; (and 12 other campaigns are untouched). Correctness only: the
                  ;; README publishes no work bound for advance-watermark!.
                  (do
                    (adv* c R 1000000000000)
                    (sync! c)
                    (is (= 1000000000000 (p/get-watermark c R)))
                    (is (= (windows-in exp-2b 6000 6180) (p/get-windows c R 6000 6180))
                        "closure changes nothing stored")
                    (is (= 153 (count (p/get-windows c R 0 1000000000020))))
                    (click* c R "after-jump" 6063 "US" "m" 1 true false)
                    (sync! c)
                    (is (= :late (:disposition (p/get-request c R "after-jump"))))
                    (is (= (get exp-2b 6060) (p/get-window c R 6060)))
                    (is (= 0 (p/get-watermark c "eff-o-3")) "other campaigns untouched")
                    (is (= (get exp-o 6000) (p/get-window c "eff-o-3" 6000)))))))))))))
