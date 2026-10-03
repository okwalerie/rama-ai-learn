(ns hld-enterprise-rag.nfr-test
  "NFR tests: index and revision writes apply exactly once under a forced
   stream retry, and put, delete, membership and point reads do not grow with
   the tenant's corpus."
  (:require [clojure.test :refer :all]
            [com.rpl.rama.test :as rtest]
            [hld-enterprise-rag.protocol :as rag]
            [rama-challenges.harness :as harness]
            [rama-challenges.nfr :as nfr]))

(defn- with-cluster [tasks f]
  (let [{:keys [module wrap-client]}
        ((requiring-resolve 'hld-enterprise-rag.module/create-module))]
    (with-open [ipc (rtest/create-ipc)]
      (rtest/launch-module! ipc module {:tasks tasks :threads tasks})
      (f (wrap-client ipc) (wrap-client ipc)))))

(defn- make-chunk [id text & tokens]
  {:chunk-id id :text text :tokens (vec tokens)})

(defn- retried!
  "Runs write f and waits, failing the first stream completion so a stream
   design retries it. A microbatch design sees no forced failure."
  [c f]
  (nfr/failed-streaming #(do (f) (harness/wait-for-processing! c))))

(deftest forced-stream-retry-maintains-index-exactly-once
  (doseq [tasks [2 4]]
    (testing (str tasks " tasks")
      (with-cluster tasks
        (fn [w r]
          (let [hits #(mapv (juxt :doc-id :chunk-id :score) (rag/query r "t" "u" % 10))]
            (retried! r #(rag/put-user-groups! w "t" "u" 1 #{"g"}))
            (retried! r #(rag/put-document-acl! w "t" "d" 1 #{"g"}))
            (retried! r #(rag/put-document! w "t" "d" 1 [(make-chunk "a" "alpha" "x" "y")
                                                         (make-chunk "b" "beta" "y")]))
            (is (= [["d" "a" 2] ["d" "b" 1]] (hits ["x" "y"])))
            (retried! r #(rag/put-document! w "t" "d" 2 [(make-chunk "c" "gamma" "z")]))
            (is (= [] (hits ["x" "y"])) "a retried replacement must remove the old postings")
            (is (= [["d" "c" 1]] (hits ["z"])) "a retried replacement must add the new postings")
            (retried! r #(rag/delete-document! w "t" "d" 3))
            (is (= [] (hits ["x" "y" "z"])) "a retried delete must remove every posting")
            (retried! r #(rag/put-document! w "t" "d" 4 [(make-chunk "a" "again" "x")]))
            (is (= [["d" "a" 1]] (hits ["x" "y" "z"])))
            (retried! r #(rag/put-document-acl! w "t" "d" 2 #{"h"}))
            (is (= [] (hits ["x"])) "a retried ACL change must take effect")
            (retried! r #(rag/put-user-groups! w "t" "u" 2 #{"h"}))
            (is (= [["d" "a" 1]] (hits ["x"])) "a retried membership change must take effect")
            (is (= {:doc-id "d" :content-revision 4 :live? true :chunk-count 1
                    :acl-revision 2 :groups #{"h"}}
                   (rag/get-document r "t" "d")))
            (is (= {:membership-revision 2 :groups #{"h"}} (rag/get-user-groups r "t" "u")))))))))

(defn- populate!
  "Adds tenant documents (start, end]; every noise chunk shares the token
   \"common\" with the measured documents."
  [w start end]
  (doseq [i (range start end)]
    (let [doc (str "noise-" i)]
      (rag/put-document! w "t" doc 1 [(make-chunk "a" "noise" "common" (str "n" i))
                                      (make-chunk "b" "noise" "other")])
      (rag/put-document-acl! w "t" doc 1 #{"g"})))
  (harness/wait-for-processing! w))

(defn- sample [w r tag rev]
  (let [measure-write (fn [f] (nfr/capture-rocks-ops #(do (f) (harness/wait-for-processing! r))))
        doc (str "fresh-" tag)
        samples
        {:put-new (measure-write #(rag/put-document! w "t" doc 1 [(make-chunk "a" "new" "common" doc)]))
         :put-replace (measure-write #(rag/put-document! w "t" doc 2 [(make-chunk "b" "newer" "common" doc)]))
         :delete (measure-write #(rag/delete-document! w "t" doc 3))
         :put-user-groups (measure-write #(rag/put-user-groups! w "t" "u" rev #{"g" (str "g" rev)}))
         :get-document (nfr/capture-rocks-ops #(rag/get-document r "t" "noise-0"))
         :get-user-groups (nfr/capture-rocks-ops #(rag/get-user-groups r "t" "u"))}]
    (is (= {:doc-id doc :content-revision 3 :live? false :chunk-count 0
            :acl-revision nil :groups nil}
           (rag/get-document r "t" doc)))
    (is (= rev (:membership-revision (rag/get-user-groups r "t" "u"))))
    samples))

(deftest writes-and-point-reads-bounded-by-one-entity
  (doseq [tasks [2 4]]
    (testing (str tasks " tasks")
      (with-cluster tasks
        (fn [w r]
          (populate! w 0 256)
          (let [small (sample w r "small" 1)]
            (populate! w 256 2048)
            (let [large (sample w r "large" 2)]
              (println "RAG measured work" tasks "tasks" small "->" large)
              (doseq [op [:put-new :put-replace :delete :put-user-groups]]
                (is (pos? (get-in small [op :writes])) (str op " measured no writes")))
              (doseq [op (keys small)
                      metric [:reads :iterators :iterator-reads :writes]]
                (let [before (get-in small [op metric])
                      after (get-in large [op metric])]
                  (is (<= after (+ 24 (* 2 before)))
                      (str op " " metric " grew with the tenant's corpus: "
                           before " -> " after)))))))))))
