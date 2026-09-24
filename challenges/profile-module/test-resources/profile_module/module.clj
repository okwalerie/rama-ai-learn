(ns profile-module.module
  (:require [com.rpl.rama :refer [get-module-name foreign-depot foreign-pstate foreign-append! foreign-select-one]]
            [com.rpl.rama.path :refer [keypath]]
            [rama-challenges.harness :as harness]
            [profile-module.protocol :as p]
            [rama.gallery.profile-module :as src]))

(defn create-module []
  {:module src/ProfileModule
   :wrap-client
   (fn [ipc]
     (let [name (get-module-name src/ProfileModule)
           registrations (foreign-depot ipc name "*registration-depot")
           edits (foreign-depot ipc name "*profile-edits-depot")
           profiles (foreign-pstate ipc name "$$profiles")]
       (reify p/ProfileModule
         (register! [_ uuid username pwd-hash]
           (get (foreign-append! registrations (src/->Registration uuid username pwd-hash)) "profiles"))
         (edit-profile! [_ user-id values]
           (foreign-append! edits (src/->ProfileEdits user-id values)) nil)
         (get-profile [_ user-id] (foreign-select-one (keypath user-id) profiles))
         harness/Synchronizable
         (wait-for-processing! [_] nil))))})
