# Content moderation

Store append-only posts with `:from-user-id`, `:to-user-id`, and `:content`.
Each recipient has an independent timeline ordered by append processing. A
user can mute or unmute an author; moderation is directional and hides that
author's posts only from that reader. A post's author may be the recipient.

`get-posts(user-id, offset, limit)` returns
`{:posts [post ...] :next-offset n-or-nil}`. Offset indexes the underlying
unfiltered timeline, not the visible results. The query scans forward from
that offset, omits muted authors, and continues until it fills `limit` or
reaches the end. A non-nil next offset is the position after the last
underlying entry scanned; nil means end of timeline. An empty page at end has
nil next offset, including when the requested offset is beyond the end.
Inputs are Long IDs, a nonnegative integer offset, a positive integer limit,
and Post records with string content. The returned posts form an oldest-first
vector with the original fields, preserving duplicate appends. Mute/unmute
takes effect for reads after processing and filters old and new posts alike.

This bounded package provides feed storage and mute filtering only: no policy
classifier, moderation queue, report handling, deletion, ranking, or content
delivery. Large mute sets and long runs of hidden posts increase query work;
the reference has no fixed-cost page guarantee.

Implement `content-moderation.protocol/ContentModeration` and the standard
`create-module`/`wrap-client` contract. Synchronize writes through
`rama-challenges.harness/Synchronizable`.
