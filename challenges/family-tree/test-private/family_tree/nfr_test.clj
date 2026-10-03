(ns family-tree.nfr-test
  "NFR tests: ancestor traversal deduplicates visited IDs (protocol
   docstring), so its work follows distinct ancestors rather than the
   exponential number of paths; and a retried add-person! links each child
   once."
  (:require [clojure.test :refer [deftest is testing]]
            [com.rpl.rama.test :as rtest]
            [family-tree.protocol :as p]
            [rama-challenges.harness :as harness]
            [rama-challenges.nfr :as nfr])
  (:import [java.util UUID]))

(defn- id [n]
  (UUID/fromString (format "00000000-0000-0000-0000-%012d" n)))

(defn- exercise [tasks f]
  (let [{:keys [module wrap-client]}
        ((requiring-resolve 'family-tree.module/create-module))]
    (with-open [ipc (rtest/create-ipc)]
      (rtest/launch-module! ipc module {:tasks tasks :threads tasks})
      (f (wrap-client ipc) (wrap-client ipc)))))

(defn- median-ms [f]
  (let [times (vec (sort (for [_ (range 5)]
                           (let [t0 (System/nanoTime)]
                             (f)
                             (/ (- (System/nanoTime) t0) 1e6)))))]
    (times 2)))

(deftest ancestor-work-follows-distinct-ancestors-not-paths
  ;; Pedigree collapse: each generation is a pair whose members are both
  ;; children of the previous pair, so 14 generations give 28 distinct
  ;; ancestors of the leaf but 2^15 parent paths to walk without
  ;; deduplication. The control lineage has the same 28 distinct ancestors
  ;; and no shared ones.
  ;;
  ;; Measured by wall-clock ratio, not storage events: with
  ;; rtest/with-event-hook installed, a loop<- traversal that emits two
  ;; parents per step returns a truncated result, so hook counts do not
  ;; reflect the real traversal here. The 10x + 250 ms margin is far above
  ;; the reference's ratio (about 1) and far below a path-walking design's
  ;; (about 100x at this depth).
  (doseq [tasks [2 4]]
    (testing (str tasks " tasks")
      (exercise tasks
        (fn [a b]
          (let [generations 14
                pair (fn [g] [(id (+ 1000 (* 2 g))) (id (+ 1001 (* 2 g)))])
                chain (fn [line g] (id (+ line g)))
                ladder-leaf (id 1)
                chain-leaf (id 2)]
            (doseq [g (range generations)
                    :let [[x y] (pair g)
                          [px py] (if (zero? g) [nil nil] (pair (dec g)))]
                    [person n] [[x "x"] [y "y"]]]
              (p/add-person! a (p/->Person person px py (str n g))))
            (let [[px py] (pair (dec generations))]
              (p/add-person! a (p/->Person ladder-leaf px py "ladder-leaf")))
            (doseq [g (range generations)
                    line [5000 6000]]
              (p/add-person! a (p/->Person (chain line g)
                                           (when (pos? g) (chain line (dec g))) nil
                                           (str "c" line "-" g))))
            (p/add-person! a (p/->Person chain-leaf (chain 5000 (dec generations))
                                         (chain 6000 (dec generations)) "chain-leaf"))
            (harness/wait-for-processing! a)
            (is (= (set (mapcat pair (range generations))) (p/ancestors b ladder-leaf 30)))
            (is (= (set (for [g (range generations) line [5000 6000]] (chain line g)))
                   (p/ancestors b chain-leaf 30)))
            (let [ladder (median-ms #(p/ancestors b ladder-leaf 30))
                  lineage (median-ms #(p/ancestors b chain-leaf 30))]
              (println "Ancestors median ms, 28 distinct ancestors" tasks "tasks:"
                       {:collapsed-ladder ladder :plain-lineage lineage})
              (is (<= ladder (+ 250 (* 10 lineage)))
                  (str "ancestor work must follow distinct ancestors, not the 2^15 paths of a "
                       "collapsed pedigree: " ladder " ms vs " lineage " ms")))))))))

(deftest forced-stream-retry-links-each-child-once
  (doseq [tasks [2 4]]
    (testing (str tasks " tasks")
      (exercise tasks
        (fn [a b]
          (let [[parent child grand] (map id [1 2 3])
                retried! #(nfr/failed-streaming
                           (fn [] (p/add-person! a %) (harness/wait-for-processing! a)))]
            (retried! (p/->Person parent nil nil "parent"))
            (retried! (p/->Person child parent nil "child"))
            (retried! (p/->Person grand child parent "grand"))
            (is (= {0 2 1 1 2 0} (p/descendants-count b parent 3))
                "a retried add must not add a second child path")
            (is (= #{child parent} (p/ancestors b grand 2)))))))))
