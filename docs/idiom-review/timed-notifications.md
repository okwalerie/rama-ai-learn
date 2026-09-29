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
