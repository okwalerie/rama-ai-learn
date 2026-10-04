#!/usr/bin/env bb

(require '[clojure.test :refer [are deftest testing is run-tests]])

;; Load the runner script to get access to its functions
(load-file "scripts/run_challenges.bb")

(deftest format-duration-test
  ;; Tests the format-duration function which converts elapsed seconds
  ;; into human-readable duration strings.
  ;; Contract: >= 60s → "Xm Ys", < 60s → "Xs", fractional → rounded
  (testing "format-duration"
    (testing "when given zero seconds"
      (is (= "0s" (format-duration 0))))
    (testing "when given sub-minute values"
      (is (= "45s" (format-duration 45))))
    (testing "when given exactly 60 seconds"
      (is (= "1m 0s" (format-duration 60))))
    (testing "when given multi-minute values"
      (is (= "3m 42s" (format-duration 222))))
    (testing "when given fractional seconds"
      (is (= "31s" (format-duration 30.6)))
      (is (= "30s" (format-duration 30.4))))
    (testing "when rounding crosses the 60s boundary"
      (is (= "1m 0s" (format-duration 59.5))))))

(deftest kondo-preflight-failure-test
  (let [commands (atom [])
        cleared? (atom false)
        launched? (atom false)
        result (with-redefs [invoke-command! (fn [cmd dir]
                                              (swap! commands conj [cmd dir])
                                              {:exit 127 :out "" :err "clj-kondo missing"})
                             clean-implementation-dir! (fn [& _] (reset! cleared? true))
                             phase-loop! (fn [& _] (reset! launched? true))
                             decrypt-challenge! (fn [& _])
                             decrypt-other-challenges! (fn [& _])
                             has-hidden-teardown? (constantly false)]
                 (binding [*out* (java.io.StringWriter.)]
                   (run-challenge {:name "bank-transfer-module"} "codex" {}
                                  "/project" "model" "high" "test-key")))]
    (is (= [[["bash" "scripts/import-kondo-configs.sh" "bank-transfer-module"]
             "/project"]] @commands))
    (is (= :fail (:status result)))
    (is (re-find #"clj-kondo missing" (:error result)))
    (is (false? @cleared?))
    (is (false? @launched?))))

(def sample-results
  [{:name "challenge-a" :status :pass :iterations 1 :duration-s 10
    :input-tokens 100 :output-tokens 50 :cache-creation-tokens 10 :cache-read-tokens 80
    :skills-used ["rama" "rama-app-design"]
    :skill-refs-used ["paths.md" "task-globals.md"]
    :scoring {:scores {:alignment 5}
              :composite 5.0}}
   {:name "challenge-b" :status :fail :iterations 3 :duration-s 50
    :input-tokens 200 :output-tokens 75 :cache-creation-tokens 20 :cache-read-tokens 150
    :skills-used ["rama"]
    :skill-refs-used []
    :scoring nil}])

(deftest parse-token-usage-test
  ;; Tests parsing of token usage from Claude's NDJSON output.
  ;; Contract: sums usage fields across result-type messages, defaults to 0
  ;; for missing fields, handles malformed/empty input gracefully.
  (testing "parse-token-usage"
    (testing "when given valid NDJSON with multiple result messages"
      (let [output (str "{\"type\":\"result\",\"usage\":{\"input_tokens\":100,\"output_tokens\":50,\"cache_creation_input_tokens\":10,\"cache_read_input_tokens\":80}}\n"
                        "{\"type\":\"result\",\"usage\":{\"input_tokens\":200,\"output_tokens\":75,\"cache_creation_input_tokens\":20,\"cache_read_input_tokens\":150}}\n")]
        (is (= {:input-tokens 300
                :output-tokens 125
                :cache-creation-tokens 30
                :cache-read-tokens 230}
               (parse-token-usage output)))))

    (testing "when given mixed message types"
      (let [output (str "{\"type\":\"assistant\",\"content\":\"hello\"}\n"
                        "{\"type\":\"result\",\"usage\":{\"input_tokens\":50,\"output_tokens\":25,\"cache_creation_input_tokens\":5,\"cache_read_input_tokens\":40}}\n"
                        "{\"type\":\"tool_use\",\"name\":\"bash\"}\n")]
        (is (= {:input-tokens 50
                :output-tokens 25
                :cache-creation-tokens 5
                :cache-read-tokens 40}
               (parse-token-usage output)))))

    (testing "when result message has no usage field"
      (let [output "{\"type\":\"result\",\"content\":\"done\"}\n"]
        (is (= {:input-tokens 0
                :output-tokens 0
                :cache-creation-tokens 0
                :cache-read-tokens 0}
               (parse-token-usage output)))))

    (testing "when given empty input"
      (is (= {:input-tokens 0
              :output-tokens 0
              :cache-creation-tokens 0
              :cache-read-tokens 0}
             (parse-token-usage ""))))

    (testing "when given nil input"
      (is (= {:input-tokens 0
              :output-tokens 0
              :cache-creation-tokens 0
              :cache-read-tokens 0}
             (parse-token-usage nil))))

    (testing "when given non-JSON lines"
      (let [output "not json\n{\"type\":\"result\",\"usage\":{\"input_tokens\":10,\"output_tokens\":5,\"cache_creation_input_tokens\":1,\"cache_read_input_tokens\":8}}\ngarbage\n"]
        (is (= {:input-tokens 10
                :output-tokens 5
                :cache-creation-tokens 1
                :cache-read-tokens 8}
               (parse-token-usage output)))))

    (testing "when usage has partial fields"
      (let [output "{\"type\":\"result\",\"usage\":{\"input_tokens\":10}}\n"]
        (is (= {:input-tokens 10
                :output-tokens 0
                :cache-creation-tokens 0
                :cache-read-tokens 0}
               (parse-token-usage output)))))

    (testing "when given a single JSON object (--output-format json)"
      (let [output "{\"type\":\"result\",\"subtype\":\"success\",\"session_id\":\"abc\",\"usage\":{\"input_tokens\":3,\"cache_creation_input_tokens\":22042,\"cache_read_input_tokens\":0,\"output_tokens\":5}}"]
        (is (= {:input-tokens 3
                :output-tokens 5
                :cache-creation-tokens 22042
                :cache-read-tokens 0}
               (parse-token-usage output)))))

    (testing "when given Codex JSONL with turn.completed events"
      (let [output (str "{\"type\":\"thread.started\",\"thread_id\":\"abc\"}\n"
                        "{\"type\":\"turn.started\"}\n"
                        "{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":100,\"cached_input_tokens\":40,\"output_tokens\":50}}\n")]
        ;; Codex input_tokens includes cached tokens; canonical input excludes them.
        (is (= {:input-tokens 60
                :output-tokens 50
                :cache-creation-tokens 0
                :cache-read-tokens 40}
               (parse-token-usage output)))))

    (testing "when given Codex JSONL with multiple turn.completed events"
      (let [output (str "{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":100,\"cached_input_tokens\":40,\"output_tokens\":50}}\n"
                        "{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":200,\"cached_input_tokens\":80,\"output_tokens\":75}}\n")]
        (is (= {:input-tokens 180
                :output-tokens 125
                :cache-creation-tokens 0
                :cache-read-tokens 120}
               (parse-token-usage output)))))

    (testing "when Codex reports cache writes, they are split out of input"
      (let [output "{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":100,\"cached_input_tokens\":40,\"cache_write_input_tokens\":10,\"output_tokens\":5}}\n"]
        (is (= {:input-tokens 50
                :output-tokens 5
                :cache-creation-tokens 10
                :cache-read-tokens 40}
               (parse-token-usage output)))))))

(deftest integer-reported-cost-test
  ;; Pi reports JSON integer zero when a provider rejects before generation.
  (is (= "$0.0000" (format-cost 0)))
  (is (= "$1.0000" (format-cost 1)))
  (is (= "$0.1250" (format-cost 0.125)))
  (is (= "N/A" (format-cost nil)))
  (let [tmp (str (fs/create-temp-dir))]
    (fs/create-dirs (fs/path tmp "project"))
    (try
      (let [results [(assoc (first sample-results) :cost 0)]
            report (generate-report results "pi" (str (fs/path tmp "project")))]
        (is (str/includes? (slurp report) "$0.0000")))
      (finally (fs/delete-tree tmp)))))

(deftest model-pricing-test
  (testing "model->pricing"
    (testing "recognizes the four current Codex models"
      (is (= {:input 10.00 :output 50.00 :cache-write 12.50 :cache-read 1.00}
             (model->pricing "gpt-6-astra")))
      (is (= {:input 4.00 :output 20.00 :cache-write 5.00 :cache-read 0.40}
             (model->pricing "gpt-5.6-sol")))
      (is (= {:input 2.00 :output 12.00 :cache-write 2.50 :cache-read 0.20}
             (model->pricing "gpt-5.6-terra")))
      (is (= {:input 0.20 :output 1.20 :cache-write 0.25 :cache-read 0.02}
             (model->pricing "gpt-5.6-luna"))))))

(deftest token-totals-test
  ;; Tests that token-totals sums token fields across result maps,
  ;; handling nil values gracefully.
  (testing "token-totals"
    (testing "sums token fields across results"
      (is (= {:input-tokens 300
              :output-tokens 125
              :cache-creation-tokens 30
              :cache-read-tokens 230}
             (token-totals sample-results))))
    (testing "returns zeros for empty results"
      (is (= {:input-tokens 0
              :output-tokens 0
              :cache-creation-tokens 0
              :cache-read-tokens 0}
             (token-totals []))))
    (testing "treats nil token values as zero"
      (is (= {:input-tokens 100
              :output-tokens 50
              :cache-creation-tokens 10
              :cache-read-tokens 80}
             (token-totals [{:name "a" :input-tokens 100 :output-tokens 50
                             :cache-creation-tokens 10 :cache-read-tokens 80}
                            {:name "b"}]))))))

(deftest parse-skills-used-test
  ;; Tests extraction of skill names from Claude NDJSON transcripts.
  ;; Contract: returns sorted, deduplicated skill names from Skill tool_use blocks.
  (testing "parse-skills-used"
    (testing "when transcript contains Skill tool uses"
      (let [output (str/join "\n"
                    ["{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"name\":\"Skill\",\"input\":{\"skill\":\"rama\"}}]}}"
                     "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"name\":\"Read\",\"input\":{\"file_path\":\"/tmp/x\"}}]}}"
                     "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"name\":\"Skill\",\"input\":{\"skill\":\"rama-app-design\"}}]}}"])]
        (is (= ["rama" "rama-app-design"] (parse-skills-used output)))))
    (testing "when same skill is used multiple times"
      (let [output (str/join "\n"
                    ["{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"name\":\"Skill\",\"input\":{\"skill\":\"rama\"}}]}}"
                     "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"name\":\"Skill\",\"input\":{\"skill\":\"rama\"}}]}}"])]
        (is (= ["rama"] (parse-skills-used output)))))
    (testing "when no skills are used"
      (let [output "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"name\":\"Read\",\"input\":{}}]}}"]
        (is (= [] (parse-skills-used output)))))
    (testing "when Codex reads SKILL.md from skills directory"
      (let [output (str "{\"type\":\"item.started\",\"item\":{\"type\":\"command_execution\",\"command\":\"/bin/zsh -lc \\\"sed -n '1,220p' .codex/skills/rama-challenges/SKILL.md\\\"\"}}\n"
                        "{\"type\":\"item.started\",\"item\":{\"type\":\"command_execution\",\"command\":\"/bin/zsh -lc \\\"sed -n '1,260p' plugins/rama-skill/skills/rama/SKILL.md\\\"\"}}\n"
                        "{\"type\":\"item.started\",\"item\":{\"type\":\"command_execution\",\"command\":\"/bin/zsh -lc \\\"sed -n '1,240p' plugins/rama-skill/skills/rama-app-design/SKILL.md\\\"\"}}\n")]
        (is (= ["rama" "rama-app-design" "rama-challenges"] (parse-skills-used output)))))
    (testing "when Codex reads same skill multiple times"
      (let [output (str "{\"type\":\"item.started\",\"item\":{\"type\":\"command_execution\",\"command\":\"/bin/zsh -lc \\\"sed -n '1,260p' plugins/rama-skill/skills/rama/SKILL.md\\\"\"}}\n"
                        "{\"type\":\"item.started\",\"item\":{\"type\":\"command_execution\",\"command\":\"/bin/zsh -lc \\\"sed -n '680,760p' plugins/rama-skill/skills/rama/SKILL.md\\\"\"}}\n")]
        (is (= ["rama"] (parse-skills-used output)))))
    (testing "when given empty input"
      (is (= [] (parse-skills-used ""))))
    (testing "when given nil input"
      (is (= [] (parse-skills-used nil))))))

(deftest parse-skill-refs-used-test
  ;; Tests extraction of skill reference filenames from agent transcripts.
  ;; Contract: returns sorted, deduplicated reference filenames from tool_use
  ;; blocks that access references/*.md files.
  (testing "parse-skill-refs-used"
    (testing "when Claude reads a reference file via Read tool"
      (let [output (str "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"name\":\"Read\",\"input\":{\"file_path\":\"/project/.claude/skills/rama/references/task-globals.md\"}}]}}\n")]
        (is (= ["task-globals.md"] (parse-skill-refs-used output)))))
    (testing "when multiple different references are accessed"
      (let [output (str/join "\n"
                    ["{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"name\":\"Read\",\"input\":{\"file_path\":\"/project/skills/rama/references/paths.md\"}}]}}"
                     "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"name\":\"Read\",\"input\":{\"file_path\":\"/project/skills/rama/references/aggregators.md\"}}]}}"])]
        (is (= ["aggregators.md" "paths.md"] (parse-skill-refs-used output)))))
    (testing "when same reference is read multiple times"
      (let [output (str/join "\n"
                    ["{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"name\":\"Read\",\"input\":{\"file_path\":\"/project/references/paths.md\"}}]}}"
                     "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"name\":\"Read\",\"input\":{\"file_path\":\"/project/references/paths.md\"}}]}}"])]
        (is (= ["paths.md"] (parse-skill-refs-used output)))))
    (testing "when Grep searches within a reference file"
      (let [output "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"name\":\"Grep\",\"input\":{\"path\":\"/project/references/pstate-schema.md\",\"pattern\":\"subindex\"}}]}}\n"]
        (is (= ["pstate-schema.md"] (parse-skill-refs-used output)))))
    (testing "when no references are accessed"
      (let [output "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"name\":\"Read\",\"input\":{\"file_path\":\"/project/src/my/module.clj\"}}]}}\n"]
        (is (= [] (parse-skill-refs-used output)))))
    (testing "when Codex reads a reference file"
      (let [output "{\"type\":\"item.started\",\"item\":{\"type\":\"command_execution\",\"command\":\"/bin/zsh -lc \\\"cat plugins/rama-skill/skills/rama/references/testing.md\\\"\"}}\n"]
        (is (= ["testing.md"] (parse-skill-refs-used output)))))
    (testing "when given empty input"
      (is (= [] (parse-skill-refs-used ""))))
    (testing "when given nil input"
      (is (= [] (parse-skill-refs-used nil))))))

(deftest print-summary-table-test
  ;; Tests that print-summary-table outputs token columns, token totals,
  ;; and elapsed time when provided.
  ;; Contract: table includes token columns and totals; total-elapsed-s
  ;; nil → no elapsed line, non-nil → "Total elapsed: ..." line
  (testing "print-summary-table"
    (testing "when total-elapsed-s is provided"
      (let [output (with-out-str (print-summary-table sample-results 222))]
        (is (re-find #"Total elapsed: 3m 42s" output)
            "should include formatted elapsed time")))
    (testing "when total-elapsed-s is nil"
      (let [output (with-out-str (print-summary-table sample-results nil))]
        (is (not (re-find #"Total elapsed" output))
            "should not include elapsed time line")))
    (testing "when called with single arity"
      (let [output (with-out-str (print-summary-table sample-results))]
        (is (not (re-find #"Total elapsed" output))
            "should not include elapsed time line")))
    (testing "includes token, skills, and score column headers"
      (let [output (with-out-str (print-summary-table sample-results))]
        (is (re-find #"In Tokens" output))
        (is (re-find #"Out Tokens" output))
        (is (re-find #"Cache Create" output))
        (is (re-find #"Cache Read" output))
        (is (re-find #"Skills" output))
        (is (re-find #"Align" output))))
    (testing "includes per-challenge token values and skills"
      (let [output (with-out-str (print-summary-table sample-results))]
        (is (re-find #"challenge-a.*100" output))
        (is (re-find #"challenge-b.*200" output))
        (is (re-find #"rama, rama-app-design" output))
        (is (re-find #"challenge-b.*rama" output))))
    (testing "includes token totals in summary"
      (let [output (with-out-str (print-summary-table sample-results))]
        (is (re-find #"Tokens: In: 300 \| Out: 125 \| Cache Create: 30 \| Cache Read: 230" output))))
    (testing "includes average score and alignment"
      (let [output (with-out-str (print-summary-table sample-results))]
        (is (re-find #"Average score:" output))
        (is (re-find #"Average alignment: 5\.0/5" output))))
    (testing "when no results have alignment scores"
      (let [unscored [{:name "ch" :status :fail :iterations 1 :duration-s 5
                        :input-tokens 10 :output-tokens 5
                        :cache-creation-tokens 0 :cache-read-tokens 0
                        :challenge-score 0
                        :skills-used [] :scoring nil}]
            output (with-out-str (print-summary-table unscored))]
        (is (not (re-find #"Average alignment" output)))))))

(deftest generate-report-test
  ;; Tests that generate-report includes token columns, token totals,
  ;; and elapsed time in the markdown report.
  ;; Contract: table has token columns; footer has token totals;
  ;; total-elapsed-s nil → no elapsed line, non-nil → "**Total elapsed:** ..." line
  (testing "generate-report"
    (let [tmp-root (str (babashka.fs/create-temp-dir))
          project-dir (str (babashka.fs/path tmp-root "project"))]
      (babashka.fs/create-dirs project-dir)
      (try
        (testing "when total-elapsed-s is provided"
          (let [path (generate-report sample-results "test" project-dir {:total-elapsed-s 222})
                content (slurp path)]
            (is (re-find #"\*\*Total elapsed:\*\* 3m 42s" content)
                "should include formatted elapsed time")))
        (testing "when total-elapsed-s is nil"
          (let [path (generate-report sample-results "test" project-dir {})
                content (slurp path)]
            (is (not (re-find #"Total elapsed" content))
                "should not include elapsed time")))
        (testing "when called with three-arity"
          (let [path (generate-report sample-results "test" project-dir)
                content (slurp path)]
            (is (not (re-find #"Total elapsed" content))
                "should not include elapsed time")))
        (testing "includes token, skills, and score column headers in markdown table"
          (let [path (generate-report sample-results "test" project-dir)
                content (slurp path)]
            (is (re-find #"In Tokens" content))
            (is (re-find #"Out Tokens" content))
            (is (re-find #"Cache Create" content))
            (is (re-find #"Cache Read" content))
            (is (re-find #"Skills" content))
            (is (re-find #"Align" content))))
        (testing "includes per-challenge token values and skills"
          (let [path (generate-report sample-results "test" project-dir)
                content (slurp path)]
            (is (re-find #"challenge-a.*100.*50.*10.*80" content))
            (is (re-find #"challenge-b.*200.*75.*20.*150" content))
            (is (re-find #"rama, rama-app-design" content))))
        (testing "includes token totals in footer"
          (let [path (generate-report sample-results "test" project-dir)
                content (slurp path)]
            (is (re-find #"\*\*Tokens:\*\* In: 300 \| Out: 125 \| Cache Create: 30 \| Cache Read: 230" content))))
        (testing "includes average score and alignment in footer"
          (let [path (generate-report sample-results "test" project-dir)
                content (slurp path)]
            (is (re-find #"\*\*Average score:\*\*" content))
            (is (re-find #"\*\*Average alignment \(informational\):\*\* 5\.0/5" content))))
        (testing "when model is specified"
          (let [path (generate-report sample-results "claude" project-dir {:total-elapsed-s 100 :model "sonnet"})
                content (slurp path)]
            (is (re-find #"-claude-sonnet\.md$" path)
                "filename should include model")
            (is (re-find #"Model: sonnet" content)
                "content should include Model line")))
        (testing "when model is nil"
          (let [path (generate-report sample-results "claude" project-dir {:total-elapsed-s 100})
                content (slurp path)]
            (is (re-find #"-claude\.md$" path)
                "filename should not include model")
            (is (not (re-find #"Model:" content))
                "content should not include Model line")))
        (testing "when reasoning is specified with model"
          (let [path (generate-report sample-results "claude" project-dir {:total-elapsed-s 100 :model "sonnet" :reasoning "high"})
                content (slurp path)]
            (is (re-find #"-claude-sonnet-high\.md$" path)
                "filename should include model and reasoning")
            (is (re-find #"Reasoning: high" content)
                "content should include Reasoning line")))
        (testing "when reasoning is specified without model"
          (let [path (generate-report sample-results "claude" project-dir {:total-elapsed-s 100 :reasoning "medium"})
                content (slurp path)]
            (is (re-find #"-claude\.md$" path)
                "filename should not include reasoning when model is absent")
            (is (re-find #"Reasoning: medium" content)
                "content should include Reasoning line")))
        (finally
          (babashka.fs/delete-tree tmp-root))))))

(deftest generate-report-location-test
  ;; Tests that generate-report writes files under ../reports
  ;; relative to the provided project root.
  (testing "generate-report stores report under ../reports"
    (let [tmp-root (str (babashka.fs/create-temp-dir))
          project-dir (str (babashka.fs/path tmp-root "project"))]
      (babashka.fs/create-dirs project-dir)
      (try
        (let [path (generate-report sample-results "test" project-dir)
              expected-dir (-> (babashka.fs/path project-dir ".." "reports")
                               babashka.fs/normalize
                               str)
              actual-dir (-> path
                             babashka.fs/parent
                             babashka.fs/normalize
                             str)]
          (is (= expected-dir actual-dir)
              "report should be written under ../reports"))
        (finally
          (babashka.fs/delete-tree tmp-root))))))

(deftest discover-all-challenges-test
  ;; Tests that challenge discovery finds runnable challenge directories even
  ;; when they are absent from CHALLENGE_ORDER.md, while excluding templates
  ;; and shared support dirs.
  (testing "discover-all-challenges"
    (let [tmp-root (str (babashka.fs/create-temp-dir))
          project-dir (str (babashka.fs/path tmp-root "project"))
          challenges-dir (babashka.fs/path project-dir "challenges")]
      (babashka.fs/create-dirs challenges-dir)
      (doseq [name ["listed-challenge"
                    "unlisted-challenge"
                    "unlisted-hard"]]
        (let [dir (babashka.fs/path challenges-dir name)]
          (babashka.fs/create-dirs dir)
          (spit (str (babashka.fs/path dir "README.md")) (str "# " name "\n"))))
      (try
        (let [ordered [{:name "listed-challenge" :batch 2 :difficulty :standard}]
              discovered (discover-all-challenges project-dir ordered)
              by-name (into {} (map (juxt :name identity) discovered))]
          (is (= #{"listed-challenge" "unlisted-challenge" "unlisted-hard"}
                 (set (keys by-name))))
          (is (= {:name "listed-challenge" :batch 2 :difficulty :standard}
                 (get by-name "listed-challenge"))
              "should preserve metadata from CHALLENGE_ORDER")
          (is (= {:name "unlisted-challenge" :batch nil :difficulty :standard}
                 (get by-name "unlisted-challenge"))
              "should infer standard difficulty for unlisted challenges")
          (is (= {:name "unlisted-hard" :batch nil :difficulty :hard}
                 (get by-name "unlisted-hard"))
              "should infer hard difficulty from -hard suffix"))
        (finally
          (babashka.fs/delete-tree tmp-root))))))

(deftest claude-phase-cmd-test
  ;; Tests that claude-phase-cmd uses stream-json for transcript support,
  ;; embeds the /challenge-phase invocation, and appends --model and --effort
  ;; when provided.
  (testing "claude-phase-cmd"
    (testing "always uses stream-json output format"
      (let [cmd (claude-phase-cmd "test-ch" 0 "/root" nil nil)]
        (is (some #{"stream-json"} cmd) "should use stream-json format")
        (is (some #{"--verbose"} cmd) "should include --verbose")))
    (testing "embeds the /challenge-phase invocation with name and phase-id"
      (let [cmd (claude-phase-cmd "test-ch" 2 "/root" nil nil)]
        (is (some #{"/challenge-phase test-ch 2"} cmd)
            "should pass the slash command with name and phase-id")))
    (testing "when model and reasoning are nil"
      (let [cmd (claude-phase-cmd "test-ch" 0 "/root" nil nil)]
        (is (not (some #{"--model"} cmd)) "should not contain --model")
        (is (not (some #{"--effort"} cmd)) "should not contain --effort")))
    (testing "when model is specified"
      (let [cmd (claude-phase-cmd "test-ch" 0 "/root" "sonnet" nil)]
        (is (some #{"--model"} cmd) "should contain --model")
        (is (some #{"sonnet"} cmd) "should contain model value")
        (is (not (some #{"--effort"} cmd)) "should not contain --effort")))
    (testing "when reasoning is specified"
      (let [cmd (claude-phase-cmd "test-ch" 0 "/root" nil "high")]
        (is (not (some #{"--model"} cmd)) "should not contain --model")
        (is (some #{"--effort"} cmd) "should contain --effort")
        (is (some #{"high"} cmd) "should contain reasoning value")))
    (testing "when both model and reasoning are specified"
      (let [cmd (claude-phase-cmd "test-ch" 0 "/root" "sonnet" "medium")]
        (is (some #{"--model"} cmd) "should contain --model")
        (is (some #{"--effort"} cmd) "should contain --effort")))))

(deftest codex-phase-cmd-test
  ;; Tests that codex-phase-cmd passes --json for token tracking, embeds the
  ;; $challenge-phase invocation, and adds --model / -c reasoning when given.
  (testing "codex-phase-cmd"
    (testing "always includes --json for token tracking"
      (let [cmd (codex-phase-cmd "test-ch" 0 "/root" nil nil)]
        (is (some #{"--json"} cmd) "should always contain --json")))
    (testing "embeds the $challenge-phase invocation with name and phase-id"
      (let [cmd (codex-phase-cmd "test-ch" 2 "/root" nil nil)]
        (is (some #{"$challenge-phase test-ch 2"} cmd)
            "should pass the codex prompt with name and phase-id")))
    (testing "when model and reasoning are nil"
      (let [cmd (codex-phase-cmd "test-ch" 0 "/root" nil nil)]
        (is (not (some #{"--model"} cmd)) "should not contain --model")
        (is (not (some #{"-c"} cmd)) "should not contain -c")))
    (testing "when model is specified"
      (let [cmd (codex-phase-cmd "test-ch" 0 "/root" "sonnet" nil)]
        (is (some #{"--model"} cmd) "should contain --model")
        (is (some #{"sonnet"} cmd) "should contain model value")))
    (testing "when reasoning is specified"
      (let [cmd (codex-phase-cmd "test-ch" 0 "/root" nil "high")]
        (is (some #{"-c"} cmd) "should contain -c")
        (is (some #{"model_reasoning_effort=high"} cmd) "should contain reasoning config")))
    (testing "when both model and reasoning are specified"
      (let [cmd (codex-phase-cmd "test-ch" 0 "/root" "sonnet" "high")]
        (is (some #{"--model"} cmd) "should contain --model")
        (is (some #{"sonnet"} cmd) "should contain model value")
        (is (some #{"-c"} cmd) "should contain -c")
        (is (some #{"model_reasoning_effort=high"} cmd) "should contain reasoning config")))))

(deftest phase-prompt-compatibility-test
  ;; Claude and native-agent phase instructions must capture the same
  ;; shareable engineering evidence without requesting private reasoning.
  (let [claude-prompt (slurp ".claude/commands/challenge-phase.md")
        native-prompt (slurp ".agents/skills/challenge-phase/SKILL.md")
        plan-template (slurp "plugins/rama-skill/skills/rama/references/artifact-plan.md")
        claude-cmd (claude-phase-cmd "test-ch" :decompose "/root" nil nil)
        codex-cmd (codex-phase-cmd "test-ch" :decompose "/root" nil nil)]
    (is (some #{"/challenge-phase test-ch decompose"} claude-cmd))
    (is (some #{"$challenge-phase test-ch decompose"} codex-cmd))
    (is (= claude-prompt native-prompt)
        "Claude and native agents must receive identical phase instructions")
    (is (re-find #"Decision:.*\n- Basis:.*\n- Outcome:" plan-template))
    (is (re-find #"not private chain-of-thought" plan-template))
    (is (not (re-find #"first-person|Write it as you design|how close the call was" plan-template)))
    (is (re-find #"Decision:.*\n  Basis:.*\n  Outcome:" native-prompt))
    (is (re-find #"(?i)do not write private chain-of-thought" native-prompt))
    (is (re-find #"rejected alternatives\s+and dead ends" native-prompt))
    (is (re-find #"CONFUSION:" native-prompt))
    (is (not (re-find #"append your reasoning AT EACH DECISION POINT|the dead ends are the point" native-prompt)))))

(deftest tier-cli-parsing-test
  ;; The four required model flags parse into the opts map.
  (testing "model-tier CLI options"
    (let [opts (cli/parse-opts ["--fast-model" "opus" "--fast-effort" "low"
                                "--slow-model" "fable" "--slow-effort" "high"
                                "--agent" "claude"]
                               {:spec cli-spec})]
      (is (= "opus" (:fast-model opts)))
      (is (= "low" (:fast-effort opts)))
      (is (= "fable" (:slow-model opts)))
      (is (= "high" (:slow-effort opts))))
    (testing "absent flags leave the keys nil"
      (let [opts (cli/parse-opts ["--agent" "claude"] {:spec cli-spec})]
        (is (nil? (:fast-model opts)))
        (is (nil? (:slow-effort opts)))))))

(deftest tier-config-test
  ;; tier-config resolves [model reasoning] from the dynamic tier vars.
  (testing "tier-config picks the right tier"
    (binding [*fast-model* "opus"
              *fast-reasoning* "low"
              *slow-model* "fable"
              *slow-reasoning* "high"]
      (is (= ["opus" "low"] (tier-config :fast)))
      (is (= ["fable" "high"] (tier-config :slow))))))

(deftest tier-routing-help-test
  (let [help (with-out-str (print-usage))]
    (is (re-find #"Fast model: subsystem build phases" help))
    (is (re-find #"Slow model: phase 0, decompose, planning, validation, full-spec-review" help))
    (is (re-find #"Phase 0, decompose, planning \(phase 1\), plan-validation \(phase 2\), and" help))
    (is (re-find #"full-spec-review run on the slow model; subsystem build phases use the fast model" help))))

(deftest print-run-header-model-test
  ;; Tests that print-run-header shows model in parentheses when provided
  ;; and the effort label inline. Format: "Agent: <name>[ (<model>)] [effort: <level>] | ...".
  (testing "print-run-header"
    (testing "when model is specified"
      (let [output (with-out-str (print-run-header "claude" 5 {} "sonnet" nil))]
        (is (re-find #"Agent: claude \(sonnet\)" output)
            "should show model in parentheses")))
    (testing "when model is nil"
      (let [output (with-out-str (print-run-header "claude" 5 {} nil nil))]
        (is (re-find #"Agent: claude \[effort:" output)
            "should not show parentheses around the agent name")
        (is (not (re-find #"Agent: claude \(" output))
            "should have no parentheses immediately after agent name")))
    (testing "when reasoning is specified with model"
      (let [output (with-out-str (print-run-header "claude" 5 {} "sonnet" "high"))]
        (is (re-find #"Agent: claude \(sonnet\)" output)
            "should show model in parentheses")
        (is (re-find #"\[effort: high\]" output)
            "should show effort inline")))
    (testing "when reasoning is specified without model"
      (let [output (with-out-str (print-run-header "claude" 5 {} nil "medium"))]
        (is (re-find #"\[effort: medium\]" output)
            "should show effort inline")))))

(deftest save-transcript-test
  ;; Tests that save-transcript! writes content under ../transcripts relative
  ;; to project root, encodes phase-id and (when > 1) attempt in the filename,
  ;; and uses run-start-time for the {date}-{time} prefix so all transcripts of
  ;; one challenge run share that prefix.
  (testing "save-transcript!"
    (let [tmp-root (str (babashka.fs/create-temp-dir))
          project-dir (str (babashka.fs/path tmp-root "project"))
          run-start (java.time.LocalDateTime/now)]
      (babashka.fs/create-dirs project-dir)
      (try
        (testing "writes content and returns path"
          (let [path (save-transcript! project-dir "claude" nil nil "my-challenge"
                                       "jsonl content" 0 1 run-start)]
            (is (= "jsonl content" (slurp path)))
            (is (re-find #"my-challenge-phase0\.jsonl$" path))))
        (testing "path is under ../transcripts"
          (let [path (save-transcript! project-dir "claude" nil nil "ch" "x" 0 1 run-start)
                expected-dir (-> (babashka.fs/path project-dir ".." "transcripts")
                                 babashka.fs/normalize str)
                actual-dir   (-> path babashka.fs/parent babashka.fs/normalize str)]
            (is (= expected-dir actual-dir))))
        (testing "includes model in filename when provided"
          (let [path (save-transcript! project-dir "claude" "sonnet" nil "ch" "x" 0 1 run-start)]
            (is (re-find #"-claude-sonnet-ch-phase0\.jsonl$" path))))
        (testing "includes model and reasoning in filename when both provided"
          (let [path (save-transcript! project-dir "claude" "sonnet" "high" "ch" "x" 0 1 run-start)]
            (is (re-find #"-claude-sonnet-high-ch-phase0\.jsonl$" path))))
        (testing "encodes phase-id and attempt when attempt > 1"
          (let [path (save-transcript! project-dir "claude" nil nil "ch" "x" 3 2 run-start)]
            (is (re-find #"-ch-phase3-attempt2\.jsonl$" path))))
        (testing "all phases of one run share the {date}-{time} prefix"
          (let [p0 (save-transcript! project-dir "claude" nil nil "ch" "x" 0 1 run-start)
                p3 (save-transcript! project-dir "claude" nil nil "ch" "x" 3 1 run-start)
                prefix (fn [p] (-> p babashka.fs/file-name str
                                   (clojure.string/replace #"-ch-phase\d+(-attempt\d+)?\.jsonl$" "")))]
            (is (= (prefix p0) (prefix p3)))))
        (finally
          (babashka.fs/delete-tree tmp-root))))))

(deftest transient-server-error-test
  ;; Regression guard for a bug that quadrupled every run: the detector used to
  ;; regex the agent's raw stdout, which carries a `rate_limit_info` block on
  ;; every Claude Code invocation and routinely carries bare numbers like 500 in
  ;; agent prose. Every phase therefore looked like a server error and was
  ;; re-run *phase-retry-cap* extra times.
  (testing "a successful run is never transient, however its stdout reads"
    (let [success (str "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,"
                       "\"rate_limit_info\":{\"status\":\"allowed\",\"rateLimitType\":\"five_hour\"},"
                       "\"result\":\"ran in 500 ms over 502 assertions; see http 429 notes\"}")]
      (is (false? (transient-server-error? success "")))))
  (testing "a tool_result error inside the stream is a phase outcome, not infra"
    (let [tool-err (str "{\"type\":\"user\",\"message\":{\"content\":"
                        "[{\"type\":\"tool_result\",\"is_error\":true,"
                        "\"content\":\"bash: connection reset by peer\"}]}}")]
      (is (false? (transient-server-error? tool-err "")))))
  (testing "the run's own error result IS transient"
    (is (true? (transient-server-error?
                (str "{\"type\":\"result\",\"subtype\":\"error_during_execution\","
                     "\"is_error\":true,\"result\":\"API Error: 529 Overloaded\"}")
                ""))))
  (testing "stderr is scanned in full"
    (is (true? (transient-server-error? "" "socket hang up")))
    (is (true? (transient-server-error? "" "Error: 503 Service Unavailable"))))
  (testing "non-JSON stdout is scanned in full (CLI died before structured output)"
    (is (true? (transient-server-error? "upstream connect error: bad gateway" ""))))
  (testing "the output-token-maximum error stays non-transient"
    (is (false? (transient-server-error?
                 (str "{\"type\":\"result\",\"subtype\":\"error_max_tokens\",\"is_error\":true,"
                      "\"result\":\"API Error: max output tokens exceeded\"}")
                 "")))))

(deftest infrastructure-failure-test
  (let [failure (fn [exit out err]
                  (infrastructure-failure {:exit exit :out out :err err
                                           :canonical (if (seq out)
                                                        (normalize-agent-output out) "")}))]
    (are [expected exit out err] (= expected (failure exit out err))
      :infra-error 137 "" ""
      :infra-error 1 "" "java.lang.OutOfMemoryError: Java heap space"
      :quota-or-provider-limit 1 "" "HTTP 501 Not Implemented"
      :quota-or-provider-limit 1 "{\"type\":\"error\",\"error\":{\"message\":\"content_filter blocked response\"}}" ""
      :quota-or-provider-limit 1 "" "Provider response blocked by safety filters"
      :quota-or-provider-limit 1 "" "insufficient_quota"
      nil 1 "" "Syntax error in module.clj"
      nil 0 "PHASE_VALIDATION:pass\n" "HTTP 503 in an unrelated warning"
      nil 1 "{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"tool_result\",\"content\":\"HTTP 503\"}]}}" ""
      nil 130 "" "HTTP 503")))

(deftest infrastructure-phase-retry-test
  (let [tmp (str (fs/create-temp-dir))
        project (str (fs/path tmp "project"))
        started (java.time.LocalDateTime/now)
        scripted (fn [responses]
                   (let [calls (atom [])
                         remaining (atom responses)
                         r (with-redefs [invoke-command! (fn [cmd _]
                                                           (swap! calls conj cmd)
                                                           (let [answer (first @remaining)]
                                                             (swap! remaining rest)
                                                             (merge {:out "" :err "" :duration-s 0}
                                                                    answer)))]
                             (binding [*phase-retry-backoff-ms* 0]
                               (run-phase! {:phase-cmd (fn [& _] ["fixed-agent" "--model" "pinned-model"])}
                                           "demo" :build 1 nil project "codex" "pinned-model" "high"
                                           started (System/currentTimeMillis))))]
                     [r @calls]))]
    (fs/create-dirs project)
    (try
      (doseq [[initial category] [[{:exit 137} :infra-error]
                                  [{:exit 1 :err "Out of memory"} :infra-error]
                                  [{:exit 1 :err "HTTP 503"} :quota-or-provider-limit]
                                  [{:exit 1 :out "{\"type\":\"error\",\"error\":\"safety filter blocked\"}"}
                                   :quota-or-provider-limit]]]
        (let [[r calls] (scripted [initial {:exit 1 :err (:err initial) :out (:out initial)}])
              completion (classify-completion {:status :fail :phase-results [r]})]
          (is (= 2 (count calls)) (pr-str initial))
          (is (= (first calls) (second calls)) "retry keeps the exact command and pin")
          (is (= 1 (:infra-retries r)))
          (is (= category completion))
          (is (nil? (compute-challenge-score (classify-outcome
                                               {:completion completion :private-status :not-run
                                                :has-implementation? false}) 0)))
          (is (fs/exists? (:transcript-path r)))
          (is (str/includes? (:transcript-path r) "-retry1.jsonl"))))
      (let [[r calls] (scripted [{:exit 137} {:exit 0 :out "PHASE_VALIDATION:pass"}])]
        (is (= 2 (count calls)))
        (is (= 0 (:exit r)))
        (is (= :pass (:verdict r)))
        (is (= 1 (:infra-retries r)))
        (is (zero? (count-semantic-retries [r])))
        (is (= 100 (compute-challenge-score :public-pass (count-semantic-retries [r])))))
      (let [[r calls] (scripted [{:exit 0 :out "{\"type\":\"error\",\"error\":\"HTTP 503\"}"}
                                 {:exit 0 :out "PHASE_VALIDATION:pass"}])]
        (is (= 2 (count calls)) "structured provider error retries despite CLI exit zero")
        (is (= 1 (:infra-retries r)))
        (is (= :pass (:verdict r))))
      (let [[r calls] (scripted [{:exit 137} {:exit 1 :out "PHASE_VALIDATION:fail"}])]
        (is (= 2 (count calls)))
        (is (= :solver-fail (classify-completion {:status :fail :phase-results [r]}))
            "an explicit solver FAIL on retry is not masked"))
      (let [[r calls] (scripted [{:exit 1 :err "HTTP 503"} {:exit 1 :err "process exited"}])]
        (is (= 2 (count calls)))
        (is (= :infra-error (classify-completion {:status :fail :phase-results [r]}))
            "an interrupted retry without solver verdict is not solver failure"))
      (let [[r calls] (scripted [{:exit 137} {:exit 1 :timed-out? true}])]
        (is (= 2 (count calls)))
        (is (not (:infra-error? r)))
        (is (= :timeout (classify-completion {:status :timeout :phase-results [r]}))))
      (let [[r calls] (scripted [{:exit 137} {:exit 130}])]
        (is (= 2 (count calls)))
        (is (= :user-stopped (classify-completion {:status :fail :phase-results [r]}))))
      (let [[r calls] (scripted [{:exit 1 :out "PHASE_VALIDATION:fail"}])]
        (is (= 1 (count calls)))
        (is (= 0 (:infra-retries r)))
        (is (= :fail (:verdict r))))
      (finally (fs/delete-tree tmp)))))

(deftest save-transcript-retry-test
  ;; A transient-error re-invocation is the SAME attempt run again, so it gets a
  ;; `-retry{R}` segment rather than sharing (and overwriting) the attempt's
  ;; filename. Before this, only the last invocation's transcript survived.
  (let [tmp-root (str (babashka.fs/create-temp-dir))
        project-dir (str (babashka.fs/path tmp-root "project"))
        run-start (java.time.LocalDateTime/now)]
    (babashka.fs/create-dirs project-dir)
    (try
      (testing "retry 0 is unsuffixed"
        (let [path (save-transcript! project-dir "claude" nil nil "ch" "x" 3 1 run-start nil 0)]
          (is (re-find #"-ch-phase3\.jsonl$" path))))
      (testing "retries get their own files, distinct from attempt 1's"
        (let [p0 (save-transcript! project-dir "claude" nil nil "ch" "a" 3 1 run-start nil 0)
              p1 (save-transcript! project-dir "claude" nil nil "ch" "b" 3 1 run-start nil 1)]
          (is (re-find #"-ch-phase3-retry1\.jsonl$" p1))
          (is (not= p0 p1))
          (is (= "a" (slurp p0)) "the earlier invocation's transcript survives")
          (is (= "b" (slurp p1)))))
      (testing "attempt and retry compose"
        (let [path (save-transcript! project-dir "claude" nil nil "ch" "x" 3 2 run-start "alpha" 2)]
          (is (re-find #"-ch-alpha-phase3-attempt2-retry2\.jsonl$" path))))
      (finally
        (babashka.fs/delete-tree tmp-root)))))

(deftest parse-phase-verdict-test
  (testing "returns nil when no verdict present"
    (is (nil? (parse-phase-verdict "just some text"))))

  (testing "extracts simple verdicts"
    (is (= :pass (parse-phase-verdict "PHASE_VALIDATION:pass")))
    (is (= :fail (parse-phase-verdict "PHASE_VALIDATION:fail")))
    (is (= :minor-fail (parse-phase-verdict "PHASE_VALIDATION:minor-fail")))
    (is (= :major-fail (parse-phase-verdict "PHASE_VALIDATION:major-fail"))))

  (testing "returns last verdict, not first (phase doc tool_result contaminates earlier in stream)"
    (is (= :fail
           (parse-phase-verdict
             (str "Emit either PHASE_VALIDATION:pass or PHASE_VALIDATION:fail as the last line. "
                  "...lots of agent reasoning here...\n"
                  "PHASE_VALIDATION:fail\n"))))
    (is (= :minor-fail
           (parse-phase-verdict
             (str "Emit one of PHASE_VALIDATION:pass, PHASE_VALIDATION:minor-fail, or "
                  "PHASE_VALIDATION:major-fail as the last line.\n"
                  "PHASE_VALIDATION:minor-fail\n")))))

  (testing "minor-fail and major-fail are not parsed as :fail"
    (is (= :minor-fail (parse-phase-verdict "PHASE_VALIDATION:minor-fail")))
    (is (= :major-fail (parse-phase-verdict "PHASE_VALIDATION:major-fail")))))

(deftest phase-cmd-subsystem-test
  ;; Tests the subsystem-aware arity of the phase command builders and the
  ;; rendering of keyword stage ids (decompose, full-spec-review).
  (testing "claude-phase-cmd"
    (testing "appends the subsystem slug as a third argument"
      (let [cmd (claude-phase-cmd "test-ch" 3 "/root" nil nil "alpha")]
        (is (some #{"/challenge-phase test-ch 3 alpha"} cmd))))
    (testing "renders keyword stage ids as their names"
      (let [cmd (claude-phase-cmd "test-ch" :full-spec-review "/root" nil nil nil)]
        (is (some #{"/challenge-phase test-ch full-spec-review"} cmd)))
      (let [cmd (claude-phase-cmd "test-ch" :decompose "/root" nil nil nil)]
        (is (some #{"/challenge-phase test-ch decompose"} cmd)))))
  (testing "codex-phase-cmd"
    (testing "appends the subsystem slug as a third argument"
      (let [cmd (codex-phase-cmd "test-ch" 3 "/root" nil nil "alpha")]
        (is (some #{"$challenge-phase test-ch 3 alpha"} cmd))))
    (testing "renders keyword stage ids as their names"
      (let [cmd (codex-phase-cmd "test-ch" :decompose "/root" nil nil nil)]
        (is (some #{"$challenge-phase test-ch decompose"} cmd))))))

(deftest save-transcript-subsystem-test
  ;; Tests subsystem and keyword-stage encoding in transcript filenames.
  ;; Contract: subsystem slug appears between challenge name and phase suffix;
  ;; keyword stage ids render as their names; no subsystem → unchanged names.
  (testing "save-transcript! with subsystem/stage ids"
    (let [tmp-root (str (babashka.fs/create-temp-dir))
          project-dir (str (babashka.fs/path tmp-root "project"))
          run-start (java.time.LocalDateTime/now)]
      (babashka.fs/create-dirs project-dir)
      (try
        (testing "includes subsystem slug before the phase suffix"
          (let [path (save-transcript! project-dir "claude" nil nil "ch" "x" 3 2 run-start "alpha")]
            (is (re-find #"-ch-alpha-phase3-attempt2\.jsonl$" path))))
        (testing "renders keyword stage ids in the filename"
          (let [path (save-transcript! project-dir "claude" nil nil "ch" "x" :full-spec-review 1 run-start nil)]
            (is (re-find #"-ch-phasefull-spec-review\.jsonl$" path))))
        (testing "nil subsystem keeps the historical filename shape"
          (let [path (save-transcript! project-dir "claude" nil nil "ch" "x" 3 1 run-start nil)]
            (is (re-find #"-ch-phase3\.jsonl$" path))))
        (finally
          (babashka.fs/delete-tree tmp-root))))))

;;; Phase-loop routing simulation
;;;
;;; Drives phase-loop! end-to-end through a STUBBED agent-fns :phase-cmd — no
;;; real agent runs, but the real run-phase! path (reasoning sentinel append,
;;; subprocess invocation, verdict parsing, transcript saving) is exercised.
;;; The stub returns a bash command that echoes the scripted PHASE_VALIDATION
;;; verdict; the decompose stage's command also writes the DECOMPOSITION.json
;;; fixture, exactly as a real agent would. Every invocation is recorded as
;;; [phase-id subsystem attempt], letting the tests assert the full routing:
;;; phase 0 → decompose → per-subsystem 1..7 cycles → full-spec review.

(defn- passing-verdicts
  "Default verdict script: every gate passes; non-verdict phases emit none."
  [phase-id _subsystem _attempt]
  (cond
    (= 2 phase-id) :pass
    (contains? #{:build :full-spec-review} phase-id) :pass
    :else nil))

(defn- decompose-fixture-script
  "Shell script for the decompose stage: writes the DECOMPOSITION.json fixture
  (relative to project-root, where invoke-command! runs) like a real agent
  would, or does nothing when the scenario omits the file."
  [challenge decomposition]
  (if decomposition
    (str "mkdir -p implementations/" challenge
         " && cat > implementations/" challenge "/DECOMPOSITION.json <<'EOF'\n"
         (json/generate-string decomposition)
         "\nEOF")
    "true"))

(defn- scripted-phase-cmd
  "Build an agent-fns :phase-cmd stub. Records [phase-id subsystem attempt]
  into `invocations` (attempt = how many times this [phase-id subsystem] pair
  has been invoked so far) and returns a bash command whose stdout carries the
  scripted PHASE_VALIDATION verdict, if any."
  [invocations verdict-fn challenge decomposition]
  (fn [_challenge-name phase-id _project-root _model _reasoning subsystem]
    (let [attempt (inc (count (filter (fn [[p s _]] (and (= p phase-id) (= s subsystem)))
                                      @invocations)))]
      (swap! invocations conj [phase-id subsystem attempt])
      (let [verdict (verdict-fn phase-id subsystem attempt)
            base    (if (= :decompose phase-id)
                      (decompose-fixture-script challenge decomposition)
                      "true")
            script  (cond-> base
                      verdict (str " && echo PHASE_VALIDATION:" (name verdict)))]
        ["bash" "-c" script]))))

(defn- simulate-phase-loop
  "Run phase-loop! against a stubbed agent-fns :phase-cmd. Options:
    :verdict-fn     (fn [phase-id subsystem attempt] verdict-or-nil) — defaults
                    to passing-verdicts
    :decomposition  EDN vector the stubbed decompose stage writes to
                    DECOMPOSITION.json, or nil to leave the file missing
  Returns {:result      <phase-loop! result>
           :invocations [[phase-id subsystem attempt] ...]
           :transcripts [transcript file names written by save-transcript!]
           :reasoning   REASONING.md content (runner-written sentinels)}."
  [{:keys [verdict-fn decomposition]}]
  (let [verdict-fn (or verdict-fn passing-verdicts)
        tmp-root (str (babashka.fs/create-temp-dir))
        project-dir (str (babashka.fs/path tmp-root "project"))
        challenge "sim-ch"
        impl-dir (babashka.fs/path project-dir "implementations" challenge)
        invocations (atom [])
        agent-fns {:phase-cmd (scripted-phase-cmd invocations verdict-fn
                                                  challenge decomposition)}]
    (babashka.fs/create-dirs impl-dir)
    (try
      (with-redefs [save-attempt! (fn [_project-root _challenge-name]
                                    {:exit 0 :out "" :err "" :duration-s 0})]
        (let [result (binding [*err* (java.io.StringWriter.)] ; silence decomposition warnings
                       (phase-loop! agent-fns challenge project-dir
                                    "claude" nil nil
                                    (java.time.LocalDateTime/now)
                                    (System/currentTimeMillis)))
              reasoning-path (babashka.fs/path impl-dir "REASONING.md")]
          {:result result
           :invocations @invocations
           :transcripts (->> (babashka.fs/glob (babashka.fs/path tmp-root "transcripts") "*.jsonl")
                             (mapv #(str (babashka.fs/file-name %)))
                             sort
                             vec)
           :reasoning (if (babashka.fs/exists? reasoning-path)
                        (slurp (str reasoning-path))
                        "")}))
      (finally
        (babashka.fs/delete-tree tmp-root)))))

(def ^:private single-subsystem-decomposition
  [{:name "whole" :scope "Build the whole module per the full spec."}])

(def ^:private two-subsystem-decomposition
  [{:name "alpha" :scope "Alpha scope."}
   {:name "beta" :scope "Beta scope."}])

(deftest read-decomposition-test
  ;; Tests parsing of DECOMPOSITION.json: a JSON array of subsystem objects
  ;; ({"name": ..., "scope": ...}) in dependency order; the runner consumes the
  ;; "name" order (difficulty is decided later by phase 2, not here). Every
  ;; entry must be an object with non-empty "name" and "scope" strings;
  ;; anything malformed returns nil (single-subsystem fallback) with a stderr
  ;; warning, never a throw.
  (testing "read-decomposition"
    (let [tmp-root (str (babashka.fs/create-temp-dir))
          challenge "rd-ch"
          impl-dir (babashka.fs/path tmp-root "implementations" challenge)
          decomp-path (babashka.fs/path impl-dir "DECOMPOSITION.json")
          write! (fn [content]
                   (babashka.fs/create-dirs impl-dir)
                   (spit (str decomp-path) content))
          read! (fn []
                  (binding [*err* (java.io.StringWriter.)] ; silence warnings
                    (read-decomposition tmp-root challenge)))]
      (try
        (testing "array of name+scope objects yields {:name} maps in order"
          (write! (json/generate-string [{:name "alpha" :scope "base state"}
                                         {:name "beta" :scope "derived views"}]))
          (is (= [{:name "alpha"} {:name "beta"}] (read!))))
        (testing "any extra keys (e.g. difficulty) are ignored"
          (write! (json/generate-string [{:name "alpha" :scope "s" :difficulty "hard"}]))
          (is (= [{:name "alpha"}] (read!))))
        (testing "plain name strings are rejected"
          (write! "[\"graph\", \"delivery\"]")
          (is (nil? (read!))))
        (testing "legacy \"spec\" key is rejected"
          (write! (json/generate-string [{:name "graph" :spec "base state"}]))
          (is (nil? (read!))))
        (testing "missing scope falls back to nil"
          (write! (json/generate-string [{:name "graph" :scope "s"} {:name "delivery"}]))
          (is (nil? (read!))))
        (testing "blank scope falls back to nil"
          (write! (json/generate-string [{:name "graph" :scope "  "}]))
          (is (nil? (read!))))
        (testing "names are trimmed"
          (write! (json/generate-string [{:name " graph " :scope "s1"}
                                         {:name "delivery" :scope "s2"}]))
          (is (= [{:name "graph"} {:name "delivery"}] (read!))))
        (testing "duplicate names fall back to nil"
          (write! (json/generate-string [{:name "graph" :scope "s1"}
                                         {:name "graph" :scope "s2"}]))
          (is (nil? (read!))))
        (testing "blank name falls back to nil"
          (write! (json/generate-string [{:name "graph" :scope "s1"}
                                         {:name "  " :scope "s2"}]))
          (is (nil? (read!))))
        (testing "non-object entry falls back to nil"
          (write! (str "[" (json/generate-string {:name "graph" :scope "s1"}) ", 42]"))
          (is (nil? (read!))))
        (testing "empty array falls back to nil"
          (write! "[]")
          (is (nil? (read!))))
        (testing "not-an-array falls back to nil"
          (write! "{\"name\": \"graph\"}")
          (is (nil? (read!))))
        (testing "unparseable JSON falls back to nil"
          (write! "[\"graph\"")
          (is (nil? (read!))))
        (testing "missing file falls back to nil"
          (babashka.fs/delete decomp-path)
          (is (nil? (read!))))
        (finally
          (babashka.fs/delete-tree tmp-root))))))

(deftest phase-loop-single-subsystem-test
  ;; A one-subsystem decomposition must collapse to the historical pipeline —
  ;; no subsystem slug on any invocation — plus decompose and one full-spec
  ;; review.
  (testing "single-subsystem happy path"
    (let [{:keys [result invocations transcripts reasoning]}
          (simulate-phase-loop {:decomposition single-subsystem-decomposition})]
      (is (= [[0 nil 1] [:decompose nil 1]
              [1 nil 1] [2 nil 1] [:build nil 1]
              [:full-spec-review nil 1]]
             invocations)
          "sequence = phase 0, decompose, plan, plan-validate, build, full-spec review")
      (is (= :pass (:status result)))
      (is (= 1 (:iterations result)))
      (testing "transcript filenames have NO subsystem segment"
        (is (some #(re-find #"-sim-ch-phasebuild\.jsonl$" %) transcripts))
        (is (some #(re-find #"-sim-ch-phasedecompose\.jsonl$" %) transcripts))
        (is (some #(re-find #"-sim-ch-phasefull-spec-review\.jsonl$" %) transcripts))
        (is (not-any? #(re-find #"-whole-" %) transcripts)
            "single-subsystem run must not tag transcripts with the slug"))
      (testing "reasoning sentinels have NO subsystem tag"
        (is (re-find #"=== PHASE BUILD attempt 1 — " reasoning))
        (is (re-find #"=== PHASE DECOMPOSE attempt 1 — " reasoning))
        (is (not (re-find #"\[whole\]" reasoning))))))
  (testing "missing DECOMPOSITION.json falls back to a single unsuffixed cycle"
    (let [{:keys [result invocations]}
          (simulate-phase-loop {:decomposition nil})]
      (is (= [[0 nil 1] [:decompose nil 1]
              [1 nil 1] [2 nil 1] [:build nil 1]
              [:full-spec-review nil 1]]
             invocations))
      (is (= :pass (:status result)))))
  (testing "malformed DECOMPOSITION.json falls back to a single unsuffixed cycle"
    (let [{:keys [result invocations]}
          (simulate-phase-loop {:decomposition {:not "a vector"}})]
      (is (= [[0 nil 1] [:decompose nil 1]
              [1 nil 1] [2 nil 1] [:build nil 1]
              [:full-spec-review nil 1]]
             invocations))
      (is (= :pass (:status result))))))

(deftest phase-loop-two-subsystem-test
  ;; Two subsystems each run plan → plan-validate → build, in dependency order,
  ;; with the slug on every cycle invocation. Plan-retry counters must be FRESH
  ;; per subsystem: each survives 3 consecutive phase-2 major-fails independently.
  (testing "two-subsystem cycles with fresh plan-retry counters"
    (let [{:keys [result invocations transcripts reasoning]}
          (simulate-phase-loop
           {:decomposition two-subsystem-decomposition
            ;; Phase 2 major-fails on attempts 1-3 and passes on attempt 4 —
            ;; in BOTH subsystems. With a shared counter the second subsystem
            ;; would exceed the cap.
            :verdict-fn (fn [phase-id _subsystem attempt]
                          (if (= 2 phase-id)
                            (if (<= attempt 3) :major-fail :pass)
                            (passing-verdicts phase-id _subsystem attempt)))})
          cycle-invs (filterv (fn [[_ s _]] (some? s)) invocations)
          alpha-invs (filterv (fn [[_ s _]] (= "alpha" s)) invocations)
          beta-invs  (filterv (fn [[_ s _]] (= "beta" s)) invocations)]
      (is (= :pass (:status result)))
      (is (= [[0 nil 1] [:decompose nil 1]] (take 2 invocations)))
      (is (= [:full-spec-review nil 1] (last invocations)))
      (testing "every cycle invocation carries a subsystem slug"
        (is (every? (fn [[_ s _]] (contains? #{"alpha" "beta"} s)) cycle-invs)))
      (testing "alpha's whole cycle runs before beta starts"
        (let [subsystem-order (mapv second (remove (fn [[_ s _]] (nil? s)) invocations))]
          (is (= ["alpha" "beta"] (vec (distinct subsystem-order))))
          (is (apply <= (map {"alpha" 0 "beta" 1} subsystem-order))
              "no alpha invocation after the first beta invocation")))
      (testing "plan retries happen independently in each subsystem"
        (is (= [[1 "alpha" 1] [2 "alpha" 1] [1 "alpha" 2] [2 "alpha" 2]
                [1 "alpha" 3] [2 "alpha" 3] [1 "alpha" 4] [2 "alpha" 4]
                [:build "alpha" 1]]
               alpha-invs))
        (is (= [[1 "beta" 1] [2 "beta" 1] [1 "beta" 2] [2 "beta" 2]
                [1 "beta" 3] [2 "beta" 3] [1 "beta" 4] [2 "beta" 4]
                [:build "beta" 1]]
               beta-invs)
            "beta gets its own 3 retries — counters and attempts reset"))
      (testing "transcript filenames carry the subsystem segment before the phase suffix"
        (is (some #(re-find #"-sim-ch-alpha-phasebuild\.jsonl$" %) transcripts))
        (is (some #(re-find #"-sim-ch-beta-phasebuild\.jsonl$" %) transcripts))
        (is (some #(re-find #"-sim-ch-alpha-phase2-attempt4\.jsonl$" %) transcripts))
        (is (some #(re-find #"-sim-ch-phasedecompose\.jsonl$" %) transcripts)
            "decompose stage is never subsystem-tagged")
        (is (some #(re-find #"-sim-ch-phasefull-spec-review\.jsonl$" %) transcripts)
            "full-spec review is never subsystem-tagged"))
      (testing "reasoning sentinels carry the subsystem tag"
        (is (re-find #"=== PHASE BUILD \[alpha\] attempt 1 — " reasoning))
        (is (re-find #"=== PHASE 2 \[beta\] attempt 4 — " reasoning)))
      (is (= 2 (:iterations result)) "one build invocation per subsystem"))))

(deftest phase-loop-full-spec-review-single-invocation-test
  ;; The full-spec stage is ONE agent session that reviews AND fixes, looping
  ;; internally. The runner never issues a second review and never issues a
  ;; separate fix session, whatever the verdict.
  (testing "a passing review is the last invocation of the run"
    (let [{:keys [result invocations]}
          (simulate-phase-loop
           {:decomposition single-subsystem-decomposition
            :verdict-fn passing-verdicts})]
      (is (= :pass (:status result)))
      (is (= [:full-spec-review nil 1] (last invocations)))
      (is (= 1 (count (filterv (fn [[p _ _]] (= :full-spec-review p)) invocations)))
          "exactly one review invocation")
      (is (empty? (filterv (fn [[p _ _]] (= :full-spec-fix p)) invocations))
          "no separate fix session exists")))

  (testing "a failing review fails the run without re-running or fixing"
    (let [{:keys [result invocations]}
          (simulate-phase-loop
           {:decomposition single-subsystem-decomposition
            :verdict-fn (fn [phase-id subsystem attempt]
                          (if (= :full-spec-review phase-id)
                            :fail
                            (passing-verdicts phase-id subsystem attempt)))})]
      (is (= :fail (:status result)))
      (is (re-find #"unresolved items" (:failure-reason result)))
      (is (= 1 (count (filterv (fn [[p _ _]] (= :full-spec-review p)) invocations)))
          "still exactly one review invocation — no runner-side retry")
      (is (empty? (filterv (fn [[p _ _]] (= :full-spec-fix p)) invocations))))))

(deftest phase-loop-subsystem-gate-cap-test
  ;; A gate-2 cap blowout in subsystem 1 fails the whole run naming the
  ;; subsystem; subsystem 2 and the full-spec review never run.
  (testing "subsystem-1 gate-2 cap exceeded"
    (let [{:keys [result invocations]}
          (simulate-phase-loop
           {:decomposition two-subsystem-decomposition
            :verdict-fn (fn [phase-id subsystem attempt]
                          (if (= 2 phase-id)
                            :major-fail
                            (passing-verdicts phase-id subsystem attempt)))})]
      (is (= :fail (:status result)))
      (is (re-find #"\[subsystem alpha\]" (:failure-reason result))
          "failure reason names the subsystem")
      (is (re-find #"Phase 2 failed validation 4 times consecutively" (:failure-reason result)))
      (is (empty? (filterv (fn [[_ s _]] (= "beta" s)) invocations))
          "subsystem beta never runs")
      (is (empty? (filterv (fn [[p _ _]] (= :full-spec-review p)) invocations))
          "full-spec review never runs"))))

(deftest isolated-solver-command-test
  (let [cmd ["opencode" "run" "--" "prompt with spaces"]]
    (is (= cmd (solver-command cmd "/project" "demo" "opencode")))
    (binding [*isolate* true]
      (is (= ["python3" "/project/scripts/isolate_solver.py"
              "--repo" "/project" "--challenge" "demo" "--agent" "opencode"
              "--" "opencode" "run" "--" "prompt with spaces"]
             (solver-command cmd "/project" "demo" "opencode"))))
    (binding [*isolate-network* true]
      (is (= ["python3" "/project/scripts/isolate_solver.py"
              "--repo" "/project" "--challenge" "demo" "--agent" "opencode"
              "--network" "strict" "--" "opencode" "run" "--" "prompt with spaces"]
             (solver-command cmd "/project" "demo" "opencode"))))))

(deftest native-harness-command-test
  (doseq [[agent binary effort-flag] [[:opencode "opencode" "--variant"]
                                      [:pi "pi" "--thinking"]]]
    (let [cmd ((get-in agents [agent :phase-cmd]) "demo" :full-spec-review
               "/project" "provider/model" "high" "orders")]
      (is (= binary (first cmd)))
      (is (some #{"json"} cmd))
      (is (some #{effort-flag} cmd))
      (is (some #{"provider/model"} cmd))
      (is (re-find #"demo full-spec-review orders" (last cmd)))
      (is (re-find #"Read .agents/skills/challenge-phase/SKILL.md" (last cmd)))))
  (is (= ["pi" "--print" "--mode" "json" "--no-session" "prompt"]
         (pi-prompt-cmd nil nil "prompt"))))

(deftest native-harness-process-test
  ;; A real child process emits a native fixture, then the real save and
  ;; normalization boundaries run. No provider/network calls or challenge data.
  (doseq [agent ["opencode" "pi" "codex" "claude"]]
    (let [tmp (str (fs/create-temp-dir))
          project (str (fs/path tmp "project"))
          raw (slurp (str "scripts/fixtures/transcripts/" agent ".jsonl"))]
      (fs/create-dirs project)
      (try
        (let [r (binding [*outer-timeout-s* 10]
                  (run-phase! {:phase-cmd (fn [& _] ["printf" "%s" raw])}
                              "demo" 0 1 nil project agent "provider/model" "high"
                              (java.time.LocalDateTime/now) (System/currentTimeMillis)))
              saved (slurp (:transcript-path r))
              normalized (normalize-agent-output saved)
              summary (result-event normalized)]
          (is (= 0 (:exit r)))
          (is (= :pass (:verdict r)))
          (is (= 32 (get-in r [:token-usage :input-tokens])))
          (is (= 18 (get-in r [:token-usage :output-tokens])))
          (is (= 8 (get-in r [:token-usage :cache-read-tokens])))
          (is (= (if (= agent "codex") 2 6) (:tool-uses r)))
          (is (= (if (= agent "codex") [] ["paths.md"]) (:skill-refs-used r)))
          (is (= (if (= agent "codex") nil 0.02) (:cost r)))
          (is (str/includes? saved raw) "native history remains intact")
          (is (str/includes? (:transcript-path r) "provider_model"))
          (is (number? (:duration_ms summary)))
          (is (str/includes? (:result summary) "PHASE_VALIDATION:pass"))
          (is (fs/exists? (generate-report sample-results agent project {:model "provider/model"}))))
        (finally (fs/delete-tree tmp))))))

(deftest native-harness-errors-and-skills-test
  (is (transient-server-error?
        "{\"type\":\"error\",\"error\":{\"message\":\"HTTP 503\"}}" ""))
  (is (transient-server-error?
        "{\"type\":\"message_end\",\"message\":{\"stopReason\":\"error\",\"errorMessage\":\"HTTP 429\"}}" ""))
  (is (not (transient-server-error?
             "{\"type\":\"tool_execution_end\",\"isError\":true,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"HTTP 503\"}]}}" "")))
  (is (= ["rama"]
         (parse-skills-used
           (normalize-agent-output
             "{\"type\":\"tool_use\",\"part\":{\"tool\":\"read\",\"state\":{\"input\":{\"filePath\":\".agents/skills/rama/SKILL.md\"}}}}")))))

(deftest native-error-with-zero-process-exit-test
  (let [tmp (str (fs/create-temp-dir))
        project (str (fs/path tmp "project"))]
    (fs/create-dirs project)
    (try
      (let [r (run-phase! {:phase-cmd (fn [& _]
                                      ["printf" "%s" "{\"type\":\"error\",\"error\":\"invalid model\"}"])}
                          "demo" 0 1 nil project "opencode" nil nil
                          (java.time.LocalDateTime/now) (System/currentTimeMillis))]
        (is (= 1 (:exit r)))
        (is (nil? (:verdict r))))
      (finally (fs/delete-tree tmp)))))

(deftest native-scoring-test
  (doseq [agent [:opencode :pi :codex]]
    (let [text "ALIGNMENT_SCORE:4\nTEST_ALIGNMENT_SCORE:3\njustification"
          event (case agent
                  :opencode {:type "text" :part {:text text}}
                  :pi {:type "message_end" :message {:role "assistant" :content [{:type "text" :text text}]}}
                  :codex {:type "item.completed" :item {:type "agent_message" :text text}})
          adapter {:prompt-cmd (fn [model effort prompt]
                                 (is (= "provider/model" model))
                                 (is (nil? effort))
                                 (is (= "score prompt" prompt))
                                 ["printf" "%s" (json/generate-string event)])}]
      (with-redefs [build-alignment-prompt (constantly "score prompt")
                    build-test-alignment-prompt (constantly "score prompt")
                    agent-tests-use-harness? (constantly false)]
        (is (= 4 (:alignment (run-alignment-scoring! "." "demo" "provider/model" adapter))))
        (is (= 3 (:test-alignment (run-test-alignment-scoring! "." "demo" "provider/model" adapter))))))))

;;; Outcome taxonomy regressions (docs/outcome-taxonomy.md)

(def private-real-fail {:exit 1 :out "Ran 12 tests containing 40 assertions.\n3 failures, 0 errors.\n"})
(def private-sentinel-0-of-1 {:exit 1 :out "Ran 1 tests containing 1 assertions.\n0 failures, 1 errors.\n"})
(def private-real-pass {:exit 0 :out "Ran 12 tests containing 40 assertions.\n0 failures, 0 errors.\n"})

(defn phase [phase-id subsystem verdict]
  {:phase-id phase-id :subsystem subsystem :verdict verdict :exit 0})

(defn scored-result
  "Mirror run-challenge's outcome/score derivation for fixture data."
  [phase-result has-suite? private-result]
  (let [pv (classify-private-result has-suite? private-result)
        completion (classify-completion phase-result)
        outcome (classify-outcome {:completion completion
                                   :private-status (:private-status pv)
                                   :has-implementation? true})
        prs (:phase-results phase-result)
        retries (count-semantic-retries prs)]
    (merge pv {:name "fixture" :status (:status phase-result) :outcome outcome
               :completion completion :has-private-suite? has-suite?
               :builds (count (filter #(= :build (:phase-id %)) prs))
               :retries retries :iterations (:iterations phase-result)
               :challenge-score (compute-challenge-score outcome retries)
               :duration-s 1 :input-tokens 0 :output-tokens 0
               :cache-creation-tokens 0 :cache-read-tokens 0 :phase-results prs})))

(def auction-runner-pass
  {:status :pass :iterations 1
   :phase-results [(phase 1 nil :pass) (phase 2 nil :pass) (phase :build nil :pass)
                   (phase :full-spec-review nil :pass)]})

(deftest private-verdict-sentinels-test
  (testing "real failures are FAIL; sentinels are UNAVAILABLE, never FAIL"
    (is (= :fail (:private-status (classify-private-result true private-real-fail))))
    (is (= :pass (:private-status (classify-private-result true private-real-pass))))
    (doseq [r [private-sentinel-0-of-1
               {:exit 0 :out "Ran 0 tests containing 0 assertions.\n0 failures, 0 errors.\n"}
               {:exit 1 :out "" :err "Syntax error compiling at (module.clj:1:1)"}
               {:exit 124 :out "" :timed-out? true :timeout-s 5}
               {:exit 1 :out "Ran 3 tests containing 3 assertions.\n0 failures, 0 errors.\n"}]]
      (is (= :unavailable (:private-status (classify-private-result true r))) (pr-str r))))
  (testing "0/1 sentinel is named in the reason"
    (is (re-find #"sentinel 0/1" (:private-reason (classify-private-result true private-sentinel-0-of-1)))))
  (testing "a genuine single-test assertion failure stays FAIL"
    (is (= :fail (:private-status (classify-private-result
                                   true {:exit 1 :out "Ran 1 tests containing 2 assertions.\n1 failures, 0 errors.\n"}))))))

(deftest outcome-enum-test
  (testing "every required outcome value exists"
    (is (every? (set outcome-order)
                [:private-pass :private-fail :private-unavailable :solver-no-implementation
                 :infra-error :quota-or-provider-limit :user-stopped :timeout])))
  (testing "completion comes from the failing phase, not the runner status alone"
    (is (= :completed (classify-completion {:status :pass})))
    (is (= :quota-or-provider-limit
           (classify-completion {:status :fail :phase-results [{:provider-limit? true}]})))
    (is (= :infra-error
           (classify-completion {:status :fail :phase-results [{:infra-error? true}]})))
    (is (= :user-stopped (classify-completion {:status :fail :phase-results [{:user-stopped? true}]})))
    (is (= :timeout (classify-completion {:status :timeout :phase-results [{:timed-out? true}]})))
    (is (= :solver-fail (classify-completion {:status :fail :phase-results [{:verdict :fail}]}))))
  (testing "headline outcome for each path; the private verdict dominates"
    (are [expected in] (= expected (classify-outcome in))
      :infra-error              {:infra-error? true :private-status :pass}
      :infra-error              {:completion :infra-error :private-status :pass}
      :private-pass             {:completion :solver-fail :private-status :pass}
      :private-fail             {:completion :completed :private-status :fail}
      :timeout                  {:completion :timeout :private-status :not-run}
      :quota-or-provider-limit  {:completion :quota-or-provider-limit :private-status :not-run}
      :user-stopped             {:completion :user-stopped :private-status :unavailable}
      :solver-no-implementation {:completion :solver-fail :private-status :unavailable
                                 :has-implementation? false}
      :private-unavailable      {:completion :completed :private-status :unavailable
                                 :has-implementation? true}
      :public-pass              {:completion :completed :private-status :none}
      :solver-fail              {:completion :solver-fail :private-status :none}))
  (testing "only correctness outcomes are scored; infrastructure outcomes are not"
    (is (= [100 0 0 0 nil nil nil nil]
           (mapv #(compute-challenge-score % 0)
                 [:private-pass :private-fail :solver-no-implementation :timeout
                  :private-unavailable :infra-error :quota-or-provider-limit :user-stopped])))))

(deftest auction-runner-pass-private-fail-test
  (testing "runner PASS + private FAIL headlines PRIVATE-FAIL with score 0"
    (let [r (scored-result auction-runner-pass true private-real-fail)
          out (with-out-str (print-summary-table [r]))]
      (is (= :completed (:completion r)))
      (is (= :private-fail (:outcome r)))
      (is (= 0 (:challenge-score r)))
      (is (re-find #"^PRIVATE-FAIL \| Private: FAIL \(3 failures" (challenge-headline r)))
      (is (re-find #"Runner: PASS" (challenge-headline r)) "runner status is labelled, not the headline")
      (is (re-find #"\| PRIVATE-FAIL +\| FAIL +\| PASS " out))
      (is (re-find #"PRIVATE-PASS: 0 \| PRIVATE-FAIL: 1" out))
      (is (not (re-find #"Private tests: \d+/\d+ passed" out)))))
  (testing "runner PASS + `Private FAIL 0/1` sentinel is PRIVATE-UNAVAILABLE, unscored, not FAIL"
    (let [r (scored-result auction-runner-pass true private-sentinel-0-of-1)
          out (with-out-str (print-summary-table [r]))]
      (is (= :private-unavailable (:outcome r)))
      (is (= :unavailable (:private-status r)))
      (is (nil? (:challenge-score r)))
      (is (re-find #"^PRIVATE-UNAVAILABLE \| Private: UNAVAIL \(sentinel 0/1" (challenge-headline r)))
      (is (re-find #"Private verdicts: PASS 0 \| FAIL 0 \| UNAVAILABLE \(not evaluated\) 1" out))
      (is (re-find #"Average score: - \(n=0 scored, 1 unscored\)" out))))
  (testing "headline never leads with PASS unless the private suite passed"
    (doseq [pr [private-real-fail private-sentinel-0-of-1 nil]]
      (let [r (scored-result auction-runner-pass true pr)]
        (is (not (re-find #"^(PASS|PRIVATE-PASS|PUBLIC-PASS)" (challenge-headline r))) (pr-str pr)))))
  (testing "private PASS is the only correctness PASS"
    (is (= :private-pass (:outcome (scored-result auction-runner-pass true private-real-pass))))))

(def social-three-subsystems
  {:status :pass :iterations 3
   :phase-results (vec (for [s ["follows" "fanout" "timeline"]
                             p [(phase 1 s :pass) (phase :build s :pass) (phase 3 s :pass)]]
                         p))})

(deftest social-three-subsystem-builds-not-penalized-test
  (testing "three independent subsystem builds are 3 builds and 0 retries"
    (let [r (scored-result social-three-subsystems true private-real-pass)]
      (is (= 3 (:builds r)))
      (is (= 0 (:retries r)))
      (is (= 100 (:challenge-score r)) "old formula scored 100/2^(3-1) = 25")))
  (testing "only a phase that follows a failed verdict in the same subsystem is a retry"
    (let [prs [(phase :build "follows" :fail) (phase :build "follows" :pass)
               (phase 3 "fanout" :major-fail) (phase :build "timeline" :pass)]]
      (is (= 1 (count-semantic-retries prs)))
      (is (= 50 (compute-challenge-score :private-pass 1)))))
  (testing "plan-validation MINOR_FAIL then build is normal progression, not a retry"
    (is (= 0 (count-semantic-retries [(phase 1 "follows" nil) (phase 2 "follows" :minor-fail)
                                      (phase :build "follows" :pass)]))))
  (testing "plan-validation MAJOR_FAIL sends the subsystem back to planning: one retry"
    (is (= 1 (count-semantic-retries [(phase 1 "fanout" nil) (phase 2 "fanout" :major-fail)
                                      (phase 1 "fanout" nil) (phase 2 "fanout" :pass)
                                      (phase :build "fanout" :pass)]))))
  (testing "retries never rescue a private failure"
    (is (= 0 (compute-challenge-score :private-fail 0)))))

(def isolation-fixture
  {:mode "bubblewrap-provider-network" :filesystem "bubblewrap" :network "strict"
   :probe "passed" :preflight "passed" :launcher "scripts/isolate_solver.py"
   :snapshot {:kind "allowlisted-public-copy" :audits {:fixture {:files 3 :violations 0}}}})

(def run-meta-fixture
  {:run-id "run-1" :started-at "t0" :finished-at "t1"
   :repo-sha "deadbeef" :repo-dirty? false :agent "claude"
   :requested {:model "m" :effort "high"} :grader-timeout-s 1800
   :resources {:orb-size "a1.large" :cpu-count 8 :memory-limit-bytes 15032385536
               :memory-available-bytes 11000000000 :grader-jvm-options grader-jvm-options}
   :isolation isolation-fixture
   :evaluator-log {:path "run.private.log" :path-relative-to "manifest-directory"
                   :sha256 (apply str (repeat 64 "0")) :bytes 0 :audience "evaluator-only"
                   :in-bundle false :scrubbed ["secret-env" "credential"]}})

(deftest run-manifest-test
  (let [r (assoc (scored-result auction-runner-pass true private-sentinel-0-of-1)
                 :implementation-sha256 "abc" :cost-reported 1.5 :cost-estimated 1.2)
        m (build-run-manifest run-meta-fixture [r])
        c (first (:challenges m))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires the solver isolation record"
          (build-run-manifest (dissoc run-meta-fixture :isolation) [r])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires measured Orb resources"
          (build-run-manifest (dissoc run-meta-fixture :resources) [r])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires the written evaluator log reference"
          (build-run-manifest (dissoc run-meta-fixture :evaluator-log) [r])))
    (is (= 4 (:schema-version m)))
    (is (= (:resources run-meta-fixture) (:resources m)))
    (is (= (:evaluator-log run-meta-fixture) (:evaluator-log m)))
    (is (= isolation-fixture (:isolation m)))
    (is (= redaction-policy (:redaction-policy m)))
    (is (= "run-1" (:run-id m)))
    (is (= {:head-sha "deadbeef" :dirty false} (:repo m)))
    (is (= "claude" (get-in m [:requested :agent])))
    (is (= "private-unavailable" (:outcome c)))
    (is (true? (:private-suite-available c)))
    (is (false? (:scored c)))
    (is (= [1.5 1.2] [(:cost-reported c) (:cost-estimated c)]))
    (is (= 4 (count (:phases c))))
    (is (= [1 "build"] [(:phase-id (first (:phases c))) (:phase-id (nth (:phases c) 2))]))
    (is (string? (json/generate-string m)))
    (let [dir (fs/create-temp-dir)
          report (str (fs/path dir "r.md"))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"evaluator log is missing or incomplete"
            (write-run-manifest! report m)))
      (is (not (fs/exists? (manifest-path report))) "no manifest claims a missing log")
      (let [m (build-run-manifest (assoc run-meta-fixture :evaluator-log
                                         (write-evaluator-log! report (str dir) "log\n"))
                                  [r])]
        (is (= (str (fs/path dir "r.manifest.json")) (write-run-manifest! report m)))
        (is (nil? (write-run-manifest! report {:run-id "other"})) "never overwrites")
        (is (= "run-1" (get (json/parse-string (slurp (manifest-path report))) "run-id"))))
      (fs/delete-tree dir))))

;;; Scrubbed diagnostics in the manifest and BUNDLE.json

(defn bundle-entries [bundle]
  (let [{:keys [exit out]} (p/shell {:out :string :err :string :continue true} "tar" "-tzf" bundle)]
    (when (zero? exit) (str/split-lines (str/trim out)))))

(defn read-bundle-json [bundle]
  (let [dir (fs/create-temp-dir {:prefix "bundle-extract-"})]
    (try
      (p/shell {:out :string :err :string} "tar" "-xzf" bundle "-C" (str dir))
      (let [[f] (fs/glob dir "*/BUNDLE.json")]
        (json/parse-string (slurp (str f)) true))
      (finally (fs/delete-tree dir)))))

