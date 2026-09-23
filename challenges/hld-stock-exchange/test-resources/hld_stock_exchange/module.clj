(ns hld-stock-exchange.module
  (:require [com.rpl.rama :refer :all]
            [com.rpl.rama.path :refer :all]
            [com.rpl.rama.aggs :as aggs]
            [com.rpl.rama.test :as rtest]
            [hld-stock-exchange.protocol :as protocol]
            [rama-challenges.harness :as harness]))

(definterface ICommand)
(defrecord SubmitOrder [symbol request-id account-id side price qty tif] ICommand)
(defrecord CancelOrder [symbol request-id order-id account-id] ICommand)

(defn valid-id! [x]
  (when-not (and (string? x) (<= 1 (count x) 128))
    (throw (IllegalArgumentException. "Expected nonempty string of at most 128 characters"))))

(defn valid-range! [x low high]
  (when-not (and (instance? Long x) (<= low x high))
    (throw (IllegalArgumentException. (str "Expected Long in range " low ".." high)))))

(defn valid-choice! [x choices]
  (when-not (contains? choices x)
    (throw (IllegalArgumentException. (str "Invalid choice: " x)))))

(defn bid-key [price] (- 1000000001 price))
(defn level-key [side price] (if (= side :buy) (bid-key price) price))
(defn level-field [side] (if (= side :buy) :bid-levels :ask-levels))
(defn queue-field [side] (if (= side :buy) :bid-queue :ask-queue))
(defn opposite [side] (if (= side :buy) :sell :buy))
(defn crosses? [side key limit]
  (if (= side :buy) (<= key limit) (<= key (bid-key limit))))
(defn can-match? [left best side price]
  (boolean (and (pos? left) (seq best) (crosses? side (ffirst best) price))))
(defn should-rest? [tif left] (and (= tif :gtc) (pos? left)))
(defn ordered-pairs [pairs] (sort-by first pairs))
(defn payload [cmd] (dissoc cmd :request-id))
(defn outcome-map [record]
  (when record (assoc (:outcome record) :conflicting-attempts (:conflicting-attempts record))))
(defn order-map [id order] (when order (assoc order :order-id id)))
(defn depth-map [side [key level]] (assoc level :price (if (= side :buy) (bid-key key) key)))
(defn trade-map [[seq trade]] (assoc trade :seq seq))
(defn maker-after [maker amount]
  (assoc maker :filled-qty (+ (:filled-qty maker) amount)
         :remaining-qty (- (:remaining-qty maker) amount)
         :state (if (= amount (:remaining-qty maker)) :filled :open)))
(defn level-after [level amount maker]
  {:qty (- (:qty level) amount)
   :order-count (- (:order-count level) (if (= amount (:remaining-qty maker)) 1 0))})
(defn add-level [level amount]
  {:qty (+ amount (if level (:qty level) 0))
   :order-count (inc (if level (:order-count level) 0))})
(defn new-order [account side price qty tif seq filled left]
  {:account-id account :side side :price price :qty qty :tif tif :seq seq
   :filled-qty filled :remaining-qty (if (= tif :gtc) left 0)
   :cancelled-qty (if (= tif :ioc) left 0)
   :state (if (zero? left) :filled (if (= tif :ioc) :cancelled :open))})
(defn submit-outcome [rid seq filled left tif old-trade-seq last-trade-seq]
  {:status :accepted :command :submit-limit-order :order-id rid :seq seq
   :filled-qty filled :resting-qty (if (= tif :gtc) left 0)
   :cancelled-qty (if (= tif :ioc) left 0)
   :trade-count (- last-trade-seq old-trade-seq)
   :first-trade-seq (if (> last-trade-seq old-trade-seq) (inc old-trade-seq) nil)
   :last-trade-seq (if (> last-trade-seq old-trade-seq) last-trade-seq nil)})
(defn trade-record [maker amount maker-id rid account side]
  {:price (:price maker) :qty amount :maker-order-id maker-id :taker-order-id rid
   :maker-account-id (:account-id maker) :taker-account-id account :taker-side side})
(defn cancel-level [level order]
  {:qty (- (:qty level) (:remaining-qty order))
   :order-count (dec (:order-count level))})
(defn cancelled-order [order]
  (assoc order :remaining-qty 0 :cancelled-qty (:remaining-qty order) :state :cancelled))
(defn accepted-cancel [oid order]
  {:status :accepted :command :cancel-order :order-id oid
   :cancelled-qty (:remaining-qty order)})

