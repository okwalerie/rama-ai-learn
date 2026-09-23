(ns hld-stock-exchange.private-test
  (:require [clojure.test :refer :all]
            [com.rpl.rama.test :as rtest]
            [hld-stock-exchange.protocol :as p]
            [hld-stock-exchange.module :as module]
            [rama-challenges.harness :as harness]))

(defn run-book [tasks f]
  (let [{:keys [module wrap-client]} (module/create-module)]
    (with-open [ipc (rtest/create-ipc)]
      (rtest/launch-module! ipc module {:tasks tasks :threads tasks})
      (f (wrap-client ipc) (wrap-client ipc) ipc module))))

(defn submit [c id sym account side price qty tif]
  (is (nil? (p/submit-limit-order! c id sym account side price qty tif))))
(defn cancel [c id sym oid account]
  (is (nil? (p/cancel-order! c id sym oid account))))
(defn wait! [c] (harness/wait-for-processing! c))

(defn rocks-work [f]
  (let [counts (atom {:rocks-read 0 :rocks-iterator 0 :rocks-iterator-read 0
                      :rocks-writes 0})]
    (rtest/with-event-hook
      (fn [kind data]
        (if (= kind :rocks-commit)
          (swap! counts update :rocks-writes + (:write-batch-count data))
          (when (contains? @counts kind) (swap! counts update kind inc))))
      (f)
      @counts)))

(defn stable-work! [label small large]
  ;; Compare the same operation and output size after growing only history.
  ;; The additive margin accommodates fixed per-batch housekeeping; it is not
  ;; a ceiling on any implementation's constant work.
  (println "WORK" label small "->" large)
  (doseq [metric [:rocks-read :rocks-iterator :rocks-iterator-read :rocks-writes]]
    (is (<= (metric large) (+ (* 2 (metric small)) 12))
        (str label " " metric " grew with unrelated history: " small " -> " large))))

(deftest matching-and-retries
  (doseq [tasks [2 4]]
    (run-book tasks
      (fn [a b _ _]
        (cancel a "pre" "X" "missing" "alice")
        (submit a "a1" "X" "alice" :sell 100 5 :gtc)
        (submit a "a2" "X" "bob" :sell 100 3 :gtc)
        (submit a "high" "X" "carol" :sell 101 4 :gtc)
        (submit a "buy" "X" "dave" :buy 100 6 :ioc)
        (wait! a)
        (is (= {:status :rejected :command :cancel-order :reason :no-such-order
                :conflicting-attempts 0} (p/get-outcome b "X" "pre")))
        (is (= [100 100] (mapv :price (p/get-trades b "X" 0 10))))
        (is (= [[1 "a1" 5] [2 "a2" 1]]
               (mapv (juxt :seq :maker-order-id :qty) (p/get-trades b "X" 0 10))))
        (is (= [{:price 100 :qty 2 :order-count 1}
                {:price 101 :qty 4 :order-count 1}]
               (p/get-depth b "X" :sell 50)))
        (is (= {:order-id "buy" :seq 4 :filled-qty 6 :resting-qty 0
                :cancelled-qty 0 :trade-count 2 :first-trade-seq 1
                :last-trade-seq 2 :status :accepted :command :submit-limit-order
                :conflicting-attempts 0}
               (p/get-outcome b "X" "buy")))
        (submit a "a4" "X" "eve" :sell 100 2 :gtc)
        (submit a "buy" "X" "dave" :buy 100 6 :ioc)
        (submit a "buy" "X" "dave" :buy 101 6 :ioc)
        (cancel a "pre" "X" "missing" "alice")
        (cancel a "pre" "X" "a2" "bob")
        (cancel a "a2" "X" "a2" "bob")
        (submit a "take" "X" "bob" :buy 100 3 :gtc)
        (wait! a)
        (is (= ["a1" "a2" "a2" "a4"]
               (mapv :maker-order-id (p/get-trades b "X" 0 10))))
        (is (= [1 2 3 4] (mapv :seq (p/get-trades b "X" 0 10))))
        (is (= 1 (:conflicting-attempts (p/get-outcome b "X" "buy"))))
        (is (= 1 (:conflicting-attempts (p/get-outcome b "X" "pre"))))
        (is (= 1 (:conflicting-attempts (p/get-outcome b "X" "a2"))))
        (is (= :filled (:state (p/get-order b "X" "a2"))))
        (is (= [{:price 100 :qty 1 :order-count 1}
                {:price 101 :qty 4 :order-count 1}]
               (p/get-depth b "X" :sell 50)))))))

