(ns hld-stock-exchange.protocol
  "Protocol definition for the hld-stock-exchange challenge. README.md is
   the authoritative contract; these docstrings summarize it.")

(defprotocol StockExchangeModule
  "Per-symbol limit order books with strict price-time priority.

   All ids are non-empty Strings; prices and quantities are positive
   Longs. Commands take a client-chosen request-id scoped to the symbol,
   return nil, and produce one durable outcome readable with get-outcome
   after wait-for-processing!. Structural validation precedes request-id
   handling: invalid arguments throw IllegalArgumentException
   synchronously, append nothing, and leave an unused request-id unused
   and an existing outcome untouched; queries throw the same on
   out-of-bounds arguments. The payload is the command type plus every
   argument except this and request-id, compared with =. Replay/conflict
   handling precedes business validation: same request-id + same payload
   replays the original outcome with no effect (never a fresh order or
   priority); same request-id + different payload has no effect and
   increments :conflicting-attempts."
  (submit-limit-order! [this request-id symbol account-id side price qty tif]
    "Submit a limit order (order-id = request-id), side :buy|:sell, tif
     :gtc|:ioc. Matches best-price-first, FIFO within price, at the
     resting order's price; a :gtc remainder rests behind earlier orders
     at its price, an :ioc remainder is cancelled and never rests.
     Self-trades allowed. No business rejections. Accepted outcome:
     :order-id :seq :filled-qty :resting-qty :cancelled-qty :trade-count
     :first-trade-seq :last-trade-seq.")
  (cancel-order! [this request-id symbol order-id account-id]
    "Remove an open order's remaining quantity. Rejected in order:
     :no-such-order, :not-owner, :order-not-open. Accepted outcome:
     :order-id :cancelled-qty.")
  (get-outcome [this symbol request-id]
    "Outcome map {:status :accepted|:rejected :command kw :reason kw
     :conflicting-attempts Long ...} or nil if never processed.")
  (get-order [this symbol order-id]
    "{:order-id :account-id :side :price :qty :tif :seq :filled-qty
     :remaining-qty :cancelled-qty :state :open|:filled|:cancelled} or
     nil.")
  (get-depth [this symbol side levels]
    "Up to levels (1..50) aggregated resting levels on side, best first:
     [{:price :qty :order-count} ...]. Empty vector for an empty side or
     unknown symbol.")
  (get-trades [this symbol after-seq limit]
    "Trades with :seq > after-seq, ascending, at most limit (1..500):
     {:seq :price :qty :maker-order-id :taker-order-id :maker-account-id
     :taker-account-id :taker-side}. Seqs start at 1 and are contiguous.
     Empty vector when none."))
