(ns who-to-follow.protocol
  "Contract for periodically computed follow recommendations.")

(defprotocol WhoToFollow
  "Follow graph analytics for Long account IDs."
  (follow! [this from to] "Record that from follows to. Returns nil.")
  (refresh! [this] "Request one recommendation sweep. Returns nil; test harness waits for completion.")
  (recommendations [this account-id]
    "Return a vector of at most 300 candidate Long IDs, excluding accounts already followed by account-id. Empty when absent."))
