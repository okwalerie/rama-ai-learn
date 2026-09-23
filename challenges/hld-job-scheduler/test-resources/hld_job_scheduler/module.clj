;; IMPORTANT: Before modifying this file, re-read
;; test-resources/development/PLAN-execution-lifecycle.md (and the plans of any
;; later subsystem that has been built) and check pending todos. Adhere to all
;; previously decided design decisions.

(ns hld-job-scheduler.module
  "Reference implementation for the hld-job-scheduler challenge.

   Subsystem 1 (execution-lifecycle): one client-appendable depot hashed by
   execution-id, one microbatch topology `lifecycle`, one PState
   `$$executions` holding the validated immutable DAG and the logical clock
   of each execution. See test-resources/development/PLAN-execution-lifecycle.md.

   Later subsystems (claims-and-leases, completion-and-reads) are not built
   yet: their protocol methods throw an explicit not-yet-implemented error."
  (:require
   [com.rpl.rama :refer :all]
   [com.rpl.rama.path :refer :all]
   [com.rpl.rama.test :as rtest]
   [rama-challenges.harness :as harness]
   [hld-job-scheduler.protocol :as p]))

;;; ---------------------------------------------------------------------------
;;; Depot records

(defrecord SubmitExecution [execution-id dag])
(defrecord AdvanceClock [execution-id clock])

;;; ---------------------------------------------------------------------------
;;; Domain constants and pure helpers (plain Clojure, called from dataflow).
;;; Every helper called from the topology MUST be total: a record that throws
;;; deterministically retries the microbatch forever and blocks the module.

(def MAX-NODES 32)

