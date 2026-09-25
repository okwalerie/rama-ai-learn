(ns waler.aor-toys.jev
  "Minimal client for TypeSafe's System One API (model family: Jev).

  POST https://api.typesafe.ai/v1/systemone with
    {:model \"jev-latest\" :state <content> :questions {name question}}
  where a question is one of
    {:type \"noul\"   :instructions s}                      -> {:noul p}
    {:type \"choice\" :instructions s :criteria {k desc}}   -> {:choice k :confidence c :probabilities {..}}
    {:type \"score\"  :instructions s :criteria [lvl0 ..]}  -> {:score x :confidence c :legend {..} :probabilities {..}}

  Each call is recorded as a :model-call nested op so it shows up in AOR's
  traces and token/latency analytics. Without TYPESAFE_API_KEY the client
  returns deterministic stub answers (flagged :stub true) so the graph still runs."
  (:require
   [clojure.data.json :as json]
   [com.rpl.agent-o-rama :as aor])
  (:import
   [java.net URI]
   [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
    HttpResponse$BodyHandlers]
   [java.time Duration]))

(def api-base (or (System/getenv "TYPESAFE_API_BASE") "https://api.typesafe.ai"))
(def default-model (or (System/getenv "TYPESAFE_MODEL") "jev-latest"))

(defonce ^:private ^HttpClient client
  (-> (HttpClient/newBuilder)
      (.connectTimeout (Duration/ofSeconds 10))
      (.build)))

(defn- stub-answer
  [{:keys [type criteria]}]
  (case type
    "noul"   {"type" "noul" "noul" 0.5}
    "choice" (let [ks (map name (keys criteria))
                   p  (/ 1.0 (count ks))]
               {"type"          "choice"
                "choice"        (first ks)
                "confidence"    p
                "probabilities" (zipmap ks (repeat p))})
    "score"  (let [n (count criteria)]
               {"type"          "score"
                "score"         (/ (dec n) 2.0)
                "confidence"    0.0
                "legend"        (zipmap (map str (range n)) criteria)
                "probabilities" (zipmap (map str (range n)) (repeat (/ 1.0 n)))})))

(defn- post!
  [api-key body]
  (let [req  (-> (HttpRequest/newBuilder (URI/create (str api-base "/v1/systemone")))
                 (.timeout (Duration/ofSeconds 30))
                 (.header "Authorization" (str "Bearer " api-key))
                 (.header "Content-Type" "application/json")
                 (.POST (HttpRequest$BodyPublishers/ofString (json/write-str body)))
                 (.build))
        resp (.send client req (HttpResponse$BodyHandlers/ofString))
        parsed (json/read-str (.body resp))]
    (if (= 200 (.statusCode resp))
      parsed
      (throw (ex-info (str "TypeSafe API " (.statusCode resp))
                      {:status (.statusCode resp) :body parsed})))))

(defn decide!
  "Ask Jev `questions` (map of name -> question map, keyword or string keys)
  about `state`. Returns {:answers {name answer} :model s :usage {..} :stub bool}
  with string keys inside answers. Records a :model-call nested op."
  ([agent-node state questions]
   (decide! agent-node state questions {}))
  ([agent-node state questions {:keys [model] :or {model default-model}}]
   (let [api-key (System/getenv "TYPESAFE_API_KEY")
         body    {"model" model "state" state "questions" questions}
         start   (System/currentTimeMillis)]
     (try
       (let [{:strs [answers usage] :as resp}
             (if api-key
               (post! api-key body)
               {"model"   (str model " (stub)")
                "answers" (into {} (for [[k q] questions] [(name k) (stub-answer q)]))
                "usage"   {"input_tokens" 0 "output_tokens" 0}})
             in  (get usage "input_tokens" 0)
             out (get usage "output_tokens" 0)]
         (aor/record-nested-op!
          agent-node :model-call start (System/currentTimeMillis)
          {"objectName"       "typesafe-jev"
           "modelName"        (get resp "model")
           "input"            (json/write-str body)
           "response"         (json/write-str answers)
           "stub"             (nil? api-key)
           "inputTokenCount"  in
           "outputTokenCount" out
           "totalTokenCount"  (+ in out)})
         {:answers answers :model (get resp "model") :usage usage :stub (nil? api-key)})
       (catch Exception e
         (aor/record-nested-op!
          agent-node :model-call start (System/currentTimeMillis)
          {"objectName" "typesafe-jev"
           "input"      (json/write-str body)
           "failure"    (str e (some-> (ex-data e) pr-str))})
         (throw e))))))
