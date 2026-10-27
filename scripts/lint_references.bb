;; Lint challenge reference implementations with the Rama clj-kondo hooks.
;;
;; The hooks (github.com/redplanetlabs/rama-clj-kondo) ship inside the Rama jar
;; under clj-kondo.exports/. This imports them into the root .clj-kondo/imports/
;; (gitignored) from the root classpath, then lints every non-test .clj under
;; challenges/*/test-resources, with challenge src/ and the harness as context.
;;
;; Usage: bb lint-references [challenge-name ...]
;; Exits non-zero when any reference file has a lint error.

(require '[babashka.fs :as fs]
         '[babashka.process :refer [shell]]
         '[clojure.edn :as edn]
         '[clojure.string :as str])

(defn- reference-files [challenge-dir]
  (->> (fs/glob (fs/path challenge-dir "test-resources") "**.clj")
       (map str)
       (remove #(str/ends-with? % "_test.clj"))
       sort))

(defn- import-configs! []
  (let [cp (str/trim (:out (shell {:out :string} "clojure" "-Spath")))]
    (shell {:out :string :err :string}
           "clj-kondo" "--lint" cp "--copy-configs" "--skip-lint" "--dependencies")))

(defn -main [args]
  (let [dirs (if (seq args)
               (map #(str "challenges/" %) args)
               (->> (fs/list-dir "challenges") (filter fs/directory?) (map str) sort))
        files (mapcat reference-files dirs)
        context (concat (filter fs/directory? (map #(str % "/src") dirs))
                        ["lib/harness/src"])]
    (when (empty? files)
      (println "No reference files found.")
      (System/exit 0))
    (import-configs!)
    (let [{:keys [out]} (apply shell {:out :string :continue true}
                               "clj-kondo" "--parallel"
                               "--config" "{:output {:format :edn}}"
                               "--lint" (concat files context))
          findings (->> (:findings (edn/read-string out))
                        (filter #(str/includes? (:filename %) "/test-resources/"))
                        (sort-by (juxt :filename :row :col)))]
      (doseq [{:keys [filename row col level type message]} findings]
        (println (str filename ":" row ":" col ": " (name level) " [" (name type) "] " message)))
      (let [levels (frequencies (map :level findings))]
        (println (format "\n%d reference files: %d errors, %d warnings, %d info"
                         (count files) (levels :error 0) (levels :warning 0) (levels :info 0)))
        (when (pos? (levels :error 0)) (System/exit 3))))))