(defn- acyclic?
  "Kahn's algorithm over `dag` ({node #{dep ...}}), whose references are
   already known to resolve. True iff every node is consumed, i.e. there is
   no cycle (a self-edge keeps a node's in-degree above 0 forever)."
  [dag]
  (let [nodes     (keys dag)
        dependents (reduce (fn [m [n deps]]
                             (reduce (fn [m d] (update m d (fnil conj []) n)) m deps))
                           {}
                           dag)]
    (loop [in-degree (into {} (map (fn [[n deps]] [n (count deps)])) dag)
           queue     (into clojure.lang.PersistentQueue/EMPTY
                           (filter #(zero? (in-degree %)) nodes))
           consumed  0]
      (if-let [n (peek queue)]
        (let [[in-degree queue]
              (reduce (fn [[in-degree queue] m]
                        (let [k (dec (in-degree m))]
                          [(assoc in-degree m k)
                           (if (zero? k) (conj queue m) queue)]))
                      [in-degree (pop queue)]
                      (dependents n))]
          (recur in-degree queue (inc consumed)))
        (= consumed (count dag))))))

(defn valid-dag?
  "True iff `dag` is a map of 1..32 entries whose keys are strings, whose
   values are collections of strings, every referenced id is a key, and the
   graph is acyclic. Never throws.

   String checks are a robustness guard beyond the README's rejection list
   (README 'Input assumptions' promises string ids): a non-string id would
   fail the PState schema at write time and block the topology forever, so it
   is rejected up front instead. Values are accepted as any collection
   (set, vector, list) and normalized to sets by `normalize-dag`."
  [dag]
  (boolean
   (and (map? dag)
        (<= 1 (count dag) MAX-NODES)
        (every? string? (keys dag))
        (every? (fn [v] (and (coll? v) (not (map? v)) (every? string? v)))
                (vals dag))
        (let [ids (set (keys dag))]
          (every? (fn [v] (every? ids v)) (vals dag)))
        (acyclic? dag))))

(defn normalize-dag
  "The stored form: dependency values coerced to sets."
  [dag]
  (into {} (map (fn [[k v]] [k (set v)])) dag))

(defn valid-clock?
  "True iff `clock` is a fixed-precision integer (fits a Long). Guards the
   `Long` schema position and the `long` coercion in the topology; anything
   else is ignored rather than allowed to throw."
  [clock]
  (int? clock))

;;; ---------------------------------------------------------------------------
;;; Module

(defmodule JobSchedulerModule [setup topologies]
  (declare-depot setup *execution-events (hash-by :execution-id))

  (let [mb (microbatch-topology topologies "lifecycle")]
    (declare-pstate mb $$executions
      {String (fixed-keys-schema
               {;; validated immutable DAG: node-id -> set of dependency ids.
                ;; Not subindexed: valid-dag? caps it at 32 entries.
                :dag   {String (set-schema String)}
                ;; logical clock, initially 0
                :clock Long})})

    (<<sources mb
      (source> *execution-events :> %microbatch)
      (%microbatch :> *event)
      (<<subsource *event
        (case> SubmitExecution :> {:keys [*execution-id *dag]})
        ;; validation is pure CPU and precedes any disk access
        (filter> (valid-dag? *dag))
        (normalize-dag *dag :> *ndag)
        ;; one read of one key: the existence guard runs inside the path on
        ;; the record the transform has loaded. Field-scoped write so other
        ;; fields ever stored at this key are untouched.
        (local-transform> [(keypath *execution-id)
                           (not-selected? :dag some?)
                           (multi-path [:dag (termval *ndag)]
                                       [:clock (termval 0)])]
                          $$executions)

        (case> AdvanceClock :> {:keys [*execution-id *clock]})
        (filter> (valid-clock? *clock))
        (long *clock :> *c)
        ;; exists (:dag present) and strictly monotonic; :clock is always
        ;; written together with :dag so it is never nil when :dag is present
        (local-transform> [(keypath *execution-id)
                           (selected? :dag some?)
                           (selected? :clock (pred< *c))
                           :clock (termval *c)]
                          $$executions)))))

;;; ---------------------------------------------------------------------------
;;; Foreign client

(defn- not-yet-implemented [method]
  (throw (ex-info (str method " is not implemented yet: it belongs to a later subsystem")
                  {:method method :subsystem-built :execution-lifecycle})))

(defn make-client
  "`append-counts` is an atom of IPC instance -> appends issued through any
   wrapper built by the same `create-module` result. The microbatch
   processed count is module-cumulative, so every wrapper of the same
   cluster must wait for the shared total, not its own count. Transient
   synchronization state only; never consulted by a read."
  [ipc append-counts]
  (let [module-name (get-module-name JobSchedulerModule)
        depot       (foreign-depot ipc module-name "*execution-events")
        append!     (fn [record]
                      (swap! append-counts update ipc (fnil inc 0))
                      (foreign-append! depot record :append-ack))]
    (reify
      p/JobScheduler
      (submit-execution! [_ execution-id dag]
        (append! (->SubmitExecution execution-id dag)))
      (advance-clock! [_ execution-id clock]
        (append! (->AdvanceClock execution-id clock)))
      (claim! [_ _execution-id _node-id _worker-id _claim-id]
        (not-yet-implemented 'claim!))
      (complete! [_ _execution-id _node-id _worker-id _token _result]
        (not-yet-implemented 'complete!))
      (get-execution [_ _execution-id]
        (not-yet-implemented 'get-execution))
      (get-node [_ _execution-id _node-id]
        (not-yet-implemented 'get-node))
      (get-claim [_ _execution-id _claim-id]
        (not-yet-implemented 'get-claim))
      (get-claimable-nodes [_ _execution-id]
        (not-yet-implemented 'get-claimable-nodes))

      harness/Synchronizable
      (wait-for-processing! [_]
        (rtest/wait-for-microbatch-processed-count
         ipc module-name "lifecycle" (get @append-counts ipc 0))))))

(defn create-module []
  ;; One counter registry per create-module result (not a process-wide
  ;; defonce): closed IPC instances are released with the result instead of
  ;; being retained for the JVM's lifetime, and wrappers of different IPCs
  ;; built from one result still keep independent counts.
  (let [append-counts (atom {})]
    {:module      JobSchedulerModule
     :wrap-client (fn [ipc] (make-client ipc append-counts))}))
