(ns hld-search-autocomplete.private-test
  (:require [clojure.test :refer :all]
            [com.rpl.rama.test :as rtest]
            [hld-search-autocomplete.module :as module]
            [hld-search-autocomplete.protocol :as p]
            [rama-challenges.harness :as harness]))

;; The oracle deliberately stores only the current snapshot and current-generation
;; sessions. It derives ranking from these, rather than mirroring the reference's
;; persistent index, placement, or encoding.
(defn step [state [kind locale generation a b]]
  (case kind
    :publish (if (> generation (get-in state [locale :generation] 0))
               (-> state
                   (assoc-in [locale :generation] generation)
                   (assoc-in [locale :bases] (into {} a))
                   (assoc-in [locale :sessions] {}))
               state)
    :search (if (= generation (get-in state [locale :generation]))
              (update-in state [locale :sessions b] (fnil conj #{}) a)
              state)
    :block (update-in state [locale :blocked] (fnil conj #{}) a)
    :unblock (update-in state [locale :blocked] disj a)))

(defn issue! [client [kind locale generation a b]]
  (case kind
    :publish (p/publish-snapshot! client locale generation a)
    :search (p/record-search! client locale generation a b)
    :block (p/block-phrase! client locale a)
    :unblock (p/unblock-phrase! client locale a)))

(defn expected-phrase [state locale phrase]
  (let [{:keys [generation bases sessions blocked]} (get state locale)
        base (long (get bases phrase 0))
        count (long (count (get sessions phrase)))]
    {:generation generation :in-corpus? (contains? bases phrase)
     :base base :sessions count :score (+ base (* 10 count))
     :blocked? (boolean (contains? blocked phrase))}))

(defn expected-suggest [state locale prefix k]
  (let [{:keys [bases sessions blocked]} (get state locale)]
    (->> (concat (keys bases) (keys sessions)) distinct
         (filter #(and (.startsWith ^String % prefix) (not (contains? blocked %))))
         (map #(select-keys (assoc (expected-phrase state locale %) :phrase %)
                            [:phrase :score]))
         (sort-by (juxt (comp - :score) :phrase))
         (take k) vec)))

(defn check-state! [client state locale phrases prefixes]
  (is (= (get-in state [locale :generation]) (p/get-generation client locale))
      (str locale " generation"))
  (doseq [phrase phrases]
    (is (= (expected-phrase state locale phrase) (p/get-phrase client locale phrase))
        (str locale " phrase " phrase)))
  (doseq [prefix prefixes k [1 3 10]]
    (is (= (expected-suggest state locale prefix k) (p/suggest client locale prefix k))
        (str locale " prefix " prefix " k=" k))))

(defn run-scenario [tasks]
  (let [{:keys [module wrap-client]} (module/create-module)
        model (atom {})]
    (with-open [ipc (rtest/create-ipc)]
      (rtest/launch-module! ipc module {:tasks tasks :threads tasks})
      (let [first-client (wrap-client ipc)
            second-client (wrap-client ipc)
            apply! (fn [client events]
                     (doseq [event events]
                       (is (nil? (issue! client event)) (str "write returns nil " event))
                       (swap! model step event)))]
        ;; A second wrapper must see the first wrapper's returned writes after
        ;; a barrier. No test assumes ordering for concurrent clients.
        (apply! first-client [[:block "en" nil "apple" nil]
                              [:search "en" 1 "discard" "novel"]
                              [:publish "en" 2 [["apple" 5] ["apex" 15]
                                                 ["a b" 25] ["atlas" 15]] nil]
                              [:search "en" 2 "s1" "apple"]
                              [:search "en" 2 "s1" "apple"]
                              [:search "en" 1 "old" "apex"]
                              [:search "en" 3 "future" "novel"]
                              [:search "en" 2 "s2" "novel"]
                              [:unblock "en" nil "apple" nil]
                              [:search "en" 2 "s2" "apple"]])
        (harness/wait-for-processing! second-client)
        (check-state! second-client @model "en"
                      ["apple" "apex" "a b" "atlas" "novel" "missing"]
                      ["a" "ap" "apple" "z"])
        ;; Equal generation with different body must not replace or reset.
        ;; Blocked candidates keep counts and reveal their final score on
        ;; unblocking. Empty publish advances generation, dropping all prior
        ;; corpus and trend without dropping policy.
        (apply! second-client [[:block "en" nil "novel" nil]
                               [:search "en" 2 "s3" "novel"]
                               [:publish "en" 2 [["wrong" 900]] nil]
                               [:publish "en" 1 [["stale" 900]] nil]])
        (harness/wait-for-processing! first-client)
        (check-state! first-client @model "en"
                      ["apple" "apex" "novel" "wrong" "stale"]
                      ["a" "ap" "novel" "wrong"])
        (apply! second-client [[:publish "en" 3 [] nil]])
        (harness/wait-for-processing! first-client)
        (check-state! first-client @model "en"
                      ["apple" "apex" "novel"] ["a" "ap" "novel"])
        (apply! second-client [[:search "en" 2 "old" "novel"]
                               [:search "en" 4 "early" "novel"]
                               [:search "en" 3 "early" "novel"]
                               [:search "en" 3 "early" "novel"]])
        (harness/wait-for-processing! first-client)
        (check-state! first-client @model "en" ["novel"] ["novel"])
        (apply! second-client [[:unblock "en" nil "novel" nil]])
        (harness/wait-for-processing! first-client)
        (check-state! first-client @model "en" ["novel"] ["novel"])
        (apply! second-client [[:block "en" nil "apple" nil]
                               [:publish "en" 4 [["apple" 1] ["apex" 10]] nil]
                               [:search "en" 4 "s1" "apple"]
                               [:search "en" 4 "s1" "apex"]
                               [:unblock "en" nil "apple" nil]])
        (harness/wait-for-processing! first-client)
        (check-state! first-client @model "en"
                      ["apple" "apex" "novel" "wrong" "stale"]
                      ["a" "ap" "apple" "novel"])
        (let [long-phrase (apply str (repeat 64 "q"))
              long-session (apply str (repeat 64 "S"))]
          (apply! first-client [[:search "en" 4 long-session long-phrase]])
          (harness/wait-for-processing! second-client)
          (check-state! second-client @model "en" [long-phrase]
                        ["q" "qq" long-phrase]))
        ;; Distinct locale, same phrases/session IDs. All hot-prefix ranks
        ;; are derived independently; a wrong fixed-size top-10 cache cannot
        ;; refill after blocking the first ten.
        (let [entries (mapv (fn [n] [(str "a" (format "%04d" n)) (long (- 2000 n))])
                            (range 600))
              blocked (mapv (fn [n] [:block "fr" nil (str "a" (format "%04d" n)) nil])
                            (range 12))]
          (apply! first-client (into [[:publish "fr" 1 entries nil]] blocked))
          (harness/wait-for-processing! first-client)
          (check-state! second-client @model "fr"
                        ["a0000" "a0011" "a0012" "a0599" "apple"]
                        ["a" "a0" "a001" "a059" "z"])
        (check-state! second-client @model "zz" ["apple"] ["a" "z"]))))))

(deftest two-tasks (run-scenario 2))
(deftest four-tasks (run-scenario 4))
