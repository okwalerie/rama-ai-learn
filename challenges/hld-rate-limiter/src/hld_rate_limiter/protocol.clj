(ns hld-rate-limiter.protocol
  "Protocol definition for the hld-rate-limiter challenge.

   Time is logical. Every tick argument is a non-negative Long supplied
   by the caller; the module never consults wall-clock time.

   All `!` methods return nil. Their effects become visible to the read
   methods after `rama-challenges.harness/wait-for-processing!`. Read
   methods never wait and never mutate state.

   ORDERING. Sequential write calls from one client to the same logical
   owner (user) are processed in invocation order. Writes from
   different clients may serialize in any order, but a write that
   returned before a wait-for-processing! barrier is processed before
   any write issued after that barrier. Tests use 2 and 4 tasks and a
   second client.

   LATENCY. Millisecond figures in the README are production
   aspirations, not test thresholds. Tests enforce the bounded-work
   contracts stated in each docstring.")

(defprotocol RateLimiter
  "Per-user two-dimensional token-bucket rate limiter.

   user-id, request-id: String 1..64 chars of [A-Za-z0-9_-].
   endpoint: String 1..64 chars of [a-z0-9/_.-].
   version: Long in [1, 2^31).
   config: {:shadow?   boolean
            :user      {:capacity Long :refill Long}
            :endpoints {endpoint {:capacity Long :refill Long}}}
           with 1..16 endpoints, capacity in [1, 10^6], refill in [0, 10^6].
   cost: Long in [1, 10^6].  now: Long tick in [0, 10^12).

   BUCKET MODEL. A bucket has state (tokens, at) and parameters
   (capacity, refill). For T >= at:
     available(T) = min(capacity, tokens + (T - at) * refill)
   A debit of cost at T sets tokens = available(T) - cost and at = T.
   A denied request changes nothing. A freshly reset bucket has
   tokens = capacity (its `at` is then irrelevant).

   USER CLOCK. Each user has a logical clock, initially 0. A new
   (not previously recorded) check! at supplied `now` is evaluated at
   effective tick T = max(now, clock). The clock is set to T only when
   the decision debits (would-allow true, enforcing or shadow). A
   would-allow false decision records its outcome and changes nothing
   else, including the clock."

  (set-config! [this user-id version config]
    "Install config for user-id at version. Returns nil.
     - If the user already has a config with version >= the given
       version: no effect at all (buckets untouched).
     - Else: config and version replace the previous ones and EVERY
       bucket of the user (the user bucket and one bucket per endpoint
       in the new config) is reset to full. Buckets for endpoints absent
       from the new config are discarded. The user clock is unchanged.")

  (check! [this user-id request-id endpoint cost now]
    "Request a rate-limit decision. Returns nil. The decision is read via
     get-decision. Rules, in order:
     1. If (user-id, request-id) already has a recorded decision: no
        effect. Buckets and the user clock are untouched, even if
        endpoint, cost, or now differ from the original request.
     2. T = max(now, user clock). The clock is NOT changed here.
     3. No config for user-id: record
        {:allowed false :would-allow false :reason :no-config
         :tick T :config-version nil :remaining nil}.
     4. endpoint not in the config: record
        {:allowed shadow? :would-allow false :reason :unknown-endpoint
         :tick T :config-version v :remaining nil}.
     5. Let au = available(T) of the user bucket and ae = available(T)
        of the endpoint bucket.
        - If cost > capacity of either bucket: would-allow false,
          reason :cost-exceeds-capacity, no debit,
          remaining {:user au :endpoint ae}.
        - Else if au >= cost and ae >= cost: would-allow true, reason
          nil, BOTH buckets debited by cost at T, user clock = T,
          remaining {:user (au - cost) :endpoint (ae - cost)}.
        - Else: would-allow false, reason :insufficient-tokens, NO
          bucket debited, remaining {:user au :endpoint ae}.
        allowed = would-allow OR shadow?. Record
        {:allowed .. :would-allow .. :reason .. :tick T
         :config-version v :remaining ..}.
     Every would-allow false decision (rules 3, 4, and the two denied
     branches of 5) records its map and has no other effect: no debit
     and no clock change.
     Sequential checks from one client for one user are processed in
     invocation order. Checks from different clients for one user are
     processed in some serial order; the recorded decisions are exactly
     those of that order.")

  (get-decision [this user-id request-id]
    "Returns the recorded decision map for (user-id, request-id):
       {:allowed        boolean
        :would-allow    boolean
        :reason         nil | :insufficient-tokens | :no-config
                        | :unknown-endpoint | :cost-exceeds-capacity
        :tick           Long
        :config-version Long or nil
        :remaining      nil | {:user Long :endpoint Long}}
     or nil when that pair has not been processed. The map never changes
     once recorded. Fixed read work.")

  (get-config [this user-id]
    "Returns {:version Long :config config} as most recently installed,
     or nil when the user has no config. Fixed read work.")

  (get-status [this user-id endpoint now]
    "Read-only bucket view. Returns nil when the user has no config or
     endpoint is not in it. Otherwise, with T = max(now, user clock) and
     WITHOUT changing the clock or any bucket:
       {:tick           T
        :config-version Long
        :user           {:capacity Long :available Long}
        :endpoint       {:capacity Long :available Long}}
     where :available is available(T) of that bucket. Fixed read work."))
