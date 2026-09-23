(ns hld-ad-click-aggregation.functional-test-support
  "Functional private tests for hld-ad-click-aggregation.

   Authority: README.md and the AdClickAggregation protocol docstrings. Every
   expected value below is derived by hand from the window, lateness,
   disposition, replay, and counter rules — never from the reference
   implementation.

   The module is deployed explicitly with the task count passed in (the
   challenge test runs the suite once with 2 tasks and once with 4). Two
   wrappers are created through the same `:wrap-client`; the suite alternates
   synchronized phases between them, and both write, so business state must
   live in the cluster and `wait-for-processing!` must be a real barrier for
   each wrapper's own writes."
  (:require
   [clojure.test :refer [is testing]]
   [com.rpl.rama.test :as rtest]
   [rama-challenges.harness :as harness]
   [hld-ad-click-aggregation.protocol :as p]))

;;; ---------------------------------------------------------------------------
;;; Launch and helpers

(defn launch-with
  "Deploys the module with exactly `tasks` tasks and calls
   (f ipc module wrap-client). Closes the cluster afterwards."
  [create-module-fn tasks f]
  (let [{:keys [module wrap-client]} (create-module-fn)]
    (is (fn? wrap-client) "create-module must return a :wrap-client fn")
    (with-open [ipc (rtest/create-ipc)]
      (rtest/launch-module! ipc module {:tasks tasks :threads 2})
      (f ipc module wrap-client))))

(defn click* [c cid rid ts geo device spend valid? fraud?]
  (p/record-click! c cid rid ts geo device spend valid? fraud?))
(defn adv* [c cid w] (p/advance-watermark! c cid w))
(defn wm* [c cid] (p/get-watermark c cid))
(defn req* [c cid rid] (p/get-request c cid rid))
(defn win* [c cid ws] (p/get-window c cid ws))
(defn wins* [c cid start end]
  (assert (and (zero? (mod start 60)) (zero? (mod end 60)) (<= start end)))
  (p/get-windows c cid start end))
(defn sync! [c] (harness/wait-for-processing! c))

(defn counters [clicks billed invalid fraud spend]
  {:clicks clicks :billed-clicks billed :invalid-clicks invalid
   :fraud-clicks fraud :billed-spend spend})

(defn audit [rid ts ws geo device spend valid? fraud? disp wm]
  {:request-id rid :timestamp ts :window-start ws :geo geo :device device
   :spend spend :valid? valid? :fraud? fraud? :disposition disp :watermark wm})

(defn window [ws totals breakdown]
  {:window-start ws :totals totals :breakdown breakdown})

(defn one-click-window
  "Window holding exactly one counted click."
  [ws geo device totals]
  (window ws totals {[geo device] totals}))

(def MAX Long/MAX_VALUE)
(def TOP-ALIGNED (- MAX 7)) ; largest multiple of 60 representable as a long

(defn spec-disposition
  "The README's ordered rules, evaluated in the test (not by the module)."
  [wm ws valid? fraud?]
  (cond (>= wm (+ ws 180)) :late
        fraud?             :fraud
        (not valid?)       :invalid
        :else              :billed))

(defn expected-windows
  "Spec-derived windows for a list of first-arrival clicks
   [ts geo device spend valid? fraud?] all applied at watermark `wm`.
   Returns {window-start window-map}."
  [wm clicks]
  (reduce
   (fn [acc [ts geo device spend valid? fraud?]]
     (let [ws (- ts (mod ts 60))
           disp (spec-disposition wm ws valid? fraud?)]
       (if (= :late disp)
         acc
         (let [d (counters 1
                           (if (= :billed disp) 1 0)
                           (if (= :invalid disp) 1 0)
                           (if (= :fraud disp) 1 0)
                           (if (= :billed disp) spend 0))
               add (fn [m] (merge-with + (or m (counters 0 0 0 0 0)) d))]
           (-> acc
               (update-in [ws :window-start] (fnil identity ws))
               (update-in [ws :totals] add)
               (update-in [ws :breakdown [geo device]] add))))))
   {}
   clicks))

(defn sum-breakdown [w]
  (reduce (fn [a b] (merge-with + a b)) (counters 0 0 0 0 0) (vals (:breakdown w))))

