(ns hld-ticketing-system.module
  (:require [com.rpl.rama :refer :all]
            [com.rpl.rama.path :refer :all]
            [com.rpl.rama.aggs :as aggs]
            [com.rpl.rama.test :as rtest]
            [hld-ticketing-system.protocol :as protocol]
            [rama-challenges.harness :as harness]))

(definterface ICommand)
(defrecord CreateEvent [event-id request-id] ICommand)
(defrecord AddSeats [event-id request-id seat-ids] ICommand)
(defrecord AdvanceClock [event-id request-id now] ICommand)
(defrecord HoldSeats [event-id request-id user-id seat-ids deadline] ICommand)
(defrecord ConfirmHold [event-id request-id hold-id user-id payment-ref] ICommand)
(defrecord ReleaseHold [event-id request-id hold-id user-id] ICommand)

(definterface IOutcome)
(defrecord Accepted [fields] IOutcome)
(defrecord Rejected [reason fields] IOutcome)

(defn command-name [cmd]
  (condp instance? cmd
    CreateEvent :create-event
    AddSeats :add-seats
    AdvanceClock :advance-clock
    HoldSeats :hold-seats
    ConfirmHold :confirm-hold
    ReleaseHold :release-hold))

(defn outcome-map [req]
  (when req
    (let [out (:outcome req)]
      (merge {:status (if (instance? Accepted out) :accepted :rejected)
              :command (:command req)
              :conflicting-attempts (:conflicting-attempts req)}
             (if (instance? Rejected out) {:reason (:reason out)} {})
             (:fields out)))))

(defn seat-view [seat clock]
  (when seat
    (let [state (cond (:confirmed? seat) :confirmed
                      (and (:hold-id seat) (< clock (:deadline seat))) :held
                      :else :available)]
      {:state state
       :hold-id (when (not= state :available) (:hold-id seat))
       :user-id (when (not= state :available) (:user-id seat))})))

(defn seats-view [ids found clock]
  (into {} (map (fn [id] [id (seat-view (get found id) clock)]) ids)))

(defn hold-view [id hold clock]
  (when hold
    {:hold-id id :user-id (:user-id hold) :seat-ids (:seat-ids hold)
     :deadline (:deadline hold) :payment-ref (:payment-ref hold)
     :state (case (:status hold)
              :confirmed :confirmed
              :released :released
              (if (< clock (:deadline hold)) :active :expired))}))

(defn sorted-commands [pairs] (mapv second (sort-by first pairs)))

(defn with-compensation-seq [decision seq]
  (update decision :outcome
          (fn [out] (update out :fields assoc :compensation-seq seq))))

(defn seat-command? [cmd]
  (or (instance? AddSeats cmd) (instance? HoldSeats cmd)))

(defn hold-command? [cmd]
  (or (instance? ConfirmHold cmd) (instance? ReleaseHold cmd)))

