(ns optchat-memory.test-support
  "Small host-side protocol examples; not a solver source dependency."
  (:require [clojure.test :refer [is]]
            [optchat-memory.protocol :as p]
            [rama-challenges.harness :as h]))

(defn basic-examples [client]
  (let [message {:kind :user :text "hello\n世界" :timestamp -1}]
    (is (= {:ok {:index 0}} (p/append! client "example" "first" message)))
    (h/wait-for-processing! client)
    (is (= {:ok (assoc message :index 0)} (p/zoom client "example" 0 1)))
    (is (= {:ok -1} (p/date client "example" 0)))
    (is (= {:ok {:index 0}} (p/append! client "example" "first" message)))
    (h/wait-for-processing! client)
    (is (= 1 (get-in (p/view client "example" nil) [:ok :watermark])))))
