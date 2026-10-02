#!/usr/bin/env bb

(require '[babashka.cli :as cli]
         '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[cheshire.core :as json]
         '[clojure.edn :as edn]
         '[clojure.string :as str]
         '[clojure.java.io :as io]
         '[babashka.tasks :as tasks])

;; Load encryption helpers — provides encrypt-challenge! / decrypt-challenge! / derive-key
(load-file (str (fs/parent (fs/absolutize *file*)) "/encrypt_challenges.bb"))

(def cli-spec
  {:grader-timeout {:desc "Seconds before a private-test (grader) run is killed with its process tree (default 1800)"
                    :coerce :long}
   :filter     {:desc "Glob pattern to match challenge names (e.g. \"basic-*\")"
                :alias :f}
   :batch      {:desc "Batch number from CHALLENGE_ORDER.md (5 requires a cluster)"
                :alias :b
                :coerce :int}
   :difficulty {:desc "Difficulty filter: standard or hard"
                :alias :d}
   :agent      {:desc "Agent: claude, codex, opencode, or pi (default: claude)"
                :alias :a
                :default "claude"}
   :fast-model  {:desc "Fast model: subsystem build phases (required)"}
   :fast-effort {:desc "Reasoning effort for the fast model (required)"}
   :slow-model  {:desc "Slow model: phase 0, decompose, planning, validation, full-spec-review (required)"}
   :slow-effort {:desc "Reasoning effort for the slow model (required)"}
   :isolate    {:desc "Run solver phases in a Linux bubblewrap public-only filesystem"
                :coerce :boolean}
   :isolate-network {:desc "Also restrict solver egress to provider/docs hosts (implies --isolate)"
                     :coerce :boolean}
   :verbose    {:desc "Stream agent output to console in real time"
                :alias :v
                :coerce :boolean}
   :pretty     {:desc "Pretty-print agent output (implies --verbose)"
                :alias :p
                :coerce :boolean}
   :help       {:desc "Show usage"
                :alias :h
                :coerce :boolean}})

(defn parse-challenge-order
  "Parse CHALLENGE_ORDER.md into a vector of challenge maps.
  Returns [{:name \"simple-streaming\" :batch 1 :difficulty :standard} ...]"
  [path]
  (let [lines (str/split-lines (slurp path))]
    (loop [lines lines
           batch nil
           result []]
      (if-let [line (first lines)]
        (let [batch-match (re-matches #"## Batch (\d+):.*" line)
              challenge-match (re-matches #"- (.+)" (str/trim line))]
          (cond
            batch-match
            (recur (rest lines) (parse-long (second batch-match)) result)

            (and challenge-match batch)
            (let [name (second challenge-match)
                  difficulty (if (str/ends-with? name "-hard") :hard :standard)]
              (recur (rest lines) batch (conj result {:name name
                                                      :batch batch
                                                      :difficulty difficulty})))

            :else
            (recur (rest lines) batch result)))
        result))))

(defn glob->regex
  "Convert a simple glob pattern to a regex pattern.
  Supports * (any chars) and ? (single char)."
  [glob]
  (-> glob
      (str/replace "\\" "\\\\")
      (str/replace #"[.+()\[\]{}^$|]" "\\\\$0")
      (str/replace "*" ".*")
      (str/replace "?" ".")))

(defn filter-challenges
  "Apply CLI filters to a challenge list. Filters compose with AND logic."
  [challenges {:keys [filter batch difficulty]}]
  (cond->> challenges
    filter     (filterv #(re-matches (re-pattern (glob->regex filter)) (:name %)))
    batch      (filterv #(= batch (:batch %)))
    difficulty (filterv #(= (keyword difficulty) (:difficulty %)))))

(defn validate-challenges
  "Check that each challenge has a directory with README.md. Returns
  {:valid [...] :missing [...]}."
  [challenges project-root]
  (let [grouped (group-by
                 (fn [{:keys [name]}]
                   (if (fs/exists? (fs/path project-root "challenges" name "README.md"))
                     :valid
                     :missing))
                 challenges)]
    {:valid (vec (:valid grouped))
     :missing (vec (:missing grouped))}))

(defn discover-all-challenges
  "Discover all runnable challenges from challenges/*/README.md, excluding
  non-runnable template/shared directories. Returns challenge maps with the
  same shape as parse-challenge-order, filling missing metadata when needed."
  [project-root ordered-challenges]
  (let [ordered-by-name (into {} (map (juxt :name identity) ordered-challenges))]
    (->> (fs/list-dir (fs/path project-root "challenges"))
         (filter fs/directory?)
         (map #(fs/file-name %))
         (map str)
         (filter #(fs/exists? (fs/path project-root "challenges" % "README.md")))
         sort
         (mapv (fn [name]
                 (or (get ordered-by-name name)
                     {:name name
                      :batch nil
                      :difficulty (if (str/ends-with? name "-hard") :hard :standard)}))))))

(defn print-usage []
  (println "Usage: bb run-challenges [options]")
  (println)
  (println "Options:")
  (println "  -f, --filter GLOB       Glob pattern to match challenge names (e.g. \"basic-*\")")
  (println "  -b, --batch N           Batch number from CHALLENGE_ORDER.md")
  (println "  -d, --difficulty TYPE   Difficulty filter: standard or hard")
  (println "  -a, --agent NAME        Agent: claude, codex, opencode, or pi (default: claude)")
  (println "      --fast-model M      Fast model: subsystem build phases (required)")
  (println "      --fast-effort E     Reasoning effort for the fast model (required)")
  (println "      --slow-model M      Slow model: phase 0, decompose, planning, validation, full-spec-review (required)")
  (println "      --slow-effort E     Reasoning effort for the slow model (required)")
  (println "      --isolate           Linux public-only solver filesystem (bubblewrap required)")
  (println "      --isolate-network   Provider/docs-only network; implies --isolate")
  (println "                          Scored runs require one; preflight rejects a run with neither")
  (println "  -v, --verbose           Stream agent output to console in real time")
  (println "  -h, --help              Show this help")
  (println)
  (println "Phase 0, decompose, planning (phase 1), plan-validation (phase 2), and")
  (println "full-spec-review run on the slow model; subsystem build phases use the fast model.")
  (println)
  (println "Note: Batch 5 (cluster operations) requires a running local Rama cluster.")
  (println "      Set RAMA_CONDUCTOR_HOST/RAMA_CONDUCTOR_UI_PORT to override defaults.")
  (println "      Batch 5 challenges are skipped gracefully when the cluster is unavailable."))

;;; Cluster precondition

(def cluster-batch 5)

(defn cluster-running?
  "Check if the Rama conductor is reachable. Returns true when the
  conductor HTTP port responds, false otherwise."
  []
  (let [host (or (System/getenv "RAMA_CONDUCTOR_HOST") "localhost")
        port (or (some-> (System/getenv "RAMA_CONDUCTOR_UI_PORT") parse-long) 8888)
        url  (str "http://" host ":" port "/")]
    (try
      (let [client  (java.net.http.HttpClient/newHttpClient)
            request (-> (java.net.http.HttpRequest/newBuilder)
                        (.uri (java.net.URI/create url))
                        (.timeout (java.time.Duration/ofSeconds 3))
                        (.GET)
                        (.build))
            resp    (.send client request (java.net.http.HttpResponse$BodyHandlers/discarding))]
        (< (.statusCode resp) 500))
      (catch Exception _ false))))

(defn partition-by-cluster
  "Split challenges into {:local [...] :cluster [...]} by batch number."
  [challenges]
  (group-by (fn [{:keys [batch]}]
              (if (= batch cluster-batch) :cluster :local))
            challenges))

;;; Agent abstraction

(def ^:private allowed-tools
  "Tools the agent is allowed to use during challenge runs."
  "Read,Write,Edit,Glob,Grep,Bash,Skill")

(defn phase-id-str
  "Render a phase id for command lines, sentinels, and filenames.
  Numbered phases render as their number; keyword stages (:decompose,
  :full-spec-review) render as their name."
  [phase-id]
  (if (keyword? phase-id) (name phase-id) (str phase-id)))

(defn- phase-invocation-args
  "Arguments passed to /challenge-phase: `<name> <phase-id>` plus the
  subsystem slug when one is set (multi-subsystem runs only)."
  [challenge-name phase-id subsystem]
  (str challenge-name " " (phase-id-str phase-id)
       (when subsystem (str " " subsystem))))

(defn claude-phase-cmd
  "Build the CLI command to invoke Claude for a single phase of a challenge."
  ([challenge-name phase-id project-root model reasoning]
   (claude-phase-cmd challenge-name phase-id project-root model reasoning nil))
  ([challenge-name phase-id _project-root model reasoning subsystem]
   (cond-> ["claude" "--print" "--output-format" "stream-json" "--verbose"
            "--allowedTools" allowed-tools
            "-p" (str "/challenge-phase "
                      (phase-invocation-args challenge-name phase-id subsystem))]
     model     (into ["--model" model])
     reasoning (into ["--effort" reasoning]))))

(defn codex-phase-cmd
  "Build the CLI command to invoke Codex for a single phase of a challenge.
  Note: requires a $challenge-phase command in the codex skills setup."
  ([challenge-name phase-id project-root model reasoning]
   (codex-phase-cmd challenge-name phase-id project-root model reasoning nil))
  ([challenge-name phase-id project-root model reasoning subsystem]
   (cond-> ["codex" "exec" "--json" "--dangerously-bypass-approvals-and-sandbox"
            "-C" project-root]
     model     (into ["--model" model])
     reasoning (into ["-c" (str "model_reasoning_effort=" reasoning)])
     true      (conj (str "$challenge-phase "
                          (phase-invocation-args challenge-name phase-id subsystem))))))

(defn opencode-prompt-cmd [model reasoning prompt]
  (cond-> ["opencode" "run" "--format" "json" "--thinking" "--auto"]
    model (into ["--model" model])
    reasoning (into ["--variant" reasoning])
    true (into ["--" prompt])))

(defn pi-prompt-cmd [model reasoning prompt]
  (cond-> ["pi" "--print" "--mode" "json" "--no-session"]
    model (into ["--model" model])
    reasoning (into ["--thinking" reasoning])
    true (conj prompt)))

(defn codex-prompt-cmd [model _reasoning prompt]
  (cond-> ["codex" "exec" "--json" "--dangerously-bypass-approvals-and-sandbox"]
    model (into ["--model" model])
    true (conj prompt)))

(defn native-phase-cmd [prompt-cmd]
  (fn [challenge-name phase-id _project-root model reasoning subsystem]
    (prompt-cmd model reasoning
                (str "Read .agents/skills/challenge-phase/SKILL.md and follow its instructions for "
                     (phase-invocation-args challenge-name phase-id subsystem)
                     ". Execute only this phase."))))

(def agents
  {:claude {:phase-cmd claude-phase-cmd}
   :codex  {:phase-cmd codex-phase-cmd :prompt-cmd codex-prompt-cmd}
   :opencode {:phase-cmd (native-phase-cmd opencode-prompt-cmd)
              :prompt-cmd opencode-prompt-cmd}
   :pi {:phase-cmd (native-phase-cmd pi-prompt-cmd)
        :prompt-cmd pi-prompt-cmd}})

;; The same Python adapters serve saved histories and runner telemetry. Keep
;; native output on disk; normalize once per invocation, not per metric.
(def transcript-adapter
  (str (fs/parent (fs/absolutize *file*)) "/transcript_events.py"))

(defn normalize-agent-output [output]
  (let [r @(p/process ["python3" transcript-adapter]
                      {:in output :out :string :err :string})]
    (when-not (zero? (:exit r))
      (throw (ex-info "Transcript normalization failed" {:stderr (:err r)})))
    (:out r)))

(defn result-event [output]
  (last (keep (fn [line]
                (try (let [e (json/parse-string line true)]
                       (when (= "result" (:type e)) e))
                     (catch Exception _ nil)))
              (str/split-lines output))))

;;; Pricing

(def claude-model-pricing
  "USD cost per million tokens for Claude models.
  Ordered list of [substring pricing-map] pairs matched against model names."
  [["haiku"  {:input 1.00  :output 5.00  :cache-write 1.25  :cache-read 0.10}]
   ["sonnet" {:input 2.00  :output 10.00 :cache-write 2.50  :cache-read 0.20}]
   ["opus"   {:input 5.00  :output 25.00 :cache-write 6.25  :cache-read 0.50}]
   ["fable"  {:input 10.00 :output 50.00 :cache-write 12.50 :cache-read 0.25}]])

(def codex-model-pricing
  "USD cost per million tokens for OpenAI Codex models, assuming <272k input tokens.
  Ordered list of [substring pricing-map] pairs matched against model names."
  [["gpt-5.6-luna"   {:input 0.20  :output 1.20  :cache-write 0.25  :cache-read 0.02}]
   ["gpt-5.6-terra"  {:input 2.00  :output 12.00 :cache-write 2.50  :cache-read 0.20}]
   ["gpt-5.6-sol"    {:input 4.00  :output 20.00 :cache-write 5.00  :cache-read 0.40}]
   ["gpt-6-astra"   {:input 10.00 :output 50.00 :cache-write 12.50 :cache-read 1.00}]])

(defn model->pricing
  "Return pricing map for a Claude or Codex model name, or nil if unknown."
  [model]
  (when model
    (some (fn [[k v]] (when (str/includes? (str/lower-case model) k) v))
          (concat claude-model-pricing codex-model-pricing))))

(defn compute-cost
  "Calculate USD cost from token usage and a pricing map.
  Returns nil when pricing is unavailable."
  [{:keys [input-tokens output-tokens cache-creation-tokens cache-read-tokens]} pricing]
  (when pricing
    (+ (* (or input-tokens 0) (/ (:input pricing) 1e6))
       (* (or output-tokens 0) (/ (:output pricing) 1e6))
       (* (or cache-creation-tokens 0) (/ (:cache-write pricing) 1e6))
       (* (or cache-read-tokens 0) (/ (:cache-read pricing) 1e6)))))

(defn format-cost
  "Format a cost value as a dollar string, or \"N/A\" when nil."
  [cost]
  (if cost (format "$%.4f" (double cost)) "N/A"))

;;; Encryption

(defn challenge-encryption-key
  "Return a derived AES key from the CHALLENGE_KEY env var.
  Exits with an error if CHALLENGE_KEY is not set."
  []
  (let [k (or (System/getenv "CHALLENGE_KEY")
              (do (println "Error: CHALLENGE_KEY env var is not set.")
                  (println "Challenge encryption is required. Set CHALLENGE_KEY to a passphrase.")
                  (System/exit 1)))]
    (derive-key k)))

;;; Implementation cleaning

(defn clean-implementation-dir!
  "Remove the entire implementation directory for a challenge."
  [project-root challenge-name]
  (let [impl-dir (fs/path project-root "implementations" challenge-name)]
    (when (fs/exists? impl-dir)
      (fs/delete-tree impl-dir))))

;;; Output parsing

(defn parse-iterations
  "Parse the number of test attempts from agent output.
  Prefers structured CHALLENGE_RESULT line, falls back to heuristics."
  [output]
  (if-let [m (re-find #"CHALLENGE_RESULT:.*iterations=(\d+)" output)]
    (parse-long (second m))
    (let [attempt-matches (re-seq #"(?i)attempt[s]?\s+(\d+)" output)]
      (if (seq attempt-matches)
        (apply max (map #(parse-long (second %)) attempt-matches))
        1))))

(defn parse-pass-fail
  "Determine pass/fail from agent exit code and output.
  Prefers structured CHALLENGE_RESULT line, falls back to heuristics."
  [exit-code output]
  (if-let [m (re-find #"CHALLENGE_RESULT:status=(pass|fail)" output)]
    (keyword (second m))
    (cond
      (not= 0 exit-code) :fail
      (re-find #"(?i)tests?\s+pass" output) :pass
      (re-find #"(?i)report success" output) :pass
      (re-find #"(?i)all \d+ tests? (passed|pass)" output) :pass
      (re-find #"0 failures, 0 errors" output) :pass
      (re-find #"(?i)attempts?\s*>=?\s*5" output) :fail
      (re-find #"(?i)stop and report the failure" output) :fail
      ;; If exit code is 0 and no clear failure signal, assume pass
      :else :pass)))

(defn parse-phase-verdict
  "Extract PHASE_VALIDATION verdict from agent output. Returns one of
  :pass, :fail, :minor-fail, :major-fail, or nil if no verdict line is present.
  Phase 2 emits pass/fail. Phases 4 and 6 emit pass/minor-fail/major-fail.
  Phase 7 (finish) emits pass/fail.

  Returns the LAST verdict match in the output. The agent's output (stream-json)
  includes the phase doc's verdict instructions in earlier tool_result events,
  which contain literal PHASE_VALIDATION:pass/fail text. Only the final emitted
  verdict matters, so we take the last occurrence."
  [output]
  (when-let [matches (seq (re-seq #"PHASE_VALIDATION:(minor-fail|major-fail|pass|fail)" output))]
    (keyword (second (last matches)))))

(def score-keys [:alignment :test-alignment])

(defn parse-skills-used
  "Extract distinct skill names from agent NDJSON output.
  Handles Claude (Skill tool_use in assistant messages) and
  Codex (command_execution reading SKILL.md files from skills directories)."
  [output]
  (let [skills (reduce
                (fn [acc line]
                  (try
                    (let [parsed (json/parse-string line true)]
                      (cond
                        ;; Claude: assistant event with Skill tool_use
                        (= "assistant" (:type parsed))
                        (reduce (fn [acc2 block]
                                  (cond
                                    (not= "tool_use" (:type block)) acc2
                                    (= "Skill" (:name block))
                                    (conj acc2 (get-in block [:input :skill]))
                                    (contains? #{"Read" "Bash"} (:name block))
                                    (into acc2 (map second
                                                 (re-seq #"(?:skills|plugins)/(?:[^/]+/skills/)?([^/]+)/SKILL\.md"
                                                         (json/generate-string (:input block)))))
                                    :else acc2))
                                acc
                                (get-in parsed [:message :content] []))

                        ;; Codex: command_execution reading a SKILL.md file
                        (and (= "item.started" (:type parsed))
                             (= "command_execution" (get-in parsed [:item :type])))
                        (let [cmd (get-in parsed [:item :command] "")]
                          (if-let [matches (re-seq #"(?:skills|plugins)/(?:[^/]+/skills/)?([^/]+)/SKILL\.md" cmd)]
                            (into acc (map second matches))
                            acc))

                        :else acc))
                    (catch Exception _ acc)))
                #{}
                (remove str/blank? (str/split-lines (or output ""))))]
    (vec (sort skills))))

(defn parse-skill-refs-used
  "Extract distinct skill reference filenames accessed by the agent.
  Detects Read/Glob/Grep tool calls whose paths contain references/*.md,
  and Codex command_execution events reading reference files."
  [output]
  (let [refs (reduce
              (fn [acc line]
                (try
                  (let [parsed (json/parse-string line true)]
                    (cond
                      ;; Claude: assistant event with tool_use blocks
                      (= "assistant" (:type parsed))
                      (reduce (fn [acc2 block]
                                (if (= "tool_use" (:type block))
                                  (let [input (json/generate-string (or (:input block) {}))
                                        matches (re-seq #"references/([a-z_-]+\.md)" input)]
                                    (into acc2 (map second matches)))
                                  acc2))
                              acc
                              (get-in parsed [:message :content] []))

                      ;; Codex: command_execution reading a reference file
                      (and (= "item.started" (:type parsed))
                           (= "command_execution" (get-in parsed [:item :type])))
                      (let [cmd (get-in parsed [:item :command] "")]
                        (if-let [matches (re-seq #"references/([a-z_-]+\.md)" cmd)]
                          (into acc (map second matches))
                          acc))

                      :else acc))
                  (catch Exception _ acc)))
              #{}
              (remove str/blank? (str/split-lines (or output ""))))]
    (vec (sort refs))))

(defn parse-tool-uses
  "Count tool invocations from agent NDJSON output.
  Handles Claude (assistant events with tool_use content blocks) and
  Codex (item.started with item.type=command_execution) event formats."
  [output]
  (reduce
   (fn [acc line]
     (try
       (let [parsed (json/parse-string line true)]
         (cond
           ;; Claude: assistant event with tool_use items in message.content
           (= "assistant" (:type parsed))
           (+ acc (count (filter #(= "tool_use" (:type %))
                                 (get-in parsed [:message :content] []))))
           ;; Codex: item.started with command_execution item
           (and (= "item.started" (:type parsed))
                (= "command_execution" (get-in parsed [:item :type])))
           (inc acc)
           :else acc))
       (catch Exception _ acc)))
   0
   (remove str/blank? (str/split-lines (or output "")))))

(defn extract-usage-from-result
  "Extract token usage from a parsed JSON event map.
  Handles Claude's result-type events and Codex's turn.completed events.
  Returns a usage map or nil if the event has no usage."
  [parsed]
  (when-let [usage (:usage parsed)]
    (condp = (:type parsed)
      ;; Claude: {"type":"result","usage":{"input_tokens":...,"cache_creation_input_tokens":...,...}}
      "result"
      {:input-tokens          (get usage :input_tokens 0)
       :output-tokens         (get usage :output_tokens 0)
       :cache-creation-tokens (get usage :cache_creation_input_tokens 0)
       :cache-read-tokens     (get usage :cache_read_input_tokens 0)}
      ;; Codex: {"type":"turn.completed","usage":{"input_tokens":...,"cached_input_tokens":...,...}}
      "turn.completed"
      {:input-tokens          (get usage :input_tokens 0)
       :output-tokens         (get usage :output_tokens 0)
       :cache-creation-tokens 0
       :cache-read-tokens     (get usage :cached_input_tokens 0)}
      nil)))

(defn parse-token-usage
  "Parse token usage from Claude's JSON or NDJSON output.
  Handles both NDJSON (one JSON object per line) and a single JSON object.
  Sums usage fields across all result-type messages. Returns a map with
  :input-tokens, :output-tokens, :cache-creation-tokens, :cache-read-tokens."
  [output]
  (let [zero-usage {:input-tokens 0
                    :output-tokens 0
                    :cache-creation-tokens 0
                    :cache-read-tokens 0}
        add-usage  (fn [acc u]
                     (-> acc
                         (update :input-tokens + (:input-tokens u))
                         (update :output-tokens + (:output-tokens u))
                         (update :cache-creation-tokens + (:cache-creation-tokens u))
                         (update :cache-read-tokens + (:cache-read-tokens u))))
        ;; First try NDJSON: process each line independently
        ndjson-result (let [lines (remove str/blank? (str/split-lines (or output "")))]
                        (reduce
                         (fn [acc line]
                           (try
                             (let [parsed (json/parse-string line true)]
                               (if-let [u (extract-usage-from-result parsed)]
                                 (add-usage acc u)
                                 acc))
                             (catch Exception _ acc)))
                         zero-usage
                         lines))]
    (if (not= zero-usage ndjson-result)
      ;; NDJSON parsing found tokens
      ndjson-result
      ;; Fall back: try parsing the entire output as a single multi-line JSON object
      (or (try
            (let [parsed (json/parse-string (str/trim (or output "")) true)]
              (when-let [u (extract-usage-from-result parsed)]
                (add-usage zero-usage u)))
            (catch Exception _ nil))
          zero-usage))))

(defn token-totals
  "Sum token usage across a sequence of result maps.
  Returns a map with :input-tokens, :output-tokens, :cache-creation-tokens, :cache-read-tokens."
  [results]
  {:input-tokens (reduce + 0 (map #(or (:input-tokens %) 0) results))
   :output-tokens (reduce + 0 (map #(or (:output-tokens %) 0) results))
   :cache-creation-tokens (reduce + 0 (map #(or (:cache-creation-tokens %) 0) results))
   :cache-read-tokens (reduce + 0 (map #(or (:cache-read-tokens %) 0) results))})

;;; Transcript saving

(defn save-transcript!
  "Save JSONL agent output to ../transcripts relative to project-root.
  Filename: {date}-{time}-{agent}[-{model}][-{reasoning}]-{challenge}[-{subsystem}]-phase{ID}[-attempt{K}][-retry{R}].jsonl
  The {subsystem} segment is present only on multi-subsystem runs (n > 1).
  {ID} is the phase number for numbered phases, or the stage name for keyword
  stages (decompose, full-spec-review).
  {K} counts validation-driven retries (a phase re-run because a later phase
  failed it); {R} counts transient-server-error re-invocations of the SAME
  attempt. They are separate segments because they mean different things: {K}
  is a decision the runner made about the work, {R} is infrastructure noise.
  All transcripts of one challenge run share the same {date}-{time} prefix
  (the run-start-time), so they can be grouped as a unit. Returns the path written."
  ([project-root agent-name model reasoning challenge-name content
    phase-id attempt run-start-time]
   (save-transcript! project-root agent-name model reasoning challenge-name
                     content phase-id attempt run-start-time nil 0))
  ([project-root agent-name model reasoning challenge-name content
    phase-id attempt run-start-time subsystem]
   (save-transcript! project-root agent-name model reasoning challenge-name
                     content phase-id attempt run-start-time subsystem 0))
  ([project-root agent-name model reasoning challenge-name content
    phase-id attempt run-start-time subsystem retry]
   (let [t               (or run-start-time (java.time.LocalDateTime/now))
         date-str        (.format t (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd"))
         time-str        (.format t (java.time.format.DateTimeFormatter/ofPattern "HHmmss"))
         transcripts-dir (fs/path project-root ".." "transcripts")
         base            (cond
                           (and model reasoning) (format "%s-%s-%s-%s-%s" date-str time-str agent-name (str/replace model #"[/\\\\]" "_") reasoning)
                           model                 (format "%s-%s-%s-%s" date-str time-str agent-name (str/replace model #"[/\\\\]" "_"))
                           :else                 (format "%s-%s-%s" date-str time-str agent-name))
         sub-segment     (if subsystem (str "-" subsystem) "")
         retry-segment   (if (pos? (or retry 0)) (format "-retry%d" retry) "")
         phase-suffix    (cond
                           (and phase-id (> attempt 1)) (format "%s-phase%s-attempt%d%s" sub-segment (phase-id-str phase-id) attempt retry-segment)
                           phase-id                     (format "%s-phase%s%s" sub-segment (phase-id-str phase-id) retry-segment)
                           :else                        "")
         filename        (str base "-" challenge-name phase-suffix ".jsonl")
         path            (str (fs/path transcripts-dir filename))]
     (fs/create-dirs transcripts-dir)
     (spit path content)
     path)))

;;; Core runner

(def ^:dynamic *outer-timeout-s*
  "Outer timeout for a single subprocess invocation (seconds).
  Phase invocations bind this to min(default, time-remaining-in-overall-budget)
  so a single phase can't exceed either the per-call cap or the run-wide cap."
  (* 3 3600))

(def ^:dynamic *overall-timeout-s*
  "Hard cap on total wall-clock for one challenge run (seconds).
  Includes all phase invocations, retries, lint, and test runs."
  (* 8 3600))

(def ^:dynamic *phase-retry-cap*
  "Max times a single phase invocation is re-run after a transient server-side
  error (overload, 5xx, rate limit) before giving up. Fresh session each time."
  3)

(defn time-remaining-s
  "Seconds left in the overall challenge run budget. Never negative."
  [run-start-millis]
  (let [elapsed (/ (- (System/currentTimeMillis) run-start-millis) 1000.0)]
    (max 0 (- *overall-timeout-s* elapsed))))

(defn stream-and-capture
  "Read an InputStream line-by-line, printing each line to print-fn
  and accumulating into a StringBuilder. Returns the captured string."
  [^java.io.InputStream input-stream print-fn]
  (let [sb (StringBuilder.)]
    (with-open [rdr (io/reader input-stream)]
      (loop []
        (when-let [line (.readLine ^java.io.BufferedReader rdr)]
          (.append sb line)
          (.append sb "\n")
          (print-fn line)
          (recur))))
    (str sb)))

(def ^:dynamic *verbose* false)

(def ^:dynamic *pretty* false)

;; Two model tiers: fast and slow. Phase 0, decompose, planning (1),
;; plan-validation (2), and full-spec-review always run on the slow tier.
;; Subsystem build phases always use the fast tier. -main resolves both tiers
;; from required CLI opts.
(def ^:dynamic *fast-model* nil)
(def ^:dynamic *fast-reasoning* nil)
(def ^:dynamic *slow-model* nil)
(def ^:dynamic *slow-reasoning* nil)
(def ^:dynamic *isolate* false)
(def ^:dynamic *isolate-network* false)

(defn require-reference-isolation!
  "Reference-bearing authoring surfaces require both filesystem and network isolation.
   Encryption hides challenge files, not atlas pages, Git history or live Portals."
  [project-root strict?]
  (when (and (not strict?)
             (some #(fs/exists? (fs/path project-root %)) ["docs/atlas" "review"]))
    (throw (ex-info
            "Reference-bearing docs/atlas or review is present: solver launches require --isolate-network (Claude or OpenCode). Encryption and --isolate alone do not block reference Portals or upstream source downloads."
            {:reason :reference-isolation-required}))))

;; Recorded per phase invocation (transcript metadata and run manifest).
(defn isolation-mode []
  (cond *isolate-network* "bubblewrap-provider-network"
        *isolate* "bubblewrap-public-only"
        :else "none"))

(defn solver-command [cmd project-root challenge-name agent-name]
  (require-reference-isolation! project-root *isolate-network*)
  (if (or *isolate* *isolate-network*)
    (into (cond-> ["python3" (str (fs/path project-root "scripts/isolate_solver.py"))
                   "--repo" project-root "--challenge" challenge-name "--agent" agent-name]
            *isolate-network* (into ["--network" "strict"])
            true (conj "--")) cmd)
    cmd))

(defn tier-config
  "Return [model reasoning] for a tier keyword (:fast | :slow)."
  [tier]
  (if (= :slow tier)
    [*slow-model* *slow-reasoning*]
    [*fast-model* *fast-reasoning*]))

;;; Pretty-printing stream-json output

(def ^:private BOLD "\033[1m")
(def ^:private DIM "\033[2m")
(def ^:private GREEN "\033[32m")
(def ^:private YELLOW "\033[33m")
(def ^:private CYAN "\033[36m")
(def ^:private RED "\033[31m")
(def ^:private RESET "\033[0m")

(defn- pretty-separator [label]
  (let [ts (.format (java.time.LocalDateTime/now)
                    (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss"))]
    (str "\n" DIM "─── " RESET BOLD label RESET DIM " ─── " ts " ───" RESET)))

(defn- truncate-lines [s max-lines]
  (when s
    (let [lines (str/split-lines s)
          n (count lines)]
      (if (<= n max-lines)
        s
        (str (str/join "\n" (take max-lines lines))
             "\n" DIM "  ... (" (- n max-lines) " more lines)" RESET)))))

(defn- head-tail-lines [s head-n tail-n]
  (when s
    (let [lines (str/split-lines s)
          n (count lines)
          total (+ head-n tail-n)]
      (if (<= n total)
        s
        (str (str/join "\n" (take head-n lines))
             "\n" DIM "  ... (skipped " (- n total) " lines)" RESET "\n"
             (str/join "\n" (take-last tail-n lines)))))))

(defn- pretty-tool-call [{:keys [name input]}]
  (println (pretty-separator (str CYAN "Tool: " name RESET)))
  (case name
    "Bash"
    (do (println (str GREEN "$ " RESET (:command input)))
        (when (:description input)
          (println (str DIM "  # " (:description input) RESET))))

    "Edit"
    (do (println (str YELLOW (:file_path input) RESET))
        (println (str DIM "old:" RESET))
        (println (truncate-lines (:old_string input) 20))
        (println (str DIM "new:" RESET))
        (println (truncate-lines (:new_string input) 20)))

    "Write"
    (do (println (str YELLOW (:file_path input) RESET))
        (println (truncate-lines (:content input) 40)))

    "Read"
    (println (str YELLOW (:file_path input) RESET
                  (when (:offset input) (str " (offset=" (:offset input) ")"))
                  (when (:limit input) (str " (limit=" (:limit input) ")"))))

    "Glob"
    (println (str (:pattern input)
                  (when (:path input) (str " in " (:path input)))))

    "Grep"
    (println (str "/" (:pattern input) "/"
                  (when (:glob input) (str " --glob " (:glob input)))
                  (when (:path input) (str " in " (:path input)))))

    "Skill"
    (println (str BOLD (:skill input) RESET
                  (when (:args input) (str " " (:args input)))))

    "TodoWrite"
    (doseq [todo (:todos input)]
      (println (str (case (:status todo)
                      "completed" (str GREEN "✓" RESET)
                      "in_progress" (str YELLOW "→" RESET)
                      " ")
                    " " (:content todo))))

    ;; default: skip noisy tools like Glob results
    nil))

(defn- pretty-tool-result [content]
  (when (sequential? content)
    (doseq [item content]
      (when (= "tool_result" (:type item))
        (let [result (:content item)
              error? (:is_error item)]
          (when (and (string? result) (not (str/blank? result)))
            (if error?
              (println (str RED (truncate-lines result 30) RESET))
              (println (str DIM (truncate-lines result 30) RESET)))))))))

(defn- pretty-print-line
  "Parse a stream-json line and print a human-readable version."
  [line]
  (try
    (let [event (json/parse-string line true)]
      (case (:type event)
        "system"
        (when (= "init" (:subtype event))
          (println (pretty-separator "Session"))
          (println (str "Model: " BOLD (:model event) RESET
                        "  Mode: " (:permissionMode event))))

        "assistant"
        (let [content (-> event :message :content)]
          (when (sequential? content)
            (doseq [item content]
              (case (:type item)
                "text" (when (and (:text item) (not (str/blank? (:text item))))
                         (println (:text item)))
                "thinking" (when (and (:thinking item) (not (str/blank? (:thinking item))))
                             (println (pretty-separator (str DIM "Thinking" RESET)))
                             (println (str DIM (head-tail-lines (:thinking item) 100 100) RESET)))
                "tool_use" (pretty-tool-call item)
                nil))))

        "user"
        (let [content (-> event :message :content)]
          (when (sequential? content)
            (doseq [item content]
              (when (and (= "text" (:type item)) (:text item) (not (str/blank? (:text item))))
                (println (str DIM (truncate-lines (:text item) 10) RESET)))))
          (pretty-tool-result content))

        "result"
        (do (println (pretty-separator "Result"))
            (println (str (if (:is_error event) RED GREEN)
                          (:result event) RESET)))

        nil))
    (catch Exception _ nil))
  (flush))

(defn invoke-command!
  "Run a shell command, capturing stdout+stderr and wall-clock duration.
  In verbose mode, streams output to console in real time.
  Always captures partial output on timeout so transcripts are preserved.
  Returns {:exit int, :out string, :err string, :duration-s int}."
  [cmd project-root]
  (when *verbose*
    (prn "CMD:" cmd)
    (flush))
  (let [start (System/currentTimeMillis)
        started-at (str (java.time.Instant/now))
        result (try
                 ;; Always stream via futures so partial output is captured on timeout
                 (let [proc (p/process cmd {:dir project-root :in ""
                                            :extra-env {"CHALLENGE_KEY" nil}})
                       out-fut (future
                                 (stream-and-capture
                                  (:out proc)
                                  (fn [line]
                                    (when *verbose*
                                      (if *pretty*
                                        (pretty-print-line line)
                                        (do (println line) (flush)))))))
                       err-fut (future
                                 (stream-and-capture
                                  (:err proc)
                                  (fn [line]
                                    (when *verbose*
                                      (binding [*out* *err*]
                                        (println line)
                                        (flush))))))
                       done (deref proc
                              (* *outer-timeout-s* 1000)
                              :timeout)]
                   (if (= done :timeout)
                     (do (.destroyForcibly (:proc proc))
                         {:exit 1
                          :timed-out? true
                          :out (str @out-fut)
                          :err (str "Timeout after " *outer-timeout-s* "s\n" @err-fut)})
                     {:exit (:exit done)
                      :out @out-fut
                      :err @err-fut}))
                 (catch Exception e
                   {:exit 1
                    :out ""
                    :err (str "Process error: " (.getMessage e))}))
        duration-s (quot (- (System/currentTimeMillis) start) 1000)]
    (assoc result :duration-s duration-s
                  :started-at started-at :finished-at (str (java.time.Instant/now)))))

;;; Outcome taxonomy and scoring (see docs/outcome-taxonomy.md)

(def outcome-order
  "Headline outcomes in report order."
  [:private-pass :public-pass :private-fail :private-unavailable
   :solver-fail :solver-no-implementation :timeout
   :quota-or-provider-limit :user-stopped :infra-error])

(def ^:private quota-error-re
  ;; Quota / billing exhaustion. Not retried (retrying cannot help), but still a
  ;; provider limit rather than a solver failure. Applied only to
  ;; `agent-error-text`, never to raw stdout.
  #"(?i)insufficient_quota|quota\s+(?:exceeded|exhausted|reached)|exceeded your (?:current )?quota|usage limit|credit balance (?:is )?too low|out of credits|payment required|billing (?:hard )?limit")

(defn parse-test-counts
  "Sum every clojure.test summary in `text`. Returns
  {:tests n :assertions m :failures f :errors e}, or nil when no
  `Ran N tests` line is present."
  [text]
  (let [text (or text "")
        ran (re-seq #"Ran (\d+) tests? containing (\d+) assertions?" text)
        fe  (re-seq #"(\d+) failures?, (\d+) errors?" text)]
    (when (seq ran)
      {:tests      (reduce + (map #(parse-long (nth % 1)) ran))
       :assertions (reduce + (map #(parse-long (nth % 2)) ran))
       :failures   (reduce + (map #(parse-long (nth % 1)) fe))
       :errors     (reduce + (map #(parse-long (nth % 2)) fe))})))

(defn sentinel-0-of-1?
  "True for the `Private FAIL 0/1` sentinel: `Ran 1 tests`, 0 failures, and
  every counted assertion is an error. The tests never ran; it is not an
  implementation failure."
  [{:keys [tests assertions failures errors]}]
  (and (= 1 tests) (zero? failures) (pos? errors) (= assertions errors)))

(defn classify-private-result
  "Turn a private-test invocation into a verdict. `has-suite?` is whether the
  challenge has test-private/; `private-result` is nil when the suite was not
  started, else {:exit :out :err :timed-out? :timeout-s}. Sentinels (zero
  tests, no summary, grader timeout) are :unavailable, never :fail, and name
  themselves in :private-sentinel.
  Returns {:private-status kw :private-counts map? :private-reason str?
  :private-sentinel kw?}."
  [has-suite? private-result]
  (cond
    (not has-suite?)
    {:private-status :none}

    (nil? private-result)
    {:private-status :not-run :private-reason "private suite not started"}

    (:timed-out? private-result)
    {:private-status :unavailable :private-sentinel :grader-timeout
     :private-reason (str "grader timeout after " (:timeout-s private-result) "s")}

    :else
    (let [counts (parse-test-counts (str (:out private-result) "\n" (:err private-result)))
          bad (when counts (+ (:failures counts) (:errors counts)))]
      (cond
        (nil? counts)
        {:private-status :unavailable :private-sentinel :no-summary
         :private-reason (format "no test summary (exit %d): compile/load error or missing implementation namespace"
                                 (:exit private-result))}

        (zero? (:tests counts))
        {:private-status :unavailable :private-counts counts :private-sentinel :ran-0
         :private-reason "Ran 0 tests: suite did not load"}

        ;; `FAIL 0/1`: one test whose only report is an uncaught error, with no
        ;; assertion passing or failing. The suite errored before any check ran
        ;; (load or launch failure), so no correctness evidence exists.
        (sentinel-0-of-1? counts)
        {:private-status :unavailable :private-counts counts :private-sentinel :zero-of-one
         :private-reason "sentinel 0/1: suite errored before any assertion ran"}

        (pos? bad)
        {:private-status :fail :private-counts counts}

        (zero? (:exit private-result))
        {:private-status :pass :private-counts counts}

        :else
        {:private-status :unavailable :private-counts counts
         :private-reason (format "exit %d with no failing assertions" (:exit private-result))}))))

(defn classify-completion
  "How the solver run ended, from phase-loop!'s {:status :phase-results}."
  [{:keys [status phase-results]}]
  (let [last-r (last phase-results)]
    (cond
      (= :pass status)          :completed
      (:provider-limit? last-r) :quota-or-provider-limit
      (:user-stopped? last-r)   :user-stopped
      (= :timeout status)       :timeout
      :else                     :solver-fail)))

(defn classify-outcome
  "Headline outcome. First match wins; the private verdict dominates the
  runner's phase status."
  [{:keys [infra-error? completion private-status has-implementation?]}]
  (cond
    infra-error?                                                     :infra-error
    (= :pass private-status)                                         :private-pass
    (= :fail private-status)                                         :private-fail
    (#{:timeout :quota-or-provider-limit :user-stopped} completion)  completion
    (false? has-implementation?)                                     :solver-no-implementation
    (#{:unavailable :not-run} private-status)                        :private-unavailable
    (and (= :none private-status) (= :completed completion))         :public-pass
    :else                                                            :solver-fail))

(defn count-semantic-retries
  "Phase invocations that directly follow a FAIL / MAJOR_FAIL verdict in the
  same subsystem. One build per subsystem is NOT a retry, and transient
  provider retries inside a single invocation are infrastructure, not solver
  behaviour."
  [phase-results]
  (count (filter (fn [[a b]]
                   (and (#{:fail :major-fail} (:verdict a))
                        (= (:subsystem a) (:subsystem b))))
                 (partition 2 1 phase-results))))

(defn compute-challenge-score
  "Score from the headline outcome and semantic retry count. Returns nil for
  outcomes that carry no evidence about the solver (unscored, excluded from
  averages)."
  [outcome retries]
  (case outcome
    (:private-pass :public-pass) (max 1 (Math/round (/ 100.0 (Math/pow 2 (or retries 0)))))
    (:private-unavailable :infra-error :quota-or-provider-limit :user-stopped) nil
    0))

(defn outcome-label [outcome]
  (str/upper-case (name (or outcome :solver-fail))))

(defn runner-label [status]
  (case status :pass "PASS" :timeout "TIMEOUT" "FAIL"))

(defn private-label [private-status]
  (case private-status
    :pass "PASS" :fail "FAIL" :unavailable "UNAVAIL" :not-run "not-run" "-"))

(defn private-detail
  "Private verdict plus counts or reason, e.g. `FAIL (2 failures, 0 errors / 10 tests)`."
  [{:keys [private-status private-counts private-reason]}]
  (str (private-label private-status)
       (cond
         (and private-counts (#{:pass :fail} private-status))
         (format " (%d failures, %d errors / %d tests)"
                 (:failures private-counts) (:errors private-counts) (:tests private-counts))
         private-reason (str " (" private-reason ")")
         :else "")))

(defn challenge-headline
  "Per-challenge console line. Leads with the outcome; the runner's phase
  status is labelled as such and never stands alone."
  [{:keys [outcome status challenge-score scoring duration-s retries builds] :as r}]
  (let [scores (:scores scoring)]
    (str (outcome-label outcome)
         " | Private: " (private-detail r)
         " | Runner: " (runner-label status)
         " | Score: " (if (some? challenge-score) challenge-score "-")
         (format " | Builds: %d Retries: %d" (or builds 0) (or retries 0))
         (when-let [a (:alignment scores)] (str " | Align: " a "/5"))
         (when-let [a (:test-alignment scores)] (str " | TestAlign: " a "/5"))
         (format " (%ds)" (or duration-s 0)))))

(defn outcome-counts-line
  "Summary line counting results by headline outcome. Correctness (private
  pass) leads; runner PASS is reported separately as completion only."
  [results]
  (let [by (frequencies (map :outcome results))
        runner-pass (count (filter #(= :pass (:status %)) results))]
    (str (format "Challenges: %d | " (count results))
         (str/join " | " (for [o outcome-order
                               :let [n (get by o 0)]
                               :when (or (pos? n) (#{:private-pass :private-fail :private-unavailable} o))]
                           (format "%s: %d" (outcome-label o) n)))
         (format " | Runner PASS (completion only): %d" runner-pass))))

(defn average-score-line
  "Average over scored results only; unscored outcomes are counted, not zeroed."
  [results]
  (let [scored (keep :challenge-score results)
        unscored (- (count results) (count scored))]
    (if (seq scored)
      (format "Average score: %.1f (n=%d scored, %d unscored)"
              (/ (reduce + 0.0 scored) (count scored)) (count scored) unscored)
      (format "Average score: - (n=0 scored, %d unscored)" unscored))))

(defn result-outcome
  "Headline outcome of a result map; derived for records that predate :outcome."
  [{:keys [outcome status private-status]}]
  (or outcome
      (classify-outcome {:completion (case status :pass :completed :timeout :timeout :solver-fail)
                         :private-status (if (#{:pass :fail :unavailable :not-run} private-status)
                                           private-status
                                           :none)})))

(defn private-verdict-line
  "Private verdict counts. Unavailable means not evaluated, never FAIL."
  [results]
  (let [by (frequencies (map :private-status results))]
    (when (some by [:pass :fail :unavailable :not-run])
      (format "Private verdicts: PASS %d | FAIL %d | UNAVAILABLE (not evaluated) %d | not-run %d"
              (get by :pass 0) (get by :fail 0) (get by :unavailable 0) (get by :not-run 0)))))

;;; Diagnostic scrubbing (docs/outcome-taxonomy.md, "Redaction")
;;
;; The manifest and bundle keep the private-suite output and the
;; full-spec-review text line for line, minus credentials and anything that may
;; carry runner-protected content. Redacted spans are replaced in place with
;; [REDACTED:<kind>]; when protection cannot be verified, whole lines are.

(def secret-env-name-re #"(?i)KEY|TOKEN|SECRET|PASSWORD|PASSWD|CREDENTIAL|AUTH")

(def min-secret-env-length 8)

(defn secret-env-values
  "[name value] pairs for secret-named variables in env, longest value first."
  [env]
  (->> env
       (keep (fn [[k v]]
               (when (and (re-find secret-env-name-re (str k))
                          (>= (count (str v)) min-secret-env-length))
                 [(str k) (str v)])))
       (sort-by (comp - count second))
       vec))

(def credential-patterns
  "[regex replacement] pairs applied in order. Values already replaced by an
  earlier rule are not matched again. Quantifiers next to a literal are
  bounded so a long unbroken line cannot backtrack quadratically."
  [[#"-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z ]*PRIVATE KEY-----" "[REDACTED:private-key]"]
   [#"\bsk-ant-[A-Za-z0-9_\-]{16,}" "[REDACTED:api-key]"]
   [#"\bsk-(?:proj-|or-v1-)?[A-Za-z0-9_\-]{32,}" "[REDACTED:api-key]"]
   [#"\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,})" "[REDACTED:github-token]"]
   [#"\b(?:AKIA|ASIA)[0-9A-Z]{16}\b" "[REDACTED:aws-key-id]"]
   [#"\bxox[abposr]-[A-Za-z0-9-]{10,}" "[REDACTED:slack-token]"]
   [#"\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}" "[REDACTED:jwt]"]
   [#"(?i)(\bauthorization\s*[:=]\s*(?:basic|bearer|token)?\s*)(?!\[REDACTED)[A-Za-z0-9._~+/=-]{8,}" "$1[REDACTED:authorization]"]
   [#"(?i)(\bbearer\s+)(?!\[REDACTED)[A-Za-z0-9._~+/=-]{12,}" "$1[REDACTED:bearer]"]
   [#"(?i)(\b[a-z][a-z0-9+.-]{0,30}://)[^/\s:@]{1,256}:[^/\s@]{1,256}@" "$1[REDACTED:url-credentials]@"]
   [#"(?i)(\b[A-Za-z0-9_.-]{0,40}(?:api[_-]?key|secret|token|password|passwd|credential)s?[A-Za-z0-9_.-]{0,40}[\"']?[ \t]*[:=][ \t]*[\"']?)(?!\[REDACTED)[^\s\"',;}\]]{8,}" "$1[REDACTED:credential]"]])

(def private-test-report-patterns
  "clojure.test report lines that print private-test source or expected data:
  the assertion form after `expected:`, and the evaluated comparison after
  `actual: (not ...)`, which embeds the expected value. Exceptions printed
  after `actual:` go through the private-test data rule."
  [[#"(?m)^([ \t]*expected:[ \t]?)(?!\[REDACTED).+$" "$1[REDACTED:private-test-assertion]"]
   [#"(?m)^([ \t]*actual:[ \t]?)\(not[ \t(].*$" "$1[REDACTED:private-test-comparison]"]])

;; Protected content. Protected sources are every file of the challenge that
;; the solver snapshot (scripts/isolate_solver.py) leaves out: test-private/,
;; test-resources/, test/, test-harness/, test-named src files, notes, and so
;; on. Public text is exactly what the snapshot copies, plus repository paths.
;; Text is compared as case-folded words (runs of letters/digits): every run of
;; 1..max-protected-ngram consecutive output words that occurs in a protected
;; file and in no public text is redacted, however short. Protected files are
;; streamed in full, whatever their size. If any protected file cannot be read
;; (or only its .enc ciphertext is present) nothing is verifiable, so every
;; non-blank line of every scrubbed field is redacted.

(def snapshot-shared-allowlist
  "Mirrors SHARED_ALLOWLIST in scripts/isolate_solver.py."
  ["deps.edn" "lib/rama-deps" "lib/harness/deps.edn" "lib/harness/src"
   "plugins/rama-skill/skills/rama" ".agents/skills/challenge-phase"
   ".claude/commands/challenge-phase.md" "scripts/import-kondo-configs.sh"])

(def snapshot-challenge-allowlist
  "Mirrors CHALLENGE_ALLOWLIST in scripts/isolate_solver.py."
  ["README.md" "deps.edn" "src" ".clj-kondo"])

(def snapshot-protected-dirs
  "Mirrors PROTECTED_DIRS in scripts/isolate_solver.py."
  #{".git" "test-private" "test-resources" "test" "test-harness" "review" "atlas"})

(def snapshot-secret-file-re
  "Mirrors SECRET_FILE_RE in scripts/isolate_solver.py."
  #"(?i)^(\.env(\..*)?|\.netrc|\.git-credentials|\.npmrc|\.pypirc|.*\.(pem|key|p12|pfx|jks|keystore)|id_(rsa|dsa|ecdsa|ed25519)(\.pub)?|\.?credentials(\.json)?|auth\.json)$")

(def max-protected-ngram
  "Longest run of consecutive words compared at once. Longer echoes are caught
  through their runs of this length."
  8)

(def baseline-vocabulary
  "Words of clojure.test, JVM and Clojure diagnostics. Treated as public, and
  kept in private-test output even when no public file contains them."
  (str/split (str "testing fail error errors failure failures ran tests test containing assertion assertions "
                  "expected actual not nil true false caused by at in more common frames omitted unknown source "
                  "native method uncaught exception exceptions throwable thrown throw stack trace message cause data "
                  "info warning warn debug trace execution compiling compile compiler syntax unable to resolve symbol "
                  "this context no such var namespace could locate init or on classpath file found java lang util io "
                  "concurrent clojure core rama com rpl invoke invokestatic apply applyto call do eval main thread "
                  "reflection wrong number of args passed fn the a an is was be and with for from into while when "
                  "after before timed out timeout illegal argument state null pointer class cast arithmetic divide "
                  "by zero index bounds unsupported operation interrupted runtime assert failed exceptioninfo "
                  "runtimeexception illegalargumentexception illegalstateexception nullpointerexception "
                  "classcastexception arithmeticexception indexoutofboundsexception unsupportedoperationexception "
                  "timeoutexception executionexception interruptedexception filenotfoundexception compilerexception "
                  "assertionerror stackoverflowerror outofmemoryerror authorization bearer clj cljc edn")
             #"\s+"))

(defn- snapshot-denied?
  "True when isolate_solver.py's `denied` refuses a component of the file's
  relative path parts. `source-tree?` adds the test-named src file rule."
  [parts source-tree?]
  (boolean (or (some #(or (snapshot-protected-dirs %) (str/ends-with? % ".enc")
                          (re-matches snapshot-secret-file-re %))
                     parts)
               (and source-tree? (str/includes? (peek parts) "test")))))

(defn- walk-tree
  "{:files regular files, :other symlinks and special files} under dir.
  Links are never followed."
  [dir]
  (let [files (volatile! []) other (volatile! [])]
    (letfn [(walk [d]
              (doseq [p (sort (fs/list-dir d))]
                (cond (fs/sym-link? p) (vswap! other conj p)
                      (fs/directory? p {:nofollow-links true}) (walk p)
                      (fs/regular-file? p {:nofollow-links true}) (vswap! files conj p)
                      :else (vswap! other conj p))))]
      (when (and (not (fs/sym-link? dir)) (fs/directory? dir {:nofollow-links true}))
        (walk dir)))
    {:files @files :other @other}))

(defn- rel-parts [base path]
  (mapv str (fs/relativize base path)))

(defn scrub-corpus
  "Protected and public files for one challenge. :unavailable names why the
  protected set cannot be verified (scrubbing then fails closed)."
  [project-root challenge-name]
  (let [root (fs/path project-root)
        cdir (fs/path root "challenges" challenge-name)
        {:keys [files other]} (walk-tree cdir)
        build-cache? #(= ".cpcache" (first (rel-parts cdir %)))
        files (remove build-cache? files)
        other (remove build-cache? other)
        public? #(let [ps (rel-parts cdir %)]
                   (and (some #{(first ps)} snapshot-challenge-allowlist)
                        (not (snapshot-denied? ps (= "src" (first ps))))))
        shared (mapcat (fn [rel]
                         (let [p (fs/path root rel)]
                           (cond (fs/sym-link? p) []
                                 (fs/regular-file? p {:nofollow-links true}) [p]
                                 :else (remove #(snapshot-denied? (rel-parts root %) false)
                                               (:files (walk-tree p))))))
                       snapshot-shared-allowlist)
        protected (vec (remove public? files))]
    {:protected protected
     :public (vec (concat (filter public? files) shared))
     :implementation (:files (walk-tree (fs/path root "implementations" challenge-name "src")))
     :paths (mapv #(str (fs/relativize root %)) (concat files other))
     :unavailable (cond (not (fs/directory? cdir {:nofollow-links true})) "challenge directory missing"
                        (seq other) "symlink or special file in the challenge directory"
                        (some #(str/ends-with? (str %) ".enc") protected) "encrypted protected file")}))

(def ^:private word-re #"[\p{L}\p{N}]+")

(defn- lower ^String [^String s] (.toLowerCase s java.util.Locale/ROOT))

(defn- words [s] (map lower (re-seq word-re s)))

(defn- each-word!
  "Call (f word) for each case-folded word of the file, in order. Reads 1 MiB
  chunks, so a file of any size is scanned completely."
  [path f]
  (with-open [^java.io.Reader r (io/reader (str path) :encoding "UTF-8")]
    (let [buf (char-array (* 1024 1024))]
      (loop [carry ""]
        (let [n (.read r buf)]
          (if (neg? n)
            (when (seq carry) (f (lower carry)))
            (let [s (str carry (String. buf 0 n))
                  m (re-matcher word-re s)
                  len (count s)]
              ;; A word touching the chunk end may continue in the next chunk.
              (recur (loop []
                       (if (.find m)
                         (if (= (.end m) len)
                           (.group m)
                           (do (f (lower (.group m))) (recur)))
                         ""))))))))))

(defn- ngram-scanner
  "Word callback adding to `found` each run of up to max-protected-ngram words
  (space-joined) ending at the current word that is in `target`. `steps` must
  contain every suffix of every target run."
  [^java.util.Set steps ^java.util.Set target ^java.util.Set found]
  (let [win (java.util.ArrayDeque.)]
    (fn [w]
      (if-not (.contains steps w)
        (.clear win)
        (do (.addFirst win w)
            (when (> (.size win) max-protected-ngram) (.removeLast win))
            (loop [it (.iterator win) k nil]
              (when (.hasNext it)
                (let [k (if k (str (.next it) " " k) (.next it))]
                  (when (.contains steps k)
                    (when (.contains target k) (.add found k))
                    (recur it k))))))))))

(defn- word-runs-ngrams
  "Every run of up to max-protected-ngram consecutive words of the word
  vectors, space-joined (closed under suffixes)."
  [word-vectors]
  (into #{} (mapcat (fn [ws]
                      (let [n (count ws)]
                        (for [i (range n) k (range 1 (inc (min max-protected-ngram (- n i))))]
                          (str/join " " (subvec ws i (+ i k)))))))
        word-vectors))

(defn- protected-only-keys
  "The word runs of `word-vectors` (output lines split at markers) that occur
  in a protected file and in no public text. A first pass finds which output
  words occur in protected files at all; only runs of those are compared.
  Throws when a protected file cannot be read; the caller then fails closed."
  [corpus word-vectors]
  (let [out-words (java.util.HashSet. ^java.util.Collection (into #{} cat word-vectors))
        present (java.util.HashSet.)]
    (doseq [p (:protected corpus)]
      (each-word! p #(when (.contains out-words %) (.add present %))))
    (let [runs (into #{} (comp (mapcat #(partition-by (fn [w] (.contains present w)) %))
                               (filter #(.contains present (first %)))
                               (map vec))
                     word-vectors)
          steps (java.util.HashSet. ^java.util.Collection (word-runs-ngrams runs))
          found (java.util.HashSet.)]
      (when-not (.isEmpty steps)
        (doseq [p (:protected corpus)]
          (each-word! p (ngram-scanner steps steps found))))
      (when-not (.isEmpty found)
        (let [public (java.util.HashSet.)]
          (doseq [p (:public corpus)]
            ;; An unreadable public file only widens redaction.
            (try (each-word! p (ngram-scanner steps found public)) (catch Exception _ nil)))
          (doseq [t (concat (:paths corpus) baseline-vocabulary)]
            (run! (ngram-scanner steps found public) (words t)))
          (.removeAll found public)))
      found)))

(defn- vocabulary
  "Case-folded words a solver could know: public files, the implementation's
  own source, repository paths and baseline-vocabulary."
  [corpus]
  (let [v (java.util.HashSet.)]
    (doseq [p (concat (:public corpus) (:implementation corpus))]
      (try (each-word! p #(.add v %)) (catch Exception _ nil)))
    (doseq [t (concat (:paths corpus) baseline-vocabulary)]
      (run! #(.add v %) (words t)))
    v))

(defn scrub-context
  "Redaction inputs for one challenge: secret env values and the protected
  and public corpus. Scrub while the protected files are plaintext."
  [project-root challenge-name env]
  (let [corpus (scrub-corpus project-root challenge-name)]
    {:env-secrets (secret-env-values env)
     :corpus corpus
     :vocabulary (delay (vocabulary corpus))}))

(def redaction-kinds
  [:secret-env :credential :private-test-assertion :protected-plaintext :private-test-data
   :protected-index-unavailable])

(defn- replace-counting [text re replacement]
  (let [n (count (re-seq re text))]
    [(if (pos? n) (str/replace text re replacement) text) n]))

(def ^:private marker-re #"\[REDACTED:[^\]\n]*\]")

(def ^:private location-number-re
  "Line/column numbers of a file:line location; never compared or redacted."
  #"(?<=\.(?:clj|cljc|cljs|edn|bb|java|py|kt|scala):)\d+(?::\d+)?")

(def ^:private count-line-re
  #"^(?:Ran \d+ tests containing \d+ assertions\.|\d+ failures, \d+ errors\.|\s*\.\.\. \d+ (?:more|common frames omitted))$")

(def private-test-template-res
  "Private-test output lines kept verbatim apart from protected word runs:
  clojure.test headers and summaries, stack frames and redacted assertions."
  [#"^\s*$"
   #"^Testing \S+$"
   #"^(?:FAIL|ERROR) in \([^()]*\) \([^()\s]+\)$"
   #"^Ran \d+ tests containing \d+ assertions\.$"
   #"^\d+ failures, \d+ errors\.$"
   #"^\s*at [^\s()]+ ?\((?:[^\s()]+|Unknown Source|Native Method)\)$"
   #"^\s*\.\.\. \d+ (?:more|common frames omitted)$"
   #"^\s*(?:expected|actual):\s*\[REDACTED:[^\]]+\]$"])

(defn- re-spans [re s]
  (let [m (re-matcher re s)]
    (loop [acc []] (if (.find m) (recur (conj acc [(.start m) (.end m)])) acc))))

(defn- overlaps? [spans s e]
  (some (fn [[a b]] (and (< s b) (< a e))) spans))

(defn- line-segments
  "Runs of comparable words of a line, as vectors of {:s :e :w}. Redaction
  markers and location numbers split runs; count summary lines have none."
  [^String line]
  (if (re-matches count-line-re line)
    []
    (let [blocked (into (re-spans marker-re line) (re-spans location-number-re line))
          m (re-matcher word-re line)]
      (loop [segs [] cur [] prev-end 0]
        (if (.find m)
          (let [s (.start m) e (.end m)
                w {:s s :e e :w (lower (.group m))}
                flushed (cond-> segs (seq cur) (conj cur))]
            (cond (overlaps? blocked s e) (recur flushed [] e)
                  (overlaps? blocked prev-end s) (recur flushed [w] e)
                  :else (recur segs (conj cur w) e)))
          (cond-> segs (seq cur) (conj cur)))))))

(defn- segment-ngrams
  "[start-index length key] for every run of up to max-protected-ngram words."
  [seg]
  (let [ws (mapv :w seg) n (count ws)]
    (for [i (range n) k (range 1 (inc (min max-protected-ngram (- n i))))]
      [i k (str/join " " (subvec ws i (+ i k)))])))

(defn- merge-spans
  "Sort spans and merge those overlapping or separated only by non-word text."
  [^String line spans]
  (reduce (fn [acc [s e]]
            (let [[ps pe] (peek acc)]
              (if (and pe (or (<= s pe) (not (re-find #"[\p{L}\p{N}]" (subs line pe s)))))
                (conj (pop acc) [ps (max pe e)])
                (conj acc [s e]))))
          [] (sort spans)))

(defn- replace-spans [^String line spans marker]
  (let [sb (StringBuilder.)]
    (loop [i 0 ss spans]
      (if-let [[s e] (first ss)]
        (do (.append sb (subs line i s)) (.append sb ^String marker) (recur e (rest ss)))
        (str (.append sb (subs line i)))))))

(defn- protected-spans [line segs ^java.util.Set protected]
  (merge-spans line
               (for [seg segs
                     [i k key] (segment-ngrams seg)
                     :when (.contains protected key)]
                 [(:s (seg i)) (:e (seg (+ i k -1)))])))

(def ^:private quoted-re #"\"(?:[^\"\\]++|\\.)*+\"?")

(defn- collection-spans
  "Printed collection literals ({...}, [...], #{...}) outside blocked spans.
  An unclosed literal runs to the end of the line."
  [^String line blocked]
  (let [n (count line)
        skip (into {} blocked)]
    (loop [i 0 acc []]
      (if (>= i n)
        acc
        (let [c (.charAt line i)]
          (cond (skip i) (recur (skip i) acc)
                (or (= c \{) (= c \[))
                (let [end (loop [j (inc i) depth 1]
                            (cond (zero? depth) j
                                  (>= j n) n
                                  (skip j) (recur (skip j) depth)
                                  :else (case (.charAt line j)
                                          (\{ \[ \() (recur (inc j) (inc depth))
                                          (\} \] \)) (recur (inc j) (dec depth))
                                          (recur (inc j) depth))))
                      start (if (and (pos? i) (= \# (.charAt line (dec i)))) (dec i) i)]
                  (recur end (conj acc [start end])))
                :else (recur (inc i) acc)))))))

(defn- private-test-data-spans
  "Spans of a non-template private-test line that may carry test data: quoted
  strings, collection literals, numbers outside file:line locations, and
  words outside the vocabulary."
  [^String line ^java.util.Set vocab]
  (let [markers (re-spans marker-re line)
        quoted (re-spans quoted-re line)
        colls (if (re-find #"\{|\[(?!REDACTED:)" line)
                (collection-spans line (into markers quoted))
                [])
        literals (into quoted colls)
        exempt (into markers (re-spans location-number-re line))
        m (re-matcher word-re line)
        loose (loop [acc []]
                (if (.find m)
                  (let [s (.start m) e (.end m) w (.group m)]
                    (recur (if (or (overlaps? exempt s e) (overlaps? literals s e)
                                   (and (not (re-matches #"\p{N}+" w)) (.contains vocab (lower w))))
                             acc
                             (conj acc [s e]))))
                  acc))]
    (merge-spans line (concat literals loose))))

(defn- pre-scrub
  "Secret env values, credentials and (private-test output) clojure.test
  assertion lines. Returns [text counts]."
  [text {:keys [env-secrets private-test?]}]
  (let [counts (volatile! (zipmap redaction-kinds (repeat 0)))
        tally! (fn [kind n] (vswap! counts update kind + n))
        text (reduce (fn [t [k v]]
                       (let [n (count (re-seq (re-pattern (java.util.regex.Pattern/quote v)) t))]
                         (tally! :secret-env n)
                         (if (pos? n) (str/replace t v (str "[REDACTED:env:" k "]")) t)))
                     (str text) env-secrets)
        text (reduce (fn [t [re r]] (let [[t n] (replace-counting t re r)] (tally! :credential n) t))
                     text credential-patterns)
        text (if private-test?
               (reduce (fn [t [re r]] (let [[t n] (replace-counting t re r)] (tally! :private-test-assertion n) t))
                       text private-test-report-patterns)
               text)]
    [text @counts]))

(defn scrub-texts
  "Scrub diagnostic texts together (one pass over the protected files).
  Returns one {:text str :redactions {kind count}} per text, nil for nil.
  `private-test?` adds the clojure.test assertion and private-test data rules.
  Fails closed: without a verifiable corpus every non-blank line is redacted."
  [texts {:keys [private-test? corpus] :as ctx}]
  (let [pre (mapv #(some-> % (pre-scrub ctx)) texts)
        lines (mapv #(some-> % first (str/split #"\n" -1)) pre)
        segs (mapv #(some->> % (mapv line-segments)) lines)
        protected (when (and corpus (not (:unavailable corpus)))
                    (try (protected-only-keys corpus (into #{} (comp cat cat (map #(mapv :w %)))
                                                           (remove nil? segs)))
                         (catch Exception _ nil)))
        vocab (when (and protected private-test?)
                (or (some-> (:vocabulary ctx) force) (vocabulary corpus)))]
    (mapv (fn [p ls ss]
            (when p
              (let [counts (volatile! (second p))
                    tally! (fn [kind n] (vswap! counts update kind + n))
                    scrub-line
                    (fn [line segs]
                      (cond
                        (nil? protected)
                        (if (str/blank? line)
                          line
                          (do (tally! :protected-index-unavailable 1) "[REDACTED:protected-index-unavailable]"))

                        :else
                        (let [spans (protected-spans line segs protected)
                              line (replace-spans line spans "[REDACTED:protected-plaintext]")]
                          (tally! :protected-plaintext (count spans))
                          (if (and private-test? (not-any? #(re-matches % line) private-test-template-res))
                            (let [spans (private-test-data-spans line vocab)]
                              (tally! :private-test-data (count spans))
                              (replace-spans line spans "[REDACTED:private-test-data]"))
                            line))))]
                {:text (str/join "\n" (map scrub-line ls ss)) :redactions @counts})))
          pre lines segs)))

(defn scrub-text
  "Scrub one diagnostic text; see scrub-texts."
  [text ctx]
  (first (scrub-texts [text] ctx)))

(defn- sum-redactions [& scrubbed]
  (apply merge-with + (zipmap redaction-kinds (repeat 0)) (keep :redactions scrubbed)))

(defn private-test-diagnostic
  "Complete, scrubbed private-suite output for the manifest; nil when the
  suite was not started. A sentinel is reported, never counted as a failure."
  [private-result private-verdict ctx]
  (when private-result
    (let [[out err] (scrub-texts [(:out private-result) (:err private-result)]
                                 (assoc ctx :private-test? true))]
      {:exit (:exit private-result)
       :timed-out (boolean (:timed-out? private-result))
       :timeout-s (:timeout-s private-result)
       :duration-s (:duration-s private-result)
       :status (some-> (:private-status private-verdict) name)
       :sentinel (some-> (:private-sentinel private-verdict) name)
       :counted-as-failure (= :fail (:private-status private-verdict))
       :stdout (:text out)
       :stderr (:text err)
       :redactions (sum-redactions out err)})))

(defn- full-spec-review-sources
  "The last full-spec-review phase result and its FULL_SPEC_REVIEW.md text.
  A symlinked report is never followed (it could point at host-only files)."
  [project-root challenge-name phase-results]
  (let [rel (str "implementations/" challenge-name "/FULL_SPEC_REVIEW.md")
        path (fs/path project-root rel)
        symlink? (fs/sym-link? path)]
    {:r (last (filter #(= :full-spec-review (:phase-id %)) phase-results))
     :rel rel
     :symlink? symlink?
     :report (when (and (not symlink?) (fs/regular-file? path {:nofollow-links true}))
               (slurp (str path)))}))

(defn full-spec-review-diagnostic
  "Complete, scrubbed full-spec-review findings: the FULL_SPEC_REVIEW.md
  report and the session's final message. nil when neither exists. A
  symlinked report is never followed (it could point at host-only files)."
  [project-root challenge-name phase-results ctx]
  (let [{:keys [r rel symlink? report]} (full-spec-review-sources project-root challenge-name phase-results)
        [report' message] (scrub-texts [report (:result-text r)] ctx)]
    (when (or r report symlink?)
      {:ran (boolean r)
       :verdict (some-> (:verdict r) name)
       :exit (:exit r)
       :report-path rel
       :report-present (some? report)
       :report-skipped (when symlink? "symlink")
       :report-text (:text report')
       :final-message (:text message)
       :redactions (sum-redactions report' message)})))

;;; Integrity hashes and run manifest

(defn sha256-hex [^bytes bs]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")]
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest md bs)))))

(defn file-sha256 [path]
  (when (and path (fs/regular-file? path))
    (sha256-hex (fs/read-all-bytes path))))

(defn tree-sha256
  "SHA-256 over sorted relative paths and contents of every file under dir;
  nil when dir is missing."
  [dir]
  (when (and dir (fs/directory? dir))
    (let [files (sort-by str (filter fs/regular-file? (fs/glob dir "**")))
          entries (map #(str (fs/relativize dir %) "\u0000" (file-sha256 %)) files)]
      (sha256-hex (.getBytes (str/join "\n" entries) "UTF-8")))))

(defn git-out
  "Trimmed stdout of a git command in dir, or nil on failure."
  [dir & args]
  (try
    (let [{:keys [exit out]} (apply p/shell {:dir (str dir) :out :string :err :string :continue true}
                                    "git" args)]
      (when (zero? exit) (str/trim out)))
    (catch Exception _ nil)))

(defn phase-manifest [r]
  {:phase-id (let [id (:phase-id r)] (if (keyword? id) (name id) id))
   :attempt (:attempt r)
   :subsystem (:subsystem r)
   :exit (:exit r)
   :verdict (some-> (:verdict r) name)
   :timed-out (boolean (:timed-out? r))
   :provider-limit (boolean (:provider-limit? r))
   :user-stopped (boolean (:user-stopped? r))
   :isolation (:isolation r)
   :transient-retries (:retries r)
   :duration-s (:duration-s r)
   :transcript-path (:transcript-path r)
   :transcript-sha256 (file-sha256 (:transcript-path r))
   :cost-reported (:cost-reported r)
   :cost-estimated (:cost-estimated r)})

(defn challenge-manifest [r]
  {:name (:name r)
   :outcome (name (result-outcome r))
   :completion (some-> (:completion r) name)
   :runner-status (some-> (:status r) name)
   :private-suite-available (boolean (:has-private-suite? r))
   :private-status (some-> (:private-status r) name)
   :private-counts (:private-counts r)
   :private-reason (:private-reason r)
   :score (:challenge-score r)
   :scored (some? (:challenge-score r))
   :builds (:builds r)
   :retries (:retries r)
   :implementation-sha256 (:implementation-sha256 r)
   :challenge-tree-sha (:challenge-tree-sha r)
   :cost-reported (:cost-reported r)
   :cost-estimated (:cost-estimated r)
   :private-test (:private-test r)
   :full-spec-review (:full-spec-review r)
   :phases (mapv phase-manifest (:phase-results r))})

(def redaction-policy
  "Recorded in every manifest and bundle; docs/outcome-taxonomy.md explains it."
  {:version 2
   :scrubbed-fields ["challenges[].private-test.stdout" "challenges[].private-test.stderr"
                     "challenges[].full-spec-review.report-text"
                     "challenges[].full-spec-review.final-message"]
   :replacements
   {:secret-env (str "value of each set env var whose name matches " secret-env-name-re
                     " and is >= " min-secret-env-length " chars -> [REDACTED:env:<NAME>]")
    :credential "PEM private keys, sk-ant-/sk- API keys, GitHub, AWS key id, Slack and JWT tokens, Authorization/Bearer values, URL user:password@, and <key|secret|token|password|credential>=/: values of >= 8 chars -> [REDACTED:<kind>]"
    :private-test-assertion "private-test output only: text after clojure.test `expected:`, and `actual: (not ...)` comparisons -> [REDACTED:private-test-assertion|comparison]"
    :protected-plaintext (str "every run of 1-" max-protected-ngram " consecutive case-folded words (letters/digits, any length) "
                              "that occurs in a protected file and in no public text -> [REDACTED:protected-plaintext]. "
                              "Protected files: every file of the challenge directory outside the solver snapshot allowlist "
                              "(test-private/, test-resources/, test/, test-harness/, test-named src files, ...; .cpcache/ excluded), "
                              "streamed in full at any size. Public text: files the snapshot copies, repository paths, "
                              "and a fixed diagnostic vocabulary. file:line numbers and clojure.test count lines are not compared.")
    :private-test-data "private-test output only, on lines other than clojure.test headers/summaries, stack frames and redacted assertions: quoted strings, {...}/[...]/#{...} literals, numbers outside file:line locations, and words absent from public text, the implementation's src/ and the diagnostic vocabulary -> [REDACTED:private-test-data]"
    :protected-index-unavailable "every non-blank line of every scrubbed field, when a protected file cannot be read, only its .enc ciphertext is present, or the challenge directory is missing or holds a symlink/special file -> [REDACTED:protected-index-unavailable]"}
   :kept ["line count (redaction never drops lines)" "file:line locations" "clojure.test headers, counts and stack frames, minus protected word runs" "exception class names and messages built from public words" "all other review text without protected word runs"]
   :limitations ["values computed at run time from protected inputs are recognized only in private-test output, by the private-test data rule; in review text only words that literally occur in protected files are redacted"
                 "a run of words that also occurs, consecutively, in public text is kept"
                 "only the challenge's own non-snapshot files are protected sources; repository docs/, review/ and .amp/ and other challenges are not"
                 "credential patterns are heuristic"]})

;;; Evaluator-only private log (docs/outcome-taxonomy.md, "Evaluator log")
;;
;; The manifest and bundle keep only the scrubbed diagnostics above. The
;; complete private-test output and full-spec-review findings go to
;; <report>.private.log next to the report (../reports, outside the repository
;; and every solver snapshot), with only secret env values and credentials
;; scrubbed. The manifest pins the log by path and SHA-256; no bundle holds it.

(def evaluator-log-scrubbing ["secret-env" "credential"])

(defn evaluator-diagnostics
  "[{:section :text}] for the evaluator log: complete private-test stdout and
  stderr, FULL_SPEC_REVIEW.md and the review's final message, with secret env
  values and credential patterns scrubbed and nothing else."
  [project-root challenge-name private-result phase-results env-secrets]
  (let [{:keys [r report]} (full-spec-review-sources project-root challenge-name phase-results)]
    (into []
          (keep (fn [[section text]]
                  (when text
                    {:section section :text (first (pre-scrub text {:env-secrets env-secrets}))})))
          [["private-test stdout" (:out private-result)]
           ["private-test stderr" (:err private-result)]
           ["full-spec-review FULL_SPEC_REVIEW.md" report]
           ["full-spec-review final message" (:result-text r)]])))

(defn evaluator-log-text
  "Evaluator log content. Each section header gives its exact UTF-8 byte
  length, so section text is recoverable verbatim whatever it contains."
  [run-id results]
  (str "# rama-ai-learn evaluator-only private diagnostics\n"
       "# run-id: " run-id "\n"
       "# EVALUATOR ONLY: never give this file to a solver; never bundle or publish it.\n"
       "# Scrubbed: set secret env values and credential patterns only. "
       "Protected and private-test text is NOT redacted.\n"
       (apply str (for [{:keys [name evaluator-diagnostics]} results
                        {:keys [section text]} evaluator-diagnostics]
                    (str "\n===== " name " | " section " | "
                         (alength (.getBytes ^String text "UTF-8")) " bytes =====\n"
                         text "\n===== end " name " | " section " =====\n")))))

(defn evaluator-log-path [report-path]
  (str (str/replace (str report-path) #"\.md$" "") ".private.log"))

(defn- solver-writable-dir? [project-root dir]
  (let [impl (fs/path project-root "implementations")]
    (and (fs/exists? impl)
         (fs/starts-with? (fs/canonicalize dir) (fs/canonicalize impl)))))

(defn write-evaluator-log!
  "Create <report>.private.log (owner-only, never overwriting), sync it and
  read it back. Returns the manifest reference. Throws, leaving no file
  behind, when the log cannot be written completely."
  [report-path project-root text]
  (let [path (fs/absolutize (evaluator-log-path report-path))
        bs (.getBytes ^String text "UTF-8")
        sha (sha256-hex bs)
        fail (fn [msg & [cause]]
               (throw (ex-info (str "Evaluator log not written; no manifest or bundle emitted: " msg)
                               {:reason :evaluator-log-failed :path (str path)} cause)))]
    (when (solver-writable-dir? project-root (fs/parent path))
      (fail "the reports directory is inside a solver-writable implementation directory"))
    (try
      (fs/create-file path {:posix-file-permissions "rw-------"})
      (catch Exception e (fail "cannot create the file (it may already exist)" e)))
    (try
      (with-open [ch (java.nio.channels.FileChannel/open
                      path (into-array java.nio.file.OpenOption [java.nio.file.StandardOpenOption/WRITE
                                                                 java.nio.file.LinkOption/NOFOLLOW_LINKS]))]
        (let [buf (java.nio.ByteBuffer/wrap bs)]
          (while (.hasRemaining buf) (.write ch buf)))
        (.force ch true))
      (let [back (fs/read-all-bytes path)]
        (when-not (and (= (alength bs) (alength back)) (= sha (sha256-hex back)))
          (fail "read-back length or SHA-256 differs")))
      (catch Exception e
        (fs/delete-if-exists path)
        (if (= :evaluator-log-failed (:reason (ex-data e))) (throw e) (fail "write failed" e))))
    {:path (str (fs/file-name path))
     :path-relative-to "manifest-directory"
     :sha256 sha
     :bytes (alength bs)
     :audience "evaluator-only"
     :in-bundle false
     :scrubbed evaluator-log-scrubbing}))

(defn evaluator-log-intact?
  "True when the log a manifest references exists next to the report as a
  regular file with the referenced length and SHA-256."
  [report-path {:keys [path sha256 bytes]}]
  (let [f (some->> path (fs/path (fs/parent (fs/absolutize report-path))))]
    (boolean (and f (= path (str (fs/file-name f)))
                  (fs/regular-file? f {:nofollow-links true})
                  (let [back (fs/read-all-bytes f)]
                    (and (= bytes (alength back)) (= sha256 (sha256-hex back))))))))

(defn build-run-manifest
  "Pure: run metadata map plus results -> JSON-ready manifest map."
  [{:keys [run-id started-at finished-at args repo-sha repo-dirty? agent requested
           grader-timeout-s isolation evaluator-log]} results]
  (when-not (seq isolation)
    (throw (ex-info "A scored run manifest requires the solver isolation record" {:run-id run-id})))
  (when-not (and (string? (:path evaluator-log)) (re-matches #"[0-9a-f]{64}" (str (:sha256 evaluator-log))))
    (throw (ex-info "A run manifest requires the written evaluator log reference" {:run-id run-id})))
  {:schema-version 3
   :run-id run-id
   :started-at started-at
   :finished-at finished-at
   :args args
   :repo {:head-sha repo-sha :dirty repo-dirty?}
   :requested (merge {:agent agent} requested)
   :grader-timeout-s grader-timeout-s
   :isolation isolation
   :redaction-policy redaction-policy
   :evaluator-log evaluator-log
   :challenges (mapv challenge-manifest results)})

(defn manifest-path [report-path]
  (str (str/replace (str report-path) #"\.md$" "") ".manifest.json"))

(defn manifest-json [manifest]
  (json/generate-string manifest {:pretty true}))

(defn write-run-manifest!
  "Write the manifest next to the report. Never overwrites an existing file.
  Returns the path written, or nil when one already existed. Throws instead
  of claiming an evaluator log that is missing or differs from its reference."
  [report-path manifest]
  (let [path (manifest-path report-path)]
    (when-not (fs/exists? path)
      (when-not (evaluator-log-intact? report-path (:evaluator-log manifest))
        (throw (ex-info "Manifest not written: its evaluator log is missing or incomplete"
                        {:reason :evaluator-log-mismatch})))
      (spit path (manifest-json manifest))
      path)))

(defn bundle-path [report-path]
  (str (str/replace (str report-path) #"\.md$" "") ".bundle.tar.gz"))

(defn build-bundle
  "BUNDLE.json content: the manifest (with its scrubbed diagnostics) plus the
  SHA-256 of the manifest file's exact JSON. The markdown report is left out:
  its alignment justifications come from a scorer that reads the reference."
  [manifest created-at]
  {:schema-version 1
   :kind "rama-ai-learn-run-bundle"
   :run-id (:run-id manifest)
   :created-at created-at
   :manifest-sha256 (sha256-hex (.getBytes ^String (manifest-json manifest) "UTF-8"))
   :redaction-policy (:redaction-policy manifest)
   :manifest manifest})

(defn write-run-bundle!
  "Write <report>.bundle.tar.gz holding <run-id>/BUNDLE.json. Never
  overwrites. Returns the path written, or nil when one already existed."
  [report-path manifest]
  (let [path (bundle-path report-path)]
    (when-not (fs/exists? path)
      (let [staging (fs/create-temp-dir {:prefix "run-bundle-"})
            dir (str/replace (str (or (:run-id manifest) "run")) #"[^A-Za-z0-9._-]" "_")]
        (try
          (fs/create-dirs (fs/path staging dir))
          (spit (str (fs/path staging dir "BUNDLE.json"))
                (json/generate-string (build-bundle manifest (str (java.time.Instant/now))) {:pretty true}))
          (let [{:keys [exit err]} (p/shell {:out :string :err :string :continue true}
                                            "tar" "-czf" (str path) "-C" (str staging) dir)]
            (when-not (zero? exit)
              (fs/delete-if-exists path)
              (throw (ex-info (str "Bundle tar failed: " err) {:exit exit}))))
          path
          (finally (fs/delete-tree staging)))))))

(defn emit-run-artifacts!
  "Evaluator log, then the manifest that references it, then the bundle.
  Nothing is emitted after a step fails. Returns the paths written."
  [report-path project-root run-meta results]
  (let [log-ref (write-evaluator-log! report-path project-root
                                      (evaluator-log-text (:run-id run-meta) results))
        manifest (build-run-manifest (assoc run-meta :evaluator-log log-ref) results)
        mpath (write-run-manifest! report-path manifest)]
    {:evaluator-log (evaluator-log-path report-path)
     :manifest mpath
     :bundle (when mpath (write-run-bundle! report-path manifest))}))

;;; Alignment scoring

(defn find-impl-files
  "Find all .clj files in the agent's implementation src directory."
  [project-root challenge-name]
  (let [src-dir (fs/path project-root "implementations" challenge-name "src")]
    (when (fs/exists? src-dir)
      (vec (filter #(str/ends-with? (str %) ".clj") (fs/glob src-dir "**"))))))

(defn find-ref-files
  "Find all .clj files in the challenge's test-resources directory."
  [project-root challenge-name]
  (let [ref-dir (fs/path project-root "challenges" challenge-name "test-resources")]
    (when (fs/exists? ref-dir)
      (vec (filter #(str/ends-with? (str %) ".clj") (fs/glob ref-dir "**"))))))

(defn alignment-rubric
  "The structural-alignment section of SCORING_RUBRIC.md, up to the next `## `
  heading. The scorer needs only its anchors, not the headline or judge
  sections. Falls back to the whole text when no such section exists."
  [rubric]
  (or (some #(when (re-find #"^## [^\n]*[Ss]tructural alignment" %) %)
            (str/split rubric #"(?m)^(?=## )"))
      rubric))

(defn build-alignment-prompt
  "Build the prompt for alignment scoring."
  [project-root challenge-name]
  (let [rubric    (alignment-rubric (slurp (str (fs/path project-root "SCORING_RUBRIC.md"))))
        impl-files (find-impl-files project-root challenge-name)
        ref-files  (find-ref-files project-root challenge-name)]
    (when (seq impl-files)
      (let [impl-code (str/join "\n\n" (map #(str "--- " (fs/file-name %) " ---\n" (slurp (str %)))
                                            impl-files))
            ref-code  (when (seq ref-files)
                        (str/join "\n\n" (map #(str "--- " (fs/file-name %) " ---\n" (slurp (str %)))
                                              ref-files)))]
        (str "Score the structural alignment of an agent's implementation against a reference implementation.\n\n"
             "## Scoring Rubric\n\n" rubric "\n\n"
             "## Reference Implementation\n\n"
             (if ref-code
               (str "```clojure\n" ref-code "\n```")
               "NOT AVAILABLE — score 0 for alignment.")
             "\n\n## Agent Implementation\n\n"
             "```clojure\n" impl-code "\n```\n\n"
             "Respond with EXACTLY one line in this format:\n"
             "ALIGNMENT_SCORE:<score>\n"
             "where <score> is an integer 0-5. Score 0 if the reference implementation is not available above.\n"
             "Follow the score line with a one-sentence justification.")))))

(defn run-alignment-scoring!
  "Run alignment scoring as a separate LLM call. Returns {:alignment int} or nil."
  [project-root challenge-name model & [agent-fns]]
  (when-let [prompt (build-alignment-prompt project-root challenge-name)]
    (let [cmd (if-let [build (:prompt-cmd agent-fns)]
                (build model nil prompt)
                (cond-> ["claude" "--print"] model (into ["--model" model])))
          start (System/currentTimeMillis)
          proc (p/process cmd {:dir project-root
                              :in (if (:prompt-cmd agent-fns) "" prompt)
                              :extra-env {"CHALLENGE_KEY" nil}})
          out-fut (future (slurp (:out proc)))
          err-fut (future (slurp (:err proc)))
          done @proc
          output (if (:prompt-cmd agent-fns)
                   (or (:result (result-event (normalize-agent-output @out-fut))) "")
                   @out-fut)
          err-output @err-fut
          duration-s (quot (- (System/currentTimeMillis) start) 1000)]
      (when *verbose*
        (println (format "Alignment scoring for %s (%ds)" challenge-name duration-s)))
      (when (not= 0 (:exit done))
        (binding [*out* *err*]
          (println (format "WARN: alignment scoring failed for %s (exit %d)" challenge-name (:exit done)))
          (when (seq (str/trim err-output))
            (println (str "  stderr: " (str/trim err-output))))))
      (when-let [[_ score-str] (re-find #"ALIGNMENT_SCORE:(\d+)" output)]
        (let [score (parse-long score-str)
              ;; Grab everything after the ALIGNMENT_SCORE line as justification
              justification (some-> (re-find #"ALIGNMENT_SCORE:\d+\s*\n(.*)" output)
                                    second
                                    str/trim
                                    not-empty)]
          (when (<= 0 score 5)
            (when (and *verbose* justification)
              (println (format "  Alignment %d/5: %s" score justification)))
            (cond-> {:alignment score}
              justification (assoc :alignment-justification justification))))))))

;;; Test-coverage alignment scoring

(defn find-agent-test-files
  "Find all .clj files in the agent's implementation test directory."
  [project-root challenge-name]
  (let [test-dir (fs/path project-root "implementations" challenge-name "test")]
    (when (fs/exists? test-dir)
      (vec (filter #(str/ends-with? (str %) ".clj") (fs/glob test-dir "**"))))))

(defn find-functional-test-files
  "Find functional private test files (functional_test_support.clj) for a challenge."
  [project-root challenge-name]
  (let [priv-dir (fs/path project-root "challenges" challenge-name "test-private")]
    (when (fs/exists? priv-dir)
      (vec (filter #(str/includes? (str (fs/file-name %)) "functional")
                   (filter #(str/ends-with? (str %) ".clj") (fs/glob priv-dir "**")))))))

(defn build-test-alignment-prompt
  "Build the prompt for test-coverage alignment scoring."
  [project-root challenge-name]
  (let [agent-tests (find-agent-test-files project-root challenge-name)
        func-tests  (find-functional-test-files project-root challenge-name)]
    (when (and (seq agent-tests) (seq func-tests))
      (let [agent-code (str/join "\n\n" (map #(str "--- " (fs/file-name %) " ---\n" (slurp (str %)))
                                              agent-tests))
            func-code  (str/join "\n\n" (map #(str "--- " (fs/file-name %) " ---\n" (slurp (str %)))
                                              func-tests))]
        (str "Score how well an agent's self-written tests cover the same behaviors as a reference functional test suite.\n\n"
             "## Scoring Rubric\n\n"
             "### Efficiency penalty\n"
             "Count the number of `create-ipc` calls and `launch-module!` calls in both the agent tests and reference tests. "
             "If the agent tests create more IPC instances or launch more modules than the reference tests, reduce the score by 1 (minimum 1).\n\n"
             "If the number of tasks is not randomized for module launch, reduce the score by 1 (minimum 1).\n\n"
             "If the number of workers is not more than one, reduce the score by 1 (minimum 1).\n\n"
             "### Coverage scoring\n"
             "| Score | Anchor |\n"
             "|-------|--------|\n"
             "| 5 | Agent tests cover all behaviors in the reference tests, and may have additional coverage. |\n"
             "| 4 | Agent tests cover nearly all reference behaviors, missing only minor edge cases. |\n"
             "| 3 | Agent tests cover the core happy-path behaviors but miss several edge cases or failure scenarios. |\n"
             "| 2 | Agent tests cover some behaviors but miss significant categories (e.g., all failure cases, or an entire protocol method). |\n"
             "| 1 | Agent tests are minimal — only trivial smoke tests with little meaningful coverage. |\n\n"
             "## Reference Functional Tests\n\n"
             "```clojure\n" func-code "\n```\n\n"
             "## Agent-Written Tests\n\n"
             "```clojure\n" agent-code "\n```\n\n"
             "Respond with EXACTLY one line in this format:\n"
             "TEST_ALIGNMENT_SCORE:<score>\n"
             "where <score> is an integer 1-5.\n"
             "Follow the score line with a one-sentence justification.")))))

(defn agent-tests-use-harness?
  "Check if any agent test file references rama-challenges.harness."
  [project-root challenge-name]
  (let [test-files (find-agent-test-files project-root challenge-name)]
    (some (fn [f]
            (let [content (slurp (str f))]
              (re-find #"rama-challenges\.harness" content)))
          test-files)))

(defn run-test-alignment-scoring!
  "Run test-coverage alignment scoring. Returns {:test-alignment int} or nil."
  [project-root challenge-name model & [agent-fns]]
  (if (agent-tests-use-harness? project-root challenge-name)
    (do (when *verbose*
          (println (format "  Test alignment 1/5: agent tests use rama-challenges.harness")))
        {:test-alignment 1
         :test-alignment-justification "Agent tests use rama-challenges.harness (automatic score of 1)."})
    (when-let [prompt (build-test-alignment-prompt project-root challenge-name)]
      (let [cmd (if-let [build (:prompt-cmd agent-fns)]
                  (build model nil prompt)
                  (cond-> ["claude" "--print"] model (into ["--model" model])))
            start (System/currentTimeMillis)
            proc (p/process cmd {:dir project-root
                                :in (if (:prompt-cmd agent-fns) "" prompt)
                                :extra-env {"CHALLENGE_KEY" nil}})
            out-fut (future (slurp (:out proc)))
            err-fut (future (slurp (:err proc)))
            done @proc
            output (if (:prompt-cmd agent-fns)
                     (or (:result (result-event (normalize-agent-output @out-fut))) "")
                     @out-fut)
            err-output @err-fut
            duration-s (quot (- (System/currentTimeMillis) start) 1000)]
        (when *verbose*
          (println (format "Test alignment scoring for %s (%ds)" challenge-name duration-s)))
        (when (not= 0 (:exit done))
          (binding [*out* *err*]
            (println (format "WARN: test alignment scoring failed for %s (exit %d)" challenge-name (:exit done)))
            (when (seq (str/trim err-output))
              (println (str "  stderr: " (str/trim err-output))))))
        (when-let [[_ score-str] (re-find #"TEST_ALIGNMENT_SCORE:(\d+)" output)]
          (let [score (parse-long score-str)
                justification (some-> (re-find #"TEST_ALIGNMENT_SCORE:\d+\s*\n(.*)" output)
                                      second
                                      str/trim
                                      not-empty)]
            (when (<= 1 score 5)
              (when (and *verbose* justification)
                (println (format "  Test alignment %d/5: %s" score justification)))
              (cond-> {:test-alignment score}
                justification (assoc :test-alignment-justification justification)))))))))

;;; Private tests

(defn has-private-tests?
  "Check whether a challenge has a test-private directory."
  [project-root challenge-name]
  (fs/exists? (fs/path project-root "challenges" challenge-name "test-private")))

(defn challenge-resource-ns-dir
  "Return the conventional underscore resource dir name for a challenge."
  [challenge-name]
  (str/replace challenge-name #"-" "_"))

(defn has-hidden-setup?
  "Check whether a challenge has a hidden setup script in test-resources.
   Accepts either plaintext or encrypted form."
  [project-root challenge-name]
  (let [base (fs/path project-root "challenges" challenge-name "test-resources" (challenge-resource-ns-dir challenge-name) "reference_solution.sh")]
    (or (fs/exists? base)
        (fs/exists? (fs/path (str base ".enc"))))))

(defn has-hidden-teardown?
  "Check whether a challenge has a hidden teardown script in test-resources.
   Accepts either plaintext or encrypted form."
  [project-root challenge-name]
  (let [base (fs/path project-root "challenges" challenge-name "test-resources" (challenge-resource-ns-dir challenge-name) "teardown.sh")]
    (or (fs/exists? base)
        (fs/exists? (fs/path (str base ".enc"))))))

(defn run-hidden-script!
  "Run a hidden challenge script from test-resources. Returns a process result map."
  [project-root challenge-name script-name]
  (let [challenge-dir (str (fs/path project-root "challenges" challenge-name))
        script-path   (str (fs/path "test-resources" (challenge-resource-ns-dir challenge-name) script-name))]
    (invoke-command! ["bash" script-path] challenge-dir)))

(defn run-hidden-setup!
  [project-root challenge-name]
  (run-hidden-script! project-root challenge-name "reference_solution.sh"))

(defn run-hidden-teardown!
  [project-root challenge-name]
  (run-hidden-script! project-root challenge-name "teardown.sh"))

(def ^:dynamic *grader-timeout-s*
  "Wall-clock cap for one private-test (grader) invocation, in seconds."
  1800)

(def ^:dynamic *private-test-cmd*
  "Command that runs a challenge's private suite from the challenge dir."
  ["clojure" "-X:test-private"])

(def ^:private setsid-path (delay (some-> (fs/which "setsid") str)))

(defn kill-process-tree!
  "Kill a process started through `setsid` (its own process group), plus any
  descendants still visible through ProcessHandle. Safe to call after the
  process exited: leftover grandchildren in the group are still reaped.
  Returns the number of descendant handles signalled."
  [^Process proc group?]
  (let [kids (try (vec (iterator-seq (.iterator (.descendants (.toHandle proc)))))
                  (catch Exception _ []))]
    (when group?
      (try (p/shell {:out :string :err :string :continue true}
                    "kill" "-KILL" "--" (str "-" (.pid proc)))
           (catch Exception _ nil)))
    (doseq [^java.lang.ProcessHandle k kids] (try (.destroyForcibly k) (catch Exception _ nil)))
    (try (.destroyForcibly proc) (catch Exception _ nil))
    (count kids)))

(defn run-private-tests!
  "Run private tests for a challenge under *grader-timeout-s*. The grader runs
  in its own session (setsid) so the whole process tree is killed on timeout
  and cleaned up after a normal exit. Returns {:exit int, :out str, :err str,
  :duration-s int, :timed-out? bool, :timeout-s int}."
  [project-root challenge-name]
  (let [challenge-dir (str (fs/path project-root "challenges" challenge-name))
        group? (boolean @setsid-path)
        cmd (if group? (into [@setsid-path] *private-test-cmd*) *private-test-cmd*)
        timeout-s *grader-timeout-s*
        start (System/currentTimeMillis)
        proc (p/process cmd {:dir challenge-dir :in ""})
        out-fut (future (slurp (:out proc)))
        err-fut (future (slurp (:err proc)))
        done (deref proc (* 1000 timeout-s) ::timeout)
        timed-out? (= ::timeout done)
        _ (kill-process-tree! (:proc proc) group?)
        ;; A stray child holding the pipes open must not hang the runner.
        out (deref out-fut 10000 "")
        err (deref err-fut 10000 "")
        duration-s (quot (- (System/currentTimeMillis) start) 1000)]
    {:exit (if timed-out? 124 (:exit done))
     :out out
     :err (if timed-out? (str "Grader timeout after " timeout-s "s\n" err) err)
     :timed-out? timed-out?
     :timeout-s timeout-s
     :duration-s duration-s}))

(defn- challenge-dir?
  "A real challenge directory, identified by a README in plain or encrypted
  form (the README itself is encrypted during full-challenge encryption)."
  [d]
  (or (fs/exists? (fs/path d "README.md"))
      (fs/exists? (fs/path d "README.md.enc"))))

(defn- encrypt-other-challenges!
  "Fully encrypt every file in all challenges except the current one, so the
  challenge under test cannot read another challenge's provided code or
  reference solution (e.g. the social-graph module that fanout provides)."
  [enc-key project-root current-challenge-name]
  (let [challenge-dirs (fs/list-dir (fs/path project-root "challenges"))]
    (doseq [d challenge-dirs
            :let [name (str (fs/file-name d))]
            :when (and (fs/directory? d)
                       (not= name current-challenge-name)
                       (challenge-dir? d))]
      (encrypt-challenge-fully! enc-key name))))

(defn- decrypt-other-challenges!
  "Fully decrypt all challenges except the current one."
  [enc-key project-root current-challenge-name]
  (let [challenge-dirs (fs/list-dir (fs/path project-root "challenges"))]
    (doseq [d challenge-dirs
            :let [name (str (fs/file-name d))]
            :when (and (fs/directory? d)
                       (not= name current-challenge-name)
                       (challenge-dir? d))]
      (decrypt-challenge-fully! enc-key name))))

;;; Phase orchestration

(def validation-retry-cap
  "Max number of times a validation phase (2, 4, 6) can FAIL and retry the prior phase."
  3)

(defn impl-root-path
  [project-root challenge-name]
  (str (fs/path project-root "implementations" challenge-name)))

(defn save-attempt!
  "Snapshot the current implementation as attempts/attemptN.clj. Returns invoke-command! result."
  [project-root challenge-name]
  (invoke-command! ["bash" "scripts/save-attempt.sh" challenge-name] project-root))

(defn append-reasoning-sentinel!
  "Append a phase sentinel to the challenge's REASONING.md. Each phase
  invocation is instructed to append its reasoning below the sentinel, so
  entries can be attributed to the phase/attempt that wrote them. On
  multi-subsystem runs the sentinel carries the subsystem slug in brackets:
  `=== PHASE 3 [some-subsystem] attempt 2 — <ts> ===`."
  ([project-root challenge-name phase-id attempt]
   (append-reasoning-sentinel! project-root challenge-name phase-id attempt nil))
  ([project-root challenge-name phase-id attempt subsystem]
   (append-reasoning-sentinel! project-root challenge-name phase-id attempt subsystem 0))
  ([project-root challenge-name phase-id attempt subsystem retry]
   (let [impl-dir (fs/path project-root "implementations" challenge-name)
         path     (fs/path impl-dir "REASONING.md")
         ts       (.format (java.time.LocalDateTime/now)
                           (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss"))
         phase-label (str/upper-case (phase-id-str phase-id))
         sub-label   (if subsystem (str " [" subsystem "]") "")
         ;; A transient-error re-invocation is a fresh session doing the SAME
         ;; attempt over again, so it gets its own sentinel — otherwise the
         ;; retried session finds the previous session's reasoning sitting under
         ;; a sentinel that claims to be its own, and burns turns working out
         ;; whether the phase already ran.
         retry-label (if (pos? retry) (format " retry %d" retry) "")]
     (fs/create-dirs impl-dir)
     (spit (str path)
           (format "\n=== PHASE %s%s attempt %d%s — %s ===\n\n"
                   phase-label sub-label attempt retry-label ts)
           :append true))))

(def ^:private transient-error-re
  ;; Server-side / infra errors worth retrying. Applied ONLY to stderr and to
  ;; the error-bearing fields of the agent's structured events (see
  ;; `agent-error-text`) — never to raw stdout, which carries a `rate_limit_info`
  ;; block on every single Claude Code run. Numeric status codes require a
  ;; surrounding HTTP context for the same reason: a bare `500` in agent prose is
  ;; far more often a timeout argument or a row count than a status code.
  ;; Deliberately excludes the output-token-maximum error (a config problem,
  ;; not transient) — that one contains "api error" but retrying it just
  ;; re-hits the same cap.
  #"(?i)overloaded|overloaded_error|internal server error|service unavailable|bad gateway|gateway timeout|too many requests|error_during_execution|connection reset|econnreset|socket hang ?up|rate.?limit\w*\s+(?:exceeded|error|reached|hit)|(?:status|code|http|error)\W{0,12}(?:429|50[0234]|529)\b|\b(?:429|50[0234]|529)\s+(?:error|status)")

(defn agent-error-text
  "The subset of an agent invocation's output worth scanning for transient
  server errors: stderr in full, plus the error-bearing fields of structured
  stdout events. Raw stdout is deliberately NOT included — every Claude Code run
  emits a `rate_limit_info` block and agent prose routinely contains bare
  numbers like 500, so a regex over the whole stream matches on every run and
  turns the retry loop into an unconditional 4x re-run of every phase.
  Non-JSON stdout lines ARE kept: a CLI that dies before it can emit structured
  output prints plainly."
  [out err]
  (let [from-stdout
        (keep (fn [line]
                (let [parsed (try (json/parse-string line true)
                                  (catch Exception _ ::unparsed))]
                  (cond
                    (= ::unparsed parsed) line
                    (contains? #{"error" "turn.failed"} (:type parsed))
                    (json/generate-string (or (:error parsed) (:message parsed)))
                    (and (= "message_end" (:type parsed))
                         (= "error" (get-in parsed [:message :stopReason])))
                    (get-in parsed [:message :errorMessage])
                    ;; `is_error` here is top-level (the run's own result event).
                    ;; Tool-level `is_error` lives nested under :message :content
                    ;; and is invisible to this check by design — a failed Bash
                    ;; call is a phase outcome, not an infrastructure failure.
                    (and (map? parsed)
                         (or (:is_error parsed)
                             (and (= "result" (:type parsed))
                                  (not= "success" (:subtype parsed)))))
                    (str/join " " (filter string?
                                          [(:subtype parsed) (:result parsed)
                                           (:error parsed) (:message parsed)]))
                    :else nil)))
              (remove str/blank? (str/split-lines (or out ""))))]
    (str/join "\n" (cons (or err "") from-stdout))))

(defn transient-server-error?
  "True when an agent invocation shows a retryable server-side error (overload,
  5xx, rate limit, dropped connection) rather than a legitimate phase failure.
  Takes the invocation's stdout and stderr separately so stdout can be narrowed
  to its error fields before matching."
  [out err]
  (boolean (re-find transient-error-re (agent-error-text out err))))

(defn run-phase!
  "Invoke one phase of a challenge. Clamps the per-call timeout to whatever's
  left in the overall run budget. A transient server-side error re-runs the
  invocation (fresh session) up to *phase-retry-cap* times with backoff before
  the result is returned. `subsystem` is nil on single-subsystem runs;
  on multi-subsystem runs it is the slug of the subsystem being built and is
  threaded into the /challenge-phase invocation, the reasoning sentinel, and
  the transcript filename. Returns a result map with everything the caller
  needs to decide next steps and accumulate per-phase telemetry."
  [agent-fns challenge-name phase-id attempt subsystem
   project-root agent-name model reasoning run-start-time run-start-millis]
  (let [cmd (solver-command
             ((:phase-cmd agent-fns) challenge-name phase-id project-root model reasoning subsystem)
             project-root challenge-name agent-name)
        remaining (long (time-remaining-s run-start-millis))
        effective-timeout (min *outer-timeout-s* remaining)
        phase-label (str (phase-id-str phase-id)
                         (when subsystem (str " [" subsystem "]")))
        _ (when *verbose*
            (println (format "  Phase %s (attempt %d) starting (budget remaining: %ds, this-call cap: %ds)..."
                             phase-label attempt remaining effective-timeout)))
        ;; Re-run the invocation on a transient server-side error, with backoff,
        ;; until it succeeds, the retry cap is hit, or the budget runs out.
        ;; Every invocation gets its own sentinel and its own saved transcript:
        ;; a discarded retry still consumed budget and still wrote to the
        ;; implementation directory, so throwing its transcript away leaves the
        ;; run's wall clock unexplainable after the fact.
        {:keys [exit out err duration-s timed-out? retries transcript-path]}
        (loop [tries 0]
          (let [remaining (long (time-remaining-s run-start-millis))
                eff (min *outer-timeout-s* (max 1 remaining))
                _ (append-reasoning-sentinel! project-root challenge-name
                                              phase-id attempt subsystem tries)
                r (binding [*outer-timeout-s* eff]
                    (invoke-command! cmd project-root))
                transcript (str (json/generate-string
                                  {:type "run_metadata" :timestamp (:started-at r)
                                   :isolation (isolation-mode)
                                   :model model :effort reasoning :agent agent-name}) "\n"
                                (:out r) "\n"
                                (json/generate-string
                                  {:type "run_metadata" :timestamp (:finished-at r)
                                   :duration_s (:duration-s r) :exit (:exit r)
                                   :stderr (:err r)
                                   :timed_out (boolean (:timed-out? r))}) "\n")
                path (save-transcript! project-root agent-name model reasoning
                                       challenge-name transcript phase-id attempt
                                       run-start-time subsystem tries)
                r (assoc r :retries tries :transcript-path path)]
            (if (and (transient-server-error? (:out r) (:err r))
                     (not (:timed-out? r))
                     (< tries *phase-retry-cap*)
                     (> (time-remaining-s run-start-millis) 0))
              (let [backoff (min 60 (* 15 (inc tries)))]
                (when *verbose*
                  (println (format "  Phase %s: transient server error — retry %d/%d in %ds"
                                   phase-label (inc tries) *phase-retry-cap* backoff)))
                (Thread/sleep (* backoff 1000))
                (recur (inc tries)))
              r)))
        canonical (normalize-agent-output out)
        summary (result-event canonical)
        combined (str out "\n" err)
        verdict (parse-phase-verdict combined)
        token-usage (parse-token-usage canonical)
        cost-reported (:total_cost_usd summary)
        cost-estimated (when (contains? #{"claude" "codex"} agent-name)
                         (compute-cost token-usage (model->pricing model)))
        cost (or cost-reported cost-estimated)
        final-exit (if (and (zero? exit) (:is_error summary)) 1 exit)
        error-text (agent-error-text out err)
        provider-limit? (boolean (and (not= 0 final-exit)
                                      (not timed-out?)
                                      (or (re-find transient-error-re error-text)
                                          (re-find quota-error-re error-text))))
        user-stopped? (boolean (and (not timed-out?) (contains? #{130 143} exit)))
        tool-uses (parse-tool-uses canonical)
        skills-used (parse-skills-used canonical)
        skill-refs-used (parse-skill-refs-used canonical)]
    (when *verbose*
      (println (format "  Phase %s (attempt %d) finished: exit=%d duration=%ds retries=%d verdict=%s"
                       phase-label attempt exit duration-s retries
                       (if verdict (name verdict) "n/a"))))
    {:phase-id phase-id
     :attempt attempt
     :retries retries
     :subsystem subsystem
     :exit final-exit
     :timed-out? (boolean timed-out?)
     :provider-limit? provider-limit?
     :user-stopped? user-stopped?
     :duration-s duration-s
     :verdict verdict
     :isolation (isolation-mode)
     ;; Final assistant message; the manifest keeps it for full-spec-review.
     :result-text (let [t (:result summary)] (when (string? t) t))
     :transcript-path transcript-path
     :token-usage token-usage
     :cost cost
     :cost-reported cost-reported
     :cost-estimated cost-estimated
     :tool-uses tool-uses
     :skills-used skills-used
     :skill-refs-used skill-refs-used}))

(defn aggregate-phase-results
  "Sum per-phase telemetry across all phase invocations."
  [phase-results]
  (let [total-tokens   (token-totals (map :token-usage phase-results))
        total-cost     (when (some :cost phase-results) (reduce + 0 (keep :cost phase-results)))
        total-duration (reduce + 0 (map #(or (:duration-s %) 0) phase-results))
        total-tool-uses (reduce + 0 (map #(or (:tool-uses %) 0) phase-results))
        all-skills     (vec (sort (into #{} (mapcat :skills-used phase-results))))
        all-skill-refs (vec (sort (into #{} (mapcat :skill-refs-used phase-results))))]
    {:token-usage     total-tokens
     :cost            total-cost
     :cost-reported   (when (some :cost-reported phase-results)
                        (reduce + 0 (keep :cost-reported phase-results)))
     :cost-estimated  (when (some :cost-estimated phase-results)
                        (reduce + 0 (keep :cost-estimated phase-results)))
     :duration-s      total-duration
     :tool-uses       total-tool-uses
     :skills-used     all-skills
     :skill-refs-used all-skill-refs}))

(defn phase3-iterations
  "Total build invocations (implementation attempts) across a run's phase
  results, summed across all subsystems. Minimum 1."
  [results]
  (max 1 (count (filter #(= :build (:phase-id %)) results))))

(defn read-decomposition
  "Read implementations/<challenge>/DECOMPOSITION.json written by the
  decompose stage. The required shape is a JSON array of subsystem objects in
  dependency order, each with a non-empty \"name\" and \"scope\":
  [{\"name\": \"graph\", \"scope\": \"...\"}, ...]; the runner consumes the
  \"name\" order (phase agents read the \"scope\" entries; per-subproblem
  difficulty is decided later by phase 2, not here). Returns a non-empty vector
  of {:name <trimmed string>} in file order, or nil when the file is missing,
  unparseable, empty, or malformed — the caller then treats the module as a
  single subsystem. Never throws."
  [project-root challenge-name]
  (let [path (fs/path project-root "implementations" challenge-name "DECOMPOSITION.json")
        warn! (fn [msg]
                (binding [*out* *err*]
                  (println (format "WARN: %s — treating %s as a single subsystem."
                                   msg challenge-name))))
        entry->map (fn [entry]
                     (when (and (map? entry)
                                (string? (:scope entry))
                                (seq (str/trim (:scope entry))))
                       (let [n (:name entry)]
                         (when (string? n)
                           (let [trimmed (str/trim n)]
                             (when (seq trimmed) {:name trimmed}))))))]
    (if-not (fs/exists? path)
      (do (warn! (str "DECOMPOSITION.json missing at " path)) nil)
      ;; cheshire parses top-level JSON arrays lazily — force realization
      ;; inside the try so malformed JSON is caught here, not downstream.
      (let [parsed (try (let [p (json/parse-string (slurp (str path)) true)]
                          (if (seqable? p) (doall p) p))
                        (catch Exception _ ::unparseable))]
        (cond
          (= ::unparseable parsed)
          (do (warn! "DECOMPOSITION.json is unparseable") nil)

          (not (sequential? parsed))
          (do (warn! "DECOMPOSITION.json is not an array of subsystem entries") nil)

          (empty? parsed)
          (do (warn! "DECOMPOSITION.json is empty") nil)

          :else
          (let [entries (mapv entry->map parsed)]
            (cond
              (some nil? entries)
              (do (warn! "DECOMPOSITION.json entries must be objects with non-empty \"name\" and \"scope\" strings") nil)

              (not (apply distinct? (map :name entries)))
              (do (warn! "DECOMPOSITION.json subsystem names must be distinct") nil)

              :else entries)))))))

(defn run-subsystem-phases!
  "Drive one subsystem: plan → plan-validate → build. `subsystem` is nil on
  single-subsystem runs. Returns {:status :pass|:fail|:timeout,
  :phase-results [...], :failure-reason str?, :transcript-path str}.

  Pipeline:
  - Phase 1 (plan) and Phase 2 (plan-validation) run on the SLOW tier.
  - Phase 2 pass|minor-fail → build. major-fail → back to Phase 1 (capped by
    validation-retry-cap consecutive major-fails).
  - build (one session: implement → validate → test → iterate to green) runs
    on the FAST tier and drives the subsystem's status. It subsumes the old
    separate implement/validate/test/finish phases.

  An overall wall-clock budget (*overall-timeout-s*) caps the entire run.
  Checked at every loop iteration; per-call subprocess timeouts are clamped
  to the remaining budget."
  [agent-fns challenge-name subsystem project-root agent-name fast-tier slow-tier
   run-start-time run-start-millis]
  (loop [phase-id 1
         attempts {1 1, 2 1, :build 1}
         plan-major-fails 0
         results []]
    (cond
      ;; Overall budget exhausted — abort.
      (<= (time-remaining-s run-start-millis) 0)
      {:status :timeout
       :phase-results results
       :failure-reason (format "Overall challenge time budget (%ds) exceeded before phase %s."
                               *overall-timeout-s* (phase-id-str phase-id))
       :transcript-path (:transcript-path (last results))}

      :else
      (let [attempt   (get attempts phase-id 1)
            [pm pr]   (if (= :build phase-id) fast-tier slow-tier)
            r         (run-phase! agent-fns challenge-name phase-id attempt subsystem
                                  project-root agent-name pm pr
                                  run-start-time run-start-millis)
            attempts' (assoc attempts phase-id (inc attempt))
            results'  (conj results r)]
        (cond
          (:timed-out? r)
          {:status :timeout
           :phase-results results'
           :failure-reason (format "Phase %s (attempt %d) timed out." (phase-id-str phase-id) attempt)
           :transcript-path (:transcript-path r)}

          (not= 0 (:exit r))
          {:status :fail
           :phase-results results'
           :failure-reason (format "Phase %s (attempt %d) exited %d." (phase-id-str phase-id) attempt (:exit r))
           :transcript-path (:transcript-path r)}

          ;; Phase 1 (plan): no verdict, advance to plan-validation.
          (= phase-id 1)
          (recur 2 attempts' plan-major-fails results')

          ;; Phase 2 (plan-validation): pass|minor-fail → build; major-fail →
          ;; back to Phase 1 (capped).
          (= phase-id 2)
          (cond
            (or (= :pass (:verdict r)) (= :minor-fail (:verdict r)))
            (recur :build attempts' plan-major-fails results')

            (= :major-fail (:verdict r))
            (if (< plan-major-fails validation-retry-cap)
              (do (save-attempt! project-root challenge-name)
                  (recur 1 attempts' (inc plan-major-fails) results'))
              {:status :fail
               :phase-results results'
               :failure-reason (format "Phase 2 failed validation %d times consecutively."
                                       (inc plan-major-fails))
               :transcript-path (:transcript-path r)})

            :else
            {:status :fail
             :phase-results results'
             :failure-reason "Phase 2 did not emit PHASE_VALIDATION verdict."
             :transcript-path (:transcript-path r)})

          ;; build: implement + validate + test + iterate to green in one
          ;; session. Binary verdict, terminal.
          (= phase-id :build)
          (cond
            (= :pass (:verdict r))
            {:status :pass
             :phase-results results'
             :transcript-path (:transcript-path r)}

            (= :fail (:verdict r))
            {:status :fail
             :phase-results results'
             :failure-reason "Build emitted FAIL — agent could not get tests passing."
             :transcript-path (:transcript-path r)}

            :else
            {:status :fail
             :phase-results results'
             :failure-reason (format "Build did not emit a valid PHASE_VALIDATION verdict (got %s)."
                                     (:verdict r))
             :transcript-path (:transcript-path r)})

          :else
          {:status :fail
           :phase-results results'
           :failure-reason (format "Unexpected phase %s in subsystem loop." (phase-id-str phase-id))
           :transcript-path (:transcript-path r)})))))

(defn run-full-spec-review!
  "Run the full-spec-review stage: an adversarial whole-spec review of the
  ENTIRE module + test suite against the ENTIRE original spec, followed by
  whatever fixing that review demands. Always runs, even on single-subsystem
  runs.

  ONE invocation. The session reviews, fixes what it found, re-reviews its own
  fixes, and repeats until it is clean — the loop lives inside the session, not
  here. A runner-side review→fix→re-review loop spent a full re-read of the
  module and test suite on every round (fresh context each time) and was capped
  at a fixed number of rounds, so it both cost more and gave up while still
  making progress. The only bound now is the run's time budget.

  Returns {:status :pass|:fail|:timeout, :phase-results [...],
  :failure-reason str?, :transcript-path str}."
  [agent-fns challenge-name project-root agent-name model reasoning
   run-start-time run-start-millis]
  (if (<= (time-remaining-s run-start-millis) 0)
    {:status :timeout
     :phase-results []
     :failure-reason (format "Overall challenge time budget (%ds) exceeded before full-spec-review."
                             *overall-timeout-s*)
     :transcript-path nil}
    (let [r (run-phase! agent-fns challenge-name :full-spec-review 1 nil
                        project-root agent-name model reasoning
                        run-start-time run-start-millis)
          results [r]]
      (cond
        (:timed-out? r)
        {:status :timeout
         :phase-results results
         :failure-reason "Phase full-spec-review timed out."
         :transcript-path (:transcript-path r)}

        (not= 0 (:exit r))
        {:status :fail
         :phase-results results
         :failure-reason (format "Phase full-spec-review exited %d." (:exit r))
         :transcript-path (:transcript-path r)}

        (= :pass (:verdict r))
        {:status :pass
         :phase-results results
         :transcript-path (:transcript-path r)}

        (= :fail (:verdict r))
        {:status :fail
         :phase-results results
         :failure-reason "Full-spec review ended with unresolved items."
         :transcript-path (:transcript-path r)}

        :else
        {:status :fail
         :phase-results results
         :failure-reason (format "Full-spec review did not emit a valid PHASE_VALIDATION verdict (got %s)."
                                 (:verdict r))
         :transcript-path (:transcript-path r)}))))

(defn phase-loop!
  "Drive the full challenge pipeline. Returns a map:
  {:status :pass | :fail | :timeout
   :iterations int            ;; total phase-3 invocations across all subsystems
   :phase-results [...]       ;; one per agent invocation (all stages included)
   :test-output str           ;; final test output (when known)
   :failure-reason str?       ;; populated on :fail/:timeout
   :transcript-path str       ;; path to the most recent agent transcript}

  Pipeline:
  - Phase 0 (implicit spec)
  - decompose stage: the agent writes DECOMPOSITION.json; the runner reads it
    to determine subsystems. Missing/unparseable/empty file → the whole module
    is one subsystem (warned, never fatal).
  - For each subsystem, run planning (phase 1), plan-validation (phase 2), and
    build in DECOMPOSITION.json order. A major validation failure retries
    planning up to the configured cap; see run-subsystem-phases!. On
    multi-subsystem runs (n > 1), each invocation carries the subsystem slug
    as a third /challenge-phase argument; when n == 1 no slug is passed.
    Any subsystem build failure fails the whole run, naming the subsystem.
  - full-spec-review stage (ALWAYS, even when n == 1): see
    run-full-spec-review!.

  Overall run pass = every subsystem build passes AND full-spec-review
  passes.

  An overall wall-clock budget (*overall-timeout-s*) caps the entire run.
  Checked before every stage; per-call subprocess timeouts are clamped to the
  remaining budget."
  [agent-fns challenge-name project-root agent-name model reasoning
   run-start-time run-start-millis]
  ;; Phase 0, decompose and full-spec-review run on the slow tier — the
  ;; highest-leverage reasoning stages (requirements interpretation, structure,
  ;; adversarial safety). Phase 0 was previously on the fast tier on the grounds
  ;; that it is enumeration rather than design; that is wrong. Deciding how much
  ;; an explicit latitude clause permits is interpretation, and IMPLICIT_SPEC.md
  ;; binds every later phase while being exempt from their validation checks, so
  ;; an error there is unrecoverable downstream.
  ;; Subproblem cycles run planning + validation on the slow tier, then build
  ;; on the fast tier (see run-subsystem-phases!).
  (let [fast-tier (tier-config :fast)
        slow-tier (tier-config :slow)
        [frame-model frame-reasoning] slow-tier
        run-stage! (fn run-stage!
                     ([phase-id] (run-stage! phase-id :slow))
                     ([phase-id tier]
                      (let [[m r] (tier-config tier)]
                        (run-phase! agent-fns challenge-name phase-id 1 nil
                                    project-root agent-name m r
                                    run-start-time run-start-millis))))
        ;; nil when the stage invocation completed (exit 0, no timeout).
        stage-failure (fn [r results]
                        (cond
                          (:timed-out? r)
                          {:status :timeout
                           :iterations (phase3-iterations results)
                           :phase-results results
                           :failure-reason (format "Phase %s (attempt %d) timed out."
                                                   (phase-id-str (:phase-id r)) (:attempt r))
                           :transcript-path (:transcript-path r)}

                          (not= 0 (:exit r))
                          {:status :fail
                           :iterations (phase3-iterations results)
                           :phase-results results
                           :failure-reason (format "Phase %s (attempt %d) exited %d."
                                                   (phase-id-str (:phase-id r)) (:attempt r) (:exit r))
                           :transcript-path (:transcript-path r)}))
        budget-exceeded (fn [results stage-label]
                          (when (<= (time-remaining-s run-start-millis) 0)
                            {:status :timeout
                             :iterations (phase3-iterations results)
                             :phase-results results
                             :failure-reason (format "Overall challenge time budget (%ds) exceeded before %s."
                                                     *overall-timeout-s* stage-label)
                             :transcript-path (:transcript-path (last results))}))]
    (or
     ;; Stage: phase 0 (implicit spec).
     (budget-exceeded [] "phase 0")
     (let [r0 (run-stage! 0)
           results [r0]]
       (or
        (stage-failure r0 results)
        ;; Stage: decompose.
        (budget-exceeded results "stage decompose")
        (let [rd (run-stage! :decompose)
              results (conj results rd)]
          (or
           (stage-failure rd results)
           ;; Determine subsystems from DECOMPOSITION.json. A missing/invalid
           ;; file → a single whole-module cycle (slug nil). The current runner
           ;; does not use a difficulty classification.
           (let [subsystems (read-decomposition project-root challenge-name)
                 multi? (> (count subsystems) 1)
                 slugs (if multi? (mapv :name subsystems) [nil])]
             (when (and *verbose* multi?)
               (println (format "  Decomposition: %d subsystems: %s"
                                (count slugs) (str/join ", " slugs))))
             ;; Stage: plan → plan-validation → build per subsystem.
             (loop [remaining slugs
                    results results]
               (if (seq remaining)
                 (let [slug (first remaining)
                       _ (when (and *verbose* slug)
                           (println (format "  → subsystem %s" slug)))
                       sub-result (run-subsystem-phases!
                                   agent-fns challenge-name slug project-root
                                   agent-name fast-tier slow-tier
                                   run-start-time run-start-millis)
                       results' (into results (:phase-results sub-result))]
                   (if (= :pass (:status sub-result))
                     (recur (rest remaining) results')
                     ;; Any subsystem failure/timeout fails the whole run,
                     ;; naming the subsystem on multi-subsystem runs.
                     {:status (:status sub-result)
                      :iterations (phase3-iterations results')
                      :phase-results results'
                      :failure-reason (if slug
                                        (format "[subsystem %s] %s" slug (:failure-reason sub-result))
                                        (:failure-reason sub-result))
                      :transcript-path (or (:transcript-path sub-result)
                                           (:transcript-path (last results')))}))
                 ;; Stage: full-spec review (always runs).
                 (or
                  (budget-exceeded results "stage full-spec-review")
                  (let [review-result (run-full-spec-review!
                                       agent-fns challenge-name project-root
                                       agent-name frame-model frame-reasoning
                                       run-start-time run-start-millis)
                        results' (into results (:phase-results review-result))]
                    (cond-> {:status (:status review-result)
                             :iterations (phase3-iterations results')
                             :phase-results results'
                             :transcript-path (or (:transcript-path review-result)
                                                  (:transcript-path (last results')))}
                      (:failure-reason review-result)
                      (assoc :failure-reason (:failure-reason review-result)))))))))))))))

(defn run-challenge
  "Run a single challenge through the agent. Returns a result map:
  {:name str, :status :pass/:fail, :iterations int, :duration-s int,
   :transcript-path str, :error str?, :private-status :pass/:fail/:skip}"
  [challenge agent-name agent-fns project-root model reasoning enc-key]
  (let [challenge-name (:name challenge)]
    (try
      ;; Initialize tooling before spending tokens or clearing prior artifacts.
      (let [{:keys [exit out err]}
            (invoke-command! ["bash" "scripts/import-kondo-configs.sh" challenge-name]
                             project-root)]
        (when (not= 0 exit)
          (throw (ex-info (str "clj-kondo setup failed for " challenge-name "\n" out err)
                          {:challenge challenge-name :exit exit}))))
      (clean-implementation-dir! project-root challenge-name)

      (let [n-decrypted (decrypt-challenge! enc-key challenge-name)]
        (when (and *verbose* (pos? n-decrypted))
          (println (format "Decrypted %d file(s) for %s" n-decrypted challenge-name))))

      (when (has-hidden-setup? project-root challenge-name)
        (when *verbose*
          (println (format "Preparing hidden setup for %s..." challenge-name)))
        (let [{:keys [exit out err]} (run-hidden-setup! project-root challenge-name)]
          (when (not= 0 exit)
            (throw (ex-info (str "Hidden setup failed for " challenge-name "\n" out err)
                            {:challenge challenge-name :exit exit :out out :err err})))))

      ;; Encrypt private files for this challenge and all other challenges
      (let [n-encrypted (encrypt-challenge! enc-key challenge-name)]
        (when (and *verbose* (pos? n-encrypted))
          (println (format "Encrypted %d file(s) for %s" n-encrypted challenge-name))))
      (encrypt-other-challenges! enc-key project-root challenge-name)

      (if *verbose*
        (println (format "\n=== Running: %s ===" challenge-name))
        (print (format "Running: %-35s" challenge-name)))
      (flush)

      (let [run-start-time (java.time.LocalDateTime/now)
            run-start-millis (System/currentTimeMillis)
            phase-result (phase-loop! agent-fns challenge-name project-root
                                      agent-name model reasoning
                                      run-start-time run-start-millis)
            _ (let [n-decrypted (decrypt-challenge! enc-key challenge-name)]
                (when (and *verbose* (pos? n-decrypted))
                  (println (format "Restored %d file(s) for %s" n-decrypted challenge-name))))
            agg (aggregate-phase-results (:phase-results phase-result))
            status (:status phase-result)
            iterations (:iterations phase-result)
            duration-s (:duration-s agg)
            token-usage (:token-usage agg)
            cost (:cost agg)
            tool-uses (:tool-uses agg)
            skills-used (:skills-used agg)
            skill-refs-used (:skill-refs-used agg)
            transcript-path (:transcript-path phase-result)
            out (or (:test-output phase-result) "")
            err (or (:failure-reason phase-result) "")
            exit (case status :pass 0 :timeout 124 1)]

        ;; Decrypt private files now that agent is done
        (decrypt-challenge! enc-key challenge-name)
        (decrypt-other-challenges! enc-key project-root challenge-name)
        (try
          (let [private-result
                (when (and (has-private-tests? project-root challenge-name)
                           (not= :timeout status))
                  (when *verbose*
                    (println (format "Running private tests for %s..." challenge-name)))
                  (let [result (run-private-tests! project-root challenge-name)]
                    (when (and *verbose* (not= 0 (:exit result)))
                      (binding [*out* *err*]
                        (when (seq (str/trim (:out result)))
                          (println (str/trim (:out result))))
                        (when (seq (str/trim (:err result)))
                          (println (str/trim (:err result))))))
                    result))

                ;; Sentinels (Ran 0 tests, no summary, grader timeout) are
                ;; :unavailable, never :fail. See docs/outcome-taxonomy.md.
                has-suite? (has-private-tests? project-root challenge-name)
                private-verdict (classify-private-result has-suite? private-result)
                private-status (:private-status private-verdict)
                ;; Complete, scrubbed diagnostics for the manifest and bundle.
                ;; Built here, while the protected files are plaintext.
                scrub-ctx (scrub-context project-root challenge-name (System/getenv))
                private-test (private-test-diagnostic private-result private-verdict scrub-ctx)
                full-spec-review (full-spec-review-diagnostic project-root challenge-name
                                                              (:phase-results phase-result) scrub-ctx)
                ;; Complete text for the evaluator-only log; secrets scrubbed only.
                evaluator-diagnostics (evaluator-diagnostics project-root challenge-name private-result
                                                             (:phase-results phase-result)
                                                             (:env-secrets scrub-ctx))
                has-implementation? (boolean (seq (find-impl-files project-root challenge-name)))
                completion (classify-completion phase-result)
                outcome (classify-outcome {:completion completion
                                           :private-status private-status
                                           :has-implementation? has-implementation?})
                phase-results (:phase-results phase-result)
                builds (count (filter #(= :build (:phase-id %)) phase-results))
                retries (count-semantic-retries phase-results)

                ;; Alignment is a separate dimension from correctness: score it
                ;; whenever there is a finished or correct implementation.
                scoring
                (when (or (= :pass status) (= :private-pass outcome))
                  (let [impl-scores (do (when *verbose*
                                          (println (format "Scoring alignment for %s..." challenge-name)))
                                        (run-alignment-scoring! project-root challenge-name model agent-fns))
                        test-scores (do (when *verbose*
                                          (println (format "Scoring test alignment for %s..." challenge-name)))
                                        (run-test-alignment-scoring! project-root challenge-name model agent-fns))
                        scores (merge impl-scores test-scores)]
                    (when (seq scores)
                      {:scores scores :composite (:alignment scores)})))]


              (let [score (compute-challenge-score outcome retries)
                    result (merge {:name challenge-name
                                   :outcome outcome
                                   :completion completion
                                   :status status
                                   :private-status private-status
                                   :private-counts (:private-counts private-verdict)
                                   :private-reason (:private-reason private-verdict)
                                   :private-test private-test
                                   :full-spec-review full-spec-review
                                   :evaluator-diagnostics evaluator-diagnostics
                                   :has-private-suite? has-suite?
                                   :has-implementation? has-implementation?
                                   :implementation-sha256 (tree-sha256 (fs/path project-root "implementations" challenge-name))
                                   :challenge-tree-sha (git-out project-root "rev-parse" (str "HEAD:challenges/" challenge-name))
                                   :challenge-score score
                                   :builds builds
                                   :retries retries
                                   :iterations iterations
                                   :duration-s duration-s
                                   :cost cost
                                   :cost-reported (:cost-reported agg)
                                   :cost-estimated (:cost-estimated agg)
                                   :tool-uses tool-uses
                                   :skills-used skills-used
                                   :skill-refs-used skill-refs-used
                                   :scoring scoring
                                   :transcript-path transcript-path
                                   :phase-results phase-results
                                   :error (when (#{:fail :timeout} status)
                                            (let [err-str (str/trim err)]
                                              (when (seq err-str)
                                                err-str)))}
                                  token-usage)]
                (println (challenge-headline result))
                (when (#{:fail :timeout} status)
                  (binding [*out* *err*]
                    (println (str "--- " (if (= status :timeout) "TIMEOUT" "FAILED") ": " challenge-name " ---"))
                    (when (seq (str/trim out))
                      (println "stdout:")
                      (println (str/trim out)))
                    (when (seq (str/trim err))
                      (println "stderr:")
                      (println (str/trim err)))
                    (println (str "exit code: " exit))
                    (println "---")))
                result))
          (finally
            (when (has-hidden-teardown? project-root challenge-name)
              (when *verbose*
                (println (format "Running hidden teardown for %s..." challenge-name)))
              (let [{:keys [exit out err]} (run-hidden-teardown! project-root challenge-name)]
                (when (not= 0 exit)
                  (binding [*out* *err*]
                    (println (format "WARN: hidden teardown failed for %s" challenge-name))
                    (when (seq (str/trim out))
                      (println (str/trim out)))
                    (when (seq (str/trim err))
                      (println (str/trim err))))))))))

      (catch Exception e
        (try
          (decrypt-challenge! enc-key challenge-name)
          (catch Exception _))
        (try (decrypt-other-challenges! enc-key project-root challenge-name) (catch Exception _))
        (try
          (when (has-hidden-teardown? project-root challenge-name)
            (run-hidden-teardown! project-root challenge-name))
          (catch Exception _))
        (println (format "INFRA-ERROR (not scored): %s" (.getMessage e)))
        {:name challenge-name
         :outcome :infra-error
         :completion :infra-error
         :status :fail
         :private-status :not-run
         :private-reason "runner error before private suite"
         :challenge-score nil
         :builds 0
         :retries 0
         :iterations 0
         :duration-s 0
         :input-tokens 0
         :output-tokens 0
         :cache-creation-tokens 0
         :cache-read-tokens 0
         :cost nil
         :tool-uses 0
         :skills-used []
         :skill-refs-used []
         :scoring nil
         :error (.getMessage e)}))))

(defn run-challenges
  "Run all challenges sequentially, returning a vector of result maps."
  [challenges agent-key agent-name project-root model reasoning enc-key]
  (let [agent-fns (get agents agent-key)]
    (when-not agent-fns
      (binding [*out* *err*]
        (println (str "Error: unknown agent '" (name agent-key) "'. Use claude, codex, opencode, or pi.")))
      (System/exit 1))
    (println)
    (mapv #(run-challenge % agent-name agent-fns project-root model reasoning enc-key) challenges)))

;;; Formatting

(defn format-duration
  "Format elapsed seconds as a human-readable duration string.
  Returns `Xm Ys` when >= 60s, or `Xs` when < 60s."
  [seconds]
  (let [s (Math/round (double seconds))]
    (if (< s 60)
      (str s "s")
      (let [m (quot s 60)
            r (rem s 60)]
        (str m "m " r "s")))))

;;; Reporting

(defn format-filters
  "Format active CLI filters into a human-readable string."
  [opts]
  (let [parts (cond-> []
                (:filter opts)     (conj (str "filter=" (:filter opts)))
                (:batch opts)      (conj (str "batch=" (:batch opts)))
                (:difficulty opts) (conj (str "difficulty=" (:difficulty opts))))]
    (when (seq parts)
      (str/join ", " parts))))

(defn resolve-effort
  "Resolve the effective effort level: explicit flag, settings.json, or 'default'."
  [reasoning]
  (or reasoning
      (try
        (let [settings-path (fs/path (fs/home) ".claude" "settings.json")]
          (when (fs/exists? settings-path)
            (get (json/parse-string (slurp (str settings-path))) "effortLevel")))
        (catch Exception _ nil))
      "default"))

(defn print-run-header
  "Print a header at the start of a challenge run."
  [agent-name challenge-count opts model reasoning]
  (println)
  (println (format "Agent: %s%s [effort: %s] | Challenges: %d"
                   agent-name
                   (if model (str " (" model ")") "")
                   (resolve-effort reasoning)
                   challenge-count))
  (when-let [filters (format-filters opts)]
    (println (format "Filters: %s" filters)))
  (println (str/join (repeat 60 "-"))))

(defn format-score
  "Format a score integer as a string, or \"-\" when nil."
  [v]
  (if v (str v) "-"))

(defn format-composite
  "Format a composite score as a string with one decimal, or \"-\" when nil."
  [v]
  (if v (format "%.1f" (double v)) "-"))

(defn result-table-rows
  "Format results into table row strings.
  Returns nil for empty results."
  [results]
  (when (seq results)
    (let [max-name   (max 9 (apply max (map #(count (:name %)) results)))
          max-skills (max 6 (apply max (map #(count (str/join ", " (:skills-used % []))) results)))
          max-refs   (max 10 (apply max (map #(count (str/join ", " (:skill-refs-used % []))) results)))]
      {:header (format (str "| %-" max-name "s | %-24s | %-7s | %-7s | %-5s | %-6s | %-7s | %-8s | %-9s | %-10s | %-12s | %-10s | %-9s | %-10s | %-" max-skills "s | %-" max-refs "s | %-5s | %-9s |")
                       "Challenge" "Outcome" "Private" "Runner" "Score" "Builds" "Retries" "Duration"
                       "In Tokens" "Out Tokens" "Cache Create" "Cache Read" "Tool Uses" "Cost" "Skills" "Skill Refs"
                       "Align" "TestAlign")
       :separator (str "|" (str/join (repeat (+ max-name 2) "-"))
                       "|--------------------------|---------|---------|-------|--------|---------|----------|-----------|------------|--------------|------------|-----------|------------|"
                       (str/join (repeat (+ max-skills 2) "-"))
                       "|"
                       (str/join (repeat (+ max-refs 2) "-"))
                       "|-------|-----------|")
       :rows (mapv (fn [{:keys [name status private-status challenge-score iterations builds retries duration-s
                                input-tokens output-tokens
                                cache-creation-tokens cache-read-tokens tool-uses cost
                                skills-used skill-refs-used scoring] :as r}]
                     (let [scores (:scores scoring)]
                       (format (str "| %-" max-name "s | %-24s | %-7s | %-7s | %-5s | %-6d | %-7d | %-7ds | %-9d | %-10d | %-12d | %-10d | %-9d | %-10s | %-" max-skills "s | %-" max-refs "s | %-5s | %-9s |")
                               name
                               (outcome-label (result-outcome r))
                               (private-label private-status)
                               (runner-label status)
                               (if (some? challenge-score) (str challenge-score) "-")
                               (or builds iterations 0)
                               (or retries 0)
                               duration-s
                               input-tokens
                               output-tokens
                               cache-creation-tokens
                               cache-read-tokens
                               (or tool-uses 0)
                               (format-cost cost)
                               (str/join ", " (or skills-used []))
                               (str/join ", " (or skill-refs-used []))
                               (format-score (:alignment scores))
                               (format-score (:test-alignment scores)))))
                   results)})))

(defn print-summary-table
  "Print a formatted summary table to the console.
  When total-elapsed-s is provided, displays total elapsed time after the
  summary line."
  ([results] (print-summary-table results nil))
  ([results total-elapsed-s]
   (when-let [{:keys [header separator rows]} (result-table-rows results)]
     (let [{:keys [input-tokens output-tokens
                   cache-creation-tokens cache-read-tokens]} (token-totals results)]
       (println)
       (println header)
       (println separator)
       (doseq [row rows]
         (println row))
       (println)
       (let [total-cost      (when (some :cost results) (reduce + 0 (keep :cost results)))
             total-tool-uses (reduce + 0 (map #(or (:tool-uses %) 0) results))
             align-vals      (keep #(get-in % [:scoring :scores :alignment]) results)
             avg-align       (when (seq align-vals)
                               (/ (reduce + 0.0 align-vals) (count align-vals)))
             test-align-vals (keep #(get-in % [:scoring :scores :test-alignment]) results)
             avg-test-align  (when (seq test-align-vals)
                               (/ (reduce + 0.0 test-align-vals) (count test-align-vals)))
             results'        (mapv #(assoc % :outcome (result-outcome %)) results)]
         (println (outcome-counts-line results'))
         (when-let [line (private-verdict-line results)]
           (println line))
         (println (average-score-line results))
         (when avg-align
           (println (format "Average alignment: %.1f/5 (n=%d, informational)" avg-align (count align-vals))))
         (when avg-test-align
           (println (format "Average test alignment: %.1f/5 (n=%d, informational)" avg-test-align (count test-align-vals))))
         (println (format "Tokens: In: %d | Out: %d | Cache Create: %d | Cache Read: %d | Tool Uses: %d | Cost: %s"
                          input-tokens output-tokens cache-creation-tokens cache-read-tokens
                          total-tool-uses (format-cost total-cost))))
       (when total-elapsed-s
         (println (str "Total elapsed: " (format-duration total-elapsed-s))))))))

(defn generate-report
  "Generate a markdown report file. Returns the report file path.
  opts is an optional map with keys:
    :total-elapsed-s - includes total elapsed time in the report footer
    :model           - includes model in filename and content
    :reasoning       - includes reasoning in content; also in filename when model is present"
  ([results agent-name project-root]
   (generate-report results agent-name project-root {}))
  ([results agent-name project-root opts]
   (let [{:keys [total-elapsed-s model reasoning]} opts
         now (java.time.LocalDateTime/now)
         date-str (.format now (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd"))
         time-str (.format now (java.time.format.DateTimeFormatter/ofPattern "HHmmss"))
         timestamp (.format now (java.time.format.DateTimeFormatter/ofPattern
                                 "yyyy-MM-dd HH:mm:ss"))
         reports-dir (fs/path project-root ".." "reports")
         filename (cond
                    (and model reasoning) (format "%s-%s-%s-%s-%s.md" date-str time-str agent-name (str/replace model #"[/\\\\]" "_") reasoning)
                    model                 (format "%s-%s-%s-%s.md" date-str time-str agent-name (str/replace model #"[/\\\\]" "_"))
                    :else                 (format "%s-%s-%s.md" date-str time-str agent-name))
         report-path (fs/path reports-dir filename)
         results' (mapv #(assoc % :outcome (result-outcome %)) results)
         sb (StringBuilder.)]
     (fs/create-dirs reports-dir)
     (.append sb (format "# Challenge Run Report - %s\n" timestamp))
     (.append sb (format "Agent: %s\n" agent-name))
     (when model
       (.append sb (format "Model: %s\n" model)))
     (when reasoning
       (.append sb (format "Reasoning: %s\n" reasoning)))
     (.append sb (str (outcome-counts-line results') "\n"))
     (when-let [line (private-verdict-line results)]
       (.append sb (str line "\n")))
     (.append sb "\n")
     (.append sb "| Challenge | Outcome | Private | Runner | Score | Builds | Retries | Duration | In Tokens | Out Tokens | Cache Create | Cache Read | Tool Uses | Cost | Skills | Skill Refs | Align | TestAlign |\n")
     (.append sb "|-----------|---------|---------|--------|-------|--------|---------|----------|-----------|------------|--------------|------------|-----------|------|--------|------------|-------|----------|\n")
     (doseq [{:keys [name status challenge-score iterations builds retries duration-s
                     input-tokens output-tokens
                     cache-creation-tokens cache-read-tokens tool-uses cost
                     skills-used skill-refs-used scoring] :as r} results']
       (let [scores (:scores scoring)]
         (.append sb (format "| %s | %s | %s | %s | %s | %d | %d | %ds | %d | %d | %d | %d | %d | %s | %s | %s | %s | %s |\n"
                             name
                             (outcome-label (:outcome r))
                             (private-detail r)
                             (runner-label status)
                             (if (some? challenge-score) (str challenge-score) "-")
                             (or builds iterations 0)
                             (or retries 0)
                             duration-s
                             input-tokens
                             output-tokens
                             cache-creation-tokens
                             cache-read-tokens
                             (or tool-uses 0)
                             (format-cost cost)
                             (str/join ", " (or skills-used []))
                             (str/join ", " (or skill-refs-used []))
                             (format-score (:alignment scores))
                             (format-score (:test-alignment scores))))))
     (let [{:keys [input-tokens output-tokens
                   cache-creation-tokens cache-read-tokens]} (token-totals results)
           total-cost      (when (some :cost results) (reduce + 0 (keep :cost results)))
           total-tool-uses (reduce + 0 (map #(or (:tool-uses %) 0) results))
           align-vals      (keep #(get-in % [:scoring :scores :alignment]) results)
           avg-align       (when (seq align-vals)
                             (/ (reduce + 0.0 align-vals) (count align-vals)))
           test-align-vals (keep #(get-in % [:scoring :scores :test-alignment]) results)
           avg-test-align  (when (seq test-align-vals)
                             (/ (reduce + 0.0 test-align-vals) (count test-align-vals)))]
       (.append sb (str "\n**" (str/replace-first (average-score-line results) ":" ":**") "\n"))
       (when avg-align
         (.append sb (format "**Average alignment (informational):** %.1f/5 (n=%d)\n" avg-align (count align-vals))))
       (when avg-test-align
         (.append sb (format "**Average test alignment (informational):** %.1f/5 (n=%d)\n" avg-test-align (count test-align-vals))))
       (.append sb (format "**Tokens:** In: %d | Out: %d | Cache Create: %d | Cache Read: %d | Tool Uses: %d | Cost: %s\n"
                           input-tokens output-tokens cache-creation-tokens cache-read-tokens
                           total-tool-uses (format-cost total-cost))))
     (let [justified (filterv #(some (fn [k] (get-in % [:scoring :scores k]))
                                     [:alignment-justification :test-alignment-justification])
                              results)]
       (when (seq justified)
         (.append sb "\n## Alignment Justifications\n\n")
         (doseq [{:keys [name scoring]} justified]
           (let [{:keys [alignment alignment-justification
                         test-alignment test-alignment-justification]} (:scores scoring)]
             (when alignment-justification
               (.append sb (format "- **%s** impl (%d/5): %s\n" name alignment alignment-justification)))
             (when test-alignment-justification
               (.append sb (format "- **%s** tests (%d/5): %s\n" name test-alignment test-alignment-justification)))))))
     (when total-elapsed-s
       (.append sb (format "\n**Total elapsed:** %s\n" (format-duration total-elapsed-s))))
     (spit (str report-path) (str sb))
     (str report-path))))

;;; Scored-run isolation preflight

(defn require-scored-run-isolation!
  "Every run-challenges run is scored, so every solver phase must launch in
  the bubblewrap public snapshot. Probe bubblewrap and audit each selected
  challenge's snapshot now, before spending tokens. Returns the isolation
  record for the manifest; throws when isolation is absent or broken."
  [project-root opts agent-name challenge-names]
  (when-not (or (:isolate opts) (:isolate-network opts))
    (throw (ex-info (str "Scored runs require solver isolation: pass --isolate-network (or --isolate). "
                         "Without it the solver can read private tests, reference docs and host credentials.")
                    {:reason :isolation-required})))
  (let [strict? (boolean (:isolate-network opts))
        {:keys [exit out err]}
        (invoke-command! (into ["python3" (str (fs/path project-root "scripts/isolate_solver.py"))
                                "--repo" (str project-root) "--agent" agent-name
                                "--network" (if strict? "strict" "shared") "--preflight"]
                               (mapcat #(vector "--audit-challenge" %) challenge-names))
                         project-root)]
    (when-not (zero? exit)
      (throw (ex-info (str "Isolation preflight failed: " (str/trim (str err "\n" out)))
                      {:reason :isolation-preflight-failed :exit exit})))
    (assoc (json/parse-string out true)
           :mode (if strict? "bubblewrap-provider-network" "bubblewrap-public-only")
           :launcher "scripts/isolate_solver.py"
           :preflight "passed")))

;;; Main

(defn -main [args]
  (let [opts (cli/parse-opts args {:spec cli-spec})
        project-root (str (fs/parent (fs/absolutize "bb.edn")))]
    (when (:help opts)
      (print-usage)
      (System/exit 0))

    (let [challenge-order-path (fs/path project-root "CHALLENGE_ORDER.md")]
      (when-not (fs/exists? challenge-order-path)
        (binding [*out* *err*]
          (println "Error: CHALLENGE_ORDER.md not found at" (str challenge-order-path)))
        (System/exit 1))

      (let [ordered-challenges (parse-challenge-order (str challenge-order-path))
            all-challenges (discover-all-challenges project-root ordered-challenges)
            filtered (filter-challenges all-challenges opts)
            {:keys [local cluster]} (partition-by-cluster filtered)
            cluster-with-setup (filterv #(has-hidden-setup? project-root (:name %)) cluster)
            cluster-without-setup (filterv #(not (has-hidden-setup? project-root (:name %))) cluster)
            ;; Gate only cluster challenges that do not provide their own hidden setup
            cluster-available (and (seq cluster-without-setup) (cluster-running?))
            _ (when (seq cluster-without-setup)
                (if cluster-available
                  (println "Cluster available: including Batch 5 challenges.")
                  (do
                    (println "Cluster unavailable: skipping Batch 5 challenges without hidden setup.")
                    (println "  Start the local cluster and retry, or check RAMA_CONDUCTOR_HOST/RAMA_CONDUCTOR_PORT."))))
            _ (when (seq cluster-with-setup)
                (println "Including Batch 5 challenges with hidden setup."))
            valid-challenges (concat local cluster-with-setup (if cluster-available cluster-without-setup []))
            {:keys [valid missing]} (validate-challenges (vec valid-challenges) project-root)
            agent-key (keyword (:agent opts))
            agent-name (:agent opts)
            fast-model   (:fast-model opts)
            fast-effort  (:fast-effort opts)
            slow-model   (:slow-model opts)
            slow-effort  (:slow-effort opts)
            missing-tier (->> [[:fast-model fast-model] [:fast-effort fast-effort]
                               [:slow-model slow-model] [:slow-effort slow-effort]]
                              (filter (fn [[_ v]] (str/blank? (str v))))
                              (mapv first))
            ;; the slow tier labels the run in headers, reports, and the db
            model slow-model
            reasoning slow-effort]

        (when (seq missing-tier)
          (binding [*out* *err*]
            (println "Error: these required model flags are missing:")
            (doseq [k missing-tier]
              (println (str "  --" (name k))))
            (println "All four of --fast-model, --fast-effort, --slow-model, --slow-effort are required."))
          (System/exit 1))

        (when (seq missing)
          (binding [*out* *err*]
            (println "Warning: missing challenge directories:")
            (doseq [{:keys [name]} missing]
              (println (str "  - " name)))))

        (when (empty? valid)
          (println "No challenges found matching filters.")
          (System/exit 0))

        (require-reference-isolation! project-root (:isolate-network opts))

        (when (and (:isolate-network opts) (not (contains? #{"claude" "opencode"} agent-name)))
          (throw (ex-info "--isolate-network supports Claude and OpenCode/OpenRouter only" {})))

        (when (and (or (:isolate opts) (:isolate-network opts))
                   (contains? #{"claude" "opencode"} agent-name))
          (let [{:keys [exit out err]}
                (invoke-command! ["python3" "scripts/check_solver_models.py"
                                  "--agent" agent-name
                                  "--pair" fast-model fast-effort
                                  "--pair" slow-model slow-effort] project-root)]
            (when (not= 0 exit)
              (throw (ex-info (str "Model/effort preflight failed: " out err)
                              {:exit exit})))
            (print out)))

        (print-run-header agent-name (count valid) opts model reasoning)
        (println (format "Models: fast=%s [%s] | slow=%s [%s]"
                         fast-model (resolve-effort fast-effort)
                         slow-model (resolve-effort slow-effort)))

        (let [isolation     (require-scored-run-isolation!
                             project-root opts agent-name (mapv :name valid))
              enc-key       (challenge-encryption-key)
              start-ms      (System/currentTimeMillis)
              started-at    (str (java.time.Instant/now))
              results       (binding [*verbose* (or (:verbose opts) (:pretty opts))
                                      *pretty* (boolean (:pretty opts))
                                      *isolate* (boolean (or (:isolate opts) (:isolate-network opts)))
                                      *isolate-network* (boolean (:isolate-network opts))
                                      *fast-model* fast-model
                                      *fast-reasoning* fast-effort
                                      *slow-model* slow-model
                                      *slow-reasoning* slow-effort
                                      *grader-timeout-s* (or (:grader-timeout opts) *grader-timeout-s*)]
                              (run-challenges valid agent-key agent-name project-root model reasoning enc-key))
              total-elapsed-s (/ (- (System/currentTimeMillis) start-ms) 1000.0)
              artifact-error (volatile! nil)]
          (print-summary-table results total-elapsed-s)
          (let [report-path (generate-report results agent-name project-root
                                             {:total-elapsed-s total-elapsed-s
                                              :model model
                                              :reasoning reasoning})]
            (println)
            (println (str "Report saved: " report-path))
            (let [run-meta {:run-id (str (fs/strip-ext (fs/file-name report-path)) "-" (subs (str (random-uuid)) 0 8))
                            :started-at started-at
                            :finished-at (str (java.time.Instant/now))
                            :args (vec args)
                            :repo-sha (git-out project-root "rev-parse" "HEAD")
                            :repo-dirty? (boolean (seq (git-out project-root "status" "--porcelain")))
                            :agent agent-name
                            :requested {:model model :effort reasoning
                                        :fast-model fast-model :fast-effort fast-effort
                                        :slow-model slow-model :slow-effort slow-effort}
                            :grader-timeout-s (or (:grader-timeout opts) *grader-timeout-s*)
                            :isolation isolation}]
              ;; A failed evaluator log stops the manifest and bundle; the
              ;; results database is still appended before the run fails.
              (try
                (let [{:keys [evaluator-log manifest bundle]}
                      (emit-run-artifacts! report-path project-root run-meta results)]
                  (println (str "Evaluator-only private log saved: " evaluator-log))
                  (some->> manifest (str "Manifest saved: ") println)
                  (some->> bundle (str "Bundle saved: ") println))
                (catch Exception e
                  (vreset! artifact-error e)
                  (binding [*out* *err*]
                    (println (str "ERROR: " (ex-message e))))))))

          ;; Append to results database
          (let [db-path (str (fs/path project-root ".." "reports" "results.edn"))
                timestamp (str (java.time.Instant/now))
                records (mapv (fn [{:keys [name status private-status challenge-score iterations duration-s
                                           input-tokens output-tokens
                                           cache-creation-tokens cache-read-tokens
                                           tool-uses cost scoring
                                           completion private-counts private-reason builds retries] :as r}]
                                {:timestamp            timestamp
                                 :agent                agent-name
                                 :model                model
                                 :reasoning            reasoning
                                 :challenge             name
                                 :status               status
                                 :outcome              (result-outcome r)
                                 :completion           completion
                                 :private-status       private-status
                                 :private-counts       private-counts
                                 :private-reason       private-reason
                                 :challenge-score      challenge-score
                                 :builds               builds
                                 :retries              retries
                                 :iterations           iterations
                                 :duration-s           duration-s
                                 :input-tokens         (or input-tokens 0)
                                 :output-tokens        (or output-tokens 0)
                                 :cache-creation-tokens (or cache-creation-tokens 0)
                                 :cache-read-tokens    (or cache-read-tokens 0)
                                 :tool-uses            (or tool-uses 0)
                                 :cost                 cost
                                 :scoring              scoring})
                              results)]
            (fs/create-dirs (fs/parent db-path))
            (spit db-path
                  (str (str/join "\n" (map pr-str records)) "\n")
                  :append true))

          (some-> @artifact-error throw)
          results)))))

(when (= *file* (System/getProperty "babashka.file"))
  (-main *command-line-args*)
  (tasks/shell "bash" "-c" "for i in $(seq 10); do printf '\\a'; sleep 0.3; done"))
