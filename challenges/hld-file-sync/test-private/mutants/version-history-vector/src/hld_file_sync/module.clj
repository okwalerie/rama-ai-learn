;; IMPORTANT: Before modifying this file, re-read test-resources/PLAN.md.
;; Adhere to all previously decided design decisions.

(ns hld-file-sync.module
  "Private reference implementation for the hld-file-sync challenge.

   One depot `*commands` (hash-by :ns-id), one microbatch topology `core`
   owning one PState `$$namespaces`, one query topology `file-head`.
   A namespace's commands within a microbatch are stamped with a durable
   ingress position in pre-agg, grouped per namespace, sorted, and applied
   by one sequential, cooperatively yielding loop event."
  (:require
   [com.rpl.rama :refer :all]
   [com.rpl.rama.path :refer :all]
   [com.rpl.rama.aggs :as aggs]
   [com.rpl.rama.test :as rtest]
   [rama-challenges.harness :as harness]
   [hld-file-sync.protocol]))

;; ---------------------------------------------------------------------------
;; Records: depot payloads and durable outcomes

(definterface ICommand)
(defrecord RegisterBlocks [ns-id request-id blocks] ICommand)
(defrecord CommitFile [ns-id request-id file-id path blocklist parent-version] ICommand)

(definterface IOutcome
  (^clojure.lang.IPersistentMap outcomeFields []))

(defrecord RegisterAccepted [registered]
  IOutcome
  (outcomeFields [_] {:status :accepted :registered registered}))

(defrecord CommitAccepted [file-id version seq size-bytes conflict-copy? conflict-of]
  IOutcome
  (outcomeFields [_]
    {:status :accepted
     :file-id file-id
     :version version
     :seq seq
     :size-bytes size-bytes
     :conflict-copy? conflict-copy?
     :conflict-of conflict-of}))

(defrecord Rejected [reason need-blocks]
  IOutcome
  (outcomeFields [_]
    (cond-> {:status :rejected :reason reason}
      (= reason :need-blocks) (assoc :need-blocks need-blocks))))

;; ---------------------------------------------------------------------------
;; Bounds and structural validation (client side, before any append)

(def MAX-ID 128)
(def MAX-PATH 1024)
(def MAX-SIZE 1000000000)
(def MAX-BLOCKS 1024)
(def MAX-LIMIT 500)

(defn- fail! [msg] (throw (IllegalArgumentException. ^String msg)))

(defn- check-id! [what v]
  (when-not (and (string? v) (pos? (count v)) (<= (count v) MAX-ID))
    (fail! (str what " must be a non-empty String of at most " MAX-ID " characters"))))

(defn- generated-file-id? [v]
  (and (string? v)
       (>= (count v) 2)
       (<= (count v) (inc MAX-ID))
       (= \~ (.charAt ^String v 0))))

(defn- client-file-id? [v]
  (and (string? v)
       (pos? (count v))
       (<= (count v) MAX-ID)
       (neg? (.indexOf ^String v "~"))))

(defn- check-file-id! [v]
  (when-not (or (client-file-id? v) (generated-file-id? v))
    (fail! "file-id must be client form (1..128 chars, no ~) or generated form (~ + request-id)")))

(defn- check-long! [what v lo hi]
  (when-not (and (instance? Long v) (<= lo v) (<= v hi))
    (fail! (str what " must be a Long in [" lo ", " hi "]"))))

(defn- check-blocks! [blocks]
  (when-not (and (vector? blocks) (<= 1 (count blocks) MAX-BLOCKS))
    (fail! (str "blocks must be a vector of 1.." MAX-BLOCKS " entries")))
  (reduce (fn [seen b]
            (when-not (map? b) (fail! "each block must be a map {:hash String :size Long}"))
            (let [h (:hash b) s (:size b)]
              (check-id! "hash" h)
              (check-long! "size" s 0 MAX-SIZE)
              (when-let [prev (get seen h)]
                (when (not= prev s) (fail! (str "hash " h " given with two different sizes"))))
              (assoc seen h s)))
          {}
          blocks))

