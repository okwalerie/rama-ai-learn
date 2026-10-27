;; IMPORTANT: Before modifying this file, re-read PLAN.md and check pending todos.
;; Adhere to all previously decided design decisions.

(ns bank-transfer-module.module
  (:require
   [com.rpl.rama :refer :all]
   [com.rpl.rama.path :refer :all]
   [com.rpl.rama.test :as rtest]
   [bank-transfer-module.protocol :as proto]
   [rama-challenges.harness :as harness]))

;; Depot records. Both carry `account` — the user whose balance is debited or
;; credited by the record's own first action (the depositor; the sender) — so a
;; single depot partitioner `(hash-by :account)` serves both and every record
;; lands on the task that owns the first PState write it performs.
(defrecord Deposit [account amt])
(defrecord Transfer [transfer-id account to-user-id amt])

;; One history entry, shared by both directions. `:other-user-id` is the
;; counterparty: the receiver in an `:outgoing` entry, the sender in an
;; `:incoming` entry. Direction is carried by which collection the entry sits
;; in, so every instance at every position has an identical field set.
;;
;; `:transfer-id` and `:amt` are `Object`, and that is deliberate. The contract
;; types exactly one thing — user ids are strings — and says nothing about the
;; type of a transfer id or an amount. Both are client-supplied values this
;; module only stores and hands back verbatim, so narrowing them would be the
;; module inventing a constraint the contract does not impose. The cost of
;; inventing it is not a rejected write: a value that violates a PState schema
;; throws inside microbatch processing, a throwing record is retried forever,
;; and the topology stops making progress for EVERY account — one client
;; passing a keyword transfer id takes the whole cluster down permanently.
;; `Object` is the schema for a value whose type the contract leaves open.
;;
;; `:amt` at `Object` is also what keeps the module internally consistent:
;; `:balance` is unbounded-magnitude on purpose, so a balance can exceed
;; Long/MAX_VALUE, and a `Long` `:amt` would leave such a balance impossible to
;; transfer in one operation.
;;
;; `:other-user-id` and `:success?` stay narrow: the first is a user id, which
;; the contract does type, and the second is produced by this module rather than
;; by the caller.
(def TRANSFER-ENTRY
  (fixed-keys-schema {:transfer-id   Object
                      :other-user-id String
                      :amt           Object
                      :success?      Boolean}))

;; Malformed-input rejection. Everything these predicates reject would otherwise
;; throw on a PState write or inside a numeric comparison, and a throw in
;; microbatch processing retries forever and blocks the topology — so a record
;; that cannot be applied has to be dropped in dataflow, never allowed to throw.
;;
;; `integer?` rather than `number?`: balances are an integral quantity
;; (`:balance` is `clojure.lang.BigInt`), so a fractional amount has no
;; representable balance semantics. Any integral amount of any magnitude is
;; accepted, since `:amt` and the balance arithmetic are both unbounded.
(defn- valid-deposit?
  [account amt]
  (and (string? account) (integer? amt)))

(defn- valid-transfer?
  [account to-user-id amt]
  (and (string? account) (string? to-user-id) (integer? amt)))

