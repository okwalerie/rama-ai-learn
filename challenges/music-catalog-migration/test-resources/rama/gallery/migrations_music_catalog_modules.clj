(ns rama.gallery.migrations-music-catalog-modules
  (:use [com.rpl.rama] [com.rpl.rama.path])
  (:require [clojure.string :as str]))
(defrecord Album [artist name songs])
(defrecord Song [name featured-artists])
(def module-name "MusicCatalogModule")
(defmodule ModuleInstanceA {:module-name module-name} [setup topologies]
  (declare-depot setup *albums-depot (hash-by :artist))
  (let [mb (microbatch-topology topologies "albums")]
    (declare-pstate mb $$albums
      (map-schema String (map-schema String
        (fixed-keys-schema {:name String :songs (vector-schema String)})
        {:subindex? true})))
    (<<sources mb
      (source> *albums-depot :> %microbatch)
      (%microbatch :> {:keys [*artist *name *songs]})
      (hash-map :name *name :songs *songs :> *album)
      (local-transform> [(keypath *artist *name) (termval *album)] $$albums))))
(defn- parse-song [song-str]
  (let [[name features] (str/split song-str #"\s*(ft|feat)\.*")
        features (->> (str/split (or features "") #",") (mapv str/trim)
                      (filterv (complement empty?)))]
    (->Song name features)))
(defn- migrate-songs [album]
  (if (some-> album :songs first string?)
    (update album :songs (partial mapv parse-song)) album))
(defmodule ModuleInstanceB {:module-name module-name} [setup topologies]
  (declare-depot setup *albums-depot (hash-by :artist))
  (let [mb (microbatch-topology topologies "albums")]
    (declare-pstate mb $$albums
      (map-schema String (map-schema String
        (migrated (fixed-keys-schema {:name String :songs (vector-schema Song)})
                  "parse-song-data" migrate-songs)
        {:subindex? true})))
    (<<sources mb
      (source> *albums-depot :> %microbatch)
      (%microbatch :> {:keys [*artist *name *songs]})
      (mapv parse-song *songs :> *songs)
      (hash-map :name *name :songs *songs :> *album)
      (local-transform> [(keypath *artist *name) (termval *album)] $$albums))))
