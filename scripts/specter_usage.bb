#!/usr/bin/env bb
;; Counts Specter path usage in Rama module files.
;;
;; Usage, from the repo root:
;;   bb scripts/specter_usage.bb <label>=<module.clj> ...
;;   bb scripts/specter_usage.bb --adoption <label>=<module.clj> ...
;;
;; Prints one EDN map per file: line count, path operations (local-select>,
;; local-transform>, foreign-select and so on), navigator frequencies, calls to
;; Clojure collection functions and uses of :allow-yield?. With --adoption it
;; instead prints, for reference files (labels starting "REF") and agent files
;; separately, how many files use each navigator at least once.
;;
;; These are raw counts. They say which navigators appear, not whether the
;; module uses them well; read the code before drawing conclusions.

(require '[edamame.core :as e]
         '[clojure.string :as str])

(def navigators
  #{"keypath" "must" "MAP-VALS" "MAP-KEYS" "ALL" "FIRST" "LAST" "STAY" "STOP"
    "sorted-map-range" "sorted-map-range-from" "sorted-map-range-to" "sorted-map-range-from-start"
    "sorted-set-range" "sorted-set-range-from" "sorted-set-range-to"
    "view" "filterer" "selected?" "not-selected?" "if-path" "multi-path" "subselect" "submap" "subset"
    "collect" "collect-one" "putval" "NONE" "NONE-ELEM" "AFTER-ELEM" "BEFORE-ELEM" "BEFORE-INDEX"
    "srange" "nthpath" "pred" "pred=" "pred<" "pred>" "pred<=" "pred>=" "transformed" "compact"
    "termval" "term" "nil->val" "NIL->VAL" "NIL->SET" "NIL->LIST" "NIL->VECTOR" "set-elem" "map-key"
    "INDEXED-VALS"})

(def path-ops #{"local-select>" "local-transform>" "select>" "local-clear>"
                "foreign-select" "foreign-select-one" "foreign-invoke-query"})

(def clj-coll-fns #{"get" "get-in" "assoc" "assoc-in" "update" "update-in" "dissoc" "vals" "keys"
                    "count" "filter" "remove" "sort" "sort-by" "take" "drop" "reduce" "into" "map"
                    "mapv" "conj" "first" "last" "contains?" "select-keys" "merge" "reverse" "seq"
                    "empty?"})

(def adoption-rows
  ["multi-path" "subselect" "view" "nil->val" "term" "submap" "sorted-map-range"
   "sorted-map-range-from" "sorted-map-range-to" "sorted-set-range-from" "selected?" "pred"
   "pred<" "if-path" "filterer" "NONE-ELEM" "AFTER-ELEM" "collect-one" "STAY" "srange"])

(defn parse-forms [src]
  (e/parse-string-all src {:all true
                           :auto-resolve name
                           :readers (fn [_] identity)
                           :read-cond :allow
                           :features #{:clj}}))

(defn usage [label path]
  (let [src (slurp path)
        nodes (tree-seq coll? seq (parse-forms src))
        heads (frequencies (for [f nodes :when (and (seq? f) (symbol? (first f)))]
                             (name (first f))))
        navs (frequencies (for [f nodes :when (and (symbol? f) (navigators (name f)))]
                            (name f)))]
    {:label label
     :file path
     :loc (count (str/split-lines src))
     :path-ops (select-keys heads path-ops)
     :navs navs
     :clj-coll-calls (reduce + 0 (vals (select-keys heads clj-coll-fns)))
     :allow-yield (count (filter #(and (keyword? %) (= "allow-yield?" (name %))) nodes))}))

(defn print-adoption [rows]
  (doseq [[group rs] (sort-by key (group-by #(if (str/starts-with? (:label %) "REF") "reference" "agent") rows))
          :let [n (count rs)]]
    (println (str group " files: " n))
    (doseq [nav adoption-rows]
      (println (format "  %-22s %2d/%d" nav (count (filter #(pos? (get-in % [:navs nav] 0)) rs)) n)))))

(defn -main [args]
  (let [adoption? (= "--adoption" (first args))
        rows (for [a (if adoption? (rest args) args)
                   :let [[label path] (str/split a #"=" 2)]]
               (try (usage label path)
                    (catch Exception ex {:label label :file path :error (ex-message ex)})))]
    (if adoption?
      (print-adoption (remove :error rows))
      (run! prn rows))))

(when (= *file* (System/getProperty "babashka.file"))
  (-main *command-line-args*))
