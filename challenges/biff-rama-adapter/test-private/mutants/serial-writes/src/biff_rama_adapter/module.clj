(ns biff-rama-adapter.module
  "Reference Biff database adapter backed by Rama.

   Entities are versioned by basis (the microbatch ID that applied them).
   The next microbatch cannot start until every task has committed the
   previous one, so a basis below the current microbatch ID is visible on
   every task. Each microbatch promotes the newest such transaction basis to
   $$visible-basis. Reads pick the newest version at or below one visible
   basis, which makes transactions atomic across partitions and gives
   wrap-db-snapshot a stable view."
  (:require [com.biffweb.graph :as biff.graph]
            [com.rpl.rama :refer :all]
            [com.rpl.rama.aggs :as aggs]
            [com.rpl.rama.ops :as ops]
            [com.rpl.rama.path :refer :all]))

(def ^:private deleted ::deleted)

(defn- entity-key [table id] [table id])

(defn- apply-op [doc [op _table x]]
  (case op
    :put    x
    :patch  (reduce-kv (fn [m k v] (if (nil? v) (dissoc m k) (assoc m k v)))
                       (if (map? doc) doc {})
                       x)
    :delete deleted))

(defn- fold-ops
  "Applies one microbatch's ops for one entity in [tx-order op-index] order."
  [current ops-by-order]
  (reduce (fn [doc [_ op]] (apply-op doc op))
          current
          (sort-by key ops-by-order)))

(defn- latest-doc [versions] (some-> versions vals first))

(defn- op-entity [[op table x]]
  (entity-key table (if (= op :delete) x (get x (keyword (name table) "id")))))

(defn- committed-before
  "Newest pending transaction basis from an earlier, fully committed microbatch."
  [pending basis]
  (first (rsubseq pending < basis)))

(defn- pending-after [pending basis]
  (into (sorted-set) (subseq pending > basis)))

(defmodule BiffRamaModule [setup topologies]
  (declare-depot setup *tx-depot (hash-by :tx-id))
  (declare-depot setup *kv-depot (hash-by :ns))
  (declare-tick-depot setup *tick 25)

  (let [mb (microbatch-topology topologies "txs")]
    (declare-pstate mb $$entities {Object (map-schema Long Object {:subindex? true})})
    (declare-pstate mb $$receipts {String Long})
    (declare-pstate mb $$pending-bases Object {:global? true :initial-value (sorted-set)})
    (declare-pstate mb $$visible-basis Long {:global? true :initial-value -1})
    (<<sources mb
      (source> *tx-depot :> %mb)
      (ops/current-microbatch-id :> *basis)
      (<<batch
        (%mb :> {:keys [*tx-id *ops]})
        (local-transform> [(keypath *tx-id) (termval *basis)] $$receipts)
        (ops/explode-indexed *ops :> *i *op)
        (op-entity *op :> *eid)
        (+group-by *eid
          (aggs/+map-agg [*tx-id *i] *op :> *ops-by-order))
        (local-select> [(keypath *eid) (sorted-map-range-to-end 1) (view latest-doc)] $$entities
                       :> *current)
        (fold-ops *current *ops-by-order :> *doc)
        (local-transform> [(keypath *eid *basis) (termval *doc)] $$entities))
      (<<batch
        (%mb)
        (|global)
        (aggs/+count :> *n)
        (filter> (pos? *n))
        (local-select> STAY $$pending-bases :> *pending)
        (local-transform> [(termval (conj *pending *basis))] $$pending-bases))

      (source> *tick :> %tick)
      (ops/current-microbatch-id :> *basis)
      (<<batch
        (%tick)
        (|global)
        (local-select> STAY $$pending-bases :> *pending)
        (committed-before *pending *basis :> *ready)
        (filter> (some? *ready))
        (local-transform> [(termval *ready)] $$visible-basis)
        (local-transform> [(termval (pending-after *pending *ready))] $$pending-bases))))

  (let [s (stream-topology topologies "kv")]
    (declare-pstate s $$kv {clojure.lang.Keyword (map-schema String Object {:subindex? true})})
    (<<sources s
      (source> *kv-depot :> {:keys [*ns *k *v]})
      (<<if (nil? *v)
        (local-transform> [(keypath *ns *k) NONE>] $$kv)
       (else>)
        (local-transform> [(keypath *ns *k) (termval *v)] $$kv))))

  (<<query-topology topologies "entities-as-of" [*eids *basis :> *result]
    (ops/explode *eids :> *eid)
    (|hash *eid)
    (local-select> [(keypath *eid) (sorted-map-range-to (inc *basis) {:max-amt 1}) (view latest-doc)]
                   $$entities :> *doc)
    (|origin)
    (aggs/+map-agg *eid *doc :> *result)))