(defmodule StockExchange [setup topologies]
  (declare-depot setup *commands (hash-by :symbol))
  (set-launch-depot-dynamic-option! setup "*commands" "depot.microbatch.max.records" 1000)
  (set-launch-topology-dynamic-option! setup "core" "topology.microbatch.phase.timeout.seconds" 3600)
  (let [mb (microbatch-topology topologies "core")
        order-schema (fixed-keys-schema {:account-id String :side clojure.lang.Keyword
                                         :price Long :qty Long :tif clojure.lang.Keyword :seq Long
                                         :filled-qty Long :remaining-qty Long :cancelled-qty Long
                                         :state clojure.lang.Keyword})
        levels-schema (map-schema Long (fixed-keys-schema {:qty Long :order-count Long}) {:subindex? true})
        queue-schema (map-schema Long (map-schema Long String {:subindex? true}) {:subindex? true})]
    (declare-pstate mb $$symbols
      {String (fixed-keys-schema
                {:ingress-seq Long :next-order-seq Long :next-trade-seq Long
                 :orders (map-schema String order-schema {:subindex? true})
                 :ask-levels levels-schema :bid-levels levels-schema
                 :ask-queue queue-schema :bid-queue queue-schema
                 :trades (map-schema Long (fixed-keys-schema
                                            {:price Long :qty Long
                                             :maker-order-id String :taker-order-id String
                                             :maker-account-id String :taker-account-id String
                                             :taker-side clojure.lang.Keyword}) {:subindex? true})
                 :requests (map-schema String (fixed-keys-schema
                                                 {:payload ICommand :outcome Object
                                                  :conflicting-attempts Long}) {:subindex? true})})})
    (<<sources mb
      (source> *commands :> %mb)
      (<<batch
        (%mb :> *cmd)
        (get *cmd :symbol :> *sym)
        (local-select> [(keypath *sym :ingress-seq) (nil->val 0)] $$symbols :> *prior)
        (inc *prior :> *position)
        (local-transform> [(keypath *sym :ingress-seq) (termval *position)] $$symbols)
        (vector *position *cmd :> *pair)
        (+group-by *sym (aggs/+vec-agg *pair :> *pairs))
        (ordered-pairs *pairs :> *ordered)
        (loop<- [*remaining *ordered :> *done]
          (<<if (empty? *remaining)
            (:> true)
           (else>)
            (yield-if-overtime)
            (first *remaining :> [*position *cmd])
            (get *cmd :request-id :> *rid)
            (local-select> (keypath *sym :requests *rid) $$symbols :> *request)
            (<<if (some? *request)
              (<<if (not= (payload *cmd) (get *request :payload))
                (local-transform> [(keypath *sym :requests *rid :conflicting-attempts)
                                   (term inc)] $$symbols))
             (else>)
              (<<if (instance? SubmitOrder *cmd)
                (get *cmd :account-id :> *account)
                (get *cmd :side :> *side)
                (get *cmd :price :> *price)
                (get *cmd :qty :> *qty)
                (get *cmd :tif :> *tif)
                (opposite *side :> *opp)
                (level-field *opp :> *opp-levels)
                (queue-field *opp :> *opp-queue)
                (local-select> [(keypath *sym :next-order-seq) (nil->val 0)] $$symbols :> *old-order-seq)
                (inc *old-order-seq :> *oseq)
                (local-select> [(keypath *sym :next-trade-seq) (nil->val 0)] $$symbols :> *old-trade-seq)
                (loop<- [*left *qty *filled 0 *trade-seq *old-trade-seq :> *match]
                  (yield-if-overtime)
                  (local-select> [(keypath *sym *opp-levels) (sorted-map-range-from-start 1)]
                                 $$symbols :> *best)
                  (<<if (can-match? *left *best *side *price)
                    (ffirst *best :> *key)
                    (local-select> [(keypath *sym *opp-queue *key) (sorted-map-range-from-start 1)]
                                   $$symbols :> *head)
                    (first *head :> [*maker-seq *maker-id])
                    (local-select> (keypath *sym :orders *maker-id) $$symbols :> *maker)
                    (min *left (get *maker :remaining-qty) :> *amount)
                    (inc *trade-seq :> *next-trade-seq)
                    (local-transform> [(keypath *sym :trades *next-trade-seq)
                                       (termval (trade-record *maker *amount *maker-id *rid *account *side))] $$symbols)
                    (local-transform> [(keypath *sym :orders *maker-id)
                                       (termval (maker-after *maker *amount))] $$symbols)
                    (local-select> (keypath *sym *opp-levels *key) $$symbols :> *level)
                    (<<if (= *amount (get *maker :remaining-qty))
                      (local-transform> [(keypath *sym *opp-queue *key *maker-seq) NONE>] $$symbols))
                    (<<if (= *amount (get *level :qty))
                      (local-transform> [(keypath *sym *opp-levels *key) NONE>] $$symbols)
                      (local-transform> [(keypath *sym *opp-queue *key) NONE>] $$symbols)
                     (else>)
                      (local-transform> [(keypath *sym *opp-levels *key)
                                         (termval (level-after *level *amount *maker))]
                                        $$symbols))
                    (continue> (- *left *amount) (+ *filled *amount) *next-trade-seq)
                   (else>)
                    (:> [*left *filled *trade-seq])))
                (identity *match :> [*left *filled *last-trade-seq])
                (<<if (should-rest? *tif *left)
                  (level-field *side :> *own-levels)
                  (queue-field *side :> *own-queue)
                  (level-key *side *price :> *own-key)
                  (local-select> (keypath *sym *own-levels *own-key) $$symbols :> *own-level)
                  (local-transform> [(keypath *sym *own-levels *own-key)
                                     (termval (add-level *own-level *left))]
                                    $$symbols)
                  (local-transform> [(keypath *sym *own-queue *own-key *oseq) (termval *rid)] $$symbols))
                (local-transform> [(keypath *sym :orders *rid)
                                   (termval (new-order *account *side *price *qty *tif *oseq *filled *left))] $$symbols)
                (local-transform> [(keypath *sym :next-order-seq) (termval *oseq)] $$symbols)
                (local-transform> [(keypath *sym :next-trade-seq) (termval *last-trade-seq)] $$symbols)
                (submit-outcome *rid *oseq *filled *left *tif *old-trade-seq *last-trade-seq
                          :> *outcome)
               (else>)
                (get *cmd :order-id :> *oid)
                (get *cmd :account-id :> *account)
                (local-select> (keypath *sym :orders *oid) $$symbols :> *order)
                (<<cond
                  (case> (nil? *order))
                  (identity {:status :rejected :command :cancel-order :reason :no-such-order} :> *outcome)
                  (case> (not= *account (get *order :account-id)))
                  (identity {:status :rejected :command :cancel-order :reason :not-owner} :> *outcome)
                  (case> (zero? (get *order :remaining-qty)))
                  (identity {:status :rejected :command :cancel-order :reason :order-not-open} :> *outcome)
                  (default>)
                  (level-field (get *order :side) :> *levels)
                  (queue-field (get *order :side) :> *queue)
                  (level-key (get *order :side) (get *order :price) :> *key)
                  (local-select> (keypath *sym *levels *key) $$symbols :> *level)
                  (local-transform> [(keypath *sym *queue *key (get *order :seq)) NONE>] $$symbols)
                  (<<if (= (get *level :qty) (get *order :remaining-qty))
                    (local-transform> [(keypath *sym *levels *key) NONE>] $$symbols)
                    (local-transform> [(keypath *sym *queue *key) NONE>] $$symbols)
                   (else>)
                    (local-transform> [(keypath *sym *levels *key)
                                       (termval (cancel-level *level *order))] $$symbols))
                  (local-transform> [(keypath *sym :orders *oid)
                                     (termval (cancelled-order *order))] $$symbols)
                  (accepted-cancel *oid *order :> *outcome)))
              (local-transform> [(keypath *sym :requests *rid)
                                 (termval {:payload (payload *cmd) :outcome *outcome
                                           :conflicting-attempts 0})] $$symbols))
            (continue> (rest *remaining))))))))

