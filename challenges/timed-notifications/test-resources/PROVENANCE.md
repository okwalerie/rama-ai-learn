# Imported source provenance

The upstream source `nlb/timed_notifications.clj` is from
next-level-backends-with-rama-clj at
`1b2e0430539962f1b8798b157e3491ccb8b821fe`; this checkout changes its test
tick selection to honor the shared `REPLACE-TICK-DEPOTS` setting. The matching public post was read:
[Fault-tolerant timed notifications in 25 LOC](https://blog.redplanetlabs.com/2025/04/16/next-level-backends-with-rama-fault-tolerant-timed-notifications-in-25-loc/).

`timed_notifications/module.clj` is an integration adapter for the
upstream namespace and source depots/PStates; it does not redesign the
topology. The private tests use `TopologyUtils` simulated time; manual ticks
use the public shared test-depot
selection setting. No behavior under restart/replay was tested, and the tests
do not measure delivery latency or exactly-once behavior.
