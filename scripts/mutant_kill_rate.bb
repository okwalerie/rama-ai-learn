#!/usr/bin/env bb
;; Mutant kill rate: an "eval of the evals" for challenge graders.
;;
;; A challenge's private suite (`test-private/`) is its grader. A good grader
;; must accept the reference AND reject plausible wrong designs. Each mutant is
;; a copy of the reference module with one targeted design change (usually an
;; NFR violation). This task runs the private suite against the reference
;; (must pass) and against every mutant (should fail), and reports which
;; mutants survive.
;;
;; Mutant layout (under test-private/, so encrypt-challenges protects it and
;; isolated solver snapshots never contain it):
;;
;;   challenges/<name>/test-private/mutants/<id>/manifest.edn
;;   challenges/<name>/test-private/mutants/<id>/src/<ns_dir>/module.clj
;;
;; manifest.edn:
;;   {:id "per-follower-writes" :challenge "fanout"
;;    :targets-nfr "..." :wrong-design "one sentence"
;;    :expected :killed            ; or :survives when a gap is accepted
;;    :split :dev                  ; optional, :dev or :held-out (default: hash)
;;    :functional-preserved? true}
;;
;; Usage:
;;   bb mutant-kill-rate [--challenge NAME]... [--mutant ID] [--jobs N]
;;                       [--json PATH] [--skip-reference] [--timeout-s N]

