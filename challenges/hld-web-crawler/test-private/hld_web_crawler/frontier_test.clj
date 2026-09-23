(ns hld-web-crawler.frontier-test
  (:require [clojure.test :refer :all]
            [com.rpl.rama.test :as rtest]
            [hld-web-crawler.protocol :as p]
            [rama-challenges.harness :as h]))

(defmacro with-frontier [[client other tasks] & body]
  `(let [factory# (requiring-resolve 'hld-web-crawler.module/create-module)
         result# (factory#)]
     (with-open [ipc# (rtest/create-ipc)]
       (rtest/launch-module! ipc# (:module result#) {:tasks ~tasks :threads ~tasks})
       (let [~client ((:wrap-client result#) ipc#)
             ~other ((:wrap-client result#) ipc#)]
         ~@body))))

(defn barrier [c] (h/wait-for-processing! c))
(defn url [host path] (str "https://" host path))

(defn capture-work [f]
  (let [counts (atom {})]
    (rtest/with-event-hook
      (fn [event-type _]
        (when (#{:rocks-read :rocks-iterator :rocks-iterator-read
                 :local-select :local-transform} event-type)
          (swap! counts update event-type (fnil inc 0))))
      (f))
    @counts))

(defn work [counts]
  (reduce + (vals counts)))

(deftest canonical-policy-and-boundaries
  (doseq [tasks [2 4]]
    (testing (str tasks " tasks")
      (with-frontier [c other tasks]
        (p/discover! c ["HTTPS://Example.COM#frag" "https://example.com/"
                        "https://example.com?" "https://example.com/a?x=1#z"
                        "http://example.com/a" "https://user@example.com/a"
                        "https://example.com:443/a" "https://example.com/ a"
                        "https://chars.example/?!~"
                        "https://example.com/😀" "https://example.com/?q=😀"])
        (barrier other)
        (is (= 3 (:queued (p/get-host other "EXAMPLE.COM"))))
        (is (= [(url "example.com" "/") (url "example.com" "/?" )
                (url "example.com" "/a?x=1")]
               (p/list-pending c "example.com" nil 10)))
        (is (nil? (p/get-url other "http://example.com/a")))
        (is (nil? (p/get-url other "https://example.com/😀")))
        (is (nil? (p/get-url other "https://example.com/?q=😀")))
        (is (= ["https://chars.example/?!~"]
               (p/list-pending other "chars.example" nil 10)))
        (p/set-host-policy! c "EXAMPLE.com" 5
                            [{:path-prefix "/" :allow? false}
                             {:path-prefix "/a" :allow? true}])
        (p/claim! c "example.com" "one" 10)
        (barrier other)
        (is (= {:status :granted :url (url "example.com" "/a?x=1")
                :fence 1 :lease-expires-at 40}
               (p/get-claim c "example.com" "one")))
        (is (= 0 (:queued (p/get-host c "example.com"))))
        (is (= :blocked (:status (p/get-url c (url "example.com" "/")))))
        (is (= [(url "example.com" "/a?x=1")]
               (p/list-pending c "example.com" nil 10)))
        (p/claim! c "example.com" "busy" 39)
        (p/claim! c "example.com" "stale" 40)
        (barrier other)
        (is (= :busy (:reason (p/get-claim c "example.com" "busy"))))
        (is (= 2 (:fence (p/get-claim c "example.com" "stale"))))
        (p/complete! c "example.com" 1 :fetched 41)
        (p/complete! c "example.com" 2 :fetched 70)
        (barrier other)
        (is (= :leased (:status (p/get-url c (url "example.com" "/a?x=1")))))
        (p/complete! c "example.com" 2 :failed 69)
        (barrier other)
        (is (= :failed (:status (p/get-url c (url "example.com" "/a?x=1")))))
        (is (nil? (:lease (p/get-host c "example.com"))))))))

(deftest empty-replay-and-stale-not-ready
  (doseq [tasks [2 4]]
    (testing (str tasks " tasks")
      (with-frontier [c other tasks]
        (p/claim! c "empty.example" "e1" 4)
        (p/claim! c "empty.example" "e2" 5)
        (barrier other)
        (is (= {:status :denied :reason :empty}
               (p/get-claim other "empty.example" "e1")))
        (is (= {:status :denied :reason :empty}
               (p/get-claim other "empty.example" "e2")))
        (p/discover! c [(url "stale.example" "/a") (url "stale.example" "/b")])
        (p/set-host-policy! c "stale.example" 100 [])
        (p/claim! c "stale.example" "first" 10)
        (p/claim! c "stale.example" "busy" 11)
        (barrier other)
        (is (= 1 (:fence (p/get-claim c "stale.example" "first"))))
        (is (= :busy (:reason (p/get-claim c "stale.example" "busy"))))
        (p/claim! c "stale.example" "later" 40)
        (barrier other)
        (is (= :not-ready (:reason (p/get-claim c "stale.example" "later"))))
        (is (= 2 (:queued (p/get-host other "stale.example"))))
        (is (nil? (:fence (p/get-url c (url "stale.example" "/a")))))
        (p/claim! c "stale.example" "first" 999999)
        (barrier other)
        (is (= 1 (:fence (p/get-claim c "stale.example" "first"))))
        (p/claim! c "stale.example" "ready" 110)
        (barrier other)
        (is (= {:status :granted :url (url "stale.example" "/a")
                :fence 2 :lease-expires-at 140}
               (p/get-claim c "stale.example" "ready")))
        (is (= 110 (:last-claim-at (p/get-host c "stale.example"))))
        (is (= 2 (:fence (p/get-host c "stale.example"))))))))

(deftest lex-order-and-second-client-writes
  (doseq [tasks [2 4]]
    (with-frontier [c other tasks]
      (let [host "lex.example" a (url host "/a") m (url host "/m") z (url host "/z")]
        (p/discover! c [z a m])
        (barrier other)
        (is (= [a m] (p/list-pending other host nil 2)))
        (is (= [z] (p/list-pending other host m 2)))
        (p/claim! c host "one" 10)
        (barrier other)
        (is (= a (:url (p/get-claim other host "one"))))
        (p/complete! other host 1 :fetched 11)
        (barrier c)
        (p/claim! c host "two" 11)
        (barrier other)
        (is (= m (:url (p/get-claim c host "two"))))
        (is (= [m z] (p/list-pending c host nil 2)))))))

(deftest robots-ties-query-and-nonretroactive-policy
  (doseq [tasks [2 4]]
    (with-frontier [c other tasks]
      (let [host "robots.example"
            blocked (url host "/a?b=1")
            allowed (url host "/a?x=1")
            longer (url host "/z/b")]
        (p/discover! c [longer blocked allowed])
        (p/set-host-policy! c host 1 [{:path-prefix "/a" :allow? false}
                                         {:path-prefix "/a?x" :allow? false}
                                         {:path-prefix "/a?x" :allow? true}
                                         {:path-prefix "/z/b" :allow? true}])
        (p/claim! c host "first" 10)
        (barrier other)
        (is (= allowed (:url (p/get-claim other host "first"))))
        (is (= :blocked (:status (p/get-url c blocked))))
        (p/set-host-policy! other host 9 [{:path-prefix "/" :allow? false}])
        (barrier c)
        (is (= :leased (:status (p/get-url c allowed))))
        (p/complete! other host 1 :fetched 12)
        (barrier c)
        (is (= :done (:status (p/get-url c allowed))))
        (is (= {:url allowed :host host :status :done
                :fence nil :lease-expires-at nil}
               (p/get-url c allowed)))
        (p/claim! c host "second" 18)
        (barrier other)
        (is (= :not-ready (:reason (p/get-claim c host "second"))))
        (p/set-host-policy! other host 9 [{:path-prefix "/" :allow? false}
                                          {:path-prefix "/z/b" :allow? true}])
        (barrier c)
        (p/claim! c host "third" 19)
        (barrier other)
        (is (= longer (:url (p/get-claim c host "third"))))
        (is (= 2 (:fence (p/get-host c host))))))))

(deftest skip-chunks-and-pagination
  (doseq [tasks [2 4]
          skipped [0 1 31 32 33 63 64 65 1000]]
    (testing (str tasks " tasks / " skipped " skips")
      (with-frontier [c other tasks]
        (let [host (str "skip" skipped ".example")
              blocked (mapv #(url host (format "/a%04d" %)) (range skipped))
              winner (url host "/b")
              tail (url host "/c")]
          (doseq [batch (partition-all 100 (concat blocked [winner tail]))]
            (p/discover! c (vec batch)))
          (p/set-host-policy! c host 1 [{:path-prefix "/a" :allow? false}
                                         {:path-prefix "/c" :allow? false}])
          (barrier other)
          (let [counts (capture-work #(do (p/claim! c host "grant" 10) (barrier other)))]
            (is (pos? (work counts)) (str "hook did not observe claim: " counts)))
          (is (= {:status :granted :url winner :fence 1 :lease-expires-at 40}
                 (p/get-claim other host "grant")))
          (is (= 1 (:queued (p/get-host c host))))
          (is (= :queued (:status (p/get-url c tail))))
          (is (= [winner tail] (p/list-pending c host nil 100)))
          (is (= [tail] (p/list-pending c host winner 1)))
          (is (= [winner tail] (p/list-pending c host (url host "/az") 100)))
          (doseq [u blocked]
            (is (= :blocked (:status (p/get-url c u))))))))))

(deftest all-blocked-and-policy-replacement
  (doseq [tasks [2 4]]
    (with-frontier [c other tasks]
      (let [host "policy.example" a (url host "/a") b (url host "/b")]
        (p/discover! c [a b])
        (p/set-host-policy! c host 1 [{:path-prefix "/" :allow? false}])
        (p/claim! c host "empty1" 10)
        (p/claim! c host "empty2" 11)
        (barrier other)
        (is (= :empty (:reason (p/get-claim c host "empty1"))))
        (is (= :empty (:reason (p/get-claim c host "empty2"))))
        (is (= [] (p/list-pending c host nil 10)))
        (p/set-host-policy! c host 1 [])
        (p/discover! c [a b])
        (barrier other)
        (is (= 0 (:queued (p/get-host c host))))
        (is (= :blocked (:status (p/get-url c a))))))))

(deftest exhausted-large-prefix-and-stale-requeue-order
  (doseq [tasks [2 4]]
    (with-frontier [c other tasks]
      (let [host "exhaust.example"
            blocked (mapv #(url host (format "/a%04d" %)) (range 1000))]
        (doseq [batch (partition-all 100 blocked)]
          (p/discover! c (vec batch)))
        (p/set-host-policy! c host 1 [{:path-prefix "/" :allow? false}])
        (p/claim! c host "all" 10)
        (p/claim! c host "again" 11)
        (barrier other)
        (is (= {:status :denied :reason :empty} (p/get-claim c host "all")))
        (is (= {:status :denied :reason :empty} (p/get-claim c host "again")))
        (is (= 0 (:queued (p/get-host c host))))
        (is (empty? (p/list-pending c host nil 100)))
        (is (= :blocked (:status (p/get-url c (last blocked))))))
      (let [host "requeue.example" m (url host "/m") a (url host "/a")]
        (p/discover! c [m])
        (p/claim! c host "initial" 10)
        (barrier other)
        (p/discover! other [a])
        (barrier c)
        (p/claim! c host "expired" 40)
        (barrier other)
        (is (= {:status :granted :url a :fence 2 :lease-expires-at 70}
               (p/get-claim other host "expired")))
        (is (= 1 (:queued (p/get-host c host))))
        (is (= :queued (:status (p/get-url c m))))
        (is (= [a m] (p/list-pending c host nil 2)))))))

(deftest pagination-depth-and-retired-history
  (doseq [tasks [2 4]]
    (with-frontier [c other tasks]
      (let [host "pages.example"
            retired (mapv #(url host (format "/a%04d" %)) (range 120))
            pending (mapv #(url host (format "/b%04d" %)) (range 230))]
        (doseq [batch (partition-all 100 (concat retired pending))]
          (p/discover! c (vec batch)))
        (p/set-host-policy! c host 1 [{:path-prefix "/a" :allow? false}])
        (p/claim! c host "head" 10)
        (barrier other)
        (is (= 229 (:queued (p/get-host c host)))) ; 230 pending minus one lease
        (let [counts (capture-work #(p/list-pending c host (pending 199) 1))]
          (is (pos? (work counts)) (str "hook did not observe pagination: " counts)))
        (is (= (subvec pending 0 100) (p/list-pending c host nil 100)))
        (is (= (subvec pending 100 200) (p/list-pending c host (pending 99) 100)))
        (is (= (subvec pending 200 230) (p/list-pending c host (pending 199) 100)))
        (is (= [(pending 151)] (p/list-pending c host (pending 150) 1)))
        (is (= (subvec pending 150 153)
               (p/list-pending c host (url host "/b0149x") 3)))
        (is (= [] (p/list-pending c host (url host "/z") 100)))
        (is (= :blocked (:status (p/get-url c (retired 119)))))))))

(deftest bounded-work-growth
  (doseq [tasks [2 4]]
    (with-frontier [c other tasks]
      (let [measure (fn [size]
                      (let [host (str "growth" size ".example")
                            retired (mapv #(url host (format "/a%04d" %)) (range size))
                            queued (mapv #(url host (format "/b%04d" %)) (range (+ size 300)))]
                        ;; Retire the old prefix, then grow claim history on an empty queue.
                        (doseq [batch (partition-all 100 retired)] (p/discover! c (vec batch)))
                        (p/set-host-policy! c host 1 [{:path-prefix "/a" :allow? false}
                                                        {:path-prefix "/b000" :allow? false}])
                        (p/claim! c host "retire" 1)
                        (doseq [i (range size)] (p/claim! c host (str "history" i) 2))
                        (doseq [batch (partition-all 100 queued)] (p/discover! c (vec batch)))
                        (barrier other)
                        (is (= size (count (filter #(= :blocked (:status (p/get-url c %))) retired))))
                        (is (= {:status :denied :reason :empty}
                               (p/get-claim other host (str "history" (dec size)))))
                        (let [cursor (queued (+ size 150))
                              page (fn [limit] (let [result (atom nil)
                                                     counts (capture-work #(reset! result (p/list-pending c host cursor limit)))]
                                                 (is (= (subvec queued (+ size 151) (+ size 151 limit)) @result))
                                                 (work counts)))
                              pages (mapv page [1 20 100])
                              claim-counts (capture-work #(do (p/claim! c host "measured" 10) (barrier other)))]
                          (is (= (queued 10) (:url (p/get-claim other host "measured"))))
                          (is (= :blocked (:status (p/get-url c (queued 9)))))
                          {:claim (work claim-counts) :pages pages})))]
        (let [small (measure 220)
              large (measure 1000)]
          (println "paired frontier work" tasks "tasks" "220 -> 1000 retired/claims; 520 -> 1300 queued; 10 skips" small large)
          (doseq [k [:claim]]
            (is (pos? (k small)))
            (is (<= (k large) (+ (k small) 100))
                (str "fixed-skip work grew with queued/retired/claim history: " small " -> " large)))
          (doseq [[a b] (map vector (:pages small) (:pages large))]
            (is (pos? a))
            (is (<= b (+ a 100))
                (str "fixed-limit page grew with population/history: " small " -> " large))))))))

(deftest durable-state-after-unchanged-module-update
  (doseq [tasks [2 4]]
    (let [result ((requiring-resolve 'hld-web-crawler.module/create-module))]
      (with-open [ipc (rtest/create-ipc)]
        (rtest/launch-module! ipc (:module result) {:tasks tasks :threads tasks})
        (let [c ((:wrap-client result) ipc)
              host "update.example" a (url host "/a") b (url host "/b")]
          (p/discover! c [a b])
          (p/set-host-policy! c host 3 [])
          (p/claim! c host "before" 10)
          (barrier c)
          (is (= {:status :granted :url a :fence 1 :lease-expires-at 40}
                 (p/get-claim c host "before")))
          (rtest/update-module! ipc (:module result))
          (let [fresh ((:wrap-client result) ipc)]
            (is (= {:status :granted :url a :fence 1 :lease-expires-at 40}
                   (p/get-claim fresh host "before")))
            (is (= {:host host :delay 3 :rules [] :queued 1 :fence 1
                    :last-claim-at 10 :lease {:url a :fence 1 :expires-at 40}}
                   (p/get-host fresh host)))
            (is (= [a b] (p/list-pending fresh host nil 10)))
            (p/discover! fresh [a b])
            (p/claim! fresh host "before" 999999)
            (p/claim! fresh host "after" 40)
            (barrier fresh)
            (is (= {:status :granted :url a :fence 1 :lease-expires-at 40}
                   (p/get-claim fresh host "before")))
            (is (= {:status :granted :url a :fence 2 :lease-expires-at 70}
                   (p/get-claim fresh host "after")))
            (is (= 1 (:queued (p/get-host fresh host))))
            (p/complete! fresh host 1 :fetched 41)
            (p/complete! fresh host 2 :failed 69)
            (barrier fresh)
            (is (= :failed (:status (p/get-url fresh a))))
            (is (= [b] (p/list-pending fresh host nil 10)))
            (is (= 2 (:fence (p/get-host fresh host))))))))))
