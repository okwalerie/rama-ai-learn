(ns hld-url-shortener.functional-test-support
  "Functional private tests for hld-url-shortener. Every scenario runs at a
   fixed task count (the challenge test runs 2 and 4) through two wrappers
   obtained from the same create-module result. Expected values are
   hand-derived from the protocol, never computed from the module."
  (:require
   [clojure.test :refer [is testing]]
   [com.rpl.rama.test :as rtest]
   [hld-url-shortener.protocol :as p]
   [rama-challenges.harness :as harness]))

(def ^:private max-tick 999999999999) ; 10^12 - 1

(defn test-module-functional
  [create-module-fn tasks]
  (let [{:keys [module wrap-client]} (create-module-fn)]
    (with-open [ipc (rtest/create-ipc)]
      (rtest/launch-module! ipc module {:tasks tasks :threads 2})
      (let [a (wrap-client ipc)
            b (wrap-client ipc)
            ;; barrier is always taken through the OTHER wrapper
            sync-a! (fn [] (harness/wait-for-processing! b))
            sync-b! (fn [] (harness/wait-for-processing! a))]

        (testing "write methods return nil"
          (is (nil? (p/create-link! a "nilret" "r1" "https://n.example/1" nil)))
          (is (nil? (p/delete-link! a "nilret")))
          (is (nil? (p/block-link! a "nilret")))
          (is (nil? (p/unblock-link! a "nilret")))
          (is (nil? (p/record-click! a "nilret" "c1")))
          (sync-a!))

        (testing "missing alias reads"
          (is (= {:status :missing} (p/resolve-alias b "never" 0)))
          (is (= {:status :missing} (p/resolve-alias b "never" max-tick)))
          (is (= 0 (p/get-click-count b "never")))
          (is (nil? (p/get-create-outcome b "never" "r1"))))

        (testing "create, resolve, outcome, first request wins"
          (p/create-link! a "alpha" "r1" "https://a.example/one" nil)
          (sync-a!)
          (is (= {:status :active :target-url "https://a.example/one" :expires-at nil}
                 (p/resolve-alias b "alpha" 0)))
          (is (= {:status :active :target-url "https://a.example/one" :expires-at nil}
                 (p/resolve-alias b "alpha" max-tick)))
          (is (= {:outcome :created} (p/get-create-outcome b "alpha" "r1")))
          (is (nil? (p/get-create-outcome b "alpha" "r2")))
          (is (= 0 (p/get-click-count b "alpha")))
          ;; replay of r1 with a changed body: nothing changes
          (p/create-link! b "alpha" "r1" "https://a.example/CHANGED" 5)
          (sync-b!)
          (is (= {:outcome :created} (p/get-create-outcome a "alpha" "r1")))
          (is (= {:status :active :target-url "https://a.example/one" :expires-at nil}
                 (p/resolve-alias a "alpha" 10)))
          ;; second request-id on a taken alias: rejected, recorded, replayed
          (p/create-link! a "alpha" "r2" "https://a.example/two" nil)
          (sync-a!)
          (is (= {:outcome :rejected :reason :alias-taken}
                 (p/get-create-outcome b "alpha" "r2")))
          (p/create-link! a "alpha" "r2" "https://a.example/three" 7)
          (sync-a!)
          (is (= {:outcome :rejected :reason :alias-taken}
                 (p/get-create-outcome b "alpha" "r2")))
          (is (= {:status :active :target-url "https://a.example/one" :expires-at nil}
                 (p/resolve-alias b "alpha" 100)))
          ;; same request-id on another alias is independent
          (p/create-link! b "beta" "r1" "https://b.example/" nil)
          (sync-b!)
          (is (= {:outcome :created} (p/get-create-outcome a "beta" "r1")))
          (is (= {:status :active :target-url "https://b.example/" :expires-at nil}
                 (p/resolve-alias a "beta" 0))))

        (testing "grammar boundaries: 1-char alias, 8-char url"
          (p/create-link! a "z" "R" "https://" 0)
          (sync-a!)
          (is (= {:status :expired :target-url "https://" :expires-at 0}
                 (p/resolve-alias b "z" 0)))
          (is (= {:outcome :created} (p/get-create-outcome b "z" "R"))))

        (testing "grammar upper bounds: 32-char alias, 64-char ids, 2048-char url, max expiry tick"
          (let [alias32 (str "u" (apply str (repeat 30 "9")) "-")          ; 32 chars of [a-z0-9-]
                rid64   (str "Req-" (apply str (repeat 60 "X")))            ; 64 chars of [A-Za-z0-9-]
                cid64   (str "Clk-" (apply str (repeat 60 "y")))            ; 64 chars
                url2048 (str "https://" (apply str (repeat 2040 "u")))]     ; 2048 chars
            (is (= 32 (count alias32)))
            (is (= 64 (count rid64) (count cid64)))
            (is (= 2048 (count url2048)))
            (p/create-link! b alias32 rid64 url2048 max-tick)
            (p/record-click! b alias32 cid64)
            (p/record-click! b alias32 cid64)
            (sync-b!)
            (is (= {:status :active :target-url url2048 :expires-at max-tick}
                   (p/resolve-alias a alias32 (dec max-tick))))
            (is (= {:status :expired :target-url url2048 :expires-at max-tick}
                   (p/resolve-alias a alias32 max-tick)))
            (is (= {:outcome :created} (p/get-create-outcome a alias32 rid64)))
            (is (nil? (p/get-create-outcome a alias32 (subs rid64 1))))
            (is (= 1 (p/get-click-count a alias32)))
            ;; a 31-char prefix of the alias is a different, missing alias
            (is (= {:status :missing} (p/resolve-alias a (subs alias32 1) 0)))))

        (testing "exact expiry boundary"
          (p/create-link! a "exp" "r1" "https://e.example/" 100)
          (sync-a!)
          (is (= {:status :active :target-url "https://e.example/" :expires-at 100}
                 (p/resolve-alias b "exp" 0)))
          (is (= {:status :active :target-url "https://e.example/" :expires-at 100}
                 (p/resolve-alias b "exp" 99)))
          (is (= {:status :expired :target-url "https://e.example/" :expires-at 100}
                 (p/resolve-alias b "exp" 100)))
          (is (= {:status :expired :target-url "https://e.example/" :expires-at 100}
                 (p/resolve-alias b "exp" max-tick)))
          ;; expired alias is still reserved
          (p/create-link! b "exp" "r2" "https://e.example/again" nil)
          (sync-b!)
          (is (= {:outcome :rejected :reason :alias-taken}
                 (p/get-create-outcome a "exp" "r2")))
          (is (= {:status :expired :target-url "https://e.example/" :expires-at 100}
                 (p/resolve-alias a "exp" 100))))

        (testing "block outranks expired; unblock restores; idempotent"
          (p/block-link! a "exp")
          (sync-a!)
          (is (= {:status :blocked :target-url "https://e.example/" :expires-at 100}
                 (p/resolve-alias b "exp" 100)))
          (is (= {:status :blocked :target-url "https://e.example/" :expires-at 100}
                 (p/resolve-alias b "exp" 0)))
          (p/block-link! b "exp")
          (sync-b!)
          (is (= :blocked (:status (p/resolve-alias a "exp" 0))))
          (p/unblock-link! a "exp")
          (sync-a!)
          (is (= {:status :expired :target-url "https://e.example/" :expires-at 100}
                 (p/resolve-alias b "exp" 100)))
          (is (= {:status :active :target-url "https://e.example/" :expires-at 100}
                 (p/resolve-alias b "exp" 99)))
          (p/unblock-link! a "exp")
          (sync-a!)
          (is (= :active (:status (p/resolve-alias b "exp" 0))))
          ;; last processed flag wins for interleaved block/unblock from one client
          (p/block-link! a "exp")
          (p/unblock-link! a "exp")
          (p/block-link! a "exp")
          (sync-a!)
          (is (= :blocked (:status (p/resolve-alias b "exp" 0))))
          (p/unblock-link! a "exp")
          (sync-a!)
          (is (= :active (:status (p/resolve-alias b "exp" 0)))))

        (testing "block/unblock/delete on a missing alias leave no trace"
          (p/block-link! a "ghost")
          (p/unblock-link! a "ghost")
          (p/delete-link! a "ghost")
          (sync-a!)
          (is (= {:status :missing} (p/resolve-alias b "ghost" 0)))
          (is (= 0 (p/get-click-count b "ghost")))
          (p/create-link! b "ghost" "r1" "https://g.example/" nil)
          (sync-b!)
          (is (= {:status :active :target-url "https://g.example/" :expires-at nil}
                 (p/resolve-alias a "ghost" 0)))
          (is (= {:outcome :created} (p/get-create-outcome a "ghost" "r1"))))

        (testing "delete is irreversible and outranks block and expiry"
          (p/block-link! a "exp")
          (sync-a!)
          (p/delete-link! a "exp")
          (sync-a!)
          (is (= {:status :deleted :target-url "https://e.example/" :expires-at 100}
                 (p/resolve-alias b "exp" 100)))
          (is (= {:status :deleted :target-url "https://e.example/" :expires-at 100}
                 (p/resolve-alias b "exp" 0)))
          (p/unblock-link! b "exp")
          (p/block-link! b "exp")
          (p/delete-link! b "exp")
          (sync-b!)
          (is (= :deleted (:status (p/resolve-alias a "exp" 0))))
          (p/create-link! a "exp" "r3" "https://e.example/after-delete" nil)
          (sync-a!)
          (is (= {:outcome :rejected :reason :alias-taken}
                 (p/get-create-outcome b "exp" "r3")))
          (is (= {:outcome :created} (p/get-create-outcome b "exp" "r1")))
          (is (= :deleted (:status (p/resolve-alias b "exp" 0)))))

        (testing "clicks: dropped when missing, deduplicated, status-independent"
          (p/record-click! a "later" "c1")
          (sync-a!)
          (is (= 0 (p/get-click-count b "later")))
          (is (= {:status :missing} (p/resolve-alias b "later" 0)))
          (p/create-link! a "later" "r1" "https://l.example/" 50)
          (sync-a!)
          (is (= 0 (p/get-click-count b "later")))
          ;; the dropped observation left no trace: c1 is new now
          (p/record-click! b "later" "c1")
          (sync-b!)
          (is (= 1 (p/get-click-count a "later")))
          (p/record-click! a "later" "c1")
          (p/record-click! b "later" "c1")
          (sync-a!)
          (is (= 1 (p/get-click-count b "later")))
          (p/record-click! a "later" "c2")
          (sync-a!)
          (is (= 2 (p/get-click-count b "later")))
          ;; click-id reused on another alias counts independently
          (p/record-click! a "alpha" "c1")
          (sync-a!)
          (is (= 1 (p/get-click-count b "alpha")))
          (is (= 2 (p/get-click-count b "later")))
          ;; expired (now >= 50): still counted
          (p/record-click! b "later" "c3")
          (sync-b!)
          (is (= :expired (:status (p/resolve-alias a "later" 50))))
          (is (= 3 (p/get-click-count a "later")))
          ;; blocked: still counted
          (p/block-link! a "later")
          (p/record-click! a "later" "c4")
          (sync-a!)
          (is (= :blocked (:status (p/resolve-alias b "later" 0))))
          (is (= 4 (p/get-click-count b "later")))
          ;; late clicks after delete: counted; duplicates still deduplicated
          (p/delete-link! a "later")
          (sync-a!)
          (p/record-click! b "later" "c5")
          (p/record-click! b "later" "c2")
          (p/record-click! b "later" "c6")
          (sync-b!)
          (is (= {:status :deleted :target-url "https://l.example/" :expires-at 50}
                 (p/resolve-alias a "later" 0)))
          (is (= 6 (p/get-click-count a "later")))
          (is (= {:outcome :created} (p/get-create-outcome a "later" "r1")))
          ;; reads do not mutate
          (dotimes [_ 3] (p/resolve-alias a "later" 0) (p/get-click-count a "later"))
          (is (= 6 (p/get-click-count b "later"))))

        (testing "one-client invocation order without intermediate barriers"
          (p/create-link! a "seq" "r1" "https://s.example/" nil)
          (p/record-click! a "seq" "k1")
          (p/block-link! a "seq")
          (p/record-click! a "seq" "k2")
          (p/delete-link! a "seq")
          (p/record-click! a "seq" "k1")
          (p/create-link! a "seq" "r2" "https://s.example/2" nil)
          (sync-a!)
          (is (= {:status :deleted :target-url "https://s.example/" :expires-at nil}
                 (p/resolve-alias b "seq" 0)))
          (is (= 2 (p/get-click-count b "seq")))
          (is (= {:outcome :created} (p/get-create-outcome b "seq" "r1")))
          (is (= {:outcome :rejected :reason :alias-taken}
                 (p/get-create-outcome b "seq" "r2")))
          ;; sequential creates from one client: the first issued wins
          (p/create-link! b "order" "r1" "https://o.example/1" nil)
          (p/create-link! b "order" "r2" "https://o.example/2" nil)
          (p/create-link! b "order" "r3" "https://o.example/3" nil)
          (sync-b!)
          (is (= {:outcome :created} (p/get-create-outcome a "order" "r1")))
          (is (= {:outcome :rejected :reason :alias-taken} (p/get-create-outcome a "order" "r2")))
          (is (= {:outcome :rejected :reason :alias-taken} (p/get-create-outcome a "order" "r3")))
          (is (= "https://o.example/1" (:target-url (p/resolve-alias a "order" 0)))))

        (testing "concurrent creates from two clients: exactly one created"
          (p/create-link! a "race" "ra" "https://r.example/a" 10)
          (p/create-link! b "race" "rb" "https://r.example/b" 20)
          (sync-a!)
          (let [oa (p/get-create-outcome a "race" "ra")
                ob (p/get-create-outcome b "race" "rb")
                res (p/resolve-alias a "race" 0)]
            (is (= #{{:outcome :created} {:outcome :rejected :reason :alias-taken}}
                   (hash-set oa ob)))
            (is (= :active (:status res)))
            (is (if (= oa {:outcome :created})
                  (= res {:status :active :target-url "https://r.example/a" :expires-at 10})
                  (= res {:status :active :target-url "https://r.example/b" :expires-at 20}))))
          ;; delete racing create across clients: only the two sequential
          ;; outcomes are acceptable (delete first: no effect, link active;
          ;; create first: link deleted). The create outcome is :created either way.
          (p/create-link! a "dc-race" "r1" "https://r.example/dc" nil)
          (p/delete-link! b "dc-race")
          (sync-a!)
          (sync-b!)
          (is (= {:outcome :created} (p/get-create-outcome b "dc-race" "r1")))
          (is (contains? #{{:status :active  :target-url "https://r.example/dc" :expires-at nil}
                           {:status :deleted :target-url "https://r.example/dc" :expires-at nil}}
                         (p/resolve-alias a "dc-race" 0)))
          ;; after a barrier the delete is ordered and the alias is deleted for good
          (p/delete-link! a "dc-race")
          (sync-a!)
          (is (= :deleted (:status (p/resolve-alias b "dc-race" 0))))
          (p/create-link! b "dc-race" "r2" "https://r.example/dc2" nil)
          (sync-b!)
          (is (= {:outcome :rejected :reason :alias-taken} (p/get-create-outcome a "dc-race" "r2"))))

        (testing "many request-ids on one alias: each outcome exact"
          (p/create-link! a "many" "req-0" "https://m.example/0" nil)
          ;; barrier before the competing cross-client creates: writes from
          ;; different clients may serialize in either order, so req-0 is only
          ;; guaranteed to win once it has been processed
          (sync-a!)
          (doseq [i (range 1 40)]
            (p/create-link! (if (odd? i) a b) "many" (str "req-" i) (str "https://m.example/" i) nil))
          (sync-a!)
          (sync-b!)
          (is (= {:outcome :created} (p/get-create-outcome b "many" "req-0")))
          (is (every? #(= {:outcome :rejected :reason :alias-taken}
                          (p/get-create-outcome a "many" (str "req-" %)))
                      (range 1 40)))
          (is (nil? (p/get-create-outcome a "many" "req-40")))
          (is (= "https://m.example/0" (:target-url (p/resolve-alias b "many" 0)))))

        (testing "distinct concurrent observations from two clients"
          (p/create-link! a "viral" "r1" "https://v.example/" nil)
          (sync-a!)
          (doseq [i (range 60)]
            (p/record-click! (if (even? i) a b) "viral" (str "v" (mod i 45))))
          (sync-b!)
          (sync-a!)
          (is (= 45 (p/get-click-count a "viral")))
          (is (= 45 (p/get-click-count b "viral"))))))))
