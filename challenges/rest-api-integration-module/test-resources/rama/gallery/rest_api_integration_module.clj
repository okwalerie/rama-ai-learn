(ns rama.gallery.rest-api-integration-module
  (:use [com.rpl.rama] [com.rpl.rama.path])
  (:require [taoensso.nippy :as nippy])
  (:import [com.rpl.rama.integration TaskGlobalObject]
           [org.asynchttpclient AsyncHttpClient Dsl]
           [org.asynchttpclient.netty NettyResponse]))
(defprotocol FetchTaskGlobalClient (task-global-client [this]))
(deftype AsyncHttpClientTaskGlobal
  [^{:unsynchronized-mutable true :tag AsyncHttpClient} client]
  TaskGlobalObject
  (prepareForTask [this task-id task-global-context] (set! client (Dsl/asyncHttpClient)))
  (close [this] (.close client))
  FetchTaskGlobalClient
  (task-global-client [this] client))
(nippy/extend-freeze AsyncHttpClientTaskGlobal ::async-http-client [o data-output])
(nippy/extend-thaw ::async-http-client [data-input] (AsyncHttpClientTaskGlobal. nil))
(defn http-get-future [^AsyncHttpClient client url]
  (-> client (.prepareGet url) .execute .toCompletableFuture))
(defn get-body [^NettyResponse response] (.getResponseBody response))
(defmodule RestAPIIntegrationModule [setup topologies]
  (declare-depot setup *get-depot (hash-by identity))
  (declare-object setup *http-client (AsyncHttpClientTaskGlobal. nil))
  (let [s (stream-topology topologies "get-http")]
    (declare-pstate s $$responses {String String})
    (<<sources s
      (source> *get-depot :> *url)
      (completable-future> (http-get-future (task-global-client *http-client) *url)
                           :> *netty-response)
      (get-body *netty-response :> *body)
      (local-transform> [(keypath *url) (termval *body)] $$responses))))
