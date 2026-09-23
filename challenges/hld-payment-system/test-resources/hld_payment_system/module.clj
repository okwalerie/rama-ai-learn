(ns hld-payment-system.module
  (:require [com.rpl.rama :refer :all]
            [com.rpl.rama.path :refer :all]
            [com.rpl.rama.aggs :as aggs]
            [com.rpl.rama.test :as rtest]
            [rama-challenges.harness :as harness]
            [hld-payment-system.protocol :as p]))

(defrecord Command [tenant-id request-id type args])

(defn- valid-id [x]
  (and (string? x) (<= 1 (count x) 128)))

(defn- check! [pred]
  (when-not pred (throw (IllegalArgumentException. "Invalid payment-system argument"))))

(defn- amount? [x]
  (and (instance? Long x) (<= 1 x 1000000000000)))

(defn- account-keys [cmd charge]
  (case (:type cmd)
    :create-account [(first (:args cmd)) "clearing"]
    :fund [(first (:args cmd)) "clearing"]
    :charge (subvec (:args cmd) 0 2)
    :refund (if charge [(:customer-id charge) (:merchant-id charge)] ["clearing" "clearing"])
    ["clearing" "clearing"]))

(defn- reject [type reason]
  {:status :rejected :command type :reason reason :conflicting-attempts 0})

(defn- accepted [type fields]
  (merge {:status :accepted :command type :conflicting-attempts 0} fields))

;; Calculates only from point-read records. No history or subindex handle crosses this boundary.
(defn- apply-original [cmd currency next-seq charge a b]
  (let [{:keys [type args request-id]} cmd
        [x y amount] args
        seqno (inc (or next-seq 0))
        tx (fn [source destination charge-id]
             {:request-id request-id :type type :charge-id charge-id
              :postings [{:account-id source :delta (- amount)}
                         {:account-id destination :delta amount}]})]
    (case type
      :create-tenant
      (if currency
        {:outcome (reject type :tenant-exists)}
        {:outcome (accepted type {}) :currency x :initial-seq 0
         :clearing {:kind :clearing :balance 0}})

      :create-account
      (cond
        (nil? currency) {:outcome (reject type :no-such-tenant)}
        a {:outcome (reject type :account-exists)}
        :else {:outcome (accepted type {}) :account-a {:kind y :balance 0}})

      :fund
      (cond
        (nil? currency) {:outcome (reject type :no-such-tenant)}
        (nil? a) {:outcome (reject type :no-such-account)}
        :else (let [balance (+ (:balance a) y)]
                {:outcome (accepted type {:seq seqno :balance balance})
                 :account-a (assoc a :balance balance)
                 :account-b (assoc b :balance (- (:balance b) y))
                 :seq seqno :row {:request-id request-id :type :fund :charge-id nil
                                  :postings [{:account-id "clearing" :delta (- y)}
                                             {:account-id x :delta y}]}}))

      :charge
      (cond
        (nil? currency) {:outcome (reject type :no-such-tenant)}
        (or (nil? a) (nil? b)) {:outcome (reject type :no-such-account)}
        (or (not= :customer (:kind a)) (not= :merchant (:kind b)))
        {:outcome (reject type :wrong-account-kind)}
        (< (:balance a) amount) {:outcome (reject type :insufficient-funds)}
        :else (let [balance (- (:balance a) amount)]
                {:outcome (accepted type {:charge-id request-id :seq seqno
                                          :customer-balance balance})
                 :account-a (assoc a :balance balance)
                 :account-b (update b :balance + amount)
                 :charge {:customer-id x :merchant-id y :amount amount
                          :refunded-total 0 :seq seqno}
                 :seq seqno :row (tx x y request-id)}))

      :refund
      (let [amt y]
        (cond
          (nil? currency) {:outcome (reject type :no-such-tenant)}
          (nil? charge) {:outcome (reject type :no-such-charge)}
          (> (+ (:refunded-total charge) amt) (:amount charge))
          {:outcome (reject type :refund-exceeds-charge)}
          (< (:balance b) amt) {:outcome (reject type :insufficient-funds)}
          :else (let [total (+ (:refunded-total charge) amt)]
                  {:outcome (accepted type {:refund-id request-id :charge-id x
                                            :seq seqno :refunded-total total})
                   :account-a (update a :balance + amt)
                   :account-b (update b :balance - amt)
                   :charge (assoc charge :refunded-total total)
                   :seq seqno
                   :row {:request-id request-id :type :refund :charge-id x
                         :postings [{:account-id (:merchant-id charge) :delta (- amt)}
                                    {:account-id (:customer-id charge) :delta amt}]}}))))))