(defn- check-blocklist! [blocklist]
  (when-not (and (vector? blocklist) (<= (count blocklist) MAX-BLOCKS))
    (fail! (str "blocklist must be a vector of 0.." MAX-BLOCKS " hashes")))
  (doseq [h blocklist] (check-id! "hash" h)))

(defn- check-path! [v]
  (when-not (and (string? v) (pos? (count v)) (<= (count v) MAX-PATH))
    (fail! (str "path must be a non-empty String of at most " MAX-PATH " characters"))))

(defn- check-parent-version! [v]
  (when-not (or (nil? v) (and (instance? Long v) (>= v 1)))
    (fail! "parent-version must be nil or a Long >= 1")))

;; ---------------------------------------------------------------------------
;; Bounded pure helpers (inputs are <= 1024 entries by structural validation)

(defn sort-pairs-by-position
  "Orders the [position command] pairs of one namespace by ingress position.
   +vec-agg element order is not relied on."
  [pairs]
  (vec (sort-by first pairs)))

(defn distinct-hashes
  "Distinct hashes of a blocklist / block vector in first-occurrence order."
  [hashes]
  (vec (distinct hashes)))

(defn register-plan
  "Given the submitted blocks and the map of already-known sizes for their
   hashes, returns {:mismatch? bool :new [[hash size] ...]}. `:new` lists
   each unknown hash once in first-occurrence order; membership uses a set
   so the work is linear in the submitted list."
  [blocks known]
  (let [mismatch? (some (fn [{:keys [hash size]}]
                          (let [k (get known hash)]
                            (and (some? k) (not= k size))))
                        blocks)
        new (:new (reduce (fn [acc {:keys [hash size]}]
                            (if (or (contains? known hash) (contains? (:seen acc) hash))
                              acc
                              (-> acc
                                  (update :new conj [hash size])
                                  (update :seen conj hash))))
                          {:seen #{} :new []}
                          blocks))]
    {:mismatch? (boolean mismatch?) :new new}))

(defn commit-precheck
  "Existence / parent checks in README order. Returns a rejection reason or nil."
  [parent-version head]
  (cond
    (and (nil? parent-version) (some? head)) :file-exists
    (and (some? parent-version) (nil? head)) :no-such-file
    (and (some? parent-version) (> parent-version head)) :unknown-parent
    :else nil))

(defn missing-hashes
  "Hashes of the blocklist absent from `known`, each once, first-occurrence order."
  [blocklist known]
  (vec (distinct (remove #(contains? known %) blocklist))))

(defn size-bytes-of
  "Sum of sizes over the blocklist counting every repeat."
  [blocklist known]
  (reduce (fn [acc h] (+ acc (long (get known h)))) 0 blocklist))

(defn conflict-path
  "path truncated so that (str prefix suffix) never exceeds MAX-PATH."
  [path request-id]
  (let [suffix (str " (conflicted copy " request-id ")")
        room (- MAX-PATH (count suffix))
        prefix (subs path 0 (min (count path) room))]
    (str prefix suffix)))

(defn commit-plan
  "Decides an accepted commit. `head`/`co` are the targeted file's current head
   and provenance (nil when absent). Returns the write plan as a map."
  [cmd head co known prev-seq]
  (let [{:keys [request-id file-id path blocklist parent-version]} cmd
        missing (missing-hashes blocklist known)]
    (if (seq missing)
      {:reason :need-blocks :missing missing}
      (let [size (size-bytes-of blocklist known)
            seq (inc (long prev-seq))
            copy? (and (some? parent-version) (< parent-version head))]
        (if copy?
          {:target (str "~" request-id) :version 1 :seq seq :size size
           :copy? true :conflict-of file-id :new-path (conflict-path path request-id)}
          {:target file-id :version (if (nil? head) 1 (inc (long head))) :seq seq :size size
           :copy? false :conflict-of co :new-path path})))))

(defn version-record [path blocklist size request-id seq conflict-of]
  {:path path :blocklist blocklist :size-bytes size
   :request-id request-id :seq seq :conflict-of conflict-of})

(defn journal-entry [file-id version path size request-id conflict-of]
  {:file-id file-id :version version :path path
   :size-bytes size :request-id request-id :conflict-of conflict-of})

(defn request-record [command cmd outcome]
  {:command command :payload cmd :outcome outcome :conflicting-attempts 0})

(defn bump-conflicts [req]
  (update req :conflicting-attempts (fnil inc 0)))

(defn head-record [file-id head rec]
  (assoc rec :file-id file-id :version head))

(defn render-outcome
  "Structural conversion of a stored request record (payload omitted) to the
   public outcome map; nil when the request-id was never processed."
  [req]
  (when (seq req)
    (let [^IOutcome outcome (:outcome req)]
      (merge (.outcomeFields outcome)
             {:command (:command req)
              :conflicting-attempts (:conflicting-attempts req)}))))

(defn render-change [[seq entry]]
  (assoc entry :seq seq))

(defn known-with
  "Adds hash -> size to the known map when the size is non-nil."
  [known hash size]
  (if (nil? size) known (assoc known hash size)))

(deframaop read-known-sizes
  "Point-reads the registered size of each distinct hash (<= 1024) on the
   namespace's task, yielding cooperatively between reads. Uses plain
   (non-yielding) selects so the attempt's own uncommitted block writes are
   visible: a yielding select that suspends reads a committed snapshot and
   would miss blocks registered earlier in the same microbatch."
  [$$p *ns *hashes]
  (loop<- [*todo *hashes *known {} :> *out]
    (yield-if-overtime)
    (<<if (empty? *todo)
      (:> *known)
     (else>)
      (first *todo :> *h)
      (local-select> [(keypath *ns :blocks *h)] $$p :> *s)
      (continue> (rest *todo) (known-with *known *h *s))))
  (:> *out))

;; ---------------------------------------------------------------------------
;; Module

(defmodule FileSyncModule [setup topologies]
  (declare-depot setup *commands (hash-by :ns-id))
  (set-launch-depot-dynamic-option! setup "*commands" "depot.microbatch.max.records" 200)

  (let [mb (microbatch-topology topologies "core")]
    (declare-pstate mb $$namespaces
      {String
       (fixed-keys-schema
         {:next-seq    Long
          :ingress-seq Long
          :blocks      (map-schema String Long {:subindex? true})
          :files       (map-schema String
                         (fixed-keys-schema
                           {:head        Long
                            :conflict-of String
                            :versions    (map-schema Long
                                           (fixed-keys-schema
                                             {:path        String
                                              :blocklist   (vector-schema String)
                                              :size-bytes  Long
                                              :request-id  String
                                              :seq         Long
                                              :conflict-of String})
                                           {:subindex? true})})
                         {:subindex? true})
          :journal     (map-schema Long
                         (fixed-keys-schema
                           {:file-id String :version Long :path String
                            :size-bytes Long :request-id String :conflict-of String})
                         {:subindex? true})
          :requests    (map-schema String
                         (fixed-keys-schema
                           {:command              clojure.lang.Keyword
                            :payload              ICommand
                            :outcome              IOutcome
                            :conflicting-attempts Long})
                         {:subindex? true})})})

    (<<sources mb
      (source> *commands :> %mb)
      (<<batch
        ;; pre-agg: synchronous, on the depot partition task, in append order
        (%mb :> *cmd)
        (get *cmd :ns-id :> *ns)
        (local-select> [(keypath *ns :ingress-seq) (nil->val 0)] $$namespaces :> *prev-pos)
        (inc *prev-pos :> *pos)
        (local-transform> [(keypath *ns :ingress-seq) (termval *pos)] $$namespaces)
        (vector *pos *cmd :> *pair)
        ;; agg: one row per namespace, routed to the namespace's task
        (+group-by *ns
          (aggs/+vec-agg *pair :> *pairs))
        ;; post-agg: one ordered, cooperative loop per namespace per microbatch
        (sort-pairs-by-position *pairs :> *ordered)
        (loop<- [*remaining *ordered :> *done]
          (yield-if-overtime)
          (<<if (empty? *remaining)
            (:> true)
           (else>)
            (first *remaining :> [*p *cmd])
            (get *cmd :request-id :> *rid)
            (local-select> [(keypath *ns :requests *rid)] $$namespaces :> *req)
            (<<cond
              (case> (nil? *req))
              ;; ---- original command
              (<<if (instance? RegisterBlocks *cmd)
                (get *cmd :blocks :> *blocks)
                (distinct-hashes (mapv :hash *blocks) :> *hashes)
                (read-known-sizes $$namespaces *ns *hashes :> *known)
                (register-plan *blocks *known :> {:keys [*mismatch? *new]})
                (<<if *mismatch?
                  (->Rejected :size-mismatch nil :> *outcome)
                 (else>)
                  (loop<- [*todo *new :> *written]
                    (yield-if-overtime)
                    (<<if (empty? *todo)
                      (:> true)
                     (else>)
                      (first *todo :> [*h *s])
                      (local-transform> [(keypath *ns :blocks *h) (termval *s)] $$namespaces)
                      (continue> (rest *todo))))
                  (->RegisterAccepted (long (count *new)) :> *outcome))
                (request-record :register-blocks *cmd *outcome :> *record)
               (else>)
                (identity *cmd :> {:keys [*file-id *path *blocklist *parent-version]})
                (local-select> [(keypath *ns :files *file-id :head)] $$namespaces :> *head)
                (commit-precheck *parent-version *head :> *pre-reason)
                (<<if *pre-reason
                  (->Rejected *pre-reason nil :> *outcome)
                 (else>)
                  (local-select> [(keypath *ns :files *file-id :conflict-of)] $$namespaces :> *co)
                  (distinct-hashes *blocklist :> *hashes)
                  (read-known-sizes $$namespaces *ns *hashes :> *known)
                  (local-select> [(keypath *ns :next-seq) (nil->val 0)] $$namespaces :> *prev-seq)
                  (commit-plan *cmd *head *co *known *prev-seq
                               :> {:keys [*reason *missing *target *version *seq *size
                                          *copy? *conflict-of *new-path]})
                  (<<if *reason
                    (->Rejected *reason *missing :> *outcome)
                   (else>)
                    (<<if *copy?
                      (local-transform> [(keypath *ns :files *target :conflict-of)
                                         (termval *conflict-of)] $$namespaces))
                    (local-transform> [(keypath *ns :files *target :head) (termval *version)]
                                      $$namespaces)
                    (local-transform> [(keypath *ns :files *target :versions *version)
                                       (termval (version-record *new-path *blocklist *size
                                                                *rid *seq *conflict-of))]
                                      $$namespaces)
                    (local-transform> [(keypath *ns :journal *seq)
                                       (termval (journal-entry *target *version *new-path
                                                               *size *rid *conflict-of))]
                                      $$namespaces)
                    (local-transform> [(keypath *ns :next-seq) (termval *seq)] $$namespaces)
                    (->CommitAccepted *target *version *seq *size *copy? *conflict-of
                                      :> *outcome)))
                (request-record :commit-file *cmd *outcome :> *record))
              (local-transform> [(keypath *ns :requests *rid) (termval *record)] $$namespaces)

              (case> (= (get *req :payload) *cmd))
              ;; ---- replay: no effect
              (identity nil)

              (default>)
              ;; ---- conflicting attempt: only the counter changes
              (local-transform> [(keypath *ns :requests *rid) (termval (bump-conflicts *req))]
                                $$namespaces))
            (continue> (rest *remaining)))))))

  (<<query-topology topologies "file-head"
    [*ns *fid :> *result]
    (|hash *ns)
    (local-select> [(keypath *ns :files *fid :head)] $$namespaces :> *head)
    (<<if (nil? *head)
      (identity nil :> *result)
     (else>)
      (local-select> [(keypath *ns :files *fid :versions *head)] $$namespaces :> *rec)
      (head-record *fid *head *rec :> *result))
    (|origin)))

;; ---------------------------------------------------------------------------
;; Client

(defn make-client
  "Protocol client. `cnt` is the append counter shared by every client of one
   create-module result (synchronization bookkeeping only, no business state)."
  [ipc cnt]
  (let [module-name (get-module-name FileSyncModule)
        commands (foreign-depot ipc module-name "*commands")
        namespaces (foreign-pstate ipc module-name "$$namespaces")
        file-head (foreign-query ipc module-name "file-head")]
    (reify hld-file-sync.protocol/FileSyncModule
      (register-blocks! [_ request-id ns-id blocks]
        (check-id! "request-id" request-id)
        (check-id! "ns-id" ns-id)
        (check-blocks! blocks)
        (swap! cnt inc)
        (foreign-append! commands (->RegisterBlocks ns-id request-id blocks))
        nil)

      (commit-file! [_ request-id ns-id file-id path blocklist parent-version]
        (check-id! "request-id" request-id)
        (check-id! "ns-id" ns-id)
        (check-file-id! file-id)
        (check-path! path)
        (check-blocklist! blocklist)
        (check-parent-version! parent-version)
        (when (and (nil? parent-version) (not (client-file-id? file-id)))
          (fail! "a create (parent-version nil) requires a client-form file-id"))
        (swap! cnt inc)
        (foreign-append! commands
                         (->CommitFile ns-id request-id file-id path blocklist parent-version))
        nil)

      (get-outcome [_ ns-id request-id]
        (check-id! "ns-id" ns-id)
        (check-id! "request-id" request-id)
        (render-outcome
          (foreign-select-one [(keypath ns-id :requests request-id)
                               (submap [:command :outcome :conflicting-attempts])]
                              namespaces)))

      (get-block-size [_ ns-id hash]
        (check-id! "ns-id" ns-id)
        (check-id! "hash" hash)
        (foreign-select-one [(keypath ns-id :blocks hash)] namespaces))

      (get-file [_ ns-id file-id]
        (check-id! "ns-id" ns-id)
        (check-file-id! file-id)
        (foreign-invoke-query file-head ns-id file-id))

      (get-file-version [_ ns-id file-id version]
        (check-id! "ns-id" ns-id)
        (check-file-id! file-id)
        (check-long! "version" version 1 Long/MAX_VALUE)
        (when-let [rec (foreign-select-one [(keypath ns-id :files file-id :versions version)]
                                           namespaces)]
          (head-record file-id version rec)))

      (get-changes [_ ns-id after-seq limit]
        (check-id! "ns-id" ns-id)
        (check-long! "after-seq" after-seq 0 Long/MAX_VALUE)
        (check-long! "limit" limit 1 MAX-LIMIT)
        (if (= after-seq Long/MAX_VALUE)
          []
          (mapv render-change
                (foreign-select [(keypath ns-id :journal)
                                 (sorted-map-range-from (inc after-seq) {:max-amt limit})
                                 ALL]
                                namespaces))))

      harness/Synchronizable
      (wait-for-processing! [_]
        (rtest/wait-for-microbatch-processed-count ipc module-name "core" @cnt)))))

(defn create-module
  "Returns {:module :wrap-client}. All clients of one result share a single
   append counter used only for wait-for-processing!."
  []
  (let [cnt (atom 0)]
    {:module      FileSyncModule
     :wrap-client (fn [ipc] (make-client ipc cnt))}))
