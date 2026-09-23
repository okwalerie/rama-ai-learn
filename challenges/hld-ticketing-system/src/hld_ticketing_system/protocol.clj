(ns hld-ticketing-system.protocol
  "Protocol definition for the hld-ticketing-system challenge. README.md
   is the authoritative contract; these docstrings summarize it.")

(defprotocol TicketingSystemModule
  "Seat inventory with deadline-bounded holds on a per-event logical
   clock.

   All ids are non-empty Strings; times are Longs. Commands take a
   client-chosen request-id scoped to the event, return nil, and produce
   one durable outcome readable with get-outcome after
   wait-for-processing!. Structural validation precedes request-id
   handling: invalid arguments throw IllegalArgumentException
   synchronously, append nothing, and leave an unused request-id unused
   and an existing outcome untouched; queries throw the same on
   out-of-bounds arguments. The payload is the command type plus every
   argument except this and request-id, compared with =. Replay/conflict
   handling precedes business validation: same request-id + same payload
   replays the original outcome with no effect (a rejected hold stays
   rejected, an expired hold is not renewed); same request-id + different
   payload has no effect and increments :conflicting-attempts."
  (create-event! [this request-id event-id]
    "Create event-id with clock 0 and no seats. Rejected :event-exists.")
  (add-seats! [this request-id event-id seat-ids]
    "Add 1..1000 distinct available seats (duplicates are structural).
     Rejected :no-such-event, :seat-exists (any seat known; atomic).
     Accepted outcome: :added.")
  (advance-clock! [this request-id event-id now]
    "Set the event clock to now (>= current). Rejected :no-such-event,
     :clock-regression. Accepted outcome: :clock.")
  (hold-seats! [this request-id event-id user-id seat-ids deadline]
    "Hold 1..8 distinct seats (duplicates are structural) for user-id
     until clock >= deadline; the hold-id is request-id. Rejected in order: :no-such-event,
     :no-such-seat, :deadline-passed (deadline <= clock),
     :seat-unavailable (any seat confirmed or under an active hold;
     outcome carries :unavailable-seats). Seats under expired or released
     holds are available. Accepted outcome: :hold-id :deadline.")
  (confirm-hold! [this request-id event-id hold-id user-id payment-ref]
    "Confirm the hold's seats as sold on successful payment payment-ref.
     Rejected in order: :no-such-event, :no-such-hold, :not-owner,
     :hold-confirmed, :hold-released, :hold-expired (clock >= deadline,
     never revived). A compensation record is appended (outcome
     :compensation-seq) for :hold-expired, :hold-released, and for
     :hold-confirmed with a different payment-ref. The record is logical,
     tied to this rejected request-id: no refund is executed and records
     are not deduplicated across request-ids sharing a payment-ref.
     Accepted outcome: :seat-ids :payment-ref.")
  (release-hold! [this request-id event-id hold-id user-id]
    "Release an active hold. Rejected in order: :no-such-event,
     :no-such-hold, :not-owner, :hold-confirmed, :hold-released,
     :hold-expired. Never affects seats owned by another hold. Accepted
     outcome: :seat-ids.")
  (get-outcome [this event-id request-id]
    "Outcome map {:status :accepted|:rejected :command kw :reason kw
     :conflicting-attempts Long ...} or nil if never processed.")
  (get-clock [this event-id]
    "Event clock (Long) or nil if the event does not exist.")
  (get-seats [this event-id seat-ids]
    "Map seat-id -> {:state :available|:held|:confirmed :hold-id :user-id}
     as of the current clock (nil value for unknown seats), for 1..64
     seat-ids (duplicates allowed; they collapse to one key). nil if the
     event does not exist.")
  (get-hold [this event-id hold-id]
    "{:hold-id :user-id :seat-ids :deadline :state :payment-ref} with
     :state :active|:expired|:released|:confirmed, or nil.")
  (get-compensations [this event-id after-seq limit]
    "Compensation records with :seq > after-seq, ascending, at most limit
     (1..500): {:seq :request-id :hold-id :user-id :payment-ref :reason}.
     Seqs start at 1 and are contiguous. Empty vector when none."))
