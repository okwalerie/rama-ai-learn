(ns hld-hotel-reservation.protocol
  "Protocol definition for the hld-hotel-reservation challenge. README.md
   is the authoritative contract; these docstrings summarize it.")

(defprotocol HotelReservationModule
  "Per-property room-type inventory with per-night capacity and rate,
   atomic multi-night reservations, and cancellation.

   All ids are non-empty Strings; nights, quantities, capacities, rates
   are Longs. A stay [checkin, checkout) covers nights checkin..checkout-1
   (1..30 nights). Commands take a client-chosen request-id scoped to
   the property, return nil, and produce one durable outcome readable
   with get-outcome after wait-for-processing!. Structural validation
   precedes request-id handling: invalid arguments throw
   IllegalArgumentException synchronously, append nothing, and leave an
   unused request-id unused and an existing outcome untouched; queries
   throw the same on out-of-bounds arguments. The payload is the command
   type plus every argument except this and request-id, compared with =.
   Replay/conflict handling precedes business validation: same
   request-id + same payload replays the original outcome with no
   effect; same request-id + different payload has no effect and
   increments :conflicting-attempts. No overbooking: booked quantity
   never exceeds capacity."
  (create-property! [this request-id property-id]
    "Create a property. Rejected :property-exists.")
  (create-room-type! [this request-id property-id room-type]
    "Create a room type. Rejected in order: :no-such-property,
     :room-type-exists.")
  (init-night! [this request-id property-id room-type night capacity rate]
    "Configure a night with immutable capacity (0..10^6) and initial rate
     (0..10^9). Rejected in order: :no-such-property, :no-such-room-type,
     :night-exists.")
  (set-rate! [this request-id property-id room-type night rate]
    "Change a configured night's rate; existing booking totals are
     unaffected. Rejected in order: :no-such-property,
     :no-such-room-type, :night-not-configured. Accepted outcome: :rate.")
  (reserve! [this request-id property-id room-type guest-id checkin checkout quantity]
    "Reserve quantity (1..100) rooms for every night of [checkin,
     checkout) atomically; booking-id = request-id; :total locked as the
     sum of rate(night) * quantity at processing time. Rejected in
     order: :no-such-property, :no-such-room-type,
     :night-not-configured, :insufficient-capacity (the last two carry
     :nights, the offending nights ascending). Accepted outcome:
     :booking-id :total :nights :seq (the :reserved event seq, the
     booking's seq forever).")
  (cancel-booking! [this request-id property-id booking-id guest-id]
    "Cancel a confirmed booking, restoring exactly its quantity to each
     of its nights once. Rejected in order: :no-such-property,
     :no-such-booking, :not-guest, :booking-cancelled. Accepted outcome:
     :booking-id :seq (the :cancelled event's own seq; the booking's
     :seq is not overwritten).")
  (get-outcome [this property-id request-id]
    "Outcome map {:status :accepted|:rejected :command kw :reason kw
     :conflicting-attempts Long ...} or nil if never processed.")
  (get-night [this property-id room-type night]
    "{:night :capacity :rate :available} or nil if unknown.")
  (get-availability [this property-id room-type checkin checkout]
    "Vector with one get-night map (or nil when unconfigured) per night
     of [checkin, checkout) ascending; nil if the property or room type
     is unknown.")
  (get-booking [this property-id booking-id]
    "{:booking-id :guest-id :room-type :checkin :checkout :quantity :total
     :state :confirmed|:cancelled :seq} or nil; :seq is the original
     :reserved event seq, unchanged by cancellation.")
  (get-booking-events [this property-id after-seq limit]
    "Events with :seq > after-seq, ascending, at most limit (1..500):
     {:seq :type :reserved|:cancelled :request-id :booking-id :guest-id
     :room-type :checkin :checkout :quantity :total}. Seqs start at 1 and
     are contiguous. :request-id identifies the producing reserve/cancel
     command; :booking-id identifies the original reservation.
     Empty vector when none."))
