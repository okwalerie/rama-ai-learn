(ns hld-web-crawler.module
  (:require [clojure.string :as str]
            [com.rpl.rama :refer :all]
            [com.rpl.rama.path :refer :all]
            [com.rpl.rama.test :as rtest]
            [hld-web-crawler.protocol :as protocol]
            [rama-challenges.harness :as harness]))

(def url-pattern
  #"(?i)^https://([a-z0-9.\-]{1,253})(/[\x21-\x22\x24-\x3e\x40-\x7e]*)?(\?[\x20-\x22\x24-\x7e]*)?(?:#[\s\S]*)?$")

(defn canonical-url [url]
  (when (<= (count url) 2048)
    (when-let [[_ host path query] (re-matches url-pattern url)]
      (str "https://" (str/lower-case host) (or path "/") query))))

(defn url-host [url]
  (second (re-find #"^https://([^/]+)" url)))

(defn path-and-query [url]
  (subs url (+ 8 (count (url-host url)))))

(defn allowed? [url rules]
  (let [path (path-and-query url)
        matches (filter #(str/starts-with? path (:path-prefix %)) rules)
        longest (reduce max 0 (map #(count (:path-prefix %)) matches))]
    (or (zero? longest)
        (boolean (some #(and (= longest (count (:path-prefix %))) (:allow? %)) matches)))))

(def default-info {:delay 1 :rules [] :clock 0 :fence 0
                   :last-claim-at nil :queued 0 :lease nil})

(defrecord Discover [host urls])
(defrecord Policy [host delay rules])
(defrecord Claim [host claim-id now])
(defrecord Complete [host fence outcome now])

;; Accumulate commands in depot emission order, not via a two-phase combiner.
;; One host has only one source partition, and its group is processed by one loop.
(def +ordered-commands
  (accumulator
    (fn [event] (term (fn [commands] (conj commands event))))
    :init-fn (fn [] [])))

(defmodule CrawlModule [setup topologies]
  (declare-depot setup *events (hash-by :host))
  (let [mb (microbatch-topology topologies "core")]
    (declare-pstate mb $$hosts
      {String (fixed-keys-schema
                {:info (fixed-keys-schema
                         {:delay Long :rules (vector-schema (fixed-keys-schema
                                                               {:path-prefix String :allow? Boolean}))
                          :clock Long :fence Long :last-claim-at Long :queued Long
                          :lease (fixed-keys-schema {:url String :fence Long :expires-at Long})})
                 :urls (map-schema String
                         (fixed-keys-schema {:status clojure.lang.Keyword :fence Long :lease-expires-at Long})
                         {:subindex-options {:track-size? false}})
                 :pending (set-schema String {:subindex-options {:track-size? false}})
                 :claims (map-schema String
                           (fixed-keys-schema {:status clojure.lang.Keyword :reason clojure.lang.Keyword :url String
                                               :fence Long :lease-expires-at Long})
                           {:subindex-options {:track-size? false}})})})
    (<<sources mb
      (source> *events :> %events)
      (<<batch
       (%events :> *incoming)
       (get *incoming :host :> *host)
       (+group-by *host
         (+ordered-commands *incoming :> *commands))
       (loop<- [*remaining-commands *commands :> *processed]
        (yield-if-overtime)
        (<<if (empty? *remaining-commands)
          (:> true)
         (else>)
          (first *remaining-commands :> *event)
          (<<cond
        (case> (instance? Discover *event))
        (get *event :urls :> *urls)
        (local-select> [(keypath *host :info) (nil->val default-info)] $$hosts :> *info)
        (loop<- [*remaining *urls *n (get *info :queued) :> *queued]
          (yield-if-overtime)
          (<<if (empty? *remaining)
            (:> *n)
           (else>)
            (first *remaining :> *url)
            (local-select> (keypath *host :urls *url) $$hosts :> *existing)
            (<<if (nil? *existing)
              (local-transform> [(keypath *host :urls *url)
                                 (termval {:status :queued :fence nil :lease-expires-at nil})] $$hosts)
              (local-transform> [(keypath *host :pending) NONE-ELEM (termval *url)] $$hosts))
            (<<if (nil? *existing)
              (continue> (rest *remaining) (inc *n))
             (else>)
              (continue> (rest *remaining) *n))))
        (local-transform> [(keypath *host :info)
                           (termval (assoc *info :queued *queued))] $$hosts)

        (case> (instance? Policy *event))
        (local-select> [(keypath *host :info) (nil->val default-info)] $$hosts :> *info)
        (local-transform> [(keypath *host :info)
                           (termval (assoc *info :delay (get *event :delay) :rules (get *event :rules)))] $$hosts)

        (case> (instance? Claim *event))
        (get *event :claim-id :> *cid)
        (local-select> (keypath *host :claims *cid) $$hosts :> *prior)
        (<<if (nil? *prior)
          (local-select> [(keypath *host :info) (nil->val default-info)] $$hosts :> *info)
          (max (get *event :now) (get *info :clock) :> *tick)
          (identity (assoc *info :clock *tick) :> *advanced)
          (get *advanced :lease :> *lease)
          (<<cond
            (case> (and> *lease (< *tick (get *lease :expires-at))))
            (identity *advanced :> *final-info)
            (identity {:status :denied :reason :busy} :> *result)

            (default>)
            (<<if *lease
              (identity (assoc *advanced :lease nil :queued (inc (get *advanced :queued))) :> *free)
             (else>)
              (identity *advanced :> *free))
            (<<if *lease
              (local-transform> [(keypath *host :urls (get *lease :url))
                                 (termval {:status :queued :fence nil :lease-expires-at nil})] $$hosts))
            (<<if (and> (get *free :last-claim-at)
                       (< *tick (+ (get *free :last-claim-at) (get *free :delay))))
              (identity *free :> *final-info)
              (identity {:status :denied :reason :not-ready} :> *result)
             (else>)
              (loop<- [*cursor nil *n (get *free :queued) :> *selected *remaining-count]
                (yield-if-overtime)
                (<<if *cursor
                  (local-select> [(keypath *host :pending)
                                  (sorted-set-range-from *cursor {:max-amt 64 :inclusive? false})]
                                 $$hosts :> *chunk)
                 (else>)
                  (local-select> [(keypath *host :pending) (sorted-set-range-from-start 64)]
                                 $$hosts :> *chunk))
                (identity (vec *chunk) :> *entries)
                (loop<- [*items *entries *count *n *last *cursor :> *found *count-left *last-seen]
                  (yield-if-overtime)
                  (<<if (empty? *items)
                    (:> nil *count *last)
                   (else>)
                    (first *items :> *url)
                    (<<if (allowed? *url (get *free :rules))
                      (:> *url *count *last)
                     (else>)
                      (local-transform> [(keypath *host :urls *url)
                                         (termval {:status :blocked :fence nil :lease-expires-at nil})] $$hosts)
                      (local-transform> [(keypath *host :pending) (set-elem *url) NONE>] $$hosts)
                      (continue> (rest *items) (dec *count) *url))))
                (<<if (and> (nil? *found) (pos? *count-left))
                  (continue> *last-seen *count-left)
                 (else>)
                  (:> *found *count-left)))
              (<<if *selected
                (inc (get *free :fence) :> *fence)
                (+ *tick 30 :> *expiry)
                (local-transform> [(keypath *host :urls *selected)
                                   (termval {:status :leased :fence *fence :lease-expires-at *expiry})] $$hosts)
                (identity (assoc *free :fence *fence :lease {:url *selected :fence *fence
                                                              :expires-at *expiry}
                                 :last-claim-at *tick :queued (dec *remaining-count)) :> *final-info)
                (identity {:status :granted :url *selected :fence *fence
                           :lease-expires-at *expiry} :> *result)
               (else>)
                (identity (assoc *free :queued *remaining-count) :> *final-info)
                (identity {:status :denied :reason :empty} :> *result))))
          (local-transform> [(keypath *host :info) (termval *final-info)] $$hosts)
          (local-transform> [(keypath *host :claims *cid) (termval *result)] $$hosts))

        (case> (instance? Complete *event))
        (local-select> [(keypath *host :info) (nil->val default-info)] $$hosts :> *info)
        (get *info :lease :> *lease)
        (<<if (and> *lease (= (get *lease :fence) (get *event :fence))
                   (< (max (get *event :now) (get *info :clock)) (get *lease :expires-at)))
          (<<if (= :fetched (get *event :outcome))
            (identity :done :> *status)
           (else>)
            (identity :failed :> *status))
          (local-transform> [(keypath *host :urls (get *lease :url))
                             (termval {:status *status
                                       :fence nil :lease-expires-at nil})] $$hosts)
          (local-transform> [(keypath *host :pending) (set-elem (get *lease :url)) NONE>] $$hosts)
          (local-transform> [(keypath *host :info)
                             (termval (assoc *info :lease nil :clock (max (get *event :now) (get *info :clock))))]
                            $$hosts)))
          (continue> (rest *remaining-commands))))))))

(defn create-module []
  (let [appends (atom 0)]
    {:module CrawlModule
     :wrap-client
     (fn [ipc]
       (let [module-name (get-module-name CrawlModule)
             depot (foreign-depot ipc module-name "*events")
             hosts (foreign-pstate ipc module-name "$$hosts")
             append! (fn [event]
                       (foreign-append! depot event)
                       (swap! appends inc))]
         (reify protocol/CrawlFrontier
           (discover! [_ urls]
             (doseq [[host grouped] (->> urls (keep canonical-url) distinct
                                         (group-by url-host))]
               (append! (->Discover host (vec grouped))))
             nil)
           (set-host-policy! [_ host delay rules]
             (append! (->Policy (str/lower-case host) delay rules)) nil)
           (claim! [_ host claim-id now]
             (append! (->Claim (str/lower-case host) claim-id now)) nil)
           (complete! [_ host fence outcome now]
             (append! (->Complete (str/lower-case host) fence outcome now)) nil)
           (get-claim [_ host claim-id]
             (when-let [v (foreign-select-one (keypath (str/lower-case host) :claims claim-id) hosts)]
               (if (= :granted (:status v))
                 (select-keys v [:status :url :fence :lease-expires-at])
                 (select-keys v [:status :reason]))))
           (get-url [_ url]
             (when-let [u (canonical-url url)]
               (when-let [v (foreign-select-one (keypath (url-host u) :urls u) hosts)]
                 {:url u :host (url-host u) :status (:status v)
                  :fence (:fence v) :lease-expires-at (:lease-expires-at v)})))
           (get-host [_ host]
             (let [h (str/lower-case host)
                   v (or (foreign-select-one (keypath h :info) hosts) default-info)]
               {:host h :delay (:delay v) :rules (:rules v) :queued (:queued v)
                :fence (:fence v) :last-claim-at (:last-claim-at v) :lease (:lease v)}))
           (list-pending [_ host from-url limit]
             (vec (foreign-select
                    [(keypath (str/lower-case host) :pending)
                     (if from-url
                       (sorted-set-range-from from-url {:max-amt limit :inclusive? false})
                       (sorted-set-range-from-start limit)) ALL] hosts)))
           harness/Synchronizable
           (wait-for-processing! [_]
             (rtest/wait-for-microbatch-processed-count ipc module-name "core" @appends)))))}))
