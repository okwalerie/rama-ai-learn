(ns hld-notification-system.protocol
  "Protocol definition for the hld-notification-system challenge.

   Time is logical. Every tick argument is a non-negative Long supplied
   by the caller; the module never consults wall-clock time.

   All `!` methods return nil. Their effects become visible to the read
   methods after `rama-challenges.harness/wait-for-processing!`. Read
   methods never wait and never mutate state.

   ORDERING. Sequential write calls from one client to the same logical
   owner (recipient user, or submission for attempt and receipt reports) are processed in invocation order. Writes from
   different clients may serialize in any order, but a write that
   returned before a wait-for-processing! barrier is processed before
   any write issued after that barrier. Tests use 2 and 4 tasks and a
   second client.

   LATENCY. Millisecond figures in the README are production
   aspirations, not test thresholds. Tests enforce the bounded-work
   contracts stated in each docstring.")

(defprotocol NotificationSystem
  "Push-only notification core: device registry, preferences, idempotent
   submissions, guarded delivery attempts, dead letters, receipts.

   user-id, device-id, submission-id: String 1..64 chars of [A-Za-z0-9_-].
   token: String 1..256 chars of [A-Za-z0-9].
   category: String 1..32 chars of [a-z_].
   payload: String 0..4096 chars. ttl: Long in [1, 10^6].
   now: Long tick in [0, 10^12). attempt-no: Long in [1, 3].

   CONSTANTS. MAX-DEVICES = 8. MAX-ATTEMPTS = 3. Retry delay after
   attempt 1 = 10 ticks; after attempt 2 = 20 ticks.

   DELIVERY MAP (one per (submission, device)):
     {:token           String   ;; snapshot at submit
      :generation      Long     ;; snapshot at submit
      :state           :pending | :accepted | :delivered | :read
                       | :failed | :expired | :invalid-token
      :attempts        Long     ;; reported attempts applied so far, 0..3
      :next-attempt-at Long or nil}  ;; non-nil iff :state is :pending
   :failed, :expired, :invalid-token are terminal."

  (register-device! [this user-id device-id token]
    "Register or refresh a device. Returns nil.
     - If device-id is new for user-id and the user already has 8
       devices: no effect.
     - If device-id is new: generation 1, token as given, valid.
     - Else: generation = previous generation + 1, token replaced,
       valid (even if previously invalidated).")

  (set-preference! [this user-id category enabled?]
    "Set the enabled flag for (user-id, category). Returns nil.
     Unset categories are enabled. Idempotent; last write wins.")

  (submit! [this submission-id user-id category payload ttl now]
    "Submit a notification. Returns nil. Rules:
     - If submission-id was already submitted (by anyone): no effect,
       even if the arguments differ (first submission wins).
     - Else record the submission with submitted-at = now and
       expires-at = now + ttl, and:
       - preference for (user-id, category) disabled: status :suppressed,
         no deliveries;
       - no currently valid device: status :no-devices, no deliveries;
       - else status :dispatched with one delivery per currently valid
         device: {:token t :generation g :state :pending :attempts 0
                  :next-attempt-at now} using that device's current
         token and generation.
     Devices registered or invalidated after submission never change the
     submission's deliveries.")

  (report-attempt! [this submission-id device-id attempt-no outcome now]
    "Report the outcome of a delivery attempt. Returns nil. outcome is
     :accepted | :transient-failure | :permanent-failure | :invalid-token.
     Rules, in order, for the delivery (submission-id, device-id):
     1. No such submission or no delivery for device-id: no effect.
     2. Delivery :state is not :pending: no effect.
     3. attempt-no != :attempts + 1, or now < :next-attempt-at: no
        effect (stale, duplicate, out-of-order, or early report), even
        when now >= expires-at. A stale report never expires a delivery.
     4. now >= the submission's expires-at: state := :expired,
        :next-attempt-at := nil, :attempts unchanged; outcome discarded.
     5. :attempts := attempt-no, then by outcome:
        :accepted          -> state :accepted, :next-attempt-at nil.
        :transient-failure -> attempt-no 1: :next-attempt-at := now + 10;
                              attempt-no 2: :next-attempt-at := now + 20;
                              attempt-no 3: state :failed,
                                :next-attempt-at nil, dead letter with
                                reason :retries-exhausted at now.
        :permanent-failure -> state :failed, :next-attempt-at nil, dead
                              letter with reason :permanent-failure at now.
        :invalid-token     -> state :invalid-token, :next-attempt-at nil;
                              the device (user-id, device-id) is marked
                              invalid iff its CURRENT generation equals
                              the delivery's :generation. No dead letter.")

  (record-receipt! [this submission-id device-id receipt]
    "Record a client receipt, :delivered or :read. Returns nil.
     Accepted only when the delivery exists and its state is :accepted,
     :delivered, or :read; otherwise (no delivery, :pending, or a
     terminal state) no effect. When accepted, with the order
     :accepted < :delivered < :read, the state becomes the maximum of
     the current state and receipt; :next-attempt-at stays nil;
     :attempts is unchanged. Receipts may arrive in any order relative
     to each other; a receipt that arrives before the delivery is
     :accepted is ignored, not deferred.")

  (get-devices [this user-id]
    "Returns {device-id {:token String :generation Long :valid? boolean}}
     for every registered device of user-id; {} when none. Fixed read
     work.")

  (get-preferences [this user-id]
    "Returns {category boolean} for every category explicitly set for
     user-id; {} when none. Fixed read work.")

  (get-submission [this submission-id]
    "Returns nil when never submitted, else
       {:submission-id String
        :user-id       String
        :category      String
        :payload       String
        :submitted-at  Long
        :expires-at    Long
        :status        :suppressed | :no-devices | :dispatched
        :deliveries    {device-id delivery-map}}   ;; {} unless :dispatched
     Fixed read work.")

  (get-recent-submissions [this user-id]
    "Returns a vector of at most 100 submission-ids for user-id, most
     recently accepted first (acceptance = the module's processing order
     of first-wins submissions). Includes :suppressed and :no-devices
     submissions. [] when none. Read work bounded by the page size,
     independent of total history.")

  (get-dead-letters [this user-id]
    "Returns a vector of at most 100 dead-letter entries for user-id,
     most recently dead-lettered first, each
       {:submission-id String :device-id String
        :reason :permanent-failure | :retries-exhausted
        :at Long}
     [] when none. Read work bounded by the page size."))
