;; IMPORTANT: Before modifying this file, re-read test-resources/development/PLAN.md
;; and check pending todos. Adhere to all previously decided design decisions.

(ns hld-ad-click-aggregation.module
  "Reference implementation for the hld-ad-click-aggregation challenge.

   One depot (hashed by campaign), one microbatch topology owning one PState
   keyed by campaign-id, and one query topology serving both window reads.
   See test-resources/development/PLAN.md for the design rationale."
  (:require
   [com.rpl.rama :refer :all]
   [com.rpl.rama.path :refer :all]
   [com.rpl.rama.aggs :as aggs]
   [com.rpl.rama.ops :as ops]
   [com.rpl.rama.test :as rtest]
   [rama-challenges.harness :as harness]
   [hld-ad-click-aggregation.protocol :as p]))

;;; ---------------------------------------------------------------------------
;;; Depot records and the typed breakdown key

(defrecord AdvanceWatermark [campaign-id watermark])
(defrecord RecordClick [campaign-id request-id timestamp geo device spend valid? fraud?])
(defrecord GeoDevice [geo device])

;;; ---------------------------------------------------------------------------
;;; Domain constants and pure helpers (plain Clojure, called from dataflow)

(def WINDOW-SIZE 60)
(def LATENESS-ALLOWANCE 120)

(defn window-start [ts] (- ts (mod ts WINDOW-SIZE)))

(defn single-window-end
  "Exclusive end of the one-window range [ws, ws + 60) used by `get-window`.
   Near Long/MAX_VALUE the addition would overflow; clamping the end to
   Long/MAX_VALUE is exact there because no timestamp exceeds
   Long/MAX_VALUE - 180, so no window can start above `ws` in that range."
  [ws]
  (if (> ws (- Long/MAX_VALUE WINDOW-SIZE))
    Long/MAX_VALUE
    (+ ws WINDOW-SIZE)))

(defn disposition
  "Ordered rules against the watermark `wm` observed at apply time and the
   click's window start `ws` (window end E = ws + 60; closed iff wm >= E + 120)."
  [wm ws valid? fraud?]
  (cond
    (>= wm (+ ws WINDOW-SIZE LATENESS-ALLOWANCE)) :late
    fraud?                                       :fraud
    (not valid?)                                 :invalid
    :else                                        :billed))

(defn audit-record
  [request-id timestamp ws geo device spend valid? fraud? disp wm]
  {:request-id   request-id
   :timestamp    timestamp
   :window-start ws
   :geo          geo
   :device       device
   :spend        spend
   :valid?       (boolean valid?)
   :fraud?       (boolean fraud?)
   :disposition  disp
   :watermark    wm})

(def ZERO-COUNTERS
  {:clicks 0 :billed-clicks 0 :invalid-clicks 0 :fraud-clicks 0 :billed-spend 0})

(def NO-COUNT-ROW
  "Aggregation row for events that touch no window (watermark advances,
   replays, late clicks): [window-start geo-device counters-delta]. A nil
   delta means \"not counted\"."
  [0 nil nil])

(defn count-row
  "Aggregation row for a first arrival with disposition `disp`. The delta is
   a full counters map so one combiner leaf updates all five fields in a
   single read-modify-write of the entry."
  [disp ws geo device spend]
  (case disp
    :billed  [ws (->GeoDevice geo device)
              (assoc ZERO-COUNTERS :clicks 1 :billed-clicks 1 :billed-spend spend)]
    :invalid [ws (->GeoDevice geo device) (assoc ZERO-COUNTERS :clicks 1 :invalid-clicks 1)]
    :fraud   [ws (->GeoDevice geo device) (assoc ZERO-COUNTERS :clicks 1 :fraud-clicks 1)]
    NO-COUNT-ROW))

(defn add-counters [a b] (merge-with + a b))

(def +counters
  "Combiner: key-wise sum of counters maps, initialized with every key at 0
   so a counters map always carries all five keys."
  (combiner add-counters :init-fn (fn [] ZERO-COUNTERS)))

(defn build-window
  "Protocol-shaped window map from the stored totals and the breakdown
   entries ([GeoDevice counters] pairs)."
  [ws totals entries]
  {:window-start ws
   :totals       totals
   :breakdown    (into {} (map (fn [[gd counters]] [[(:geo gd) (:device gd)] counters]))
                       entries)})

(defn sort-windows [rows] (vec (sort-by :window-start rows)))

