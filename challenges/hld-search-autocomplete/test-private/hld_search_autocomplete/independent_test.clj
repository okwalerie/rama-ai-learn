(ns hld-search-autocomplete.independent-test
  (:require [clojure.test :refer :all]
            [com.rpl.rama.test :as rtest]
            [hld-search-autocomplete.module :as module]
            [hld-search-autocomplete.protocol :as p]
            [rama-challenges.harness :as harness]))

(defn capture [f]
  (let [counts (atom {:point 0 :iterator 0 :next 0 :writes 0})]
    (rtest/with-event-hook
      (fn [kind data]
        (case kind
          :rocks-read (swap! counts update :point inc)
          :rocks-iterator (swap! counts update :iterator inc)
          :rocks-iterator-read (swap! counts update :next inc)
          :rocks-commit (swap! counts update :writes + (:write-batch-count data))
          nil))
      (f))
    @counts))

(defn read-work [m] (+ (:point m) (:iterator m) (:next m)))
(defn write-work [m] (+ (read-work m) (:writes m)))

(defn bounded-comparison! [label small large metric]
  ;; Qualitative comparison, not a prescribed topology or exact RocksDB budget.
  ;; The large input is 10x the already-populated small input; a full scan
  ;; differs from bounded work without disallowing fixed-size page reads.
  (println "INDEPENDENT-WORK" label small "->" large)
  (is (pos? (metric small)) (str label " small operation observed " small))
  (is (< (metric large) (* 4 (max 1 (metric small))))
      (str label " work grew with unrelated state: " small " -> " large)))

(defn scenario [tasks]
  (let [{:keys [module wrap-client]} (module/create-module)]
    (with-open [ipc (rtest/create-ipc)]
      (rtest/launch-module! ipc module {:tasks tasks :threads tasks})
      (let [client (wrap-client ipc)
            sync! #(harness/wait-for-processing! client)
            ;; Distinct phrases share the measured prefix. The highest result
            ;; is unchanged as the corpus grows; fixed k and input each time.
            entries (mapv (fn [n] [(format "ab%04d" n) (long (- 10000 n))]) (range 3000))]
        (p/publish-snapshot! client "en" 1 (subvec entries 0 300))
        (sync!)
        (let [want [{:phrase "ab0000" :score 10000}]]
          (is (= want (p/suggest client "en" "ab" 1)))
          (let [small (capture #(is (= want (p/suggest client "en" "ab" 1))))]
            (p/publish-snapshot! client "en" 2 entries)
            (sync!)
            (bounded-comparison! (str tasks " tasks suggest") small
                                 (capture #(is (= want (p/suggest client "en" "ab" 1)))) read-work)))

        ;; Same phrase, different already-populated session history; duplicate
        ;; and new events probe membership and count update without scanning it.
        (p/publish-snapshot! client "fr" 1 [["cedar" 7]])
        (doseq [n (range 60)] (p/record-search! client "fr" 1 (str "session" n) "cedar"))
        (sync!)
        (let [small-read (capture #(is (= 60 (:sessions (p/get-phrase client "fr" "cedar")))))
              small-write (capture #(do (p/record-search! client "fr" 1 "new-small" "cedar") (sync!)))]
          (doseq [n (range 60 600)] (p/record-search! client "fr" 1 (str "session" n) "cedar"))
          (sync!)
          (bounded-comparison! (str tasks " tasks get-phrase") small-read
                               (capture #(is (= 601 (:sessions (p/get-phrase client "fr" "cedar")))))
                               read-work)
          (let [large-write (capture #(do (p/record-search! client "fr" 1 "new-large" "cedar") (sync!)))]
            (bounded-comparison! (str tasks " tasks search") small-write large-write write-work)
            (is (= {:generation 1 :in-corpus? true :base 7 :sessions 602
                    :score 6027 :blocked? false}
                   (p/get-phrase client "fr" "cedar")))))

        ;; Old-generation trend data must not be traversed by publication.
        ;; Both measured publications have the same single-entry payload.
        (p/publish-snapshot! client "de" 1 [["cedar" 7]])
        (doseq [n (range 20)]
          (p/record-search! client "de" 1 (str "s" n) (format "n%04d" n)))
        (sync!)
        (let [small (capture #(do (p/publish-snapshot! client "de" 2 [["cedar" 7]]) (sync!)))]
          (doseq [n (range 320)]
            (p/record-search! client "de" 2 (str "s" n) (format "n%04d" n)))
          (sync!)
          (let [large (capture #(do (p/publish-snapshot! client "de" 3 [["cedar" 7]]) (sync!)))]
            (bounded-comparison! (str tasks " tasks publish") small large write-work)
            (is (= [] (p/suggest client "de" "n" 10)))
            (is (= 3 (p/get-generation client "de")))))

        ;; Ingress distribution is diagnostic only. Depot placement does not
        ;; establish balanced PState storage or processing, and another valid
        ;; design could centralize ingress before partitioning its writes.
        (let [owners (atom #{})]
          (rtest/with-event-hook
            (fn [kind data]
              (when (and (= kind :depot-read) (some? (:task-id data)))
                (swap! owners conj (:task-id data))))
            (doseq [locale ["it" "es" "pt" "nl" "sv" "da" "fi" "no"
                            "pl" "cs" "hu" "ro" "tr" "el" "ja" "ko"]]
              (p/publish-snapshot! client locale 1 [["alpha" 1] ["zebra" 2]])
              (p/record-search! client locale 1 "x" "zebra"))
            (sync!))
          (println "INDEPENDENT-DEPOT-TASKS" tasks @owners))

        ;; An unchanged module update loses in-memory state but not policy,
        ;; session membership or the current generation. Fresh wrappers must
        ;; continue appending and observing writes after the update.
        (p/block-phrase! client "en" "ab0000")
        (p/record-search! client "en" 2 "persist" "ab0001")
        (sync!)
        (rtest/update-module! ipc module)
        (let [fresh (wrap-client ipc)]
          (is (= 2 (p/get-generation fresh "en")))
          (is (= {:generation 2 :in-corpus? true :base 9999 :sessions 1
                  :score 10009 :blocked? false}
                 (p/get-phrase fresh "en" "ab0001")))
          (is (= [{:phrase "ab0001" :score 10009}] (p/suggest fresh "en" "ab" 1)))
          (p/record-search! fresh "en" 2 "persist" "ab0001")
          (p/record-search! fresh "en" 2 "fresh" "ab0001")
          (p/unblock-phrase! fresh "en" "ab0000")
          (sync!)
          (is (= 2 (:sessions (p/get-phrase client "en" "ab0001"))))
          (is (= [{:phrase "ab0001" :score 10019}
                  {:phrase "ab0000" :score 10000}]
                 (p/suggest client "en" "ab" 2))))))))

(deftest independent-two-tasks (scenario 2))
(deftest independent-four-tasks (scenario 4))
