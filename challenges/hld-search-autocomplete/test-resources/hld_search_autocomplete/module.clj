(ns hld-search-autocomplete.module
  (:require [com.rpl.rama :refer :all]
            [com.rpl.rama.path :refer :all]
            [com.rpl.rama.ops :as ops]
            [com.rpl.rama.test :as rtest]
            [hld-search-autocomplete.protocol :as p]
            [rama-challenges.harness :as harness]))

(defn placement [locale s]
  [locale (subs s 0 (min 2 (count s)))])

(defn prefixes [phrase]
  (mapv #(subs phrase 0 %) (range 2 (inc (count phrase)))))

(defn rank-key [score phrase]
  (str (format "%08d" (- 99999999 score)) "|" phrase))

(defn decode-rank [s]
  {:phrase (subs s 9) :score (- 99999999 (Long/parseLong (subs s 0 8)))})

(defn phrase-view [generation blocked record]
  (let [base (long (or (:base record) 0))
        sessions (long (or (:sessions record) 0))]
    {:generation generation :in-corpus? (pos? base) :base base
     :sessions sessions :score (+ base (* 10 sessions)) :blocked? (boolean blocked)}))

(defn record-base [record] (long (or (:base record) 0)))
(defn record-sessions [record] (long (or (:sessions record) 0)))
(defn newer? [candidate current] (or (nil? current) (> candidate current)))
(defn active-candidate? [generation base sessions]
  (boolean (and generation (or (pos? base) (pos? sessions)))))
(defn old-visible? [blocked base sessions]
  (not (or blocked (and (zero? base) (zero? sessions)))))

(defmodule AutocompleteModule [setup topologies]
  (declare-depot setup *events (hash-by :locale))
  (let [mb (microbatch-topology topologies "core")]
    ;; The owner generation is only changed at hash(locale). The replicated copy is
    ;; read metadata; no acceptance decision is based on its delivery order.
    (declare-pstate mb $$owner {String Long})
    (declare-pstate mb $$generation {String Long})
    (declare-pstate mb $$blocked {String (set-schema String {:subindex? true})})
    (declare-pstate mb $$phrases
                    {String (map-schema Long
                                        (map-schema String
                                                    (fixed-keys-schema {:base Long :sessions Long})
                                                    {:subindex? true})
                                        {:subindex? true})})
    (declare-pstate mb $$sessions
                    {String (map-schema Long
                                        (map-schema String
                                                    (set-schema String {:subindex? true})
                                                    {:subindex? true})
                                        {:subindex? true})})
    (declare-pstate mb $$prefixes
                    {String (map-schema Long
                                        (map-schema String
                                                    (set-schema String {:subindex? true})
                                                    {:subindex? true})
                                        {:subindex? true})})

    (<<sources mb
      (source> *events :> %mb)
      (%mb :> {:keys [*op *locale *generation *entries *session-id *phrase]})
      (local-select> [(keypath *locale)] $$owner :> *current)
      ;; One ordered owner-to-phrase path for all accepted writes. A publish
      ;; emits its metadata separately, but only the owner decides acceptance.
      (<<cond
        (case> (= *op :publish))
        (filter> (newer? *generation *current))
        (local-transform> [(keypath *locale) (termval *generation)] $$owner)
        (anchor> <published>)
        (<<branch <published>
          (|all)
          (local-transform> [(keypath *locale) (nil->val 0)
                             (term (partial max *generation))] $$generation))
        (ops/explode *entries :> [*item-phrase *base])
        (identity {:op :init :locale *locale :generation *generation
                   :phrase *item-phrase :base *base} :> *work)

        (case> (= *op :search))
        (filter> (= *generation *current))
        (identity {:op :count :locale *locale :generation *generation
                   :phrase *phrase :session-id *session-id} :> *work)

        (default>)
        (identity {:op *op :locale *locale :generation *current
                   :phrase *phrase} :> *work))
      (identity *work :> {*work-op :op *work-locale :locale
                          *work-generation :generation *work-phrase :phrase
                          *work-base :base *work-session-id :session-id})
      (placement *work-locale *work-phrase :> *pk)
      (|hash *pk)
      (local-select> [(keypath *work-locale) (view contains? *work-phrase)]
                     $$blocked :> *was-blocked)
      (<<cond
        (case> (= *work-op :init))
        (long 0 :> *zero)
        (local-transform> [(keypath *work-locale *work-generation *work-phrase)
                           (termval {:base *work-base :sessions *zero})] $$phrases)
        (identity nil :> *old-rank)
        (ifexpr *was-blocked nil (rank-key *work-base *work-phrase) :> *new-rank)

        (case> (= *work-op :count))
        (local-select> [(keypath *work-locale *work-generation *work-phrase)]
                       $$phrases :> *record)
        (local-select> [(keypath *work-locale *work-generation *work-phrase)
                        (view contains? *work-session-id)] $$sessions :> *seen)
        (filter> (not *seen))
        (local-transform> [(keypath *work-locale *work-generation *work-phrase)
                           NONE-ELEM (termval *work-session-id)] $$sessions)
        (record-base *record :> *base)
        (record-sessions *record :> *count)
        (local-transform> [(keypath *work-locale *work-generation *work-phrase)
                           (termval {:base *base :sessions (inc *count)})] $$phrases)
        (ifexpr (old-visible? *was-blocked *base *count)
                (rank-key (+ *base (* 10 *count)) *work-phrase) nil :> *old-rank)
        (ifexpr *was-blocked nil
                (rank-key (+ *base (* 10 (inc *count))) *work-phrase) :> *new-rank)

        (default>)
        (local-select> [(keypath *work-locale *work-generation *work-phrase)]
                       $$phrases :> *record)
        (record-base *record :> *base)
        (record-sessions *record :> *count)
        (active-candidate? *work-generation *base *count :> *candidate)
        (<<if (= *work-op :block)
          (filter> (not *was-blocked))
          (local-transform> [(keypath *work-locale) NONE-ELEM
                             (termval *work-phrase)] $$blocked)
          (ifexpr *candidate (rank-key (+ *base (* 10 *count)) *work-phrase)
                  nil :> *old-rank)
          (identity nil :> *new-rank)
         (else>)
          (filter> *was-blocked)
          (local-transform> [(keypath *work-locale) (set-elem *work-phrase) NONE>]
                            $$blocked)
          (identity nil :> *old-rank)
          (ifexpr *candidate (rank-key (+ *base (* 10 *count)) *work-phrase)
                  nil :> *new-rank)))
      (anchor> <rank-change>)
      (<<branch <rank-change>
        (prefixes *work-phrase :> *long-prefixes)
        (ops/explode *long-prefixes :> *prefix)
        (<<if (some? *old-rank)
          (local-transform> [(keypath *work-locale *work-generation *prefix)
                             (set-elem *old-rank) NONE>] $$prefixes))
        (<<if (some? *new-rank)
          (local-transform> [(keypath *work-locale *work-generation *prefix)
                             NONE-ELEM (termval *new-rank)] $$prefixes)))
      (subs *work-phrase 0 1 :> *short-prefix)
      (placement *work-locale *short-prefix :> *short-pk)
      (|hash *short-pk)
      (<<if (some? *old-rank)
        (local-transform> [(keypath *work-locale *work-generation *short-prefix)
                           (set-elem *old-rank) NONE>] $$prefixes))
      (<<if (some? *new-rank)
        (local-transform> [(keypath *work-locale *work-generation *short-prefix)
                           NONE-ELEM (termval *new-rank)] $$prefixes)))

    (<<query-topology topologies "suggest"
      [*locale *prefix *k :> *result]
      (placement *locale *prefix :> *pk)
      (|hash *pk)
      (local-select> [(keypath *locale)] $$generation :> *generation)
      (<<if (some? *generation)
        (local-select> [(keypath *locale *generation *prefix)
                        (sorted-set-range-from-start *k)] $$prefixes :> *ranks)
        (identity (mapv decode-rank *ranks) :> *result)
       (else>)
        (identity [] :> *result))
      (|origin))

    (<<query-topology topologies "phrase"
      [*locale *phrase :> *result]
      (placement *locale *phrase :> *pk)
      (|hash *pk)
      (local-select> [(keypath *locale)] $$generation :> *generation)
      (local-select> [(keypath *locale) (view contains? *phrase)] $$blocked :> *blocked)
      (<<if (some? *generation)
        (local-select> [(keypath *locale *generation *phrase)] $$phrases :> *record)
        (phrase-view *generation *blocked *record :> *result)
       (else>)
        (phrase-view nil *blocked nil :> *result))
      (|origin))))

(defn create-module []
  (let [module-name (get-module-name AutocompleteModule)
        append-count (atom 0)]
    {:module AutocompleteModule
     :wrap-client
     (fn [ipc]
       (let [depot (foreign-depot ipc module-name "*events")
             owner (foreign-pstate ipc module-name "$$owner")
             suggest-query (foreign-query ipc module-name "suggest")
             phrase-query (foreign-query ipc module-name "phrase")
             append! (fn [event]
                       (locking append-count
                         (foreign-append! depot event)
                         (swap! append-count inc))
                       nil)]
         (reify
           p/Autocomplete
           (publish-snapshot! [_ locale generation entries]
             (append! {:op :publish :locale locale :generation generation :entries entries}))
           (record-search! [_ locale generation session-id phrase]
             (append! {:op :search :locale locale :generation generation
                       :session-id session-id :phrase phrase}))
           (block-phrase! [_ locale phrase]
             (append! {:op :block :locale locale :phrase phrase}))
           (unblock-phrase! [_ locale phrase]
             (append! {:op :unblock :locale locale :phrase phrase}))
           (suggest [_ locale prefix k]
             (foreign-invoke-query suggest-query locale prefix k))
           (get-phrase [_ locale phrase]
             (foreign-invoke-query phrase-query locale phrase))
           (get-generation [_ locale]
             (foreign-select-one [(keypath locale)] owner))
           harness/Synchronizable
           (wait-for-processing! [_]
             (rtest/wait-for-microbatch-processed-count ipc module-name "core"
                                                       @append-count)))))}))
