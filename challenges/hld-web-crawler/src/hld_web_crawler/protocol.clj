(ns hld-web-crawler.protocol
  "Protocol definition for the hld-web-crawler challenge.

   Time is logical. Every tick argument is a non-negative Long supplied
   by the caller; the module never consults wall-clock time.

   All `!` methods return nil. Their effects become visible to the read
   methods after `rama-challenges.harness/wait-for-processing!`. Read
   methods never wait and never mutate state.

   ORDERING. Sequential write calls from one client to the same logical
   owner (host) are processed in invocation order. Writes from
   different clients may serialize in any order, but a write that
   returned before a wait-for-processing! barrier is processed before
   any write issued after that barrier. Tests use 2 and 4 tasks and a
   second client.

   LATENCY. Millisecond figures in the README are production
   aspirations, not test thresholds. Tests enforce the bounded-work
   contracts stated in each docstring.
   A discover! batch preserves element order only within each host.")

(defprotocol CrawlFrontier
  "Host-addressed crawl frontier with exact dedup, lex-ordered per-host
   queues, caller-configured robots rules, politeness delay, and fenced
   leases.

   CANONICAL URL. Valid input: https://HOST[PATH][?QUERY][#FRAGMENT],
   scheme case-insensitive, HOST 1..253 chars of [A-Za-z0-9.-], PATH
   starting with / of printable ASCII (0x21..0x7E) excluding ? and #,
   QUERY of printable ASCII excluding #, total input <= 2048 chars.
   Canonical = \"https://\" + lowercase(HOST) + (PATH or \"/\")
   + (\"?\" + QUERY when a ? is present); fragment dropped. Anything
   else is invalid and ignored. \"path-and-query\" below means the
   canonical string after the host.

   host arguments are matched case-insensitively; lowercase is canonical.
   claim-id: String 1..64 chars of [A-Za-z0-9_-]. delay: Long [1, 10^6].
   rules: vector of <= 100 {:path-prefix String :allow? boolean}.
   now: Long tick in [0, 10^12). limit: Long [1, 100].

   CONSTANTS. LEASE-TICKS = 30. DEFAULT-DELAY = 1.

   ROBOTS. A URL is allowed iff, among rules whose :path-prefix is a
   prefix of the URL's path-and-query, the longest prefix has :allow?
   true; when both an allow and a disallow rule have that same longest
   prefix, allowed; when no rule matches, allowed.

   ROBOTS rules are configurable through set-host-policy! and never
   retroactive: a policy change affects only claims processed after it.

   HOST CLOCK. Each host has a logical clock, initially 0. A new claim
   at supplied `now` uses effective tick T = max(now, clock) and sets
   clock = T. A successful complete! sets clock = max(now, clock). A
   rejected complete! changes nothing, including the clock."

  (discover! [this urls]
    "Add URLs to the frontier. Returns nil. For each element:
     invalid -> ignored; canonical form already seen (any status) ->
     ignored; else the canonical URL becomes :queued for its host.
     Duplicates within one call count once. Element order is preserved
     only within each host; elements for different hosts may be
     processed in any relative order.")

  (set-host-policy! [this host delay rules]
    "Replace host's policy. Returns nil. Idempotent; last write wins.
     A host without a policy has delay 1 and no rules. Affects only
     claims processed afterwards: never re-evaluates leased, done,
     failed, or blocked URLs and never changes the lease, clock, fence
     counter, or last-claim-at.")

  (claim! [this host claim-id now]
    "Ask for host's next URL. Returns nil; outcome via get-claim. Rules:
     1. (host, claim-id) already recorded: no effect.
     2. T = max(now, host clock); host clock = T.
     3. If host has a lease and T < its :expires-at: record
        {:status :denied :reason :busy}; stop.
     4. If host has a lease and T >= its :expires-at: the leased URL
        returns to :queued and the lease is cleared.
     5. If host has a last-claim-at and T < last-claim-at + delay:
        record {:status :denied :reason :not-ready}; stop.
     6. Repeatedly take the lexicographically smallest :queued URL of
        host: if disallowed by the current rules, retire it as :blocked
        and continue; if none remain, record
        {:status :denied :reason :empty}; stop (last-claim-at unchanged).
     7. Grant: fence = host's previous fence + 1 (first fence is 1); the
        URL becomes :leased with lease {:url u :fence f :expires-at T+30};
        last-claim-at = T; record
        {:status :granted :url u :fence f :lease-expires-at (+ T 30)}.")

  (complete! [this host fence outcome now]
    "Report the result of a leased fetch. Returns nil. outcome is
     :fetched or :failed. Let C = max(now, host clock). The completion
     has an effect iff host has a lease whose :fence equals fence AND
     C < the lease's :expires-at. Then: the leased URL is retired as
     :done (for :fetched) or :failed, the lease is cleared, and host
     clock = C. Otherwise (no lease, a different fence, or
     C >= :expires-at, including exactly C = :expires-at) NOTHING
     changes: not the URL, the lease, last-claim-at, the fence counter,
     nor the clock. An expired lease stays recorded until the next
     claim! requeues it. A completion can never affect a lease other
     than the one it was issued for.")

  (get-claim [this host claim-id]
    "Returns the recorded outcome for (host, claim-id):
       {:status :granted :url String :fence Long :lease-expires-at Long}
       {:status :denied  :reason :busy | :not-ready | :empty}
     or nil when not processed. Immutable once recorded. Fixed read
     work.")

  (get-url [this url]
    "Returns nil when url is invalid or its canonical form was never
     seen, else
       {:url             String   ;; canonical
        :host            String   ;; lowercase
        :status          :queued | :leased | :done | :failed | :blocked
        :fence           Long or nil   ;; non-nil iff :leased
        :lease-expires-at Long or nil} ;; non-nil iff :leased
     A :leased URL whose :lease-expires-at <= the caller's notion of now
     is stale: it can no longer be completed and will be requeued by
     the host's next claim. Fixed read work.")

  (get-host [this host]
    "Returns
       {:host          String            ;; lowercase
        :delay         Long
        :rules         vector            ;; as set, [] by default
        :queued        Long              ;; count of :queued URLs
        :fence         Long              ;; last issued fence, 0 if none
        :last-claim-at Long or nil
        :lease         nil | {:url String :fence Long :expires-at Long}}
     For a never-seen host: {:host h :delay 1 :rules [] :queued 0
     :fence 0 :last-claim-at nil :lease nil}. Fixed read work; :queued
     is not computed by scanning.")

  (list-pending [this host from-url limit]
    "Returns a vector of at most limit canonical URLs of host whose
     status is :queued or :leased, in ascending String order, strictly
     after from-url (all from the smallest when from-url is nil). Pass
     the last returned URL as the next call's from-url. Read work
     bounded by limit."))