;; A decision only contains bounded writes: one clock, one hold and at most
;; the command's 1000 seats. It never contains the event or a history map.
(defn decide [cmd clock seats hold]
  (let [rid (:request-id cmd)
        ids (:seat-ids cmd)
        accepted (fn [fields & {:as writes}]
                   (assoc writes :outcome (->Accepted fields)))
        rejected (fn [reason & [fields compensation]]
                   {:outcome (->Rejected reason (or fields {}))
                    :compensation compensation})
        empty-seat {:hold-id nil :user-id nil :deadline nil :confirmed? false}]
    (cond
      (instance? CreateEvent cmd)
      (if (nil? clock) (accepted {} :clock 0 :initialize? true)
          (rejected :event-exists))

      (nil? clock) (rejected :no-such-event)

      (instance? AddSeats cmd)
      (if (some #(some? (get seats %)) ids)
        (rejected :seat-exists)
        (accepted {:added (long (count ids))}
                  :seat-writes (mapv (fn [id] [id empty-seat]) ids)))

      (instance? AdvanceClock cmd)
      (if (< (:now cmd) clock)
        (rejected :clock-regression)
        (accepted {:clock (:now cmd)} :clock (:now cmd)))

      (instance? HoldSeats cmd)
      (cond
        (some #(nil? (get seats %)) ids) (rejected :no-such-seat)
        (<= (:deadline cmd) clock) (rejected :deadline-passed)
        :else
        (let [unavailable (filterv (fn [id]
                                     (let [seat (get seats id)]
                                       (or (:confirmed? seat)
                                           (and (:hold-id seat)
                                                (< clock (:deadline seat)))))) ids)]
          (if (seq unavailable)
            (rejected :seat-unavailable {:unavailable-seats unavailable})
            (accepted {:hold-id rid :deadline (:deadline cmd)}
                      :seat-writes (mapv (fn [id]
                                           [id {:hold-id rid :user-id (:user-id cmd)
                                                :deadline (:deadline cmd) :confirmed? false}]) ids)
                      :hold-write [rid {:user-id (:user-id cmd) :seat-ids ids
                                        :deadline (:deadline cmd) :status :held
                                        :payment-ref nil}]))))

      (or (instance? ConfirmHold cmd) (instance? ReleaseHold cmd))
      (let [confirm? (instance? ConfirmHold cmd)
            reason (cond
                     (nil? hold) :no-such-hold
                     (not= (:user-id cmd) (:user-id hold)) :not-owner
                     (= :confirmed (:status hold)) :hold-confirmed
                     (= :released (:status hold)) :hold-released
                     (>= clock (:deadline hold)) :hold-expired)]
        (if reason
          (rejected reason nil
                    (when (and confirm?
                               (or (= reason :hold-expired)
                                   (= reason :hold-released)
                                   (and (= reason :hold-confirmed)
                                        (not= (:payment-ref cmd) (:payment-ref hold)))))
                      {:request-id rid :hold-id (:hold-id cmd)
                       :user-id (:user-id cmd) :payment-ref (:payment-ref cmd)
                       :reason reason}))
          (accepted (cond-> {:seat-ids (:seat-ids hold)} confirm?
                      (assoc :payment-ref (:payment-ref cmd)))
                    :hold-write [(:hold-id cmd)
                                 (assoc hold :status (if confirm? :confirmed :released)
                                        :payment-ref (when confirm? (:payment-ref cmd)))]
                    :seat-writes (mapv (fn [id]
                                         [id (if confirm?
                                               {:hold-id (:hold-id cmd) :user-id (:user-id hold)
                                                :deadline (:deadline hold) :confirmed? true}
                                               empty-seat)]) (:seat-ids hold)))))
      :else (throw (IllegalArgumentException. "Unknown command")))))

(defn valid-id! [value]
  (when-not (and (string? value) (<= 1 (count value) 128))
    (throw (IllegalArgumentException. "Expected a non-empty id of at most 128 characters"))))

(defn valid-ids! [ids low high distinct?]
  (when-not (and (vector? ids) (<= low (count ids) high)
                 (every? (fn [id] (and (string? id) (<= 1 (count id) 128))) ids)
                 (or (not distinct?) (= (count ids) (count (distinct ids)))))
    (throw (IllegalArgumentException. "Invalid seat-ids"))))

(defn valid-time! [value]
  (when-not (and (instance? Long value) (<= 0 value 9007199254740992))
    (throw (IllegalArgumentException. "Invalid logical time"))))

(defmodule TicketingSystem [setup topologies]
  (declare-depot setup *commands (hash-by :event-id))
  ;; Bounded grouped batches; ETL seat reads stay synchronous so each
  ;; command sees preceding writes in its event's ordered command loop.
  (set-launch-depot-dynamic-option! setup "*commands" "depot.microbatch.max.records" 200)
  (let [mb (microbatch-topology topologies "core")]
    (declare-pstate mb $$events
      {String (fixed-keys-schema
                {:clock Long :ingress-seq Long :next-comp-seq Long
                 :seats (map-schema String
                                    (fixed-keys-schema
                                      {:hold-id String :user-id String :deadline Long
                                       :confirmed? Boolean})
                                    {:subindex? true})
                 :holds (map-schema String
                                    (fixed-keys-schema
                                      {:user-id String :seat-ids (vector-schema String)
                                       :deadline Long :status clojure.lang.Keyword
                                       :payment-ref String})
                                    {:subindex? true})
                 :compensations (map-schema Long
                                            (fixed-keys-schema
                                              {:request-id String :hold-id String
                                               :user-id String :payment-ref String
                                               :reason clojure.lang.Keyword})
                                            {:subindex? true})
                 :requests (map-schema String
                                       (fixed-keys-schema
                                         {:command clojure.lang.Keyword :payload ICommand
                                          :outcome IOutcome :conflicting-attempts Long})
                                       {:subindex? true})})})
    (<<sources mb
      (source> *commands :> %mb)
      (<<batch
        (%mb :> *cmd)
        (get *cmd :event-id :> *ev)
        (local-select> [(keypath *ev :ingress-seq) (nil->val 0)] $$events :> *prev)
        (inc *prev :> *pos)
        (local-transform> [(keypath *ev :ingress-seq) (termval *pos)] $$events)
        (vector *pos *cmd :> *pair)
        (+group-by *ev
          (aggs/+vec-agg *pair :> *pairs))
        (sorted-commands *pairs :> *ordered)
        (loop<- [*remaining *ordered :> *done]
          (<<if (empty? *remaining)
            (:> true)
           (else>)
            (first *remaining :> *current)
            (get *current :request-id :> *rid)
            (local-select> (keypath *ev :requests *rid) $$events :> *existing)
            (<<if (some? *existing)
              (<<if (not= (get *existing :payload) *current)
                (local-transform>
                  [(keypath *ev :requests *rid)
                   (termval (update *existing :conflicting-attempts inc))] $$events))
             (else>)
              (local-select> (keypath *ev :clock) $$events :> *clock)
              (<<if (seat-command? *current)
                ;; At most 1000 point keys. In the recorded A/B, a yielding
                ;; read missed preceding same-batch seat writes; the internal
                ;; cause is unverified. Loop yields remain cooperative.
                (local-select> [(keypath *ev :seats) (submap (get *current :seat-ids))]
                               $$events :> *seats)
               (else>)
                (identity nil :> *seats))
              (<<if (hold-command? *current)
                (local-select> (keypath *ev :holds (get *current :hold-id)) $$events :> *hold)
               (else>)
                (identity nil :> *hold))
              (decide *current *clock *seats *hold :> *decision)
              (<<if (some? (get *decision :clock))
                (local-transform> [(keypath *ev :clock) (termval (get *decision :clock))] $$events))
              (<<if (get *decision :initialize?)
                (local-transform> [(keypath *ev :next-comp-seq) (termval 0)] $$events))
              (<<if (get *decision :hold-write)
                (local-transform>
                  [(keypath *ev :holds (first (get *decision :hold-write)))
                   (termval (second (get *decision :hold-write)))] $$events))
              (loop<- [*writes (get *decision :seat-writes) :> *written]
                (<<if (empty? *writes)
                  (:> true)
                 (else>)
                  (first *writes :> [*seat-id *seat])
                  (local-transform> [(keypath *ev :seats *seat-id) (termval *seat)] $$events)
                  (yield-if-overtime)
                  (continue> (rest *writes))))
              (<<if (get *decision :compensation)
                (local-select> [(keypath *ev :next-comp-seq) (nil->val 0)] $$events :> *last-seq)
                (inc *last-seq :> *seq)
                (local-transform> [(keypath *ev :compensations *seq)
                                   (termval (get *decision :compensation))] $$events)
                (local-transform> [(keypath *ev :next-comp-seq) (termval *seq)] $$events)
                (with-compensation-seq *decision *seq :> *result)
               (else>)
                (identity *decision :> *result))
              (local-transform>
                [(keypath *ev :requests *rid)
                 (termval {:command (command-name *current) :payload *current
                           :outcome (get *result :outcome) :conflicting-attempts 0})] $$events))
            (yield-if-overtime)
            (continue> (rest *remaining))))))

  (<<query-topology topologies "seats"
    [*ev *ids :> *result]
    (|hash *ev)
    (local-select> (keypath *ev :clock) $$events :> *clock)
    (<<if (nil? *clock)
      (identity nil :> *result)
     (else>)
      (local-select> [(keypath *ev :seats) (submap *ids)] $$events
                     {:allow-yield? true} :> *found)
      (seats-view *ids *found *clock :> *result))
    (|origin))

  (<<query-topology topologies "hold"
    [*ev *hid :> *result]
    (|hash *ev)
    (local-select> (keypath *ev :clock) $$events :> *clock)
    (<<if (nil? *clock)
      (identity nil :> *result)
     (else>)
      (local-select> (keypath *ev :holds *hid) $$events :> *h)
      (hold-view *hid *h *clock :> *result))
    (|origin))))

(defn make-client [ipc count*]
  (let [name (get-module-name TicketingSystem)
        depot (foreign-depot ipc name "*commands")
        events (foreign-pstate ipc name "$$events")
        seats (foreign-query ipc name "seats")
        hold (foreign-query ipc name "hold")
        append! (fn [cmd] (foreign-append! depot cmd :ack) (swap! count* inc) nil)]
    (reify protocol/TicketingSystemModule
      (create-event! [_ rid ev]
        (valid-id! rid) (valid-id! ev) (append! (->CreateEvent ev rid)))
      (add-seats! [_ rid ev ids]
        (valid-id! rid) (valid-id! ev) (valid-ids! ids 1 1000 true)
        (append! (->AddSeats ev rid ids)))
      (advance-clock! [_ rid ev now]
        (valid-id! rid) (valid-id! ev) (valid-time! now)
        (append! (->AdvanceClock ev rid now)))
      (hold-seats! [_ rid ev user ids deadline]
        (valid-id! rid) (valid-id! ev) (valid-id! user)
        (valid-ids! ids 1 8 true) (valid-time! deadline)
        (append! (->HoldSeats ev rid user ids deadline)))
      (confirm-hold! [_ rid ev hid user payment]
        (doseq [id [rid ev hid user payment]] (valid-id! id))
        (append! (->ConfirmHold ev rid hid user payment)))
      (release-hold! [_ rid ev hid user]
        (doseq [id [rid ev hid user]] (valid-id! id))
        (append! (->ReleaseHold ev rid hid user)))
      (get-outcome [_ ev rid]
        (valid-id! ev) (valid-id! rid)
        (outcome-map (foreign-select-one (keypath ev :requests rid) events)))
      (get-clock [_ ev]
        (valid-id! ev) (foreign-select-one (keypath ev :clock) events))
      (get-seats [_ ev ids]
        (valid-id! ev) (valid-ids! ids 1 64 false)
        (foreign-invoke-query seats ev (vec (distinct ids))))
      (get-hold [_ ev hid]
        (valid-id! ev) (valid-id! hid) (foreign-invoke-query hold ev hid))
      (get-compensations [_ ev after limit]
        (valid-id! ev)
        (when-not (and (instance? Long after) (<= 0 after)
                       (instance? Long limit) (<= 1 limit 500))
          (throw (IllegalArgumentException. "Invalid compensation page")))
        (if (= after Long/MAX_VALUE) []
            (mapv (fn [[seq record]] (assoc record :seq seq))
                  (foreign-select [(keypath ev :compensations)
                                   (sorted-map-range-from (inc after) {:max-amt limit}) ALL]
                                  events))))
      harness/Synchronizable
      (wait-for-processing! [_]
        (rtest/wait-for-microbatch-processed-count ipc name "core" @count*)))))

(defn create-module []
  (let [count* (atom 0)]
    {:module TicketingSystem :wrap-client (fn [ipc] (make-client ipc count*))}))
