(ns hld-search-autocomplete.nfr-test
  "NFR tests: trend counts apply exactly once under a forced stream retry, and
   suggest ranks by score without scanning matching phrases, even when the
   best phrases sort lexically last or when many top phrases are blocked."
  (:require [clojure.test :refer [deftest is testing]]
            [com.rpl.rama.test :as rtest]
            [hld-search-autocomplete.protocol :as p]
            [rama-challenges.harness :as harness]
            [rama-challenges.nfr :as nfr]))

(defn- exercise [tasks f]
  (let [{:keys [module wrap-client]}
        ((requiring-resolve 'hld-search-autocomplete.module/create-module))]
    (with-open [ipc (rtest/create-ipc)]
      (rtest/launch-module! ipc module {:tasks tasks :threads tasks})
      (f (wrap-client ipc) (wrap-client ipc)))))

(defn- retried!
  "Runs write f and waits, failing the first stream completion so a stream
   design retries it. A microbatch design sees no forced failure."
  [c f]
  (nfr/failed-streaming #(do (f) (harness/wait-for-processing! c))))

(deftest forced-stream-retry-counts-each-session-once
  (doseq [tasks [2 4]]
    (testing (str tasks " tasks")
      (exercise tasks
        (fn [c r]
          (retried! r #(p/publish-snapshot! c "en" 1 [["apple pie" 5] ["apricot" 30] ["banana" 1]]))
          (retried! r #(p/record-search! c "en" 1 "s1" "apple pie"))
          (retried! r #(p/record-search! c "en" 1 "s1" "apple pie"))
          (retried! r #(p/record-search! c "en" 1 "s2" "apple pie"))
          (retried! r #(p/record-search! c "en" 1 "s1" "avocado"))
          (is (= {:generation 1 :in-corpus? true :base 5 :sessions 2 :score 25 :blocked? false}
                 (p/get-phrase r "en" "apple pie"))
              "a retried search counts its session once")
          (let [want [{:phrase "apricot" :score 30} {:phrase "apple pie" :score 25}
                      {:phrase "avocado" :score 10}]]
            (is (= want (p/suggest r "en" "a" 10)) "one-character prefix agrees with get-phrase")
            (is (= (subvec want 1 2) (p/suggest r "en" "app" 10)))
            (is (= (subvec want 1 2) (p/suggest r "en" "apple pie" 10))))
          (retried! r #(p/block-phrase! c "en" "apricot"))
          (is (= [{:phrase "apple pie" :score 25} {:phrase "avocado" :score 10}]
                 (p/suggest r "en" "a" 10)))
          (retried! r #(p/publish-snapshot! c "en" 2 [["apricot" 50] ["apple pie" 1]]))
          (retried! r #(p/record-search! c "en" 1 "s9" "apple pie"))
          (retried! r #(p/record-search! c "en" 2 "s1" "apple pie"))
          (retried! r #(p/unblock-phrase! c "en" "apricot"))
          (is (= [{:phrase "apricot" :score 50} {:phrase "apple pie" :score 11}]
                 (p/suggest r "en" "ap" 10)))
          (is (= [2 1] ((juxt :generation :sessions) (p/get-phrase r "en" "apple pie")))))))))

(defn- corpus
  "n phrases ab0000.. whose base increases with lexical order, so the best
   matches for \"a\" and \"ab\" sort lexically last."
  [n]
  (mapv (fn [i] [(format "ab%04d" i) (long (inc i))]) (range n)))

(defn- expected-top [n k blocked]
  (->> (range (dec n) -1 -1)
       (remove #(< (- n 1 blocked) % n))
       (take k)
       (mapv (fn [i] {:phrase (format "ab%04d" i) :score (long (inc i))}))))

(defn- suggest-work [r prefix k want]
  (let [[result ops] (nfr/capture-rocks-ops-with-result #(p/suggest r "en" prefix k))]
    (is (= want result) (str "suggest " prefix " " k))
    (+ (:reads ops) (:iterators ops) (:iterator-reads ops))))

(deftest suggest-ranks-by-score-not-lexical-order
  (doseq [tasks [2 4]]
    (testing (str tasks " tasks")
      (exercise tasks
        (fn [c r]
          (p/publish-snapshot! c "en" 1 (corpus 300))
          (harness/wait-for-processing! r)
          (let [small (into {} (for [prefix ["a" "ab"] k [1 10]]
                                 [[prefix k] (suggest-work r prefix k (expected-top 300 k 0))]))]
            (p/publish-snapshot! c "en" 2 (corpus 3000))
            (harness/wait-for-processing! r)
            (let [large (into {} (for [prefix ["a" "ab"] k [1 10]]
                                   [[prefix k] (suggest-work r prefix k (expected-top 3000 k 0))]))]
              ;; The 500 best phrases are blocked; the next best must be found
              ;; without walking past the blocked ones.
              (doseq [i (range 2500 3000)]
                (p/block-phrase! c "en" (format "ab%04d" i)))
              (harness/wait-for-processing! r)
              (let [blocked (into {} (for [prefix ["a" "ab"] k [1 10]]
                                       [[prefix k] (suggest-work r prefix k (expected-top 3000 k 500))]))]
                (println "Autocomplete suggest work" tasks "tasks" small "->" large "-> blocked" blocked)
                (doseq [q (keys small)]
                  (is (pos? (small q)))
                  (is (<= (large q) (+ 20 (* 2 (small q))))
                      (str q " suggest work grew with matching candidates (300 -> 3000): "
                           (small q) " -> " (large q)))
                  (is (<= (blocked q) (+ 20 (* 2 (large q))))
                      (str q " suggest work grew with 500 blocked top candidates: "
                           (large q) " -> " (blocked q))))))))))))