(defn consistent-window?
  [w]
  (and (= (:totals w) (sum-breakdown w))
       (= (:clicks (:totals w))
          (+ (:billed-clicks (:totals w)) (:invalid-clicks (:totals w))
             (:fraud-clicks (:totals w))))
       (every? (fn [[_ cnt]] (>= (:clicks cnt) 1)) (:breakdown w))))

;;; ---------------------------------------------------------------------------
;;; The suite

(defn test-module-functional
  [create-module-fn tasks]
  (launch-with
   create-module-fn tasks
   (fn [ipc module wrap-client]
     (let [ca (wrap-client ipc)
           cb (wrap-client ipc)]
       (is (satisfies? harness/Synchronizable ca)
           "wrap-client must reify rama-challenges.harness/Synchronizable")
       (is (satisfies? p/AdClickAggregation ca))
       ;; wait with no prior writes returns
       (sync! cb)

       (testing (str "README worked example, verbatim (" tasks " tasks)")
         (let [w60-5 (window 60 (counters 3 1 1 1 30)
                             {["US" "mobile"]  (counters 1 1 0 0 30)
                              ["US" "desktop"] (counters 1 0 1 0 0)
                              ["DE" "mobile"]  (counters 1 0 0 1 0)})
               w4980 (one-click-window 4980 "US" "mobile" (counters 1 1 0 0 10))
               w60-7 (window 60 (counters 4 2 1 1 55)
                             {["US" "mobile"]  (counters 2 2 0 0 55)
                              ["US" "desktop"] (counters 1 0 1 0 0)
                              ["DE" "mobile"]  (counters 1 0 0 1 0)})]
           ;; steps 1-4 through A, step 5 read through B
           (click* ca "cmp-1" "r1" 100 "US" "mobile" 30 true false)
           (click* ca "cmp-1" "r2" 110 "US" "desktop" 20 false false)
           (click* ca "cmp-1" "r3" 119 "DE" "mobile" 50 true true)
           (click* ca "cmp-1" "r4" 5000 "US" "mobile" 10 true false)
           (sync! ca)
           (is (= w60-5 (win* cb "cmp-1" 60)))
           (is (= w4980 (win* cb "cmp-1" 4980)) "future timestamps are admitted")
           (is (= (audit "r1" 100 60 "US" "mobile" 30 true false :billed 0) (req* cb "cmp-1" "r1")))
           (is (= (audit "r2" 110 60 "US" "desktop" 20 false false :invalid 0) (req* cb "cmp-1" "r2")))
           (is (= (audit "r3" 119 60 "DE" "mobile" 50 true true :fraud 0) (req* cb "cmp-1" "r3")))
           (is (= (audit "r4" 5000 4980 "US" "mobile" 10 true false :billed 0) (req* cb "cmp-1" "r4")))
           (is (= 0 (wm* cb "cmp-1")) "clicks never move the watermark")
           ;; steps 6-7 through B, read through A
           (adv* cb "cmp-1" 239)
           (click* cb "cmp-1" "r5" 61 "US" "mobile" 25 true false)
           (sync! cb)
           (is (= 239 (wm* ca "cmp-1")))
           (is (= w60-7 (win* ca "cmp-1" 60)) "239 < 240: window 60 still open")
           (is (= (audit "r5" 61 60 "US" "mobile" 25 true false :billed 239) (req* ca "cmp-1" "r5")))
           ;; steps 8-10 in one synchronized phase through A, read through B
           (adv* ca "cmp-1" 240)
           (click* ca "cmp-1" "r6" 90 "US" "mobile" 40 true false)
           (click* ca "cmp-1" "r1" 100 "US" "mobile" 30 true false)
           (sync! ca)
           (is (= (audit "r6" 90 60 "US" "mobile" 40 true false :late 240) (req* cb "cmp-1" "r6")))
           (is (= w60-7 (win* cb "cmp-1" 60)) "late click and replay leave window 60 unchanged")
           (is (= (audit "r1" 100 60 "US" "mobile" 30 true false :billed 0) (req* cb "cmp-1" "r1"))
               "replay after closure keeps the first disposition and watermark")
           ;; step 11
           (is (= [w60-7 w4980] (wins* cb "cmp-1" 0 6000)))
           (is (= [w60-7] (wins* cb "cmp-1" 0 120)))
           (is (nil? (win* cb "cmp-1" 120)))
           ;; step 12
           (adv* cb "cmp-1" 100)
           (sync! cb)
           (is (= 240 (wm* ca "cmp-1")) "stale advance is a no-op")
           ;; step 13
           (click* cb "cmp-2" "r1" 100 "US" "mobile" 30 true false)
           (sync! cb)
           (is (= (audit "r1" 100 60 "US" "mobile" 30 true false :billed 0) (req* ca "cmp-2" "r1")))
           (is (= (one-click-window 60 "US" "mobile" (counters 1 1 0 0 30)) (win* ca "cmp-2" 60)))
           (is (= w60-7 (win* ca "cmp-1" 60)) "cmp-2's click does not touch cmp-1")
           (is (= 0 (wm* ca "cmp-2")))))

       (testing "unknown campaign reads"
         (is (= 0 (wm* ca "ghost")))
         (is (nil? (req* ca "ghost" "r1")))
         (is (nil? (win* ca "ghost" 0)))
         (is (= [] (wins* ca "ghost" 0 6000)))
         (is (= [] (wins* ca "ghost" 0 0)))
         (is (= [] (wins* cb "ghost" 0 TOP-ALIGNED)))
         (is (nil? (req* cb "cmp-1" "never")) "known campaign, unknown request-id"))

       (testing "window membership at 60-unit boundaries"
         (click* ca "wb" "r0" 0 "X" "d" 1 true false)
         (click* ca "wb" "r59" 59 "X" "d" 2 true false)
         (click* ca "wb" "r60" 60 "X" "d" 4 true false)
         (click* ca "wb" "r119" 119 "X" "d" 8 true false)
         (click* ca "wb" "r120" 120 "X" "d" 16 true false)
         (sync! ca)
         (let [w0 (one-click-window 0 "X" "d" (counters 2 2 0 0 3))
               w60 (one-click-window 60 "X" "d" (counters 2 2 0 0 12))
               w120 (one-click-window 120 "X" "d" (counters 1 1 0 0 16))]
           (is (= w0 (win* cb "wb" 0)))
           (is (= w60 (win* cb "wb" 60)))
           (is (= w120 (win* cb "wb" 120)))
           (is (= [w0 w60] (wins* cb "wb" 0 120)) "end is exclusive")
           (is (= [w0 w60 w120] (wins* cb "wb" 0 180)))
           (is (= [w60] (wins* cb "wb" 60 120)) "start is inclusive")
           (is (= [] (wins* cb "wb" 60 60)) "start == end")
           (is (= [] (wins* cb "wb" 180 6000)) "range above every window")
           (is (= 0 (:window-start (req* cb "wb" "r59"))))
           (is (= 60 (:window-start (req* cb "wb" "r60"))))
           (is (= 0 (wm* cb "wb")) "campaign with clicks but no advance")))

       (testing "exact lateness cutoff: closed iff watermark >= window-start + 180"
         (adv* cb "late" 179)
         (click* cb "late" "a" 30 "US" "m" 5 true false)   ; window 0, 179 < 180
         (click* cb "late" "b" 61 "US" "m" 10 true false)  ; window 60
         (sync! cb)
         (is (= (one-click-window 0 "US" "m" (counters 1 1 0 0 5)) (win* ca "late" 0)))
         (adv* ca "late" 180)
         (click* ca "late" "c" 59 "US" "m" 99 true false)  ; window 0 closed exactly at 180
         (click* ca "late" "d" 60 "US" "m" 20 true false)  ; window 60 open until 240
         (sync! ca)
         (is (= (audit "c" 59 0 "US" "m" 99 true false :late 180) (req* cb "late" "c")))
         (is (= (one-click-window 0 "US" "m" (counters 1 1 0 0 5)) (win* cb "late" 0))
             "a late click changes nothing in its window")
         (adv* cb "late" 239)
         (click* cb "late" "e" 119 "US" "m" 30 true false) ; 239 < 240: counted
         (sync! cb)
         (adv* ca "late" 240)
         (click* ca "late" "f" 119 "US" "m" 40 true true)  ; closed: :late beats fraud?
         (click* ca "late" "g" 240 "US" "m" 7 true false)  ; window 240, open
         (sync! ca)
         (is (= (audit "e" 119 60 "US" "m" 30 true false :billed 239) (req* cb "late" "e")))
         (is (= (audit "f" 119 60 "US" "m" 40 true true :late 240) (req* cb "late" "f"))
             "late record still stores the supplied flags")
         (is (= (one-click-window 60 "US" "m" (counters 3 3 0 0 60)) (win* cb "late" 60)))
         ;; a big advance closes many windows at once; a never-touched closed
         ;; window stays absent even after a late click into it
         (adv* cb "late" 600)
         (click* cb "late" "h" 400 "US" "m" 1 true false)  ; window 360: 540 <= 600, closed
         (click* cb "late" "i" 500 "US" "m" 3 true false)  ; window 480: 660 > 600, open
         (sync! cb)
         (is (= 600 (wm* ca "late")))
         (is (= (audit "h" 400 360 "US" "m" 1 true false :late 600) (req* ca "late" "h")))
         (is (nil? (win* ca "late" 360)) "late-only window is never created")
         (is (= (audit "i" 500 480 "US" "m" 3 true false :billed 600) (req* ca "late" "i")))
         (let [w0 (one-click-window 0 "US" "m" (counters 1 1 0 0 5))
               w60 (one-click-window 60 "US" "m" (counters 3 3 0 0 60))
               w240 (one-click-window 240 "US" "m" (counters 1 1 0 0 7))
               w480 (one-click-window 480 "US" "m" (counters 1 1 0 0 3))]
           (is (= [w0 w60 w240 w480] (wins* ca "late" 0 660))
               "closed and open windows, ascending, late-only window absent")
           (is (= [w480] (wins* ca "late" 300 660)))
           (is (= [w0 w60] (wins* ca "late" 0 240)))
           (is (= [w240] (wins* ca "late" 240 300)))
           (is (= [] (wins* ca "late" 660 100020)))
           (is (= [] (wins* ca "late" 360 480)) "range covering only a late-only window")))

       (testing "disposition precedence and spend rules"
         (click* ca "flags" "f1" 600 "US" "web" 100 false true)  ; fraud beats invalid
         (click* ca "flags" "f2" 601 "US" "web" 100 true true)
         (click* ca "flags" "i1" 602 "US" "app" 100 false false)
         (click* ca "flags" "b0" 603 "US" "web" 0 true false)     ; billed with spend 0
         (click* ca "flags" "b1" 604 "US" "web" 7 true false)
         (click* ca "flags" "i2" 1200 "DE" "web" 50 false false)  ; invalid-only window
         (sync! ca)
         (is (= (audit "f1" 600 600 "US" "web" 100 false true :fraud 0) (req* cb "flags" "f1")))
         (is (= :fraud (:disposition (req* cb "flags" "f2"))))
         (is (= :invalid (:disposition (req* cb "flags" "i1"))))
         (is (= :billed (:disposition (req* cb "flags" "b0"))))
         (is (= (window 600 (counters 5 2 1 2 7)
                        {["US" "web"] (counters 4 2 0 2 7)
                         ["US" "app"] (counters 1 0 1 0 0)})
                (win* cb "flags" 600))
             "spend of fraud/invalid clicks is never summed; spend 0 bills a click")
         (is (= (one-click-window 1200 "DE" "web" (counters 1 0 1 0 0)) (win* cb "flags" 1200))
             "window with no billed clicks still exists"))

       (testing "replays with conflicting bodies never change anything"
         (click* ca "replay" "x" 100 "US" "mobile" 30 true false)
         (click* ca "replay" "z" 300 "US" "mobile" 1 true false)
         (click* ca "replay" "z" 300 "US" "mobile" 2 true false)   ; same phase replay
         (sync! ca)
         (click* cb "replay" "x" 5000 "DE" "desktop" 999 false true) ; every field differs
         (click* cb "replay" "x" 100 "US" "mobile" 30 true false)
         (sync! cb)
         (is (= (audit "x" 100 60 "US" "mobile" 30 true false :billed 0) (req* ca "replay" "x")))
         (is (= (one-click-window 60 "US" "mobile" (counters 1 1 0 0 30)) (win* ca "replay" 60)))
         (is (nil? (win* ca "replay" 4980)) "replay's different window is not created")
         (is (= (one-click-window 300 "US" "mobile" (counters 1 1 0 0 1)) (win* ca "replay" 300))
             "second arrival in the same phase is a replay")
         (is (= 1 (:spend (req* ca "replay" "z"))))
         (is (= [(one-click-window 60 "US" "mobile" (counters 1 1 0 0 30))
                 (one-click-window 300 "US" "mobile" (counters 1 1 0 0 1))]
                (wins* ca "replay" 0 6000)))
         ;; replay after closure, and replay of a late record
         (adv* ca "replay" 240)
         (click* ca "replay" "x" 100 "US" "mobile" 30 true false)
         (click* ca "replay" "y" 90 "US" "mobile" 5 true false)     ; late
         (sync! ca)
         (click* cb "replay" "y" 100000 "US" "mobile" 5 true false) ; replay of a late record
         (sync! cb)
         (is (= (audit "x" 100 60 "US" "mobile" 30 true false :billed 0) (req* cb "replay" "x"))
             "audit :watermark stays at the first arrival's value")
         (is (= (audit "y" 90 60 "US" "mobile" 5 true false :late 240) (req* cb "replay" "y")))
         (is (nil? (win* cb "replay" 99960)))
         (is (= (one-click-window 60 "US" "mobile" (counters 1 1 0 0 30)) (win* cb "replay" 60))))

       (testing "audit records are immutable across watermark changes; closed windows stay readable"
         (click* cb "imm" "k" 1000 "FR" "tv" 12 true false)
         (sync! cb)
         (adv* ca "imm" 500)
         (sync! ca)
         (is (= (audit "k" 1000 960 "FR" "tv" 12 true false :billed 0) (req* cb "imm" "k")))
         (adv* cb "imm" 1000000000000)
         (click* cb "imm" "k2" 1000 "FR" "tv" 12 true false)
         (click* cb "imm" "k3" 1000000000000 "FR" "tv" 4 true false)
         (sync! cb)
         (is (= 1000000000000 (wm* ca "imm")))
         (is (= (audit "k" 1000 960 "FR" "tv" 12 true false :billed 0) (req* ca "imm" "k"))
             "stored watermark does not move with the campaign watermark")
         (is (= (audit "k2" 1000 960 "FR" "tv" 12 true false :late 1000000000000) (req* ca "imm" "k2")))
         ;; 10^12 mod 60 = 40 -> window 999999999960, end + 120 = 1000000000140 > W
         (is (= (audit "k3" 1000000000000 999999999960 "FR" "tv" 4 true false :billed 1000000000000)
                (req* ca "imm" "k3")))
         (is (= (one-click-window 960 "FR" "tv" (counters 1 1 0 0 12)) (win* ca "imm" 960))
             "closed window readable with identical contents")
         (is (= [(one-click-window 960 "FR" "tv" (counters 1 1 0 0 12))
                 (one-click-window 999999999960 "FR" "tv" (counters 1 1 0 0 4))]
                (wins* ca "imm" 0 1000000000020)))
         (is (= [(one-click-window 999999999960 "FR" "tv" (counters 1 1 0 0 4))]
                (wins* ca "imm" 999999999960 1000000000020))))

       (testing "campaign isolation"
         (click* ca "iso-a" "same" 100 "US" "m" 1 true false)
         (click* ca "iso-b" "same" 700 "DE" "d" 2 false false)
         (adv* ca "iso-a" 10000)
         (click* ca "iso-a" "l" 0 "US" "m" 1 true false)   ; late in A
         (click* ca "iso-b" "l" 0 "US" "m" 1 true false)   ; B's watermark is 0
         (sync! ca)
         (is (= 10000 (wm* cb "iso-a")))
         (is (= 0 (wm* cb "iso-b")) "advancing A leaves B's watermark alone")
         (is (= (audit "same" 100 60 "US" "m" 1 true false :billed 0) (req* cb "iso-a" "same")))
         (is (= (audit "same" 700 660 "DE" "d" 2 false false :invalid 0) (req* cb "iso-b" "same")))
         (is (= :late (:disposition (req* cb "iso-a" "l"))))
         (is (= :billed (:disposition (req* cb "iso-b" "l"))))
         (is (= [(one-click-window 60 "US" "m" (counters 1 1 0 0 1))] (wins* cb "iso-a" 0 6000)))
         (is (= [(one-click-window 0 "US" "m" (counters 1 1 0 0 1))
                 (one-click-window 660 "DE" "d" (counters 1 0 1 0 0))]
                (wins* cb "iso-b" 0 6000)))
         (is (nil? (win* cb "iso-b" 60)) "A's window is not visible under B"))

       (testing "watermark monotonicity and no-ops"
         (adv* cb "wm" 0)
         (sync! cb)
         (is (= 0 (wm* ca "wm")))
         (is (= [] (wins* ca "wm" 0 6000)))
         (is (nil? (req* ca "wm" "r")))
         (adv* ca "wm" 1000)
         (adv* ca "wm" 1000)
         (adv* ca "wm" 999)
         (sync! ca)
         (is (= 1000 (wm* cb "wm")))
         (adv* cb "wm" 1001)
         (sync! cb)
         (is (= 1001 (wm* ca "wm")))
         (is (= [] (wins* ca "wm" 0 6000)) "advances never create windows")
         (is (nil? (win* ca "wm" 0))))

       (testing "signed 64-bit limits: highest timestamps, watermark, and aligned bounds"
         (let [ts1 (- MAX 180)              ; largest allowed timestamp
               ws1 (- MAX 187)              ; its window start (MAX mod 60 = 7)
               ws1-end (+ ws1 60)]          ; MAX - 127; closes at watermark MAX - 7
           (is (zero? (mod ws1 60)))
           (is (zero? (mod TOP-ALIGNED 60)))
           (click* ca "big" "t1" ts1 "US" "m" 5 true false)
           (adv* ca "big" (- MAX 8))                          ; MAX-8 < MAX-7: open
           (click* ca "big" "t2" (- MAX 181) "US" "m" 6 true false)
           (sync! ca)
           (is (= (audit "t1" ts1 ws1 "US" "m" 5 true false :billed 0) (req* cb "big" "t1")))
           (is (= (audit "t2" (- MAX 181) ws1 "US" "m" 6 true false :billed (- MAX 8))
                  (req* cb "big" "t2")))
           (adv* cb "big" TOP-ALIGNED)                        ; == ws1 + 180: closed
           (click* cb "big" "t3" (- MAX 186) "US" "m" 7 true false)
           (adv* cb "big" MAX)
           (click* cb "big" "t4" ts1 "US" "m" 8 true false)
           (sync! cb)
           (is (= MAX (wm* ca "big")))
           (is (= (audit "t3" (- MAX 186) ws1 "US" "m" 7 true false :late TOP-ALIGNED)
                  (req* ca "big" "t3")))
           (is (= (audit "t4" ts1 ws1 "US" "m" 8 true false :late MAX) (req* ca "big" "t4")))
           (let [w (one-click-window ws1 "US" "m" (counters 2 2 0 0 11))]
             (is (= w (win* ca "big" ws1)))
             (is (nil? (win* ca "big" TOP-ALIGNED)) "highest aligned start must not overflow")
             (is (nil? (win* cb "big" ws1-end)))
             (is (= [w] (wins* ca "big" 0 TOP-ALIGNED)))
             (is (= [w] (wins* cb "big" ws1 ws1-end)))
             (is (= [] (wins* cb "big" ws1-end TOP-ALIGNED)))
             (is (= [] (wins* cb "big" TOP-ALIGNED TOP-ALIGNED))))
           ;; a fresh campaign advanced straight to MAX: every window is closed
           (adv* ca "big2" MAX)
           (click* ca "big2" "z" 0 "US" "m" 1 true false)
           (click* ca "big2" "t" ts1 "US" "m" 1 true false)
           (sync! ca)
           (is (= :late (:disposition (req* cb "big2" "z"))))
           (is (= (audit "t" ts1 ws1 "US" "m" 1 true false :late MAX) (req* cb "big2" "t")))
           (is (= [] (wins* cb "big2" 0 TOP-ALIGNED)))))

       (testing "same-campaign writes take effect in client order inside one phase"
         (adv* cb "ord-1" 240)
         (click* cb "ord-1" "a" 90 "US" "m" 1 true false)   ; late at 240
         (click* cb "ord-1" "b" 300 "US" "m" 2 true false)  ; window 300 open
         (adv* cb "ord-1" 100)                              ; stale
         (click* cb "ord-1" "c" 61 "US" "m" 3 true false)   ; still late at 240
         (adv* cb "ord-1" 480)                              ; closes window 300 exactly
         (click* cb "ord-1" "d" 301 "US" "m" 4 true false)  ; late at 480
         (sync! cb)
         (is (= 480 (wm* ca "ord-1")))
         (is (= (audit "a" 90 60 "US" "m" 1 true false :late 240) (req* ca "ord-1" "a")))
         (is (= (audit "b" 300 300 "US" "m" 2 true false :billed 240) (req* ca "ord-1" "b")))
         (is (= (audit "c" 61 60 "US" "m" 3 true false :late 240) (req* ca "ord-1" "c")))
         (is (= (audit "d" 301 300 "US" "m" 4 true false :late 480) (req* ca "ord-1" "d")))
         (is (= [(one-click-window 300 "US" "m" (counters 1 1 0 0 2))] (wins* ca "ord-1" 0 6000)))
         ;; reverse order on another campaign
         (click* ca "ord-2" "a" 90 "US" "m" 1 true false)
         (adv* ca "ord-2" 240)
         (click* ca "ord-2" "b" 90 "US" "m" 1 true false)
         (sync! ca)
         (is (= (audit "a" 90 60 "US" "m" 1 true false :billed 0) (req* cb "ord-2" "a")))
         (is (= (audit "b" 90 60 "US" "m" 1 true false :late 240) (req* cb "ord-2" "b")))
         (is (= (one-click-window 60 "US" "m" (counters 1 1 0 0 1)) (win* cb "ord-2" 60))))

       (testing "two wrappers writing the same window and pairs: every click counted once"
         (let [mk (fn [prefix]
                    (vec (for [i (range 100)]
                           [(str prefix i) (+ 3600 (mod i 60))
                            (nth ["US" "DE" "FR"] (mod i 3)) "m" i
                            (not= 0 (mod i 7)) (= 0 (mod i 11))])))
               a-clicks (mk "a-")
               b-clicks (mk "b-")
               exp (expected-windows 0 (map rest (concat a-clicks b-clicks)))]
           (doseq [[rid ts geo device spend valid? fraud?] a-clicks]
             (click* ca "hot" rid ts geo device spend valid? fraud?))
           (doseq [[rid ts geo device spend valid? fraud?] b-clicks]
             (click* cb "hot" rid ts geo device spend valid? fraud?))
           (sync! ca)
           (sync! cb)
           (is (= 1 (count exp)))
           (let [w (win* ca "hot" 3600)]
             (is (= (get exp 3600) w))
             (is (= 200 (:clicks (:totals w))))
             (is (consistent-window? w))
             (is (= 3 (count (:breakdown w)))))
           (is (= [(get exp 3600)] (wins* cb "hot" 3600 3660)))
           (is (= :fraud (:disposition (req* cb "hot" "a-0"))) "fraud? beats valid? false")
           (is (= :fraud (:disposition (req* ca "hot" "b-77"))))
           (is (= :invalid (:disposition (req* ca "hot" "a-7"))))
           (is (= :billed (:disposition (req* cb "hot" "b-1"))))))

       (testing "a window with more than 100 distinct [geo device] pairs"
         (let [clicks (vec (for [i (range 130)]
                             [(str "p-" i) (+ 7200 (mod i 60)) (str "g" i) (str "d" (mod i 4)) 1 true false]))
               exp (get (expected-windows 0 (map rest clicks)) 7200)]
           (doseq [[rid ts geo device spend valid? fraud?] clicks]
             (click* cb "wide" rid ts geo device spend valid? fraud?))
           (sync! cb)
           (let [w (win* ca "wide" 7200)]
             (is (= exp w))
             (is (= 130 (count (:breakdown w))))
             (is (= (counters 130 130 0 0 130) (:totals w)))
             (is (consistent-window? w)))
           (is (= [exp] (wins* ca "wide" 0 TOP-ALIGNED)))))

       (testing "a wrapper created after the writes sees the same state; second-wrapper write barrier"
         ;; wrapper A does a large batch and synchronizes
         (adv* ca "bar-a" 1000)
         (doseq [i (range 300)]
           (click* ca "bar-a" (str "a" i) (+ 1000 i) "US" "m" 1 true false))
         (sync! ca)
         (let [exp-a (expected-windows 1000 (for [i (range 300)] [(+ 1000 i) "US" "m" 1 true false]))
               cc (wrap-client ipc)]
           (is (= [960 1020 1080 1140 1200 1260] (sort (keys exp-a))))
           (is (= (mapv exp-a (sort (keys exp-a))) (wins* cc "bar-a" 0 6000))
               "fresh wrapper sees state written before it existed")
           (is (= 300 (reduce + (map (comp :clicks :totals) (wins* cc "bar-a" 0 6000)))))
           ;; the brand-new wrapper must wait for ITS OWN writes even though the
           ;; cluster already processed more records than it will append
           (doseq [i (range 200)]
             (click* cc "bar-b" (str "b" i) (+ 5000 i) "DE" "d" 2 true false))
           (sync! cc)
           (let [exp-b (expected-windows 0 (for [i (range 200)] [(+ 5000 i) "DE" "d" 2 true false]))]
             (is (= [4980 5040 5100 5160] (sort (keys exp-b))))
             (is (= (mapv exp-b (sort (keys exp-b))) (wins* cc "bar-b" 0 6000))
                 "wait-for-processing! on the new wrapper must cover its own writes")
             (is (= (mapv exp-b (sort (keys exp-b))) (wins* ca "bar-b" 0 6000))
                 "and the older wrappers observe them too")
             (is (= (one-click-window 4980 "DE" "d" (counters 40 40 0 0 80)) (win* cb "bar-b" 4980))
                 "4980..5039 holds the 40 clicks 5000..5039 of spend 2")
             ;; new wrapper's advance closes window 960 exactly; A observes
             (adv* cc "bar-a" 1140)
             (click* cc "bar-a" "after" 1000 "US" "m" 1 true false)
             (click* cc "bar-a" "after2" 1020 "US" "m" 1 true false) ; window 1020 open until 1200
             (sync! cc)
             (is (= (audit "after" 1000 960 "US" "m" 1 true false :late 1140) (req* ca "bar-a" "after")))
             (is (= (audit "after2" 1020 1020 "US" "m" 1 true false :billed 1140) (req* cb "bar-a" "after2")))
             (is (= (one-click-window 960 "US" "m" (counters 20 20 0 0 20)) (get exp-a 960)))
             (is (= (get exp-a 960) (win* ca "bar-a" 960)) "closed window unchanged by the late click")
             (is (= (one-click-window 1020 "US" "m" (counters 61 61 0 0 61)) (win* ca "bar-a" 1020))))))

       (testing "durable state survives a simulated worker restart (module update)"
         (adv* ca "rst" 1000)
         (click* ca "rst" "r1" 1000 "US" "m" 3 true false)
         (click* ca "rst" "r2" 700 "US" "m" 4 true false)   ; window 660 closes at 840: late
         (click* ca "rst" "r3" 1010 "DE" "m" 5 false false)
         (sync! ca)
         (rtest/update-module! ipc module)
         (is (= 1000 (wm* cb "rst")))
         (is (= (audit "r1" 1000 960 "US" "m" 3 true false :billed 1000) (req* cb "rst" "r1")))
         (is (= (audit "r2" 700 660 "US" "m" 4 true false :late 1000) (req* cb "rst" "r2")))
         (is (= (window 960 (counters 2 1 1 0 3)
                        {["US" "m"] (counters 1 1 0 0 3) ["DE" "m"] (counters 1 0 1 0 0)})
                (win* cb "rst" 960)))
         (is (nil? (win* cb "rst" 660)))
         (click* cb "rst" "r1" 1000 "US" "m" 300 true false) ; replay survives restart
         (click* cb "rst" "r4" 1011 "US" "m" 6 true false)
         (adv* cb "rst" 1140)                                ; closes 960
         (click* cb "rst" "r5" 1012 "US" "m" 7 true false)   ; late
         (sync! cb)
         (is (= (audit "r1" 1000 960 "US" "m" 3 true false :billed 1000) (req* ca "rst" "r1")))
         (is (= (audit "r5" 1012 960 "US" "m" 7 true false :late 1140) (req* ca "rst" "r5")))
         (is (= (window 960 (counters 3 2 1 0 9)
                        {["US" "m"] (counters 2 2 0 0 9) ["DE" "m"] (counters 1 0 1 0 0)})
                (win* ca "rst" 960)))
         (is (= 1140 (wm* ca "rst"))))))))
