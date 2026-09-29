(ns hld-hotel-reservation.module
  (:require [com.rpl.rama :refer :all]
            [com.rpl.rama.path :refer :all]
            [com.rpl.rama.aggs :as aggs]
            [com.rpl.rama.test :as rtest]
            [hld-hotel-reservation.protocol :as p]
            [rama-challenges.harness :as harness]))

(defrecord Command [property-id request-id op args])

(defn valid-id [x]
  (when-not (and (string? x) (<= 1 (count x) 128))
    (throw (IllegalArgumentException. "Expected nonempty id of at most 128 characters")))
  x)

(defn valid-long [x low high]
  (when-not (and (instance? Long x) (<= low x high))
    (throw (IllegalArgumentException. (str "Expected Long in [" low ", " high "]"))))
  x)

(defn valid-stay [a b]
  (valid-long a 0 1099511627776)
  (valid-long b 0 1099511627776)
  (when-not (<= 1 (- b a) 30)
    (throw (IllegalArgumentException. "Stay must contain 1..30 nights"))))

(defn night-view [n v]
  (when v (assoc v :night n)))

(defn availability-view [a b nights]
  (mapv #(night-view % (get nights %)) (range a b)))

(defn missing-nights [a b nights]
  (filterv #(not (contains? nights %)) (range a b)))

(defn lacking-nights [a b nights quantity]
  (filterv #(< (:available (get nights %)) quantity) (range a b)))

(defn stay-total [a b nights quantity]
  (reduce + 0 (map #(* quantity (:rate (get nights %))) (range a b))))

(defn sorted-commands [pairs]
  (mapv second (sort-by first pairs)))

(defn render-outcome [record]
  (when record
    (assoc (:outcome record) :conflicting-attempts (:conflicting-attempts record))))

(defn simple-outcome [op reason extras]
  (merge {:status (if reason :rejected :accepted) :command op}
         (when reason {:reason reason})
         (when-not reason extras)))

(defn reserve-rejection [reason missing lacking]
  (cond-> (simple-outcome :reserve reason nil)
    (= reason :night-not-configured) (assoc :nights missing)
    (= reason :insufficient-capacity) (assoc :nights lacking)))

(defmodule HotelModule [setup topologies]
  (declare-depot setup *commands (hash-by :property-id))
  (set-launch-depot-dynamic-option! setup "*commands" "depot.microbatch.max.records" 1000)
  (let [mb (microbatch-topology topologies "core")]
    (declare-pstate mb $$properties
      {String (fixed-keys-schema
        {:next-seq Long :ingress-seq Long
         :room-types (map-schema String
                       (fixed-keys-schema
                         {:exists? Boolean
                          :nights (map-schema Long
                                    (fixed-keys-schema {:capacity Long :rate Long :available Long})
                                    {:subindex? true})})
                       {:subindex? true})
         :bookings (map-schema String
                     (fixed-keys-schema
                       {:guest-id String :room-type String :checkin Long :checkout Long
                        :quantity Long :total Long :state clojure.lang.Keyword :seq Long})
                     {:subindex? true})
         :events (map-schema Long
                   (fixed-keys-schema
                     {:type clojure.lang.Keyword :request-id String :booking-id String
                      :guest-id String :room-type String :checkin Long :checkout Long
                      :quantity Long :total Long})
                   {:subindex? true})
         :requests (map-schema String
                     (fixed-keys-schema
                       {:payload Command :outcome clojure.lang.IPersistentMap
                        :conflicting-attempts Long})
                     {:subindex? true})})})
    (<<sources mb
      (source> *commands :> %mb)
      (<<batch
        (%mb :> *cmd)
        (get *cmd :property-id :> *p)
        (local-select> [(keypath *p :ingress-seq) (nil->val 0)] $$properties :> *previous)
        (inc *previous :> *position)
        (local-transform> [(keypath *p :ingress-seq) (termval *position)] $$properties)
        (vector *position *cmd :> *pair)
        (+group-by *p (aggs/+vec-agg *pair :> *pairs))
        (sorted-commands *pairs :> *ordered)
        (loop<- [*remaining *ordered :> *done]
          (<<if (empty? *remaining)
            (:> true)
           (else>)
            (first *remaining :> *command)
            (get *command :request-id :> *rid)
            (get *command :op :> *op)
            (get *command :args :> *args)
            (local-select> [(keypath *p :requests *rid)] $$properties :> *prior)
            (<<if *prior
              (<<if (not= (get *prior :payload) *command)
                (local-transform> [(keypath *p :requests *rid :conflicting-attempts)
                                   (term inc)] $$properties))
             (else>)
              (local-select> [(keypath *p :next-seq)] $$properties :> *seq)
              (<<cond
                (case> (= *op :create-property))
                (<<if (some? *seq)
                  (identity {:status :rejected :command *op :reason :property-exists} :> *outcome)
                 (else>)
                  (local-transform> [(keypath *p :next-seq) (termval 0)] $$properties)
                  (identity {:status :accepted :command *op} :> *outcome))

                (case> (= *op :create-room-type))
                (<<do
                  (first *args :> *rt)
                  (local-select> [(keypath *p :room-types *rt :exists?)] $$properties :> *exists)
                  (<<cond
                    (case> (nil? *seq)) (identity :no-such-property :> *reason)
                    (case> *exists) (identity :room-type-exists :> *reason)
                    (default>) (identity nil :> *reason))
                  (<<if (nil? *reason)
                    (local-transform> [(keypath *p :room-types *rt :exists?) (termval true)] $$properties))
                  (simple-outcome *op *reason nil :> *outcome))

                (case> (= *op :init-night))
                (<<do
                  (nth *args 0 :> *rt) (nth *args 1 :> *n)
                  (nth *args 2 :> *capacity) (nth *args 3 :> *rate)
                  (local-select> [(keypath *p :room-types *rt :exists?)] $$properties :> *exists)
                  (local-select> [(keypath *p :room-types *rt :nights *n)] $$properties :> *night)
                  (<<cond
                    (case> (nil? *seq)) (identity :no-such-property :> *reason)
                    (case> (not *exists)) (identity :no-such-room-type :> *reason)
                    (case> *night) (identity :night-exists :> *reason)
                    (default>) (identity nil :> *reason))
                  (<<if (nil? *reason)
                    (local-transform> [(keypath *p :room-types *rt :nights *n)
                                       (termval {:capacity *capacity :rate *rate :available *capacity})] $$properties))
                  (simple-outcome *op *reason nil :> *outcome))

                (case> (= *op :set-rate))
                (<<do
                  (nth *args 0 :> *rt) (nth *args 1 :> *n) (nth *args 2 :> *rate)
                  (local-select> [(keypath *p :room-types *rt :exists?)] $$properties :> *exists)
                  (local-select> [(keypath *p :room-types *rt :nights *n)] $$properties :> *night)
                  (<<cond
                    (case> (nil? *seq)) (identity :no-such-property :> *reason)
                    (case> (not *exists)) (identity :no-such-room-type :> *reason)
                    (case> (nil? *night)) (identity :night-not-configured :> *reason)
                    (default>) (identity nil :> *reason))
                  (<<if (nil? *reason)
                    (local-transform> [(keypath *p :room-types *rt :nights *n)
                                       (termval (assoc *night :rate *rate))] $$properties))
                  (simple-outcome *op *reason {:rate *rate} :> *outcome))

                (case> (= *op :reserve))
                (<<do
                  (nth *args 0 :> *rt) (nth *args 1 :> *guest)
                  (nth *args 2 :> *ci) (nth *args 3 :> *co) (nth *args 4 :> *quantity)
                  (local-select> [(keypath *p :room-types *rt :exists?)] $$properties :> *exists)
                  (<<if (and> (some? *seq) *exists)
                    (local-select> [(keypath *p :room-types *rt :nights)
                                    (sorted-map-range *ci *co)] $$properties :> *nights)
                   (else>) (identity {} :> *nights))
                  (missing-nights *ci *co *nights :> *missing)
                  (<<cond
                    (case> (nil? *seq)) (identity :no-such-property :> *reason)
                    (case> (not *exists)) (identity :no-such-room-type :> *reason)
                    (case> (seq *missing)) (identity :night-not-configured :> *reason)
                    (default>) (identity nil :> *reason))
                  (<<if (nil? *reason)
                    (lacking-nights *ci *co *nights *quantity :> *lacking)
                   (else>) (identity [] :> *lacking))
                  (<<if (seq *lacking)
                    (identity :insufficient-capacity :> *final-reason)
                   (else>) (identity *reason :> *final-reason))
                  (<<if *final-reason
                    (reserve-rejection *final-reason *missing *lacking :> *outcome)
                   (else>)
                    (stay-total *ci *co *nights *quantity :> *total)
                    (inc *seq :> *new-seq)
                    (loop<- [*n *ci :> *written]
                      (<<if (= *n *co)
                        (:> true)
                       (else>)
                        (local-transform> [(keypath *p :room-types *rt :nights *n)
                                           (termval (update (get *nights *n) :available - *quantity))] $$properties)
                        (continue> (inc *n))))
                    (local-transform> [(keypath *p :bookings *rid)
                                       (termval {:guest-id *guest :room-type *rt :checkin *ci :checkout *co
                                                 :quantity *quantity :total *total :state :confirmed :seq *new-seq})] $$properties)
                    (local-transform> [(keypath *p :events *new-seq)
                                       (termval {:type :reserved :request-id *rid :booking-id *rid
                                                 :guest-id *guest :room-type *rt :checkin *ci :checkout *co
                                                 :quantity *quantity :total *total})] $$properties)
                    (local-transform> [(keypath *p :next-seq) (termval *new-seq)] $$properties)
                    (identity {:status :accepted :command *op :booking-id *rid :total *total
                               :nights (- *co *ci) :seq *new-seq} :> *outcome)))

                (case> (= *op :cancel-booking))
                (<<do
                  (nth *args 0 :> *bid) (nth *args 1 :> *guest)
                  (local-select> [(keypath *p :bookings *bid)] $$properties :> *booking)
                  (<<cond
                    (case> (nil? *seq)) (identity :no-such-property :> *reason)
                    (case> (nil? *booking)) (identity :no-such-booking :> *reason)
                    (case> (not= *guest (get *booking :guest-id))) (identity :not-guest :> *reason)
                    (case> (= :cancelled (get *booking :state))) (identity :booking-cancelled :> *reason)
                    (default>) (identity nil :> *reason))
                  (<<if *reason
                    (identity {:status :rejected :command *op :reason *reason} :> *outcome)
                   (else>)
                    (get *booking :room-type :> *rt)
                    (get *booking :checkin :> *ci)
                    (get *booking :checkout :> *co)
                    (get *booking :quantity :> *quantity)
                    (local-select> [(keypath *p :room-types *rt :nights)
                                    (sorted-map-range *ci *co)] $$properties :> *nights)
                    (inc *seq :> *new-seq)
                    (loop<- [*n *ci :> *written]
                      (<<if (= *n *co)
                        (:> true)
                       (else>)
                        (local-transform> [(keypath *p :room-types *rt :nights *n)
                                           (termval (update (get *nights *n) :available + *quantity))] $$properties)
                        (continue> (inc *n))))
                    (local-transform> [(keypath *p :bookings *bid)
                                       (termval (assoc *booking :state :cancelled))] $$properties)
                    (local-transform> [(keypath *p :events *new-seq)
                                       (termval (merge (select-keys *booking
                                                        [:guest-id :room-type :checkin :checkout :quantity :total])
                                                       {:type :cancelled :request-id *rid :booking-id *bid}))] $$properties)
                    (local-transform> [(keypath *p :next-seq) (termval *new-seq)] $$properties)
                    (identity {:status :accepted :command *op :booking-id *bid :seq *new-seq} :> *outcome))))
              (local-transform> [(keypath *p :requests *rid)
                                 (termval {:payload *command :outcome *outcome
                                           :conflicting-attempts 0})] $$properties))
            (yield-if-overtime)
            (continue> (rest *remaining))))))
  (<<query-topology topologies "availability" [*p *rt *ci *co :> *result]
    (|hash *p)
    (local-select> [(keypath *p :room-types *rt :exists?)] $$properties :> *exists)
    (<<if *exists
      (local-select> [(keypath *p :room-types *rt :nights)
                      (sorted-map-range *ci *co)] $$properties :> *nights)
      (availability-view *ci *co *nights :> *result)
     (else>)
      (identity nil :> *result))
    (|origin))))

(defn create-module []
  (let [name (get-module-name HotelModule)]
    {:module HotelModule
     :wrap-client
     (fn [ipc]
       (let [depot (foreign-depot ipc name "*commands")
             state (foreign-pstate ipc name "$$properties")
             availability (foreign-query ipc name "availability")
             submit (fn [rid property op args]
                      (valid-id rid) (valid-id property)
                      (foreign-append! depot (->Command property rid op args))
                      nil)]
         (reify
           harness/Synchronizable
           (wait-for-processing! [_]
             (let [partitions (:num-partitions (foreign-object-info depot))
                   target (reduce + (map (fn [partition]
                                           (:end-offset (foreign-depot-partition-info depot partition)))
                                         (range partitions)))]
               (rtest/wait-for-microbatch-processed-count ipc name "core" target)))
           p/HotelReservationModule
           (create-property! [_ rid property]
             (submit rid property :create-property []))
           (create-room-type! [_ rid property rt]
             (valid-id rt) (submit rid property :create-room-type [rt]))
           (init-night! [_ rid property rt n capacity rate]
             (valid-id rt) (valid-long n 0 1099511627776)
             (valid-long capacity 0 1000000) (valid-long rate 0 1000000000)
             (submit rid property :init-night [rt n capacity rate]))
           (set-rate! [_ rid property rt n rate]
             (valid-id rt) (valid-long n 0 1099511627776)
             (valid-long rate 0 1000000000)
             (submit rid property :set-rate [rt n rate]))
           (reserve! [_ rid property rt guest ci co quantity]
             (valid-id rt) (valid-id guest) (valid-stay ci co)
             (valid-long quantity 1 100)
             (submit rid property :reserve [rt guest ci co quantity]))
           (cancel-booking! [_ rid property bid guest]
             (valid-id bid) (valid-id guest)
             (submit rid property :cancel-booking [bid guest]))
           (get-outcome [_ property rid]
             (valid-id property) (valid-id rid)
             (render-outcome (foreign-select-one [(keypath property :requests rid)] state)))
           (get-night [_ property rt n]
             (valid-id property) (valid-id rt) (valid-long n 0 1099511627776)
             (night-view n (foreign-select-one [(keypath property :room-types rt :nights n)] state)))
           (get-availability [_ property rt ci co]
             (valid-id property) (valid-id rt) (valid-stay ci co)
             (foreign-invoke-query availability property rt ci co))
           (get-booking [_ property bid]
             (valid-id property) (valid-id bid)
             (some-> (foreign-select-one [(keypath property :bookings bid)] state)
                     (assoc :booking-id bid)))
           (get-booking-events [_ property after limit]
             (valid-id property) (valid-long after 0 Long/MAX_VALUE)
             (valid-long limit 1 500)
             (if (= after Long/MAX_VALUE)
               []
               (mapv (fn [[seq event]] (assoc event :seq seq))
                     (foreign-select [(keypath property :events)
                                      (sorted-map-range-from (inc after) {:max-amt limit}) ALL] state)))))))}))
