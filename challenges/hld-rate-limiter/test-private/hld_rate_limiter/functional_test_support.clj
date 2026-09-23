(ns hld-rate-limiter.functional-test-support
  "Functional private tests for hld-rate-limiter. Every scenario runs at a
   fixed task count (the challenge test runs 2 and 4) through two wrappers
   obtained from the same create-module result. Expected values are
   hand-derived from the protocol bucket model, never computed from the
   module."
  (:require
   [clojure.test :refer [is testing]]
   [com.rpl.rama.test :as rtest]
   [hld-rate-limiter.protocol :as p]
   [rama-challenges.harness :as harness]))

(def C1 {:shadow? false
         :user {:capacity 10 :refill 1}
         :endpoints {"api/a" {:capacity 5 :refill 0}
                     "api/b" {:capacity 8 :refill 2}}})

(def C1-other-content {:shadow? false
                       :user {:capacity 99 :refill 9}
                       :endpoints {"api/a" {:capacity 99 :refill 9}}})

(def C3 {:shadow? true
         :user {:capacity 4 :refill 1}
         :endpoints {"api/b" {:capacity 6 :refill 1}
                     "api/c" {:capacity 2 :refill 0}}})

(def C4 {:shadow? false
         :user {:capacity 3 :refill 0}
         :endpoints {"api/b" {:capacity 3 :refill 0}}})

(def C5 {:shadow? false
         :user {:capacity 2 :refill 0}
         :endpoints {"e" {:capacity 5 :refill 0}}})

(def CS {:shadow? true
         :user {:capacity 4 :refill 1}
         :endpoints {"e" {:capacity 6 :refill 1}}})

(def V1 {:shadow? false
         :user {:capacity 10 :refill 0}
         :endpoints {"a" {:capacity 10 :refill 0}
                     "b" {:capacity 10 :refill 0}}})

(def V2 {:shadow? true
         :user {:capacity 10 :refill 0}
         :endpoints {"b" {:capacity 10 :refill 0}}})

(defn- status
  [tick version ucap uav ecap eav]
  {:tick tick :config-version version
   :user {:capacity ucap :available uav}
   :endpoint {:capacity ecap :available eav}})

(defn- decision
  [allowed would reason tick version remaining]
  {:allowed allowed :would-allow would :reason reason
   :tick tick :config-version version :remaining remaining})

