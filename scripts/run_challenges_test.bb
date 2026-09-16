#!/usr/bin/env bb

(require '[clojure.test :refer [deftest testing is run-tests]])
(require '[cheshire.core :as json])

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
        (is (= {:input-tokens 100
                :output-tokens 50
                :cache-creation-tokens 0
                :cache-read-tokens 40}
               (parse-token-usage output)))))

    (testing "when given Codex JSONL with multiple turn.completed events"
      (let [output (str "{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":100,\"cached_input_tokens\":40,\"output_tokens\":50}}\n"
                        "{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":200,\"cached_input_tokens\":80,\"output_tokens\":75}}\n")]
        (is (= {:input-tokens 300
                :output-tokens 125
                :cache-creation-tokens 0
                :cache-read-tokens 120}
               (parse-token-usage output)))))))

(deftest normalize-agent-output-test
  ;; Codex's native event stream is normalized before persistence so the
  ;; transcript analyzer can consume one stable message shape. The source
  ;; event and runner-owned timing remain available for auditability.
  (let [output (str
                "{\"type\":\"thread.started\",\"thread_id\":\"t-1\"}\n"
                "{\"type\":\"item.started\",\"item\":{\"id\":\"c-1\",\"type\":\"command_execution\",\"command\":\"cat PLAN.md\",\"status\":\"in_progress\"}}\n"
                "{\"type\":\"item.completed\",\"item\":{\"id\":\"m-1\",\"type\":\"agent_message\",\"text\":\"PHASE_VALIDATION:pass\"}}\n"
                "{\"type\":\"item.completed\",\"item\":{\"id\":\"c-1\",\"type\":\"command_execution\",\"command\":\"cat PLAN.md\",\"aggregated_output\":\"plan\",\"exit_code\":0,\"status\":\"completed\"}}\n"
                "{\"type\":\"item.completed\",\"item\":{\"id\":\"f-1\",\"type\":\"file_change\",\"changes\":[{\"path\":\"PLAN.md\",\"kind\":\"add\"}],\"status\":\"completed\"}}\n"
                "{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":100,\"cached_input_tokens\":40,\"output_tokens\":50}}\n")
        invocation {:started-at "2026-09-16T10:00:00Z"
                    :finished-at "2026-09-16T10:00:12Z"
                    :duration-s 12
                    :exit 0
                    :timed-out? false}
        transcript (normalize-codex-output output invocation)
        events (mapv #(json/parse-string % true)
                     (clojure.string/split-lines transcript))
        messages (filter #(contains? #{"assistant" "user"} (:type %)) events)
        tool-uses (mapcat #(filter (fn [block] (= "tool_use" (:type block)))
                                   (get-in % [:message :content] []))
                          messages)
        tool-results (mapcat #(filter (fn [block] (= "tool_result" (:type block)))
                                      (get-in % [:message :content] []))
                             messages)
        result (first (filter #(= "result" (:type %)) events))]
    (testing "Claude output is already canonical"
      (is (= "claude-output"
             (normalize-claude-output "claude-output" invocation))))
    (testing "Codex messages are represented in the canonical shape"
      (is (some #(= "PHASE_VALIDATION:pass"
                    (get-in % [:message :content 0 :text])) messages))
      (is (some #(= "cat PLAN.md" (get-in % [:input :command])) tool-uses))
      (is (some #(= "plan" (:content %)) tool-results))
      (is (some #(= "FileChange" (:name %)) tool-uses)))
    (testing "completed events do not duplicate their started command"
      (is (= 1 (count (filter #(= "cat PLAN.md" (get-in % [:input :command]))
                              tool-uses))))
    (testing "runner timing, exit, and usage are explicit"
      (is (= "2026-09-16T10:00:00Z" (:started_at (first events))))
      (is (= "2026-09-16T10:00:12Z" (:finished_at (last events))))
      (is (= 12000 (:duration_ms result)))
      (is (= 100 (get-in result [:usage :input_tokens])))
      (is (= 40 (get-in result [:usage :cached_input_tokens])))))))

(deftest normalized-summary-analysis-test
  ;; Exercise the Python consumer as well as the adapter contract.
  (let [invocation {:started-at "2026-09-16T10:00:00Z"
                    :finished-at "2026-09-16T10:00:12Z"
                    :duration-s 12 :exit 0 :timed-out? false}
        message (fn [text] {:type "item.completed"
                            :item {:id text :type "agent_message" :text text}})
        completed {:type "turn.completed" :usage {:input_tokens 100 :output_tokens 50}}
        failure {:type "turn.failed" :error {:message "unique-provider-failure-detail"}}
        normalize (fn [events overrides]
                    (mapv #(json/parse-string % true)
                          (str/split-lines
                           (normalize-codex-output
                            (str/join "\n" (map json/generate-string events))
                            (merge invocation overrides)))))
        result-of (fn [events] (first (filter #(= "result" (:type %)) events)))
        summary (fn [events]
                  (let [proc @(p/process
                               ["python3" "-c"
                                (str "import json, runpy, sys; "
                                     "runpy.run_path('scripts/analyze-latest-transcript.py')"
                                     "['cmd_summary'](json.load(sys.stdin), [])")]
                               {:in (json/generate-string events) :out :string :err :string})]
                    (is (= 0 (:exit proc)) (:err proc))
                    (:out proc)))
        success (normalize [(message "progress") completed
                            (message "PHASE_VALIDATION:pass") completed] {})]
    (testing "final text, turn count, and lifecycle status reach the analyzer"
      (let [output (summary success)]
        (is (str/includes? output "Result: PHASE_VALIDATION:pass"))
        (is (str/includes? output "Turns: 2"))
        (is (str/includes? output "Stop: turn.completed"))
        (is (str/includes? output "Cost: N/A"))))
    (testing "turn failure retains the original diagnostic and surfaces it in summary"
      (doseq [exit [0 1]]
        (let [events (normalize [(message (apply str (repeat 400 "x"))) failure]
                                {:exit exit})]
          (is (some #(= failure (:source_event %)) events))
          (is (true? (:is_error (result-of events))))
          (let [output (summary events)]
            (is (str/includes? output "Result: unique-provider-failure-detail"))
            (is (str/includes? output "Stop: error_during_execution"))))))
    (testing "timeout preserves partial text and does not invent a turn count"
      (let [output (summary (normalize [(message "partial response")]
                                       {:exit 1 :timed-out? true}))]
        (is (str/includes? output "Result: partial response"))
        (is (str/includes? output "Turns: N/A"))
        (is (str/includes? output "Stop: timeout"))))
    (testing "unavailable summary fields are explicit"
      (let [output (summary [{:type "result"}])]
        (doseq [field ["Cost" "Turns" "Stop" "Result"]]
          (is (str/includes? output (str field ": N/A"))))))
    (testing "reported zero cost and turn count remain valid values"
      (let [output (summary [{:type "result" :total_cost_usd 0 :num_turns 0
                             :stop_reason "end_turn" :result "done"}])]
        (is (str/includes? output "Cost: $0.00"))
        (is (str/includes? output "Turns: 0"))
        (is (str/includes? output "Stop: end_turn"))
        (is (str/includes? output "Result: done"))))))

(deftest normalize-malformed-codex-output-test
  (let [transcript (normalize-codex-output
                    "not-json\n"
                    {:started-at "2026-09-16T10:00:00Z"
                     :finished-at "2026-09-16T10:00:01Z"
                     :duration-s 1
                     :exit 1
                     :timed-out? false})
        events (mapv #(json/parse-string % true)
                     (clojure.string/split-lines transcript))]
    (is (= "not-json" (:text (second events))))
    (is (= "error_during_execution"
           (:subtype (first (filter #(= "result" (:type %)) events)))))))

(deftest agent-adapter-contract-test
  (testing "Claude and Codex expose the same adapter contract"
    (doseq [agent [:claude :codex]
            key [:phase-cmd :prompt-cmd :score-text :normalize-output :parse-token-usage
                 :parse-tool-uses :parse-skills-used :parse-skill-refs-used :pricing]]
      (is (fn? (get-in agent-adapters [agent key]))
          (str (name agent) " adapter must provide " (name key)))))
  (testing "pricing stays provider-specific behind the adapter"
    (is (= {:input 2.00 :output 10.00 :cache-write 2.50 :cache-read 0.20}
           ((:pricing (:claude agent-adapters)) "sonnet")))
    (is (= {:input 10.00 :output 50.00 :cache-write 12.50 :cache-read 1.00}
           ((:pricing (:codex agent-adapters)) "gpt-6-astra"))))
  (testing "Codex scoring text is extracted before score parsing"
    (is (= "ALIGNMENT_SCORE:4\nclear"
           (codex-score-text
            "{\"type\":\"item.completed\",\"item\":{\"type\":\"agent_message\",\"text\":\"ALIGNMENT_SCORE:4\\nclear\"}}\n")))))

(deftest run-phase-saves-canonical-codex-transcript-test
  ;; Exercise the actual run-phase! save boundary, not just the pure
  ;; normalizer. This is the enforcement surface for future Codex runs.
  (let [tmp-root (str (babashka.fs/create-temp-dir))
        project-dir (str (babashka.fs/path tmp-root "project"))
        implementation-dir (babashka.fs/path project-dir "implementations" "ch")
        codex-lines ["{\"type\":\"item.completed\",\"item\":{\"id\":\"m-1\",\"type\":\"agent_message\",\"text\":\"done\"}}"
                     "{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":1,\"output_tokens\":2}}"]
        shell-script (str "printf '%s\\n' "
                          (clojure.string/join " " (map #(str "'" % "'") codex-lines)))]
    (babashka.fs/create-dirs implementation-dir)
    (try
      (let [result (binding [*outer-timeout-s* 10]
                     (run-phase! {:phase-cmd (fn [& _] ["bash" "-c" shell-script])}
                                  "ch" 0 1 nil project-dir "codex" nil nil
                                  (java.time.LocalDateTime/now)
                                  (System/currentTimeMillis)))
            saved (mapv #(json/parse-string % true)
                        (clojure.string/split-lines
                         (slurp (:transcript-path result))))]
        (is (= 0 (:exit result)))
        (is (= "run_metadata" (:type (first saved))))
        (is (some #(= "done" (get-in % [:message :content 0 :text])) saved))
        (is (some #(= "result" (:type %)) saved)))
      (finally
        (babashka.fs/delete-tree tmp-root)))))

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
            (is (re-find #"\*\*Average alignment:\*\* 5\.0/5" content))))
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

(let [{:keys [fail error]} (run-tests)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