(deftest bid-priority-cancellation-and-ioc
  (doseq [tasks [2 4]]
    (run-book tasks
      (fn [a b _ _]
        (submit a "b99" "B" "alice" :buy 99 7 :gtc)
        (submit a "b101" "B" "bob" :buy 101 5 :gtc)
        (submit a "b100" "B" "alice" :buy 100 3 :gtc)
        (wait! a)
        (is (= [{:price 101 :qty 5 :order-count 1}
                {:price 100 :qty 3 :order-count 1}
                {:price 99 :qty 7 :order-count 1}]
               (p/get-depth b "B" :buy 50)))
        (cancel a "wrong" "B" "b101" "alice")
        (submit a "s" "B" "bob" :sell 100 9 :ioc)
        (wait! a)
        (is (= [[101 5 "b101"] [100 3 "b100"]]
               (mapv (juxt :price :qty :maker-order-id) (p/get-trades b "B" 0 10))))
        (is (= [{:price 99 :qty 7 :order-count 1}] (p/get-depth b "B" :buy 50)))
        (is (= [] (p/get-depth b "B" :sell 50)))
        (is (= {:status :rejected :command :cancel-order :reason :not-owner
                :conflicting-attempts 0} (p/get-outcome b "B" "wrong")))
        (is (= :cancelled (:state (p/get-order b "B" "s"))))
        (is (= 1 (:cancelled-qty (p/get-order b "B" "s"))))
        (cancel b "after" "B" "b101" "bob")
        (wait! b)
        (is (= :order-not-open (:reason (p/get-outcome a "B" "after"))))
        (cancel a "take99" "B" "b99" "alice")
        (wait! a)
        (is (= [] (p/get-depth b "B" :buy 50)))
        (is (= 7 (:cancelled-qty (p/get-outcome b "B" "take99"))))))))

(deftest validation-replay-and-update
  (doseq [tasks [2 4]]
    (run-book tasks
      (fn [a b ipc m]
        (submit a "edge" "E" "same" :buy 1 1000000000 :gtc)
        (wait! a)
        (is (thrown? IllegalArgumentException
                     (p/submit-limit-order! a "edge" "E" "same" :buy 0 1 :gtc)))
        (is (thrown? IllegalArgumentException
                     (p/submit-limit-order! a "unused" "E" "same" :buy 1 0 :gtc)))
        (is (thrown? IllegalArgumentException (p/get-trades a "E" -1 1)))
        (is (thrown? IllegalArgumentException (p/get-depth a "E" :buy 51)))
        (is (= 0 (:conflicting-attempts (p/get-outcome b "E" "edge"))))
        (is (nil? (p/get-outcome b "E" "unused")))
        (submit b "unused" "E" "same" :sell 1 2 :gtc)
        (wait! b)
        (is (= [{:seq 1 :price 1 :qty 2 :maker-order-id "edge"
                 :taker-order-id "unused" :maker-account-id "same"
                 :taker-account-id "same" :taker-side :sell}]
               (p/get-trades a "E" 0 1)))
        (rtest/update-module! ipc m)
        (is (= 2 (:seq (p/get-order a "E" "unused"))))
        (is (= 1 (:seq (first (p/get-trades b "E" 0 10)))))
        (submit b "next" "E" "same" :sell 1 1 :gtc)
        (wait! b)
        (is (= 3 (:seq (p/get-order a "E" "next"))))
        (is (= [1 2] (mapv :seq (p/get-trades a "E" 0 10))))
        (is (= [2] (mapv :seq (p/get-trades a "E" 1 1))))
        (is (= [] (p/get-trades a "E" Long/MAX_VALUE 1)))))))

(deftest bounded-work-and-deep-page
  (doseq [tasks [2 4]]
    (run-book tasks
      (fn [a b _ _]
        ;; Same symbols, fixed touched and returned sizes; history alone grows 4x.
        (let [snapshots
              (for [[start end] [[0 320] [320 1280]]]
                (do
                  (doseq [i (range start end)]
                    (submit a (str "m" i) "H" "maker" :sell (+ 1000 i) 1 :gtc)
                    (submit a (str "q" i) "Q" "maker" :sell 100 1 :gtc))
                  (wait! a)
                  (let [depth (rocks-work #(is (= [{:price 1000 :qty 1 :order-count 1}
                                                    {:price 1001 :qty 1 :order-count 1}
                                                    {:price 1002 :qty 1 :order-count 1}]
                                                   (p/get-depth b "H" :sell 3))))
                        queue-depth (rocks-work #(is (= [{:price 100 :qty end :order-count end}]
                                                         (p/get-depth b "Q" :sell 1))))
                        order (rocks-work #(is (= 1 (:remaining-qty (p/get-order b "H" "m0")))))
                        outcome (rocks-work #(is (= 1 (:seq (p/get-outcome b "H" "m0")))))
                        noncross (rocks-work #(do (submit a (str "noncross" end) "H" "buyer" :buy 1 1 :gtc)
                                                  (wait! a)))
                        cancellation (rocks-work #(do (cancel a (str "remove" end) "H"
                                                             (str "m" (dec end)) "maker")
                                                     (wait! a)))
                        matching (do
                                   (doseq [j (range 3)]
                                     (submit a (str "fixed" end "-" j) "H" "maker"
                                             :sell (+ 10 j) 1 :gtc))
                                   (wait! a)
                                   (rocks-work #(do (submit a (str "take" end) "H" "taker"
                                                              :buy 12 3 :ioc)
                                                    (wait! a))))]
                    (is (= 1 (:cancelled-qty (p/get-outcome b "H" (str "remove" end)))))
                    (is (= [10 11 12]
                           (mapv :price (p/get-trades b "H" (if (= end 320) 0 3) 3))))
                    {:depth depth :queue-depth queue-depth :order order
                     :outcome outcome :noncross noncross
                     :cancellation cancellation :matching matching})))
              [small large] snapshots]
          (doseq [operation (keys small)]
            (stable-work! operation (operation small) (operation large)))
          ;; Grow a separate symbol's tape from hundreds to thousands; fixed
          ;; five-row pages near the end of each stage must not scan history.
          (let [pages
                (for [[start end cursor] [[0 320 300] [320 1280 1260]]]
                  (do
                    (doseq [i (range start end)]
                      (submit a (str "t" i) "T" "maker" :sell (+ 1000 i) 1 :gtc))
                    (wait! a)
                    (submit a (str "sweep" end) "T" "taker" :buy 2279 (- end start) :ioc)
                    (wait! a)
                    (let [page (p/get-trades b "T" cursor 5)]
                      (is (= (- end start) (:trade-count (p/get-outcome b "T" (str "sweep" end)))))
                      (is (= (vec (range (inc cursor) (+ cursor 6))) (mapv :seq page)))
                      (is (= (vec (range (+ 1000 cursor) (+ 1005 cursor)))
                             (mapv :price page)))
                      (rocks-work #(is (= (mapv :seq page)
                                           (mapv :seq (p/get-trades b "T" cursor 5))))))))]
            (stable-work! :deep-page (first pages) (second pages))))))))

