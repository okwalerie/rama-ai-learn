(ns biff-rama-adapter.nfr-test
  "NFR tests: reading an entity costs the same after many updates as after a
   few, and concurrent writers share commit work instead of queueing."
  (:require [clojure.test :refer [deftest is testing]]
            [biff-rama-adapter.test-support :as ts]
            [rama-challenges.nfr :as nfr]))

(defn- read-cost [node id]
  (nfr/capture-rocks-ops #(ts/entity node :doc id)))

(deftest entity-read-cost-is-independent-of-history
  (ts/with-cluster [ipc biff-module 2]
    (let [node (ts/start-system! ipc biff-module)]
      (try
        (dotimes [k 3] (ts/transact! node [[:put :doc {:doc/id :short :doc/v k}]]))
        (dotimes [k 30] (ts/transact! node [[:put :doc {:doc/id :long :doc/v k}]]))
        (is (= {:doc/id :long :doc/v 29} (ts/entity node :doc :long)))
        (let [short (read-cost node :short)
              long (read-cost node :long)
              cost #(+ (:reads %) (:iterators %) (:iterator-reads %))]
          (is (<= (cost long) (cost short))
              (str "reading after 30 updates cost " long " but after 3 cost " short)))
        (finally (ts/stop-system! node))))))

(deftest concurrent-transactions-share-commits
  (ts/with-cluster [ipc biff-module 4]
    (let [node (ts/start-system! ipc biff-module)]
      (try
        (ts/transact! node [[:put :doc {:doc/id :warmup}]])
        (let [time-ms (fn [f] (let [t (System/nanoTime)] (f) (/ (- (System/nanoTime) t) 1e6)))
              single (/ (time-ms #(dotimes [k 4] (ts/transact! node [[:put :doc {:doc/id [:seq k]}]]))) 4)
              n 32
              parallel (time-ms #(run! deref (doall (for [k (range n)]
                                                       (future (ts/transact! node [[:put :doc {:doc/id [:par k]}]]))))))]
          (is (every? #(= {:doc/id [:par %]} (ts/entity node :doc [:par %])) (range n)))
          (is (< parallel (* 0.5 n single))
              (str n " concurrent transactions took " (long parallel) " ms; one alone takes "
                   (long single) " ms")))
        (finally (ts/stop-system! node))))))