(require '[babashka.fs :as fs]
         '[babashka.cli :as cli]
         '[babashka.process :as p]
         '[cheshire.core :as json]
         '[clojure.edn :as edn]
         '[clojure.string :as str])

(def project-root
  (str (fs/parent (fs/parent (fs/absolutize *file*)))))

(def test-runner-dep
  {'io.github.cognitect-labs/test-runner {:git/tag "v0.5.1" :git/sha "dfb30dd"}})

;;; ── discovery ────────────────────────────────────────────────────────────────

(defn challenge-dir [root challenge]
  (fs/path root "challenges" challenge))

(defn mutants-dir [root challenge]
  (fs/path (challenge-dir root challenge) "test-private" "mutants"))

(defn held-out-by-hash?
  "Deterministic ~40% held-out assignment for manifests with no :split."
  [id]
  (< (mod (Math/abs (long (hash (str id)))) 10) 4))

(def required-manifest-keys [:id :challenge :targets-nfr :wrong-design :expected])

(defn manifest-problems
  "Return a vector of human-readable problems with a manifest (empty = valid)."
  [manifest dir-name challenge]
  (cond-> []
    (not (map? manifest)) (conj "manifest is not a map")
    (map? manifest)
    (into (for [k required-manifest-keys :when (nil? (get manifest k))]
            (str "missing " k)))
    (and (map? manifest) (:id manifest) (not= (str (:id manifest)) dir-name))
    (conj (str ":id " (:id manifest) " does not match directory " dir-name))
    (and (map? manifest) (:challenge manifest) (not= (str (:challenge manifest)) challenge))
    (conj (str ":challenge " (:challenge manifest) " does not match " challenge))
    (and (map? manifest) (:expected manifest)
         (not (#{:killed :survives} (:expected manifest))))
    (conj ":expected must be :killed or :survives")))

(defn identical-to-reference?
  "True when the mutant provides no source files, or every file it provides is
   byte-identical to the test-resources file it shadows. Such a placeholder
   would always \"survive\" and must not count against the grader."
  [root challenge dir]
  (let [src (fs/path dir "src")
        files (when (fs/directory? src) (filter fs/regular-file? (fs/glob src "**")))]
    (every? (fn [f]
              (let [ref (fs/path (challenge-dir root challenge) "test-resources"
                                 (str (fs/relativize src f)))]
                (and (fs/exists? ref) (= (slurp (str f)) (slurp (str ref))))))
            files)))

(defn read-mutant [root challenge dir]
  (let [id (str (fs/file-name dir))
        mf (fs/path dir "manifest.edn")
        manifest (when (fs/exists? mf) (edn/read-string (slurp (str mf))))
        problems (cond-> (if manifest
                           (manifest-problems manifest id challenge)
                           ["missing manifest.edn"])
                   (identical-to-reference? root challenge dir)
                   (conj "no mutation: source identical to reference"))
        split (or (:split manifest) (if (held-out-by-hash? id) :held-out :dev))]
    (merge manifest
           {:id id
            :challenge challenge
            :split split
            :src-rel (str (fs/relativize (challenge-dir root challenge) (fs/path dir "src")))
            :problems problems})))

(defn find-mutants [root challenge]
  (let [d (mutants-dir root challenge)]
    (if (fs/directory? d)
      (->> (fs/list-dir d)
           (filter fs/directory?)
           (sort-by str)
           (mapv #(read-mutant root challenge %)))
      [])))

(defn challenges-with-mutants [root]
  (->> (fs/list-dir (fs/path root "challenges"))
       (filter #(fs/directory? (fs/path % "test-private" "mutants")))
       (map #(str (fs/file-name %)))
       sort
       vec))

;;; ── command construction ─────────────────────────────────────────────────────

(defn private-alias
  "The challenge's grader alias (:test-private) from deps.edn. When the
   challenge also has a :test-private-harness alias, its :replace-paths name
   the reference's resource roots (e.g. test-resources/upstream for
   source-backed challenges); they are kept as ::resource-paths."
  [root challenge]
  (let [deps (edn/read-string (slurp (str (fs/path (challenge-dir root challenge) "deps.edn"))))
        harness-paths (get-in deps [:aliases :test-private-harness :replace-paths])]
    (cond-> (get-in deps [:aliases :test-private])
      harness-paths (assoc ::resource-paths
                           (vec (remove #{"src" "test-private"} harness-paths))))))

(defn grader-alias
  "Alias that runs the challenge grader against `module-dir` (a mutant src dir,
   or nil for the reference). `:paths` replaces the project paths so an
   implementation under implementations/ can never shadow the module under test.
   test-resources stays on the path after the mutant so the mutant shadows only
   the files it provides."
  [test-private-alias module-dir]
  (let [base (or test-private-alias {})]
    {:paths (vec (concat ["src"] (when module-dir [module-dir])
                         (or (::resource-paths base) ["test-resources"])))
     :extra-paths (or (:extra-paths base) ["test-private"])
     :extra-deps (merge test-runner-dep (:extra-deps base))
     :exec-fn (or (:exec-fn base) 'cognitect.test-runner.api/test)
     :exec-args (or (:exec-args base) {:dirs ["test-private"]})
     :jvm-opts (vec (distinct (concat (:jvm-opts base)
                                      ["--add-opens=java.base/java.lang=ALL-UNNAMED"
                                       "--enable-native-access=ALL-UNNAMED"])))}))

(defn grader-command [test-private-alias module-dir]
  ["clojure" "-Sdeps" (pr-str {:aliases {:mutant-kill-rate (grader-alias test-private-alias module-dir)}})
   "-X:mutant-kill-rate"])

;;; ── output parsing ───────────────────────────────────────────────────────────

(defn parse-test-output
  "Extract failing tests from cognitect test-runner / clojure.test output.
   Returns {:ran n :failures n :errors n :killed-by [{:kind :test :context}]}."
  [out]
  (let [lines (str/split-lines (or out ""))
        summary (some #(re-find #"(\d+) failures, (\d+) errors" %) lines)
        ran (some #(some-> (re-find #"Ran (\d+) tests" %) second parse-long) lines)
        hits (keep-indexed
              (fn [i line]
                (when-let [[_ kind test] (re-find #"^(FAIL|ERROR) in \((\S+)\)" line)]
                  {:kind (keyword (str/lower-case kind))
                   :test test
                   :context (let [nxt (get lines (inc i))]
                              (when (and nxt
                                         (not (str/starts-with? nxt "expected"))
                                         (not (str/blank? nxt)))
                                (str/trim nxt)))}))
              lines)]
    {:ran ran
     :failures (some-> summary (nth 1) parse-long)
     :errors (some-> summary (nth 2) parse-long)
     :killed-by (vec (distinct hits))}))

(defn classify
  "Classify a grader run. :pass means the grader accepted the module."
  [{:keys [exit timed-out?]} parsed]
  (cond
    timed-out? :timeout
    (and (zero? exit) (zero? (or (:failures parsed) 0)) (zero? (or (:errors parsed) 0))
         (pos? (or (:ran parsed) 0))) :pass
    (seq (:killed-by parsed)) :fail
    :else :crash))

;;; ── running ─────────────────────────────────────────────────────────────────

(defn run-grader [root challenge module-dir timeout-s]
  (let [cmd (grader-command (private-alias root challenge) module-dir)
        start (System/nanoTime)
        proc (p/process cmd {:dir (str (challenge-dir root challenge))
                             :out :string :err :out})
        res (deref proc (* 1000 timeout-s) ::timeout)
        timed-out? (= res ::timeout)
        _ (when timed-out? (p/destroy-tree proc))
        res (if timed-out? {:exit -1 :out ""} res)
        parsed (parse-test-output (:out res))
        secs (/ (- (System/nanoTime) start) 1e9)]
    {:status (classify (assoc res :timed-out? timed-out?) parsed)
     :exit (:exit res)
     :duration-s (Math/round (double secs))
     :killed-by (:killed-by parsed)
     :ran (:ran parsed)
     :output-tail (->> (str/split-lines (or (:out res) "")) (take-last 25) (str/join "\n"))}))

(defn mutant-result [mutant run]
  ;; :crash (no test failed; the JVM or compile died) is a broken mutant, not a
  ;; kill, and is excluded from the rate. A timeout counts as a kill.
  (let [killed? (contains? #{:fail :timeout} (:status run))]
    (merge (select-keys mutant [:challenge :id :targets-nfr :wrong-design :expected :split
                                :functional-preserved?])
           (dissoc run :output-tail)
           {:killed? killed?
            :as-expected? (= (:expected mutant) (if killed? :killed :survives))}
           (when (#{:crash :timeout} (:status run))
             {:output-tail (:output-tail run)}))))

(defn pmap-n [n f coll]
  (let [pool (java.util.concurrent.Executors/newFixedThreadPool (max 1 n))]
    (try
      (->> coll
           (mapv (fn [x] (.submit pool ^Callable (fn [] (f x)))))
           (mapv deref))
      (finally (.shutdown pool)))))

(defn killed-by-label
  "Short human label for what killed a mutant: test name plus testing context."
  [{:keys [killed-by]}]
  (let [label (->> killed-by
                   (map (fn [{:keys [test context]}]
                          (if context (str test " > " context) test)))
                   distinct
                   (str/join "; "))]
    (if (> (count label) 140) (str (subs label 0 137) "...") label)))

(defn run-challenge [root challenge {:keys [mutant jobs skip-reference timeout-s]}]
  (let [mutants (cond->> (find-mutants root challenge)
                  mutant (filterv #(= mutant (:id %))))
        invalid (filterv (comp seq :problems) mutants)
        valid (filterv (comp empty? :problems) mutants)
        _ (println (format "== %s: %d mutant(s)%s" challenge (count valid)
                           (if skip-reference "" " + reference")))
        reference (when-not skip-reference
                    (let [r (run-grader root challenge nil timeout-s)]
                      (println (format "   reference: %s (%ds)" (name (:status r)) (:duration-s r)))
                      r))
        results (pmap-n (or jobs 1)
                        (fn [m]
                          (let [r (mutant-result m (run-grader root challenge (:src-rel m) timeout-s))]
                            (println (format "   %-34s %-8s %4ds %s" (:id r) (name (:status r))
                                             (:duration-s r)
                                             (killed-by-label r)))
                            r))
                        valid)]
    {:challenge challenge
     :reference (some-> reference (select-keys [:status :duration-s :killed-by]))
     :reference-ok? (if reference (= :pass (:status reference)) :skipped)
     :invalid (mapv #(select-keys % [:id :problems]) invalid)
     :mutants results}))

;;; ── reporting ───────────────────────────────────────────────────────────────

(defn broken? [result] (= :crash (:status result)))

(defn kill-rate [results]
  (let [results (remove broken? results)
        n (count results) k (count (filter :killed? results))]
    {:mutants n :killed k :rate (when (pos? n) (double (/ k n)))}))

(defn summary-rows [challenge-results]
  (for [{:keys [challenge reference-ok?] results :mutants} challenge-results]
    (let [{:keys [mutants killed]} (kill-rate results)]
      {:challenge challenge
       :reference-ok? reference-ok?
       :mutants mutants
       :killed killed
       :survivors (mapv :id (remove #(or (:killed? %) (broken? %)) results))
       :broken (mapv :id (filter broken? results))})))

(defn format-table [challenge-results]
  (let [rows (summary-rows challenge-results)
        all (mapcat :mutants challenge-results)
        by-split (group-by :split all)
        {:keys [mutants killed]} (kill-rate all)]
    (str/join
     "\n"
     (concat
      ["| Challenge | Reference | Mutants | Killed | Surviving mutants | Broken |"
       "|---|---|---|---|---|---|"]
      (for [{:keys [challenge reference-ok? mutants killed survivors broken]} rows]
        (format "| %s | %s | %d | %d | %s | %s |" challenge
                (case reference-ok? true "pass" false "FAIL" :skipped "skipped")
                mutants killed (if (seq survivors) (str/join ", " survivors) "-")
                (if (seq broken) (str/join ", " broken) "-")))
      [""
       (format "Overall: %d/%d mutants killed%s." killed mutants
               (if (pos? mutants) (format " (%.0f%%)" (* 100.0 (/ killed mutants))) ""))
       (str/join "  "
                 (for [[split rs] (sort-by key by-split)]
                   (let [{:keys [mutants killed]} (kill-rate rs)]
                     (format "%s: %d/%d" (name split) killed mutants))))]))))

(defn format-mutant-table [challenge-results]
  (str/join
   "\n"
   (concat
    ["| Challenge | Mutant | Split | Status | Killed by | Wrong design |"
     "|---|---|---|---|---|---|"]
    (for [{:keys [challenge mutants]} challenge-results
          m mutants]
      (format "| %s | %s | %s | %s | %s | %s |" challenge (:id m) (name (:split m))
              (if (:killed? m) "killed" (if (broken? m) "BROKEN" "SURVIVED"))
              (let [l (killed-by-label m)] (if (str/blank? l) "-" (str/replace l "|" "/")))
              (str/replace (str (:wrong-design m)) "|" "/"))))))

(def cli-spec
  {:challenge {:desc "Challenge to evaluate (repeatable; default: all with mutants)"
               :alias :c :coerce []}
   :mutant {:desc "Only this mutant id" :alias :m}
   :jobs {:desc "Parallel mutant runs per challenge (default 1)" :alias :j :coerce :long}
   :json {:desc "Write JSON results here (default docs/mutant-kill-rate.json)"}
   :skip-reference {:desc "Do not run the reference" :coerce :boolean}
   :timeout-s {:desc "Per-run timeout in seconds (default 900)" :coerce :long}
   :help {:alias :h :coerce :boolean}})

(defn -main [args]
  (let [opts (cli/parse-opts args {:spec cli-spec})]
    (when (:help opts)
      (println "Usage: bb mutant-kill-rate [options]")
      (println (cli/format-opts {:spec cli-spec}))
      (System/exit 0))
    (let [challenges (or (seq (:challenge opts)) (challenges-with-mutants project-root))
          opts (merge {:jobs 1 :timeout-s 900} opts)
          results (mapv #(run-challenge project-root % opts) challenges)
          json-path (or (:json opts) (str (fs/path project-root "docs" "mutant-kill-rate.json")))
          report {:generated-at (str (java.time.Instant/now))
                  :git-rev (str/trim (:out (p/shell {:out :string :dir project-root :continue true}
                                                    "git" "rev-parse" "--short" "HEAD")))
                  :summary (kill-rate (mapcat :mutants results))
                  :challenges results}]
      (fs/create-dirs (fs/parent (fs/absolutize json-path)))
      (spit json-path (json/generate-string report {:pretty true}))
      (println)
      (println (format-table results))
      (println)
      (println (format-mutant-table results))
      (println)
      (println "JSON:" json-path)
      (doseq [{:keys [challenge invalid]} results :when (seq invalid)]
        (println "Invalid mutants in" challenge ":" invalid))
      (when (some #(false? (:reference-ok? %)) results)
        (println "ERROR: a reference failed its private suite; kill rates for it are meaningless.")
        (System/exit 2)))))

(when (= *file* (System/getProperty "babashka.file"))
  (-main *command-line-args*))
