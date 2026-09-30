(ns collaborative-document-editor.challenge-test
  (:require [clojure.test :refer [deftest is testing]]
            [collaborative-document-editor.module :as module]
            [collaborative-document-editor.protocol :as p]
            [com.rpl.rama.test :as rtest]
            [rama-challenges.harness :as harness]))

(defn failed-streaming [f]
  (let [failed? (atom false)]
    (rtest/with-event-hook
      (fn [event-type _data]
        (when (and (= event-type :streaming-complete)
                   (compare-and-set! failed? false true))
          :fail))
      (f))))

(deftest document-edit-contract
  (doseq [tasks [2 4]]
    (testing (str tasks " tasks")
      (with-redefs [clojure.core/rand-nth (constantly tasks)]
        (harness/with-module [client module/create-module]
          (is (= {:doc nil :version 0} (p/doc+version client 99)))
          (p/edit! client (p/->Edit 7 0 0 (p/->AddText "abcdef")))
          (harness/wait-for-processing! client)
          (is (= {:doc "abcdef" :version 1} (p/doc+version client 7)))
          (p/edit! client (p/->Edit 8 0 0 (p/->AddText "other")))
          (p/edit! client (p/->Edit 7 1 3 (p/->AddText "XY")))
          (harness/wait-for-processing! client)
          (is (= {:doc "abcXYdef" :version 2} (p/doc+version client 7)))
          (is (= {:doc "other" :version 1} (p/doc+version client 8)))
          ;; Client B's [1,5) removal predates the insertion at 3: it
          ;; splits around XY, removing bc and de, not the inserted text.
          (p/edit! client (p/->Edit 7 1 1 (p/->RemoveText 4)))
          (harness/wait-for-processing! client)
          (is (= {:doc "aXYf" :version 4} (p/doc+version client 7)))
          (p/edit! client (p/->Edit 7 4 0 (p/->RemoveText 4)))
          (harness/wait-for-processing! client)
          (is (= {:doc "" :version 5} (p/doc+version client 7)))
          (is (= {:doc "other" :version 1} (p/doc+version client 8))))))))

(deftest stale-insertion-and-removal-boundaries
  (doseq [tasks [2 4]]
    (testing (str tasks " tasks")
      (with-redefs [clojure.core/rand-nth (constantly tasks)]
        (harness/with-module [client module/create-module]
          (p/edit! client (p/->Edit 42 0 0 (p/->AddText "abcd")))
          (p/edit! client (p/->Edit 42 1 2 (p/->AddText "XY")))
          ;; At the same offset a stale insertion follows the earlier one.
          (p/edit! client (p/->Edit 42 1 2 (p/->AddText "!")))
          (harness/wait-for-processing! client)
          (is (= {:doc "abXY!cd" :version 3} (p/doc+version client 42)))
          ;; Removing a span already removed transforms to no operations.
          (p/edit! client (p/->Edit 42 3 2 (p/->RemoveText 3)))
          (p/edit! client (p/->Edit 42 3 2 (p/->RemoveText 3)))
          (harness/wait-for-processing! client)
          (is (= {:doc "abcd" :version 4} (p/doc+version client 42))))))))

;; Each forced retry replays one depot record after the first attempt ran.
(deftest stream-retry-applies-each-edit-once
  (doseq [tasks [2 4]]
    (testing (str tasks " tasks")
      (with-redefs [clojure.core/rand-nth (constantly tasks)]
        (harness/with-module [client module/create-module]
          (doseq [id [50 51]]
            (p/edit! client (p/->Edit id 0 0 (p/->AddText "abcdef")))
            (p/edit! client (p/->Edit id 1 3 (p/->AddText "XY"))))
          (p/edit! client (p/->Edit 51 1 3 (p/->AddText "!")))
          (harness/wait-for-processing! client)
          (testing "stale insertion"
            (failed-streaming
              #(p/edit! client (p/->Edit 50 1 3 (p/->AddText "!"))))
            (harness/wait-for-processing! client)
            (is (= {:doc "abcXY!def" :version 3} (p/doc+version client 50))))
          (testing "stale removal split around insertions"
            (failed-streaming
              #(p/edit! client (p/->Edit 51 1 1 (p/->RemoveText 4))))
            (harness/wait-for-processing! client)
            (is (= {:doc "aXY!f" :version 5} (p/doc+version client 51))))
          (testing "fully subsumed stale removal"
            (failed-streaming
              #(p/edit! client (p/->Edit 51 1 2 (p/->RemoveText 1))))
            (harness/wait-for-processing! client)
            (is (= {:doc "aXY!f" :version 5} (p/doc+version client 51))))
          (testing "later edits"
            (p/edit! client (p/->Edit 50 3 9 (p/->AddText "g")))
            (p/edit! client (p/->Edit 51 5 5 (p/->AddText "g")))
            (harness/wait-for-processing! client)
            (is (= {:doc "abcXY!defg" :version 4} (p/doc+version client 50)))
            (is (= {:doc "aXY!fg" :version 6} (p/doc+version client 51)))))))))
