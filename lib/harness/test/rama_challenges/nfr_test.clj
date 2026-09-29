(ns rama-challenges.nfr-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [com.rpl.rama :refer :all]
   [com.rpl.rama.path :refer :all]
   [com.rpl.rama.test :as rtest]
   [rama-challenges.nfr :as nfr]))

;; Stream topology: appends [k v] -> $$counts {k count} and $$items {k {v true}}
;; (subindexed). A deliberately non-idempotent counter makes a forced retry
;; visible as a double count.
(defmodule NfrTestModule [setup topologies]
  (declare-depot setup *events (hash-by first))
  (declare-depot setup *mb-events (hash-by first))
  (let [s (stream-topology topologies "s")
        mb (microbatch-topology topologies "mb")]
    (declare-pstate s $$counts {String Long})
    (declare-pstate s $$items {String (map-schema Long Boolean {:subindex? true})})
    (declare-pstate mb $$mb-counts {String Long})
    (<<sources s
      (source> *events :> [*k *v])
      (local-transform> [(keypath *k) (nil->val 0) (term inc)] $$counts)
      (local-transform> [(keypath *k *v) (termval true)] $$items))
    (<<sources mb
      (source> *mb-events :> %mb)
      (%mb :> [*k *v])
      (local-transform> [(keypath *k) (nil->val 0) (term inc)] $$mb-counts))))

(def module-name (get-module-name NfrTestModule))

(defn- with-ipc [tasks f]
  (with-open [ipc (rtest/create-ipc)]
    (rtest/launch-module! ipc NfrTestModule {:tasks tasks :threads 2})
    (f ipc)))

(deftest helpers-observe-module
  (with-ipc 4
    (fn [ipc]
      (let [events (foreign-depot ipc module-name "*events")
            mb-events (foreign-depot ipc module-name "*mb-events")
            counts (foreign-pstate ipc module-name "$$counts")
            items (foreign-pstate ipc module-name "$$items")
            mb-counts (foreign-pstate ipc module-name "$$mb-counts")]
        (testing "capture-rocks-ops counts writes and reads"
          (let [ops (nfr/capture-rocks-ops #(foreign-append! events ["a" 1]))]
            (is (pos? (:writes ops)))
            (is (zero? (:iterator-reads ops))))
          (let [[v ops] (nfr/capture-rocks-ops-with-result
                         #(foreign-select-one (keypath "a") counts))]
            (is (= 1 v))
            (is (= 1 (:reads ops)))))
        (testing "capture-iterator-reads proves subindexed range reads"
          (dotimes [i 5] (foreign-append! events ["b" i]))
          (is (pos? (nfr/capture-iterator-reads
                     #(foreign-select [(keypath "b") (sorted-map-range 0 3) MAP-KEYS] items))))
          (is (zero? (nfr/capture-iterator-reads
                      #(foreign-select-one (keypath "b") counts)))))
        (testing "topology-types-used reports the stream topology"
          (is (contains? (nfr/topology-types-used #(foreign-append! events ["c" 1])) :stream)))
        (testing "with-forced-stream-retry forces a retry that a non-idempotent write double-applies"
          (let [[_ forced] (nfr/with-forced-stream-retry #(foreign-append! events ["d" 1]))]
            (is (= 1 forced))
            (is (= 2 (foreign-select-one (keypath "d") counts))
                "the retried stream event incremented twice")))
        (testing "with-forced-stream-retry is a no-op for microbatch"
          (let [[_ forced] (nfr/with-forced-stream-retry
                             #(do (foreign-append! mb-events ["e" 1])
                                  (rtest/wait-for-microbatch-processed-count ipc module-name "mb" 1)))]
            (is (zero? forced))
            (is (= 1 (foreign-select-one (keypath "e") mb-counts)))))
        (testing "failed-streaming returns the result of f"
          (is (= :ok (nfr/failed-streaming #(do (foreign-append! events ["f" 1]) :ok)))))
        (testing "per-task ops expose :task-id and gen-task-keys spreads keys"
          (let [ks (nfr/gen-task-keys 4)
                per-task (nfr/capture-per-task-ops
                          #(doseq [k ks] (foreign-append! events [k 1])))
                spread (nfr/task-spread per-task :writes 4)]
            (is (= 4 (count ks)))
            (is (= #{0 1 2 3} (set (keys per-task))))
            (is (pos? (:min spread)))
            (is (<= (:max-share spread) 0.5))))
        (testing "task-spread detects skew"
          (let [per-task (nfr/capture-per-task-ops
                          #(dotimes [i 8] (foreign-append! events ["hot" i])))
                spread (nfr/task-spread per-task :writes 4)]
            (is (= 1.0 (:max-share spread)))))
        (testing "allow-yield-used? returns a boolean"
          (is (boolean? (nfr/allow-yield-used? #(foreign-append! events ["y" 1])))))
        (testing "capture-events returns ordered event maps"
          (let [[_ evs] (nfr/capture-events (fn [t _] (= t :rocks-read))
                                            #(foreign-select-one (keypath "a") counts))]
            (is (= [:rocks-read] (mapv :event-type evs)))
            (is (contains? (first evs) :task-id))))))))
