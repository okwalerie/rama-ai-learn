(ns hld-file-sync.performance-test-support
  "Private storage-work tests for hld-file-sync. RocksDB event hooks are
   captured around single operations after the fixture history has been
   written and barriered outside the capture. Bounds are loose ceilings that
   reject full scans of a namespace's own history (blocks, versions, journal,
   requests) while leaving room for any reasonable design: no exact counts,
   topology names, or partition schemes are asserted. Business correctness
   is asserted outside captures because query-topology results can be
   truncated under the hook. Event counts cannot detect a whole collection
   stored as one value; that limitation is covered only by the manual
   schema/path review in IMPLEMENTATION_VALIDATION.md."
  (:require
   [clojure.test :refer [is testing]]
   [com.rpl.rama.test :as rtest]
   [rama-challenges.harness :as harness]
   [hld-file-sync.protocol :as p]))

(defn capture-rocks-ops [f]
  (let [state (atom {})]
    (rtest/with-event-hook
      (fn [event-type data]
        (case event-type
          :rocks-read (swap! state update :rocks-read (fnil inc 0))
          :rocks-iterator (swap! state update :rocks-iterator (fnil inc 0))
          :rocks-iterator-read (swap! state update :rocks-iterator-read (fnil inc 0))
          :rocks-commit (swap! state update :rocks-writes
                               (fnil #(+ % (:write-batch-count data)) 0))
          nil))
      (f)
      @state)))

(defn- report!
  "Prints captured counts so a run leaves evidence of the actual storage work."
  [label info]
  (println "rocks-ops" label (pr-str info)))

(defn- reads [info] (+ (:rocks-read info 0) (:rocks-iterator-read info 0)))
(defn- writes [info] (:rocks-writes info 0))

(defn- blocks [prefix from to size]
  (mapv (fn [i] {:hash (str prefix i) :size size}) (range from to)))

(defn- write-history!
  "Registers `n-blocks` blocks (<= 1024 per call), commits `n-versions`
   chained versions of file \"f\" and `n-files` single-version files, all
   without intermediate barriers. Returns nil; the caller barriers once."
  [c ns n-blocks n-versions n-files]
  (doseq [[from to] (partition 2 1 (concat (range 0 n-blocks 500) [n-blocks]))]
    (p/register-blocks! c (str "reg" from) ns (blocks "b" from to 1)))
  (p/commit-file! c "v1" ns "f" "/f/1" ["b0"] nil)
  (doseq [i (range 2 (inc n-versions))]
    (p/commit-file! c (str "v" i) ns "f" (str "/f/" i) ["b0" "b1"] (long (dec i))))
  (doseq [i (range n-files)]
    (p/commit-file! c (str "g" i) ns (str "g" i) (str "/g/" i) ["b1"] nil)))

(defn- run-at
  [create-module-fn {:keys [tasks] :as cfg}]
  (let [{:keys [module wrap-client]} (create-module-fn)]
    (with-open [ipc (rtest/create-ipc)]
      (rtest/launch-module! ipc module cfg)
      (let [c (wrap-client ipc)
            sync! #(harness/wait-for-processing! c)
            small "small" big "big"
            small-versions 5 small-files 3
            big-versions 1200 big-files 300
            small-last-seq (+ small-versions small-files)
            big-last-seq (+ big-versions big-files)]
        (testing (str "fixture at " tasks " tasks")
          (write-history! c small 10 small-versions small-files)
          (write-history! c big 1500 big-versions big-files)
          (sync!)
          (is (= small-versions (:version (p/get-file c small "f"))))
          (is (= big-versions (:version (p/get-file c big "f"))))
          (is (= big-last-seq (:seq (p/get-file c big (str "g" (dec big-files))))))
          (is (= 1 (p/get-block-size c big "b1499"))))

        (testing "register-blocks! cost is bounded by the submitted list, not the block index"
          (let [new-blocks (fn [] (blocks "n" 0 5 9))
                s (capture-rocks-ops (fn [] (p/register-blocks! c "extra" small (new-blocks)) (sync!)))
                b (capture-rocks-ops (fn [] (p/register-blocks! c "extra" big (new-blocks)) (sync!)))]
            (report! "register small/big" [s b])
            (is (< (reads s) 80) (str "small register reads " s))
            (is (< (reads b) 80) (str "big register reads " b))
            (is (< (writes b) 60) (str "big register writes " b))
            (is (<= (reads b) (+ 30 (* 2 (reads s)))) (str "register must not scan the block index: " s " vs " b))
            (is (= 9 (p/get-block-size c big "n4")))
            (is (= 5 (:registered (p/get-outcome c big "extra"))))))

        (testing "commit-file! cost is bounded by the blocklist, not versions/journal/requests"
          (let [bl ["b0" "n1" "n2" "b1" "n1"]
                s (capture-rocks-ops
                   (fn [] (p/commit-file! c "late" small "f" "/late" bl (long small-versions)) (sync!)))
                b (capture-rocks-ops
                   (fn [] (p/commit-file! c "late" big "f" "/late" bl (long big-versions)) (sync!)))]
            (report! "commit small/big" [s b])
            (is (< (reads s) 100) (str "small commit reads " s))
            (is (< (reads b) 100) (str "big commit reads " b))
            (is (< (writes b) 60) (str "big commit writes " b))
            (is (<= (reads b) (+ 30 (* 2 (reads s)))) (str "commit must not scan history: " s " vs " b))
            (is (= {:status :accepted :command :commit-file :conflicting-attempts 0
                    :file-id "f" :version (inc big-versions) :seq (inc big-last-seq)
                    :size-bytes 29 :conflict-copy? false :conflict-of nil}
                   (p/get-outcome c big "late")))
            (is (= (inc big-versions) (:version (p/get-file c big "f"))))))

        (testing "a stale commit (conflict copy) and a rejected commit are equally bounded"
          (let [copy (capture-rocks-ops
                      (fn [] (p/commit-file! c "stale" big "f" "/stale" ["b0"] 1) (sync!)))
                rej (capture-rocks-ops
                     (fn [] (p/commit-file! c "rej" big "f" "/rej" ["zz" "b0" "yy" "zz"] 3) (sync!)))
                replay (capture-rocks-ops
                        (fn [] (p/commit-file! c "late" big "f" "/late" ["b0" "n1" "n2" "b1" "n1"]
                                               (long big-versions)) (sync!)))]
            (report! "copy/rejected/replay" [copy rej replay])
            (is (< (reads copy) 100) (str "conflict copy reads " copy))
            (is (< (writes copy) 60) (str "conflict copy writes " copy))
            (is (< (reads rej) 100) (str "rejected commit reads " rej))
            (is (< (writes rej) 30) (str "rejected commit writes " rej))
            (is (< (reads replay) 60) (str "replay reads " replay))
            (is (< (writes replay) 30) (str "replay writes " replay))
            (is (= "f" (:conflict-of (p/get-file c big "~stale"))))
            (is (= (+ 2 big-last-seq) (:seq (p/get-file c big "~stale"))))
            (is (= ["zz" "yy"] (:need-blocks (p/get-outcome c big "rej"))))
            (is (= (inc big-versions) (:version (p/get-file c big "f"))))
            (is (= 0 (:conflicting-attempts (p/get-outcome c big "late"))))))

        (testing "point reads stay constant as the namespace's own history grows"
          (let [gf (capture-rocks-ops (fn [] (p/get-file c big "f")))
                gv (capture-rocks-ops (fn [] (p/get-file-version c big "f" 600)))
                go (capture-rocks-ops (fn [] (p/get-outcome c big "v3")))
                gb (capture-rocks-ops (fn [] (p/get-block-size c big "b1499")))
                gm (capture-rocks-ops (fn [] (p/get-file c big "absent")))]
            (doseq [[label info] [["get-file" gf] ["get-file-version" gv]
                                  ["get-outcome" go] ["get-block-size" gb] ["get-file absent" gm]]]
              (report! label info)
              (is (< (reads info) 25) (str label " must be a constant number of reads: " info))
              (is (< (:rocks-iterator-read info 0) 10) (str label " must not iterate history: " info)))
            (is (= 600 (:version (p/get-file-version c big "f" 600))))
            (is (= "v600" (:request-id (p/get-file-version c big "f" 600))))
            (is (= (inc big-versions) (:version (p/get-file c big "f"))))
            (is (= 3 (:seq (p/get-outcome c big "v3"))))))

        (testing "get-changes cost is proportional to limit, not to after-seq or journal length"
          (let [shallow (capture-rocks-ops (fn [] (p/get-changes c big 0 50)))
                deep (capture-rocks-ops (fn [] (p/get-changes c big 1400 50)))
                page (capture-rocks-ops (fn [] (p/get-changes c big 900 500)))
                tail-small (capture-rocks-ops (fn [] (p/get-changes c small (+ 50 small-last-seq) 50)))
                tail-big (capture-rocks-ops (fn [] (p/get-changes c big (+ 5 big-last-seq) 50)))
                tail-500 (capture-rocks-ops (fn [] (p/get-changes c big (+ 5 big-last-seq) 500)))]
            (report! "changes shallow/deep/500-page/tail-small-50/tail-big-50/tail-big-500"
                     [shallow deep page tail-small tail-big tail-500])
            (is (< (reads shallow) 180) (str "shallow page " shallow))
            (is (< (reads deep) 180) (str "deep page " deep))
            (is (<= (reads deep) (+ 30 (* 2 (reads shallow)))) (str "deep page must not scan from seq 1: " shallow " vs " deep))
            (is (< (reads page) 1600) (str "500-entry page " page))
            ;; An empty page is bounded by the requested limit (a point lookup per
            ;; candidate seq is a valid O(limit) design), never by the journal.
            (is (< (reads tail-small) (+ 40 (* 2 50))) (str "empty tail, small namespace " tail-small))
            (is (< (reads tail-big) (+ 40 (* 2 50))) (str "empty tail, big namespace " tail-big))
            (is (<= (reads tail-big) (+ 30 (* 2 (reads tail-small))))
                (str "an empty tail page must not depend on journal length: " tail-small " vs " tail-big))
            (is (< (reads tail-500) (+ 40 (* 2 500))) (str "empty tail at limit 500 " tail-500))
            (let [entries (p/get-changes c big 1400 50)]
              (is (= (range 1401 1451) (map :seq entries)))
              (is (= (str "g" (- 1401 1201)) (:file-id (first entries)))))
            (is (= 500 (count (p/get-changes c big 900 500))))
            (is (= [] (p/get-changes c small (+ 50 small-last-seq) 50)))
            (is (= [] (p/get-changes c big (+ 5 big-last-seq) 500)))
            (is (= [] (p/get-changes c big Long/MAX_VALUE 500)))))

        (testing "independent namespaces keep independent block indexes"
          ;; The per-task depot-read distribution is printed as a diagnostic only:
          ;; the README does not constrain the ingress topology or partition scheme.
          (let [state (atom {})
                nss (mapv #(str "ns-" %) (range (* 2 tasks)))
                size-of (fn [i] (long (inc i)))]
            (rtest/with-event-hook
              (fn [event-type data]
                (when (= event-type :depot-read)
                  (swap! state update (:task-id data) (fnil inc 0))))
              (doseq [[i n] (map-indexed vector nss)]
                (p/register-blocks! c "d1" n [{:hash "x" :size (size-of i)}]))
              (sync!))
            (report! "depot-read tasks (diagnostic only)" @state)
            (doseq [[i n] (map-indexed vector nss)]
              (is (= (size-of i) (p/get-block-size c n "x")) (str "size of x in " n))
              (is (= 1 (:registered (p/get-outcome c n "d1"))) (str "outcome in " n)))
            (is (nil? (p/get-block-size c "ns-none" "x")))
            (is (= 1 (p/get-block-size c big "b1"))
                "the fixture namespace is untouched by registrations elsewhere")))

        (testing "register-blocks! and commit-file! at the 1024-entry input maximum stay proportional to the input"
          (let [wide (blocks "w" 0 1024 2)
                hashes (mapv :hash wide)
                head (inc big-versions)
                r (capture-rocks-ops (fn [] (p/register-blocks! c "wide" big wide) (sync!)))
                cm (capture-rocks-ops
                    (fn [] (p/commit-file! c "wide-c" big "f" "/wide" hashes (long head)) (sync!)))]
            (report! "1024-entry register/commit" [r cm])
            (is (< (reads r) (+ 100 (* 3 1024))) (str "1024-block register reads " r))
            (is (< (writes r) (+ 100 (* 2 1024))) (str "1024-block register writes " r))
            (is (< (reads cm) (+ 100 (* 3 1024))) (str "1024-hash commit reads " cm))
            (is (< (writes cm) 60) (str "1024-hash commit writes " cm))
            (is (= 1024 (:registered (p/get-outcome c big "wide"))))
            (is (= 2 (p/get-block-size c big "w1023")))
            (is (= {:status :accepted :command :commit-file :conflicting-attempts 0
                    :file-id "f" :version (inc head) :seq (+ 3 big-last-seq)
                    :size-bytes 2048 :conflict-copy? false :conflict-of nil}
                   (p/get-outcome c big "wide-c")))
            (is (= hashes (:blocklist (p/get-file-version c big "f" (inc head)))))))))))

(defn test-module-performance
  "Runs the storage-work checks at 2 tasks and again at 4 tasks (two workers)."
  [create-module-fn]
  (run-at create-module-fn {:tasks 2 :threads 2})
  (run-at create-module-fn {:tasks 4 :threads 2 :workers 2}))
