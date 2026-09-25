(ns waler.aor-toys.module
  "Two toy agents for trying Jev and headless Claude Code on Agent-o-rama.

  JevTriage       - no LLM besides Jev. Input: a support-ticket string.
                    classify (Jev) -> escalate | auto-handle -> result map.
  ClaudeJevAgent  - Input: a question string, or {\"question\" .. \"model\" .. \"allowed-tools\" ..}.
                    ask-claude (`claude -p`, traced) -> judge (Jev grades the answer)
                    -> result map with answer, Jev verdict, CLI usage/cost.

  Deploy (on waler):
    rama-ctl deploy <jar> waler.aor-toys.module/AorToysModule 2 1 1"
  (:require
   [com.rpl.agent-o-rama :as aor]
   [waler.aor-toys.claude-cli :as claude]
   [waler.aor-toys.jev :as jev]))

(def triage-questions
  {"category"    {"type"         "choice"
                  "instructions" "What kind of request is this?"
                  "criteria"     {"bug"      "Something that used to work is broken"
                                  "question" "Asking how to do something"
                                  "feature"  "Asking for a new capability"
                                  "billing"  "About charges, plans or invoices"}}
   "urgency"     {"type"         "score"
                  "instructions" "How urgent is this?"
                  "criteria"     ["Can wait" "Needs attention this week" "Production is down"]}
   "needs_human" {"type"         "noul"
                  "instructions" "A human engineer must look at this."}})

(def judge-questions
  {"answers_question" {"type"         "noul"
                       "instructions" "The answer directly addresses the question that was asked."}
   "quality"          {"type"         "score"
                       "instructions" "How good is this answer?"
                       "criteria"     ["Wrong or unhelpful" "Partially correct" "Correct but thin"
                                       "Correct and complete"]}
   "next_step"        {"type"         "choice"
                       "instructions" "What should happen next with this answer?"
                       "criteria"     {"accept"    "The answer can be used as is"
                                       "retry"     "Ask again, the answer is poor"
                                       "escalate"  "A human expert should review it"}}})

(aor/defagentmodule AorToysModule
  [topology]

  (-> (aor/new-agent topology "JevTriage")
      (aor/node
       "classify"
       ["escalate" "auto-handle"]
       (fn [agent-node ticket]
         (let [{:keys [answers] :as d} (jev/decide! agent-node ticket triage-questions)
               urgency  (get-in answers ["urgency" "score"])
               human-p  (get-in answers ["needs_human" "noul"])
               decision {:ticket   ticket
                         :category (get-in answers ["category" "choice"])
                         :category-confidence (get-in answers ["category" "confidence"])
                         :urgency  urgency
                         :needs-human-p human-p
                         :model    (:model d)
                         :stub     (:stub d)}]
           (if (or (>= urgency 1.5) (>= human-p 0.6))
             (aor/emit! agent-node "escalate" decision)
             (aor/emit! agent-node "auto-handle" decision)))))
      (aor/node
       "escalate"
       nil
       (fn [agent-node decision]
         (aor/result! agent-node (assoc decision :route "escalate"))))
      (aor/node
       "auto-handle"
       nil
       (fn [agent-node decision]
         (aor/result! agent-node (assoc decision :route "auto-handle")))))

  (-> (aor/new-agent topology "ClaudeJevAgent")
      (aor/node
       "ask-claude"
       "judge"
       ;; Input: a question string, or {"question" .. "model" .. "allowed-tools" ..}.
       ;; (AOR node fns need a fixed arity.) Without allowed-tools the CLI runs
       ;; with every tool disabled.
       (fn [agent-node input]
         (let [opts     (if (map? input) input {"question" input})
               question (get opts "question")
               {:keys [result is-error failure session-id cost-usd num-turns usage events]}
               (claude/invoke! agent-node question
                               {:model         (or (get opts "model")
                                                   (System/getenv "AOR_TOYS_CLAUDE_MODEL")
                                                   "haiku")
                                :allowed-tools (get opts "allowed-tools")
                                :timeout-ms    (* 5 60 1000)})]
           (when is-error
             (throw (ex-info (str "claude -p failed: " failure) {:session-id session-id})))
           (aor/emit! agent-node "judge"
                      {:question   question
                       :answer     result
                       :session-id session-id
                       :cost-usd   cost-usd
                       :num-turns  num-turns
                       :usage      usage
                       :n-events   (count events)}))))
      (aor/node
       "judge"
       nil
       (fn [agent-node {:keys [question answer] :as run}]
         (let [{:keys [answers model stub]}
               (jev/decide! agent-node
                            {"question" question "answer" answer}
                            judge-questions)]
           (aor/result! agent-node
                        (assoc run
                               :verdict {:answers-question-p (get-in answers ["answers_question" "noul"])
                                         :quality            (get-in answers ["quality" "score"])
                                         :next-step          (get-in answers ["next_step" "choice"])
                                         :next-step-confidence (get-in answers ["next_step" "confidence"])
                                         :model              model
                                         :stub               stub})))))))
