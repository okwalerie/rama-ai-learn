(ns music-catalog-migration.migration-test
  (:require [clojure.test :as t :refer [deftest is]]
            [com.rpl.rama.test :as rt]
            [music-catalog-migration.protocol :as p]
            [rama-challenges.harness :as h]))
(defn- factory [] (requiring-resolve 'music-catalog-migration.module/create-module))
(deftest one-update-lifecycle
  (doseq [tasks [2 4]]
    (let [{:keys [module update-module wrap-client]} ((factory))]
      (with-open [ipc (rt/create-ipc)]
        (rt/launch-module! ipc module {:tasks tasks :threads 2})
        (let [c (wrap-client ipc)]
          (is (nil? (p/album c "Missing Artist" "Missing Album")))
          (is (nil? (p/add-album! c "Artist" "Old"
                                  ["Song ft. A, B" "No feature" "  Weird feat.. C ,, D "])))
          (h/wait-for-processing! c)
          (is (= {:name "Old" :songs ["Song ft. A, B" "No feature" "  Weird feat.. C ,, D "]}
                 (p/album c "Artist" "Old")))
          (p/add-album! c "Artist" "Markers" ["Song ft. A feat. B" "UPPER FT. Guest"])
          (h/wait-for-processing! c)
          (rt/update-module! ipc update-module)
          (is (= [{:name "Song" :featured-artists ["A"]}
                  {:name "UPPER FT. Guest" :featured-artists []}]
                 (mapv #(into {} %) (:songs (p/album c "Artist" "Markers")))))
          (is (= [{:name "Song" :featured-artists ["A" "B"]}
                  ;; `feat` is matched even inside the ordinary word "feature".
                  {:name "No" :featured-artists ["ure"]}
                  {:name "  Weird" :featured-artists ["C" "D"]}]
                 (mapv #(select-keys % [:name :featured-artists])
                       (:songs (p/album c "Artist" "Old")))))
          (is (nil? (p/add-album! c "New Artist" "New"
                                  ["Song ft. A, B" "No feature" "  Weird feat.. C ,, D "])))
          (h/wait-for-processing! c)
          (let [new-value (p/album c "New Artist" "New")]
            (is (= {:name "New"
                    :songs [{:name "Song" :featured-artists ["A" "B"]}
                            {:name "No" :featured-artists ["ure"]}
                            {:name "  Weird" :featured-artists ["C" "D"]}]}
                   (update new-value :songs
                           #(mapv (fn [song] (select-keys song [:name :featured-artists])) %)))
            (is (= (:songs (p/album c "Artist" "Old")) (:songs new-value))))
          (is (nil? (p/add-album! c "New Artist" "New" ["Replacement ft. One, , Two"])))
          (h/wait-for-processing! c)
            (let [replacement {:name "New" :songs [{:name "Replacement" :featured-artists ["One" "Two"]}]}
                  plain-album #(update (p/album c "New Artist" "New") :songs
                                       (fn [songs] (mapv (fn [song] (into {} song)) songs)))]
              (is (= replacement (plain-album)))
          ;; Reapplying the same generation must retain migrated and replacement values.
              (rt/update-module! ipc update-module)
              (is (= replacement (plain-album)))
              (is (= [{:name "Song" :featured-artists ["A" "B"]}
                      {:name "No" :featured-artists ["ure"]}
                      {:name "  Weird" :featured-artists ["C" "D"]}]
                     (mapv #(select-keys % [:name :featured-artists])
                           (:songs (p/album c "Artist" "Old"))))))))))))
