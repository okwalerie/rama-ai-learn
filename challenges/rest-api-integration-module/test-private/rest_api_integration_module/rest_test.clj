(ns rest-api-integration-module.rest-test
  (:require [clojure.test :as t :refer [deftest is]]
            [com.rpl.rama.test :as rt]
            [rest-api-integration-module.protocol :as p]
            [rama-challenges.harness :as h])
  (:import [com.sun.net.httpserver HttpServer HttpHandler]
           [java.net InetSocketAddress]))
(defn- factory [] (requiring-resolve 'rest-api-integration-module.module/create-module))
(defn fixture []
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/ok" (reify HttpHandler (handle [_ ex]
      (let [body (.getBytes "fixture-ok")]
        (.sendResponseHeaders ex 200 (alength body))
        (with-open [out (.getResponseBody ex)] (.write out body))))))
    (.createContext server "/unavailable" (reify HttpHandler (handle [_ ex]
      (let [body (.getBytes "http-error-body")]
        (.sendResponseHeaders ex 503 (alength body))
        (with-open [out (.getResponseBody ex)] (.write out body))))))
    (let [calls (atom 0)]
      (.createContext server "/same" (reify HttpHandler (handle [_ ex]
        (let [body (.getBytes (if (= 1 (swap! calls inc)) "first-body" "second-body"))]
          (.sendResponseHeaders ex 200 (alength body))
          (with-open [out (.getResponseBody ex)] (.write out body)))))))
    (.createContext server "/slow" (reify HttpHandler (handle [_ ex]
      (Thread/sleep 5000)
      (let [body (.getBytes "slow-body")]
        (.sendResponseHeaders ex 200 (alength body))
        (with-open [out (.getResponseBody ex)] (.write out body))))))
    (.start server)
    [server (str "http://127.0.0.1:" (.getPort (.getAddress server)))]))
(deftest bounded-local-http-fixture
  (doseq [tasks [2 4]]
    (let [[server root] (fixture)]
      (try
        (let [{:keys [module wrap-client]} ((factory))]
          (with-open [ipc (rt/create-ipc)]
            (rt/launch-module! ipc module {:tasks tasks :threads 2})
            (let [c (wrap-client ipc) ok (str root "/ok") bad (str root "/unavailable")
                  same (str root "/same") slow (str root "/slow")]
              (is (nil? (p/get-body c ok)))
              (is (nil? (p/fetch! c ok)))
              (h/wait-for-processing! c)
              (is (= "fixture-ok" (p/get-body c ok)))
              (is (nil? (p/fetch! c bad)))
              (h/wait-for-processing! c)
              ;; A completed HTTP 503 stores its body; it is not a transport retry.
              (is (= "http-error-body" (p/get-body c bad)))
              (is (nil? (p/fetch! c same)))
              (h/wait-for-processing! c)
              (is (= "first-body" (p/get-body c same)))
              (is (nil? (p/fetch! c same)))
              (h/wait-for-processing! c)
              (is (= "second-body" (p/get-body c same)))
              (let [started (System/nanoTime)]
                (is (nil? (p/fetch! c slow)))
                (is (< (/ (- (System/nanoTime) started) 1000000) 3000)
                    "fetch! returns without waiting for the delayed HTTP response"))
              (h/wait-for-processing! c)
              (is (= "slow-body" (p/get-body c slow))))))
        (finally (.stop server 0))))))
