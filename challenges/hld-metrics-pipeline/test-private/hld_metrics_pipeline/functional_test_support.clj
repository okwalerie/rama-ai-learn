(ns hld-metrics-pipeline.functional-test-support
  "Functional private tests for hld-metrics-pipeline.

   Authority: README.md and the MetricsPipeline protocol docstrings. Every
   expected value below is derived by hand from the spec's admission,
   retention, and rollup rules — never from the reference implementation.

   The module is deployed explicitly with the task count passed in (the
   challenge test runs the suite once with 2 tasks and once with 4). Two
   wrappers are created through the same `:wrap-client`; the suite
   alternates synchronized phases between them so business state must live
   in the cluster, not in client memory."
  (:require
   [clojure.test :refer [is testing]]
   [com.rpl.rama.test :as rtest]
   [rama-challenges.harness :as harness]
   [hld-metrics-pipeline.protocol :as p]))

;;; ---------------------------------------------------------------------------
;;; Launch and per-series helpers

(defn launch-with
  "Deploys the module with exactly `tasks` tasks and calls
   (f ipc wrap-client). Closes the cluster afterwards."
  [create-module-fn tasks f]
  (let [{:keys [module wrap-client]} (create-module-fn)]
    (is (fn? wrap-client) "create-module must return a :wrap-client fn")
    (with-open [ipc (rtest/create-ipc)]
      (rtest/launch-module! ipc module {:tasks tasks :threads 2})
      (f ipc wrap-client))))

(def ZERO-INFO
  {:clock 0 :accepted 0 :rejected-future 0 :rejected-expired 0 :rejected-duplicate 0})

(defn info* [c [t m l]] (p/get-series-info c t m l))
(defn raw* [c [t m l] start end] (p/query-raw c t m l start end))
(defn rollup* [c [t m l] w start end]
  (assert (and (zero? (mod start w)) (zero? (mod end w))))
  (p/query-rollup c t m l w start end))
(defn advance* [c [t m l] clock] (p/advance-clock! c t m l clock))
(defn ingest* [c [t m l] ts v] (p/ingest-sample! c t m l ts v))

(defn sync! [c] (harness/wait-for-processing! c))

(defn row [ts v] {:timestamp ts :value v})
(defn bucket [start cnt sum mn mx] {:start start :count cnt :sum sum :min mn :max mx})

(def BIG 1000000000800) ; divisible by both rollup widths

;;; ---------------------------------------------------------------------------
;;; The suite

