(ns hld-notification-system.module
  (:require [com.rpl.rama :refer :all]
            [com.rpl.rama.path :refer :all]
            [com.rpl.rama.aggs :as aggs]
            [com.rpl.rama.ops :as ops]
            [com.rpl.rama.test :as rtest]
            [hld-notification-system.protocol :as p]
            [rama-challenges.harness :as harness]))

(defrecord RegisterDevice [owner user-id device-id token])
(defrecord SetPreference [owner user-id category enabled?])
(defrecord Submit [owner submission-id user-id category payload ttl now])
(defrecord ReportAttempt [owner submission-id device-id attempt-no outcome now])
(defrecord RecordReceipt [owner submission-id device-id receipt])

(defn update-profile [profile event]
  (let [profile (merge {:devices {} :prefs {} :submit-seq 0 :dl-seq 0} profile)]
    (cond
      (instance? RegisterDevice event)
      (let [{:keys [device-id token]} event
            devices (:devices profile)
            old (get devices device-id)]
        (if (and (nil? old) (= 8 (count devices))) profile
            (assoc-in profile [:devices device-id]
                      {:token token :generation (inc (or (:generation old) 0)) :valid? true})))
      (instance? SetPreference event)
      (assoc-in profile [:prefs (:category event)] (:enabled? event))
      :else profile)))

(defn build-submission [event profile]
  (let [{:keys [submission-id user-id category payload ttl now]} event
        devices (if (false? (get (:prefs profile) category true))
                  nil
                  (into {} (filter (comp :valid? val) (:devices profile))))
        status (cond (false? (get (:prefs profile) category true)) :suppressed
                     (empty? devices) :no-devices
                     :else :dispatched)]
    {:submission-id submission-id :user-id user-id :category category
     :payload payload :submitted-at now :expires-at (+ now ttl) :status status
     :deliveries (into {} (map (fn [[id {:keys [token generation]}]]
                                 [id {:token token :generation generation :state :pending
                                      :attempts 0 :next-attempt-at now}]) devices))}))

(defn apply-report [sub device-id attempt-no outcome now]
  (let [delivery (get-in sub [:deliveries device-id])]
    (when (and (= :pending (:state delivery))
               (= attempt-no (inc (:attempts delivery)))
               (>= now (:next-attempt-at delivery)))
      (let [expiry? (>= now (:expires-at sub))
            state (cond expiry? :expired
                        (= outcome :accepted) :accepted
                        (= outcome :invalid-token) :invalid-token
                        (= outcome :permanent-failure) :failed
                        (= attempt-no 3) :failed
                        :else :pending)
            reason (when (= state :failed)
                     (if (= outcome :permanent-failure) :permanent-failure :retries-exhausted))]
        {:delivery (assoc delivery :state state
                          :attempts (if expiry? (:attempts delivery) attempt-no)
                          :next-attempt-at (when (= state :pending)
                                             (+ now (if (= attempt-no 1) 10 20))))
         :effect (cond reason {:kind :dead-letter :reason reason :at now
                               :generation (:generation delivery)}
                       (= state :invalid-token) {:kind :invalidate
                                                 :generation (:generation delivery)})}))))

(defn apply-receipt [sub device-id receipt]
  (let [delivery (get-in sub [:deliveries device-id])
        rank {:accepted 0 :delivered 1 :read 2}]
    (when (contains? rank (:state delivery))
      (assoc delivery :state (if (> (rank receipt) (rank (:state delivery)))
                               receipt (:state delivery))))))

(defn apply-event [sub event]
  (when sub
    (if (instance? ReportAttempt event)
      (apply-report sub (:device-id event) (:attempt-no event) (:outcome event) (:now event))
      (when-let [delivery (apply-receipt sub (:device-id event) (:receipt event))]
        {:delivery delivery}))))

(defn invalidate-profile [profile device-id generation]
  (if (= generation (get-in profile [:devices device-id :generation]))
    (assoc-in profile [:devices device-id :valid?] false)
    profile))

(defn next-submit-seq [profile]
  (inc (or (:submit-seq profile) 0)))

(defn candidate-rank [task pos]
  (+ (* task 1099511627776) pos))

(defn user-event? [event]
  (or (instance? RegisterDevice event)
      (instance? SetPreference event)
      (instance? Submit event)))

(defn delivery-event? [event]
  (or (instance? ReportAttempt event)
      (instance? RecordReceipt event)))

