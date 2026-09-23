# Completion and reads validation

Scenario trace: at C=10 lease expiry 10, status is ready and completion ignored; a token-2 grant expires at 20, and a token-1 completion is ignored even for the same worker. Token-2 completion stores result and clears lease. A repeat cannot overwrite it. Dependent nodes transition to ready; all-success execution becomes success. A nil result remains success because success is explicit. The read cost remains bounded by the 32-node DAG, not unbounded claims. Verdict: PHASE_VALIDATION:pass.
