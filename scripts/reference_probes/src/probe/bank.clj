(ns probe.bank
  (:require [com.rpl.rama.test :as rtest]
            [rama-challenges.harness :as h]
            [bank-transfer-module.protocol :as p]))

(defn run []
  (let [{:keys [module wrap-client]} ((requiring-resolve 'bank-transfer-module.module/create-module))]
    (with-open [ipc (rtest/create-ipc)]
      (rtest/launch-module! ipc module {:tasks 4 :threads 2})
      (let [c (wrap-client ipc)
            safe (fn [f] (try (f) :accepted (catch Throwable t (keyword "rejected-by-client"))))]
        ;; 1. negative transfer
        (p/deposit! c "a" 100) (h/wait-for-processing! c)
        (let [r (safe #(p/transfer! c "t1" "a" "b" -50))]
          (h/wait-for-processing! c)
          (prn {:probe :negative-transfer :client r :a (p/get-balance c "a") :b (p/get-balance c "b")}))
        ;; 2. negative deposit
        (let [r (safe #(p/deposit! c "n" -100))]
          (h/wait-for-processing! c)
          (prn {:probe :negative-deposit :client r :n (p/get-balance c "n")}))
        ;; 3. deposit then transfer appended back to back, no wait between
        (let [ok (atom 0) n 40]
          (dotimes [i n]
            (p/deposit! c (str "s" i) 100)
            (p/transfer! c (str "o" i) (str "s" i) (str "r" i) 100))
          (h/wait-for-processing! c)
          (dotimes [i n] (when (= 100 (p/get-balance c (str "r" i))) (swap! ok inc)))
          (prn {:probe :deposit-then-transfer :succeeded @ok :of n}))))))

(defn -main [& _] (run) (shutdown-agents) (System/exit 0))
