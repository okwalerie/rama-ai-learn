(ns biff-rama-adapter.test-support
  (:require [com.biffweb.core :as biff.core]
            [com.biffweb.graph :as biff.graph]
            [com.rpl.rama.test :as rt]))

(defn factory []
  ((requiring-resolve 'biff-rama-adapter.module/create-module)))

(defn launch! [ipc module tasks]
  (rt/launch-module! ipc module {:tasks tasks :threads 2}))

(defn start-system!
  "Starts one Biff system (one app node) against `ipc`. Returns
   {:system sys :on-tx-calls atom :on-tx-reads atom}. `on-tx-read`, when
   given, runs inside on-tx with the ctx on-tx received and its result is
   conjed onto :on-tx-reads."
  [ipc biff-module & {:keys [columns on-tx-read]}]
  (let [calls (atom 0)
        reads (atom [])
        adapter (if columns
                  (biff-module :biff.rama/columns columns)
                  (biff-module))
        modules [adapter
                 (biff.graph/module)
                 {:biff.core/on-tx (fn [ctx]
                                     (swap! calls inc)
                                     (when on-tx-read
                                       (swap! reads conj (on-tx-read ctx))))}]
        modules-var (clojure.lang.Var/create modules)]
    {:module adapter
     :system (biff.core/start {:biff.rama/cluster ipc} modules-var [:biff.rama/module])
     :on-tx-calls calls
     :on-tx-reads reads}))

(defn stop-system! [{:keys [system]}]
  (biff.core/stop system))

(defn handler [{:keys [module]} k]
  (or (get-in module [:biff.fx/handlers k])
      (throw (ex-info (str "missing fx handler " k) {}))))

(defn transact! [node ops]
  ((handler node :biff.rama.fx/transact) (:system node) ops))

(defn entity
  ([node table id] (entity node (:system node) table id))
  ([node ctx table id] ((handler node :biff.rama.fx/entity) ctx table id)))

(defn kv-set! [{:keys [system]} ns* k v] ((:biff.core/kv-set system) system ns* k v))
(defn kv-get [{:keys [system]} ns* k] ((:biff.core/kv-get system) system ns* k))
(defn kv-list
  ([{:keys [system]} ns*] ((:biff.core/kv-list system) system ns*))
  ([{:keys [system]} ns* prefix] ((:biff.core/kv-list system) system ns* prefix)))

(defn in-snapshot
  "Calls (f snapshot-ctx) through the system's :biff.core/wrap-db-snapshot."
  [{:keys [system]} f]
  (((:biff.core/wrap-db-snapshot system) f) system))

(defn graph-query [{:keys [system]} input query]
  (biff.graph/query system input query))

(defn eventually
  "Polls (pred) every 20 ms until it is truthy or `ms` elapses."
  [ms pred]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (or (pred)
          (when (< (System/currentTimeMillis) deadline)
            (Thread/sleep 20)
            (recur))))))

(defmacro with-cluster
  "Launches the candidate Rama module on a fresh IPC with `tasks` tasks and
   binds [ipc biff-module]."
  [[ipc-sym biff-module-sym tasks] & body]
  `(let [{module# :module ~biff-module-sym :biff-module} (factory)]
     (with-open [~ipc-sym (rt/create-ipc)]
       (launch! ~ipc-sym module# ~tasks)
       ~@body)))
