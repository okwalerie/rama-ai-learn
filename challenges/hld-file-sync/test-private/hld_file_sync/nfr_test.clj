(ns hld-file-sync.nfr-test
  "NFR tests: versioning, conflict copies and the journal apply exactly once
   under a forced stream retry, and change pages read each returned entry."
  (:require [clojure.test :refer [deftest is testing]]
            [com.rpl.rama.test :as rtest]
            [hld-file-sync.protocol :as p]
            [rama-challenges.harness :as harness]
            [rama-challenges.nfr :as nfr]))

(defn- exercise [tasks f]
  (let [{:keys [module wrap-client]}
        ((requiring-resolve 'hld-file-sync.module/create-module))]
    (with-open [ipc (rtest/create-ipc)]
      (rtest/launch-module! ipc module {:tasks tasks :threads tasks})
      (f (wrap-client ipc) (wrap-client ipc)))))

(defn- retried!
  "Runs command f and waits, failing the first stream completion so a stream
   design retries it. A microbatch design sees no forced failure."
  [c f]
  (nfr/failed-streaming #(do (f) (harness/wait-for-processing! c))))

(deftest forced-stream-retry-versions-exactly-once
  (doseq [tasks [2 4]]
    (testing (str tasks " tasks")
      (exercise tasks
        (fn [c r]
          (retried! r #(p/register-blocks! c "reg" "n" [{:hash "h1" :size 3} {:hash "h2" :size 4}]))
          (retried! r #(p/commit-file! c "c1" "n" "f" "/f" ["h1"] nil))
          (retried! r #(p/commit-file! c "c2" "n" "f" "/f" ["h1" "h2"] 1))
          (retried! r #(p/commit-file! c "stale" "n" "f" "/f" ["h2"] 1))
          (retried! r #(p/commit-file! c "stale" "n" "f" "/f" ["h2"] 1))
          (retried! r #(p/commit-file! c "c3" "n" "f" "/f" ["h2" "h2"] 2))
          (is (= [:accepted 2] ((juxt :status :registered) (p/get-outcome r "n" "reg"))))
          (is (= {:status :accepted :command :commit-file :file-id "~stale" :version 1 :seq 3
                  :size-bytes 4 :conflict-copy? true :conflict-of "f" :conflicting-attempts 0}
                 (p/get-outcome r "n" "stale")))
          (is (= [[1 "f" 1 "c1"] [2 "f" 2 "c2"] [3 "~stale" 1 "stale"] [4 "f" 3 "c3"]]
                 (mapv (juxt :seq :file-id :version :request-id) (p/get-changes r "n" 0 500)))
              "one journal entry per accepted commit, seqs contiguous, one conflict copy")
          (is (= [3 ["h2" "h2"] 8] ((juxt :version :blocklist :size-bytes) (p/get-file r "n" "f"))))
          (is (= [1 "/f (conflicted copy stale)"]
                 ((juxt :version :path) (p/get-file r "n" "~stale")))))))))

(deftest change-page-reads-each-returned-entry
  ;; A page must read its entries from a structure it can range over, not
  ;; deserialize the whole journal as one value (one read for any page).
  (doseq [tasks [2 4]]
    (testing (str tasks " tasks")
      (exercise tasks
        (fn [c r]
          (p/register-blocks! c "reg" "n" [{:hash "h" :size 1}])
          (doseq [i (range 300)]
            (p/commit-file! c (str "c" i) "n" (str "f" i) (str "/f" i) ["h"] nil))
          (harness/wait-for-processing! r)
          (doseq [limit [5 20 100]]
            (let [[page ops] (nfr/capture-rocks-ops-with-result #(p/get-changes r "n" 150 limit))
                  touched (+ (:reads ops) (:iterator-reads ops))]
              (is (= (vec (range 151 (+ 151 limit))) (mapv :seq page)))
              (is (<= limit touched (+ (* 2 limit) 8))
                  (str "limit " limit ": a page must read about one stored entry per returned change; "
                       ops)))))))))
