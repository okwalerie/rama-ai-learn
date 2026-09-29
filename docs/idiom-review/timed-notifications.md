# Timed notifications retry guarantee

The README currently says the tests "do not measure latency or prove worker
restart, replay, or exactly-once delivery behavior." That is now too broad:
the private suite forces one stream retry of `schedule-post!` and verifies the
scheduled item appears once. It still does not establish worker-restart or
replay behavior.

Suggested minimal replacement sentence:

> They do not measure latency or prove worker restart or replay behavior; a
> private regression test verifies exactly-once delivery after a forced stream
> retry.

This is a proposal only; the challenge README was not changed.

Reference design: the client assigns a stable schedule ID before appending, so
a retried depot record carries the same ID. Delivery point-checks
`[(keypath account) (view contains? id)]` on the subindexed `$$delivered` set
and, only when absent, writes `NONE-ELEM` there and `AFTER-ELEM` to `$$feeds`.
Both writes occur in one event on the account's task, so they are atomic
together. A forced retry of the tick event did not duplicate delivery even
before this change (TopologyScheduler already handles that case), so no tick
retry test was added.
