(ns hld-feature-flag-service.private-test-support
  (:require [clojure.test :refer [is testing]]
            [com.rpl.rama.test :as rtest]
            [hld-feature-flag-service.protocol :as p]
            [rama-challenges.harness :as harness]))

(def base {:revision 3 :killed? false :off-value "off" :default-value "v1"
           :rules [{:attribute "plan" :operator :eq :value "enterprise" :serve "v2"}
                   {:attribute "country" :operator :eq :value "DE" :serve "v1"}]
           :rollout {:threshold 7000 :serve "v2"}})

(defn- put! [client key config]
  (p/put-flag-config! client "acme" "prod" key config))

(defn- read! [client key]
  (p/get-flag-config client "acme" "prod" key))

(defn- eval! [client key subject attributes]
  (p/evaluate client "acme" "prod" key subject attributes))

(defn- result! [expected actual]
  (is (= expected (select-keys actual (keys expected)))
      (str "expected required fields " expected ", got " actual)))

(defn- count-reads [f]
  (let [reads (atom 0)]
    (rtest/with-event-hook
      (fn [event _]
        (when (#{:rocks-read :rocks-iterator :rocks-iterator-read} event)
          (swap! reads inc)))
      (f)
      @reads)))

(defn test-module [create-module-fn tasks]
  (let [{:keys [module wrap-client]} (create-module-fn)]
    (binding [harness/*task-count* tasks]
      (with-open [ipc (rtest/create-ipc)]
        (rtest/launch-module! ipc module {:tasks tasks :threads 2})
        (let [a (wrap-client ipc)
              b (wrap-client ipc)]
          (harness/wait-for-processing! b)
          (testing (str tasks " tasks: independent published and byte-level buckets")
            (doseq [[args expected] [[ ["acme" "prod" "new-checkout" "user-42"] 7336]
                                     [["acme" "prod" "new-checkout" "user-7"] 6729]
                                     [["acme" "staging" "new-checkout" "user-42"] 798]
                                     [["acme" "prod" "dark-mode" "user-42"] 598]
                                     [["globex" "prod" "new-checkout" "user-42"] 2961]
                                     [["é" "prod" "a:b" "用户"] 3610]
                                     [["a" "bc" "flag" "u"] 5866]
                                     [["ab" "c" "flag" "u"] 9548]]]
              (is (= expected (apply p/compute-bucket a args)) (str args)))
            (result! {:value nil :revision nil :reason :missing}
                     (eval! b "new-checkout" "user-42" {}))
            (is (nil? (read! b "new-checkout"))))

          (testing "revision ordering, whole replacement, and cross-wrapper visibility"
            (put! a "new-checkout" base)
            (harness/wait-for-processing! a)
            (is (= base (read! b "new-checkout")))
            (put! b "new-checkout" (assoc base :killed? true))
            (put! b "new-checkout" (assoc base :revision 2 :default-value "stale"))
            (harness/wait-for-processing! b)
            (is (= base (read! a "new-checkout")))
            (result! {:value "v2" :revision 3 :reason :rule :rule-index 0}
                     (eval! a "new-checkout" "user-42" {"plan" "enterprise"}))
            (result! {:value "v1" :revision 3 :reason :rule :rule-index 1}
                     (eval! b "new-checkout" "user-42" {"country" "DE" "plan" "free"}))
            (result! {:value "v1" :revision 3 :reason :default :bucket 7336}
                     (eval! a "new-checkout" "user-42" {}))
            (result! {:value "v2" :revision 3 :reason :rollout :bucket 6729}
                     (eval! b "new-checkout" "user-7" {"plan" "free"}))
            (p/put-flag-config! b "acme" "staging" "new-checkout" (assoc base :revision 1))
            (p/put-flag-config! b "globex" "prod" "new-checkout" (assoc base :revision 11))
            (harness/wait-for-processing! b)
            (result! {:value "v2" :revision 1 :reason :rollout :bucket 798}
                     (p/evaluate a "acme" "staging" "new-checkout" "user-42" {}))
            (is (= 11 (:revision (p/get-flag-config a "globex" "prod" "new-checkout"))))
            (is (= base (read! a "new-checkout"))))

          (testing "kill and unknown operator precedence, then clean recovery"
            (put! b "new-checkout" (assoc base :revision 4 :killed? true
                                           :rules (conj (:rules base)
                                                        {:attribute "age" :operator :gt
                                                         :value 18 :serve "bad"})))
            (harness/wait-for-processing! b)
            (result! {:value "off" :revision 4 :reason :killed}
                     (eval! a "new-checkout" "user-42" {"plan" "enterprise"}))
            (let [unknown (assoc base :revision 5 :rules
                                 [(first (:rules base))
                                  {:attribute "age" :operator :gt :value 18 :serve "bad"}])]
              (put! a "new-checkout" unknown)
              (harness/wait-for-processing! a)
              (is (= unknown (read! b "new-checkout")))
              (result! {:value "off" :revision 5 :reason :unknown-operator}
                       (eval! b "new-checkout" "user-42" {"plan" "enterprise"})))
            (let [clean (-> base (assoc :revision 6 :rules []) (dissoc :rollout))]
              (put! b "new-checkout" clean)
              (harness/wait-for-processing! b)
              (is (= clean (read! a "new-checkout"))))
            (let [result (eval! a "new-checkout" "user-42" {"plan" "enterprise"})]
              (result! {:value "v1" :revision 6 :reason :default} result)
              (is (not (contains? result :bucket)))))

          (testing "false versus missing, first rule, boundaries and strict threshold"
            (let [cfg (-> base
                          (assoc :revision 1 :off-value false :default-value 0
                                 :rules [{:attribute "x" :operator :eq :value false :serve false}
                                         {:attribute "x" :operator :eq :value false :serve 999}]
                                 :rollout {:threshold 7336 :serve 7}))]
              (put! a "boundary" cfg)
              (harness/wait-for-processing! a)
              (is (= cfg (read! b "boundary")))
              ;; Use the published 7336 bucket under its own identity, not boundary's.
              (put! b "new-checkout" (assoc cfg :revision 7))
              (harness/wait-for-processing! b)
              (result! {:value false :revision 7 :reason :rule :rule-index 0}
                       (eval! a "new-checkout" "user-42" {"x" false}))
              (result! {:value 0 :revision 7 :reason :default :bucket 7336}
                       (eval! a "new-checkout" "user-42" {}))
              (result! {:value 7 :revision 7 :reason :rollout :bucket 6729}
                       (eval! a "new-checkout" "user-7" {}))
              (put! a "new-checkout" (assoc cfg :revision 8 :rollout {:threshold 0 :serve 7}))
              (harness/wait-for-processing! a)
              (result! {:value 0 :revision 8 :reason :default :bucket 6729}
                       (eval! b "new-checkout" "user-7" {}))
              (put! a "new-checkout" (assoc cfg :revision 9 :rollout {:threshold 10000 :serve 7}))
              (harness/wait-for-processing! a)
              (result! {:value 7 :revision 9 :reason :rollout :bucket 7336}
                       (eval! b "new-checkout" "user-42" {}))
              (put! b "new-checkout" (assoc cfg :revision 10 :rollout nil))
              (harness/wait-for-processing! b)
              (is (= nil (:rollout (read! a "new-checkout"))))
              (is (contains? (read! a "new-checkout") :rollout))
              (let [result (eval! b "new-checkout" "user-42" {})]
                (result! {:value 0 :revision 10 :reason :default} result)
                (is (not (contains? result :bucket))))))

          (testing "typed equality and false safe values"
            (put! b "typed" (assoc base :revision 1 :off-value false
                                   :rules [{:attribute "age" :operator :eq
                                            :value 18 :serve 0}]
                                   :rollout nil))
            (harness/wait-for-processing! b)
            (result! {:value 0 :revision 1 :reason :rule :rule-index 0}
                     (eval! a "typed" "user-42" {"age" 18}))
            (result! {:value "v1" :revision 1 :reason :default}
                     (eval! a "typed" "user-42" {"age" "18"}))
            (put! b "typed" (assoc base :revision 2 :killed? true :off-value false))
            (harness/wait-for-processing! b)
            (result! {:value false :revision 2 :reason :killed}
                     (eval! a "typed" "user-42" {"age" 18})))

          (testing "ordered equal revisions in one unsynchronized group and signed-64 boundary"
            (put! a "order" (assoc base :revision Long/MAX_VALUE :default-value "first"))
            (put! a "order" (assoc base :revision Long/MAX_VALUE :default-value "second"))
            (harness/wait-for-processing! a)
            (is (= "first" (:default-value (read! b "order"))))
            (put! b "order" (assoc base :revision (dec Long/MAX_VALUE) :default-value "lower"))
            (harness/wait-for-processing! b)
            (is (= Long/MAX_VALUE (:revision (read! a "order")))))

          (testing "one-flag lookup work is bounded while unrelated flags grow"
            (doseq [i (range 150)]
              (put! a (str "other-" i) (assoc base :revision 1)))
            (harness/wait-for-processing! a)
            (let [reads (count-reads
                         #(do (is (= 10 (:revision (read! b "new-checkout"))))
                              (result! {:value 0 :revision 10 :reason :default}
                                       (eval! b "new-checkout" "user-42" {}))))]
              ;; One key per call, with slack for engine bookkeeping. The hook
              ;; does not measure serialized value bytes or client-side scans.
              (is (< reads 20) (str "narrow reads after 150 unrelated flags: " reads))))

          (testing "fresh wrapper after prior processed records"
            (let [c (wrap-client ipc)
                  cfg (assoc base :revision 12 :default-value "from-new-wrapper")]
              (put! c "fresh" cfg)
              (harness/wait-for-processing! c)
              (is (= cfg (read! a "fresh")))))

          (testing "durable update and subsequent write from second wrapper"
            (rtest/update-module! ipc module)
            (is (= 10 (:revision (read! b "new-checkout"))))
            (result! {:value 0 :revision 10 :reason :default}
                     (eval! b "new-checkout" "user-42" {}))
            (put! b "new-checkout" (assoc base :revision 11 :killed? true))
            (harness/wait-for-processing! b)
            (result! {:value "off" :revision 11 :reason :killed}
                     (eval! a "new-checkout" "user-42" {}))))))))
