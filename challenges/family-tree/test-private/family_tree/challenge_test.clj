(ns family-tree.challenge-test
  (:require [clojure.test :refer [deftest is testing]]
            [com.rpl.rama.test :as rtest]
            [family-tree.protocol :as p]
            [rama-challenges.harness :as harness])
  (:import [java.util UUID]))

(defn id [n]
  (UUID/fromString (format "00000000-0000-0000-0000-%012d" n)))

(defn exercise [tasks]
  (let [{:keys [module wrap-client]}
        ((requiring-resolve 'family-tree.module/create-module))]
    (with-open [ipc (rtest/create-ipc)]
      (rtest/launch-module! ipc module {:tasks tasks :threads 2})
      (let [a (wrap-client ipc)
            b (wrap-client ipc)
            [root p1 p2 child grand missing] (mapv id (range 1 7))]
        (testing (str tasks " tasks: missing nodes and zero-generation boundaries")
          (is (nil? (p/ancestors b missing 0)))
          (is (= {} (p/descendants-count b missing 0)))
          (is (= {0 0} (p/descendants-count b missing 2))))
        (doseq [person [(p/->Person root nil nil "root")
                        (p/->Person p1 root nil "p1")
                        (p/->Person p2 root nil "p2")
                        (p/->Person child p1 p2 "child")
                        (p/->Person grand child nil "grand")]]
          (p/add-person! a person))
        (harness/wait-for-processing! a)
        (testing (str tasks " tasks: parents, paths, and terminal zero levels")
          (is (nil? (p/ancestors b root 4)))
          (is (= #{p1 p2} (p/ancestors b child 0)))
          (is (= #{p1 p2 root} (p/ancestors b child 1)))
          (is (= #{child p1 p2 root} (p/ancestors b grand 3)))
          (is (= {} (p/descendants-count b root 0)))
          (is (= {0 2} (p/descendants-count b root 1)))
          (is (= {0 2 1 2 2 2} (p/descendants-count b root 3)))
          (is (= {0 2 1 2 2 2 3 0} (p/descendants-count b root 4)))
          (is (= {0 0} (p/descendants-count b grand 3))))
        (rtest/update-module! ipc module)
        (let [fresh (wrap-client ipc)]
          (testing (str tasks " tasks: retained state and independent clients")
            (is (= {0 2 1 2 2 2} (p/descendants-count fresh root 3)))
            (is (= #{p1 p2 root} (p/ancestors fresh child 1)))
            (p/add-person! fresh (p/->Person (id 7) grand nil "next"))
            (harness/wait-for-processing! fresh)
            (is (= {0 1 1 0} (p/descendants-count b grand 2)))
            (is (= #{grand child p1 p2 root}
                   (p/ancestors a (id 7) 4)))))))))

(deftest family-tree-contract
  (doseq [tasks [2 4]]
    (exercise tasks)))
