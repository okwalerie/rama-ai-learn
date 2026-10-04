(ns optchat-memory.protocol)

(defprotocol OptchatMemory
  "Durable deterministic memory. README.md defines exact types, errors,
  extraction, ordering, pagination and resource bounds for every method."
  (append! [client chat-id request-key message]
    "Accept an immutable message; identical retries return its original index.")
  (view [client chat-id cursor]
    "Return at most 64 covering parts at one revision; nil starts a traversal.")
  (zoom [client chat-id start count]
    "Read an exact leaf or the ready/pending children of a complete binary range.")
  (date [client chat-id index]
    "Read the original epoch-millisecond timestamp.")
  (compact! [client chat-id request-key max-actions]
    "Perform 1..32 bounded deterministic actions; identical retries return the old result."))
