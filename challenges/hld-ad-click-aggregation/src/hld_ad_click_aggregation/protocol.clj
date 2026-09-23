(ns hld-ad-click-aggregation.protocol
  "Protocol definition for the hld-ad-click-aggregation challenge.

   All state is scoped by campaign. Request IDs are unique within a
   campaign; the same request-id in two campaigns is two different clicks.
   Each campaign has an explicit, monotonic watermark, initially 0.
   Clicks fall into 60-unit tumbling windows: the window for timestamp ts
   starts at ts - (ts mod 60) and ends at start + 60.

   A counters map has the shape
     {:clicks <int> :billed-clicks <int> :invalid-clicks <int>
      :fraud-clicks <int> :billed-spend <int>}
   where :clicks = :billed-clicks + :invalid-clicks + :fraud-clicks and
   :billed-spend is the sum of :spend over billed clicks only.

   See README.md for the full admission and disposition rules with worked
   numbers.")

(defprotocol AdClickAggregation
  "Exactly-once click accounting with tumbling windows and an audit trail."

  (advance-watermark! [this campaign-id watermark]
    "Write. Advance the campaign watermark to `watermark` (integer >= 0).
     Monotonic: a value <= the current watermark is a no-op. A window with
     end E is open while watermark < E + 120 and closed once
     watermark >= E + 120.")

  (record-click! [this campaign-id request-id timestamp geo device spend valid? fraud?]
    "Write. Offer one click. `timestamp` is an integer >= 0 (future
     timestamps relative to the watermark are admitted); `geo` and `device`
     are non-empty strings; `spend` is an integer >= 0; `valid?` and
     `fraud?` are booleans supplied by the caller.

     If `request-id` has already been seen for this campaign, the call has
     no effect whatsoever (the first arrival's disposition is frozen, even
     if the replay arrives after the window has closed).

     Otherwise the click is audited with exactly one disposition, decided
     in this order against the watermark W at the time the write is applied
     and the click's window end E:
       1. W >= E + 120        -> :late    (audited only; no aggregate change)
       2. fraud? is true      -> :fraud   (counted, not billed)
       3. valid? is false     -> :invalid (counted, not billed)
       4. otherwise           -> :billed  (counted and billed)
     Dispositions :fraud, :invalid, and :billed update the window's totals
     and its [geo device] breakdown entry.")

  (get-watermark [this campaign-id]
    "Read. Returns the campaign's current watermark; 0 for an unknown
     campaign.")

  (get-request [this campaign-id request-id]
    "Read. Returns nil if the request has never been recorded for the
     campaign, otherwise the immutable audit record
       {:request-id <str> :timestamp <int> :window-start <int>
        :geo <str> :device <str> :spend <int> :valid? <bool> :fraud? <bool>
        :disposition <:billed|:invalid|:fraud|:late> :watermark <int>}
     where :watermark is the campaign watermark observed when the first
     arrival was applied. Replays never change this record.")

  (get-window [this campaign-id window-start]
    "Read. `window-start` is a multiple of 60. Returns nil if no click has
     been counted (disposition :billed, :invalid, or :fraud) into the
     window, otherwise
       {:window-start <int>
        :totals    <counters map>
        :breakdown {[geo device] <counters map> ...}}
     :breakdown has one entry per distinct [geo device] pair counted into
     the window. Late clicks and replays contribute nothing.")

  (get-windows [this campaign-id start end]
    "Read. `start` and `end` are multiples of 60 with start <= end. Returns
     a vector of the maps `get-window` would return for every window with
     start <= window-start < end that has at least one counted click,
     ascending by :window-start. Empty vector when none."))
