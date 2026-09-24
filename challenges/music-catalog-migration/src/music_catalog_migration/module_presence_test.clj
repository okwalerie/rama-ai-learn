(ns music-catalog-migration.module-presence-test
  (:require [clojure.test :refer [deftest is]]))

(deftest candidate-factory-contract
  (let [create-module (requiring-resolve 'music-catalog-migration.module/create-module)
        result (create-module)]
    (is (map? result))
    (is (some? (:module result)))
    (is (some? (:update-module result)))
    (is (fn? (:wrap-client result)))))
