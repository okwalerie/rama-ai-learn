(ns rest-api-integration-module.module
  (:require [com.rpl.rama :refer [get-module-name foreign-depot foreign-pstate foreign-append! foreign-select-one]]
            [com.rpl.rama.path :refer [keypath]]
            [rama-challenges.harness :as harness]
            [rest-api-integration-module.protocol :as p]
            [rama.gallery.rest-api-integration-module :as src]))
(defn create-module []
  {:module src/RestAPIIntegrationModule
   :wrap-client (fn [ipc]
     (let [name (get-module-name src/RestAPIIntegrationModule)
           depot (foreign-depot ipc name "*get-depot")
           responses (foreign-pstate ipc name "$$responses")
           pending (atom [])]
       (reify p/RestApiIntegrationModule
         (fetch! [_ url]
           (swap! pending conj (future (foreign-append! depot url)))
           nil)
         (get-body [_ url] (foreign-select-one (keypath url) responses))
         harness/Synchronizable
         (wait-for-processing! [_]
           (doseq [append @pending] @append)
           (reset! pending [])))))})
