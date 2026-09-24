(ns music-catalog-migration.module
  (:require [com.rpl.rama :refer [get-module-name foreign-depot foreign-pstate foreign-append! foreign-select-one]]
            [com.rpl.rama.path :refer [keypath]]
            [com.rpl.rama.test :as rt]
            [rama-challenges.harness :as harness]
            [music-catalog-migration.protocol :as p]
            [rama.gallery.migrations-music-catalog-modules :as src]))
(defn create-module []
  (let [count (atom 0)]
    {:module src/ModuleInstanceA
     :update-module src/ModuleInstanceB
     :wrap-client (fn [ipc]
       (let [name (get-module-name src/ModuleInstanceA)
             depot (foreign-depot ipc name "*albums-depot")
             albums (foreign-pstate ipc name "$$albums")]
         (reify p/MusicCatalog
           (add-album! [_ artist album-name songs]
             (foreign-append! depot (src/->Album artist album-name songs))
             (swap! count inc) nil)
           (album [_ artist album-name]
             (foreign-select-one (keypath artist album-name) albums))
           harness/Synchronizable
           (wait-for-processing! [_]
             (rt/wait-for-microbatch-processed-count ipc name "albums" @count)))))}))
