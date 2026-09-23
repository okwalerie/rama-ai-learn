;; IMPORTANT: Before modifying this file, re-read test-resources/development/PLAN.md
;; and check pending todos. Adhere to all previously decided design decisions.

(ns hld-metrics-pipeline.module
  "Reference implementation for the hld-metrics-pipeline challenge.

   One depot (hashed by series), one microbatch topology owning one PState
   keyed by the canonical series record, and two query topologies. See
   test-resources/development/PLAN.md for the design rationale."
  (:require
   [com.rpl.rama :refer :all]
   [com.rpl.rama.path :refer :all]
   [com.rpl.rama.ops :as ops]
   [com.rpl.rama.test :as rtest]
   [rama-challenges.harness :as harness]
   [hld-metrics-pipeline.protocol :as p]))

;;; ---------------------------------------------------------------------------
;;; Series identity and depot records

(defrecord SeriesKey [tenant metric labels])

(defn series-key
  "Canonical series identity. Labels are canonicalized to a sorted map so
   equal label sets have identical serialized bytes regardless of the
   caller's map type or insertion order (top-level PState keys are matched
   on serialized form, and depot / |hash routing must agree)."
  [tenant metric labels]
  (->SeriesKey tenant metric (into (sorted-map) labels)))

(defrecord AdvanceClock [series clock])
(defrecord IngestSample [series timestamp value])

;;; ---------------------------------------------------------------------------
;;; Domain constants and pure helpers (plain Clojure, called from dataflow)

(def RAW-RETENTION 300)
(def ROLLUP-RETENTION 7200)

(def INFO-KEYS [:clock :accepted :rejected-future :rejected-expired :rejected-duplicate])
(def ZERO-INFO (zipmap INFO-KEYS (repeat 0)))

(defn bucket-start [ts w] (- ts (mod ts w)))

(defn raw-window
  "[lo hi) of raw timestamps that are retained at `clock` and inside
   [start, end). Retained <=> ts + 300 > clock <=> ts >= clock - 299;
   accepted samples never exceed the clock."
  [clock start end]
  [(max start (- clock (dec RAW-RETENTION)))
   end])

(defn rollup-window
  "[lo hi) of bucket starts of width `w` that are complete and retained at
   `clock` and inside [start, end).
   complete: s + w <= clock  <=> s < clock - w + 1
   retained: s + w + 7200 > clock <=> s >= clock - w - 7199"
  [clock w start end]
  [(max start (- clock w (dec ROLLUP-RETENTION)))
   (min end (inc (- clock w)))])

(defn raw-rows [submap]
  (mapv (fn [[ts v]] {:timestamp ts :value v}) submap))

(defn bucket-rows
  "Rows for an already-sorted submap of bucket-start -> aggregates."
  [submap]
  (mapv (fn [[s agg]] (assoc agg :start s)) submap))

(defn bucket-rows-in
  "Rows for a plain (unsorted, tiny) map, keeping lo <= start < hi, ascending."
  [m lo hi]
  (->> m
       (filter (fn [[s _]] (and (>= s lo) (< s hi))))
       (sort-by key)
       (mapv (fn [[s agg]] (assoc agg :start s)))))

(defn fold-value-into-bucket [b v]
  (if (nil? b)
    {:count 1 :sum v :min v :max v}
    {:count (inc (:count b))
     :sum   (+ (:sum b) v)
     :min   (min (:min b) v)
     :max   (max (:max b) v)}))

(defn fold-into-map [m start v]
  (update (or m {}) start fold-value-into-bucket v))

(defn prune-buckets
  "Drop entries whose start is below `cutoff` (start < cutoff <=> expired)."
  [m cutoff]
  (into {} (filter (fn [[s _]] (>= s cutoff)) m)))

(def +fold-bucket
  (accumulator (fn [*v] (term (fn [*b] (fold-value-into-bucket *b *v))))))

;;; ---------------------------------------------------------------------------
;;; Module

