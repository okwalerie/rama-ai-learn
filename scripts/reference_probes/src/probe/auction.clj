(ns probe.auction
  (:require [com.rpl.rama.test :as rtest]
            [rama-challenges.harness :as h]
            [rama-challenges.shared :as shared]
            [auction-module.protocol :as p])
  (:import [com.rpl.rama.helpers TopologyUtils]))

(defn fake-id [real]
  (if (record? real)
    (assoc real (first (remove #{:user-id} (keys real))) (java.util.UUID/randomUUID))
    (java.util.UUID/randomUUID)))

(defn run []
  (with-redefs [shared/REPLACE-TICK-DEPOTS true]
    (with-open [_ (TopologyUtils/startSimTime)]
      (let [{:keys [module wrap-client]} ((requiring-resolve 'auction-module.module/create-module))]
        (with-open [ipc (rtest/create-ipc)]
          (rtest/launch-module! ipc module {:tasks 4 :threads 2})
          (let [c (wrap-client ipc)
                settle #(do (h/wait-for-processing! c) (Thread/sleep 300))
                now (TopologyUtils/currentTimeMillis)
                l1 (p/list-item! c 1 "late" (+ now 1000))
                l2 (p/list-item! c 1 "rebid" (+ now 100000))]
            (settle)
            ;; 1. bid after expiration time, before expiration processing
            (TopologyUtils/advanceSimTime 2000)
            (p/bid! c 7 l1 10)
            (settle)
            (p/process-expirations! c)
            (prn {:probe :bid-after-expiry :highest (p/get-highest-bid c l1)
                  :seller-notes (p/get-notifications c 1) :bidder-notes (p/get-notifications c 7)})
            ;; 2. bid on a listing that does not exist
            (let [f (fake-id l2)]
              (p/bid! c 8 f 10) (settle)
              (prn {:probe :bid-on-missing-listing :bids (p/get-bids c f) :highest (p/get-highest-bid c f)}))
            ;; 3. lower re-bid by same bidder
            (p/bid! c 9 l2 20) (settle)
            (p/bid! c 9 l2 10) (settle)
            (prn {:probe :lower-rebid :bids (p/get-bids c l2) :highest (p/get-highest-bid c l2)})
            ;; 4. seller bids on own listing; non-positive bid
            (p/bid! c 1 l2 50) (settle)
            (p/bid! c 10 l2 -5) (settle)
            (prn {:probe :self-and-negative-bid :bids (p/get-bids c l2) :highest (p/get-highest-bid c l2)})))))))

(defn -main [& _] (run) (shutdown-agents) (System/exit 0))
