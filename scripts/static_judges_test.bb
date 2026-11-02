#!/usr/bin/env bb
;; Tests for scripts/static_judges.bb. Run from the repo root:
;;   bb scripts/static_judges_test.bb

(require '[clojure.test :refer [deftest testing is run-tests]])

(load-file "scripts/static_judges.bb")

(defn- verdicts [src]
  (let [forms (parse-forms src)]
    [(:verdict (judge-j6 forms)) (:verdict (judge-j7 forms))]))

(def stream-count
  "(defmodule M [setup topologies]
     (declare-depot setup *votes (hash-by :option-id))
     (let [s (stream-topology topologies \"tallies\")]
       (declare-pstate s $$tallies {Long Long})
       (<<sources s
         (source> *votes :> {:keys [*option-id]})
         (+compound $$tallies {*option-id (aggs/+count)}))))")

(def microbatch-count
  "(defmodule M [setup topologies]
     (let [mb (microbatch-topology topologies \"tallies\")]
       (declare-pstate mb $$tallies {Long Long})
       (<<sources mb
         (source> *votes :> %mb)
         (%mb :> {:keys [*option-id]})
         (+compound $$tallies {*option-id (aggs/+count)}))))")

(def stream-overwrite
  "(defmodule M [setup topologies]
     (let [s (stream-topology topologies \"core\")]
       (declare-pstate s $$profiles {Long String})
       (<<sources s
         (source> *profiles :> {:keys [*id *name]})
         (local-transform> [(keypath *id) (termval *name)] $$profiles))))")

(def stream-dedup-then-append
  "(defmodule M [setup topologies]
     (let [s (stream-topology topologies \"core\")]
       (<<sources s
         (source> *events :> {:keys [*id *req-id *v]})
         (local-select> (keypath *req-id) $$seen :> *seen?)
         (<<if (nil? *seen?)
           (local-transform> [(keypath *id) NIL->VECTOR AFTER-ELEM (termval *v)] $$log)))))")

(def stream-term-var
  "(defmodule M [setup topologies]
     (let [s (stream-topology topologies \"core\")]
       (<<sources s
         (source> *events :> {:keys [*id *f]})
         (local-transform> [(keypath *id) (term *f)] $$p))))")

(def stream-term-inc
  "(defmodule M [setup topologies]
     (let [s (stream-topology topologies \"core\")]
       (<<sources s
         (source> *events :> {:keys [*id]})
         (local-transform> [(keypath *id) (nil->val 0) (term inc)] $$p))))")

(def global-entity-pstate
  "(defmodule M [setup topologies]
     (let [s (stream-topology topologies \"core\")]
       (declare-pstate s $$player-scores {String Long} {:global? true})
       (<<sources s
         (source> *scores :> {:keys [*p *v]})
         (local-transform> [(keypath *p) (termval *v)] $$player-scores))))")

(def global-singleton
  "(defmodule M [setup topologies]
     (let [mb (microbatch-topology topologies \"ids\")]
       (declare-pstate mb $$next-id Long {:global? true})
       (<<sources mb
         (source> *events :> %mb)
         (<<batch
           (%mb :> *e)
           (|global)
           (aggs/+count :> *n)
           (local-transform> (term (partial + *n)) $$next-id)))))")

(def stream-global-hop
  "(defmodule M [setup topologies]
     (let [s (stream-topology topologies \"core\")]
       (<<sources s
         (source> *events :> {:keys [*id *v]})
         (|global)
         (local-transform> [(keypath *id) (termval *v)] $$p))))")

(def custom-combiner
  "(defmodule M [setup topologies]
     (let [mb (microbatch-topology topologies \"stats\")]
       (<<sources mb
         (source> *events :> %mb)
         (%mb :> {:keys [*k *v]})
         (+compound $$stats {*k (+merge-stats *v)}))))")

(deftest j6-verdicts
  (testing "a custom aggregator in a microbatch topology passes rather than reading as N/A"
    (is (= :pass (first (verdicts custom-combiner)))))
  (testing "non-idempotent aggregation in a stream topology fails"
    (is (= :fail (first (verdicts stream-count)))))
  (testing "the same aggregation in a microbatch topology passes"
    (is (= :pass (first (verdicts microbatch-count)))))
  (testing "keyed overwrite only is not applicable"
    (is (= :n-a (first (verdicts stream-overwrite)))))
  (testing "a read before the append may be a dedup check, so the code cannot decide"
    (is (= :unjudged (first (verdicts stream-dedup-then-append)))))
  (testing "an opaque term fn cannot be decided"
    (is (= :unjudged (first (verdicts stream-term-var)))))
  (testing "(term inc) in a stream topology fails and cites its line"
    (let [r (judge-j6 (parse-forms stream-term-inc))]
      (is (= :fail (:verdict r)))
      (is (= 5 (:line (first (:sites r))))))))

(def global-record
  "(defmodule M [setup topologies]
     (let [mb (microbatch-topology topologies \"jobs\")]
       (declare-pstate mb $$status (fixed-keys-schema {:active Long}) {:global? true})))")

(deftest j7-verdicts
  (testing "a global fixed-keys record is a singleton"
    (is (= :pass (second (verdicts global-record)))))
  (testing "a global PState keyed by entity is unjudged without a key declaration"
    (is (= :unjudged (second (verdicts global-entity-pstate)))))
  (testing "a singleton global PState written after |global inside a batch block passes"
    (is (= :pass (second (verdicts global-singleton)))))
  (testing "|global on per-event stream work fails"
    (is (= :fail (second (verdicts stream-global-hop)))))
  (testing "no global state passes"
    (is (= :pass (second (verdicts stream-overwrite))))))

(let [{:keys [fail error]} (run-tests)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
