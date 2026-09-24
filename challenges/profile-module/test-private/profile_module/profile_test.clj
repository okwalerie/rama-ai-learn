(ns profile-module.profile-test
  (:require [clojure.test :as t :refer [deftest is testing]]
            [com.rpl.rama.test :as rt]
            [profile-module.protocol :as p]
            [rama-challenges.harness :as h]))
(defn- factory [] (requiring-resolve 'profile-module.module/create-module))
(deftest registration-and-edit-contract
  (doseq [tasks [2 4]]
    (let [{:keys [module wrap-client]} ((factory))]
      (with-open [ipc (rt/create-ipc)]
        (rt/launch-module! ipc module {:tasks tasks :threads 2})
        (let [c (wrap-client ipc)
              id (p/register! c "uuid-a" "alice" "h1")
              other-id (p/register! c "uuid-other" "bob" "h-bob")]
          (h/wait-for-processing! c)
          (is (instance? Long id))
          (is (= {:username "alice" :pwd-hash "h1"} (p/get-profile c id)))
          (is (instance? Long other-id))
          (is (= {:username "bob" :pwd-hash "h-bob"} (p/get-profile c other-id)))
          (is (= {:username "alice" :pwd-hash "h1"} (p/get-profile c id)))
          (is (nil? (p/register! c "uuid-b" "alice" "wrong")))
          ;; Seeded source-behavior control: same UUID is not stable/idempotent.
          (let [retry-id (p/register! c "uuid-a" "alice" "h2")]
            (h/wait-for-processing! c)
            (is (instance? Long retry-id))
            (is (not= id retry-id))
            (is (= {:username "alice" :pwd-hash "h1"} (p/get-profile c id)))
            (is (= {:username "alice" :pwd-hash "h2"} (p/get-profile c retry-id))))
          (is (nil? (p/edit-profile! c id [(p/display-name-edit "Alicia")
                                           (p/height-inches-edit 65)
                                           (p/display-name-edit "A. Smith")])) )
          (h/wait-for-processing! c)
          (is (= {:username "alice" :pwd-hash "h1" :display-name "A. Smith" :height-inches 65}
                 (p/get-profile c id)))
          (is (= {:username "bob" :pwd-hash "h-bob"} (p/get-profile c other-id)))
          (is (nil? (p/get-profile c -999))))))))
