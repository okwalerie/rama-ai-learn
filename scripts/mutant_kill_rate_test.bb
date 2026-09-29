#!/usr/bin/env bb
;; Tests for scripts/mutant_kill_rate.bb. Run from the repo root:
;;   bb scripts/mutant_kill_rate_test.bb

(require '[clojure.test :refer [deftest testing is run-tests]]
         '[babashka.fs :as fs]
         '[clojure.string :as str])

(load-file "scripts/mutant_kill_rate.bb")

;; encrypt_challenges.bb expects this var from bb.edn; define before loading.
(def challenge-crypt-mode :encrypt)
(load-file "scripts/encrypt_challenges.bb")

(defn- make-fixture-root []
  (let [root (fs/create-temp-dir {:prefix "mkr-test"})
        ch (fs/path root "challenges" "demo")]
    (fs/create-dirs (fs/path ch "test-private" "mutants" "global-placement" "src" "demo"))
    (fs/create-dirs (fs/path ch "test-private" "mutants" "no-manifest" "src" "demo"))
    (fs/create-dirs (fs/path ch "test-resources" "demo"))
    (spit (str (fs/path ch "deps.edn"))
          (pr-str {:aliases {:test-private {:extra-paths ["test-private"]
                                            :exec-fn 'cognitect.test-runner.api/test
                                            :exec-args {:dirs ["test-private"]}}}}))
    (spit (str (fs/path ch "test-private" "mutants" "global-placement" "manifest.edn"))
          (pr-str {:id "global-placement" :challenge "demo" :targets-nfr "balance"
                   :wrong-design "All state on one task." :expected :killed :split :dev}))
    (spit (str (fs/path ch "test-private" "mutants" "global-placement" "src" "demo" "module.clj"))
          "(ns demo.module)")
    root))

(deftest discovery-test
  (let [root (str (make-fixture-root))
        ms (find-mutants root "demo")
        by-id (into {} (map (juxt :id identity) ms))]
    (is (= ["demo"] (challenges-with-mutants root)))
    (is (= #{"global-placement" "no-manifest"} (set (keys by-id))))
    (is (empty? (:problems (by-id "global-placement"))))
    (is (= :dev (:split (by-id "global-placement"))))
    (is (= "test-private/mutants/global-placement/src" (:src-rel (by-id "global-placement"))))
    (is (= ["missing manifest.edn"] (:problems (by-id "no-manifest"))))))

(deftest manifest-validation-test
  (is (empty? (manifest-problems {:id "x" :challenge "c" :targets-nfr "t"
                                  :wrong-design "w" :expected :killed} "x" "c")))
  (is (some #(str/includes? % ":wrong-design")
            (manifest-problems {:id "x" :challenge "c" :targets-nfr "t" :expected :killed} "x" "c")))
  (is (some #(str/includes? % "does not match directory")
            (manifest-problems {:id "y" :challenge "c" :targets-nfr "t"
                                :wrong-design "w" :expected :killed} "x" "c")))
  (is (some #(str/includes? % ":expected")
            (manifest-problems {:id "x" :challenge "c" :targets-nfr "t"
                                :wrong-design "w" :expected :maybe} "x" "c"))))

(deftest grader-alias-test
  (testing "mutant dir shadows test-resources and implementations are never on the path"
    (let [a (grader-alias {:extra-paths ["test-private"] :exec-args {:dirs ["test-private"]}}
                          "test-private/mutants/m/src")]
      (is (= ["src" "test-private/mutants/m/src" "test-resources"] (:paths a)))
      (is (not-any? #(str/includes? % "implementations") (concat (:paths a) (:extra-paths a))))
      (is (= {:dirs ["test-private"]} (:exec-args a)))
      (is (contains? (:extra-deps a) 'io.github.cognitect-labs/test-runner))))
  (testing "reference run has no mutant dir"
    (is (= ["src" "test-resources"] (:paths (grader-alias nil nil)))))
  (testing "command is a -Sdeps -X invocation"
    (let [cmd (grader-command nil nil)]
      (is (= "clojure" (first cmd)))
      (is (= "-X:mutant-kill-rate" (last cmd))))))

(def sample-fail-output
  "Testing demo.performance-challenge-test

FAIL in (performance-test) (performance_test_support.clj:64)
post fans out with bounded writes
expected: (< writes 40)
  actual: (not (< 301 40))

ERROR in (functional-test) (module.clj:10)
Uncaught exception, not in assertion.

Ran 2 tests containing 51 assertions.
1 failures, 1 errors.")

(deftest parse-test-output-test
  (let [p (parse-test-output sample-fail-output)]
    (is (= 2 (:ran p)))
    (is (= 1 (:failures p)))
    (is (= 1 (:errors p)))
    (is (= [{:kind :fail :test "performance-test" :context "post fans out with bounded writes"}
            {:kind :error :test "functional-test" :context "Uncaught exception, not in assertion."}]
           (:killed-by p)))
    (is (= :fail (classify {:exit 1} p))))
  (let [p (parse-test-output "Ran 2 tests containing 51 assertions.\n0 failures, 0 errors.")]
    (is (= :pass (classify {:exit 0} p))))
  (testing "a JVM that dies before running tests is a crash, not a pass"
    (is (= :crash (classify {:exit 1} (parse-test-output "Syntax error compiling"))))
    (is (= :timeout (classify {:exit -1 :timed-out? true} (parse-test-output ""))))))

(deftest kill-rate-and-table-test
  (let [results [{:challenge "demo" :reference-ok? true
                  :mutants [{:id "a" :killed? true :split :dev}
                            {:id "b" :killed? false :split :held-out}
                            {:id "c" :killed? false :status :crash :split :dev}]}]]
    (is (= {:mutants 2 :killed 1 :rate 0.5} (kill-rate (:mutants (first results)))))
    (let [t (format-table results)]
      (is (str/includes? t "| demo | pass | 2 | 1 | b | c |"))
      (is (str/includes? t "Overall: 1/2 mutants killed (50%)."))
      (is (str/includes? t "dev: 1/1"))
      (is (str/includes? t "held-out: 0/1")))))

(deftest mutants-are-protected-from-solvers-test
  (testing "every mutant file is under a directory encrypt-challenges protects"
    (let [root (make-fixture-root)
          ch (fs/path root "challenges" "demo")
          sensitive (map str (sensitive-dirs (str ch)))
          mutant-files (filter fs/regular-file? (fs/glob (fs/path ch "test-private" "mutants") "**"))]
      (is (seq mutant-files))
      (doseq [f mutant-files]
        (is (some #(str/starts-with? (str f) %) sensitive) (str f)))))
  (testing "real repo mutants live only under test-private/mutants"
    (doseq [c (challenges-with-mutants project-root)
            m (find-mutants project-root c)]
      (is (str/starts-with? (:src-rel m) "test-private/mutants/") (str c "/" (:id m))))))

(let [{:keys [fail error]} (run-tests)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
