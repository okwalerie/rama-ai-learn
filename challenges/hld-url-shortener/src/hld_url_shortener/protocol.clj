(ns hld-url-shortener.protocol
  "Protocol definition for the hld-url-shortener challenge.

   Time is logical. Every tick argument is a non-negative Long supplied
   by the caller; the module never consults wall-clock time.

   All `!` methods return nil. Their effects become visible to the read
   methods after `rama-challenges.harness/wait-for-processing!`. Read
   methods never wait and never mutate state.

   ORDERING. Sequential write calls from one client to the same logical
   owner (alias) are processed in invocation order. Writes from
   different clients may serialize in any order, but a write that
   returned before a wait-for-processing! barrier is processed before
   any write issued after that barrier. Tests use 2 and 4 tasks and a
   second client.

   LATENCY. Millisecond figures in the README are production
   aspirations, not test thresholds. Tests enforce the bounded-work
   contracts stated in each docstring.")

(defprotocol UrlShortener
  "Custom-alias URL shortener with immutable mappings, lifecycle flags,
   status-aware resolution, and a lifetime click counter.

   alias: String 1..32 chars of [a-z0-9-].
   request-id, click-id: String 1..64 chars of [A-Za-z0-9-].
   target-url: String 8..2048 printable ASCII chars starting with https://.
   expires-at: nil (never expires) or Long tick in [0, 10^12).
   now: Long tick in [0, 10^12)."

  (create-link! [this alias request-id target-url expires-at]
    "Request creation of alias -> target-url with the given expiry.
     Returns nil.

     Outcome rules, keyed by (alias, request-id):
     - If (alias, request-id) already has a recorded outcome: no effect.
       The recorded outcome stands even if target-url or expires-at
       differ from the original request (first request wins).
     - Else if alias has ever been created (by any request-id), including
       aliases since deleted, blocked, or expired: record outcome
       {:outcome :rejected :reason :alias-taken}. Nothing else changes.
     - Else: create the link with exactly this target-url and expires-at
       and record {:outcome :created}.
     Of concurrent creates for one alias with different request-ids,
     exactly one is :created and all others are :rejected. Sequential
     creates from one client for one alias are processed in invocation
     order, so the first one issued is the :created one.")

  (delete-link! [this alias]
    "Irreversibly delete alias. Returns nil. No effect when alias does not
     exist or is already deleted. A deleted alias stays reserved forever
     and its click count remains readable.")

  (block-link! [this alias]
    "Set alias's block flag. Returns nil. Idempotent. No effect when alias
     does not exist.")

  (unblock-link! [this alias]
    "Clear alias's block flag. Returns nil. Idempotent. No effect when
     alias does not exist.")

  (record-click! [this alias click-id]
    "Record a trusted click observation. Returns nil.
     - If alias does not exist: dropped, and no trace is kept (a later
       observation with the same click-id after the alias is created is
       a new observation).
     - Else if (alias, click-id) was already counted: no effect.
     - Else: the alias's lifetime click count increases by exactly 1,
       regardless of whether the link is currently active, expired,
       blocked, or deleted.")

  (get-create-outcome [this alias request-id]
    "Returns the recorded outcome for (alias, request-id):
       {:outcome :created}
       {:outcome :rejected :reason :alias-taken}
     or nil when no create with that pair has been processed. Fixed
     read work.")

  (resolve-alias [this alias now]
    "Returns exactly one of:
       {:status :missing}                        alias never created
       {:status :deleted  :target-url s :expires-at e}
       {:status :blocked  :target-url s :expires-at e}
       {:status :expired  :target-url s :expires-at e}
       {:status :active   :target-url s :expires-at e}
     Precedence when several apply: :deleted > :blocked > :expired
     > :active. :expired holds when expires-at is non-nil and
     now >= expires-at. :target-url and :expires-at are the values given
     at creation (:expires-at may be nil). Read work must be fixed,
     independent of the alias's click and request history.")

  (get-click-count [this alias]
    "Returns the lifetime number of distinct counted click observations
     for alias as a Long; 0 when alias does not exist or has no counted
     clicks. Read work must be fixed, independent of the count."))
