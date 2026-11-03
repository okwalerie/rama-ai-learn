#!/usr/bin/env bb
;; Static (code-only) detectors for two design judges in docs/judge-criteria.md:
;;
;;   J6 non-idempotent-retry    a non-idempotent write in a stream topology
;;   J7 global-or-hot-partition a global PState or |global on per-event work
;;
;; Usage, from the repo root:
;;   bb scripts/static_judges.bb <label>=<module.clj> ...
;;
;; Prints one EDN map per file: {:label .. :J6 {:verdict .. :sites [..]} :J7 {..}}.
;; Verdicts are :pass, :fail, :n-a or :unjudged. These are the code halves of
;; J6 and J7 only. The event-hook halves are not implemented, and neither judge
;; has been calibrated, so treat every verdict as uncalibrated detector output.
;; When the code cannot decide, the verdict is :unjudged, never a guess.

(require '[edamame.core :as e]
         '[clojure.string :as str])

(defn parse-forms [src]
  (e/parse-string-all src {:all true
                           :auto-resolve name
                           :readers (fn [_] identity)
                           :read-cond :allow
                           :features #{:clj}
                           :row-key :line
                           :col-key :col}))

(defn line-of [form] (:line (meta form)))

(defn sym-name [x] (when (symbol? x) (name x)))

(defn head [form] (when (seq? form) (sym-name (first form))))

(defn subforms
  "Every nested form, depth first, in source order."
  [form]
  (tree-seq coll? seq form))

;; ---------------------------------------------------------------------------
;; Topology types

(def topology-ctors {"stream-topology" :stream "microbatch-topology" :microbatch})

(defn topology-bindings
  "Map of binding symbol -> :stream or :microbatch, from let bindings such as
   (let [s (stream-topology topologies \"core\")] ...)."
  [forms]
  (into {}
        (for [f (mapcat subforms forms)
              :when (and (seq? f) (#{"let" "let*" "binding"} (head f)) (vector? (second f)))
              [sym v] (partition 2 (second f))
              :let [t (topology-ctors (head v))]
              :when (and (symbol? sym) t)]
          [(name sym) t])))

(defn sources-blocks
  "[{:type :stream|:microbatch|nil :segments [[form ...] ...]}] for each
   (<<sources t ...). A segment is the forms from one source> to the next."
  [forms bindings]
  (for [f (mapcat subforms forms)
        :when (= "<<sources" (head f))
        :let [[_ t & body] f]]
    {:type (bindings (sym-name t))
     :line (line-of f)
     :segments (->> body
                    (partition-by #(= "source>" (head %)))
                    (partition-all 2)
                    (map #(apply concat %)))}))

;; ---------------------------------------------------------------------------
;; J6 non-idempotent-retry

(def non-idempotent-aggs #{"+sum" "+count" "+vec-agg"})
(def idempotent-aggs #{"+max" "+min" "+last" "+first" "+set-agg" "+map-agg" "+and" "+or"})
(def append-navs #{"AFTER-ELEM" "BEFORE-ELEM" "BEFORE-INDEX"})
(def non-idempotent-term-fns #{"inc" "dec" "+" "-" "conj" "+'" "-'"})

(defn term-kind
  "Classifies (term f): :non-idempotent, :unknown (f is a dataflow var or an
   unrecognised fn), or nil (known idempotent, such as identity or max)."
  [f]
  (cond
    (and (symbol? f) (non-idempotent-term-fns (name f))) :non-idempotent
    (and (seq? f) (= "partial" (head f)) (non-idempotent-term-fns (sym-name (second f)))) :non-idempotent
    (and (symbol? f) (#{"identity" "max" "min"} (name f))) nil
    (and (seq? f) (= "partial" (head f)) (#{"max" "min"} (sym-name (second f)))) nil
    :else :unknown))

(defn write-site
  "Returns {:kind .. :line .. :what ..} when form is a write J6 cares about."
  [form]
  (case (head form)
    "+compound"
    (let [aggs (keep #(when (str/starts-with? (or (head %) "") "+") (head %))
                     (subforms (nth form 2 nil)))]
      (or (when-let [agg (some #(when (non-idempotent-aggs %) %) aggs)]
            {:kind :non-idempotent :line (line-of form) :what (str "+compound with " agg)})
          (when-let [agg (some #(when-not (idempotent-aggs %) %) aggs)]
            {:kind :unknown :line (line-of form) :what (str "+compound with custom aggregator " agg)})))

    "local-transform>"
    (let [path (second form)
          nodes (subforms path)]
      (or (when-let [n (some #(when (append-navs (sym-name %)) (name %)) nodes)]
            {:kind :non-idempotent :line (line-of form) :what (str "local-transform> " n)})
          (some (fn [t]
                  (when (= "term" (head t))
                    (when-let [k (term-kind (second t))]
                      {:kind k :line (line-of form) :what (str "local-transform> (term " (pr-str (second t)) ")")})))
                nodes)))

    "depot-partition-append!"
    {:kind :unknown :line (line-of form) :what "depot-partition-append! (re-append on retry)"}

    nil))

(defn reads? [form] (#{"local-select>" "select>"} (head form)))

(defn segment-sites
  "Write sites in one source> segment, each marked :read-before? when a
   PState read comes earlier in the segment (it may be a dedup check)."
  [segment]
  (loop [[f & more] (mapcat subforms segment) seen-read? false acc []]
    (if (nil? f)
      acc
      (let [site (write-site f)]
        (recur more
               (or seen-read? (boolean (reads? f)))
               (cond-> acc site (conj (assoc site :read-before? seen-read?))))))))

(defn judge-j6 [forms]
  (let [blocks (sources-blocks forms (topology-bindings forms))
        sites (for [{:keys [type segments]} blocks
                    seg segments
                    s (segment-sites seg)]
                (assoc s :topology type))
        risky (remove #(= :microbatch (:topology %)) sites)
        definite (filter #(and (= :non-idempotent (:kind %)) (= :stream (:topology %))
                               (not (:read-before? %)))
                         risky)]
    (cond
      (empty? blocks) {:verdict :unjudged :reason "no <<sources block found" :sites []}
      (empty? sites) {:verdict :n-a :reason "no non-idempotent write" :sites []}
      (seq definite) {:verdict :fail :sites (vec definite)}
      (empty? risky) {:verdict :pass :reason "all non-idempotent writes are in microbatch topologies" :sites (vec sites)}
      :else {:verdict :unjudged
             :reason "stream-topology write whose idempotence code cannot decide (possible dedup read, or opaque term fn / depot append)"
             :sites (vec risky)})))

;; ---------------------------------------------------------------------------
;; J7 global-or-hot-partition

(defn global-opts? [form]
  (some #(and (map? %) (true? (:global? %))) form))

(defn global-pstates [forms]
  (for [f (mapcat subforms forms)
        :when (and (= "declare-pstate" (head f)) (global-opts? f))
        :let [[_ _ pname schema] f]]
    {:line (line-of f)
     :pstate (str pname)
     ;; A plain class or a fixed-keys record has no per-entity keys.
     :singleton? (or (and (symbol? schema) (not (str/includes? (name schema) "schema")))
                     (= "fixed-keys-schema" (head schema)))}))

(defn global-partitioners
  "Every |global with the topology it sits in and whether it is inside a
   <<batch block (where it can follow a two-phase aggregation)."
  [forms bindings]
  (for [{:keys [type segments]} (sources-blocks forms bindings)
        seg segments
        top seg
        :let [batch-forms (set (for [f (subforms top) :when (= "<<batch" (head f)) g (subforms f)] g))]
        f (subforms top)
        :when (= "|global" (head f))]
    {:line (line-of f) :topology type :in-batch? (contains? batch-forms f)}))

(defn judge-j7 [forms]
  (let [gps (global-pstates forms)
        gparts (global-partitioners forms (topology-bindings forms))
        fails (remove :in-batch? gparts)
        open-gps (remove :singleton? gps)]
    (cond
      (seq fails) {:verdict :fail :reason "|global on per-event work outside a batch block" :sites (vec fails)}
      (seq open-gps) {:verdict :unjudged
                      :reason "global PState with a collection schema; key cardinality needs a human declaration"
                      :sites (vec open-gps)}
      :else {:verdict :pass
             :reason (if (seq (concat gps gparts))
                       "global state is singleton-only, and any |global is inside a batch block"
                       "no global PState and no |global")
             :sites (vec (concat gps gparts))})))

;; ---------------------------------------------------------------------------

(defn judge-file [label path]
  (let [forms (parse-forms (slurp path))]
    {:label label :file path :J6 (judge-j6 forms) :J7 (judge-j7 forms)}))

(defn -main [args]
  (doseq [a args
          :let [[label path] (str/split a #"=" 2)]]
    (prn (try (judge-file label path)
              (catch Exception ex {:label label :file path :error (ex-message ex)})))))

(when (= *file* (System/getProperty "babashka.file"))
  (-main *command-line-args*))
