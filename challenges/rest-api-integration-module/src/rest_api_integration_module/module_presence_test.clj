(ns rest-api-integration-module.module-presence-test
  (:require [clojure.test :refer [deftest is]]))

(deftest candidate-factory-contract
  (let [create-module (requiring-resolve 'rest-api-integration-module.module/create-module)
        result (create-module)]
    (is (map? result))
    (is (some? (:module result)))
    (is (fn? (:wrap-client result)))))