(defn- ordered [pairs]
  (mapv second (sort-by first pairs)))

(defmodule PaymentSystem [setup topologies]
  (declare-depot setup *commands (hash-by :tenant-id))
  (set-launch-depot-dynamic-option! setup "*commands" "depot.microbatch.max.records" 1000)
  (let [mb (microbatch-topology topologies "core")]
    (declare-pstate mb $$tenants
      {String (fixed-keys-schema
               {:currency String :ingress-seq Long :next-seq Long
                :accounts (map-schema String Object {:subindex? true})
                :charges (map-schema String Object {:subindex? true})
                :journal (map-schema Long Object {:subindex? true})
                :requests (map-schema String Object {:subindex? true})})})
    (<<sources mb
      (source> *commands :> %mb)
      (<<batch
        (%mb :> *cmd)
        (get *cmd :tenant-id :> *t)
        (local-select> [(keypath *t :ingress-seq) (nil->val 0)] $$tenants :> *prev)
        (inc *prev :> *pos)
        (local-transform> [(keypath *t :ingress-seq) (termval *pos)] $$tenants)
        (vector *pos *cmd :> *pair)
        (+group-by *t
          (aggs/+vec-agg *pair :> *pairs))
        (ordered *pairs :> *commands)
        (loop<- [*remaining *commands :> *done]
          (<<if (empty? *remaining)
            (:> true)
           (else>)
            (first *remaining :> *command)
            (get *command :request-id :> *rid)
            (local-select> [(keypath *t :requests *rid)] $$tenants :> *existing)
            (<<if *existing
              (<<if (not= (get *existing :payload) *command)
                (local-transform>
                  [(keypath *t :requests *rid)
                   (termval (update *existing :outcome update :conflicting-attempts inc))]
                  $$tenants))
             (else>)
              (local-select> [(keypath *t :currency)] $$tenants :> *currency)
              (local-select> [(keypath *t :next-seq)] $$tenants :> *next)
              (get *command :args :> *args)
              (get *command :type :> *type)
              (<<if (= *type :refund)
                (local-select> [(keypath *t :charges (first *args))] $$tenants :> *charge)
               (else>)
                (identity nil :> *charge))
              (account-keys *command *charge :> [*a-id *b-id])
              (local-select> [(keypath *t :accounts *a-id)] $$tenants :> *a)
              (local-select> [(keypath *t :accounts *b-id)] $$tenants :> *b)
              (apply-original *command *currency *next *charge *a *b :> *result)
              (<<if (get *result :currency)
                (local-transform> [(keypath *t :currency) (termval (get *result :currency))] $$tenants)
                (local-transform> [(keypath *t :next-seq) (termval 0)] $$tenants)
                (local-transform> [(keypath *t :accounts "clearing")
                                   (termval (get *result :clearing))] $$tenants))
              (<<if (get *result :account-a)
                (local-transform> [(keypath *t :accounts *a-id)
                                   (termval (get *result :account-a))] $$tenants))
              (<<if (get *result :account-b)
                (local-transform> [(keypath *t :accounts *b-id)
                                   (termval (get *result :account-b))] $$tenants))
              (<<if (get *result :charge)
                (<<if (= *type :charge)
                  (identity *rid :> *charge-id)
                 (else>)
                  (identity (first *args) :> *charge-id))
                (local-transform> [(keypath *t :charges *charge-id)
                                   (termval (get *result :charge))] $$tenants))
              (<<if (get *result :row)
                (local-transform> [(keypath *t :next-seq) (termval (get *result :seq))] $$tenants)
                (local-transform> [(keypath *t :journal (get *result :seq))
                                   (termval (get *result :row))] $$tenants))
              (local-transform> [(keypath *t :requests *rid)
                                 (termval {:payload *command :outcome (get *result :outcome)})]
                                $$tenants))
            (yield-if-overtime)
            (continue> (rest *remaining))))))))