(deftest cancellation-with-surviving-level-and-recreation
  (doseq [tasks [2 4]
          side [:buy :sell]]
    (run-book tasks
      (fn [a b _ _]
        (let [opposite-side (if (= side :buy) :sell :buy)]
          (doseq [id ["A" "B" "C"]]
            (submit a id "C" id side 100 5 :gtc))
          (submit a "partial" "C" "taker" opposite-side 100 2 :ioc)
          (cancel a "cancel-B" "C" "B" "B")
          (cancel a "cancel-A" "C" "A" "A")
          (wait! b)
          (is (= [{:price 100 :qty 5 :order-count 1}]
                 (p/get-depth b "C" side 1)))
          (is (= {:filled-qty 2 :remaining-qty 0 :cancelled-qty 3
                  :state :cancelled}
                 (select-keys (p/get-order b "C" "A")
                              [:filled-qty :remaining-qty :cancelled-qty :state])))
          (is (= 5 (:cancelled-qty (p/get-outcome b "C" "cancel-B"))))
          (submit b "consume" "C" "taker" opposite-side 100 5 :ioc)
          (wait! a)
          (is (= ["A" "C"] (mapv :maker-order-id (p/get-trades a "C" 0 10))))
          (is (= [] (p/get-depth a "C" side 1)))
          (submit a "D" "C" "D" side 100 4 :gtc)
          (wait! b)
          (is (= [{:price 100 :qty 4 :order-count 1}]
                 (p/get-depth b "C" side 1))))))))

(deftest symbol-scope-and-second-writer
  (doseq [tasks [2 4]]
    (run-book tasks
      (fn [a b _ _]
        (submit a "same" "one" "a" :buy 10 2 :gtc)
        (submit b "same" "two" "b" :sell 20 3 :gtc)
        (cancel a "cancel" "one" "same" "b")
        (wait! b)
        (is (= 1 (:seq (p/get-order b "one" "same"))))
        (is (= 1 (:seq (p/get-order a "two" "same"))))
        (is (= :not-owner (:reason (p/get-outcome b "one" "cancel"))))
        (is (nil? (p/get-outcome b "two" "cancel")))
        (is (= [{:price 10 :qty 2 :order-count 1}] (p/get-depth b "one" :buy 1)))
        (is (= [{:price 20 :qty 3 :order-count 1}] (p/get-depth a "two" :sell 1)))))))

(deftest same-symbol-mixed-command-batches
  (doseq [tasks [2 4]]
    (run-book tasks
      (fn [a b _ _]
        ;; 1040 unbarriered commands exceed the reference's 1000-record
        ;; microbatch cap. The contract only requires per-client order, not a
        ;; particular topology or how many records one batch contains.
        (doseq [i (range 520)]
          (submit a (str "order-" i) "M" "owner" :sell 100 2 :gtc)
          (cancel a (str "cancel-" i) "M" (str "order-" i) "owner"))
        (submit a "last" "M" "owner" :sell 101 3 :gtc)
        (submit a "take" "M" "buyer" :buy 101 2 :ioc)
        (wait! a)
        (is (= 520 (:seq (p/get-order b "M" "order-519"))))
        (is (= 2 (:cancelled-qty (p/get-outcome b "M" "cancel-519"))))
        (is (= 521 (:seq (p/get-order b "M" "last"))))
        (is (= 522 (:seq (p/get-order b "M" "take"))))
        (is (= [101 2 "last"]
               ((juxt :price :qty :maker-order-id) (first (p/get-trades b "M" 0 2)))))
        (is (= [{:price 101 :qty 1 :order-count 1}]
               (p/get-depth b "M" :sell 2)))))))
