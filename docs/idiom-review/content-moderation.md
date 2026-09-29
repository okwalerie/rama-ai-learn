# Idiom review: content-moderation

Baseline: `43abd3fccff8777d8995f8a29658f3c4c97df01a`.

## Finding

The private reference wrapped the upstream `ContentModerationModule`, whose
`core` stream topology appends each post with
`(local-transform> [(keypath *to-user-id) AFTER-ELEM (termval *post)] $$posts)`.
Stream topologies are at-least-once. If processing fails after that write
commits (for example, at `:streaming-complete`), Rama retries the record and
appends the post again. The contract preserves intentional duplicate appends,
so a retry duplicate is indistinguishable from a real one and cannot be
filtered at read time.

## Fix (private reference only)

`test-resources/content_moderation/module.clj` now defines its own module
with the same depots, PState schemas, per-record logic, and query topologies,
but it processes `*post-depot` and `*mute-depot` in a `core`
`microbatch-topology`. Microbatch applies each depot record exactly once
under retry, which is the idiomatic Rama answer when writes are not
naturally idempotent. `wait-for-processing!` counts successful appends and
calls `rtest/wait-for-microbatch-processed-count`. The verbatim upstream copy
under `test-resources/upstream/nlb/` is unchanged. Protocol and skill are
unchanged.

## Regression test

`test-private/content_moderation/challenge_test.clj`
`retry-does-not-duplicate-posts` uses the `failed-streaming` idiom from
`auction-module/test-private/auction_module/performance_test_support.clj`:
the event hook returns `:fail` for the first `:streaming-complete` during
`post!`, then asserts the feed holds exactly one copy. The test failed on the
old reference (one failure, duplicated post) and passes on the fix; see
`challenges/content-moderation/test-private/EVIDENCE.md`.

## README

The README makes no false retry or state guarantee, so it is unchanged. It
does not state the exactly-once property the private test now enforces.
Minimal candidate sentence, to append after "preserving duplicate appends."
if maintainers want the contract to be explicit:

> Each accepted `post!` appears exactly once, even if processing is retried.
