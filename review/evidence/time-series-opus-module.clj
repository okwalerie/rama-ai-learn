;; IMPORTANT: Before modifying this file, re-read PLAN.md and check pending todos.
;; Adhere to all previously decided design decisions.

(ns time-series-module-hard.module
  (:require
   [com.rpl.rama :refer :all]
   [com.rpl.rama.path :refer :all]
   [com.rpl.rama.aggs :as aggs]
   [com.rpl.rama.ops :as ops]
   [com.rpl.rama.test :as rtest]
   [rama-challenges.harness :as harness]
   [time-series-module-hard.protocol :as proto]))

;; ---------------------------------------------------------------------------
;; Constants fixed by PLAN.md
;; ---------------------------------------------------------------------------

(def MINUTE-MILLIS 60000)

;; Rollup branching factor B = 64 = 2^LEVEL-BITS. A power of two so that the
;; level-k bucket index is an arithmetic shift, which is an exact floor for
;; negative minutes (pre-epoch timestamps) and cannot overflow.
(def LEVEL-BITS 6)

;; Levels are 0..MAX-LEVEL; widths 1, 64, 4096, 262144 minutes.
(def MAX-LEVEL 3)

(def LEVELS [0 1 2 3])

(def EMPTY-STATS
  {:cardinality        0
   :total              0
   :min-latency-millis nil
   :max-latency-millis nil})

;; ---------------------------------------------------------------------------
;; Pure arithmetic helpers
;; ---------------------------------------------------------------------------

(defn minute-of
  "Minute bucket index containing `timestamp-millis`. Floors (not truncates
  toward zero) so pre-epoch timestamps land in the correct bucket."
  [timestamp-millis]
  (Math/floorDiv (long timestamp-millis) (long MINUTE-MILLIS)))

(defn bucket-of
  "Level-`level` bucket index of minute `minute`. An arithmetic shift right is
  an exact floor division by 64^level for negative minutes as well."
  [minute level]
  (bit-shift-right (long minute) (int (* LEVEL-BITS (long level)))))

(defn round-down
  "Largest multiple of 2^s that is <= x. Always <= x, including for negative x
  (`quot` would truncate toward zero and round negatives UP)."
  [x s]
  (bit-shift-left (bit-shift-right (long x) (int s)) (int s)))

(defn round-up
  "Smallest multiple of 2^s that is >= x."
  [x s]
  (- (round-down (- (long x)) s)))

(defn scan-specs
  "Decomposes the half-open minute range [start, end), clipped to the URL's
  occupied envelope, into at most 2*MAX-LEVEL+1 aligned rollup scans.

  `bounds` is the {:min-minute _ :max-minute _} projection of the URL's record;
  it may be nil, or a map whose fields are nil, when the URL has no data.

  Returns a vector of [level bucket-lo bucket-hi] triples, each describing a
  half-open, non-empty, contiguous run of buckets at that level. Every minute of
  the clipped range is covered by exactly one emitted (level, bucket), so no
  measurement is double-counted or dropped. Returns [] when the clipped range is
  empty — which covers start == end, start > end, an unknown URL, and a range
  lying entirely outside the URL's data — so that no scan is ever dispatched
  that is empty by construction."
  [start end bounds]
  (let [min-minute (:min-minute bounds)
        max-minute (:max-minute bounds)]
    (if (or (nil? min-minute) (nil? max-minute))
      []
      ;; Clip BEFORE any rounding: this bounds |lo| and |hi| by the minute index
      ;; of a 64-bit millisecond timestamp (~1.5e14), so round-up cannot overflow
      ;; even when the caller passes end = Long/MAX_VALUE to mean "all time".
      (let [lo (max (long start) (long min-minute))
            hi (min (long end) (inc (long max-minute)))]
        (loop [level 0
               lo    lo
               hi    hi
               acc   []]
          (cond
            (>= lo hi)
            acc

            (>= level MAX-LEVEL)
            (conj acc [(long level)
                       (bucket-of lo level)
                       (bucket-of hi level)])

            :else
            (let [next-shift (int (* LEVEL-BITS (inc level)))
                  lo2        (min hi (round-up lo next-shift))
                  hi2        (max lo2 (round-down hi next-shift))
                  acc        (cond-> acc
                               (> lo2 lo)
                               (conj [(long level)
                                      (bucket-of lo level)
                                      (bucket-of lo2 level)])

                               (> hi hi2)
                               (conj [(long level)
                                      (bucket-of hi2 level)
                                      (bucket-of hi level)]))]
              (recur (inc level) lo2 hi2 acc))))))))

(defn reduce-scan-stats
  "Folds the stats maps of one scan into a single partial.

  Returns [] when the scan navigated to no buckets, and [[cardinality total
  min-latency max-latency]] otherwise. Emitting zero rows rather than a row of
  nils keeps nil out of the +min/+max folds entirely."
  [stats-vals]
  (if (empty? stats-vals)
    []
    (loop [remaining stats-vals
           card      0
           total     0
           min-l     nil
           max-l     nil]
      (if-let [s (first remaining)]
        (let [c (long (:cardinality s))
              v-min (:min-latency-millis s)
              v-max (:max-latency-millis s)]
          (recur (rest remaining)
                 (+ card c)
                 (+ total (long (:total s)))
                 (if (or (nil? min-l) (and (some? v-min) (< (long v-min) (long min-l))))
                   v-min
                   min-l)
                 (if (or (nil? max-l) (and (some? v-max) (> (long v-max) (long max-l))))
                   v-max
                   max-l)))
        [[card total min-l max-l]]))))

