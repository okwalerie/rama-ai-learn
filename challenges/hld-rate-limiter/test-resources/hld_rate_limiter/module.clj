;; IMPORTANT: Before modifying this file, re-read PLAN.md and check pending todos.
;; Adhere to all previously decided design decisions.

(ns hld-rate-limiter.module
  "Reference implementation for the hld-rate-limiter challenge.

   One depot hashed by user-id, one microbatch topology, one PState keyed
   by user-id holding the inline limiter (version, config, clock, user
   bucket, endpoint buckets) beside a subindexed map of recorded decisions.
   Every write and read is a single-task, fixed-work operation on
   hash(user-id); the two-bucket debit and the config+reset switch are each
   one termval of the whole limiter value."
  (:require
   [com.rpl.rama :refer :all]
   [com.rpl.rama.path :refer :all]
   [com.rpl.rama.test :as rtest]
   [rama-challenges.harness :as harness]
   [hld-rate-limiter.protocol :as proto]))

(defrecord SetConfig [user-id version config])
(defrecord Check [user-id request-id endpoint cost now])

;; ---------------------------------------------------------------------------
;; Pure bucket arithmetic (used both in the topology and in get-status).

(defn available
  "available(T) = min(capacity, tokens + (T - at) * refill)."
  [{:keys [tokens at]} {:keys [capacity refill]} t]
  (min (long capacity) (+ (long tokens) (* (- (long t) (long at)) (long refill)))))

(defn full-bucket
  [{:keys [capacity]}]
  {:tokens (long capacity) :at 0})

(defn new-limiter
  "Returns the limiter value to install for set-config!, or nil when the
   version is not strictly greater than the current one."
  [limiter version config]
  (when (or (nil? limiter) (> (long version) (long (:version limiter))))
    {:version          (long version)
     :config           config
     :clock            (if limiter (long (:clock limiter)) 0)
     :user-bucket      (full-bucket (:user config))
     :endpoint-buckets (into {} (map (fn [[e params]] [e (full-bucket params)]))
                             (:endpoints config))}))

(defn evaluate-check
  "Applies protocol rules 2-5 for a not-yet-recorded check. Returns
   [decision new-limiter-or-nil]; new-limiter is non-nil only when the
   decision debits (would-allow true)."
  [limiter endpoint cost now]
  (let [cost  (long cost)
        clock (if limiter (long (:clock limiter)) 0)
        t     (max (long now) clock)]
    (cond
      (nil? limiter)
      [{:allowed false :would-allow false :reason :no-config
        :tick t :config-version nil :remaining nil}
       nil]

      (not (contains? (get-in limiter [:config :endpoints]) endpoint))
      [{:allowed (boolean (get-in limiter [:config :shadow?])) :would-allow false
        :reason :unknown-endpoint :tick t
        :config-version (:version limiter) :remaining nil}
       nil]

      :else
      (let [{:keys [config version user-bucket endpoint-buckets]} limiter
            shadow?  (boolean (:shadow? config))
            u-params (:user config)
            e-params (get-in config [:endpoints endpoint])
            e-bucket (get endpoint-buckets endpoint)
            au       (available user-bucket u-params t)
            ae       (available e-bucket e-params t)
            base     {:tick t :config-version version}]
        (cond
          (or (> cost (long (:capacity u-params))) (> cost (long (:capacity e-params))))
          [(assoc base :allowed shadow? :would-allow false
                  :reason :cost-exceeds-capacity
                  :remaining {:user au :endpoint ae})
           nil]

          (and (>= au cost) (>= ae cost))
          [(assoc base :allowed true :would-allow true :reason nil
                  :remaining {:user (- au cost) :endpoint (- ae cost)})
           (assoc limiter
                  :clock t
                  :user-bucket {:tokens (- au cost) :at t}
                  :endpoint-buckets (assoc endpoint-buckets endpoint
                                           {:tokens (- ae cost) :at t}))]

          :else
          [(assoc base :allowed shadow? :would-allow false
                  :reason :insufficient-tokens
                  :remaining {:user au :endpoint ae})
           nil])))))

;; ---------------------------------------------------------------------------

