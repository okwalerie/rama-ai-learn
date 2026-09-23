(ns hld-search-autocomplete.protocol
  "Protocol definition for the hld-search-autocomplete challenge.

   All `!` methods return nil. Their effects become visible to the read
   methods after `rama-challenges.harness/wait-for-processing!`. Read
   methods never wait and never mutate state.

   ORDERING. Sequential write calls from one client to the same logical
   owner (locale) are processed in invocation order. Writes from
   different clients may serialize in any order, but a write that
   returned before a wait-for-processing! barrier is processed before
   any write issued after that barrier. Tests use 2 and 4 tasks and a
   second client.

   LATENCY. Millisecond figures in the README are production
   aspirations, not test thresholds. Tests enforce the bounded-work
   contracts stated in each docstring.")

(defprotocol Autocomplete
  "Per-locale typeahead: generational snapshots, session-deduplicated
   trend events, persistent block list, exact top-k prefix queries.

   locale: String matching [a-z]{2}(-[a-z]{2})?.
   generation: Long in [1, 2^31).
   phrase: String 1..64 chars matching [a-z0-9]+( [a-z0-9]+)*.
   base: Long in [1, 10^6]. session-id: String 1..64 chars of [A-Za-z0-9_-].
   prefix: String 1..64 chars of [a-z0-9 ]. k: Long in [1, 10].

   SCORE of a phrase in a locale's current generation:
     score = base + 10 * unique-sessions
   where base is the phrase's snapshot value (0 when absent from the
   current snapshot) and unique-sessions is the number of distinct
   session-ids counted for the phrase in the current generation.

   CANDIDATES of a locale: phrases in the current snapshot plus phrases
   with unique-sessions >= 1 in the current generation, minus blocked
   phrases."

  (publish-snapshot! [this locale generation entries]
    "Publish the full corpus for locale at generation. entries is a
     vector of [phrase base] pairs with distinct phrases, 0..10,000
     entries; an empty vector is allowed. Returns nil.
     - If locale's current generation is >= generation: no effect.
     - Else: generation becomes current; the corpus is exactly entries
       (possibly empty); every unique-sessions count of the locale is
       reset to 0; the block list is unchanged.")

  (record-search! [this locale generation session-id phrase]
    "Record that session-id searched phrase under generation. Returns nil.
     - If generation is not locale's current generation (including when
       the locale has none): no effect and no trace.
     - Else if (phrase, session-id) was already counted in this
       generation: no effect.
     - Else unique-sessions of phrase increases by 1. A phrase absent
       from the snapshot becomes a candidate with base 0.")

  (block-phrase! [this locale phrase]
    "Add phrase to locale's block list. Returns nil. Idempotent.
     Persists across snapshot generations. Blocked phrases keep
     accumulating counts but never appear in suggest.")

  (unblock-phrase! [this locale phrase]
    "Remove phrase from locale's block list. Returns nil. Idempotent.")

  (suggest [this locale prefix k]
    "Returns a vector of at most k maps {:phrase String :score Long} for
     the candidates of locale whose phrase starts with prefix, ordered
     by :score descending, then :phrase ascending (String.compareTo).
     [] when the locale has no generation or nothing matches. Read work
     must be independent of the number of matching candidates and of
     corpus size.")

  (get-phrase [this locale phrase]
    "Returns
       {:generation  Long or nil     ;; locale's current generation
        :in-corpus?  boolean         ;; phrase in the current snapshot
        :base        Long            ;; snapshot base, 0 if not in corpus
        :sessions    Long            ;; unique-sessions in current generation
        :score       Long            ;; base + 10 * sessions
        :blocked?    boolean}
     For a locale with no generation: {:generation nil :in-corpus? false
     :base 0 :sessions 0 :score 0 :blocked? b}. Fixed read work.")

  (get-generation [this locale]
    "Returns locale's current generation as a Long, or nil when no
     snapshot has been published. Fixed read work."))