(defn build-stats
  "Assembles the protocol result. Branches on cardinality alone, never on total
  or on the truthiness of min/max, so a range whose measurements all have
  render-millis 0 is NOT reported as the empty result."
  [cardinality total min-latency max-latency]
  (if (zero? (long cardinality))
    EMPTY-STATS
    {:cardinality        (long cardinality)
     :total              (long total)
     :min-latency-millis min-latency
     :max-latency-millis max-latency}))

;; ---------------------------------------------------------------------------
;; Module
;; ---------------------------------------------------------------------------

(defmodule TimeSeriesModule [setup topologies]
  ;; :random rather than (hash-by :url): hash-by would place a hot URL's entire
  ;; durable append stream on one depot partition, and durability happens before
  ;; any aggregation, so no in-topology mechanism could rebalance it. Local
  ;; ordering is worth nothing here — all four aggregates are commutative and
  ;; associative.
  (declare-depot setup *latency-depot :random)

  (let [mb (microbatch-topology topologies "stats")]
    ;; Microbatch, not stream: :cardinality is a count and :total a sum, both
    ;; non-idempotent, so a stream retry would silently inflate them while
    ;; min/max stayed correct. Microbatch gives exactly-once PState updates
    ;; across retries of the same microbatch id.
    (declare-pstate mb $$url-stats
      {String (fixed-keys-schema
                {:min-minute Long
                 :max-minute Long
                 ;; Exactly MAX-LEVEL+1 entries, fixed by LEVELS in code, so NOT
                 ;; subindexed. Its values ARE subindexed: a continuously-active
                 ;; URL accumulates ~525,600 level-0 entries per year, and
                 ;; subindexed maps are sorted, which is what makes each level's
                 ;; contiguous sibling run a single seek + sequential scan.
                 :levels (map-schema Long
                           (map-schema Long
                             (fixed-keys-schema
                               {:cardinality        Long
                                :total              Long
                                :min-latency-millis Long
                                :max-latency-millis Long})
                             {:subindex? true}))})})

    (<<sources mb
      (source> *latency-depot :> %mb)
      (<<batch
        (%mb :> {:keys [*url *render-millis *timestamp-millis]})
        (minute-of *timestamp-millis :> *minute)
        (ops/explode LEVELS :> *level)
        (bucket-of *minute *level :> *bucket)
        ;; Every leaf below is a combiner, so the batch block pre-aggregates on
        ;; the source side and only reduced partials cross this partitioner.
        (|hash *url)
        (+compound $$url-stats
          {*url {:min-minute (aggs/+min *minute)
                 :max-minute (aggs/+max *minute)
                 :levels {*level
                          {*bucket
                           {:cardinality        (aggs/+count)
                            :total              (aggs/+sum *render-millis)
                            :min-latency-millis (aggs/+min *render-millis)
                            :max-latency-millis (aggs/+max *render-millis)}}}}}))))

  ;; One invocation, one network round trip, independent of range size.
  (<<query-topology topologies "range-stats" [*url *start *end :> *stats]
    ;; Leading built-in single-task partitioner over topology input vars: it is
    ;; evaluated client-side and routes straight to the task owning *url.
    (|hash *url)
    ;; Free in disk terms — the URL's top-level value must be read anyway to
    ;; reach the subindexed level structures. Projects only the two envelope
    ;; fields, never :levels, whose values are RocksDB handles that cannot cross
    ;; a serialization boundary.
    (local-select> [(keypath *url) (submap [:min-minute :max-minute])]
                   $$url-stats
                   :> *bounds)
    (scan-specs *start *end *bounds :> *specs)
    (ops/explode *specs :> [*level *blo *bhi])
    ;; 1 seek + sequential iteration per level. :allow-yield? so a wide scan
    ;; cannot monopolize the task and spike concurrent ingestion latency.
    (local-select> [(keypath *url :levels *level)
                    (sorted-map-range *blo *bhi) (subselect MAP-VALS)]
                   $$url-stats
                   {:allow-yield? true}
                   :> *stats-vals)
    (reduce-scan-stats *stats-vals :> *partials)
    (ops/explode *partials :> [*c *t *mn *mx])
    (|origin)
    (aggs/+sum *c :> *card)
    (aggs/+sum *t :> *total)
    (aggs/+min *mn :> *min-latency)
    (aggs/+max *mx :> *max-latency)
    (build-stats *card *total *min-latency *max-latency :> *stats)))

(def module-name* (get-module-name TimeSeriesModule))

;; ---------------------------------------------------------------------------
;; Foreign client
;; ---------------------------------------------------------------------------

(defn create-module []
  {:module TimeSeriesModule
   :wrap-client
   (fn [ipc]
     (let [depot        (foreign-depot ipc module-name* "*latency-depot")
           range-query  (foreign-query ipc module-name* "range-stats")
           ;; Test-support only: cumulative count of appends this wrapper has
           ;; issued, for wait-for-processing!. Not production state.
           append-count (atom 0)]
       (reify
         proto/TimeSeriesModuleHard
         (record-latency! [_ url render-millis timestamp-millis]
           ;; Exactly one depot append per client write operation. :append-ack
           ;; makes the record durable and replicated before this returns.
           (foreign-append! depot
                            {:url              url
                             :render-millis    (long render-millis)
                             :timestamp-millis (long timestamp-millis)}
                            :append-ack)
           ;; Incremented only after a successful append — a pre-increment would
           ;; make wait-for-processing! block forever if the append threw.
           (swap! append-count inc)
           nil)

         (get-stats-for-range [_ url start-minute-bucket end-minute-bucket]
           (foreign-invoke-query range-query
                                 url
                                 (long start-minute-bucket)
                                 (long end-minute-bucket)))

         harness/Synchronizable
         (wait-for-processing! [_]
           ;; One topology consuming one client-appendable depot, so this covers
           ;; every ingestion path.
           (rtest/wait-for-microbatch-processed-count
             ipc module-name* "stats" @append-count)))))})
