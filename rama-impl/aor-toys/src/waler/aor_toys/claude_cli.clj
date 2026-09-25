(ns waler.aor-toys.claude-cli
  "Run `claude -p --output-format stream-json` from inside an AOR node and
  turn its event stream into AOR trace data:

    - every event          -> aor/stream-chunk! (compact form, live in the UI/clients)
    - each tool_use/result -> :tool-call nested op (start = tool_use seen, finish = result seen)
    - the whole run        -> one :model-call nested op carrying the CLI's token
                              usage, cost, session id, num_turns and first-token time

  Auth: uses whatever the `claude` binary finds — normally CLAUDE_CODE_OAUTH_TOKEN
  (from `claude setup-token`) in the worker's environment. Never pass --bare:
  that forces ANTHROPIC_API_KEY."
  (:require
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [com.rpl.agent-o-rama :as aor])
  (:import
   [java.io BufferedReader]
   [java.nio.file Files]
   [java.nio.file.attribute FileAttribute]
   [java.util.concurrent TimeUnit]))

(def claude-bin (or (System/getenv "CLAUDE_BIN") "claude"))

(defn- now [] (System/currentTimeMillis))

(defn- truncate [s n]
  (let [s (str s)] (if (> (count s) n) (str (subs s 0 n) "…") s)))

(defn- kill-tree!
  "Kill the CLI and everything it spawned (tool subprocesses, MCP servers)."
  [^Process proc]
  (.forEach (.descendants (.toHandle proc))
            (reify java.util.function.Consumer
              (accept [_ h] (.destroyForcibly ^java.lang.ProcessHandle h))))
  (.destroyForcibly proc))

(defn- content-blocks [event]
  (get-in event ["message" "content"]))

(defn- chunk-of
  "Small, UI-friendly summary of one stream-json event."
  [{:strs [type subtype] :as event}]
  (case type
    "system"    {:event "init" :model (get event "model") :session (get event "session_id")}
    "assistant" {:event  "assistant"
                 :blocks (vec (for [{:strs [type text name input]} (content-blocks event)]
                                (case type
                                  "text"     {:text (truncate text 500)}
                                  "tool_use" {:tool name :input (truncate (json/write-str input) 300)}
                                  {:type type})))}
    "user"      {:event   "tool-results"
                 :results (vec (for [{:strs [tool_use_id is_error content]} (content-blocks event)
                                     :when tool_use_id]
                                 {:id tool_use_id :error (boolean is_error)
                                  :content (truncate (if (string? content) content (json/write-str content)) 300)}))}
    "result"    {:event "result" :subtype subtype :turns (get event "num_turns")
                 :cost (get event "total_cost_usd")}
    {:event type}))

(defn invoke!
  "Run one headless Claude Code invocation inside `agent-node`. Blocks (fine:
  AOR nodes run on virtual threads). Returns
  {:result s :is-error bool :session-id s :usage {..} :cost-usd x :num-turns n
   :exit n :events [..raw stream-json events..]}.

  opts:
    :model          e.g. \"sonnet\" / \"haiku\"
    :allowed-tools  string for --allowedTools (default: no tools at all)
    :cwd            working directory (default: a fresh temp dir)
    :timeout-ms     hard deadline; the process tree is killed after it (default 10 min)"
  [agent-node prompt {:keys [model allowed-tools cwd timeout-ms]
                      :or   {timeout-ms (* 10 60 1000)}}]
  (let [cwd     (or cwd (str (Files/createTempDirectory "aor-claude-" (make-array FileAttribute 0))))
        cmd     (cond-> [claude-bin "-p" prompt
                         "--output-format" "stream-json" "--verbose"]
                  model         (into ["--model" model])
                  allowed-tools (into ["--allowedTools" allowed-tools])
                  (not allowed-tools) (into ["--tools" ""]))
        start   (now)
        pb      (doto (ProcessBuilder. ^java.util.List cmd)
                  (.directory (io/file cwd))
                  (.redirectErrorStream false))
        proc    (.start pb)
        _       (.close (.getOutputStream proc))
        stderr  (future (slurp (.getErrorStream proc)))
        watchdog (future
                   (when-not (.waitFor proc timeout-ms TimeUnit/MILLISECONDS)
                     (kill-tree! proc)
                     :timed-out))
        events  (volatile! [])
        open-tools (volatile! {})   ; tool_use id -> {:start :name :input}
        first-token (volatile! nil)
        final   (volatile! nil)]
    (try
      (with-open [^BufferedReader rdr (io/reader (.getInputStream proc))]
        (doseq [line (line-seq rdr)
                :when (str/starts-with? line "{")]
          (let [ev (json/read-str line)
                t  (now)]
            (vswap! events conj ev)
            (aor/stream-chunk! agent-node (chunk-of ev))
            (case (get ev "type")
              "assistant"
              (do (vreset! first-token (or @first-token t))
                  (doseq [{:strs [type id name input]} (content-blocks ev)
                          :when (= type "tool_use")]
                    (vswap! open-tools assoc id {:start t :name name :input input})))
              "user"
              (doseq [{:strs [tool_use_id is_error content]} (content-blocks ev)
                      :let [{s :start n :name in :input} (get @open-tools tool_use_id)]
                      :when s]
                (vswap! open-tools dissoc tool_use_id)
                (aor/record-nested-op!
                 agent-node :tool-call s t
                 {"toolName" n
                  "toolUseId" tool_use_id
                  "input"    (json/write-str in)
                  "output"   (truncate (if (string? content) content (json/write-str content)) 4000)
                  "isError"  (boolean is_error)}))
              "result" (vreset! final ev)
              nil))))
      (let [exit   (.waitFor proc)
            timed-out? (= :timed-out (deref watchdog 1000 nil))
            err    (deref stderr 5000 "")
            {:strs [result is_error session_id usage total_cost_usd num_turns modelUsage]} @final
            in     (+ (get usage "input_tokens" 0)
                      (get usage "cache_creation_input_tokens" 0)
                      (get usage "cache_read_input_tokens" 0))
            out    (get usage "output_tokens" 0)
            failure (cond timed-out? (str "timed out after " timeout-ms "ms")
                          (nil? @final) (str "no result event; exit " exit "; stderr: " (truncate err 2000))
                          is_error (str "claude reported error: " (truncate result 2000)))]
        (aor/record-nested-op!
         agent-node :model-call start (now)
         (cond-> {"objectName"       "claude-cli"
                  "modelName"        (or (some-> modelUsage keys first) model "default")
                  "input"            prompt
                  "response"         result
                  "sessionId"        session_id
                  "numTurns"         num_turns
                  "costUsd"          total_cost_usd
                  "cacheReadTokens"  (get usage "cache_read_input_tokens" 0)
                  "inputTokenCount"  in
                  "outputTokenCount" out
                  "totalTokenCount"  (+ in out)}
           @first-token (assoc "firstTokenTimeMillis" @first-token)
           failure      (assoc "failure" failure)))
        {:result     result
         :is-error   (boolean (or failure is_error))
         :failure    failure
         :session-id session_id
         :usage      usage
         :cost-usd   total_cost_usd
         :num-turns  num_turns
         :exit       exit
         :events     @events})
      (finally
        (when (.isAlive proc)
          (kill-tree! proc))))))