(defn test-module-functional
  [create-module-fn tasks]
  (launch-with
   create-module-fn tasks
   (fn [ipc wrap-client]
     (let [ca (wrap-client ipc)
           cb (wrap-client ipc)]
       (is (satisfies? harness/Synchronizable ca)
           "wrap-client must reify rama-challenges.harness/Synchronizable")
       (is (satisfies? p/MetricsPipeline ca))

       (testing (str "README worked example, verbatim (" tasks " tasks)")
         ;; writes through wrapper A, reads through wrapper B
         (let [S ["acme" "http_requests" {"host" "web-1"}]]
           (advance* ca S 1000)
           (sync! ca)
           (is (= (assoc ZERO-INFO :clock 1000) (info* cb S))
               "advance on a fresh series sets the clock, counters stay 0")
           (ingest* ca S 1000 5)      ; accepted (ts == clock)
           (ingest* ca S 1001 8)      ; future
           (ingest* ca S 700 4)       ; expired: 700 + 300 <= 1000
           (ingest* ca S 701 3)       ; accepted: 701 + 300 > 1000
           (ingest* ca S 701 9)       ; duplicate; value 3 stays
           (sync! ca)
           (is (= {:clock 1000 :accepted 2 :rejected-future 1
                   :rejected-expired 1 :rejected-duplicate 1}
                  (info* cb S)))
           (is (= [(row 701 3) (row 1000 5)] (raw* cb S 600 1100)))
           (is (= [(bucket 660 1 3 3 3)] (rollup* cb S 60 600 1020))
               "bucket 960 ends at 1020 > clock 1000: incomplete")
           (is (= [] (rollup* cb S 3600 0 3600)) "bucket 0 ends at 3600 > 1000")

           ;; step 11: sample 701 expires exactly when 701 + 300 == clock
           (advance* cb S 1001)
           (sync! cb)
           (is (= [(row 1000 5)] (raw* ca S 600 1100))
               "ts 701 must be gone from query-raw once 701 + 300 <= clock")
           (is (= [(bucket 660 1 3 3 3)] (rollup* ca S 60 600 1020))
               "raw expiry never subtracts from a bucket")

           ;; step 12: bucket 960 completes exactly at clock 1020
           (advance* cb S 1019)
           (sync! cb)
           (is (= [(bucket 660 1 3 3 3)] (rollup* ca S 60 600 1020))
               "bucket end 1020 > clock 1019: still incomplete")
           (advance* cb S 1020)
           (sync! cb)
           (is (= [(bucket 660 1 3 3 3) (bucket 960 1 5 5 5)]
                  (rollup* ca S 60 600 1020)))

           ;; step 13: 3600 bucket counts the raw-expired sample too
           (advance* ca S 3600)
           (sync! ca)
           (is (= [(bucket 0 2 8 3 5)] (rollup* cb S 3600 0 3600)))
           (is (= [] (raw* cb S 0 BIG)) "both raw samples expired by 3600")
           (is (= [(bucket 660 1 3 3 3) (bucket 960 1 5 5 5)]
                  (rollup* cb S 60 0 BIG)))

           ;; step 14: 60-bucket 660 retained at 7919, gone at 7920
           (advance* ca S 7919)
           (sync! ca)
           (is (= [(bucket 660 1 3 3 3) (bucket 960 1 5 5 5)]
                  (rollup* cb S 60 600 1020))
               "660 + 60 + 7200 = 7920 > 7919: retained")
           (advance* ca S 7920)
           (sync! ca)
           (is (= [(bucket 960 1 5 5 5)] (rollup* cb S 60 600 1020))
               "660 + 60 + 7200 = 7920 <= 7920: gone")
           (is (= [(bucket 0 2 8 3 5)] (rollup* cb S 3600 0 3600))
               "hourly retention is independent: 0 + 3600 + 7200 = 10800 > 7920")

           ;; step 15: backward clock is a no-op
           (advance* cb S 5000)
           (advance* cb S 7920)
           (sync! cb)
           (is (= {:clock 7920 :accepted 2 :rejected-future 1
                   :rejected-expired 1 :rejected-duplicate 1}
                  (info* ca S)))
           (is (= [(bucket 960 1 5 5 5)] (rollup* ca S 60 600 1020)))

           ;; independent hourly retention boundary: 10799 keeps, 10800 drops
           (advance* ca S 10799)
           (sync! ca)
           (is (= [(bucket 0 2 8 3 5)] (rollup* cb S 3600 0 7200)))
           (advance* ca S 10800)
           (sync! ca)
           (is (= [] (rollup* cb S 3600 0 BIG)))
           (is (= [] (rollup* cb S 60 0 BIG)) "960 + 7260 = 8220 <= 10800")
           (is (= {:clock 10800 :accepted 2 :rejected-future 1
                   :rejected-expired 1 :rejected-duplicate 1}
                  (info* cb S))
               "expiry never touches the counters")))

       (testing "rejection never reserves a timestamp; retry after clock catches up"
         (let [T ["acme" "t" {}]]
           (advance* cb T 400)
           (ingest* cb T 500 1)       ; future at 400
           (sync! cb)
           (is (= (assoc ZERO-INFO :clock 400 :rejected-future 1) (info* ca T)))
           (is (= [] (raw* ca T 0 BIG)))
           (advance* ca T 500)
           (ingest* ca T 500 7)       ; now accepted, with the new value
           (sync! ca)
           (is (= [(row 500 7)] (raw* cb T 500 501)))
           (is (= [] (raw* cb T 500 500)) "start == end is empty")
           (is (= [] (raw* cb T 501 600)) "end is exclusive, start inclusive")
           (is (= (assoc ZERO-INFO :clock 500 :accepted 1 :rejected-future 1)
                  (info* cb T)))))

       (testing "unknown series"
         (let [U ["nobody" "nothing" {"x" "y"}]]
           (is (= ZERO-INFO (info* ca U)))
           (is (= [] (raw* ca U 0 BIG)))
           (is (= [] (raw* ca U 0 1)))
           (is (= [] (rollup* ca U 60 0 BIG)))
           (is (= [] (rollup* ca U 3600 0 BIG)))
           (is (= ZERO-INFO (info* cb U)) "reads through either wrapper agree")))

       (testing "fresh series at clock 0 admits exactly ts 0"
         (let [Z ["zero" "m" {}]]
           (advance* ca Z 0)          ; no-op on a fresh series
           (sync! ca)
           (is (= ZERO-INFO (info* cb Z)))
           (ingest* ca Z 1 9)         ; future
           (ingest* ca Z 0 4)         ; accepted
           (ingest* ca Z 0 5)         ; duplicate
           (sync! ca)
           (is (= (assoc ZERO-INFO :accepted 1 :rejected-future 1 :rejected-duplicate 1)
                  (info* cb Z)))
           (is (= [(row 0 4)] (raw* cb Z 0 1)))
           (is (= [] (rollup* cb Z 60 0 60)) "bucket 0 incomplete at clock 0")
           (advance* cb Z 59)
           (sync! cb)
           (is (= [] (rollup* ca Z 60 0 60)) "0 + 60 > 59: still incomplete")
           (advance* cb Z 60)
           (sync! cb)
           (is (= [(bucket 0 1 4 4 4)] (rollup* ca Z 60 0 60)))
           (is (= [] (rollup* ca Z 3600 0 3600)))
           (advance* cb Z 3600)
           (sync! cb)
           (is (= [(bucket 0 1 4 4 4)] (rollup* ca Z 3600 0 3600)))
           (is (= [] (raw* ca Z 0 BIG)) "0 + 300 <= 3600: raw copy gone")))

       (testing "admission boundaries at clock C: C-300 expired, C-299 accepted, C accepted, C+1 future"
         (let [B ["bound" "m" {"a" "1"}]]
           (advance* cb B 1000)
           (ingest* cb B 700 1)       ; expired
           (ingest* cb B 701 2)       ; accepted
           (ingest* cb B 1000 3)      ; accepted
           (ingest* cb B 1001 4)      ; future
           (ingest* cb B 699 5)       ; expired
           (sync! cb)
           (is (= {:clock 1000 :accepted 2 :rejected-future 1
                   :rejected-expired 2 :rejected-duplicate 0}
                  (info* ca B)))
           (is (= [(row 701 2) (row 1000 3)] (raw* ca B 0 BIG)))
           ;; rule order: an expired re-offer of an accepted ts is "expired", not "duplicate"
           (advance* ca B 1001)
           (ingest* ca B 701 8)
           (sync! ca)
           (is (= {:clock 1001 :accepted 2 :rejected-future 1
                   :rejected-expired 3 :rejected-duplicate 0}
                  (info* cb B)))
           (is (= [(bucket 660 1 2 2 2)] (rollup* cb B 60 660 720))
               "the rejected re-offer did not change the bucket")
           ;; counters sum to the number of applied ingest calls
           (let [i (info* cb B)]
             (is (= 6 (+ (:accepted i) (:rejected-future i)
                         (:rejected-expired i) (:rejected-duplicate i)))))))

       (testing "conflicting duplicate keeps the first accepted value everywhere"
         (let [D ["dup" "m" {}]]
           (advance* ca D 100)
           (ingest* ca D 50 7)
           (ingest* ca D 50 -100)
           (ingest* ca D 50 7)
           (sync! ca)
           (is (= (assoc ZERO-INFO :clock 100 :accepted 1 :rejected-duplicate 2)
                  (info* cb D)))
           (is (= [(row 50 7)] (raw* cb D 0 BIG)))
           (is (= [(bucket 0 1 7 7 7)] (rollup* cb D 60 0 60)))
           (advance* cb D 3600)
           (sync! cb)
           (is (= [(bucket 0 1 7 7 7)] (rollup* ca D 3600 0 3600)))))

       (testing "label insertion order and map type do not change series identity"
         (let [L1 ["lab" "m" {"host" "a" "zone" "b"}]
               L2 ["lab" "m" {"zone" "b" "host" "a"}]
               L3 ["lab" "m" (sorted-map "zone" "b" "host" "a")]
               L4 ["lab" "m" (hash-map "host" "a" "zone" "b")]
               many-a (into {} (for [i (range 12)] [(str "k" i) (str "v" i)]))
               many-b (into (sorted-map-by (fn [x y] (compare y x))) many-a)
               M1 ["lab" "big" many-a]
               M2 ["lab" "big" many-b]]
           (advance* ca L1 100)
           (ingest* ca L2 10 1)
           (sync! ca)
           (ingest* cb L3 11 2)
           (ingest* cb L4 10 9)       ; duplicate of the sample written via L2
           (sync! cb)
           (is (= (assoc ZERO-INFO :clock 100 :accepted 2 :rejected-duplicate 1)
                  (info* ca L1)))
           (is (= (info* ca L1) (info* cb L2) (info* ca L3) (info* cb L4)))
           (is (= [(row 10 1) (row 11 2)] (raw* cb L4 0 100)))
           (is (= [(bucket 0 2 3 1 2)] (rollup* ca L2 60 0 60)))
           (advance* ca M1 50)
           (ingest* ca M2 20 5)
           (sync! ca)
           (is (= (assoc ZERO-INFO :clock 50 :accepted 1) (info* cb M1) (info* cb M2))
               "12-key label maps (hash-map vs reverse-sorted) are the same series")
           ;; but different labels are different series
           (is (= ZERO-INFO (info* cb ["lab" "m" {"host" "a"}])))
           (is (= ZERO-INFO (info* cb ["lab" "m" {}])))
           (is (= ZERO-INFO (info* cb ["lab" "m" {"host" "a" "zone" "c"}])))))

       (testing "tenant, metric, and empty-vs-non-empty labels isolate series"
         (let [A ["t1" "cpu" {}]
               B ["t2" "cpu" {}]
               C ["t1" "mem" {}]
               D ["t1" "cpu" {"host" "h"}]]
           (advance* ca A 1000)
           (advance* ca B 2000)
           (ingest* ca A 1000 1)
           (ingest* ca B 1000 2)      ; B at 2000: 1000 + 300 <= 2000 -> expired
           (ingest* ca B 1900 3)
           (ingest* ca C 0 4)         ; C at clock 0
           (ingest* ca D 5 5)         ; D at clock 0 -> future
           (sync! ca)
           (is (= (assoc ZERO-INFO :clock 1000 :accepted 1) (info* cb A)))
           (is (= (assoc ZERO-INFO :clock 2000 :accepted 1 :rejected-expired 1) (info* cb B)))
           (is (= (assoc ZERO-INFO :accepted 1) (info* cb C)))
           (is (= (assoc ZERO-INFO :rejected-future 1) (info* cb D)))
           (is (= [(row 1000 1)] (raw* cb A 0 BIG)))
           (is (= [(row 1900 3)] (raw* cb B 0 BIG)))
           (is (= [(row 0 4)] (raw* cb C 0 BIG)))
           (is (= [] (raw* cb D 0 BIG)))
           (advance* cb A 10000)      ; expires everything on A only
           (sync! cb)
           (is (= [] (raw* ca A 0 BIG)))
           (is (= [(row 1900 3)] (raw* ca B 0 BIG)) "advancing A must not touch B")
           (is (= [(row 0 4)] (raw* ca C 0 BIG)))))

       (testing "late samples land in an already-complete bucket and change it"
         (let [Lt ["late" "m" {}]]
           (advance* cb Lt 1100)
           (ingest* cb Lt 1000 5)     ; bucket 960 complete (1020 <= 1100)
           (sync! cb)
           (is (= [(bucket 960 1 5 5 5)] (rollup* ca Lt 60 960 1020)))
           (ingest* ca Lt 1010 1)     ; late but admissible: 1010 + 300 > 1100
           (ingest* ca Lt 961 -3)
           (sync! ca)
           (is (= [(bucket 960 3 3 -3 5)] (rollup* cb Lt 60 960 1020)))
           (is (= [(bucket 960 3 3 -3 5)] (rollup* cb Lt 60 0 BIG)))
           (is (= [] (rollup* cb Lt 3600 0 BIG)) "3600-bucket 0 incomplete at 1100")
           (advance* cb Lt 3600)
           (sync! cb)
           (is (= [(bucket 0 3 3 -3 5)] (rollup* ca Lt 3600 0 3600)))))

       (testing "bucket membership at width boundaries; 60 and 3600 widths independent"
         (let [W ["width" "m" {}]]
           (advance* ca W 3700)
           (ingest* ca W 3599 1)      ; 60-bucket 3540, 3600-bucket 0
           (ingest* ca W 3600 2)      ; 60-bucket 3600, 3600-bucket 3600
           (ingest* ca W 3659 3)      ; 60-bucket 3600
           (ingest* ca W 3660 4)      ; 60-bucket 3660
           (ingest* ca W 3700 5)      ; 60-bucket 3660 (open: 3720 > 3700)
           (sync! ca)
           (is (= [(bucket 3540 1 1 1 1) (bucket 3600 2 5 2 3)]
                  (rollup* cb W 60 0 BIG))
               "bucket 3660 is still open at clock 3700")
           (is (= [(bucket 0 1 1 1 1)] (rollup* cb W 3600 0 BIG))
               "3600-bucket 0 complete at 3700; bucket 3600 still open")
           (advance* cb W 3720)
           (sync! cb)
           (is (= [(bucket 3540 1 1 1 1) (bucket 3600 2 5 2 3) (bucket 3660 2 9 4 5)]
                  (rollup* ca W 60 3540 3720)))
           (is (= [(bucket 3600 2 5 2 3)] (rollup* ca W 60 3600 3660))
               "rollup range is half-open on bucket starts")
           (is (= [] (rollup* ca W 60 3720 3780)) "start == first incomplete bucket")
           (is (= [] (rollup* ca W 60 3600 3600)) "start == end")
           (is (= [(row 3599 1) (row 3600 2) (row 3659 3) (row 3660 4) (row 3700 5)]
                  (raw* ca W 3599 3701)))
           (is (= [(row 3600 2) (row 3659 3)] (raw* ca W 3600 3660)))
           (is (= [] (raw* ca W 3701 BIG)) "range entirely in the future")))

       (testing "negative, zero, and > 32-bit values aggregate exactly"
         (let [V ["vals" "m" {}]]
           (advance* cb V 100)
           (ingest* cb V 10 -4)
           (ingest* cb V 11 0)
           (ingest* cb V 12 5000000000)
           (ingest* cb V 70 -7)
           (sync! cb)
           (is (= [(bucket 0 3 4999999996 -4 5000000000)] (rollup* ca V 60 0 60)))
           (is (= [] (rollup* ca V 60 60 120)) "bucket 60 open at clock 100")
           (is (= [(row 10 -4) (row 11 0) (row 12 5000000000) (row 70 -7)]
                  (raw* ca V 0 BIG)))
           (advance* ca V 120)
           (sync! ca)
           (is (= [(bucket 60 1 -7 -7 -7)] (rollup* cb V 60 60 120))
               "a single negative sample sets min = max = value, never 0")))

       (testing "one huge advance takes buckets straight to expired; counters survive"
         (let [J ["jump" "m" {}]
               clock 1000000000000]
           (ingest* ca J 0 3)
           (sync! ca)
           (is (= [(row 0 3)] (raw* cb J 0 1)))
           (advance* ca J clock)
           (sync! ca)
           (is (= (assoc ZERO-INFO :clock clock :accepted 1) (info* cb J)))
           (is (= [] (raw* cb J 0 BIG)))
           (is (= [] (rollup* cb J 60 0 BIG)))
           (is (= [] (rollup* cb J 3600 0 BIG)))
           ;; the window has moved far away; old timestamps are expired now
           (ingest* cb J 0 9)
           (ingest* cb J (- clock 300) 1)
           (ingest* cb J (- clock 299) 2)
           (ingest* cb J (+ clock 1) 4)
           (sync! cb)
           (is (= {:clock clock :accepted 2 :rejected-future 1
                   :rejected-expired 2 :rejected-duplicate 0}
                  (info* ca J)))
           (is (= [(row (- clock 299) 2)] (raw* ca J 0 BIG)))
           ;; 10^12 mod 60 = 40, so clock-299 = 999999999701 lies in the 60-bucket
           ;; 999999999660, whose end 999999999720 <= clock: complete already.
           (is (= [(bucket 999999999660 1 2 2 2)] (rollup* ca J 60 0 BIG)))
           (is (= [] (rollup* ca J 60 999999999720 BIG)) "later slots are empty")
           (is (= [] (rollup* ca J 3600 0 BIG))
               "3600-bucket 999999997200 ends at 1000000000800 > clock: open")
           (advance* ca J (+ clock 60))
           (sync! ca)
           (is (= [(bucket 999999999660 1 2 2 2)] (rollup* cb J 60 0 (+ BIG 60))))
           (is (= [] (raw* cb J 0 (+ BIG 60)))
               "(clock-299) + 300 = clock+1 <= clock+60: raw copy expired, bucket kept")))

       (testing "64-bit timestamp limits do not overflow intermediate arithmetic"
         (let [S ["max-time" "m" {}]
               limit Long/MAX_VALUE]
           (advance* ca S limit)
           (sync! ca)
           (is (= [] (raw* cb S 0 1)))
           (ingest* cb S (- limit 300) 99)
           (ingest* cb S (- limit 299) -7)
           (ingest* cb S (dec limit) 11)
           (ingest* cb S limit 3)
           (sync! cb)
           (is (= (assoc ZERO-INFO :clock limit :accepted 3 :rejected-expired 1)
                  (info* ca S)))
           (is (= [(row (- limit 299) -7) (row (dec limit) 11)]
                  (raw* ca S 0 limit)))))

       (testing "same-series writes take effect in client order, even inside one sync phase"
         (let [O ["order" "m" {}]
               O2 ["order2" "m" {}]]
           (advance* cb O 500)
           (ingest* cb O 500 7)       ; judged against 500 -> accepted
           (ingest* cb O 500 9)       ; duplicate
           (advance* cb O 800)
           (ingest* cb O 500 1)       ; expired at 800
           (ingest* cb O 801 1)       ; future at 800
           (ingest* cb O 799 2)       ; accepted
           (advance* cb O 700)        ; stale, no-op
           (sync! cb)
           (is (= {:clock 800 :accepted 2 :rejected-future 1
                   :rejected-expired 1 :rejected-duplicate 1}
                  (info* ca O)))
           (is (= [(row 799 2)] (raw* ca O 0 BIG)))
           ;; reverse order: ingest before the advance is future and unreserved
           (ingest* ca O2 500 7)
           (advance* ca O2 500)
           (ingest* ca O2 500 8)
           (sync! ca)
           (is (= (assoc ZERO-INFO :clock 500 :accepted 1 :rejected-future 1) (info* cb O2)))
           (is (= [(row 500 8)] (raw* cb O2 500 501)))))

       (testing "a wrapper created after the writes sees the same state; second-wrapper write barrier"
         (let [X ["barrier" "x" {}]
               Y ["barrier" "y" {}]]
           ;; wrapper A does a large batch of work and synchronizes
           (advance* ca X 1000)
           (doseq [ts (range 701 1001)] (ingest* ca X ts ts))
           (sync! ca)
           (is (= (assoc ZERO-INFO :clock 1000 :accepted 300) (info* ca X)))
           ;; a brand-new wrapper must wait for ITS OWN writes even though the
           ;; cluster has already processed more records than it will append
           (let [cc (wrap-client ipc)]
             (is (= (assoc ZERO-INFO :clock 1000 :accepted 300) (info* cc X))
                 "fresh wrapper sees state written before it existed")
             (is (= 300 (count (raw* cc X 0 BIG))))
             (advance* cc Y 2000)
             (doseq [ts (range 1801 2001)] (ingest* cc Y ts 1))
             (sync! cc)
             (is (= (assoc ZERO-INFO :clock 2000 :accepted 200) (info* cc Y))
                 "wait-for-processing! on the new wrapper must cover its own writes")
             (is (= 200 (count (raw* cc Y 1801 2001))))
             (is (= (assoc ZERO-INFO :clock 2000 :accepted 200) (info* ca Y))
                 "and the older wrapper observes them too")
             ;; and the new wrapper's advance expires data written by A
             (advance* cc X 1001)
             (sync! cc)
             (is (= 299 (count (raw* ca X 0 BIG))))
             (is (= (row 702 702) (first (raw* cb X 0 BIG)))))))

       (testing "durable state survives a simulated worker restart (module update)"
         (let [R ["restart" "m" {}]]
           (advance* ca R 1000)
           (ingest* ca R 701 3)
           (ingest* ca R 1000 5)
           (ingest* ca R 700 4)
           (sync! ca)
           (rtest/update-module! ipc (:module (create-module-fn)))
           (is (= {:clock 1000 :accepted 2 :rejected-future 0
                   :rejected-expired 1 :rejected-duplicate 0}
                  (info* cb R)))
           (is (= [(row 701 3) (row 1000 5)] (raw* cb R 0 BIG)))
           (is (= [(bucket 660 1 3 3 3)] (rollup* cb R 60 0 BIG)))
           (advance* cb R 1001)
           (ingest* cb R 701 1)       ; expired now
           (ingest* cb R 1001 1)
           (sync! cb)
           (is (= {:clock 1001 :accepted 3 :rejected-future 0
                   :rejected-expired 2 :rejected-duplicate 0}
                  (info* ca R)))
           (is (= [(row 1000 5) (row 1001 1)] (raw* ca R 0 BIG)))))))))