(defmodule RateLimiterModule [setup topologies]
  (declare-depot setup *user-events (hash-by :user-id))

  (let [mb (microbatch-topology topologies "core")]
    (declare-pstate mb $$users
      {String (fixed-keys-schema
               {:limiter   (fixed-keys-schema
                            {:version          Long
                             :config           (fixed-keys-schema
                                                {:shadow?   Boolean
                                                 :user      (fixed-keys-schema {:capacity Long :refill Long})
                                                 :endpoints (map-schema String (fixed-keys-schema {:capacity Long :refill Long}))})
                             :clock            Long
                             :user-bucket      (fixed-keys-schema {:tokens Long :at Long})
                             :endpoint-buckets (map-schema String (fixed-keys-schema {:tokens Long :at Long}))})
                :decisions (map-schema String
                                       (fixed-keys-schema {:allowed        Boolean
                                                           :would-allow    Boolean
                                                           :reason         clojure.lang.Keyword
                                                           :tick           Long
                                                           :config-version Long
                                                           :remaining      (fixed-keys-schema {:user Long :endpoint Long})})
                                       {:subindex? true})})})

    (<<sources mb
      (source> *user-events :> %mb)
      (%mb :> *event)
      (<<subsource *event
        ;; set-config!: strictly newer version replaces config and resets
        ;; every bucket in one write; older/equal versions are ignored.
        (case> SetConfig :> {:keys [*user-id *version *config]})
        (local-select> [(keypath *user-id :limiter)] $$users :> *limiter)
        (new-limiter *limiter *version *config :> *new-limiter)
        (<<if (some? *new-limiter)
          (local-transform> [(keypath *user-id :limiter) (termval *new-limiter)]
                            $$users))

        ;; check!: first decision per (user, request-id) is final; a
        ;; debiting decision writes both buckets and the clock atomically.
        (case> Check :> {:keys [*user-id *request-id *endpoint *cost *now]})
        (local-select> [(keypath *user-id :decisions *request-id)] $$users :> *recorded)
        (<<if (nil? *recorded)
          (local-select> [(keypath *user-id :limiter)] $$users :> *limiter)
          (evaluate-check *limiter *endpoint *cost *now :> [*decision *new-limiter])
          (local-transform> [(keypath *user-id :decisions *request-id)
                             (termval *decision)]
                            $$users)
          (<<if (some? *new-limiter)
            (local-transform> [(keypath *user-id :limiter) (termval *new-limiter)]
                              $$users)))))))

(defn- decision-view
  [d]
  (when d
    {:allowed        (:allowed d)
     :would-allow    (:would-allow d)
     :reason         (:reason d)
     :tick           (:tick d)
     :config-version (:config-version d)
     :remaining      (when-let [r (:remaining d)]
                       {:user (:user r) :endpoint (:endpoint r)})}))

(defn- status-view
  [limiter endpoint now]
  (when-let [e-params (get-in limiter [:config :endpoints endpoint])]
    (let [t        (max (long now) (long (:clock limiter)))
          u-params (get-in limiter [:config :user])]
      {:tick           t
       :config-version (:version limiter)
       :user           {:capacity  (:capacity u-params)
                        :available (available (:user-bucket limiter) u-params t)}
       :endpoint       {:capacity  (:capacity e-params)
                        :available (available (get-in limiter [:endpoint-buckets endpoint])
                                              e-params t)}})))

(defn make-client
  "Creates a protocol client. `counter` is harness-only synchronization
   bookkeeping shared by every wrapper produced by one create-module call."
  [ipc counter]
  (let [module-name (get-module-name RateLimiterModule)
        events (foreign-depot ipc module-name "*user-events")
        users  (foreign-pstate ipc module-name "$$users")
        append! (fn [record]
                  (foreign-append! events record)
                  (swap! counter inc)
                  nil)]
    (reify proto/RateLimiter
      (set-config! [_ user-id version config]
        (append! (->SetConfig user-id version config)))
      (check! [_ user-id request-id endpoint cost now]
        (append! (->Check user-id request-id endpoint cost now)))
      (get-decision [_ user-id request-id]
        (decision-view
         (foreign-select-one [(keypath user-id :decisions request-id)] users)))
      (get-config [_ user-id]
        (when-let [limiter (foreign-select-one [(keypath user-id :limiter)] users)]
          {:version (:version limiter) :config (:config limiter)}))
      (get-status [_ user-id endpoint now]
        (when-let [limiter (foreign-select-one [(keypath user-id :limiter)] users)]
          (status-view limiter endpoint now)))

      harness/Synchronizable
      (wait-for-processing! [_]
        (rtest/wait-for-microbatch-processed-count ipc module-name "core" @counter)))))

(defn create-module
  []
  (let [counter (atom 0)]
    {:module      RateLimiterModule
     :wrap-client (fn [ipc] (make-client ipc counter))}))
