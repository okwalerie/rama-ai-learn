(ns content-moderation.test-support
  (:require [clojure.test :refer [is testing]]
            [com.rpl.rama.test :as rtest]
            [content-moderation.protocol :as p]
            [rama-challenges.harness :as harness]))

(defn check-contract
  "Run identical private assertions against a candidate or private reference."
  [create-module tasks]
  (let [{:keys [module wrap-client]} (create-module)]
    (with-open [ipc (rtest/create-ipc)]
      (rtest/launch-module! ipc module {:tasks tasks :threads 2})
      (let [client (wrap-client ipc)
            feed (mapv #(p/->Post % 10 (str "p" %2))
                       [1 2 1 3 2 4] (range 6))
            page (fn [reader offset limit] (p/get-posts client reader offset limit))]
        (testing (str tasks " tasks: unfiltered feed and boundaries")
          (is (= {:posts [] :next-offset nil} (page 10 0 2)))
          (is (= {:posts [] :next-offset nil} (page 99 5 1)))
          (doseq [post feed] (p/post! client post))
          (harness/wait-for-processing! client)
          (is (= {:posts (subvec feed 0 2) :next-offset 2} (page 10 0 2)))
          (is (= {:posts (subvec feed 2 6) :next-offset nil} (page 10 2 4)))
          (is (= {:posts [] :next-offset nil} (page 10 6 2)))
          (is (= {:posts [] :next-offset nil} (page 10 9 2))))

        (testing "mute skips underlying offsets, including consecutive hidden posts"
          (p/mute! client 10 1)
          (p/mute! client 10 2)
          (harness/wait-for-processing! client)
          (is (= {:posts [(feed 3) (feed 5)] :next-offset nil} (page 10 0 2)))
          (is (= {:posts [(feed 3)] :next-offset 4} (page 10 1 1)))
          (is (= {:posts [(feed 5)] :next-offset nil} (page 10 4 1)))
          (p/mute! client 10 3)
          (p/mute! client 10 4)
          (harness/wait-for-processing! client)
          (is (= {:posts [] :next-offset nil} (page 10 0 2))))

        (testing "unmuting restores old posts and repeated mute/unmute is idempotent"
          (p/unmute! client 10 1)
          (p/unmute! client 10 1)
          (harness/wait-for-processing! client)
          (is (= {:posts [(feed 0) (feed 2)] :next-offset 3} (page 10 0 2)))
          (p/mute! client 10 1)
          (p/mute! client 10 1)
          (harness/wait-for-processing! client)
          (is (= {:posts [] :next-offset nil} (page 10 0 2)))
          (p/unmute! client 10 1)
          (p/unmute! client 10 3)
          (p/unmute! client 10 4)
          (harness/wait-for-processing! client)
          (is (= {:posts [(feed 0) (feed 2)] :next-offset 3} (page 10 0 2)))
          (is (= {:posts [(feed 3) (feed 5)] :next-offset nil} (page 10 3 2))))

        (testing "a long hidden run is traversed, not counted toward page size"
          (let [hidden (mapv #(p/->Post 2 10 (str "hidden" %)) (range 12))
                tail (p/->Post 5 10 "tail")]
            (doseq [post hidden] (p/post! client post))
            (p/post! client tail)
            (harness/wait-for-processing! client)
            (is (= {:posts [(feed 0) (feed 2) (feed 3)] :next-offset 4}
                   (page 10 0 3)))
            (is (= {:posts [(feed 5) tail] :next-offset nil} (page 10 4 2)))
            (is (= {:posts [tail] :next-offset nil} (page 10 6 1)))
            (is (= {:posts [] :next-offset nil} (page 10 19 1)))))

        (testing "recipient isolation, repeated posts, and new writes after reads"
          (let [same (p/->Post 2 20 "repeat")
                self (p/->Post 20 20 "self")
                next-post (p/->Post 7 10 "new")]
            (p/post! client same)
            (p/post! client same)
            (p/post! client self)
            (harness/wait-for-processing! client)
            (is (= {:posts [same same] :next-offset 2} (page 20 0 2)))
            (is (= {:posts [self] :next-offset nil} (page 20 2 2)))
            (p/mute! client 20 2)
            (harness/wait-for-processing! client)
            (is (= {:posts [self] :next-offset nil} (page 20 0 2)))
            (is (= {:posts [(feed 0)] :next-offset 1} (page 10 0 1)))
            (p/post! client next-post)
            (harness/wait-for-processing! client)
            (is (= {:posts [next-post] :next-offset nil} (page 10 19 2)))
            (is (= {:posts [self] :next-offset nil} (page 20 0 2)))
            (p/unmute! client 20 2)
            (harness/wait-for-processing! client)
            (is (= {:posts [same same self] :next-offset nil} (page 20 0 3)))))))))
