(ns hld-payment-system.protocol
  "Protocol definition for the hld-payment-system challenge. README.md is
   the authoritative contract; these docstrings summarize it.")

(defprotocol PaymentSystemModule
  "Per-tenant closed double-entry ledger in a single currency.

   All ids are non-empty Strings; amounts are positive Long minor units.
   Commands take a client-chosen request-id scoped to the tenant, return
   nil, and produce one durable outcome readable with get-outcome after
   wait-for-processing!. Structural validation precedes request-id
   handling: invalid arguments throw IllegalArgumentException
   synchronously, append nothing, and leave an unused request-id unused
   and an existing outcome untouched; queries throw the same on
   out-of-bounds arguments. The payload is the command type plus every
   argument except this and request-id, compared with =. Replay/conflict
   handling precedes business validation: same request-id + same payload
   replays the original outcome with no effect; same request-id +
   different payload has no effect and increments :conflicting-attempts.
   Every tenant has a clearing account with account-id \"clearing\", the
   only account allowed to go negative; clients cannot target it."
  (create-tenant! [this request-id tenant-id currency]
    "Create a tenant in currency (3 uppercase letters) with its clearing
     account at 0. Rejected :tenant-exists.")
  (create-account! [this request-id tenant-id account-id kind]
    "Create an account of kind :customer or :merchant at balance 0.
     Rejected in order: :no-such-tenant, :account-exists.")
  (fund! [this request-id tenant-id account-id amount]
    "Move amount from clearing into account-id (journal postings, in
     order: clearing -amount, then account +amount). Rejected in order:
     :no-such-tenant, :no-such-account. Accepted outcome: :seq :balance.")
  (charge! [this request-id tenant-id customer-id merchant-id amount]
    "Move amount from a :customer account to a :merchant account
     (postings: customer -amount, then merchant +amount); the charge-id
     is request-id. Its :seq is the charge transaction's seq forever;
     refunds never change it. Rejected in order: :no-such-tenant,
     :no-such-account, :wrong-account-kind, :insufficient-funds.
     Accepted outcome: :charge-id :seq :customer-balance.")
  (refund! [this request-id tenant-id charge-id amount]
    "Return amount of an accepted charge from its merchant to its
     customer (postings: merchant -amount, then customer +amount);
     partial refunds may repeat while the total stays within the charge
     amount. :seq in the outcome is the refund transaction's own seq. Rejected in order: :no-such-tenant,
     :no-such-charge, :refund-exceeds-charge, :insufficient-funds.
     Accepted outcome: :refund-id :charge-id :seq :refunded-total.")
  (get-outcome [this tenant-id request-id]
    "Outcome map {:status :accepted|:rejected :command kw :reason kw
     :conflicting-attempts Long ...} or nil if never processed.")
  (get-tenant [this tenant-id]
    "{:tenant-id :currency} or nil.")
  (get-account [this tenant-id account-id]
    "{:account-id :kind :balance} or nil; \"clearing\" is valid here.")
  (get-balance [this tenant-id account-id]
    "Balance (Long) or nil if tenant or account is unknown.")
  (get-charge [this tenant-id charge-id]
    "{:charge-id :customer-id :merchant-id :amount :refunded-total :seq}
     for an accepted charge, or nil. :seq is the original charge
     transaction's journal seq and is unchanged by refunds.")
  (get-journal [this tenant-id after-seq limit]
    "Transactions with :seq > after-seq, ascending, at most limit
     (1..500): {:seq :request-id :type :fund|:charge|:refund :charge-id
     :postings [{:account-id :delta} ...]} with exactly two postings,
     source (negative delta) first then destination (positive delta),
     summing to 0; deltas are signed balance changes, not debit/credit
     conventions.
     Seqs start at 1 and are contiguous. Empty vector when none."))