(defmodule MetricsPipelineModule [setup topologies]
  (declare-depot setup *series-events (hash-by :series))

  (let [mb (microbatch-topology topologies "metrics")]
    (declare-pstate mb $$series
      {SeriesKey
       (fixed-keys-schema
        {:clock              Long
         :accepted           Long
         :rejected-future    Long
         :rejected-expired   Long
         :rejected-duplicate Long
         ;; retained raw samples: timestamp -> value (<= 300 live)
         :raw                (map-schema Long Long
                                         {:subindex-options {:track-size? false}})
         ;; 60-wide buckets: bucket-start -> aggregates (<= 121 live)
         :buckets-60         (map-schema Long
                                         (fixed-keys-schema
                                          {:count Long :sum Long :min Long :max Long})
                                         {:subindex-options {:track-size? false}})
         ;; 3600-wide buckets: <= 3 live entries, one small plain value
         :buckets-3600       (map-schema Long
                                         (fixed-keys-schema
                                          {:count Long :sum Long :min Long :max Long}))})})

    (<<sources mb
      (source> *series-events :> %microbatch)
      (%microbatch :> *event)
      (<<subsource *event
        (case> AdvanceClock :> {:keys [*series *clock]})
        (local-select> [(keypath *series :clock) (nil->val 0)] $$series :> *cur)
        (<<if (> *clock *cur)
          (local-transform> [(keypath *series :clock) (termval *clock)] $$series)
          (anchor> <advanced>)
          ;; raw expiry: ts + 300 <= clock  <=> ts < clock - 299
          (<<branch <advanced>
            (local-select> [(keypath *series :raw)
                            (sorted-map-range-to (- *clock (dec RAW-RETENTION)))
                            (subselect MAP-KEYS)]
                           $$series :> *expired-ts)
            (ops/explode *expired-ts :> *ts)
            (local-transform> [(keypath *series :raw *ts) NONE>] $$series))
          ;; 60-bucket expiry: s + 60 + 7200 <= clock  <=> s < clock - 7259
          (<<branch <advanced>
            (local-select> [(keypath *series :buckets-60)
                            (sorted-map-range-to (- *clock 60 (dec ROLLUP-RETENTION)))
                            (subselect MAP-KEYS)]
                           $$series :> *expired-starts)
            (ops/explode *expired-starts :> *s)
            (local-transform> [(keypath *series :buckets-60 *s) NONE>] $$series))
          ;; 3600-bucket expiry: s < clock - 3600 - 7199, tiny plain map
          (<<branch <advanced>
            (local-select> [(keypath *series :buckets-3600)] $$series :> *m3600)
            (prune-buckets *m3600 (- *clock 3600 (dec ROLLUP-RETENTION)) :> *pruned)
            (local-transform> [(keypath *series :buckets-3600) (termval *pruned)]
                              $$series)))

        (case> IngestSample :> {:keys [*series *timestamp *value]})
        (local-select> [(keypath *series :clock) (nil->val 0)] $$series :> *clock)
        (<<cond
          (case> (> *timestamp *clock))
          (identity :rejected-future :> *outcome)

          (case> (<= *timestamp (- *clock RAW-RETENTION)))
          (identity :rejected-expired :> *outcome)

          (default>)
          (local-select> [(keypath *series :raw *timestamp)] $$series :> *existing)
          (<<if (some? *existing)
            (identity :rejected-duplicate :> *outcome)
            (else>)
            (identity :accepted :> *outcome)
            (local-transform> [(keypath *series :raw *timestamp) (termval *value)]
                              $$series)
            (bucket-start *timestamp 60 :> *b60)
            (bucket-start *timestamp 3600 :> *b3600)
            (+compound $$series {*series {:buckets-60 {*b60 (+fold-bucket *value)}}})
            (local-select> [(keypath *series :buckets-3600)] $$series :> *m3600)
            (fold-into-map *m3600 *b3600 *value :> *m3600')
            (local-transform> [(keypath *series :buckets-3600) (termval *m3600')]
                              $$series)))
        (local-transform> [(keypath *series *outcome) (nil->val 0) (term inc)]
                          $$series))))

  (<<query-topology topologies "query-raw"
    [*series *start *end :> *result]
    (|hash *series)
    (local-select> [(keypath *series :clock) (nil->val 0)] $$series :> *clock)
    (raw-window *clock *start *end :> [*lo *hi])
    (<<if (< *lo *hi)
      (local-select> [(keypath *series :raw) (sorted-map-range *lo *hi)]
                     $$series {:allow-yield? true} :> *submap)
      (raw-rows *submap :> *result)
      (else>)
      (identity [] :> *result))
    (|origin))

  (<<query-topology topologies "query-rollup"
    [*series *width *start *end :> *result]
    (|hash *series)
    (local-select> [(keypath *series :clock) (nil->val 0)] $$series :> *clock)
    (rollup-window *clock *width *start *end :> [*lo *hi])
    (<<cond
      (case> (>= *lo *hi))
      (identity [] :> *result)

      (case> (= *width 60))
      (local-select> [(keypath *series :buckets-60) (sorted-map-range *lo *hi)]
                     $$series {:allow-yield? true} :> *submap)
      (bucket-rows *submap :> *result)

      (default>)
      (local-select> [(keypath *series :buckets-3600)] $$series :> *m3600)
      (bucket-rows-in *m3600 *lo *hi :> *result))
    (|origin)))

;;; ---------------------------------------------------------------------------
;;; Foreign client

(defn make-client
  "`append-counts` is an atom of IPC instance -> appends issued through any
   wrapper built by the same `create-module` result. The microbatch
   processed count is module-cumulative, so every wrapper of the same
   cluster must wait for the shared total, not its own count. Transient
   synchronization state only; never consulted by a read."
  [ipc append-counts]
  (let [module-name (get-module-name MetricsPipelineModule)
        depot       (foreign-depot ipc module-name "*series-events")
        series-ps   (foreign-pstate ipc module-name "$$series")
        raw-q       (foreign-query ipc module-name "query-raw")
        rollup-q    (foreign-query ipc module-name "query-rollup")
        append!     (fn [record]
                      (swap! append-counts update ipc (fnil inc 0))
                      (foreign-append! depot record :append-ack))]
    (reify
      p/MetricsPipeline
      (advance-clock! [_ tenant metric labels clock]
        (append! (->AdvanceClock (series-key tenant metric labels) clock)))
      (ingest-sample! [_ tenant metric labels timestamp value]
        (append! (->IngestSample (series-key tenant metric labels) timestamp value)))
      (get-series-info [_ tenant metric labels]
        ;; submap on a fixed-keys record yields explicit nils for fields never
        ;; written; only non-nil values may override the zero defaults.
        (reduce-kv (fn [m k v] (if (some? v) (assoc m k v) m))
                   ZERO-INFO
                   (foreign-select-one [(keypath (series-key tenant metric labels))
                                        (submap INFO-KEYS)]
                                       series-ps)))
      (query-raw [_ tenant metric labels start end]
        (foreign-invoke-query raw-q (series-key tenant metric labels) start end))
      (query-rollup [_ tenant metric labels width start end]
        (foreign-invoke-query rollup-q (series-key tenant metric labels) width start end))

      harness/Synchronizable
      (wait-for-processing! [_]
        (rtest/wait-for-microbatch-processed-count
         ipc module-name "metrics" (get @append-counts ipc 0))))))

(defn create-module []
  ;; One counter registry per create-module result (not a process-wide
  ;; defonce): closed IPC instances are released with the result instead of
  ;; being retained for the JVM's lifetime, and wrappers of different IPCs
  ;; built from one result still keep independent counts.
  (let [append-counts (atom {})]
    {:module      MetricsPipelineModule
     :wrap-client (fn [ipc] (make-client ipc append-counts))}))
