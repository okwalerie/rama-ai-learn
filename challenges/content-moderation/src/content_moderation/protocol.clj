(ns content-moderation.protocol)

(defrecord Post [from-user-id to-user-id content])
(defrecord Mute [user-id muted-user-id])
(defrecord Unmute [user-id unmuted-user-id])

(defprotocol ContentModeration
  "Append posts addressed to a user and paginate that user's chronological
  feed. Muting is directional: a user hides posts authored by a muted user.
  Writes are visible after wait-for-processing!; reads never wait."
  (post! [this post]
    "Accepts Post with Long author and recipient IDs and string content.")
  (mute! [this user-id muted-user-id]
    "Mute an author for a reader; both IDs are Long.")
  (unmute! [this user-id unmuted-user-id]
    "Remove a reader-to-author mute; both IDs are Long.")
  (get-posts [this user-id offset limit]
    "For a Long user ID, nonnegative integer offset, and positive integer
    limit, return {:posts vector :next-offset integer-or-nil}. Each post has
    :from-user-id, :to-user-id, and :content matching its append. Posts are
    oldest-first in append order, including repeated identical posts.
    Offset indexes the recipient's unfiltered append-only feed, not visible
    posts. Scan forward, skip authors currently muted by this reader, and
    return at most limit posts. The next offset is the exclusive index after
    the last underlying entry scanned, including hidden entries; nil means
    the end was reached (even if the page is full). An offset at or beyond
    the end yields {:posts [] :next-offset nil}."))