;;; Paged range reads. On subindexed maps, `sorted-map-range` seeks to
;;; `start` but then iterates to the END OF THE MAP and filters (measured:
;;; a 3-window range cost 104 iterator reads with 100 windows above it),
;;; which violates the README bound for `get-window` / `get-windows`.
;;; `sorted-map-range-from` with `:max-amt` stops exactly, so the query
;;; reads pages of keys >= from, each page bounded by the number of 60-unit
;;; slots left in the range and by a doubling cap. Work is therefore
;;; proportional to the windows inside [start, end) plus a constant, and a
;;; single-window range (`get-window`) reads exactly one key.

(def INITIAL-PAGE 16)
(def MAX-PAGE 1024)

(defn slots-left
  "Number of 60-unit window slots in [from, end). Overflow-safe: from >= 0
   and end <= Long/MAX_VALUE, so end - from never overflows."
  [from end]
  (let [d (- end from)]
    (if (pos? d)
      (+ (quot d WINDOW-SIZE) (if (zero? (rem d WINDOW-SIZE)) 0 1))
      0)))

(defn first-page
  "Page size for the first read of [start, end): never more keys than the
   range has slots, never more than INITIAL-PAGE. At least 1 so an empty
   range still performs one bounded read."
  [start end]
  (max 1 (min INITIAL-PAGE (slots-left start end))))

(defn page-rows
  "Splits a flat page [ws totals ws totals ...] of keys >= from into
   {:rows [[ws totals] ...] :more? bool :next-from k :next-page n}. `rows`
   holds only windows below `end`. Another page is needed only when this
   page was full and its last key is still below `end`; the next page
   starts just after that key and doubles in size, bounded by the slots
   left and MAX-PAGE."
  [flat page end]
  (let [pairs (mapv vec (partition 2 flat))
        rows (into [] (take-while (fn [[ws _]] (< ws end))) pairs)
        n (count pairs)
        last-ws (when (pos? n) (first (peek pairs)))
        more? (boolean (and (= n page) last-ws (< last-ws end)))
        next-from (when more? (inc last-ws))]
    {:rows rows
     :more? more?
     :next-from (or next-from 0)
     :next-page (if more?
                  (max 1 (min MAX-PAGE (* 2 page) (slots-left next-from end)))
                  0)}))

;;; ---------------------------------------------------------------------------
;;; Module

(def counters-schema
  (fixed-keys-schema {:clicks         Long
                      :billed-clicks  Long
                      :invalid-clicks Long
                      :fraud-clicks   Long
                      :billed-spend   Long}))

(def audit-schema
  (fixed-keys-schema {:request-id   String
                      :timestamp    Long
                      :window-start Long
                      :geo          String
                      :device       String
                      :spend        Long
                      :valid?       Boolean
                      :fraud?       Boolean
                      :disposition  clojure.lang.Keyword
                      :watermark    Long}))