(defmodule BankTransferModule
  [setup topologies]
  (declare-depot setup *bank-depot (hash-by :account))

  (let [mb (microbatch-topology topologies "bank")]
    (declare-pstate mb $$accounts
      {String (fixed-keys-schema
                {:balance  clojure.lang.BigInt
                 :outgoing (vector-schema TRANSFER-ENTRY {:subindex? true})
                 :incoming (vector-schema TRANSFER-ENTRY {:subindex? true})})})

    (<<sources mb
      (source> *bank-depot :> %mb)
      (%mb :> *event)
      (<<subsource *event

        ;; ---- Deposit -------------------------------------------------------
        ;; Already on hash(account): zero partitioner hops. One read+write.
        ;; `nil->val` creates the account implicitly for a never-seen user.
        (case> Deposit :> {:keys [*account *amt]})
        ;; Malformed first: `>=` below would itself throw on a non-number.
        (filter> (valid-deposit? *account *amt))
        (filter> (>= *amt 0))
        ;; `0N` cannot be embedded directly in dataflow — a BigInt is not one of
        ;; the constant types the object cache allows — so it is produced at
        ;; runtime by a clojure.core call on a primitive constant instead.
        (bigint 0 :> *zero)
        (local-transform> [(keypath *account :balance)
                           (nil->val *zero) (term (partial + *amt))]
                          $$accounts)

        ;; ---- Transfer ------------------------------------------------------
        ;; Already on hash(from): the funds check and the debit are local, which
        ;; is what serializes concurrent transfers out of one account.
        (case> Transfer :> {:keys [*transfer-id *account *to-user-id *amt]})
        ;; Rejected wholesale, before any write: a record that cannot be applied
        ;; to both sides must not be applied to one of them. `*transfer-id` is
        ;; unconstrained — it is stored at an `Object` position.
        (filter> (valid-transfer? *account *to-user-id *amt))
        (bigint 0 :> *zero)
        (local-select> [(keypath *account :balance)] $$accounts :> *bal0)
        (or> *bal0 *zero :> *bal)
        ;; Decided ONCE, on the sender's task, and carried across the hop as a
        ;; plain dataflow variable so both history entries can never disagree.
        (and> (>= *amt 0) (>= *bal *amt) :> *success?)
        (identity {:transfer-id   *transfer-id
                   :other-user-id *to-user-id
                   :amt           *amt
                   :success?      *success?}
                  :> *out-entry)
        ;; The debit and the `:outgoing` append live in the same account record,
        ;; so one `multi-path` loads that record once instead of twice. The
        ;; balance value is already in hand, so `termval` (no second read).
        ;; Both writes happen BEFORE the partitioner below, so the next transfer
        ;; out of this account sees the debited balance.
        (<<if *success?
          (bigint (- *bal *amt) :> *new-bal)
          (local-transform> [(keypath *account)
                             (multi-path [:balance  (termval *new-bal)]
                                         [:outgoing AFTER-ELEM (termval *out-entry)])]
                            $$accounts)
         (else>)
          ;; A rejected transfer changes no balance, but is still history.
          (local-transform> [(keypath *account :outgoing) AFTER-ELEM
                             (termval *out-entry)]
                            $$accounts))

        ;; The only hop in the module. Same microbatch attempt, so the debit,
        ;; the credit and both history entries settle as one unit.
        (|hash *to-user-id)
        (identity {:transfer-id   *transfer-id
                   :other-user-id *account
                   :amt           *amt
                   :success?      *success?}
                  :> *in-entry)
        (<<if *success?
          (local-transform> [(keypath *to-user-id)
                             (multi-path [:balance  (nil->val *zero) (term (partial + *amt))]
                                         [:incoming AFTER-ELEM (termval *in-entry)])]
                            $$accounts)
         (else>)
          (local-transform> [(keypath *to-user-id :incoming) AFTER-ELEM
                             (termval *in-entry)]
                            $$accounts))))))

(def module-name* (get-module-name BankTransferModule))

(defn- ->outgoing-info
  "Shapes a stored entry into the protocol's outgoing pair. The map is built
   key-by-key so it carries EXACTLY the three keys the protocol specifies —
   callers may compare maps for equality."
  [e]
  [(:transfer-id e) {:to-user-id (:other-user-id e)
                     :amt        (:amt e)
                     :success?   (:success? e)}])

(defn- ->incoming-info
  [e]
  [(:transfer-id e) {:from-user-id (:other-user-id e)
                     :amt          (:amt e)
                     :success?     (:success? e)}])

(defn create-module
  []
  ;; The append counter lives HERE, in the create-module closure, not inside
  ;; wrap-client: `wait-for-microbatch-processed-count` counts records processed
  ;; cluster-wide and is cumulative, so a per-client counter would pass an
  ;; undercount whenever more than one client appends, and wait-for-processing!
  ;; would return before that client's own appends were processed.
  (let [append-count (atom 0)]
    {:module BankTransferModule
     :wrap-client
     (fn [ipc]
       (let [depot    (foreign-depot ipc module-name* "*bank-depot")
             accounts (foreign-pstate ipc module-name* "$$accounts")]
         (reify
           proto/BankTransferModule
           (deposit! [_ user-id amt]
             (swap! append-count inc)
             ;; Financial data: :ack returns only once the record is persisted
             ;; and replicated on its depot partition.
             (foreign-append! depot (->Deposit user-id amt) :ack)
             nil)

           (transfer! [_ transfer-id from-user-id to-user-id amt]
             (swap! append-count inc)
             (foreign-append! depot
                              (->Transfer transfer-id from-user-id to-user-id amt)
                              :ack)
             nil)

           (get-balance [_ user-id]
             ;; keypath on an absent user navigates to nil with multiplicity 1,
             ;; so foreign-select-one is safe and never throws.
             (or (foreign-select-one [(keypath user-id :balance)] accounts) 0))

           (get-outgoing-transfers [_ user-id]
             ;; ALL on an absent/nil collection emits zero values, so a
             ;; never-seen user yields [] — a vector, not nil.
             (mapv ->outgoing-info
                   (foreign-select [(keypath user-id :outgoing) ALL] accounts)))

           (get-incoming-transfers [_ user-id]
             (mapv ->incoming-info
                   (foreign-select [(keypath user-id :incoming) ALL] accounts)))

           harness/Synchronizable
           (wait-for-processing! [_]
             (rtest/wait-for-microbatch-processed-count
              ipc module-name* "bank" @append-count)))))}))