(defn- append! [depot count-atom cmd]
  (foreign-append! depot cmd :append-ack)
  (swap! count-atom inc)
  nil)

(defn- client [ipc count-atom]
  (let [name (get-module-name PaymentSystem)
        depot (foreign-depot ipc name "*commands")
        state (foreign-pstate ipc name "$$tenants")]
    (reify p/PaymentSystemModule
      (create-tenant! [_ rid tid currency]
        (check! (and (valid-id rid) (valid-id tid) (string? currency)
                     (boolean (re-matches #"[A-Z]{3}" currency))))
        (append! depot count-atom (->Command tid rid :create-tenant [currency])))
      (create-account! [_ rid tid aid kind]
        (check! (and (valid-id rid) (valid-id tid) (valid-id aid)
                     (not= aid "clearing") (#{:customer :merchant} kind)))
        (append! depot count-atom (->Command tid rid :create-account [aid kind])))
      (fund! [_ rid tid aid amount]
        (check! (and (valid-id rid) (valid-id tid) (valid-id aid)
                     (not= aid "clearing") (amount? amount)))
        (append! depot count-atom (->Command tid rid :fund [aid amount])))
      (charge! [_ rid tid customer merchant amount]
        (check! (and (valid-id rid) (valid-id tid) (valid-id customer) (valid-id merchant)
                     (not= customer "clearing") (not= merchant "clearing") (amount? amount)))
        (append! depot count-atom (->Command tid rid :charge [customer merchant amount])))
      (refund! [_ rid tid charge-id amount]
        (check! (and (valid-id rid) (valid-id tid) (valid-id charge-id) (amount? amount)))
        (append! depot count-atom (->Command tid rid :refund [charge-id amount])))
      (get-outcome [_ tid rid]
        (check! (and (valid-id tid) (valid-id rid)))
        (:outcome (foreign-select-one [(keypath tid :requests rid)] state)))
      (get-tenant [_ tid]
        (check! (valid-id tid))
        (when-let [currency (foreign-select-one [(keypath tid :currency)] state)]
          {:tenant-id tid :currency currency}))
      (get-account [_ tid aid]
        (check! (and (valid-id tid) (valid-id aid)))
        (when-let [account (foreign-select-one [(keypath tid :accounts aid)] state)]
          (assoc account :account-id aid)))
      (get-balance [_ tid aid]
        (check! (and (valid-id tid) (valid-id aid)))
        (:balance (foreign-select-one [(keypath tid :accounts aid)] state)))
      (get-charge [_ tid cid]
        (check! (and (valid-id tid) (valid-id cid)))
        (when-let [charge (foreign-select-one [(keypath tid :charges cid)] state)]
          (assoc charge :charge-id cid)))
      (get-journal [_ tid after limit]
        (check! (and (valid-id tid) (instance? Long after) (<= 0 after)
                     (instance? Long limit) (<= 1 limit 500)))
        (if (= after Long/MAX_VALUE)
          []
          (mapv (fn [[seqno row]] (assoc row :seq seqno))
                (foreign-select [(keypath tid :journal)
                                 (sorted-map-range-from (inc after) {:max-amt limit}) ALL]
                                state))))
      harness/Synchronizable
      (wait-for-processing! [_]
        (rtest/wait-for-microbatch-processed-count ipc name "core" @count-atom)))))

(defn create-module []
  (let [count-atom (atom 0)]
    {:module PaymentSystem :wrap-client #(client % count-atom)}))
