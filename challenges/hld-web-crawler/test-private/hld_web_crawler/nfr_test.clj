(ns hld-web-crawler.nfr-test
  "NFR tests: frontier writes apply exactly once under a forced stream retry;
   discover!, complete! and the point reads do not grow with the host's queue;
   list-pending pages read each returned URL rather than one stored value."
  (:require [clojure.test :refer :all]
            [com.rpl.rama.test :as rtest]
            [hld-web-crawler.protocol :as p]
            [rama-challenges.harness :as h]
            [rama-challenges.nfr :as nfr]))

(defn- exercise [tasks f]
  (let [{:keys [module wrap-client]}
        ((requiring-resolve 'hld-web-crawler.module/create-module))]
    (with-open [ipc (rtest/create-ipc)]
      (rtest/launch-module! ipc module {:tasks tasks :threads tasks})
      (f (wrap-client ipc) (wrap-client ipc)))))

(defn- url [host path] (str "https://" host path))

(defn- retried!
  "Runs write f and waits, failing the first stream completion so a stream
   design retries it. A microbatch design sees no forced failure."
  [c f]
  (nfr/failed-streaming #(do (f) (h/wait-for-processing! c))))

(deftest forced-stream-retry-applies-writes-exactly-once
  (doseq [tasks [2 4]]
    (testing (str tasks " tasks")
      (exercise tasks
        (fn [c other]
          (let [host "retry.example"
                urls (mapv #(url host (str "/p" %)) (range 5))]
            (retried! other #(p/discover! c (conj urls (url host "/x"))))
            (retried! other #(p/discover! c urls))
            (is (= 6 (:queued (p/get-host other host)))
                "a retried discover must count each new URL once")
            (retried! other #(p/set-host-policy! c host 1 [{:path-prefix "/x" :allow? false}]))
            (retried! other #(p/claim! c host "c1" 10))
            (retried! other #(p/claim! c host "c1" 10))
            (is (= {:status :granted :url (urls 0) :fence 1 :lease-expires-at 40}
                   (p/get-claim other host "c1")))
            (retried! other #(p/complete! c host 1 :fetched 11))
            (retried! other #(p/claim! c host "c2" 12))
            (is (= {:status :granted :url (urls 1) :fence 2 :lease-expires-at 42}
                   (p/get-claim other host "c2"))
                "a retried claim must not burn a fence")
            (is (= :done (:status (p/get-url other (urls 0)))))
            (is (= {:host host :delay 1 :rules [{:path-prefix "/x" :allow? false}]
                    :queued 4 :fence 2 :last-claim-at 12
                    :lease {:url (urls 1) :fence 2 :expires-at 42}}
                   (p/get-host other host)))
            (is (= (conj (subvec urls 1) (url host "/x")) (p/list-pending other host nil 10)))))))))

(defn- grow!
  "Queues (from, to] more URLs on host in batches of 100."
  [c other host from to]
  (doseq [batch (partition-all 100 (range from to))]
    (p/discover! c (mapv #(url host (format "/q%06d" %)) batch)))
  (h/wait-for-processing! other))

(defn- sample [c other host tag]
  (let [measure-write (fn [f] (nfr/capture-rocks-ops #(do (f) (h/wait-for-processing! other))))
        measure-read (fn [f] (nfr/capture-rocks-ops f))
        fresh (mapv #(url host (str "/fresh-" tag "-" %)) (range 100))
        claim-id (str "claim-" tag)
        discover (measure-write #(p/discover! c fresh))
        _ (p/claim! c host claim-id 1000)
        _ (h/wait-for-processing! other)
        {:keys [fence] :as granted} (p/get-claim other host claim-id)
        complete (measure-write #(p/complete! c host fence :fetched 1001))]
    (is (= :granted (:status granted)))
    (is (= :done (:status (p/get-url other (:url granted)))) "the measured completion took effect")
    (is (= :queued (:status (p/get-url other (fresh 99)))))
    {:discover discover
     :complete complete
     :get-host (measure-read #(p/get-host other host))
     :get-url (measure-read #(p/get-url other (fresh 50)))
     :get-claim (measure-read #(p/get-claim other host claim-id))}))

(deftest discover-complete-and-point-reads-bounded-by-input
  (doseq [tasks [2 4]]
    (testing (str tasks " tasks")
      (exercise tasks
        (fn [c other]
          (let [host "hot.example"]
            (grow! c other host 0 520)
            (let [small (sample c other host "small")]
              (grow! c other host 520 1300)
              (let [large (sample c other host "large")]
                (println "Crawler measured work" tasks "tasks" small "->" large)
                (is (= (+ 1300 200 -2) (:queued (p/get-host other host))))
                (doseq [op [:discover :complete]]
                  (is (pos? (get-in small [op :writes])) (str op " measured no writes")))
                (doseq [op (keys small)
                        metric [:reads :iterators :iterator-reads :writes]]
                  (let [before (get-in small [op metric])
                        after (get-in large [op metric])]
                    (is (<= after (+ 24 (* 2 before)))
                        (str op " " metric " grew with the host's queue: " before " -> " after))))
                (doseq [op [:get-host :get-url :get-claim]]
                  (is (zero? (get-in large [op :writes])) (str op " wrote state")))))))))))

(deftest list-pending-reads-each-returned-url
  ;; A page must read its URLs from a structure it can range over, not
  ;; deserialize the whole pending set as one value (one read for any page).
  (doseq [tasks [2 4]]
    (testing (str tasks " tasks")
      (exercise tasks
        (fn [c other]
          (let [host "pages.example"
                queued (mapv #(url host (format "/q%06d" %)) (range 600))]
            (grow! c other host 0 600)
            (doseq [limit [5 20 100]]
              (let [[page ops] (nfr/capture-rocks-ops-with-result
                                #(p/list-pending other host (queued 299) limit))
                    touched (+ (:reads ops) (:iterator-reads ops))]
                (is (= (subvec queued 300 (+ 300 limit)) page))
                (is (<= limit touched (+ (* 3 limit) 16))
                    (str "limit " limit ": a page must read about one stored entry per returned URL; "
                         ops))))))))))
