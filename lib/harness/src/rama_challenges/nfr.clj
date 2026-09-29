(ns rama-challenges.nfr
  "Shared helpers for non-functional-requirement (NFR) tests.

   Every helper observes the module through `rtest/with-event-hook`, so it
   depends on neither PState names nor depot layout. The hook reports
   operation counts, never value sizes: to catch a growing collection stored
   as one value, assert that a range read uses iterators
   (`capture-iterator-reads`), not that it reads few bytes.

   Size-independent budgets: compare cost at N against cost at k*N instead
   of fixing an absolute number wherever layout is left to the solver."
  (:require
   [com.rpl.rama.test :as rtest]))

(defn internal-pstate?
  "True when a RocksDB event concerns a Rama-internal PState ($$__...)."
  [{:keys [name]}]
  (and (string? name) (.startsWith ^String name "$$__")))

(def zero-ops
  {:reads 0 :iterators 0 :iterator-reads 0 :writes 0})

(defn record-op
  "Folds one hook event into an ops map. Returns ops unchanged when the event
   is not a RocksDB operation on a non-internal PState. :writes sums the
   `:write-batch-count` of every commit."
  [ops event-type data]
  (if (internal-pstate? data)
    ops
    (case event-type
      :rocks-read          (update ops :reads inc)
      :rocks-iterator      (update ops :iterators inc)
      :rocks-iterator-read (update ops :iterator-reads inc)
      :rocks-commit        (update ops :writes + (long (or (:write-batch-count data) 0)))
      ops)))

(defn capture-rocks-ops
  "Runs f and returns RocksDB operation counts on the module's own PStates:
   {:reads n :iterators n :iterator-reads n :writes n}."
  [f]
  (let [state (atom zero-ops)]
    (rtest/with-event-hook
      (fn [event-type data]
        (swap! state record-op event-type data)
        nil)
      (f))
    @state))

(defn capture-rocks-ops-with-result
  "Like capture-rocks-ops but returns [result-of-f ops]."
  [f]
  (let [state (atom zero-ops)
        ret (rtest/with-event-hook
              (fn [event-type data]
                (swap! state record-op event-type data)
                nil)
              (f))]
    [ret @state]))

(defn capture-iterator-reads
  "Runs f and returns the number of :rocks-iterator-read events on the
   module's own PStates. A positive count proves the read walked a
   subindexed (or top-level) structure rather than loading one value."
  [f]
  (:iterator-reads (capture-rocks-ops f)))

(defn capture-per-task-ops
  "Runs f and returns RocksDB operation counts keyed by the event's :task-id:
   {task-id {:reads n :iterators n :iterator-reads n :writes n}}. Events
   without a :task-id are ignored."
  [f]
  (let [state (atom {})]
    (rtest/with-event-hook
      (fn [event-type data]
        (when (and (#{:rocks-read :rocks-iterator :rocks-iterator-read :rocks-commit}
                    event-type)
                   (some? (:task-id data))
                   (not (internal-pstate? data)))
          (swap! state update (:task-id data)
                 (fn [ops] (record-op (or ops zero-ops) event-type data))))
        nil)
      (f))
    @state))

(defn capture-events
  "Runs f and returns [result-of-f events], where events is a vector of
   {:event-type t & data} in hook order, for events satisfying (pred t data).
   pred defaults to accepting everything."
  ([f] (capture-events (constantly true) f))
  ([pred f]
   (let [events (atom [])
         ret (rtest/with-event-hook
               (fn [event-type data]
                 (when (pred event-type data)
                   (swap! events conj (assoc (into {} data) :event-type event-type)))
                 nil)
               (f))]
     [ret @events])))

(defn topology-types-used
  "Runs f and returns the set of topology types (:stream, :microbatch, ...)
   that emitted a :topology-event while f ran. Microbatch topologies emit
   events in the background whenever they poll, so assert membership
   (e.g. `(contains? types :stream)`), never set equality."
  [f]
  (let [types (atom #{})]
    (rtest/with-event-hook
      (fn [event-type data]
        (when (= event-type :topology-event)
          (swap! types conj (:type data)))
        nil)
      (f))
    @types))

(defn allow-yield-used?
  "Runs f and returns true when any :local-select ran with :allow-yield? set.
   Foreign selects may also set it, so measure topology work (e.g. a tick or
   expiry sweep), not client queries."
  [f]
  (let [seen (atom false)]
    (rtest/with-event-hook
      (fn [event-type data]
        (when (and (= event-type :local-select) (:allow-yield? data))
          (reset! seen true))
        nil)
      (f))
    @seen))

(defn with-forced-stream-retry
  "Runs f, failing the first `n` (default 1) :streaming-complete events so the
   stream topology retries that processing. Returns [result-of-f failures],
   where failures is how many retries were forced.

   Topology-neutral: a microbatch design emits no :streaming-complete, so it
   sees zero forced failures and must still produce exactly-once effects.
   A stream design must be retry-safe (idempotent) to pass."
  ([f] (with-forced-stream-retry 1 f))
  ([n f]
   (let [forced (atom 0)
         ret (rtest/with-event-hook
               (fn [event-type _data]
                 (when (and (= event-type :streaming-complete)
                            (let [[old _] (swap-vals! forced
                                                      #(if (< % n) (inc %) %))]
                              (< old n)))
                   :fail))
               (f))]
     [ret @forced])))

(defn failed-streaming
  "Gold-standard shape: runs f forcing exactly one stream retry, returns f's
   result."
  [f]
  (first (with-forced-stream-retry 1 f)))

(defn task-spread
  "Summarises per-task totals of metric k from capture-per-task-ops output.
   Returns {:tasks n :max m :min m :total t :max-share s} where max-share is
   the largest task's fraction of the total (0 when total is 0). `expected-
   tasks` fills absent tasks with 0."
  [per-task k expected-tasks]
  (let [vals (map #(get-in per-task [% k] 0) (range expected-tasks))
        total (reduce + vals)
        mx (if (seq vals) (apply max vals) 0)
        mn (if (seq vals) (apply min vals) 0)]
    {:tasks expected-tasks :max mx :min mn :total total
     :max-share (if (pos? total) (/ (double mx) total) 0.0)}))

(defn gen-task-keys
  "Returns one key per task such that `rtest/gen-hashing-index-keys` hashes
   key i to task i for a module with `task-count` tasks."
  [task-count]
  (vec (rtest/gen-hashing-index-keys task-count)))
