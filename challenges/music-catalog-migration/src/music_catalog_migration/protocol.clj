(ns music-catalog-migration.protocol)

(defprotocol MusicCatalog
  (add-album! [this artist name songs]
    "Write or replace the album at [artist name]. Inputs are always vectors
     of strings. Reads before migration contain strings; reads after migration
     contain values with :name and :featured-artists. Return nil.")
  (album [this artist name]
    "Return {:name name :songs [...]} for the indexed album, or nil when the
     artist/name pair has no album. Values reflect the current module schema."))
