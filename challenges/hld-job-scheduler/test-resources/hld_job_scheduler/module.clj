(ns hld-job-scheduler.module
  "Durable, execution-partitioned reference scheduler."
  (:require [com.rpl.rama :refer :all]
            [com.rpl.rama.path :refer :all]
            [com.rpl.rama.test :as rtest]
            [rama-challenges.harness :as harness]
            [hld-job-scheduler.protocol :as p]))

(defrecord SubmitExecution [execution-id dag])
(defrecord AdvanceClock [execution-id clock])
(defrecord Claim [execution-id node-id worker-id claim-id])
(defrecord Complete [execution-id node-id worker-id token result])
(defrecord ExecutionState [dag clock nodes])
(defrecord ClaimDecision [value])

(defn- acyclic? [dag]
  (let [dependents (reduce (fn [m [n deps]]
                             (reduce (fn [m d] (update m d (fnil conj []) n)) m deps))
                           {} dag)]
    (loop [degrees (into {} (map (fn [[n deps]] [n (count deps)])) dag)
           queue (into clojure.lang.PersistentQueue/EMPTY
                       (filter #(zero? (degrees %)) (keys dag)))
           consumed 0]
      (if-let [n (peek queue)]
        (let [[degrees queue]
              (reduce (fn [[ds q] m]
                        (let [degree (dec (ds m))]
                          [(assoc ds m degree) (if (zero? degree) (conj q m) q)]))
                      [degrees (pop queue)] (dependents n))]
          (recur degrees queue (inc consumed)))
        (= consumed (count dag))))))

(defn- valid-dag? [dag]
  (boolean (and (map? dag) (<= 1 (count dag) 32)
                (every? #(and (string? %) (not (empty? %))) (keys dag))
                (every? set? (vals dag))
                (every? (fn [deps] (every? (set (keys dag)) deps)) (vals dag))
                (acyclic? dag))))

(defn- initial-state [dag]
  (->ExecutionState dag 0 (into {} (map (fn [n] [n {:attempts 0 :lease nil :success? false :result nil}])) (keys dag))))

(defn- node-status [state node-id]
  (let [{:keys [lease success?]} (get (:nodes state) node-id)]
    (cond success? :success
          (not-every? #(get-in state [:nodes % :success?]) (get (:dag state) node-id)) :pending
          (and lease (< (:clock state) (:expiry lease))) :running
          :else :ready)))

(defn- decide-claim [state node-id worker-id claim-id]
  (let [clock (if state (:clock state) 0)
        base {:claim-id claim-id :node-id node-id :worker-id worker-id :clock clock}
        node (get (:nodes state) node-id)
        reason (cond (nil? state) :unknown-execution
                     (nil? node) :unknown-node
                     (:success? node) :already-succeeded
                     (not-every? #(get-in state [:nodes % :success?]) (get (:dag state) node-id)) :dependencies-incomplete
                     (and (:lease node) (< clock (get-in node [:lease :expiry]))) :lease-held)]
    (if reason
      [state (->ClaimDecision (assoc base :granted? false :reason reason))]
      (let [token (inc (:attempts node))
            expiry (+ clock 10)
            lease {:worker-id worker-id :token token :expiry expiry}]
        [(assoc-in state [:nodes node-id] (assoc node :attempts token :lease lease))
         (->ClaimDecision (assoc base :granted? true :token token :lease-expiry expiry))]))))

(defn- completed-state [state node-id worker-id token result]
  (let [node (get (:nodes state) node-id)
        lease (:lease node)]
    (if (and node (not (:success? node)) lease
             (= worker-id (:worker-id lease)) (= token (:token lease))
             (< (:clock state) (:expiry lease)))
      (assoc-in state [:nodes node-id] (assoc node :success? true :lease nil :result result))
      state)))

(defn- advances? [state clock]
  (and state (> clock (:clock state))))

(defmodule JobSchedulerModule [setup topologies]
  (declare-depot setup *execution-events (hash-by :execution-id))
  (let [mb (microbatch-topology topologies "lifecycle")]
    (declare-pstate mb $$executions
      {String (fixed-keys-schema
               {:state ExecutionState
                :claims (map-schema String ClaimDecision {:subindex? true})})})
    (<<sources mb
      (source> *execution-events :> %microbatch)
      (%microbatch :> *event)
      (<<subsource *event
        (case> SubmitExecution :> {:keys [*execution-id *dag]})
        (filter> (valid-dag? *dag))
        (local-select> [(keypath *execution-id) :state] $$executions :> *state)
        (<<if (nil? *state)
          (local-transform> [(keypath *execution-id) :state (termval (initial-state *dag))] $$executions))

        (case> AdvanceClock :> {:keys [*execution-id *clock]})
        (local-select> [(keypath *execution-id) :state] $$executions :> *state)
        (<<if (advances? *state *clock)
          (local-transform> [(keypath *execution-id) :state
                             (termval (assoc *state :clock *clock))] $$executions))

        (case> Claim :> {:keys [*execution-id *node-id *worker-id *claim-id]})
        (local-select> [(keypath *execution-id) :claims (keypath *claim-id)] $$executions :> *old)
        (<<if (nil? *old)
          (local-select> [(keypath *execution-id) :state] $$executions :> *state)
          (decide-claim *state *node-id *worker-id *claim-id :> [*new-state *decision])
          (<<if (not= *state *new-state)
            (local-transform> [(keypath *execution-id) :state (termval *new-state)] $$executions))
          (local-transform> [(keypath *execution-id) :claims (keypath *claim-id)
                             (termval *decision)] $$executions))

        (case> Complete :> {:keys [*execution-id *node-id *worker-id *token *result]})
        (local-select> [(keypath *execution-id) :state] $$executions :> *state)
        (<<if *state
          (completed-state *state *node-id *worker-id *token *result :> *new-state)
          (<<if (not= *state *new-state)
            (local-transform> [(keypath *execution-id) :state (termval *new-state)] $$executions)))))))

(defn make-client [ipc append-counts]
  (let [module-name (get-module-name JobSchedulerModule)
        depot (foreign-depot ipc module-name "*execution-events")
        executions (foreign-pstate ipc module-name "$$executions")
        state (fn [id] (foreign-select-one [(keypath id) :state] executions))
        append! (fn [record]
                  (swap! append-counts update ipc (fnil inc 0))
                  (foreign-append! depot record :append-ack))]
    (reify
      p/JobScheduler
      (submit-execution! [_ id dag] (append! (->SubmitExecution id dag)))
      (advance-clock! [_ id clock] (append! (->AdvanceClock id clock)))
      (claim! [_ id node worker claim] (append! (->Claim id node worker claim)))
      (complete! [_ id node worker token result] (append! (->Complete id node worker token result)))
      (get-execution [_ id]
        (when-let [s (state id)]
          (let [statuses (into {} (map (fn [n] [n (node-status s n)]) (keys (:dag s))))]
            {:execution-id id :clock (:clock s) :status (if (every? #{:success} (vals statuses)) :success :running)
             :dag (:dag s) :node-statuses statuses})))
      (get-node [_ id node]
        (when-let [s (state id)]
          (when-let [n (get (:nodes s) node)]
            {:node-id node :effect-id [id node] :dependencies (get (:dag s) node)
             :status (node-status s node) :attempts (:attempts n)
             :lease (:lease n) :result (:result n)})))
      (get-claim [_ id claim]
        (some-> (foreign-select-one [(keypath id) :claims (keypath claim)] executions) :value))
      (get-claimable-nodes [_ id]
        (if-let [s (state id)]
          (->> (keys (:dag s)) (filter #(= :ready (node-status s %))) sort vec)
          []))
      harness/Synchronizable
      (wait-for-processing! [_]
        (rtest/wait-for-microbatch-processed-count ipc module-name "lifecycle" (get @append-counts ipc 0))))))

(defn create-module []
  (let [append-counts (atom {})]
    {:module JobSchedulerModule
     :wrap-client (fn [ipc] (make-client ipc append-counts))}))