(defn diagnostics-fixture
  "Project with protected and public challenge files and a solver review.
  Strings are assembled so this test file itself holds no fixture secret."
  []
  (let [root (fs/create-temp-dir {:prefix "diag-"})
        protected-line (str "(is (= 4711 (withdraw! client " "\"acct-overdraft-fixture\" 99999)))")
        protected-msg (str "overdraft beyond balance " "must be rejected with 4711")
        ref-line (str "(defn- hidden-ledger-invariant " "[ledger] (reduce + (vals ledger)))")
        secret (str "fixture-" "env-secret-" "value-42")
        api-key (str "sk-ant-" (apply str (repeat 30 "q")))]
    (doseq [[rel text] {"challenges/demo/README.md" "Withdrawals beyond balance are rejected.\n"
                        "challenges/demo/src/demo/protocol.clj" "(defprotocol Bank (withdraw! [c a n]))\n"
                        "challenges/demo/test-private/demo/private_test.clj"
                        (str "(deftest withdraw-test\n  (testing \"" protected-msg "\"\n    " protected-line "))\n")
                        "challenges/demo/test-resources/demo/module.clj" (str ref-line "\n")
                        "implementations/demo/src/demo/module.clj"
                        "(throw (ex-info \"depot append timed out\" {}))\n"
                        "implementations/demo/FULL_SPEC_REVIEW.md"
                        (str "# Full-spec review\n\n## Finding 1: withdraw! allowed negative balances\n"
                             "Spec says withdrawals beyond balance are rejected; fixed in withdraw!.\n\n"
                             "## Finding 2: depot partitioning\nRe-partitioned by account id.\n\n"
                             "## Finding 3\nOPENAI_API_KEY=" secret " was echoed by a tool; " api-key "\n\n"
                             "## Finding 4: private suite\nwithdraw-test said: " protected-msg "\n\n"
                             "Verdict: all findings fixed; suite green.\n")}]
      (fs/create-dirs (fs/parent (fs/path root rel)))
      (spit (str (fs/path root rel)) text))
    {:root (str root) :protected-line protected-line :protected-msg protected-msg
     :ref-line ref-line :secret secret :api-key api-key
     :env {"OPENAI_API_KEY" secret "PATH" "/usr/bin" "SHORT_TOKEN" "abc"}}))

