(ns hld-ticketing-system.ordering-diagnostic-test
  "Reference-only pause/resume diagnostic. Do not include in solver-facing
   private harness: this test deliberately names the reference topology."
  (:require [clojure.test :refer :all]
            [com.rpl.rama :as rama]
            [com.rpl.rama.test :as rtest]
            [rama-challenges.harness :as harness]
            [hld-ticketing-system.protocol :as p]
            [hld-ticketing-system.module :as reference]))

(defn controlled-batch [tasks mode]
  (let [{:keys [module wrap-client]} (reference/create-module)]
    (with-open [ipc (rtest/create-ipc)]
      (rtest/launch-module! ipc module {:tasks tasks :threads 2})
      (let [a (wrap-client ipc)
            b (wrap-client ipc)
            name (rama/get-module-name module)
            pause! #(rtest/pause-microbatch-topology! ipc name "core")
            resume! #(rtest/resume-microbatch-topology! ipc name "core")]
        (when (= mode :all) (pause!))
        (p/create-event! a "create" "e")
        (p/add-seats! a "add" "e" ["a" "b" "c"])
        (when (= mode :split) (harness/wait-for-processing! b) (pause!))
        (p/hold-seats! a "h1" "e" "u" ["a" "b"] 7)
        (p/hold-seats! a "h2" "e" "v" ["c" "b"] 11)
        (when (= mode :split)
          (resume!) (harness/wait-for-processing! b) (pause!))
        (p/advance-clock! a "tick" "e" 7)
        (p/hold-seats! a "h3" "e" "v" ["a" "b"] 12)
        (resume!)
        (harness/wait-for-processing! b)
        {:h1 (p/get-outcome b "e" "h1")
         :h2 (p/get-outcome b "e" "h2")
         :h3 (p/get-outcome b "e" "h3")
         :clock (p/get-clock b "e")
         :seats (p/get-seats b "e" ["a" "b" "c"])}))))

(deftest controlled-ordering
  (doseq [tasks [2 4], mode [:split :all]]
    (let [result (controlled-batch tasks mode)]
      (println "TICKETING_RESULT" tasks mode result)
      (is (= {:status :accepted :command :hold-seats :conflicting-attempts 0
              :hold-id "h1" :deadline 7} (:h1 result)))
      (is (= {:status :rejected :command :hold-seats :reason :seat-unavailable
              :conflicting-attempts 0 :unavailable-seats ["b"]} (:h2 result)))
      (is (= {:status :accepted :command :hold-seats :conflicting-attempts 0
              :hold-id "h3" :deadline 12} (:h3 result)))
      (is (= 7 (:clock result))))))