(defmodule NotificationModule [setup topologies]
  (declare-depot setup *events (hash-by :owner))
  (let [mb (microbatch-topology topologies "core")]
    (declare-pstate mb $$users
      {String (fixed-keys-schema
                {:profile (fixed-keys-schema
                            {:devices (map-schema String (fixed-keys-schema
                                                            {:token String :generation Long :valid? Boolean}))
                             :prefs (map-schema String Boolean)
                             :submit-seq Long :dl-seq Long})
                 :recent (map-schema Long String {:subindex? true})
                 :dead-letters (map-schema Long (fixed-keys-schema
                                                  {:submission-id String :device-id String
                                                   :reason clojure.lang.Keyword :at Long})
                                           {:subindex? true})})})
    (declare-pstate mb $$submissions
      {String (fixed-keys-schema
                {:submission-id String :user-id String :category String :payload String
                 :submitted-at Long :expires-at Long :status clojure.lang.Keyword
                 :deliveries (map-schema String (fixed-keys-schema
                                                   {:token String :generation Long
                                                    :state clojure.lang.Keyword :attempts Long
                                                    :next-attempt-at Long}))})})
    (declare-pstate mb $$task-pos {Long Long})

    (<<sources mb
      (source> *events :> %mb)
      (<<batch
        (%mb :> *event)
        (filter> (user-event? *event))
        (get *event :user-id :> *user)
        (local-select> [(keypath *user :profile)] $$users :> *profile)
        (<<cond
          (case> (instance? Submit *event))
          (next-submit-seq *profile :> *seq)
          (local-transform> [(keypath *user :profile :submit-seq) (termval *seq)] $$users)
          (ops/current-task-id :> *task)
          (local-select> [(keypath *task) (nil->val 0)] $$task-pos :> *previous)
          (inc *previous :> *pos)
          (local-transform> [(keypath *task) (termval *pos)] $$task-pos)
          (build-submission *event *profile :> *record)
          (get *event :submission-id :> *submission-id)
          (candidate-rank *task *pos :> *candidate-rank)
          (vector *submission-id *user *seq *record *candidate-rank :> *candidate)
          (default>)
          (update-profile *profile *event :> *updated)
          (local-transform> [(keypath *user :profile) (termval *updated)] $$users)
          (identity nil :> *candidate))
        (filter> (some? *candidate))
        (identity *candidate :> [*sid *winner-user *winner-seq *winner-record *rank])
        (+group-by *sid
          (aggs/+limit [1] *rank *winner-user *winner-seq *winner-record
                       :+options {:sort *rank}))
        (materialize> *sid *rank *winner-user *winner-seq *winner-record :> $$winners))
      (<<batch
        ($$winners :> *sid *rank *user *seq *record)
        (local-select> (keypath *sid) $$submissions :> *existing)
        (filter> (nil? *existing))
        (local-transform> [(keypath *sid) (termval *record)] $$submissions)
        (|hash *user)
        (local-transform> [(keypath *user :recent *seq) (termval *sid)] $$users))
      (<<batch
        (%mb :> *event)
        (filter> (delivery-event? *event))
        (get *event :submission-id :> *sid)
        (get *event :device-id :> *device)
        (local-select> (keypath *sid) $$submissions :> *sub)
        (apply-event *sub *event :> *result)
        (filter> (some? *result))
        (get *result :delivery :> *delivery)
        (local-transform> [(keypath *sid :deliveries *device) (termval *delivery)] $$submissions)
        (get *result :effect :> *effect)
        (filter> (some? *effect))
        (get *sub :user-id :> *user)
        (|hash *user)
        (<<cond
          (case> (= :invalidate (get *effect :kind)))
          (local-select> (keypath *user :profile) $$users :> *profile)
          (invalidate-profile *profile *device (get *effect :generation) :> *updated)
          (local-transform> [(keypath *user :profile) (termval *updated)] $$users)
          (default>)
          (local-select> [(keypath *user :profile :dl-seq) (nil->val 0)] $$users :> *prior)
          (inc *prior :> *next)
          (local-transform> [(keypath *user :profile :dl-seq) (termval *next)] $$users)
          (local-transform> [(keypath *user :dead-letters *next)
                             (termval {:submission-id *sid :device-id *device
                                       :reason (get *effect :reason) :at (get *effect :at)})]
                            $$users))))))

(defn create-module []
  (let [counter (atom 0)]
    {:module NotificationModule
     :wrap-client
     (fn [ipc]
       (let [name (get-module-name NotificationModule)
             depot (foreign-depot ipc name "*events")
             users (foreign-pstate ipc name "$$users")
             submissions (foreign-pstate ipc name "$$submissions")
             append (fn [event]
                      (locking counter
                        (foreign-append! depot event)
                        (swap! counter inc))
                      nil)]
         (reify p/NotificationSystem
           (register-device! [_ user device token]
             (append (->RegisterDevice user user device token)))
           (set-preference! [_ user category enabled?]
             (append (->SetPreference user user category enabled?)))
           (submit! [_ sid user category payload ttl now]
             (append (->Submit user sid user category payload ttl now)))
           (report-attempt! [_ sid device attempt outcome now]
             (append (->ReportAttempt sid sid device attempt outcome now)))
           (record-receipt! [_ sid device receipt]
             (append (->RecordReceipt sid sid device receipt)))
           (get-devices [_ user]
             (or (foreign-select-one [(keypath user :profile :devices) (nil->val {})] users) {}))
           (get-preferences [_ user]
             (or (foreign-select-one [(keypath user :profile :prefs) (nil->val {})] users) {}))
           (get-submission [_ sid]
             (foreign-select-one (keypath sid) submissions))
           (get-recent-submissions [_ user]
             (vec (reverse (foreign-select [(keypath user :recent)
                                            (sorted-map-range-to-end 100) MAP-VALS] users))))
           (get-dead-letters [_ user]
             (vec (reverse (foreign-select [(keypath user :dead-letters)
                                            (sorted-map-range-to-end 100) MAP-VALS] users))))
           harness/Synchronizable
           (wait-for-processing! [_]
             (locking counter
               (rtest/wait-for-microbatch-processed-count ipc name "core" @counter))))))}))