;;; Client side

(def ^:private module-name (get-module-name BiffRamaModule))

(defn- conn [ctx]
  (or (:biff.rama/conn ctx)
      (throw (ex-info "biff.rama adapter is not started" {}))))

(defn- read-basis [ctx]
  (or (:biff.rama/snapshot-basis ctx) @(:visible (conn ctx))))

(defn- validate-tx! [ops]
  (when-not (and (sequential? ops) (seq ops))
    (throw (ex-info "transaction must be a non-empty sequence of ops" {:ops ops})))
  (doseq [[op table x :as o] ops]
    (when-not (and (vector? o) (= 3 (count o)) (#{:put :patch :delete} op)
                   (simple-keyword? table))
      (throw (ex-info "invalid op" {:op o})))
    (let [id-key (keyword (name table) "id")]
      (if (= op :delete)
        (when (nil? x) (throw (ex-info "delete needs an id" {:op o})))
        (when-not (and (map? x) (some? (get x id-key))
                       (every? #(and (qualified-keyword? %) (= (name table) (namespace %)))
                               (keys x)))
          (throw (ex-info "document must be a map of :<table>/... keys with :<table>/id"
                          {:op o})))))))

(defn- await-basis! [{:keys [visible]} basis]
  (let [deadline (+ (System/currentTimeMillis) 60000)]
    (loop []
      (when (< @visible basis)
        (when (> (System/currentTimeMillis) deadline)
          (throw (ex-info "timed out waiting for transaction to become visible" {:basis basis})))
        (locking visible (.wait ^Object visible 20))
        (recur)))))

(defn- transact* [ctx ops]
  (validate-tx! ops)
  (let [{:keys [tx-depot receipts] :as c} (conn ctx)
        tx-id (str (random-uuid))]
    (foreign-append! tx-depot {:tx-id tx-id :ops (vec ops)})
    (let [basis (loop [n 0]
                  (or (foreign-select-one (keypath tx-id) receipts)
                      (if (< n 6000)
                        (do (Thread/sleep 10) (recur (inc n)))
                        (throw (ex-info "timed out waiting for transaction" {:tx-id tx-id})))))]
      (await-basis! c basis)
      ((:notify! c) ctx)
      {:biff.rama/basis basis})))

(def ^:private tx-lock (Object.))

(defn transact [ctx ops]
  (locking tx-lock (transact* ctx ops)))

(defn- entities [ctx table ids]
  (let [{:keys [entities-q]} (conn ctx)
        eids (mapv #(entity-key table %) ids)
        result (foreign-invoke-query entities-q eids (read-basis ctx))]
    (mapv (fn [eid] (let [doc (get result eid)]
                      (when (and doc (not= doc deleted)) doc)))
          eids)))

(defn entity [ctx table id]
  (first (entities ctx table [id])))

(defn wrap-db-snapshot [f]
  (fn [ctx]
    (if (:biff.rama/snapshot-basis ctx)
      (f ctx)
      (f (assoc ctx :biff.rama/snapshot-basis @(:visible (conn ctx)))))))

(defn kv-set [ctx ns* k v]
  (when-not (and (qualified-keyword? ns*) (string? k))
    (throw (ex-info "invalid kv namespace or key" {:ns ns* :k k})))
  (foreign-append! (:kv-depot (conn ctx)) {:ns ns* :k k :v v})
  nil)

(defn kv-get [ctx ns* k]
  (when-not (and (qualified-keyword? ns*) (string? k))
    (throw (ex-info "invalid kv namespace or key" {:ns ns* :k k})))
  (foreign-select-one (keypath ns* k) (:kv (conn ctx)) {:pkey ns*}))

(defn kv-list
  ([ctx ns*] (kv-list ctx ns* nil))
  ([ctx ns* prefix]
   (when-not (and (qualified-keyword? ns*) (or (nil? prefix) (string? prefix)))
     (throw (ex-info "invalid kv namespace or prefix" {:ns ns* :prefix prefix})))
   (let [ps (:kv (conn ctx))]
     (if prefix
       (foreign-select [(keypath ns*) (sorted-map-range prefix (str prefix "￿")) MAP-KEYS]
                       ps {:pkey ns*})
       (foreign-select [(keypath ns*) MAP-KEYS] ps {:pkey ns*})))))

(defn- strip-id-suffix [k]
  (keyword (namespace k) (subs (name k) 0 (- (count (name k)) 3))))

(defn make-resolvers [columns]
  (for [[table cols] (group-by (comp keyword namespace key) columns)
        :let [pk (keyword (name table) "id")
              outputs (mapcat (fn [[k {:keys [ref]}]]
                                (cond
                                  (and ref (.endsWith (name k) "-id"))
                                  [[k k nil] [k (strip-id-suffix k) ref]]
                                  ref [[k k ref]]
                                  :else [[k k nil]]))
                              cols)]]
    (biff.graph/resolver
     {:id (keyword "biff-rama-adapter" (str (name table) "-resolver"))
      :input [pk]
      :output (mapv (fn [[_ out ref]] (if ref {out [ref]} out)) outputs)
      :batch true
      :resolve-fn
      (fn [ctx inputs]
        (mapv (fn [doc]
                (into {}
                      (keep (fn [[src out ref]]
                              (when-some [v (get doc src)]
                                [out (if ref {ref v} v)])))
                      outputs))
              (entities ctx table (mapv pk inputs))))})))

(def fx-handlers
  {:biff.rama.fx/transact transact
   :biff.rama.fx/entity   entity})

(defn- start [ctx]
  (let [cluster (:biff.rama/cluster ctx)
        visible-ps (foreign-pstate cluster module-name "$$visible-basis")
        visible (atom (foreign-select-one STAY visible-ps))
        notify! (fn [ctx] (when-let [on-tx (:biff.core/on-tx ctx)] (on-tx ctx)))
        ctx* (volatile! nil)
        proxy (foreign-proxy STAY visible-ps
                {:callback-fn (fn [new-val _ _]
                                (when (and new-val (> new-val @visible))
                                  (reset! visible new-val)
                                  (locking visible (.notifyAll ^Object visible))
                                  (when-let [c @ctx*] (notify! c))))})
        ctx (assoc ctx
                   :biff.rama/conn {:tx-depot (foreign-depot cluster module-name "*tx-depot")
                                    :kv-depot (foreign-depot cluster module-name "*kv-depot")
                                    :receipts (foreign-pstate cluster module-name "$$receipts")
                                    :kv (foreign-pstate cluster module-name "$$kv")
                                    :entities-q (foreign-query cluster module-name "entities-as-of")
                                    :visible visible
                                    :proxy proxy
                                    :notify! notify!}
                   :biff.core/kv-get kv-get
                   :biff.core/kv-set kv-set
                   :biff.core/kv-list kv-list
                   :biff.core/wrap-db-snapshot wrap-db-snapshot)]
    (vreset! ctx* ctx)
    (swap! visible max (or (foreign-select-one STAY visible-ps) -1))
    ctx))

(defn- stop [ctx]
  (some-> ctx :biff.rama/conn :proxy .close))

(defn create-module []
  {:module BiffRamaModule
   :biff-module
   (fn [& {:biff.rama/keys [columns]}]
     (cond-> {:biff.core/id :biff.rama/module
              :biff.core/start start
              :biff.core/stop stop
              :biff.fx/handlers fx-handlers}
       columns (assoc :biff.graph/resolvers (vec (make-resolvers columns)))))})
