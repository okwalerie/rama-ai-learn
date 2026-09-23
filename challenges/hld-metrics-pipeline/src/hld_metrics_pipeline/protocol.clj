(ns hld-metrics-pipeline.protocol
  "Protocol definition for the hld-metrics-pipeline challenge.

   A series is identified structurally by [tenant metric labels]. `tenant`
   and `metric` are non-empty strings; `labels` is a map of non-empty string
   keys to string values (possibly empty). Two calls address the same series
   iff their tenant, metric, and label maps are equal.

   Every series has its own logical clock, initially 0. All timestamps,
   clocks, and sample values are integers. See README.md for the full
   admission, retention, and rollup rules with worked numbers.")

(defprotocol MetricsPipeline
  "Per-series metrics ingestion with raw retention and 60/3600 rollups."

  (advance-clock! [this tenant metric labels clock]
    "Write. Advance the series clock to `clock` (integer >= 0).
     The clock is monotonic: if `clock` <= the current clock this is a no-op.
     Advancing the clock is what expires data:
       - a raw sample with timestamp ts is retained while ts + 300 > clock
       - a rollup bucket [start, start+width) is retained while
         start + width + 7200 > clock
     Expiring raw samples never changes rollup aggregates.")

  (ingest-sample! [this tenant metric labels timestamp value]
    "Write. Offer one sample (integer `timestamp` >= 0, integer `value`) to
     the series. Let C be the series clock when the write is applied. The
     sample is checked in this order:
       1. timestamp > C            -> rejected as future
       2. timestamp + 300 <= C     -> rejected as expired
       3. timestamp already accepted on this series -> rejected as duplicate
       4. otherwise accepted
     Accepted samples are stored raw and folded into the 60-wide and
     3600-wide rollup buckets containing `timestamp`. The first accepted
     sample for a timestamp wins; a rejected sample does not reserve its
     timestamp (a later admissible sample with the same timestamp is
     accepted).")

  (get-series-info [this tenant metric labels]
    "Read. Returns
       {:clock <int> :accepted <int> :rejected-future <int>
        :rejected-expired <int> :rejected-duplicate <int>}
     for the series. Counters are cumulative over all ingest-sample! calls
     ever applied to the series (expiration does not decrement :accepted).
     For a series that has never been written, all values are 0.")

  (query-raw [this tenant metric labels start end]
    "Read. Returns a vector of {:timestamp <int> :value <int>} for every
     retained raw sample of the series with start <= timestamp < end,
     ascending by :timestamp. Expired samples are never returned.
     Empty vector when nothing matches or the series is unknown.")

  (query-rollup [this tenant metric labels width start end]
    "Read. `width` is 60 or 3600. `start` and `end` are multiples of
     `width` with start <= end. Returns a vector of
       {:start <int> :count <int> :sum <int> :min <int> :max <int>}
     for every bucket of that width whose start satisfies
     start <= bucket-start < end and which is
       - complete: bucket-start + width <= current series clock, and
       - retained: bucket-start + width + 7200 > current series clock, and
       - non-empty: at least one sample was accepted into it.
     Ascending by :start. Aggregates reflect every sample ever accepted into
     the bucket, including samples whose raw copy has since expired, and
     including samples accepted after the bucket became complete."))
