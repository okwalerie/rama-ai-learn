# Private test evidence

From `challenges/family-tree`:

- `timeout 180s env JDK_JAVA_OPTIONS='-Xmx1g -XX:ActiveProcessorCount=2' clojure -X:test-private-harness` exited 0: `Ran 1 tests containing 32 assertions. 0 failures, 0 errors.` Both 2- and 4-task IPC cases completed; includes missing IDs, ancestor generation zero, descendant generation zero and terminal zero, shared paths, multiple clients, and retained state across `update-module!`.
- The first bounded run of this suite exited 1 with six failed assertions (32 total), proving the direct source returns `nil` for empty ancestors and `{0 0}` for unknown positive-depth descendants. Assertions and public text were corrected to source behavior, not the source.
- Same `family-tree.challenge-test/family-tree-contract` var, invoked with the command below: mutant delegates to the same upstream module/client but increments descendant depth-2 count by one when present. `MUTATION-COUNTS {:test 1, :pass 26, :fail 6, :error 0}` at both task counts; wrapper exited 0 only after verifying positive failures and zero errors. This is a real mutation against acceptance assertions, not an `is false` sentinel.

```sh
timeout 180s env JDK_JAVA_OPTIONS='-Xmx1g -XX:ActiveProcessorCount=2' clojure -M:test-private-harness -e "$(cat <<'EOF'
(require '[clojure.test :as t] '[family-tree.challenge-test :as suite] '[family-tree.module :as m] '[family-tree.protocol :as p] '[rama-challenges.harness :as h])
(let [original m/create-module
      mutant (fn [] (update (original) :wrap-client
                            (fn [wrap] (fn [ipc] (let [c (wrap ipc)]
                               (reify p/FamilyTree
                                 (add-person! [_ person] (p/add-person! c person))
                                 (ancestors [_ id n] (p/ancestors c id n))
                                 (descendants-count [_ id n]
                                   (let [result (p/descendants-count c id n)]
                                     (if (contains? result 2) (update result 2 inc) result)))
                                 h/Synchronizable
                                 (wait-for-processing! [_] (h/wait-for-processing! c))))))))]
  (binding [t/*report-counters* (ref t/*initial-report-counters*)]
    (with-redefs [m/create-module mutant]
      (t/test-vars [#'suite/family-tree-contract]))
    (let [{:keys [test pass fail error] :as counts} @t/*report-counters*]
      (println "MUTATION-COUNTS" counts)
      (System/exit (if (and (= test 1) (pos? fail) (zero? error)) 0 1)))))
EOF
)"
```

The imported upstream test is not used as challenge acceptance because it tests its own implementation directly and has different assumptions. `clojure -A:test-private-harness -Spath` shows `test-resources` and `test-resources/upstream` but not the candidate path; `clojure -A:test-private -Spath` shows the candidate path but no private resource path. The candidate `:test-private` tests have not been run (there is no candidate implementation here). Rama emitted Kafka offset-index recovery and module-assignment cache logs during IPC teardown, without test errors. No latency or throughput measurement was made.
