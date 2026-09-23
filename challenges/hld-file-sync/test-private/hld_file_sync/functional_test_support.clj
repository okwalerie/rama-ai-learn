(ns hld-file-sync.functional-test-support
  "Private functional tests for hld-file-sync. Every expected value is derived
   from the README contract and the test's own inputs, never read back from
   the module. The whole scenario set runs once at 2 tasks and once at 4
   tasks; each run uses two clients from the same :wrap-client, explicit
   barriers before reads, and a module update part-way through."
  (:require
   [clojure.test :refer [is testing]]
   [com.rpl.rama.test :as rtest]
   [rama-challenges.harness :as harness]
   [hld-file-sync.protocol :as p]))

;; ---------------------------------------------------------------------------
;; Helpers (pure; no module access)

(defn- chars-of [n c] (apply str (repeat n c)))

(def id128 (chars-of 128 "r"))
(def id129 (chars-of 129 "r"))
(def path1024 (chars-of 1024 "p"))
(def path1025 (chars-of 1025 "p"))

(defn- blocks
  "Block maps from alternating hash/size arguments."
  [& hs]
  (mapv (fn [[h s]] {:hash h :size s}) (partition 2 hs)))

(defn- accepted-register [n]
  {:status :accepted :command :register-blocks :conflicting-attempts 0 :registered n})

(defn- rejected [command reason & [attempts]]
  {:status :rejected :command command :reason reason
   :conflicting-attempts (or attempts 0)})

(defn- need-blocks [missing & [attempts]]
  (assoc (rejected :commit-file :need-blocks attempts) :need-blocks missing))

(defn- accepted-commit [file-id version seq size copy? conflict-of & [attempts]]
  {:status :accepted :command :commit-file :conflicting-attempts (or attempts 0)
   :file-id file-id :version version :seq seq :size-bytes size
   :conflict-copy? copy? :conflict-of conflict-of})

(defn- file-rec [file-id version path blocklist size rid seq conflict-of]
  {:file-id file-id :version version :path path :blocklist blocklist
   :size-bytes size :request-id rid :seq seq :conflict-of conflict-of})

(defn- change [seq file-id version path size rid conflict-of]
  {:seq seq :file-id file-id :version version :path path
   :size-bytes size :request-id rid :conflict-of conflict-of})

(defn- conflict-path [path rid]
  (let [suffix (str " (conflicted copy " rid ")")]
    (str (subs path 0 (min (count path) (- 1024 (count suffix)))) suffix)))

