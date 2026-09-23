(ns hld-feature-flag-service.module
  "Durable reference implementation of revisioned feature flags."
  (:require [com.rpl.rama :refer :all]
            [com.rpl.rama.path :refer :all]
            [com.rpl.rama.test :as rtest]
            [hld-feature-flag-service.protocol :as protocol]
            [rama-challenges.harness :as harness])
  (:import [java.nio ByteBuffer]
           [java.nio.charset StandardCharsets]
           [java.security MessageDigest]
           [java.math BigInteger]))

(defrecord FlagId [tenant env flag-key])
(defrecord PutFlagConfig [flag-id config])

(defn- valid-config? [config]
  (and (map? config)
       (integer? (:revision config))
       (<= 1 (:revision config) Long/MAX_VALUE)
       (vector? (:rules config))
       (every? #(and (map? %) (string? (:attribute %))) (:rules config))
       (or (nil? (:rollout config))
           (and (map? (:rollout config))
                (integer? (get-in config [:rollout :threshold]))
                (<= 0 (get-in config [:rollout :threshold]) 10000)))))

(defn- newer? [config previous]
  (or (nil? previous) (> (:revision config) previous)))

(defmodule FeatureFlags [setup topologies]
  (declare-depot setup *flag-writes (hash-by :flag-id))
  (let [mb (microbatch-topology topologies "flags")]
    (declare-pstate mb $$flags
                    {FlagId (fixed-keys-schema
                             {:revision Long :killed? Boolean
                              :off-value Object :default-value Object
                              :rules (vector-schema
                                      (fixed-keys-schema
                                       {:attribute String :operator Object
                                        :value Object :serve Object}))
                              :rollout (fixed-keys-schema
                                        {:threshold Long :serve Object})})})
    (<<sources mb
      (source> *flag-writes :> %mb)
      (%mb :> {:keys [*flag-id *config]})
      (valid-config? *config :> *valid?)
      (filter> *valid?)
      (local-select> [(keypath *flag-id :revision)] $$flags :> *previous)
      (newer? *config *previous :> *newer?)
      (filter> *newer?)
      (local-transform> [(keypath *flag-id) (termval *config)] $$flags))))

(defn- bucket [tenant env flag-key subject-id]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (doseq [component [tenant env flag-key subject-id]]
      (let [bytes (.getBytes ^String component StandardCharsets/UTF_8)
            length (.array (doto (ByteBuffer/allocate 4)
                             (.putInt (alength bytes))))]
        (.update digest ^bytes length)
        (.update digest ^bytes bytes)))
    (-> (BigInteger. 1 (java.util.Arrays/copyOf (.digest digest) 8))
        (.mod (BigInteger/valueOf 10000))
        (.intValue))))

(defn- evaluate-config [config tenant env flag-key subject-id attributes]
  (if (nil? config)
    {:value nil :revision nil :reason :missing}
    (let [{:keys [revision killed? off-value default-value rules rollout]} config
          base {:revision revision}]
      (cond
        killed? (assoc base :value off-value :reason :killed)
        (some #(not= :eq (:operator %)) rules)
        (assoc base :value off-value :reason :unknown-operator)
        :else
        (if-let [[i rule] (first (keep-indexed
                                 (fn [i rule]
                                   (when (and (contains? attributes (:attribute rule))
                                              (= (get attributes (:attribute rule)) (:value rule)))
                                     [i rule])) rules))]
          (assoc base :value (:serve rule) :reason :rule :rule-index i)
          (if (some? rollout)
            (let [b (bucket tenant env flag-key subject-id)]
              (if (< b (:threshold rollout))
                (assoc base :value (:serve rollout) :reason :rollout :bucket b)
                (assoc base :value default-value :reason :default :bucket b)))
            (assoc base :value default-value :reason :default)))))))

;; Synchronization metadata is shared by wrappers created for one deployment.
(defn- make-client [append-count ipc]
  (let [module-name (get-module-name FeatureFlags)
        depot (foreign-depot ipc module-name "*flag-writes")
        flags (foreign-pstate ipc module-name "$$flags")]
    (reify protocol/FeatureFlagService
      (put-flag-config! [_ tenant env flag-key config]
        (swap! append-count inc)
        (foreign-append! depot (->PutFlagConfig (->FlagId tenant env flag-key) config) :append-ack)
        nil)
      (get-flag-config [_ tenant env flag-key]
        (foreign-select-one [(keypath (->FlagId tenant env flag-key))] flags))
      (compute-bucket [_ tenant env flag-key subject-id]
        (bucket tenant env flag-key subject-id))
      (evaluate [_ tenant env flag-key subject-id attributes]
        (evaluate-config (foreign-select-one [(keypath (->FlagId tenant env flag-key))] flags)
                         tenant env flag-key subject-id attributes))
      harness/Synchronizable
      (wait-for-processing! [_]
        (rtest/wait-for-microbatch-processed-count
         ipc module-name "flags" @append-count)))))

(defn create-module []
  (let [append-count (atom 0)]
    {:module FeatureFlags
     :wrap-client (partial make-client append-count)}))