(defn test-module-functional
  [create-module-fn tasks]
  (let [{:keys [module wrap-client]} (create-module-fn)]
    (with-open [ipc (rtest/create-ipc)]
      (rtest/launch-module! ipc module {:tasks tasks :threads 2})
      (let [a (wrap-client ipc)
            b (wrap-client ipc)
            ;; barrier is always taken through the OTHER wrapper
            sync-a! (fn [] (harness/wait-for-processing! b))
            sync-b! (fn [] (harness/wait-for-processing! a))]

        (testing "write methods return nil"
          (is (nil? (p/set-config! a "nilret" 1 C1)))
          (is (nil? (p/check! a "nilret" "r1" "api/a" 1 0)))
          (sync-a!))

        (testing "no config: reads are nil, checks record :no-config with T = now"
          (is (nil? (p/get-config b "u1")))
          (is (nil? (p/get-status b "u1" "api/a" 0)))
          (is (nil? (p/get-decision b "u1" "r0")))
          (p/check! a "u1" "r0" "api/a" 1 7)
          (sync-a!)
          (is (= (decision false false :no-config 7 nil nil)
                 (p/get-decision b "u1" "r0")))
          (is (nil? (p/get-config b "u1")))
          (is (nil? (p/get-status b "u1" "api/a" 7)))
          (is (nil? (p/get-decision b "u2" "r0")) "request-id known for another user only"))

        (testing "first config: full buckets, clock untouched, old decision replays"
          (p/set-config! a "u1" 1 C1)
          (sync-a!)
          (is (= {:version 1 :config C1} (p/get-config b "u1")))
          (is (= (status 0 1 10 10 5 5) (p/get-status b "u1" "api/a" 0)))
          (is (= (status 123 1 10 10 8 8) (p/get-status b "u1" "api/b" 123)))
          (is (nil? (p/get-status b "u1" "api/x" 0)))
          (p/check! b "u1" "r0" "api/a" 1 999)
          (sync-b!)
          (is (= (decision false false :no-config 7 nil nil)
                 (p/get-decision a "u1" "r0")))
          (is (= (status 999 1 10 10 5 5) (p/get-status a "u1" "api/a" 999))))

        (testing "allowed debit sets both buckets and the clock"
          (p/check! a "u1" "r1" "api/a" 5 3)
          (sync-a!)
          (is (= (decision true true nil 3 1 {:user 5 :endpoint 0})
                 (p/get-decision b "u1" "r1")))
          (is (= (status 3 1 10 5 5 0) (p/get-status b "u1" "api/a" 3)))
          (is (= (status 3 1 10 5 5 0) (p/get-status b "u1" "api/a" 1)) "now < clock uses clock")
          (is (= (status 3 1 10 5 8 8) (p/get-status b "u1" "api/b" 3)) "other endpoint untouched")
          (is (= (status 5 1 10 7 8 8) (p/get-status b "u1" "api/b" 5)) "refill projection")
          (is (= (p/get-status b "u1" "api/b" 5) (p/get-status b "u1" "api/b" 5)) "status is pure"))

        (testing "insufficient endpoint tokens: nothing changes"
          (p/check! a "u1" "r2" "api/a" 1 1)
          (sync-a!)
          (is (= (decision false false :insufficient-tokens 3 1 {:user 5 :endpoint 0})
                 (p/get-decision b "u1" "r2")))
          (is (= (status 3 1 10 5 5 0) (p/get-status b "u1" "api/a" 1))))

        (testing "cost above endpoint capacity at a large now: no debit, no clock change"
          (p/check! b "u1" "r3" "api/a" 6 50)
          (sync-b!)
          (is (= (decision false false :cost-exceeds-capacity 50 1 {:user 10 :endpoint 0})
                 (p/get-decision a "u1" "r3")))
          (is (= (status 4 1 10 6 8 8) (p/get-status a "u1" "api/b" 4))
              "clock still 3: T = 4 and one tick of refill"))

        (testing "debit at a smaller now uses the clock"
          (p/check! a "u1" "r4" "api/b" 3 2)
          (sync-a!)
          (is (= (decision true true nil 3 1 {:user 2 :endpoint 5})
                 (p/get-decision b "u1" "r4")))
          (is (= (status 3 1 10 2 8 5) (p/get-status b "u1" "api/b" 3)))
          (is (= (status 3 1 10 2 5 0) (p/get-status b "u1" "api/a" 3))))

        (testing "retry of a recorded request-id with different arguments is a no-op"
          (p/check! b "u1" "r1" "api/b" 100 999)
          (p/check! a "u1" "r1" "api/b" 100 999)
          (sync-a!)
          (sync-b!)
          (is (= (decision true true nil 3 1 {:user 5 :endpoint 0})
                 (p/get-decision a "u1" "r1")))
          (is (= (status 3 1 10 2 8 5) (p/get-status a "u1" "api/b" 3)))
          (is (= (status 4 1 10 3 8 7) (p/get-status a "u1" "api/b" 4)) "clock still 3"))

        (testing "refill overshoot clamps to capacity; refill 0 never refills"
          (is (= (status 1000 1 10 10 8 8) (p/get-status b "u1" "api/b" 1000)))
          (is (= (status 1000 1 10 10 5 0) (p/get-status b "u1" "api/a" 1000))))

        (testing "same tick: second request sees the first debit with zero refill"
          (p/check! a "u1" "r5" "api/b" 8 1000)
          (p/check! a "u1" "r6" "api/b" 2 1000)
          (sync-a!)
          (is (= (decision true true nil 1000 1 {:user 2 :endpoint 0})
                 (p/get-decision b "u1" "r5")))
          (is (= (decision false false :insufficient-tokens 1000 1 {:user 2 :endpoint 0})
                 (p/get-decision b "u1" "r6")))
          (is (= (status 1000 1 10 2 8 0) (p/get-status b "u1" "api/b" 1000))
              "user bucket sufficient but not debited when endpoint denied"))

        (testing "endpoint sufficient, user insufficient: endpoint not debited"
          (p/check! b "u1" "r7" "api/b" 6 1003)
          (sync-b!)
          (is (= (decision false false :insufficient-tokens 1003 1 {:user 5 :endpoint 6})
                 (p/get-decision a "u1" "r7")))
          (is (= (status 1003 1 10 5 8 6) (p/get-status a "u1" "api/b" 1003)))
          (is (= (status 1000 1 10 2 8 0) (p/get-status a "u1" "api/b" 1000)) "clock still 1000"))

        (testing "cost equal to available is allowed with remaining 0; cost + 1 denied"
          (p/check! a "u1" "r8" "api/b" 5 1003)
          (p/check! a "u1" "r9" "api/b" 1 1003)
          (p/check! a "u1" "r10" "api/b" 1 1004)
          (sync-a!)
          (is (= (decision true true nil 1003 1 {:user 0 :endpoint 1})
                 (p/get-decision b "u1" "r8")))
          (is (= (decision false false :insufficient-tokens 1003 1 {:user 0 :endpoint 1})
                 (p/get-decision b "u1" "r9")))
          (is (= (decision true true nil 1004 1 {:user 0 :endpoint 2})
                 (p/get-decision b "u1" "r10")))
          (is (= (status 1004 1 10 0 8 2) (p/get-status b "u1" "api/b" 1004))))

        (testing "equal version with different content is ignored"
          (p/set-config! a "u1" 1 C1-other-content)
          (sync-a!)
          (is (= {:version 1 :config C1} (p/get-config b "u1")))
          (is (= (status 1004 1 10 0 8 2) (p/get-status b "u1" "api/b" 1004))))

        (testing "newer version replaces config, resets buckets, keeps clock and history"
          (p/set-config! a "u1" 3 C3)
          ;; same client: the next check sees the new config without a barrier
          (p/check! a "u1" "rS1" "api/b" 3 2000)
          (sync-a!)
          (is (= {:version 3 :config C3} (p/get-config b "u1")))
          (is (nil? (p/get-status b "u1" "api/a" 2000)) "removed endpoint")
          (is (= (status 2000 3 4 1 6 3) (p/get-status b "u1" "api/b" 2000)))
          (is (= (status 2000 3 4 1 2 2) (p/get-status b "u1" "api/c" 0)) "clock is 2000 now")
          (is (= (decision true true nil 2000 3 {:user 1 :endpoint 3})
                 (p/get-decision b "u1" "rS1")))
          (is (= (decision true true nil 3 1 {:user 5 :endpoint 0})
                 (p/get-decision b "u1" "r1")) "history survives config change")
          (p/set-config! b "u1" 2 C1)
          (sync-b!)
          (is (= {:version 3 :config C3} (p/get-config a "u1")) "lower version ignored"))

        (testing "shadow mode: identical accounting, allowed always true"
          (p/check! a "u1" "rS2" "api/b" 3 2000)
          (p/check! a "u1" "rS3" "api/z" 1 2000)
          (p/check! a "u1" "rS4" "api/c" 3 2000)
          (p/check! a "u1" "rS5" "api/c" 2 1500)
          (sync-a!)
          (is (= (decision true false :insufficient-tokens 2000 3 {:user 1 :endpoint 3})
                 (p/get-decision b "u1" "rS2")))
          (is (= (decision true false :unknown-endpoint 2000 3 nil)
                 (p/get-decision b "u1" "rS3")))
          (is (= (decision true false :cost-exceeds-capacity 2000 3 {:user 1 :endpoint 2})
                 (p/get-decision b "u1" "rS4")))
          (is (= (decision true false :insufficient-tokens 2000 3 {:user 1 :endpoint 2})
                 (p/get-decision b "u1" "rS5")))
          (is (= (status 2000 3 4 1 6 3) (p/get-status b "u1" "api/b" 1999)) "no debit, clock 2000")
          (is (= (status 2000 3 4 1 2 2) (p/get-status b "u1" "api/c" 2000)))
          (p/check! b "nobody" "n1" "api/b" 1 5)
          (sync-b!)
          (is (= (decision false false :no-config 5 nil nil)
                 (p/get-decision a "nobody" "n1")) "no config means no shadow"))

        (testing "back to enforcing; refill 0 drains until a new version"
          (p/set-config! a "u1" 4 C4)
          (p/check! a "u1" "rE1" "api/b" 3 0)
          (p/check! a "u1" "rE2" "api/b" 1 5000)
          (sync-a!)
          (is (= (decision true false :insufficient-tokens 2000 3 {:user 1 :endpoint 3})
                 (p/get-decision b "u1" "rS2")) "earlier shadow decision unchanged")
          (is (= (decision true true nil 2000 4 {:user 0 :endpoint 0})
                 (p/get-decision b "u1" "rE1")))
          (is (= (decision false false :insufficient-tokens 5000 4 {:user 0 :endpoint 0})
                 (p/get-decision b "u1" "rE2")))
          (is (= (status 2000 4 3 0 3 0) (p/get-status b "u1" "api/b" 0)) "clock still 2000")
          (p/set-config! b "u1" 5 C4)
          (sync-b!)
          (is (= (status 2000 5 3 3 3 3) (p/get-status a "u1" "api/b" 0)))
          (is (= {:version 5 :config C4} (p/get-config a "u1"))))

        (testing "user-only over-capacity: user capacity binds even when the endpoint could pay"
          (p/set-config! a "ucap" 1 C5)
          (p/check! a "ucap" "x1" "e" 3 100)
          (sync-a!)
          (is (= (decision false false :cost-exceeds-capacity 100 1 {:user 2 :endpoint 5})
                 (p/get-decision b "ucap" "x1")) "cost above user capacity is not :insufficient-tokens")
          (is (= (status 0 1 2 2 5 5) (p/get-status b "ucap" "e" 0)) "clock 0, buckets full")
          (p/check! b "ucap" "x2" "nope" 1 101)
          (sync-b!)
          (is (= (decision false false :unknown-endpoint 101 1 nil)
                 (p/get-decision a "ucap" "x2")) "enforcing unknown endpoint is allowed=false, remaining nil")
          (is (= (status 0 1 2 2 5 5) (p/get-status a "ucap" "e" 0)) "still clock 0, buckets full"))

        (testing "shadow denials at future ticks: projected remaining, unchanged clock and balances"
          (p/set-config! a "sh" 1 CS)
          (p/check! a "sh" "s0" "e" 3 10)
          (sync-a!)
          (is (= (decision true true nil 10 1 {:user 1 :endpoint 3}) (p/get-decision b "sh" "s0")))
          (is (= (status 10 1 4 1 6 3) (p/get-status b "sh" "e" 0)))
          (p/check! b "sh" "s1" "e" 3 11)
          (sync-b!)
          (is (= (decision true false :insufficient-tokens 11 1 {:user 2 :endpoint 4})
                 (p/get-decision a "sh" "s1")) "remaining is the projected availability at T = 11")
          (is (= (status 10 1 4 1 6 3) (p/get-status a "sh" "e" 0)) "clock 10, stored 1/3 unchanged")
          (p/check! a "sh" "s2" "e" 5 1000)
          (sync-a!)
          (is (= (decision true false :cost-exceeds-capacity 1000 1 {:user 4 :endpoint 6})
                 (p/get-decision b "sh" "s2")) "cost above user capacity; remaining projected full")
          (is (= (status 10 1 4 1 6 3) (p/get-status b "sh" "e" 0)) "clock 10, stored 1/3 unchanged")
          (p/check! b "sh" "s3" "zz" 1 1000)
          (sync-b!)
          (is (= (decision true false :unknown-endpoint 1000 1 nil) (p/get-decision a "sh" "s3")))
          (is (= (status 10 1 4 1 6 3) (p/get-status a "sh" "e" 0)) "clock 10, stored 1/3 unchanged"))

        (testing "changed-body retries across a config version: recorded decisions replay, buckets untouched"
          (p/set-config! a "pay" 1 V1)
          (p/check! a "pay" "paid" "a" 3 10)
          (p/check! a "pay" "paid" "b" 2 100)
          (sync-a!)
          (is (= (decision true true nil 10 1 {:user 7 :endpoint 7}) (p/get-decision b "pay" "paid"))
              "only the first body debits")
          (is (= (status 10 1 10 7 10 7) (p/get-status b "pay" "a" 0)))
          (is (= (status 10 1 10 7 10 10) (p/get-status b "pay" "b" 0)) "second body never debited b")
          (p/check! a "pay" "denied" "a" 8 11)
          (sync-a!)
          (is (= (decision false false :insufficient-tokens 11 1 {:user 7 :endpoint 7})
                 (p/get-decision b "pay" "denied")))
          (is (= (status 10 1 10 7 10 7) (p/get-status b "pay" "a" 0)) "denial did not advance the clock")
          (p/set-config! a "pay" 2 V2)
          (sync-a!)
          (is (= {:version 2 :config V2} (p/get-config b "pay")))
          (is (nil? (p/get-status b "pay" "a" 0)) "endpoint a removed")
          (is (= (status 10 2 10 10 10 10) (p/get-status b "pay" "b" 0)) "reset full, clock kept")
          (p/check! b "pay" "paid" "b" 1 500)
          (p/check! b "pay" "denied" "b" 1 600)
          (sync-b!)
          (is (= (decision true true nil 10 1 {:user 7 :endpoint 7}) (p/get-decision a "pay" "paid"))
              "payable retry under v2 replays the v1 decision")
          (is (= (decision false false :insufficient-tokens 11 1 {:user 7 :endpoint 7})
                 (p/get-decision a "pay" "denied"))
              "payable retry of a denied id replays the denial, even in shadow")
          (is (= (status 10 2 10 10 10 10) (p/get-status a "pay" "b" 0)) "buckets full, clock 10")
          (p/check! b "pay" "fresh" "b" 1 500)
          (sync-b!)
          (is (= (decision true true nil 500 2 {:user 9 :endpoint 9}) (p/get-decision a "pay" "fresh"))
              "a new id debits from full buckets"))

        (testing "public grammar boundaries: max version, max tick, max refill product, max cost, 64-char ids, 16 endpoints"
          ;; ids at the 64-char limit using every class of the grammar
          (let [u64 (str "U_" (apply str (repeat 60 "x")) "-9")
                r64 (str "R-" (apply str (repeat 62 "0")))
                e64 (str "a/b_c.d-" (apply str (repeat 56 "z")))
                eps (into {e64 {:capacity 1000000 :refill 1000000}}
                          (map (fn [i] [(str "svc/v1.0/ep_" i "-x") {:capacity 1000000 :refill 1000000}])
                               (range 15)))
                big {:shadow? false :user {:capacity 1000000 :refill 1000000} :endpoints eps}
                vmax 2147483647
                tmax 999999999999]
            (is (= 64 (count u64) (count r64) (count e64)))
            (is (= 16 (count eps)))
            (p/set-config! a u64 vmax big)
            (sync-a!)
            (is (= {:version vmax :config big} (p/get-config b u64)))
            (is (= (status tmax vmax 1000000 1000000 1000000 1000000) (p/get-status b u64 e64 tmax))
                "full buckets at the maximum tick")
            (p/check! a u64 r64 e64 1000000 tmax)
            (sync-a!)
            (is (= (decision true true nil tmax vmax {:user 0 :endpoint 0}) (p/get-decision b u64 r64))
                "cost equal to the maximum capacity drains both buckets")
            (is (= (status tmax vmax 1000000 0 1000000 0) (p/get-status b u64 e64 0)) "clock at the maximum tick")
            (is (= (status tmax vmax 1000000 0 1000000 1000000) (p/get-status b u64 "svc/v1.0/ep_14-x" 0))
                "the other 15 endpoint buckets are untouched")
            (p/check! b u64 "r2" "svc/v1.0/ep_0-x" 1 0)
            (sync-b!)
            (is (= (decision false false :insufficient-tokens tmax vmax {:user 0 :endpoint 1000000})
                   (p/get-decision a u64 "r2"))
                "no refill within the same maximum tick; user bucket denies")
            (p/set-config! b u64 2147483646 C1)
            (sync-b!)
            (is (= {:version vmax :config big} (p/get-config a u64)) "version below the maximum is ignored")
            (is (= (status tmax vmax 1000000 0 1000000 0) (p/get-status a u64 e64 0)) "buckets not reset"))
          ;; largest refill product: (T - at) * refill with T = 10^12 - 1 and refill = 10^6
          (p/set-config! a "ovf" 1 {:shadow? false :user {:capacity 1000000 :refill 1000000}
                                    :endpoints {"e" {:capacity 1000000 :refill 1000000}}})
          (p/check! a "ovf" "d1" "e" 1000000 0)
          (sync-a!)
          (is (= (decision true true nil 0 1 {:user 0 :endpoint 0}) (p/get-decision b "ovf" "d1")))
          (is (= (status 0 1 1000000 0 1000000 0) (p/get-status b "ovf" "e" 0)))
          (is (= (status 1 1 1000000 1000000 1000000 1000000) (p/get-status b "ovf" "e" 1))
              "one tick of maximum refill clamps to capacity")
          (is (= (status 999999999999 1 1000000 1000000 1000000 1000000) (p/get-status b "ovf" "e" 999999999999))
              "elapsed 10^12 - 1 times refill 10^6 clamps exactly to capacity")
          (p/check! b "ovf" "d2" "e" 1000000 999999999999)
          (sync-b!)
          (is (= (decision true true nil 999999999999 1 {:user 0 :endpoint 0}) (p/get-decision a "ovf" "d2")))
          (is (= (status 999999999999 1 1000000 0 1000000 0) (p/get-status a "ovf" "e" 5)))
          ;; separator-bearing ids must not collide across users or endpoints
          (p/check! a "a-b" "c" "e" 1 5)
          (p/check! a "a" "b-c" "e" 1 6)
          (p/check! a "a_b" "c" "e" 1 8)
          (sync-a!)
          (is (= (decision false false :no-config 5 nil nil) (p/get-decision b "a-b" "c")))
          (is (= (decision false false :no-config 6 nil nil) (p/get-decision b "a" "b-c")))
          (is (= (decision false false :no-config 8 nil nil) (p/get-decision b "a_b" "c")))
          (is (nil? (p/get-decision b "a" "c")))
          (is (nil? (p/get-decision b "a-b" "b-c")))
          (p/set-config! b "sep" 1 {:shadow? false :user {:capacity 10 :refill 0}
                                    :endpoints {"x/y" {:capacity 5 :refill 0}
                                                "x" {:capacity 5 :refill 0}
                                                "y" {:capacity 5 :refill 0}}})
          (p/check! b "sep" "s1" "x/y" 2 0)
          (sync-b!)
          (is (= (decision true true nil 0 1 {:user 8 :endpoint 3}) (p/get-decision a "sep" "s1")))
          (is (= (status 0 1 10 8 5 3) (p/get-status a "sep" "x/y" 0)))
          (is (= (status 0 1 10 8 5 5) (p/get-status a "sep" "x" 0)) "endpoint x untouched by x/y")
          (is (= (status 0 1 10 8 5 5) (p/get-status a "sep" "y" 0)) "endpoint y untouched by x/y")
          (is (nil? (p/get-status a "sep" "x/" 0)))
          (is (nil? (p/get-status a "sep" "/y" 0))))

        (testing "users are independent"
          (p/check! a "u2" "r1" "api/a" 1 42)
          (sync-a!)
          (is (= (decision false false :no-config 42 nil nil) (p/get-decision b "u2" "r1")))
          (is (= (decision true true nil 3 1 {:user 5 :endpoint 0}) (p/get-decision b "u1" "r1")))
          (is (nil? (p/get-config b "u2"))))

        (testing "one client: check, config, check, config, check in invocation order"
          (p/check! b "ord" "o1" "e" 1 10)
          (p/set-config! b "ord" 1 {:shadow? false :user {:capacity 6 :refill 0}
                                    :endpoints {"e" {:capacity 6 :refill 0}}})
          (p/check! b "ord" "o2" "e" 4 10)
          (p/set-config! b "ord" 2 {:shadow? false :user {:capacity 6 :refill 0}
                                    :endpoints {"e" {:capacity 6 :refill 0}}})
          (p/check! b "ord" "o3" "e" 4 10)
          (p/check! b "ord" "o4" "e" 4 10)
          (sync-b!)
          (is (= (decision false false :no-config 10 nil nil) (p/get-decision a "ord" "o1")))
          (is (= (decision true true nil 10 1 {:user 2 :endpoint 2}) (p/get-decision a "ord" "o2")))
          (is (= (decision true true nil 10 2 {:user 2 :endpoint 2}) (p/get-decision a "ord" "o3")))
          (is (= (decision false false :insufficient-tokens 10 2 {:user 2 :endpoint 2})
                 (p/get-decision a "ord" "o4"))))

        (testing "two clients on one user: debits sum exactly, tokens never negative"
          (p/set-config! a "hot" 1 {:shadow? false :user {:capacity 10 :refill 0}
                                    :endpoints {"e" {:capacity 100 :refill 0}}})
          (sync-a!)
          (doseq [i (range 8)]
            (p/check! a "hot" (str "ha-" i) "e" 3 1)
            (p/check! b "hot" (str "hb-" i) "e" 3 1))
          (sync-a!)
          (sync-b!)
          (let [ids (concat (map #(str "ha-" %) (range 8)) (map #(str "hb-" %) (range 8)))
                ds (mapv #(p/get-decision a "hot" %) ids)
                allowed (filter :would-allow ds)
                denied (remove :would-allow ds)]
            (is (= 3 (count allowed)))
            (is (= #{{:user 7 :endpoint 97} {:user 4 :endpoint 94} {:user 1 :endpoint 91}}
                   (set (map :remaining allowed))))
            (is (every? #(= (decision true true nil 1 1 (:remaining %)) %) allowed))
            (is (= 13 (count denied)))
            (is (every? #(= (decision false false :insufficient-tokens 1 1 {:user 1 :endpoint 91}) %)
                        denied))
            (is (= (status 1 1 10 1 100 91) (p/get-status b "hot" "e" 1)))))))))
