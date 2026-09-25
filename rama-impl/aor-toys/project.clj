(defproject waler/aor-toys "0.1.0-SNAPSHOT"
  :description "Toy Agent-o-rama agents: Jev (TypeSafe System One) and traced `claude -p`."
  :dependencies [[com.rpl/agent-o-rama "0.10.0"]
                 [org.clojure/data.json "2.5.1"]]
  :source-paths ["src"]
  ;; Rama/AOR module compilation recurses deeply; the default stack overflows.
  :jvm-opts ["-Xss6m"]
  :global-vars {*warn-on-reflection* true}
  :repositories
  [["releases"
    {:id  "maven-releases"
     :url "https://nexus.redplanetlabs.com/repository/maven-public-releases"}]]
  :profiles {:provided {:dependencies
                        [[com.rpl/rama "1.9.0"]
                         [org.clojure/clojure "1.12.4"]
                         [org.apache.logging.log4j/log4j-slf4j2-impl "2.25.1"]]}})
