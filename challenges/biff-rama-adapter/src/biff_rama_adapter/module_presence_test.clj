(ns biff-rama-adapter.module-presence-test
  (:require [clojure.test :refer [deftest is]]))

(deftest candidate-factory-contract
  (let [create-module (requiring-resolve 'biff-rama-adapter.module/create-module)
        {:keys [module biff-module]} (create-module)
        adapter (biff-module)]
    (is (some? module))
    (is (fn? biff-module))
    (is (= :biff.rama/module (:biff.core/id adapter)))
    (is (fn? (:biff.core/start adapter)))
    (is (fn? (get-in adapter [:biff.fx/handlers :biff.rama.fx/transact])))
    (is (fn? (get-in adapter [:biff.fx/handlers :biff.rama.fx/entity])))))
