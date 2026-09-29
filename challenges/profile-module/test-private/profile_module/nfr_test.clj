(ns profile-module.nfr-test
  "NFR test: username ownership holds under concurrent registrations from
   several clients; exactly one UUID wins each username."
  (:require [clojure.test :refer [deftest is testing]]
            [com.rpl.rama.test :as rt]
            [profile-module.protocol :as p]
            [rama-challenges.harness :as h]))

(deftest concurrent-registrations-have-one-winner
  (doseq [tasks [2 4]]
    (testing (str tasks " tasks")
      (let [{:keys [module wrap-client]}
            ((requiring-resolve 'profile-module.module/create-module))]
        (with-open [ipc (rt/create-ipc)]
          (rt/launch-module! ipc module {:tasks tasks :threads tasks})
          (let [clients (vec (repeatedly 4 #(wrap-client ipc)))
                usernames (mapv #(str "user" %) (range 5))
                start (promise)
                attempts (vec (for [u usernames, i (range 20)] [u i]))
                results (mapv (fn [[u i]]
                                (future @start
                                        [[u i] (p/register! (clients (mod i 4))
                                                            (str "uuid-" u "-" i) u (str "h-" i))]))
                              attempts)
                _ (deliver start true)
                results (mapv deref results)
                c (first clients)]
            (h/wait-for-processing! c)
            (doseq [u usernames]
              (let [winners (filterv (fn [[[u' _] id]] (and (= u u') (some? id))) results)]
                (is (= 1 (count winners))
                    (str u ": exactly one of 20 concurrent UUIDs may own the username, got "
                         winners))
                (when-let [[[_ i] id] (first winners)]
                  (is (= {:username u :pwd-hash (str "h-" i)} (p/get-profile c id))))))
            (let [ids (keep second results)]
              (is (= (count ids) (count (set ids))) "user IDs are unique"))))))))
