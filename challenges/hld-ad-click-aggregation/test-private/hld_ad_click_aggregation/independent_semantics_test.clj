(ns hld-ad-click-aggregation.independent-semantics-test
  "Independent adversarial checks for campaign-scoped identity and irreversible
   first-arrival accounting. Expectations come from README, not the reference."
  (:require
   [clojure.test :refer [deftest is testing]]
   [hld-ad-click-aggregation.functional-test-support :as s]))

(defn exercise [tasks]
  (s/launch-with
   (requiring-resolve 'hld-ad-click-aggregation.module/create-module) tasks
   (fn [ipc wrap-client]
     (let [a (wrap-client ipc)
           b (wrap-client ipc)
           billed (s/one-click-window 0 "CA" "phone" (s/counters 1 1 0 0 17))]
       (testing (str "same request ID, divergent campaign watermarks (" tasks " tasks)")
         ;; A's window 0 is closed at 180; B's remains open at 179.
         ;; The ID is identical, but its first arrival is independent in B.
         (s/adv* a "independent-a" 180)
         (s/adv* a "independent-b" 179)
         (s/click* a "independent-a" "shared" 59 "US" "web" 101 true false)
         (s/click* a "independent-b" "shared" 0 "CA" "phone" 17 true false)
         (s/sync! a)
         (is (= (s/audit "shared" 59 0 "US" "web" 101 true false :late 180)
                (s/req* b "independent-a" "shared")))
         (is (= (s/audit "shared" 0 0 "CA" "phone" 17 true false :billed 179)
                (s/req* b "independent-b" "shared")))
         (is (nil? (s/win* b "independent-a" 0)))
         (is (= billed (s/win* b "independent-b" 0)))

         ;; Advance B across its exact closure, then retry both IDs with
         ;; conflicting future event times and flags. A's late record must
         ;; never become billable; B's earlier bill must never be reversed or
         ;; doubled. A new ID into B's closed window must be late.
         (s/adv* b "independent-b" 180)
         (s/click* b "independent-a" "shared" 600 "DE" "tablet" 1000 true false)
         (s/click* b "independent-b" "shared" 59 "US" "web" 999 false true)
         (s/click* b "independent-b" "new" 1 "CA" "phone" 31 true false)
         (s/sync! b)
         (is (= (s/audit "shared" 59 0 "US" "web" 101 true false :late 180)
                (s/req* a "independent-a" "shared")))
         (is (= (s/audit "shared" 0 0 "CA" "phone" 17 true false :billed 179)
                (s/req* a "independent-b" "shared")))
         (is (= (s/audit "new" 1 0 "CA" "phone" 31 true false :late 180)
                (s/req* a "independent-b" "new")))
         (is (= 180 (s/wm* a "independent-b")))
         (is (nil? (s/win* a "independent-a" 600)))
         (is (= [] (s/wins* a "independent-a" 0 660)))
         (is (= billed (s/win* a "independent-b" 0)))
         (is (= [billed] (s/wins* a "independent-b" 0 60))))))))

(deftest independent-semantics-2-tasks (exercise 2))
(deftest independent-semantics-4-tasks (exercise 4))
