# Claims and leases validation

Scenario trace: claim `c1` on absent `e` writes only `:claims` denial with clock 0; submit `e` writes only `:state`, then replay `c1` reads the existing decision and performs no update. Fresh `c2` grants token 1. At C=10, `c3` grants token 2; old decision `c2` remains token 1. Dependency and success checks precede lease checks. Claim histories are subindexed, never selected wholesale. All events addressed to `e` are consumed in one topology on its hash partition. No work grows with other executions or workers. Verdict: PHASE_VALIDATION:pass.
