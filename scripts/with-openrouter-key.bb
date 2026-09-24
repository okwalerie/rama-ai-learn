#!/usr/bin/env bb
(require '[cheshire.core :as json])

(defn fail [message]
  (binding [*out* *err*] (println message))
  (System/exit 1))

(defn bws [token args]
  ;; CLI JSON includes values: never inherit its output or diagnostics.
  (let [process (ProcessBuilder. (into ["bws"] args))
        env (.environment process)]
    (.put env "BWS_ACCESS_TOKEN" token)
    (.redirectError process java.lang.ProcessBuilder$Redirect/DISCARD)
    (let [child (.start process)
          output (slurp (.getInputStream child))]
      (when-not (zero? (.waitFor child)) (throw (Exception. "lookup failed")))
      (json/parse-string output true))))

(when (empty? *command-line-args*)
  (fail "Usage: scripts/with-openrouter-key.bb command [args...]"))

(let [token (System/getenv "BWS_API_KEY")]
  (when-not (seq token)
    (fail "Missing BWS_API_KEY: ensure the personal masked secret is injected into this orb."))
  (let [id (or (not-empty (System/getenv "OPENROUTER_BWS_SECRET_ID"))
               (try
                 (let [project (System/getenv "OPENROUTER_BWS_PROJECT_ID")
                       entries (bws token (cond-> ["secret" "list"] (seq project) (conj project)))
                       matches (filter #(= "OPENROUTER_API_KEY" (:key %)) entries)]
                   (when-not (and (sequential? entries) (= 1 (count matches)) (string? (:id (first matches)))
                                  (seq (:id (first matches))))
                     (throw (Exception. "not unique")))
                   (:id (first matches)))
                 (catch Exception _ (fail "Unable to resolve exactly one OPENROUTER_API_KEY secret by name."))))
        secret (try (bws token ["secret" "get" id])
                    (catch Exception _ (fail "OpenRouter secret lookup failed.")))
        value (:value secret)]
    (when-not (and (= id (:id secret)) (= "OPENROUTER_API_KEY" (:key secret))
                   (string? value) (seq value))
      (fail "OpenRouter secret lookup failed or returned an unexpected ID, key, or empty value."))
    (let [consumer (ProcessBuilder. *command-line-args*)
          env (.environment consumer)]
      (.remove env "BWS_API_KEY")
      (.remove env "BWS_ACCESS_TOKEN")
      (.put env "OPENROUTER_API_KEY" value)
      (.inheritIO consumer)
      (try (System/exit (.waitFor (.start consumer)))
           (catch Exception _ (fail "Unable to start consumer command."))))))