(defmodule AdClickAggregationModule [setup topologies]
  (declare-depot setup *campaign-events (hash-by :campaign-id))

  (let [mb (microbatch-topology topologies "click-accounting")]
    (declare-pstate mb $$campaigns
      {String
       (fixed-keys-schema
        {:watermark Long
         :requests  (map-schema String audit-schema
                                {:subindex-options {:track-size? false}})
         :windows   (map-schema Long
                                (fixed-keys-schema
                                 {:totals    counters-schema
                                  :breakdown (map-schema GeoDevice counters-schema
                                                         {:subindex-options {:track-size? false}})})
                                {:subindex-options {:track-size? false}})})})

    (<<sources mb
      (source> *campaign-events :> %microbatch)
      (%microbatch :> *event)
      (<<subsource *event
        (case> AdvanceWatermark :> {:keys [*campaign-id *watermark]})
        (local-select> [(keypath *campaign-id :watermark) (nil->val 0)] $$campaigns :> *cur)
        (<<if (> *watermark *cur)
          (local-transform> [(keypath *campaign-id :watermark) (termval *watermark)]
                            $$campaigns))
        (identity NO-COUNT-ROW :> *row)

        (case> RecordClick :> {:keys [*campaign-id *request-id *timestamp *geo *device
                                      *spend *valid? *fraud?]})
        ;; replay check first: an existing audit record freezes everything
        (local-select> [(keypath *campaign-id :requests *request-id)] $$campaigns :> *existing)
        (<<if (nil? *existing)
          (local-select> [(keypath *campaign-id :watermark) (nil->val 0)] $$campaigns :> *wm)
          (window-start *timestamp :> *win)
          (disposition *wm *win *valid? *fraud? :> *disp)
          (local-transform> [(keypath *campaign-id :requests *request-id)
                             (termval (audit-record *request-id *timestamp *win *geo *device
                                                    *spend *valid? *fraud? *disp *wm))]
                            $$campaigns)
          (count-row *disp *win *geo *device *spend :> *row)
          (else>)
          (identity NO-COUNT-ROW :> *row)))
      ;; every event kind unifies on *campaign-id and *row; only counted
      ;; first arrivals (non-nil delta) reach the single tail aggregator
      (identity *row :> [*ws *gd *delta])
      (filter> (some? *delta))
      (+compound $$campaigns
                 {*campaign-id
                  {:windows {*ws {:totals    (+counters *delta)
                                  :breakdown {*gd (+counters *delta)}}}}})))

  (<<query-topology topologies "windows-in-range"
    [*campaign-id *start *end :> *result]
    (|hash *campaign-id)
    ;; paged range read: each page is one seek + (page size) sequential
    ;; reads of populated windows >= from, with totals read inline; see the
    ;; note above `page-rows` for why `sorted-map-range` is not used
    (loop<- [*from *start *page (first-page *start *end) :> *ws *totals]
      (local-select> [(keypath *campaign-id :windows)
                      (sorted-map-range-from *from {:max-amt *page})
                      (subselect ALL (multi-path FIRST [LAST :totals]))]
                     $$campaigns {:allow-yield? true} :> *flat)
      (page-rows *flat *page *end :> {:keys [*rows *more? *next-from *next-page]})
      (<<if *more?
        (continue> *next-from *next-page))
      (ops/explode *rows :> [*ws-row *totals-row])
      (:> *ws-row *totals-row))
    ;; one seek + b iterations per emitted window; never empty because a
    ;; window exists only once a counted click created it
    (local-select> [(keypath *campaign-id :windows *ws :breakdown) (subselect ALL)]
                   $$campaigns {:allow-yield? true} :> *entries)
    (build-window *ws *totals *entries :> *window)
    (|origin)
    (aggs/+vec-agg *window :> *windows)
    (sort-windows *windows :> *result)))

;;; ---------------------------------------------------------------------------
;;; Foreign client

(defn make-client
  "`append-counts` is an atom of IPC instance -> appends issued through any
   wrapper built by the same `create-module` result. The microbatch
   processed count is module-cumulative, so every wrapper of the same
   cluster must wait for the shared total, not its own count. Transient
   synchronization state only; never consulted by a read."
  [ipc append-counts]
  (let [module-name (get-module-name AdClickAggregationModule)
        depot       (foreign-depot ipc module-name "*campaign-events")
        campaigns   (foreign-pstate ipc module-name "$$campaigns")
        windows-q   (foreign-query ipc module-name "windows-in-range")
        append!     (fn [record]
                      (swap! append-counts update ipc (fnil inc 0))
                      (foreign-append! depot record :append-ack))]
    (reify
      p/AdClickAggregation
      (advance-watermark! [_ campaign-id watermark]
        (append! (->AdvanceWatermark campaign-id (long watermark))))
      (record-click! [_ campaign-id request-id timestamp geo device spend valid? fraud?]
        (append! (->RecordClick campaign-id request-id (long timestamp) geo device
                                (long spend) (boolean valid?) (boolean fraud?))))
      (get-watermark [_ campaign-id]
        (foreign-select-one [(keypath campaign-id :watermark) (nil->val 0)] campaigns))
      (get-request [_ campaign-id request-id]
        (foreign-select-one [(keypath campaign-id :requests request-id)] campaigns))
      (get-window [_ campaign-id window-start]
        (first (foreign-invoke-query windows-q campaign-id window-start
                                     (single-window-end window-start))))
      (get-windows [_ campaign-id start end]
        (foreign-invoke-query windows-q campaign-id start end))

      harness/Synchronizable
      (wait-for-processing! [_]
        (rtest/wait-for-microbatch-processed-count
         ipc module-name "click-accounting" (get @append-counts ipc 0))))))

(defn create-module []
  ;; One counter registry per create-module result (not a process-wide
  ;; defonce): closed IPC instances are released with the result instead of
  ;; being retained for the JVM's lifetime, and wrappers of different IPCs
  ;; built from one result still keep independent counts.
  (let [append-counts (atom {})]
    {:module      AdClickAggregationModule
     :wrap-client (fn [ipc] (make-client ipc append-counts))}))