(def review-phase
  (assoc (phase :full-spec-review nil :pass)
         :isolation "bubblewrap-provider-network"
         :result-text "Reviewed the whole spec: 3 findings, all fixed.\nPHASE_VALIDATION:pass"))

(defn diagnosed-result
  "scored-result plus the diagnostics run-challenge attaches."
  [{:keys [root env]} private-result]
  (let [r (scored-result (update auction-runner-pass :phase-results #(conj (pop %) review-phase))
                         true private-result)
        ctx (scrub-context root "demo" env)]
    (assoc r :private-test (private-test-diagnostic private-result
                                                    (classify-private-result true private-result) ctx)
           :full-spec-review (full-spec-review-diagnostic root "demo" (:phase-results r) ctx)
           :evaluator-diagnostics (evaluator-diagnostics root "demo" private-result (:phase-results r)
                                                         (:env-secrets ctx)))))

(def empty-corpus
  "A verified corpus with no protected and no public files."
  {:protected [] :public [] :implementation [] :paths []})

(deftest scrub-text-test
  (let [ctx {:env-secrets (secret-env-values {"MY_TOKEN" "abcdefgh12345" "HOME" "/home/x" "X_KEY" "short"})
             :corpus empty-corpus}
        gh (str "ghp_" (apply str (repeat 36 "a")))
        {:keys [text redactions]}
        (scrub-text (str "token abcdefgh12345\nAuthorization: Bearer abcdefghijklmnop\n"
                         "push https://user:hunter2pass@example.com/repo " gh "\n"
                         "password = hunter2hunter2\nexpected: (= 1 x)\nordinary line\n")
                    ctx)]
    (is (= [["MY_TOKEN" "abcdefgh12345"]] (:env-secrets ctx)) "short and non-secret names ignored")
    (is (not-any? #(str/includes? text %) ["abcdefgh12345" "abcdefghijklmnop" "hunter2pass" gh "hunter2hunter2"]))
    (is (str/includes? text "[REDACTED:env:MY_TOKEN]"))
    (is (str/includes? text "https://[REDACTED:url-credentials]@example.com/repo"))
    (is (str/includes? text "expected: (= 1 x)") "assertion lines are only redacted in private-test output")
    (is (str/includes? text "ordinary line"))
    (is (not (str/includes? text "]]")) "no double redaction")
    (is (= 1 (:secret-env redactions)))
    (is (= text (:text (scrub-text text ctx))) "idempotent")
    (let [line (apply str (repeat 200000 "a"))
          t0 (System/currentTimeMillis)]
      (is (= line (:text (scrub-text line ctx))))
      (is (= "[REDACTED:private-test-data]" (:text (scrub-text line (assoc ctx :private-test? true))))
          "a word outside the public vocabulary is private-test data")
      (is (< (- (System/currentTimeMillis) t0) 5000) "no quadratic backtracking on a 200 KB line"))))

(deftest scrubbing-without-a-corpus-fails-closed-test
  (doseq [ctx [{} {:corpus (assoc empty-corpus :unavailable "encrypted protected file")}]]
    (is (= {:text "[REDACTED:protected-index-unavailable]\n\n[REDACTED:protected-index-unavailable]\n"
            :redactions {:secret-env 0 :credential 0 :private-test-assertion 0 :protected-plaintext 0
                         :private-test-data 0 :protected-index-unavailable 2}}
           (scrub-text "Ran 1 tests containing 1 assertions.\n\nanything at all\n" ctx)))))

(deftest private-output-and-review-survive-manifest-and-bundle-test
  (let [{:keys [root protected-line protected-msg ref-line secret api-key] :as fx} (diagnostics-fixture)
        stdout (str "\nTesting demo.private-test\n\nFAIL in (withdraw-test) (private_test.clj:3)\n"
                    protected-msg "\n"
                    "expected: (= 4711 (withdraw! client \"acct\" 99999))\n"
                    "  actual: (not (= 4711 0))\n\n"
                    "ERROR in (deposit-test) (private_test.clj:9)\n"
                    "expected: (= 1 (deposit! client))\n"
                    "  actual: clojure.lang.ExceptionInfo: depot append timed out {:depot \"*deposits\"}\n"
                    " at demo.module$deposit_BANG_.invoke (module.clj:42)\n"
                    "echo " protected-line "\n"
                    "env leak " secret "\n"
                    "Ran 12 tests containing 40 assertions.\n2 failures, 1 errors.\n")
        stderr (str "WARNING: implementation emitted a reflection warning\n" ref-line "\n"
                    "Authorization: Bearer abcdefghijklmnopqrstu\n")
        private-result {:exit 1 :out stdout :err stderr :duration-s 7 :timeout-s 1800}
        r (diagnosed-result fx private-result)
        dir (fs/create-temp-dir {:prefix "bundle-"})
        report (str (fs/path dir "run.md"))]
    (try
      (let [{mpath :manifest bpath :bundle lpath :evaluator-log}
            (emit-run-artifacts! report root (dissoc run-meta-fixture :evaluator-log) [r])
            manifest-text (slurp mpath)
            from-file (json/parse-string manifest-text true)
            bundle (read-bundle-json bpath)
            log-bytes (fs/read-all-bytes lpath)
            log (String. ^bytes log-bytes "UTF-8")
            review (slurp (str (fs/path root "implementations/demo/FULL_SPEC_REVIEW.md")))]
        (is (= [(str (fs/path dir "run.bundle.tar.gz"))] [bpath]))
        (testing "evaluator log keeps complete private diagnostics, scrubbing only secrets"
          (is (= (str (fs/path dir "run.private.log")) lpath))
          (is (= {:path "run.private.log" :path-relative-to "manifest-directory"
                  :sha256 (sha256-hex log-bytes) :bytes (alength ^bytes log-bytes)
                  :audience "evaluator-only" :in-bundle false :scrubbed ["secret-env" "credential"]}
                 (:evaluator-log from-file) (:evaluator-log (:manifest bundle)))
              "manifest and bundle pin the log by path and SHA-256")
          (is (= "rw-------" (fs/posix->str (fs/posix-file-permissions lpath))))
          (is (str/includes? log "FAIL in (withdraw-test) (private_test.clj:3)\n"))
          (doseq [kept [protected-msg protected-line ref-line
                        "expected: (= 4711 (withdraw! client \"acct\" 99999))\n"
                        "  actual: (not (= 4711 0))\n"
                        "  actual: clojure.lang.ExceptionInfo: depot append timed out {:depot \"*deposits\"}\n"
                        "## Finding 4: private suite\nwithdraw-test said: " (:result-text review-phase)]]
            (is (str/includes? log kept) "evaluator log keeps protected and private-test text"))
          (doseq [[section text] [["private-test stdout" stdout] ["private-test stderr" stderr]
                                  ["full-spec-review FULL_SPEC_REVIEW.md" review]
                                  ["full-spec-review final message" (:result-text review-phase)]]
                  :let [text (-> text
                                 (str/replace secret "[REDACTED:env:OPENAI_API_KEY]")
                                 (str/replace api-key "[REDACTED:api-key]")
                                 (str/replace "abcdefghijklmnopqrstu" "[REDACTED:authorization]"))]]
            (is (str/includes? log (str "\n===== " (:name r) " | " section " | "
                                        (alength (.getBytes ^String text "UTF-8")) " bytes =====\n"
                                        text "\n===== end " (:name r) " | " section " =====\n"))
                (str section " is complete, byte for byte, except scrubbed secrets")))
          (doseq [leaked [secret api-key "abcdefghijklmnopqrstu"]]
            (is (not (str/includes? log leaked)) "evaluator log leaks a secret/credential"))
          (doseq [[label text] [["manifest" manifest-text] ["bundle" (json/generate-string bundle)]]
                  private ["FAIL in (withdraw-test)" "withdraw-test said: overdraft"
                           "depot append timed out {:depot" "=====" "EVALUATOR ONLY"]]
            (is (not (str/includes? text private)) (str label " holds evaluator-only text"))))
        (is (= ["run-1/" "run-1/BUNDLE.json"] (sort (bundle-entries bpath)))
            "the bundle holds BUNDLE.json only, never the evaluator log")
        (is (nil? (write-run-bundle! report {:run-id "other"})) "never overwrites")
        (is (= (sha256-hex (.getBytes ^String manifest-text "UTF-8")) (:manifest-sha256 bundle))
            "BUNDLE.json pins the exact manifest file")
        (is (= from-file (:manifest bundle)) "bundle carries the same manifest")
        (is (= "rama-ai-learn-run-bundle" (:kind bundle)))
        (is (= (get-in from-file [:redaction-policy]) (:redaction-policy bundle)))
        (doseq [[label doc] [["manifest" from-file] ["bundle" (:manifest bundle)]]]
          (testing label
            (let [c (first (:challenges doc))
                  pt (:private-test c)
                  fsr (:full-spec-review c)
                  out (:stdout pt)]
              (is (= "private-fail" (:outcome c)))
              (is (true? (:counted-as-failure pt)))
              (is (nil? (:sentinel pt)))
              (is (= [1 false 1800 7] [(:exit pt) (:timed-out pt) (:timeout-s pt) (:duration-s pt)]))
              (testing "line for line, protected runs and private-test data redacted in place"
                (is (= (str "\nTesting demo.private-test\n\n"
                            "FAIL in ([REDACTED:protected-plaintext]) (private_test.clj:3)\n"
                            "[REDACTED:protected-plaintext]\n"
                            "expected: [REDACTED:private-test-assertion]\n"
                            "  actual: [REDACTED:private-test-comparison]\n\n"
                            "ERROR in (deposit-test) (private_test.clj:9)\n"
                            "expected: [REDACTED:private-test-assertion]\n"
                            "  actual: clojure.lang.ExceptionInfo: depot append timed out [REDACTED:private-test-data]\n"
                            " at demo.module$deposit_BANG_.invoke (module.clj:42)\n"
                            "[REDACTED:private-test-data] ([REDACTED:protected-plaintext])))\n"
                            "[REDACTED:private-test-data] [REDACTED:env:OPENAI_API_KEY]\n"
                            "Ran 12 tests containing 40 assertions.\n2 failures, 1 errors.\n")
                       out))
                (is (= (count (str/split stdout #"\n" -1)) (count (str/split out #"\n" -1)))
                    "line-for-line: redaction never drops lines")
                (is (= (str "WARNING: [REDACTED:private-test-data] a reflection warning\n"
                            "([REDACTED:protected-plaintext])))\n"
                            "Authorization: Bearer [REDACTED:authorization]\n")
                       (:stderr pt)))
                (is (= {:secret-env 1 :credential 1 :private-test-assertion 3 :protected-plaintext 4
                        :private-test-data 4 :protected-index-unavailable 0}
                       (:redactions pt))))
              (testing "complete full-spec-review findings survive"
                (is (= {:ran true :verdict "pass" :exit 0 :report-present true :report-skipped nil
                        :report-path "implementations/demo/FULL_SPEC_REVIEW.md"}
                       (select-keys fsr [:ran :verdict :exit :report-present :report-skipped :report-path])))
                (is (= (-> review
                           (str/replace secret "[REDACTED:env:OPENAI_API_KEY]")
                           (str/replace api-key "[REDACTED:api-key]")
                           (str/replace (str "withdraw-test said: " protected-msg)
                                        "[REDACTED:protected-plaintext] said: [REDACTED:protected-plaintext]"))
                       (:report-text fsr))
                    "byte-for-byte except the two redacted secrets and the protected echo")
                (is (= (:result-text review-phase) (:final-message fsr)))
                (is (= 1 (:secret-env (:redactions fsr)))))
              (is (= "bubblewrap-provider-network" (:isolation (last (:phases c))))))))
        (doseq [[label text] [["manifest" manifest-text] ["bundle" (json/generate-string bundle)]]
                leaked [secret api-key protected-line protected-msg ref-line "abcdefghijklmnopqrstu"
                        "(withdraw! client \"acct\" 99999)" "(not (= 4711 0))"]]
          (is (not (str/includes? text leaked)) (str label " leaks a fixture secret/protected value"))))
      (finally (fs/delete-tree dir) (fs/delete-tree root)))))

(defn short-literal-fixture
  "Challenge whose protected files hold short literals and a > 8 MiB data
  file with a token near its end. Literals are assembled at run time."
  []
  (let [root (fs/create-temp-dir {:prefix "scrub-"})
        short-a (str "z" "q")
        short-b (str "k" "9")
        big-token (str "qx7" "wplm")
        filler (apply str (repeat (* 9 1024 1024) "."))]
    (doseq [[rel text] {"challenges/demo/README.md" "Accounts have scores. Unknown accounts are rejected.\n"
                        "challenges/demo/src/demo/protocol.clj" "(defprotocol Scores (score! [c account k]))\n"
                        "challenges/demo/test-private/demo/private_test.clj"
                        (str "(deftest scores-short-literals\n  (is (= 7 (score! c \"" short-a "\" :" short-b ")))\n"
                             "  (is (= 6 (reduce + [3 1 2]))))\n")
                        "challenges/demo/test-private/demo/big_data.edn" (str "[" filler " " big-token "]\n")
                        "challenges/demo/test/demo/runner.clj" "(def hidden-runner-flag :qv4)\n"
                        "implementations/demo/src/demo/module.clj"
                        (str "(throw (ex-info \"unknown account\" {}))\n"
                             "(throw (IllegalArgumentException. (str \"bad input \" x)))\n"
                             "(println \"processing\" user)\n")}]
      (fs/create-dirs (fs/parent (fs/path root rel)))
      (spit (str (fs/path root rel)) text))
    {:root (str root) :short-a short-a :short-b short-b :big-token big-token}))

(deftest fail-closed-protected-matching-test
  (let [{:keys [root short-a short-b big-token]} (short-literal-fixture)
        ctx (scrub-context root "demo" {})
        leaked? (fn [text] (some #(re-find (re-pattern (str "(?i)(?<![\\p{L}\\p{N}])" % "(?![\\p{L}\\p{N}])")) text)
                                 [short-a short-b big-token "qv4" "3 1 2" "58213" "scores-short-literals"]))]
    (try
      (is (> (fs/size (fs/path root "challenges/demo/test-private/demo/big_data.edn")) (* 8 1024 1024)))
      (is (nil? (:unavailable (:corpus ctx))))
      (is (= ["challenges/demo/README.md" "challenges/demo/src/demo/protocol.clj"]
             (map #(str (fs/relativize root %)) (filter #(str/includes? (str %) "challenges/") (:public (:corpus ctx))))))
      (is (= 3 (count (:protected (:corpus ctx)))) "test/ is outside the snapshot, so protected")
      (testing "review text: short literals, an oversized file's token and a short run of public words"
        (let [review (str "Finding: score! returned 0 for " short-a " with :" short-b ".\n"
                          "Inputs 3 1 2 were summed; token " big-token " appeared; value " "qv4" ".\n"
                          "Unknown accounts are rejected.\n")
              {:keys [text redactions]} (scrub-text review ctx)]
          (is (= (str "Finding: score! returned 0 for [REDACTED:protected-plaintext] with :[REDACTED:protected-plaintext].\n"
                      "Inputs [REDACTED:protected-plaintext] were summed; token [REDACTED:protected-plaintext] appeared; "
                      "value [REDACTED:protected-plaintext].\n"
                      "Unknown accounts are rejected.\n")
                 text))
          (is (= 5 (:protected-plaintext redactions)))
          (is (not (leaked? text)))))
      (testing "private-test output: exception messages, ex-data and echoed inputs"
        (let [stdout (str "\nTesting demo.private-test\n\n"
                          "ERROR in (scores-short-literals) (private_test.clj:2)\n"
                          "expected: (= 7 (score! c \"" short-a "\" :" short-b "))\n"
                          "  actual: clojure.lang.ExceptionInfo: unknown account " short-a
                          " {:account \"" short-a "\" :input [3 1 2]}\n"
                          "Caused by: java.lang.IllegalArgumentException: bad input " big-token "\n"
                          "\tat demo.module$score_BANG_.invoke(module.clj:17)\n"
                          "processing user-58213 after 3 1 2\n"
                          "Ran 1 tests containing 2 assertions.\n0 failures, 1 errors.\n")
              [out err] (scrub-texts [stdout (str "bad input " big-token "\n")] (assoc ctx :private-test? true))]
          (is (= (str "\nTesting demo.private-test\n\n"
                      "ERROR in ([REDACTED:protected-plaintext]) (private_test.clj:2)\n"
                      "expected: [REDACTED:private-test-assertion]\n"
                      "  actual: clojure.lang.ExceptionInfo: unknown account [REDACTED:protected-plaintext] "
                      "[REDACTED:private-test-data]\n"
                      "Caused by: java.lang.IllegalArgumentException: bad input [REDACTED:protected-plaintext]\n"
                      "\tat demo.module$score_BANG_.invoke(module.clj:17)\n"
                      "processing user-[REDACTED:private-test-data] after [REDACTED:protected-plaintext]\n"
                      "Ran 1 tests containing 2 assertions.\n0 failures, 1 errors.\n")
                 (:text out)))
          (is (= "bad input [REDACTED:protected-plaintext]\n" (:text err)))
          (is (not-any? leaked? [(:text out) (:text err)]))))
      (finally (fs/delete-tree root)))))

(deftest unverifiable-protected-files-fail-closed-test
  (let [text "Testing demo.private-test\n\nRan 1 tests containing 1 assertions.\n"
        closed "[REDACTED:protected-index-unavailable]\n\n[REDACTED:protected-index-unavailable]\n"
        private (fn [root rel] (fs/path root "challenges/demo/test-private/demo" rel))]
    (doseq [[label break! reason]
            [["ciphertext only" #(fs/move (private % "private_test.clj") (private % "private_test.clj.enc"))
              "encrypted protected file"]
             ["symlink" #(fs/create-sym-link (private % "link.clj") (fs/path % "challenges/demo/README.md"))
              "symlink or special file in the challenge directory"]
             ["unreadable" #(fs/set-posix-file-permissions (private % "private_test.clj") "---------") nil]
             ["vanished after listing" nil nil]]]
      (testing label
        (let [{:keys [root]} (short-literal-fixture)]
          (try
            (when break! (break! root))
            (let [ctx (scrub-context root "demo" {})]
              (is (= reason (:unavailable (:corpus ctx))))
              (when-not break! (fs/delete (private root "big_data.edn")))
              (doseq [private-test? [false true]]
                (let [{:keys [text redactions]} (scrub-text text (assoc ctx :private-test? private-test?))]
                  (is (= closed text))
                  (is (= 2 (:protected-index-unavailable redactions))))))
            (finally (fs/delete-tree root))))))))

(deftest scrub-corpus-mirrors-snapshot-allowlist-test
  (let [py (slurp "scripts/isolate_solver.py")
        strings (fn [re] (re-seq #"\"([^\"]+)\"" (second (re-find re py))))
        shared (map second (strings #"(?s)SHARED_ALLOWLIST = \((.*?)\)\n"))
        challenge (map second (strings #"CHALLENGE_ALLOWLIST = \((.*?)\)\n"))
        dirs (set (map second (strings #"(?s)PROTECTED_DIRS = frozenset\(\{(.*?)\}\)")))]
    (is (= shared snapshot-shared-allowlist))
    (is (= challenge snapshot-challenge-allowlist))
    (is (= dirs snapshot-protected-dirs))
    (is (str/includes? py "auth\\.json)$\")") "SECRET_FILE_RE still ends where the mirror does")))

(deftest sentinel-0-of-1-survives-bundle-as-unavailable-test
  (let [fx (diagnostics-fixture)
        sentinel (assoc private-sentinel-0-of-1
                        :out (str "\nERROR in (demo.private-test) (Compiler.java:7)\n"
                                  "Uncaught exception, not in assertion.\n"
                                  "expected: nil\n  actual: java.io.FileNotFoundException: Could not locate demo/module__init.class\n"
                                  (:out private-sentinel-0-of-1))
                        :err "Execution error\n")
        r (diagnosed-result fx sentinel)
        genuine (diagnosed-result fx private-real-fail)
        dir (fs/create-temp-dir {:prefix "bundle-"})
        report (str (fs/path dir "run.md"))
        m (build-run-manifest run-meta-fixture [r genuine])]
    (try
      (let [{:keys [bundle]} (emit-run-artifacts! report (:root fx) (dissoc run-meta-fixture :evaluator-log)
                                                  [r genuine])
            bundle (read-bundle-json bundle)]
        (doseq [doc [m (json/parse-string (slurp (manifest-path report)) true) (:manifest bundle)]]
          (let [[c g] (:challenges doc)
                pt (:private-test c)]
            (is (= ["private-unavailable" "unavailable" false nil]
                   [(:outcome c) (:private-status c) (:scored c) (:score c)]))
            (is (= ["zero-of-one" false] [(:sentinel pt) (:counted-as-failure pt)])
                "0/1 sentinel is recorded, never counted as a private failure")
            (is (str/includes? (:stdout pt) "Could not locate demo/module__init.class")
                "the load error that caused the sentinel is preserved")
            (is (str/includes? (:stdout pt) "Ran 1 tests containing 1 assertions.\n0 failures, 1 errors."))
            (is (= "Execution error\n" (:stderr pt)))
            (is (= ["private-fail" true 0] [(:outcome g) (:counted-as-failure (:private-test g)) (:score g)])
                "a genuine failure next to it is still a failure"))))
      (is (re-find #"Private verdicts: PASS 0 \| FAIL 1 \| UNAVAILABLE \(not evaluated\) 1"
                   (private-verdict-line [r genuine])))
      (finally (fs/delete-tree dir) (fs/delete-tree (:root fx))))))

;;; Evaluator-only private log: fail closed, never in a solver-visible place

(deftest evaluator-log-fails-closed-test
  (let [{:keys [root] :as fx} (diagnostics-fixture)
        r (diagnosed-result fx private-real-fail)
        meta (dissoc run-meta-fixture :evaluator-log)
        dir (fs/create-temp-dir {:prefix "evlog-"})
        none-emitted (fn [report]
                       (is (not-any? #(fs/exists? (% report)) [manifest-path bundle-path])
                           "no manifest or bundle after a failed evaluator log"))]
    (try
      (testing "an existing log is never overwritten"
        (let [report (str (fs/path dir "a.md"))]
          (spit (evaluator-log-path report) "prior")
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Evaluator log not written"
                (emit-run-artifacts! report root meta [r])))
          (is (= "prior" (slurp (evaluator-log-path report))))
          (none-emitted report)))
      (testing "a missing reports directory"
        (let [report (str (fs/path dir "missing" "b.md"))]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Evaluator log not written"
                (emit-run-artifacts! report root meta [r])))
          (none-emitted report)))
      (testing "an incomplete write is detected on read-back and removed"
        (let [report (str (fs/path dir "c.md"))]
          (with-redefs [fs/read-all-bytes (fn [_] (byte-array 1))]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"read-back length or SHA-256 differs"
                  (emit-run-artifacts! report root meta [r]))))
          (is (not (fs/exists? (evaluator-log-path report))))
          (none-emitted report)))
      (testing "a log changed after it was referenced is not claimed"
        (let [report (str (fs/path dir "d.md"))
              ref (write-evaluator-log! report root "complete\n")]
          (spit (evaluator-log-path report) "trunc")
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"evaluator log is missing or incomplete"
                (write-run-manifest! report (build-run-manifest (assoc meta :evaluator-log ref) [r]))))
          (none-emitted report)))
      (testing "never inside a solver-writable implementation directory"
        (let [report (str (fs/path root "implementations" "demo" "e.md"))]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"solver-writable"
                (emit-run-artifacts! report root meta [r])))
          (is (not (fs/exists? (evaluator-log-path report))))
          (none-emitted report)))
      (finally (fs/delete-tree dir) (fs/delete-tree root)))))

(defn- git-ignored-or-untracked?
  "True when git can never commit path: it lies outside every worktree, or
  the enclosing worktree ignores it."
  [path]
  (let [dir (first (filter fs/directory? (iterate fs/parent (fs/parent (fs/absolutize path)))))
        top (git-out dir "rev-parse" "--show-toplevel")]
    (or (nil? top)
        (zero? (:exit (p/shell {:dir top :out :string :err :string :continue true}
                               "git" "check-ignore" "-q" "--no-index" (str (fs/relativize top (fs/absolutize path))))))
        false)))

(deftest evaluator-log-path-is-gitignored-test
  (let [repo (fs/canonicalize (fs/cwd))
        ;; generate-report writes to <repo>/../reports; the log sits beside it.
        log (fs/path (fs/normalize (fs/path repo ".." "reports")) (fs/file-name (evaluator-log-path "x.md")))]
    (is (= "x.private.log" (str (fs/file-name log))))
    (is (not (fs/starts-with? log repo)) "outside the repository, so outside every solver snapshot")
    (is (git-ignored-or-untracked? log))
    (doseq [rel ["reports/2026-10-02-run.private.log" "implementations/demo/run.private.log"
                 "challenges/demo/run.private.log"]]
      (is (git-ignored-or-untracked? (fs/path repo rel)) rel))
    (is (not (git-ignored-or-untracked? (fs/path repo "scripts/run_challenges.bb")))
        "the check itself distinguishes tracked paths")))

(deftest symlinked-review-is-not-followed-test
  (let [{:keys [root ref-line] :as fx} (diagnostics-fixture)
        review (fs/path root "implementations/demo/FULL_SPEC_REVIEW.md")]
    (try
      (fs/delete review)
      (fs/create-sym-link review (fs/path root "challenges/demo/test-resources/demo/module.clj"))
      (let [d (full-spec-review-diagnostic root "demo" [review-phase] (scrub-context root "demo" {}))]
        (is (= [false "symlink" nil] [(:report-present d) (:report-skipped d) (:report-text d)]))
        (is (not (str/includes? (pr-str d) ref-line))))
      (finally (fs/delete-tree root)))))

(deftest no-private-run-has-no-private-diagnostic-test
  (is (nil? (private-test-diagnostic nil {:private-status :not-run} {})))
  (is (nil? (full-spec-review-diagnostic (str (fs/create-temp-dir)) "demo" [] {}))))

;;; Scored-run isolation preflight

(deftest scored-run-requires-isolation-test
  (let [calls (atom [])]
    (with-redefs [invoke-command! (fn [cmd _] (swap! calls conj cmd)
                                    {:exit 0 :out (json/generate-string {:filesystem "bubblewrap" :probe "passed"})})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Scored runs require solver isolation"
            (require-scored-run-isolation! "/project" {} "claude" ["demo"])))
      (is (empty? @calls) "rejected before any process starts")
      (let [rec (require-scored-run-isolation! "/project" {:isolate-network true} "claude" ["demo" "other"])]
        (is (= {:filesystem "bubblewrap" :probe "passed" :mode "bubblewrap-provider-network"
                :launcher "scripts/isolate_solver.py" :preflight "passed"} rec))
        (is (= ["python3" "/project/scripts/isolate_solver.py" "--repo" "/project" "--agent" "claude"
                "--network" "strict" "--preflight" "--audit-challenge" "demo" "--audit-challenge" "other"]
               (last @calls))))
      (is (= "bubblewrap-public-only"
             (:mode (require-scored-run-isolation! "/project" {:isolate true} "codex" ["demo"])))))
    (with-redefs [invoke-command! (fn [_ _] {:exit 1 :out "" :err "isolation preflight failed: bubblewrap is required"})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Isolation preflight failed: .*bubblewrap is required"
            (require-scored-run-isolation! "/project" {:isolate true} "claude" ["demo"]))))))

(deftest scored-run-isolation-real-preflight-test
  (if-not (fs/which "bwrap")
    (println "SKIP scored-run-isolation-real-preflight-test: bubblewrap not installed")
    (let [rec (require-scored-run-isolation! (str (fs/cwd)) {:isolate-network true} "claude" ["fanout"])]
      (is (= ["bubblewrap" "passed" "strict" "allowlisted-public-copy" 0]
             [(:filesystem rec) (:probe rec) (:network rec) (get-in rec [:snapshot :kind])
              (get-in rec [:snapshot :audits :fanout :violations])]))
      (is (re-matches #"[0-9a-f]{64}" (:launcher-sha256 rec))))))

(deftest alignment-rubric-test
  (testing "the scorer gets only the alignment section, with the original anchors"
    (let [section (alignment-rubric (slurp "SCORING_RUBRIC.md"))]
      (is (re-find #"^## 4\. Structural alignment \(informational only\)" section))
      (is (re-find #"\| 4 \| Minor structural difference" section))
      (is (re-find #"\| 0 \| Reference implementation is not available" section))
      (is (not (re-find #"## 1\. Headline|## 3\. Quality|private-pass" section)))))
  (testing "falls back to the whole text without the section"
    (is (= "# Rubric\nbody" (alignment-rubric "# Rubric\nbody"))))
  (testing "build-alignment-prompt embeds the section, not the headline rubric"
    (let [root (fs/create-temp-dir)]
      (fs/copy "SCORING_RUBRIC.md" (fs/path root "SCORING_RUBRIC.md"))
      (fs/create-dirs (fs/path root "implementations" "x" "src"))
      (spit (str (fs/path root "implementations" "x" "src" "m.clj")) "(ns m)")
      (let [prompt (build-alignment-prompt (str root) "x")]
        (is (re-find #"ALIGNMENT_SCORE:<score>" prompt))
        (is (re-find #"\| 5 \| Approach is structurally equivalent" prompt))
        (is (not (re-find #"private-pass|Judge vector|judge vector" prompt))))
      (fs/delete-tree root))))

(deftest tree-sha256-test
  (let [dir (fs/create-temp-dir)]
    (spit (str (fs/path dir "a.clj")) "(ns a)")
    (let [h1 (tree-sha256 dir)]
      (is (= 64 (count h1)))
      (spit (str (fs/path dir "a.clj")) "(ns a) ;; changed")
      (is (not= h1 (tree-sha256 dir))))
    (is (nil? (tree-sha256 (fs/path dir "missing"))))
    (fs/delete-tree dir)))

(deftest grader-timeout-kills-process-tree-test
  (let [root (fs/create-temp-dir)
        marker (str "sleep " (+ 7000 (rand-int 999)))]
    (fs/create-dirs (fs/path root "challenges" "x"))
    (let [r (binding [*grader-timeout-s* 1
                      *private-test-cmd* ["bash" "-c" (str marker " & " marker " & wait")]]
              (run-private-tests! (str root) "x"))]
      (is (:timed-out? r))
      (is (= 124 (:exit r)))
      (is (= :unavailable (:private-status (classify-private-result true r))))
      (Thread/sleep 300)
      (is (not= 0 (:exit (p/shell {:out :string :err :string :continue true} "pgrep" "-f" marker)))
          "no grandchild survives the grader"))
    (fs/delete-tree root)))

(deftest orb-resource-floor-test
  (let [good {:orb-size "unknown" :cpu-count 8
              :memory-limit-bytes minimum-orb-limit-bytes
              :memory-available-bytes minimum-free-memory-bytes}]
    (is (= good (require-orb-resources! good)))
    (doseq [bad [(assoc good :cpu-count 7)
                 (assoc good :memory-limit-bytes (dec minimum-orb-limit-bytes))
                 (assoc good :memory-available-bytes (dec minimum-free-memory-bytes))
                 (assoc good :memory-limit-bytes nil)
                 (assoc good :memory-available-bytes nil)]]
      (is (= :insufficient-orb-resources
             (:reason (ex-data (try (require-orb-resources! bad)
                                    (catch clojure.lang.ExceptionInfo e e)))))))
    (let [actual (orb-resources)]
      (is (pos? (:cpu-count actual)))
      (is (pos? (:memory-limit-bytes actual)))
      (is (pos? (:memory-available-bytes actual)))
      (is (<= (:memory-available-bytes actual) (:memory-limit-bytes actual))))))

(deftest orb-resource-ancestor-limits-test
  (with-redefs [cgroup-ancestors (constantly ["/mock/child" "/mock/parent"])
                read-long-file (fn [path]
                                 (get {"/mock/child/memory.max" (* 16 1024 1024 1024)
                                       "/mock/child/memory.current" (* 2 1024 1024 1024)
                                       "/mock/parent/memory.max" minimum-orb-limit-bytes
                                       "/mock/parent/memory.current" (* 7 1024 1024 1024)}
                                      (str path)))
                proc-text (constantly "MemAvailable:   15000000 kB\n")
                slurp (fn [path]
                        (get {"/mock/child/cpu.max" "max 100000"
                              "/mock/parent/cpu.max" "700000 100000"}
                             (str path)))]
    (let [r (orb-resources)]
      (is (= minimum-orb-limit-bytes (:memory-limit-bytes r)))
      (is (= (* 7 1024 1024 1024) (:memory-available-bytes r)))
      (is (= (min 7 (.availableProcessors (Runtime/getRuntime))) (:cpu-count r)))
      (is (thrown? clojure.lang.ExceptionInfo (require-orb-resources! r))))))

(deftest orb-resource-unconfined-host-test
  (let [meminfo "MemTotal:       32000000 kB\nMemAvailable:   12000000 kB\n"]
    (testing "every ancestor reads max: bounded by host memory"
      (with-redefs [cgroup-ancestors (constantly ["/mock/child" "/mock/parent" "/sys/fs/cgroup"])
                    proc-text (constantly meminfo)
                    slurp (fn [path] (when (str/ends-with? (str path) "memory.max") "max\n"))]
        (let [r (orb-resources)]
          (is (= (* 32000000 1024) (:memory-limit-bytes r)))
          (is (= (* 12000000 1024) (:memory-available-bytes r))))))
    (testing "an unreadable ancestor limit stays unknown"
      (with-redefs [cgroup-ancestors (constantly ["/mock/child" "/mock/parent"])
                    proc-text (constantly meminfo)
                    slurp (fn [path] (when (= "/mock/child/memory.max" (str path)) "max\n"))]
        (let [r (orb-resources)]
          (is (nil? (:memory-limit-bytes r)))
          (is (nil? (:memory-available-bytes r))))))))

(deftest grader-jvm-options-and-oom-test
  (let [root (fs/create-temp-dir)]
    (fs/create-dirs (fs/path root "challenges" "x"))
    (let [r (binding [*private-test-cmd* ["bash" "-c" "printf '%s' \"$JDK_JAVA_OPTIONS\""]]
              (run-private-tests! (str root) "x"))]
      (is (= 0 (:exit r)))
      (is (str/ends-with? (:out r) grader-jvm-options)))
    (let [r (binding [*private-test-cmd* ["java" "-XshowSettings:vm" "-version"]]
              (run-private-tests! (str root) "x"))]
      (is (= 0 (:exit r)))
      (is (re-find #"Max. Heap Size.*4.00G" (:err r))))
    (doseq [r [{:exit 137 :out "Ran 2 tests containing 2 assertions.\n1 failures, 0 errors." :err ""}
               {:exit 1 :out "Ran 2 tests containing 2 assertions.\n0 failures, 1 errors." :err "java.lang.OutOfMemoryError: Java heap space"}]]
      (is (= :grader-oom (:private-sentinel (classify-private-result true r)))))
    (fs/delete-tree root)))

(let [{:keys [fail error]} (run-tests)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
