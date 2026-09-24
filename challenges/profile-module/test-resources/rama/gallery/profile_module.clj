(ns rama.gallery.profile-module
  (:use [com.rpl.rama] [com.rpl.rama.path])
  (:require [com.rpl.rama.ops :as ops])
  (:import [com.rpl.rama.helpers ModuleUniqueIdPState]))
(defrecord Registration [uuid username pwd-hash])
(defrecord ProfileEdit [field value])
(defrecord ProfileEdits [user-id edits])
(defn display-name-edit [value] (->ProfileEdit :display-name value))
(defn pwd-hash-edit [value] (->ProfileEdit :pwd-hash value))
(defn height-inches-edit [value] (->ProfileEdit :height-inches value))
(defmodule ProfileModule [setup topologies]
  (declare-depot setup *registration-depot (hash-by :username))
  (declare-depot setup *profile-edits-depot (hash-by :user-id))
  (let [s (stream-topology topologies "profiles")
        id-gen (ModuleUniqueIdPState. "$$id")]
    (declare-pstate s $$username->registration
      {String (fixed-keys-schema {:user-id Long :uuid String})})
    (declare-pstate s $$profiles
      {Long (fixed-keys-schema {:username String :pwd-hash String
                                :display-name String :height-inches Long})})
    (.declarePState id-gen s)
    (<<sources s
      (source> *registration-depot :> {:keys [*uuid *username *pwd-hash]})
      (local-select> (keypath *username) $$username->registration
                     :> {*curr-uuid :uuid :as *curr-info})
      (<<if (or> (nil? *curr-info) (= *curr-uuid *uuid))
        (java-macro! (.genId id-gen "*user-id"))
        (local-transform> [(keypath *username)
                           (multi-path [:user-id (termval *user-id)]
                                       [:uuid (termval *uuid)])]
                          $$username->registration)
        (|hash *user-id)
        (local-transform> [(keypath *user-id)
                           (multi-path [:username (termval *username)]
                                       [:pwd-hash (termval *pwd-hash)])]
                          $$profiles)
        (ack-return> *user-id))
      (source> *profile-edits-depot :> {:keys [*user-id *edits]})
      (ops/explode *edits :> {:keys [*field *value]})
      (local-transform> [(keypath *user-id *field) (termval *value)] $$profiles))))
