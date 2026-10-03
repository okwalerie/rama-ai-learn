(ns music-catalog-migration.nfr-test
  "NFR test: after the update, every existing album reads in the new shape
   immediately, with no processing barrier in between, at a scale where a
   design that rebuilds state by replaying history would still be catching
   up."
  (:require [clojure.test :refer [deftest is testing]]
            [com.rpl.rama.test :as rt]
            [music-catalog-migration.protocol :as p]
            [rama-challenges.harness :as h]))

(defn- songs [artist-n album-n]
  [(str "Track " album-n " ft. Guest " artist-n ", Other")
   (str "Plain " album-n)])

(defn- expected [artist-n album-n]
  [{:name (str "Track " album-n) :featured-artists [(str "Guest " artist-n) "Other"]}
   {:name (str "Plain " album-n) :featured-artists []}])

(deftest five-hundred-albums-read-migrated-immediately
  (doseq [tasks [2 4]]
    (testing (str tasks " tasks")
      (let [{:keys [module update-module wrap-client]}
            ((requiring-resolve 'music-catalog-migration.module/create-module))]
        (with-open [ipc (rt/create-ipc)]
          (rt/launch-module! ipc module {:tasks tasks :threads tasks})
          (let [c (wrap-client ipc)
                albums (for [a (range 50) n (range 10)] [a n])]
            (doseq [[a n] albums]
              (p/add-album! c (str "Artist " a) (str "Album " n) (songs a n)))
            (h/wait-for-processing! c)
            (is (= (songs 7 3) (:songs (p/album c "Artist 7" "Album 3"))))
            (rt/update-module! ipc update-module)
            ;; No wait-for-processing! here: the migration must already apply.
            (let [reads (mapv (fn [[a n]] [[a n] (p/album c (str "Artist " a) (str "Album " n))])
                              albums)
                  wrong (remove (fn [[[a n] v]]
                                  (= {:name (str "Album " n) :songs (expected a n)}
                                     (some-> v (update :songs #(mapv (fn [s] (select-keys s [:name :featured-artists])) %)))))
                                reads)]
              (is (= 500 (count reads)))
              (is (empty? wrong)
                  (str (count wrong) " of 500 albums did not read migrated right after the update, e.g. "
                       (vec (take 3 wrong)))))))))))
