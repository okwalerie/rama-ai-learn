# Timed notifications

Build a backend that stores scheduled posts and appends them to each account's
feed when their scheduled millisecond is reached.

## Contract

- Account IDs and post bodies are strings. Scheduled times are absolute
  non-negative milliseconds on the module's simulated clock. Inputs satisfy
  this domain.
- `schedule-post!` records the account, scheduled time, and post. It returns
  `nil`; delivery is asynchronous.
- On timer processing, every scheduled item whose time is less than or equal
  to the current simulated time becomes visible in that account's feed.
- Posts for separate accounts remain isolated. A not-yet-due item is not
  delivered early. Feed order follows scheduled delivery order; no tie order
  is promised for equal times.
- Production processing is timer-driven. `tick!` is a test-only hook used
  with simulated time; it is not an application requirement.

## Limits and verification

Tests advance simulated time and trigger timer processing. They check a time
just before and exactly at a due boundary, multiple times, multiple accounts,
and repeated ticks. They do not measure latency or prove worker restart,
replay, or exactly-once delivery behavior.

For manual-tick tests, reference and candidate modules may select a random
test tick depot while building the module by honoring
`rama-challenges.shared/REPLACE-TICK-DEPOTS`. Tests bind this shared setting
before calling `create-module`; production uses the automatic timer by default.
The client implements `rama-challenges.harness/Synchronizable`; tests call
`wait-for-processing!` after each tick.

## Interface

Implement `timed-notifications.protocol/TimedNotifications` and provide
`timed-notifications.module/create-module`, returning `:module` and
`:wrap-client`. See `src/timed_notifications/protocol.clj` for call
signatures.
