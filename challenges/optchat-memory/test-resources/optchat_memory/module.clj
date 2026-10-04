;; IMPORTANT: Re-read maintainer/PLAN-challenge-packaging.md before modifying.
(ns optchat-memory.module
  (:require [com.rpl.rama :refer :all]
            [com.rpl.rama.path :refer :all]
            [optchat-memory.protocol :as p]
            [rama-challenges.harness :as h])
  (:import [java.math BigInteger]
           [java.nio.charset StandardCharsets]
           [clojure.lang Keyword]))

(defn utf8 [s] (alength (.getBytes ^String s StandardCharsets/UTF_8)))
(defn unicode? [s]
  (and (string? s) (.canEncode (.newEncoder StandardCharsets/UTF_8) ^String s)))
(defn id? [x] (and (unicode? x) (<= 1 (utf8 x) 128)))
(defn integer-between? [x low high] (and (integer? x) (<= low x high)))
(defn index? [x] (integer-between? x 0 Long/MAX_VALUE))
(defn address? [s n]
  (and (index? s) (integer-between? n 1 Long/MAX_VALUE)
       (zero? (bit-and (long n) (dec (long n)))) (zero? (mod s n))))
(defn message? [m]
  (and (map? m) (= #{:kind :text :timestamp} (set (keys m)))
       (contains? #{:user :talk :tool :echo :note} (:kind m))
       (unicode? (:text m)) (<= (utf8 (:text m)) 65536)
       (integer-between? (:timestamp m) Long/MIN_VALUE Long/MAX_VALUE)))
(defn cursor? [c]
  (or (nil? c) (and (map? c) (= #{:revision :start} (set (keys c)))
                    (integer? (:revision c)) (not (neg? (:revision c)))
                    (index? (:start c)))))
(defn add-level [n levels] (conj (or levels #{}) n))
(defn drop-level [n levels] (disj levels n))
(defn invalid [] {:error :invalid-argument})
(defn missing [] {:error :not-found})
(defn ok [x] {:ok x})
(defn fresh-meta [] {:watermark 0 :pending-nodes 0 :revision (biginteger 0)
                     :content-bytes (biginteger 0) :rendered-bytes (biginteger 0)})
(defn render-bytes [r] (utf8 (str (:start r) "+" (:count r) "|" (:text r) "\n")))
(defn representation [s n source units separator inherited]
  (let [suffix (str "\n[omitted; zoom " s "+" n "]")
        too-big (> (utf8 source) 512)
        text (if-not too-big source
               (loop [xs units kept nil]
                 (if-let [x (first xs)]
                   (let [candidate (if (nil? kept) x (str kept separator x))]
                     (if (<= (utf8 (str candidate suffix)) 512)
                       (recur (rest xs) candidate)
                       (if (nil? kept) (subs suffix 1) (str kept suffix))))
                   (if (nil? kept) (subs suffix 1) (str kept suffix)))))
        omitted (boolean (or too-big inherited))]
    {:start (long s) :count (long n) :text text :policy "extract-v1"
     :content-bytes (long (utf8 text)) :omitted? omitted
     :provenance (if omitted :extractive :verbatim)}))
(defn leaf [i m]
  (let [source (str (name (:kind m)) ": " (:text m))]
    (representation i 1 source (re-seq #"[^\n]*\n|[^\n]+$" source) "" false)))
(defn parent [s n left right]
  (representation s n (str (:text left) "\n" (:text right))
                  [(:text left) (:text right)] "\n"
                  (or (:omitted? left) (:omitted? right))))
(defn parent-address [s n]
  (let [pn (*' 2 n)]
    (when (<= pn Long/MAX_VALUE) [(long (- s (mod s pn))) (long pn)])))
(defn sibling [s n] (if (even? (quot s n)) (+' s n) (- s n)))
(defn append-meta [m r]
  (let [t (inc (:watermark m))]
    (-> m (assoc :watermark t)
        (update :pending-nodes + (long (Long/numberOfTrailingZeros t)))
        (update :revision #(biginteger (inc %)))
        (update :content-bytes #(biginteger (+' % (:content-bytes r))))
        (update :rendered-bytes #(biginteger (+' % (render-bytes r)))))))
(defn merge-meta [m l r par]
  (-> m
      (update :content-bytes #(biginteger (+' % (- (:content-bytes par) (:content-bytes l) (:content-bytes r)))))
      (update :rendered-bytes #(biginteger (+' % (- (render-bytes par) (render-bytes l) (render-bytes r)))))))
(defn better-candidate [t best candidate]
  (if-not best candidate
    (let [[c s] candidate [bc bs] best
          cmp (compare (*' (- t s) bc) (*' (- t bs) c))]
      (if (or (pos? cmp) (and (zero? cmp) (< s bs))) candidate best))))
(defn compact-result [m b merged]
  {:actions (+ b merged) :built b :merged merged :watermark (:watermark m)
   :revision (:revision m) :pending-nodes (:pending-nodes m)
   :over-budget? (> (:content-bytes m) 2048)})
(defn page-result [m s rows]
  (let [parts (mapv second rows) end (if-let [r (peek parts)] (+ (:start r) (:count r)) s)]
    (if (and (< s (:watermark m)) (not= s (:start (first parts))))
      (invalid)
      (ok (assoc m :parts parts :covered-start s :covered-end end
                 :budget-bytes 2048 :over-budget? (> (:content-bytes m) 2048)
                 :next-cursor (when (< end (:watermark m)) {:revision (:revision m) :start end}))))))
(defn ready-or-pending [s n r] (if r (assoc r :state :ready) {:state :pending :start s :count n}))

(def representation-schema
  (fixed-keys-schema {:start Long :count Long :text String :policy String
                      :content-bytes Long :omitted? Boolean :provenance Keyword}))
(def meta-schema (fixed-keys-schema {:watermark Long :pending-nodes Long
                                    :revision BigInteger :content-bytes BigInteger :rendered-bytes BigInteger}))
(def receipt-schema
  (fixed-keys-schema {:max-actions Long :actions Long :built Long :merged Long :watermark Long
                      :revision BigInteger :pending-nodes Long :over-budget? Boolean}))
(def chat-schema
  (fixed-keys-schema
    {:meta meta-schema
     :messages (map-schema Long (fixed-keys-schema {:kind Keyword :text String :timestamp Long}) {:subindex? true})
     :append-receipts (map-schema String Long {:subindex? true})
     :compact-receipts (map-schema String receipt-schema {:subindex? true})
     :nodes (map-schema Long (map-schema Long representation-schema {:subindex? true}) {:subindex? true})
     :parts (map-schema Long representation-schema {:subindex? true})
     :eligible (map-schema Long (map-schema Long Boolean {:subindex? true}) {:subindex? true})
     :candidates (map-schema Long (map-schema Long (fixed-keys-schema {:parent-text-bytes Long}) {:subindex? true}) {:subindex? true})
     :eligible-levels (set-schema Long) :candidate-levels (set-schema Long)}))

(deframafn mark-eligible [$$chats *chat *s *n]
  (parent-address *s *n :> *pa)
  (<<if *pa
    (identity *pa :> [*ps *pn])
    (local-select> (keypath *chat :nodes *n (long (sibling *s *n))) $$chats :> *sib)
    (<<if *sib
      (local-transform> [(keypath *chat :eligible *pn *ps) (termval true)] $$chats)
      (local-transform> [(keypath *chat :eligible-levels) (term (partial add-level *pn))] $$chats)))
  (:>))

(deframafn remove-index [$$chats *chat *index *flags *n *s]
  (local-transform> [(keypath *chat *index *n *s) NONE>] $$chats)
  (local-select> [(keypath *chat *index *n) (sorted-map-range-from-start 1)] $$chats :> *remaining)
  (<<if (empty? *remaining)
    (local-transform> [(keypath *chat *flags) (term (partial drop-level *n))] $$chats))
  (:>))

(deframafn mark-candidate [$$chats *chat *s *n *par]
  (quot *n 2 :> *c)
  (local-select> (keypath *chat :parts *s) $$chats :> *l)
  (local-select> (keypath *chat :parts (+ *s *c)) $$chats :> *r)
  (<<if (and> (= *c (get *l :count)) (= *c (get *r :count))
               (< (get *par :content-bytes) (+ (get *l :content-bytes) (get *r :content-bytes))))
    (local-transform> [(keypath *chat :candidates *c *s) (termval {:parent-text-bytes (get *par :content-bytes)})] $$chats)
    (local-transform> [(keypath *chat :candidate-levels) (term (partial add-level *c))] $$chats))
  (:>))

(deframafn best-merge [$$chats *chat *m]
  (local-select> [(keypath *chat :candidate-levels) (nil->val #{})] $$chats :> *levels)
  (loop<- [*ls (seq *levels) *best nil :> *result]
    (<<if (empty? *ls)
      (:> *best)
      (else>)
      (first *ls :> *c)
      (local-select> [(keypath *chat :candidates *c) (sorted-map-range-from-start 1)] $$chats :> *head)
      (first (first *head) :> *s)
      (continue> (next *ls) (better-candidate (get *m :watermark) *best [*c *s]))))
  (:> *result))

(deframafn do-build [$$chats *chat *n *s *m]
  (quot *n 2 :> *half)
  (local-select> (keypath *chat :nodes *half *s) $$chats :> *l)
  (local-select> (keypath *chat :nodes *half (+ *s *half)) $$chats :> *r)
  (parent *s *n *l *r :> *par)
  (local-transform> [(keypath *chat :nodes *n *s) (termval *par)] $$chats)
  (remove-index $$chats *chat :eligible :eligible-levels *n *s)
  (mark-eligible $$chats *chat *s *n)
  (mark-candidate $$chats *chat *s *n *par)
  (:> (update *m :pending-nodes dec)))

(deframafn do-merge [$$chats *chat *c *s *m]
  (* 2 *c :> *n)
  (local-select> (keypath *chat :parts *s) $$chats :> *l)
  (local-select> (keypath *chat :parts (+ *s *c)) $$chats :> *r)
  (local-select> (keypath *chat :nodes *n *s) $$chats :> *par)
  (remove-index $$chats *chat :candidates :candidate-levels *c *s)
  (local-transform> [(keypath *chat :parts (+ *s *c)) NONE>] $$chats)
  (local-transform> [(keypath *chat :parts *s) (termval *par)] $$chats)
  (parent-address *s *n :> *pa)
  (<<if *pa
    (identity *pa :> [*ps *pn])
    (local-select> (keypath *chat :nodes *pn *ps) $$chats :> *higher)
    (<<if *higher (mark-candidate $$chats *chat *ps *pn *higher)))
  (:> (merge-meta *m *l *r *par)))

(deframafn do-compact [$$chats *chat *limit *initial]
  (loop<- [*m *initial *b 0 *merged 0 :> *final *result]
    (<<if (= *limit (+ *b *merged))
      (:> *m (compact-result *m *b *merged))
      (else>)
      (<<if (> (get *m :content-bytes) 2048)
        (best-merge $$chats *chat *m :> *candidate)
        (else>)
        (identity nil :> *candidate))
      (<<if *candidate
        (identity *candidate :> [*c *s])
        (do-merge $$chats *chat *c *s *m :> *updated)
        (continue> *updated *b (inc *merged))
        (else>)
        (local-select> [(keypath *chat :eligible-levels) (nil->val #{})] $$chats :> *levels)
        (<<if (empty? *levels)
          (:> *m (compact-result *m *b *merged))
          (else>)
          (apply min *levels :> *n)
          (local-select> [(keypath *chat :eligible *n) (sorted-map-range-from-start 1)] $$chats :> *head)
          (first (first *head) :> *s)
          (do-build $$chats *chat *n *s *m :> *updated)
          (continue> *updated (inc *b) *merged)))))
  (<<if (pos? (get *result :actions))
    (biginteger (inc (get *final :revision)) :> *revision)
    (assoc *final :revision *revision :> *new-final)
    (assoc *result :revision *revision :> *new-result)
    (:> *new-final *new-result)
    (else>)
    (:> *final *result)))

(defmodule OptchatMemoryModule [setup topologies]
  (declare-depot setup *commands (hash-by :chat))
  (let [s (stream-topology topologies "commands")]
    (declare-pstate s $$chats {String chat-schema})
    (<<sources s
      (source> *commands :> {:keys [*chat *key *op *message *limit]})
      (<<if (= *op :append)
        (local-select> (keypath *chat :append-receipts *key) $$chats :> *old)
        (<<if (some? *old)
          (local-select> (keypath *chat :messages *old) $$chats :> *original)
          (<<if (= *original *message)
            (ack-return> {:ok {:index *old}})
            (else>) (ack-return> {:error :idempotency-conflict}))
          (else>)
          (local-select> [(keypath *chat :meta) (nil->val (fresh-meta))] $$chats :> *m)
          (get *m :watermark :> *i)
          (<<if (= *i Long/MAX_VALUE)
            (ack-return> {:error :capacity-exhausted})
            (else>)
            (leaf *i *message :> *r)
            (local-transform> [(keypath *chat :messages *i) (termval *message)] $$chats)
            (local-transform> [(keypath *chat :nodes 1 *i) (termval *r)] $$chats)
            (local-transform> [(keypath *chat :parts *i) (termval *r)] $$chats)
            (local-transform> [(keypath *chat :append-receipts *key) (termval *i)] $$chats)
            (local-transform> [(keypath *chat :meta) (termval (append-meta *m *r))] $$chats)
            (mark-eligible $$chats *chat *i 1)
            (ack-return> {:ok {:index *i}})))
        (else>)
        (local-select> (keypath *chat :meta) $$chats :> *m)
        (<<if (nil? *m)
          (ack-return> {:error :not-found})
          (else>)
          (local-select> (keypath *chat :compact-receipts *key) $$chats :> *receipt)
          (<<if *receipt
            (<<if (= *limit (get *receipt :max-actions))
              (ack-return> {:ok (dissoc *receipt :max-actions)})
              (else>) (ack-return> {:error :idempotency-conflict}))
            (else>)
            (do-compact $$chats *chat *limit *m :> *new *result)
            (local-transform> [(keypath *chat :meta) (termval *new)] $$chats)
            (local-transform> [(keypath *chat :compact-receipts *key) (termval (assoc *result :max-actions *limit))] $$chats)
            (ack-return> {:ok *result}))))))
  (<<query-topology topologies "page" [*chat *cursor :> *out]
    (|hash *chat)
    (local-select> (keypath *chat :meta) $$chats :> *m)
    (get *cursor :start 0 :> *start)
    (<<cond
      (case> (nil? *m)) (missing :> *out)
      (case> (and> *cursor (not= (get *cursor :revision) (get *m :revision))))
      (identity {:error :stale-view} :> *out)
      (case> (> *start (get *m :watermark))) (invalid :> *out)
      (case> (= *start (get *m :watermark))) (page-result *m *start [] :> *out)
      (default>)
      (local-select> [(keypath *chat :parts) (sorted-map-range-from *start 64)] $$chats :> *rows)
      (page-result *m *start *rows :> *out))
    (|origin))
  (<<query-topology topologies "children" [*chat *start *n :> *out]
    (|hash *chat)
    (local-select> (keypath *chat :meta) $$chats :> *m)
    (<<if (or> (nil? *m) (> (+' *start *n) (get *m :watermark)))
      (missing :> *out)
      (else>)
      (quot *n 2 :> *half)
      (+ *start *half :> *right)
      (local-select> (keypath *chat :nodes *half *start) $$chats :> *l)
      (local-select> (keypath *chat :nodes *half *right) $$chats :> *r)
      (ok {:children [(ready-or-pending *start *half *l) (ready-or-pending *right *half *r)]} :> *out))
    (|origin)))

(defn create-module []
  {:module OptchatMemoryModule
   :wrap-client
   (fn [ipc]
     (let [name (get-module-name OptchatMemoryModule)
           commands (foreign-depot ipc name "*commands")
           chats (foreign-pstate ipc name "$$chats")
           page (foreign-query ipc name "page")
           children (foreign-query ipc name "children")]
       (reify
         h/Synchronizable (wait-for-processing! [_] nil)
         p/OptchatMemory
         (append! [_ chat key message]
           (if (and (id? chat) (id? key) (message? message))
             (get (foreign-append! commands {:op :append :chat chat :key key :message (update message :timestamp long)} :ack) "commands")
             (invalid)))
         (compact! [_ chat key limit]
           (if (and (id? chat) (id? key) (integer-between? limit 1 32))
             (get (foreign-append! commands {:op :compact :chat chat :key key :limit (long limit)} :ack) "commands")
             (invalid)))
         (view [_ chat cursor]
           (if (and (id? chat) (cursor? cursor))
             (foreign-invoke-query page chat (when cursor {:revision (biginteger (:revision cursor)) :start (long (:start cursor))}))
             (invalid)))
         (zoom [_ chat start n]
           (if (and (id? chat) (address? start n))
             (if (= n 1)
               (if-let [m (foreign-select-one (keypath chat :messages (long start)) chats)]
                 (ok (assoc m :index (long start))) (missing))
               (foreign-invoke-query children chat (long start) (long n)))
             (invalid)))
         (date [_ chat index]
           (if (and (id? chat) (index? index))
             (if-let [t (foreign-select-one (keypath chat :messages (long index) :timestamp) chats)] (ok t) (missing))
             (invalid))))))})
