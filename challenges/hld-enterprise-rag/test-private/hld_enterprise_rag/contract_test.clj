(ns hld-enterprise-rag.contract-test
  (:require [clojure.test :refer :all]
            [com.rpl.rama.test :as rtest]
            [hld-enterprise-rag.protocol :as rag]
            [rama-challenges.harness :as harness]))

(defn with-cluster [tasks f]
  (let [{:keys [module wrap-client]} ((requiring-resolve 'hld-enterprise-rag.module/create-module))]
    (with-open [ipc (rtest/create-ipc)]
      (rtest/launch-module! ipc module {:tasks tasks :threads tasks})
      (f (wrap-client ipc) (wrap-client ipc)))))

(defn make-chunk [id text & tokens]
  {:chunk-id id :text text :tokens (vec tokens)})

(defn result [doc id score text cr ar]
  {:doc-id doc :chunk-id id :score score :text text
   :content-revision cr :acl-revision ar})

(deftest revision-and-authorization
  (doseq [tasks [2 4]]
    (testing (str tasks " tasks")
      (with-cluster tasks
        (fn [writer reader]
          (is (nil? (rag/get-document reader "a" "d")))
          (rag/put-user-groups! writer "a" "u" 2 #{"allowed"})
          (rag/put-document! writer "a" "forbidden" 1
                             [(make-chunk "z" "high" "x" "y")])
          (rag/put-document-acl! writer "a" "forbidden" 1 #{"other"})
          (rag/put-document! writer "a" "d" 3
                             [(make-chunk "b" "low" "x") (make-chunk "a" "tie" "x" "x")])
          (rag/put-document-acl! writer "a" "d" 5 #{"allowed"})
          (rag/put-document! writer "b" "d" 1 [(make-chunk "a" "alien" "x" "y")])
          (rag/put-document-acl! writer "b" "d" 1 #{"allowed"})
          (harness/wait-for-processing! writer)
          (is (= [(result "d" "a" 1 "tie" 3 5)]
                 (rag/query reader "a" "u" ["x" "y" "x"] 1)))
          (is (= [(result "d" "a" 1 "tie" 3 5)
                  (result "d" "b" 1 "low" 3 5)]
                 (rag/query reader "a" "u" ["x" "y"] 9)))
          (is (= [] (rag/query reader "b" "u" ["x"] 1)))
          (is (= [] (rag/query reader "a" "u" ["x"] 0)))
          (rag/put-document-acl! reader "a" "forbidden" 2 #{"allowed"})
          (rag/put-document! reader "a" "d" 3 [(make-chunk "a" "stale" "y")])
          (rag/put-document! reader "a" "d" 4 [(make-chunk "c" "new" "y")])
          (harness/wait-for-processing! reader)
          (is (= [(result "forbidden" "z" 2 "high" 1 2)]
                 (rag/query writer "a" "u" ["x" "y"] 1)))
          (is (= {:doc-id "d" :content-revision 4 :live? true :chunk-count 1
                  :acl-revision 5 :groups #{"allowed"}}
                 (rag/get-document writer "a" "d")))
          (rag/delete-document! writer "a" "d" 6)
          (rag/put-document-acl! writer "a" "d" 6 #{"other"})
          (rag/put-document! writer "a" "d" 6 [(make-chunk "c" "equal" "x")])
          (harness/wait-for-processing! writer)
          (is (= {:doc-id "d" :content-revision 6 :live? false :chunk-count 0
                  :acl-revision 6 :groups #{"other"}}
                 (rag/get-document reader "a" "d")))
          (is (= [(result "forbidden" "z" 2 "high" 1 2)]
                 (rag/query reader "a" "u" ["x" "y"] 9)))
          (rag/put-user-groups! reader "a" "u" 3 #{})
          (harness/wait-for-processing! reader)
          (is (= {:membership-revision 3 :groups #{}}
                 (rag/get-user-groups writer "a" "u")))
          (is (= [] (rag/query writer "a" "u" ["x" "y"] 9))))))))

(deftest first-delete-recreate-and-current-payload
  (doseq [tasks [2 4]]
    (testing (str tasks " tasks")
      (with-cluster tasks
        (fn [a b]
          (rag/delete-document! a "t" "never" 5)
          (rag/put-document-acl! a "t" "acl-first" 2 #{"g"})
          (rag/delete-document! a "t" "acl-first" 4)
          (rag/put-user-groups! a "t" "u" 1 #{"g"})
          (harness/wait-for-processing! a)
          (is (= {:doc-id "never" :content-revision 5 :live? false
                  :chunk-count 0 :acl-revision nil :groups nil}
                 (rag/get-document b "t" "never")))
          (is (= {:doc-id "acl-first" :content-revision 4 :live? false
                  :chunk-count 0 :acl-revision 2 :groups #{"g"}}
                 (rag/get-document b "t" "acl-first")))
          (rag/put-document! b "t" "never" 5 [(make-chunk "x" "blocked" "match")])
          (rag/put-document! b "t" "acl-first" 4 [(make-chunk "x" "blocked" "match")])
          (rag/put-document! b "t" "acl-first" 5
                             [(make-chunk "keep" "old" "match")
                              (make-chunk "drop" "old-extra" "match")])
          (harness/wait-for-processing! b)
          (is (= [(result "acl-first" "drop" 1 "old-extra" 5 2)
                  (result "acl-first" "keep" 1 "old" 5 2)]
                 (rag/query a "t" "u" ["match"] 10)))
          (rag/put-document! a "t" "acl-first" 6
                             [(make-chunk "keep" "new" "match" "extra")
                              (make-chunk "unrelated" "not-a-match" "other")])
          (rag/put-document-acl! a "t" "acl-first" 2 #{})
          (harness/wait-for-processing! a)
          (is (= [(result "acl-first" "keep" 2 "new" 6 2)]
                 (rag/query b "t" "u" ["match" "extra" "match"] 10)))
          (is (= [] (rag/query b "t" "u" ["other-token"] 10)))
          (rag/put-document-acl! b "t" "acl-first" 3 #{})
          (harness/wait-for-processing! b)
          (is (= [] (rag/query a "t" "u" ["match"] 10)))
          (rag/put-document-acl! a "t" "acl-first" 4 #{"g"})
          (rag/put-user-groups! a "t" "u" 1 #{})
          (harness/wait-for-processing! a)
          (is (= [(result "acl-first" "keep" 1 "new" 6 4)]
                 (rag/query b "t" "u" ["match"] 10)))
          (rag/put-user-groups! b "t" "u" 2 #{})
          (harness/wait-for-processing! b)
          (is (= [] (rag/query a "t" "u" ["match"] 10))))))))

(deftest fences-denial-and-tenant-isolation
  (doseq [tasks [2 4]]
    (testing (str tasks " tasks")
      (with-cluster tasks
        (fn [a b]
          (doseq [tenant ["east" "west"]]
            (rag/put-user-groups! a tenant "same-user" 7 #{"g"}))
          (rag/put-document! a "east" "same-doc" 9 [(make-chunk "c" "east" "hit")])
          (rag/put-document-acl! a "east" "same-doc" 2 #{"g"})
          (rag/put-document! a "west" "same-doc" 2 [(make-chunk "c" "west" "hit")])
          (rag/put-document-acl! a "west" "same-doc" 9 #{"g"})
          (rag/put-document! a "east" "unlisted" 1 [(make-chunk "c" "deny" "hit" "extra")])
          (harness/wait-for-processing! a)
          (is (= [(result "same-doc" "c" 1 "east" 9 2)]
                 (rag/query b "east" "same-user" ["hit" "extra"] 1)))
          (is (= [(result "same-doc" "c" 1 "west" 2 9)]
                 (rag/query b "west" "same-user" ["hit"] 1)))
          ;; Observe rejected bodies before any newer write can mask them.
          (rag/put-document! b "east" "same-doc" 8 [(make-chunk "c" "lower" "wrong")])
          (rag/delete-document! b "east" "same-doc" 9)
          (rag/put-document-acl! b "east" "same-doc" 1 #{})
          (rag/put-document-acl! b "west" "same-doc" 9 #{})
          (rag/put-user-groups! b "east" "same-user" 6 #{})
          (rag/put-user-groups! b "east" "same-user" 7 #{})
          (harness/wait-for-processing! b)
          (is (= [(result "same-doc" "c" 1 "east" 9 2)]
                 (rag/query a "east" "same-user" ["hit"] 1)))
          (is (= [] (rag/query a "east" "same-user" ["wrong"] 1)))
          (is (= {:membership-revision 7 :groups #{"g"}}
                 (rag/get-user-groups a "east" "same-user")))
          (is (= [(result "same-doc" "c" 1 "west" 2 9)]
                 (rag/query a "west" "same-user" ["hit"] 1)))
          (rag/delete-document! a "east" "same-doc" 10)
          (harness/wait-for-processing! a)
          (is (= [] (rag/query b "east" "same-user" ["hit"] 10)))
          (rag/put-document-acl! b "east" "same-doc" 3 #{"g" "extra"})
          (rag/put-document! b "east" "same-doc" 10 [(make-chunk "c" "equal" "hit")])
          (harness/wait-for-processing! b)
          (is (= [] (rag/query a "east" "same-user" ["hit"] 10)))
          (rag/put-document! a "east" "same-doc" 11 [(make-chunk "n" "revived" "new")])
          (harness/wait-for-processing! a)
          (is (= [] (rag/query b "east" "same-user" ["hit"] 10)))
          (is (= [(result "same-doc" "n" 1 "revived" 11 3)]
                 (rag/query b "east" "same-user" ["new"] 10)))
          (is (= [(result "same-doc" "c" 1 "west" 2 9)]
                 (rag/query b "west" "same-user" ["hit"] 10))))))))

(deftest durable-after-module-update
  (doseq [tasks [2 4]]
    (testing (str tasks " tasks")
      (let [{:keys [module wrap-client]} ((requiring-resolve 'hld-enterprise-rag.module/create-module))]
        (with-open [ipc (rtest/create-ipc)]
          (rtest/launch-module! ipc module {:tasks tasks :threads tasks})
          (let [writer (wrap-client ipc)]
            (rag/put-user-groups! writer "t" "u" 1 #{"g"})
            (rag/put-document-acl! writer "t" "d" 1 #{"g"})
            (rag/put-document! writer "t" "d" 1 [(make-chunk "c" "durable" "hit")])
            (harness/wait-for-processing! writer)
            (rtest/update-module! ipc module)
            (let [reader (wrap-client ipc)]
              (is (= [(result "d" "c" 1 "durable" 1 1)]
                     (rag/query reader "t" "u" ["hit"] 1)))
              (rag/delete-document! reader "t" "d" 2)
              (harness/wait-for-processing! reader)
              (is (= [] (rag/query writer "t" "u" ["hit"] 1))))))))))

(defn rocks-cost [f]
  (let [counts (atom {:reads 0 :writes 0})]
    (rtest/with-event-hook
      (fn [event-type data]
        (case event-type
          :rocks-read (swap! counts update :reads inc)
          :rocks-iterator-read (swap! counts update :reads inc)
          :rocks-commit (swap! counts update :writes + (:write-batch-count data))
          nil))
      (f)
      @counts)))

(deftest unrelated-corpus-growth
  (doseq [tasks [2 4]]
    (testing (str tasks " tasks")
      (with-cluster tasks
        (fn [a b]
          (rag/put-user-groups! a "t" "u" 1 #{"g"})
          (rag/put-document-acl! a "t" "needle" 1 #{"g"})
          (rag/put-document! a "t" "needle" 1 [(make-chunk "c" "needle" "target")])
          (rag/put-user-groups! a "other" "u" 1 #{"g"})
          (letfn [(populate! [start end]
                    (doseq [i (range start end)]
                      (let [tenant (if (even? i) "t" "other")
                            doc (str "noise-" i)]
                        (rag/put-document! a tenant doc 1
                                           [(make-chunk "a" "unrelated"
                                                        (if (= tenant "other") "target" "not-target"))
                                            (make-chunk "b" "also unrelated" "other-token")])
                        (rag/put-document-acl! a tenant doc 1 #{"g"})))
                    (harness/wait-for-processing! a))
                  (query-cost [content-revision acl-revision]
                    (let [actual (atom nil)
                          cost (rocks-cost #(reset! actual (rag/query b "t" "u" ["target"] 1)))]
                      (is (= [(result "needle" "c" 1 "needle" content-revision acl-revision)] @actual))
                      cost))
                  (acl-cost [revision]
                    (rocks-cost #(do (rag/put-document-acl! b "t" "needle" revision #{"g"})
                                     (harness/wait-for-processing! b))))]
            (populate! 0 256)
            (let [small-query (query-cost 1 1)
                  small-acl (acl-cost 2)]
              (populate! 256 2048)
              (let [large-query (query-cost 1 2)
                    large-acl (acl-cost 3)]
                (rag/put-document! a "t" "needle" 2
                                   (into [(make-chunk "c" "needle" "target")]
                                         (for [i (range 31)]
                                           (make-chunk (str "irrelevant-" i) "irrelevant" "not-target"))))
                (harness/wait-for-processing! a)
                (let [chunk-query (query-cost 2 3)
                      chunk-acl (acl-cost 4)]
                  (println "growth costs" {:tasks tasks :documents [256 2048]
                                           :chunks-per-document 2 :target-chunks [1 32]
                                           :query [small-query large-query chunk-query]
                                           :acl [small-acl large-acl chunk-acl]})
                  (is (every? pos? (map :reads [small-query large-query chunk-query])))
                  (is (every? pos? (map :writes [small-acl large-acl chunk-acl])))
                  (is (<= (:reads large-query) (+ (:reads small-query) 16))
                      (str "unrelated documents/chunks must not grow query reads: "
                           small-query " → " large-query))
                  (is (<= (:reads chunk-query) (+ (:reads large-query) 16))
                      (str "nonmatching chunks on the matching document must not grow query reads: "
                           large-query " → " chunk-query))
                  (is (<= (:reads large-acl) (+ (:reads small-acl) 16))
                      (str "unrelated documents must not grow ACL reads: "
                           small-acl " → " large-acl))
                  (is (<= (:reads chunk-acl) (+ (:reads large-acl) 16))
                      (str "target-document chunks must not grow ACL reads: "
                           large-acl " → " chunk-acl))
                  (is (<= (:writes large-acl) (+ (:writes small-acl) 16))
                      (str "unrelated documents must not grow ACL writes: "
                           small-acl " → " large-acl))
                  (is (<= (:writes chunk-acl) (+ (:writes large-acl) 16))
                      (str "target-document chunks must not grow ACL writes: "
                           large-acl " → " chunk-acl)))))))))))
