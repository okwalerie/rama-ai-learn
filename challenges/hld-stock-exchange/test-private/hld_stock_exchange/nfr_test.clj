(ns hld-stock-exchange.nfr-test
  "NFR tests: matching, trades and cancels apply exactly once under a forced
   stream retry, and trade pages and depth read each returned entry rather
   than one stored value."
  (:require [clojure.test :refer :all]
            [com.rpl.rama.test :as rtest]
            [hld-stock-exchange.protocol :as p]
            [rama-challenges.harness :as harness]
            [rama-challenges.nfr :as nfr]))

(defn- run-book [tasks f]
  (let [{:keys [module wrap-client]}
        ((requiring-resolve 'hld-stock-exchange.module/create-module))]
    (with-open [ipc (rtest/create-ipc)]
      (rtest/launch-module! ipc module {:tasks tasks :threads tasks})
      (f (wrap-client ipc) (wrap-client ipc)))))

(defn- retried!
  "Runs command f and waits, failing the first stream completion so a stream
   design retries it. A microbatch design sees no forced failure."
  [c f]
  (nfr/failed-streaming #(do (f) (harness/wait-for-processing! c))))

(deftest forced-stream-retry-trades-exactly-once
  (doseq [tasks [2 4]]
    (testing (str tasks " tasks")
      (run-book tasks
        (fn [a b]
          (retried! b #(p/submit-limit-order! a "s1" "X" "alice" :sell 100 5 :gtc))
          (retried! b #(p/submit-limit-order! a "s2" "X" "bob" :sell 100 3 :gtc))
          (retried! b #(p/submit-limit-order! a "s3" "X" "carol" :sell 101 4 :gtc))
          (retried! b #(p/submit-limit-order! a "b1" "X" "dave" :buy 101 10 :gtc))
          (retried! b #(p/submit-limit-order! a "b1" "X" "dave" :buy 101 10 :gtc))
          (retried! b #(p/submit-limit-order! a "b1" "X" "dave" :buy 101 11 :gtc))
          (is (= [[1 "s1" 5 100] [2 "s2" 3 100] [3 "s3" 2 101]]
                 (mapv (juxt :seq :maker-order-id :qty :price) (p/get-trades b "X" 0 500)))
              "one trade per fill, seqs contiguous")
          (is (= {:status :accepted :command :submit-limit-order :order-id "b1"
                  :filled-qty 10 :resting-qty 0 :cancelled-qty 0 :trade-count 3
                  :first-trade-seq 1 :last-trade-seq 3 :conflicting-attempts 1}
                 (dissoc (p/get-outcome b "X" "b1") :seq)))
          (is (= [{:price 101 :qty 2 :order-count 1}] (p/get-depth b "X" :sell 50)))
          (retried! b #(p/cancel-order! a "c3" "X" "s3" "carol"))
          (retried! b #(p/cancel-order! a "c3-again" "X" "s3" "carol"))
          (is (= [:accepted 2] ((juxt :status :cancelled-qty) (p/get-outcome b "X" "c3"))))
          (is (= :order-not-open (:reason (p/get-outcome b "X" "c3-again"))))
          (is (= [] (p/get-depth b "X" :sell 50)))
          (is (= [:cancelled 2 2] ((juxt :state :filled-qty :cancelled-qty) (p/get-order b "X" "s3"))))
          (retried! b #(p/submit-limit-order! a "b2" "X" "dave" :buy 99 1 :ioc))
          (is (= [:accepted 0 1] ((juxt :status :resting-qty :cancelled-qty) (p/get-outcome b "X" "b2"))))
          (is (= 3 (count (p/get-trades b "X" 0 500)))))))))

(deftest trade-pages-and-depth-read-each-returned-entry
  ;; Trades and levels must come from structures the query can range over,
  ;; not from a trade log or order book deserialized as one value (one read
  ;; for any page).
  (doseq [tasks [2 4]]
    (testing (str tasks " tasks")
      (run-book tasks
        (fn [a b]
          (doseq [i (range 300)]
            (p/submit-limit-order! a (str "s" i) "X" "maker" :sell (+ 1000 i) 1 :gtc))
          (p/submit-limit-order! a "sweep" "X" "taker" :buy 1199 200 :ioc)
          (harness/wait-for-processing! b)
          (doseq [limit [5 20 100]]
            (let [[page ops] (nfr/capture-rocks-ops-with-result #(p/get-trades b "X" 90 limit))
                  touched (+ (:reads ops) (:iterator-reads ops))]
              (is (= (vec (range 91 (+ 91 limit))) (mapv :seq page)))
              (is (<= limit touched (+ (* 2 limit) 8))
                  (str "limit " limit ": a trade page must read about one stored entry per trade; " ops))))
          (doseq [levels [5 20 50]]
            (let [[depth ops] (nfr/capture-rocks-ops-with-result #(p/get-depth b "X" :sell levels))
                  touched (+ (:reads ops) (:iterator-reads ops))]
              (is (= (mapv #(hash-map :price (+ 1200 %) :qty 1 :order-count 1) (range levels))
                     depth))
              (is (<= levels touched (+ (* 3 levels) 8))
                  (str levels " levels: depth must read about one stored entry per level; " ops)))))))))