(defmacro throws-iae? [& body]
  `(try ~@body false
        (catch IllegalArgumentException _# true)))

(defn- longs? [m ks] (every? #(instance? Long (get m %)) ks))

;; ---------------------------------------------------------------------------
;; Scenario groups. `c` and `c2` are two clients of the same module.

(defn- structural-validation [c]
  (testing "structural violations throw IllegalArgumentException and append nothing"
    (let [ns "sv"]
      (testing "register-blocks! bounds"
        (is (throws-iae? (p/register-blocks! c "s1" ns [])))
        (is (throws-iae? (p/register-blocks! c "s1" ns (vec (repeat 1025 {:hash "h" :size 1})))))
        (is (throws-iae? (p/register-blocks! c "s1" ns (blocks "h" -1))))
        (is (throws-iae? (p/register-blocks! c "s1" ns (blocks "h" 1000000001))))
        (is (throws-iae? (p/register-blocks! c "s1" ns [{:hash "h" :size (int 3)}]))
            "size must be a Long, not an Integer")
        (is (throws-iae? (p/register-blocks! c "s1" ns [{:hash "h" :size "3"}])))
        (is (throws-iae? (p/register-blocks! c "s1" ns (blocks "" 1))))
        (is (throws-iae? (p/register-blocks! c "s1" ns (blocks id129 1))))
        (is (throws-iae? (p/register-blocks! c "s1" ns (blocks "h" 1 "h" 2)))
            "same hash with two sizes in one call is structural")
        (is (throws-iae? (p/register-blocks! c "s1" ns (list {:hash "h" :size 1}))))
        (is (throws-iae? (p/register-blocks! c "s1" ns [{:size 1}])) "block without :hash")
        (is (throws-iae? (p/register-blocks! c "s1" ns ["h"])) "block entry that is not a map")
        (is (throws-iae? (p/register-blocks! c "s1" id129 (blocks "h" 1))) "ns-id over 128")
        (is (throws-iae? (p/register-blocks! c "" ns (blocks "h" 1))))
        (is (throws-iae? (p/register-blocks! c id129 ns (blocks "h" 1))))
        (is (throws-iae? (p/register-blocks! c "s1" "" (blocks "h" 1))))
        (is (throws-iae? (p/register-blocks! c "s1" nil (blocks "h" 1)))))
      (testing "commit-file! bounds"
        (is (throws-iae? (p/commit-file! c "s2" ns "a~b" "/p" [] nil)))
        (is (throws-iae? (p/commit-file! c "s2" ns "~" "/p" [] 1)))
        (is (throws-iae? (p/commit-file! c "s2" ns (str "~" id129) "/p" [] 1)))
        (is (throws-iae? (p/commit-file! c "s2" ns "" "/p" [] nil)))
        (is (throws-iae? (p/commit-file! c "s2" ns id129 "/p" [] nil)))
        (is (throws-iae? (p/commit-file! c "s2" ns "f" "" [] nil)))
        (is (throws-iae? (p/commit-file! c "s2" ns "f" path1025 [] nil)))
        (is (throws-iae? (p/commit-file! c "s2" ns "f" "/p" (vec (repeat 1025 "h")) nil)))
        (is (throws-iae? (p/commit-file! c "s2" ns "f" "/p" ["h" ""] nil)))
        (is (throws-iae? (p/commit-file! c "s2" ns "f" "/p" (list "h") nil)))
        (is (throws-iae? (p/commit-file! c "s2" ns "f" "/p" [5] nil)) "blocklist entry not a String")
        (is (throws-iae? (p/commit-file! c "s2" ns "f" "/p" [id129] nil)) "blocklist hash over 128")
        (is (throws-iae? (p/commit-file! c "s2" ns nil "/p" [] nil)) "nil file-id")
        (is (throws-iae? (p/commit-file! c "s2" ns "f" nil [] nil)) "nil path")
        (is (throws-iae? (p/commit-file! c "s2" id129 "f" "/p" [] nil)) "ns-id over 128")
        (is (throws-iae? (p/commit-file! c "s2" ns "f" "/p" [] 0)))
        (is (throws-iae? (p/commit-file! c "s2" ns "f" "/p" [] -1)))
        (is (throws-iae? (p/commit-file! c "s2" ns "f" "/p" [] (int 1)))
            "parent-version must be a Long, not an Integer")
        (is (throws-iae? (p/commit-file! c "s2" ns "f" "/p" [] "1")))
        (is (throws-iae? (p/commit-file! c "s2" ns "~x" "/p" [] nil))
            "a create requires a client-form file-id"))
      (testing "query bounds"
        (is (throws-iae? (p/get-changes c ns 0 0)))
        (is (throws-iae? (p/get-changes c ns 0 501)))
        (is (throws-iae? (p/get-changes c ns -1 10)))
        (is (throws-iae? (p/get-changes c ns (int 0) 10)))
        (is (throws-iae? (p/get-changes c ns 0 (int 10))))
        (is (throws-iae? (p/get-changes c "" 0 10)))
        (is (throws-iae? (p/get-file-version c ns "f" 0)))
        (is (throws-iae? (p/get-file-version c ns "f" (int 1))))
        (is (throws-iae? (p/get-file c ns "a~b")))
        (is (throws-iae? (p/get-file c ns "~")))
        (is (throws-iae? (p/get-file c ns (str "~" id129))))
        (is (throws-iae? (p/get-file-version c ns "a~b" 1)))
        (is (throws-iae? (p/get-block-size c ns "")))
        (is (throws-iae? (p/get-block-size c ns id129)))
        (is (throws-iae? (p/get-outcome c ns "")))
        (is (throws-iae? (p/get-outcome c "" "s1")))
        (is (throws-iae? (p/get-file c "" "f")))
        (is (throws-iae? (p/get-file c ns nil)))
        (is (throws-iae? (p/get-file-version c ns "f" -1)))
        (is (throws-iae? (p/get-block-size c "" "h"))))
      (harness/wait-for-processing! c)
      (testing "nothing was appended or created"
        (is (nil? (p/get-outcome c ns "s1")))
        (is (nil? (p/get-outcome c ns "s2")))
        (is (nil? (p/get-file c ns "f")))
        (is (= [] (p/get-changes c ns 0 10))))
      (testing "valid boundary values are accepted"
        (is (nil? (p/register-blocks! c id128 ns (blocks id128 0 "z" 1000000000)))
            "commands return nil, never a business result")
        (p/register-blocks! c "s3" ns (mapv #(hash-map :hash (str "b" %) :size 1) (range 1024)))
        (is (nil? (p/commit-file! c "s4" ns id128 path1024 (vec (repeat 1024 "z")) nil)))
        (p/commit-file! c "s5" ns "e" "/e" [] nil)
        (harness/wait-for-processing! c)
        (is (= (accepted-register 2) (p/get-outcome c ns id128)))
        (is (= (accepted-register 1024) (p/get-outcome c ns "s3")))
        (is (= (accepted-commit id128 1 1 (* 1024 1000000000) false nil)
               (p/get-outcome c ns "s4")))
        (is (= 0 (p/get-block-size c ns id128)))
        (is (= 1000000000 (p/get-block-size c ns "z")))
        (is (= 1 (p/get-block-size c ns "b1023")))
        (is (= (file-rec "e" 1 "/e" [] 0 "s5" 2 nil) (p/get-file c ns "e")))
        (is (= [(change 1 id128 1 path1024 (* 1024 1000000000) "s4" nil)]
               (p/get-changes c ns 0 1)))
        (is (= 2 (count (p/get-changes c ns 0 500))))
        (is (nil? (p/get-file c ns (str "~" id128))) "valid generated id naming no file")
        (is (nil? (p/get-file-version c ns "~~x" 1)))
        (is (nil? (p/get-file-version c ns "e" 2)) "version above head is nil, not an error")
        (is (nil? (p/get-file-version c ns "e" Long/MAX_VALUE)))
        (is (nil? (p/get-file c "sv-unknown" "f")) "unknown namespace reads as absent")
        (is (nil? (p/get-file-version c "sv-unknown" "f" 1)))
        (is (nil? (p/get-outcome c "sv-unknown" "s4")))
        (is (nil? (p/get-block-size c "sv-unknown" "z"))))
      (testing "a malformed retry leaves an existing outcome untouched"
        (is (throws-iae? (p/commit-file! c "s5" ns "e" path1025 [] nil)))
        (is (throws-iae? (p/register-blocks! c id128 ns (blocks "q" -5))))
        (harness/wait-for-processing! c)
        (is (= (accepted-commit "e" 1 2 0 false nil) (p/get-outcome c ns "s5")))
        (is (= (accepted-register 2) (p/get-outcome c ns id128)))
        (is (= 2 (count (p/get-changes c ns 0 500))))))))

(defn- register-semantics [c]
  (testing "register-blocks!"
    (let [ns "rb"]
      (p/register-blocks! c "r1" ns (blocks "a" 3 "b" 5 "a" 3 "c" 7))
      (harness/wait-for-processing! c)
      (is (= (accepted-register 3) (p/get-outcome c ns "r1")) "repeated hash counted once")
      (is (= 3 (p/get-block-size c ns "a")))
      (is (= 5 (p/get-block-size c ns "b")))
      (is (= 7 (p/get-block-size c ns "c")))
      (is (nil? (p/get-block-size c ns "d")))
      (p/register-blocks! c "r2" ns (blocks "b" 5 "d" 11))
      (p/register-blocks! c "r3" ns (blocks "a" 3 "c" 7))
      (harness/wait-for-processing! c)
      (is (= (accepted-register 1) (p/get-outcome c ns "r2")) "known same-size hash is not new")
      (is (= (accepted-register 0) (p/get-outcome c ns "r3")))
      (testing "size mismatch rejects the whole call atomically"
        (p/register-blocks! c "r4" ns (blocks "e" 1 "b" 6 "f" 2))
        (harness/wait-for-processing! c)
        (is (= (rejected :register-blocks :size-mismatch) (p/get-outcome c ns "r4")))
        (is (= 5 (p/get-block-size c ns "b")))
        (is (nil? (p/get-block-size c ns "e")))
        (is (nil? (p/get-block-size c ns "f"))))
      (testing "namespaces are independent"
        (p/register-blocks! c "r1" "rb2" (blocks "b" 99))
        (harness/wait-for-processing! c)
        (is (= (accepted-register 1) (p/get-outcome c "rb2" "r1")))
        (is (= 99 (p/get-block-size c "rb2" "b")))
        (is (= 5 (p/get-block-size c ns "b")))
        (is (nil? (p/get-block-size c "rb3" "b")))
        (is (nil? (p/get-outcome c "rb3" "r1")))))))

(defn- commit-semantics [c]
  (testing "commit-file!"
    (let [ns "cf"]
      (p/register-blocks! c "reg" ns (blocks "a" 3 "b" 5 "c" 11))
      (harness/wait-for-processing! c)
      (testing "create, update, and rejections in README order"
        (p/commit-file! c "c1" ns "f" "/f" ["a" "a" "b"] nil)
        (harness/wait-for-processing! c)
        (is (= (accepted-commit "f" 1 1 11 false nil) (p/get-outcome c ns "c1")))
        (is (= (file-rec "f" 1 "/f" ["a" "a" "b"] 11 "c1" 1 nil) (p/get-file c ns "f")))
        (is (longs? (p/get-file c ns "f") [:version :size-bytes :seq]))
        (p/commit-file! c "c2" ns "f" "/f" [] nil)
        (p/commit-file! c "c3" ns "nope" "/n" [] 1)
        (p/commit-file! c "c4" ns "~absent" "/n" [] 1)
        (p/commit-file! c "c5" ns "f" "/f" ["zz"] 2)
        (p/commit-file! c "c6" ns "f" "/f" ["x" "a" "y" "x"] 1)
        (p/commit-file! c "c7" ns "f" "/f-v2" ["c"] 1)
        (harness/wait-for-processing! c)
        (is (= (rejected :commit-file :file-exists) (p/get-outcome c ns "c2")))
        (is (= (rejected :commit-file :no-such-file) (p/get-outcome c ns "c3")))
        (is (= (rejected :commit-file :no-such-file) (p/get-outcome c ns "c4"))
            "a valid generated-form id naming no file follows missing-file rules")
        (is (= (rejected :commit-file :unknown-parent) (p/get-outcome c ns "c5"))
            "unknown-parent is checked before need-blocks")
        (is (= (need-blocks ["x" "y"]) (p/get-outcome c ns "c6"))
            "missing hashes once each, first-occurrence order")
        (is (= (accepted-commit "f" 2 2 11 false nil) (p/get-outcome c ns "c7")))
        (is (= (file-rec "f" 2 "/f-v2" ["c"] 11 "c7" 2 nil) (p/get-file c ns "f")))
        (is (= (file-rec "f" 1 "/f" ["a" "a" "b"] 11 "c1" 1 nil) (p/get-file-version c ns "f" 1))
            "version history retained")
        (is (= (file-rec "f" 2 "/f-v2" ["c"] 11 "c7" 2 nil) (p/get-file-version c ns "f" 2)))
        (is (nil? (p/get-file-version c ns "f" 3)))
        (is (nil? (p/get-file c ns "nope")))
        (let [{:keys [blocklist size-bytes]} (p/get-file-version c ns "f" 1)]
          (is (= size-bytes (reduce + 0 (map #(p/get-block-size c ns %) blocklist)))
              "size-bytes equals the registered sizes of the blocklist summed with repeats"))
        (is (= [(change 1 "f" 1 "/f" 11 "c1" nil) (change 2 "f" 2 "/f-v2" 11 "c7" nil)]
               (p/get-changes c ns 0 10))
            "rejections consume no seq and add no journal entry"))
      (testing "stale parent creates a conflict copy and never touches the original"
        (p/commit-file! c "c8" ns "f" "/stale" ["b" "b"] 1)
        (p/commit-file! c "c9" ns "f" "/f" ["x"] 1)
        (harness/wait-for-processing! c)
        (is (= (accepted-commit "~c8" 1 3 10 true "f") (p/get-outcome c ns "c8")))
        (is (= (need-blocks ["x"]) (p/get-outcome c ns "c9"))
            "a stale commit lacking blocks is need-blocks and creates no copy")
        (is (= (file-rec "~c8" 1 "/stale (conflicted copy c8)" ["b" "b"] 10 "c8" 3 "f")
               (p/get-file c ns "~c8")))
        (is (= (file-rec "f" 2 "/f-v2" ["c"] 11 "c7" 2 nil) (p/get-file c ns "f"))
            "original head unchanged, seq immutable")
        (is (nil? (p/get-file c ns "~c9")))
        (is (= 3 (count (p/get-changes c ns 0 10)))))
      (testing "a copy is an ordinary file: updates keep provenance, stale edits nest"
        (p/commit-file! c "c10" ns "~c8" "/stale2" ["a"] 1)
        (harness/wait-for-processing! c)
        (is (= (accepted-commit "~c8" 2 4 3 false "f") (p/get-outcome c ns "c10")))
        (is (= (file-rec "~c8" 2 "/stale2" ["a"] 3 "c10" 4 "f") (p/get-file c ns "~c8")))
        (is (= (file-rec "~c8" 1 "/stale (conflicted copy c8)" ["b" "b"] 10 "c8" 3 "f")
               (p/get-file-version c ns "~c8" 1)))
        (p/commit-file! c "c11" ns "~c8" "/nested" ["c"] 1)
        (p/commit-file! c "c12" ns "f" "/again" [] 1)
        (harness/wait-for-processing! c)
        (is (= (accepted-commit "~c11" 1 5 11 true "~c8") (p/get-outcome c ns "c11")))
        (is (= (file-rec "~c11" 1 "/nested (conflicted copy c11)" ["c"] 11 "c11" 5 "~c8")
               (p/get-file c ns "~c11")))
        (is (= (accepted-commit "~c12" 1 6 0 true "f") (p/get-outcome c ns "c12")))
        (is (= (file-rec "~c8" 2 "/stale2" ["a"] 3 "c10" 4 "f") (p/get-file c ns "~c8")))
        (is (= (file-rec "f" 2 "/f-v2" ["c"] 11 "c7" 2 nil) (p/get-file c ns "f")))
        (is (= [(change 3 "~c8" 1 "/stale (conflicted copy c8)" 10 "c8" "f")
                (change 4 "~c8" 2 "/stale2" 3 "c10" "f")
                (change 5 "~c11" 1 "/nested (conflicted copy c11)" 11 "c11" "~c8")
                (change 6 "~c12" 1 "/again (conflicted copy c12)" 0 "c12" "f")]
               (p/get-changes c ns 2 10))
            "journal carries each copy once with its own provenance"))
      (testing "129-character generated ids and path truncation at the limit"
        (p/commit-file! c id128 ns "f" path1024 ["a"] 1)
        (harness/wait-for-processing! c)
        (let [copy-id (str "~" id128)
              expected-path (conflict-path path1024 id128)]
          (is (= 129 (count copy-id)))
          (is (= 1024 (count expected-path)))
          (is (= (subs path1024 0 (- 1024 147)) (subs expected-path 0 (- 1024 147))))
          (is (.endsWith ^String expected-path (str " (conflicted copy " id128 ")")))
          (is (= (accepted-commit copy-id 1 7 3 true "f") (p/get-outcome c ns id128)))
          (is (= (file-rec copy-id 1 expected-path ["a"] 3 id128 7 "f") (p/get-file c ns copy-id)))
          (is (= (file-rec copy-id 1 expected-path ["a"] 3 id128 7 "f")
                 (p/get-file-version c ns copy-id 1)))
          (p/commit-file! c "c13" ns copy-id "/edited" ["b"] 1)
          (harness/wait-for-processing! c)
          (is (= (accepted-commit copy-id 2 8 5 false "f") (p/get-outcome c ns "c13")))
          (is (= (file-rec copy-id 2 "/edited" ["b"] 5 "c13" 8 "f") (p/get-file c ns copy-id)))
          (is (throws-iae? (p/commit-file! c "c14" ns copy-id "/x" [] nil))
              "a create against a generated id is structural")
          (p/commit-file! c "c15" ns copy-id "/deep" [] 1)
          (harness/wait-for-processing! c)
          (is (= (accepted-commit "~c15" 1 9 0 true copy-id) (p/get-outcome c ns "c15")))
          (is (= "/deep (conflicted copy c15)" (:path (p/get-file c ns "~c15"))))
          (is (= copy-id (:conflict-of (p/get-file c ns "~c15"))))))
      (testing "request-ids containing ~ produce ids with a double tilde"
        (p/commit-file! c "~t" ns "f" "/t" [] 1)
        (harness/wait-for-processing! c)
        (is (= (accepted-commit "~~t" 1 10 0 true "f") (p/get-outcome c ns "~t")))
        (is (= "~t" (:request-id (p/get-file c ns "~~t"))))
        (is (= 1 (:version (p/get-file-version c ns "~~t" 1))))))))

(defn- idempotency [c]
  (testing "replays and conflicting attempts"
    (let [ns "id"]
      (p/register-blocks! c "reg" ns (blocks "a" 3 "b" 5))
      (p/commit-file! c "k1" ns "f" "/f" ["a"] nil)
      (harness/wait-for-processing! c)
      (testing "replay of an accepted create has no effect"
        (p/commit-file! c "k1" ns "f" "/f" ["a"] nil)
        (harness/wait-for-processing! c)
        (is (= (accepted-commit "f" 1 1 3 false nil) (p/get-outcome c ns "k1")))
        (is (= 1 (:version (p/get-file c ns "f"))))
        (is (= 1 (count (p/get-changes c ns 0 10)))))
      (testing "different payloads only bump the counter"
        (p/commit-file! c "k1" ns "f" "/f" ["b"] nil)
        (harness/wait-for-processing! c)
        (is (= (accepted-commit "f" 1 1 3 false nil 1) (p/get-outcome c ns "k1")))
        (p/commit-file! c "k1" ns "f" "/f" ["a"] 1)
        (p/register-blocks! c "k1" ns (blocks "a" 3))
        (harness/wait-for-processing! c)
        (is (= (accepted-commit "f" 1 1 3 false nil 3) (p/get-outcome c ns "k1"))
            "a parent change and a cross-type reuse are both conflicting attempts")
        (is (= 1 (:version (p/get-file c ns "f"))))
        (is (= 1 (count (p/get-changes c ns 0 10))))
        (p/commit-file! c "k1" ns "f" "/f" ["a"] nil)
        (harness/wait-for-processing! c)
        (is (= (accepted-commit "f" 1 1 3 false nil 3) (p/get-outcome c ns "k1"))
            "an identical replay after conflicts leaves the counter alone"))
      (testing "replay of a stale commit creates exactly one copy"
        (p/commit-file! c "k2" ns "f" "/f2" ["b"] 1)
        (p/commit-file! c "k3" ns "f" "/f3" ["b"] 1)
        (p/commit-file! c "k3" ns "f" "/f3" ["b"] 1)
        (harness/wait-for-processing! c)
        (p/commit-file! c "k3" ns "f" "/f3" ["b"] 1)
        (harness/wait-for-processing! c)
        (is (= (accepted-commit "f" 2 2 5 false nil) (p/get-outcome c ns "k2")))
        (is (= (accepted-commit "~k3" 1 3 5 true "f") (p/get-outcome c ns "k3")))
        (is (= 1 (:version (p/get-file c ns "~k3"))))
        (is (= 3 (count (p/get-changes c ns 0 10)))))
      (testing "rejected originals stay rejected and are not re-evaluated"
        (p/commit-file! c "k4" ns "g" "/g" ["zz"] nil)
        (harness/wait-for-processing! c)
        (is (= (need-blocks ["zz"]) (p/get-outcome c ns "k4")))
        (p/register-blocks! c "k5" ns (blocks "zz" 1))
        (p/commit-file! c "k4" ns "g" "/g" ["zz"] nil)
        (harness/wait-for-processing! c)
        (is (= (need-blocks ["zz"]) (p/get-outcome c ns "k4")))
        (is (nil? (p/get-file c ns "g")) "replay precedes business validation")
        (p/commit-file! c "k6" ns "g" "/g" ["zz"] nil)
        (p/commit-file! c "k4" ns "g" "/g-other" ["zz"] nil)
        (harness/wait-for-processing! c)
        (is (= (accepted-commit "g" 1 4 1 false nil) (p/get-outcome c ns "k6"))
            "a new request-id succeeds")
        (is (= (need-blocks ["zz"] 1) (p/get-outcome c ns "k4")))
        (is (= 1 (:version (p/get-file c ns "g"))))
        (p/register-blocks! c "k4" ns (blocks "zz" 1))
        (harness/wait-for-processing! c)
        (is (= (need-blocks ["zz"] 2) (p/get-outcome c ns "k4"))
            "cross-type reuse of a rejected id is a conflicting attempt, still rejected")
        (is (= 1 (:version (p/get-file c ns "g")))))
      (testing "the same request-id in another namespace is unrelated"
        (p/register-blocks! c "k1" "id2" (blocks "q" 1))
        (harness/wait-for-processing! c)
        (is (= (accepted-register 1) (p/get-outcome c "id2" "k1")))
        (is (= (accepted-commit "f" 1 1 3 false nil 3) (p/get-outcome c ns "k1")))))))

(defn- rejected-before-creation [c]
  (testing "an outcome stored before its file or namespace exists survives creation"
    (testing "issued back-to-back without intermediate barriers (may span microbatches)"
      (let [ns "rc1"]
        (p/commit-file! c "q1" ns "g" "/g" [] 1)
        (p/register-blocks! c "q2" ns (blocks "a" 3))
        (p/commit-file! c "q3" ns "g" "/g" ["a"] nil)
        (p/commit-file! c "q1" ns "g" "/g" [] 1)
        (p/commit-file! c "q1" ns "g" "/g-other" [] 1)
        (harness/wait-for-processing! c)
        (is (= (rejected :commit-file :no-such-file 1) (p/get-outcome c ns "q1")))
        (is (= (accepted-register 1) (p/get-outcome c ns "q2")))
        (is (= (accepted-commit "g" 1 1 3 false nil) (p/get-outcome c ns "q3")))
        (is (= (file-rec "g" 1 "/g" ["a"] 3 "q3" 1 nil) (p/get-file c ns "g")))
        (is (= [(change 1 "g" 1 "/g" 3 "q3" nil)] (p/get-changes c ns 0 10)))))
    (testing "across barriers"
      (let [ns "rc2"]
        (p/commit-file! c "q1" ns "g" "/g" [] 1)
        (harness/wait-for-processing! c)
        (is (= (rejected :commit-file :no-such-file) (p/get-outcome c ns "q1")))
        (p/register-blocks! c "q2" ns (blocks "a" 3))
        (harness/wait-for-processing! c)
        (p/commit-file! c "q3" ns "g" "/g" ["a"] nil)
        (harness/wait-for-processing! c)
        (is (= (rejected :commit-file :no-such-file) (p/get-outcome c ns "q1"))
            "creation did not erase the earlier outcome")
        (p/commit-file! c "q1" ns "g" "/g" [] 1)
        (harness/wait-for-processing! c)
        (is (= (rejected :commit-file :no-such-file) (p/get-outcome c ns "q1"))
            "replay stays rejected although the file now exists")
        (is (= 1 (:version (p/get-file c ns "g"))))
        (p/commit-file! c "q1" ns "g" "/g-other" [] 1)
        (harness/wait-for-processing! c)
        (is (= (rejected :commit-file :no-such-file 1) (p/get-outcome c ns "q1")))
        (is (= (file-rec "g" 1 "/g" ["a"] 3 "q3" 1 nil) (p/get-file c ns "g")))
        (is (= 1 (count (p/get-changes c ns 0 10))))))))

(defn- ordering [c]
  (testing "commands of one namespace apply in issue order without barriers"
    (let [ns "ord"]
      (p/register-blocks! c "r" ns (blocks "a" 3 "b" 5))
      (p/commit-file! c "c1" ns "f" "/f" ["a"] nil)
      (p/commit-file! c "c2" ns "f" "/f" ["b"] 1)
      (p/commit-file! c "c3" ns "f" "/f" ["a" "b"] 1)
      (p/commit-file! c "c4" ns "h" "/h" [] nil)
      (p/commit-file! c "c5" ns "h" "/h" [] 9)
      (p/commit-file! c "c6" ns "h" "/h2" ["a"] 1)
      (p/commit-file! c "c7" ns "f" "/f3" [] 2)
      (harness/wait-for-processing! c)
      (is (= (accepted-register 2) (p/get-outcome c ns "r")))
      (is (= (accepted-commit "f" 1 1 3 false nil) (p/get-outcome c ns "c1")))
      (is (= (accepted-commit "f" 2 2 5 false nil) (p/get-outcome c ns "c2")))
      (is (= (accepted-commit "~c3" 1 3 8 true "f") (p/get-outcome c ns "c3"))
          "second commit against the same parent becomes a copy")
      (is (= (accepted-commit "h" 1 4 0 false nil) (p/get-outcome c ns "c4")))
      (is (= (rejected :commit-file :unknown-parent) (p/get-outcome c ns "c5")))
      (is (= (accepted-commit "h" 2 5 3 false nil) (p/get-outcome c ns "c6")))
      (is (= (accepted-commit "f" 3 6 0 false nil) (p/get-outcome c ns "c7")))
      (is (= [1 2 3 4 5 6] (mapv :seq (p/get-changes c ns 0 10))))
      (is (= ["f" "f" "~c3" "h" "h" "f"] (mapv :file-id (p/get-changes c ns 0 10))))
      (is (= (file-rec "f" 3 "/f3" [] 0 "c7" 6 nil) (p/get-file c ns "f")))))
  (testing "1024 distinct hashes: register, create, update, stale copy, replays and duplicate paths without intermediate barriers"
    (let [ns "wide"
          hashes (mapv #(str "d" %) (range 1024))
          sizes (mapv #(long (+ 1000 %)) (range 1024))
          regs (mapv (fn [h s] {:hash h :size s}) hashes sizes)
          size-of (fn [h] (nth sizes (Long/parseLong (subs h 1))))
          sum (fn [hs] (reduce + 0 (map size-of hs)))
          all hashes
          rev (vec (rseq hashes))
          evens (vec (take-nth 2 hashes))
          s-all (sum all)
          s-evens (sum evens)
          copy-path "/wf-stale (conflicted copy w3)"]
      (is (= 1547776 s-all) "expected size derived from the inputs: 1024 x 1000 + 0..1023")
      (is (= 773632 s-evens) "expected size derived from the inputs: 512 x 1000 + even 0..1022")
      (p/register-blocks! c "w0" ns regs)
      (p/commit-file! c "w1" ns "wf" "/wf" all nil)
      (p/commit-file! c "w2" ns "wf" "/wf2" rev 1)
      (p/commit-file! c "w3" ns "wf" "/wf-stale" evens 1)
      (p/commit-file! c "w1" ns "wf" "/wf" all nil)
      (p/commit-file! c "w3" ns "wf" "/wf-stale" evens 1)
      (p/commit-file! c "w4" ns "dup-a" "/same" [] nil)
      (p/commit-file! c "w5" ns "dup-b" "/same" [] nil)
      (harness/wait-for-processing! c)
      (is (= (accepted-register 1024) (p/get-outcome c ns "w0")))
      (is (= 1000 (p/get-block-size c ns "d0")))
      (is (= 2023 (p/get-block-size c ns "d1023")))
      (is (= (accepted-commit "wf" 1 1 s-all false nil) (p/get-outcome c ns "w1")))
      (is (= (accepted-commit "wf" 2 2 s-all false nil) (p/get-outcome c ns "w2")))
      (is (= (accepted-commit "~w3" 1 3 s-evens true "wf") (p/get-outcome c ns "w3")))
      (is (= (accepted-commit "dup-a" 1 4 0 false nil) (p/get-outcome c ns "w4")))
      (is (= (accepted-commit "dup-b" 1 5 0 false nil) (p/get-outcome c ns "w5"))
          "paths are labels: two files may carry the same path")
      (is (= (file-rec "wf" 2 "/wf2" rev s-all "w2" 2 nil) (p/get-file c ns "wf")))
      (is (= (file-rec "wf" 1 "/wf" all s-all "w1" 1 nil) (p/get-file-version c ns "wf" 1)))
      (is (nil? (p/get-file-version c ns "wf" 3)) "the replayed create added no version")
      (is (= (file-rec "~w3" 1 copy-path evens s-evens "w3" 3 "wf") (p/get-file c ns "~w3")))
      (is (nil? (p/get-file-version c ns "~w3" 2)) "the replayed stale commit added no version")
      (is (= [(change 1 "wf" 1 "/wf" s-all "w1" nil)
              (change 2 "wf" 2 "/wf2" s-all "w2" nil)
              (change 3 "~w3" 1 copy-path s-evens "w3" "wf")
              (change 4 "dup-a" 1 "/same" 0 "w4" nil)
              (change 5 "dup-b" 1 "/same" 0 "w5" nil)]
             (p/get-changes c ns 0 10))
          "exactly one journal entry per accepted commit; replays add none"))))

(defn- paging-and-history [c]
  (testing "journal paging over 1200 entries and deep version history"
    (let [ns "pg"
          big-rid #(str "v" %)
          big-path #(str "/big/" %)
          new-rid #(str "p" %)]
      (p/register-blocks! c "reg" ns (blocks "z" 7))
      (p/commit-file! c (big-rid 1) ns "big" (big-path 1) ["z"] nil)
      (doseq [i (range 2 1001)]
        (p/commit-file! c (big-rid i) ns "big" (big-path i) ["z"] (long (dec i))))
      (doseq [i (range 200)]
        (p/commit-file! c (new-rid i) ns (str "p" i) (str "/p/" i) ["z" "z"] nil))
      (harness/wait-for-processing! c)
      (let [expected-entry (fn [seq]
                             (if (<= seq 1000)
                               (change seq "big" seq (big-path seq) 7 (big-rid seq) nil)
                               (let [i (- seq 1001)]
                                 (change seq (str "p" i) 1 (str "/p/" i) 14 (new-rid i) nil))))
            expected-page (fn [after limit]
                            (mapv expected-entry (range (inc after) (inc (min 1200 (+ after limit))))))]
        (testing "three full pages then empty"
          (is (= (expected-page 0 500) (p/get-changes c ns 0 500)))
          (is (= (expected-page 500 500) (p/get-changes c ns 500 500)))
          (is (= (expected-page 1000 500) (p/get-changes c ns 1000 500)))
          (is (= 200 (count (p/get-changes c ns 1000 500))))
          (is (= [] (p/get-changes c ns 1200 500))))
        (testing "cursor forms"
          (is (= (expected-page 0 1) (p/get-changes c ns 0 1)))
          (is (= (expected-page 1150 500) (p/get-changes c ns 1150 500)))
          (is (= (expected-page 999 3) (p/get-changes c ns 999 3)) "page straddling the two files")
          (is (= [] (p/get-changes c ns 5000 10)))
          (is (= [] (p/get-changes c ns Long/MAX_VALUE 10)))
          (is (= [] (p/get-changes c ns (dec Long/MAX_VALUE) 500)))
          (is (= [] (p/get-changes c "unknown-ns" 0 10)))
          (is (every? #(not (contains? % :blocklist)) (p/get-changes c ns 0 5))
              "journal entries omit blocklist"))
        (testing "version history is complete and the head is right"
          (is (= (file-rec "big" 1000 (big-path 1000) ["z"] 7 (big-rid 1000) 1000 nil)
                 (p/get-file c ns "big")))
          (is (= (file-rec "big" 1 (big-path 1) ["z"] 7 (big-rid 1) 1 nil)
                 (p/get-file-version c ns "big" 1)))
          (is (= (file-rec "big" 537 (big-path 537) ["z"] 7 (big-rid 537) 537 nil)
                 (p/get-file-version c ns "big" 537)))
          (is (nil? (p/get-file-version c ns "big" 1001)))
          (is (= (accepted-commit "big" 537 537 7 false nil) (p/get-outcome c ns (big-rid 537))))
          (is (= (file-rec "p199" 1 "/p/199" ["z" "z"] 14 (new-rid 199) 1200 nil)
                 (p/get-file c ns "p199"))))
        (testing "journal entries and stored versions agree"
          (doseq [e (p/get-changes c ns 995 10)]
            (let [v (p/get-file-version c ns (:file-id e) (:version e))]
              (is (= e (select-keys v [:seq :file-id :version :path :size-bytes
                                       :request-id :conflict-of]))))))))))

(defn- shared-state-and-update [c c2 ipc module]
  (testing "two clients and a module update share durable state"
    (let [ns "sh"]
      (p/register-blocks! c "r" ns (blocks "a" 3))
      (p/commit-file! c "c1" ns "f" "/f" ["a"] nil)
      (harness/wait-for-processing! c2)
      (is (= (accepted-commit "f" 1 1 3 false nil) (p/get-outcome c2 ns "c1"))
          "a second wrap-client sees writes made through the first")
      (p/commit-file! c2 "c2" ns "f" "/f2" ["a" "a"] 1)
      (harness/wait-for-processing! c)
      (is (= (file-rec "f" 2 "/f2" ["a" "a"] 6 "c2" 2 nil) (p/get-file c ns "f")))
      (testing "the same conflicting body counts once per attempt"
        (p/commit-file! c "x1" ns "g" "/g" ["a"] nil)
        (p/commit-file! c "x1" ns "g" "/g-b" ["a"] nil)
        (p/commit-file! c "x1" ns "g" "/g-b" ["a"] nil)
        (harness/wait-for-processing! c2)
        (is (= (accepted-commit "g" 1 3 3 false nil 2) (p/get-outcome c2 ns "x1"))
            "two attempts with the same differing body count twice")
        (p/commit-file! c2 "x1" ns "g" "/g" ["a"] nil)
        (harness/wait-for-processing! c)
        (is (= (accepted-commit "g" 1 3 3 false nil 2) (p/get-outcome c ns "x1"))
            "a replay of the original after conflicts leaves the counter alone")
        (is (= (file-rec "g" 1 "/g" ["a"] 3 "x1" 3 nil) (p/get-file c2 ns "g")))
        (is (= 3 (count (p/get-changes c ns 0 10)))))
      (rtest/update-module! ipc module)
      (testing "reads are unchanged after the update"
        (is (= (accepted-commit "f" 1 1 3 false nil) (p/get-outcome c ns "c1")))
        (is (= (accepted-commit "f" 2 2 6 false nil) (p/get-outcome c2 ns "c2")))
        (is (= (accepted-commit "g" 1 3 3 false nil 2) (p/get-outcome c2 ns "x1")))
        (is (= (accepted-register 1) (p/get-outcome c2 ns "r")))
        (is (= (file-rec "f" 2 "/f2" ["a" "a"] 6 "c2" 2 nil) (p/get-file c2 ns "f")))
        (is (= (file-rec "f" 1 "/f" ["a"] 3 "c1" 1 nil) (p/get-file-version c ns "f" 1)))
        (is (= 3 (p/get-block-size c ns "a")))
        (is (= [(change 1 "f" 1 "/f" 3 "c1" nil) (change 2 "f" 2 "/f2" 6 "c2" nil)
                (change 3 "g" 1 "/g" 3 "x1" nil)]
               (p/get-changes c2 ns 0 10)))
        (is (= (rejected :commit-file :no-such-file 1) (p/get-outcome c "rc2" "q1"))
            "outcomes from earlier scenarios survive")
        (is (= 1000 (:version (p/get-file c "pg" "big")))))
      (testing "writes continue after the update with contiguous seqs"
        ;; The README orders commands only within one client, so the commit
        ;; that must win is barriered before the stale one is issued.
        (p/commit-file! c2 "c3" ns "f" "/f3" [] 2)
        (harness/wait-for-processing! c)
        (p/commit-file! c "c4" ns "f" "/f4" ["a"] 2)
        (p/commit-file! c "c1" ns "f" "/f" ["a"] nil)
        (p/commit-file! c "x1" ns "g" "/g-b" ["a"] nil)
        (p/register-blocks! c2 "r2" ns (blocks "a" 4))
        (p/commit-file! c2 "q1" "rc2" "g" "/g" [] 1)
        (harness/wait-for-processing! c2)
        (is (= (accepted-commit "f" 3 4 0 false nil) (p/get-outcome c ns "c3")))
        (is (= (accepted-commit "~c4" 1 5 3 true "f") (p/get-outcome c2 ns "c4")))
        (is (= (accepted-commit "f" 1 1 3 false nil) (p/get-outcome c ns "c1"))
            "replay across the update is still a replay")
        (is (= (accepted-commit "g" 1 3 3 false nil 3) (p/get-outcome c2 ns "x1"))
            "the same conflicting body after the update is a third attempt")
        (is (= (file-rec "g" 1 "/g" ["a"] 3 "x1" 3 nil) (p/get-file c ns "g")))
        (is (nil? (p/get-file-version c ns "g" 2)))
        (is (= (rejected :register-blocks :size-mismatch) (p/get-outcome c ns "r2")))
        (is (= 3 (p/get-block-size c2 ns "a")))
        (is (= [4 5] (mapv :seq (p/get-changes c ns 3 10))))
        (is (= 5 (count (p/get-changes c2 ns 0 10))))
        (is (= (rejected :commit-file :no-such-file 1) (p/get-outcome c "rc2" "q1"))
            "a rejected-before-creation original replayed after the update stays rejected although parent 1 now equals the head")
        (is (= (file-rec "g" 1 "/g" ["a"] 3 "q3" 1 nil) (p/get-file c2 "rc2" "g")))
        (is (= 1 (count (p/get-changes c "rc2" 0 10))))))))

(defn- fresh-client [c3]
  (testing "a client created after the writes sees everything and can barrier for them"
    (let [ns "sh"]
      (is (= (accepted-commit "f" 1 1 3 false nil) (p/get-outcome c3 ns "c1")))
      (is (= (accepted-commit "g" 1 3 3 false nil 3) (p/get-outcome c3 ns "x1")))
      (is (= 3 (:version (p/get-file c3 ns "f"))))
      (is (= (file-rec "f" 1 "/f" ["a"] 3 "c1" 1 nil) (p/get-file-version c3 ns "f" 1)))
      (is (= 3 (p/get-block-size c3 ns "a")))
      (is (= [1 2 3 4 5] (mapv :seq (p/get-changes c3 ns 0 10))))
      (is (= 1000 (:version (p/get-file c3 "pg" "big"))))
      (is (= (need-blocks ["zz"] 2) (p/get-outcome c3 "id" "k4")))
      (p/commit-file! c3 "c5" ns "f" "/f5" ["a" "a" "a"] 3)
      (harness/wait-for-processing! c3)
      (is (= (accepted-commit "f" 4 6 9 false nil) (p/get-outcome c3 ns "c5")))
      (is (= (file-rec "f" 4 "/f5" ["a" "a" "a"] 9 "c5" 6 nil) (p/get-file c3 ns "f")))
      (is (= [6] (mapv :seq (p/get-changes c3 ns 5 10)))))))

(defn- run-all [create-module-fn launch-config]
  (let [{:keys [module wrap-client]} (create-module-fn)]
    (with-open [ipc (rtest/create-ipc)]
      (rtest/launch-module! ipc module launch-config)
      (let [c (wrap-client ipc)
            c2 (wrap-client ipc)]
        (testing (str "launch " launch-config)
          (structural-validation c)
          (register-semantics c)
          (commit-semantics c)
          (idempotency c)
          (rejected-before-creation c)
          (ordering c)
          (paging-and-history c)
          (shared-state-and-update c c2 ipc module)
          (fresh-client (wrap-client ipc)))))))

(defn test-module-functional
  "Runs every scenario group at 2 tasks and again at 4 tasks (two workers)."
  [create-module-fn]
  (run-all create-module-fn {:tasks 2 :threads 2})
  (run-all create-module-fn {:tasks 4 :threads 2 :workers 2}))