(defn create-module []
  (let [count (atom 0)]
    {:module StockExchange
     :wrap-client
     (fn [ipc]
       (let [name (get-module-name StockExchange)
             depot (foreign-depot ipc name "*commands")
             state (foreign-pstate ipc name "$$symbols")]
         (reify protocol/StockExchangeModule
           (submit-limit-order! [_ rid sym account side price qty tif]
             (doseq [id [rid sym account]] (valid-id! id))
             (valid-choice! side #{:buy :sell})
             (valid-range! price 1 1000000000)
             (valid-range! qty 1 1000000000)
             (valid-choice! tif #{:gtc :ioc})
             (swap! count inc)
             (foreign-append! depot (->SubmitOrder sym rid account side price qty tif))
             nil)
           (cancel-order! [_ rid sym oid account]
             (doseq [id [rid sym oid account]] (valid-id! id))
             (swap! count inc)
             (foreign-append! depot (->CancelOrder sym rid oid account))
             nil)
           (get-outcome [_ sym rid]
             (valid-id! sym) (valid-id! rid)
             (outcome-map (foreign-select-one (keypath sym :requests rid) state)))
           (get-order [_ sym oid]
             (valid-id! sym) (valid-id! oid)
             (order-map oid (foreign-select-one (keypath sym :orders oid) state)))
           (get-depth [_ sym side levels]
             (valid-id! sym) (valid-choice! side #{:buy :sell}) (valid-range! levels 1 50)
             (mapv (partial depth-map side)
                   (foreign-select-one [(keypath sym (level-field side))
                                        (sorted-map-range-from-start levels)] state)))
           (get-trades [_ sym after limit]
             (valid-id! sym) (valid-range! after 0 Long/MAX_VALUE) (valid-range! limit 1 500)
             (if (= after Long/MAX_VALUE) []
                 (mapv trade-map
                       (foreign-select-one [(keypath sym :trades)
                                            (sorted-map-range-from (inc after) {:max-amt limit})] state))))
           harness/Synchronizable
           (wait-for-processing! [_]
             (rtest/wait-for-microbatch-processed-count ipc name "core" @count)))))}))
