(ns timed-notifications.protocol
  "Contract for posts delivered to feeds when their scheduled time arrives.")

(defprotocol TimedNotifications
  (schedule-post! [this account-id time-millis post]
    "Schedule a string post for a string account at an absolute simulated-time millisecond. Returns nil.")
  (tick! [this] "Process due work using the current simulated clock; test helper only.")
  (feed [this account-id] "Return the vector of delivered post strings, in due-processing order."))
