(ns nlb.timed-notifications
  (:use [com.rpl.rama]
        [com.rpl.rama.path])
  (:require [rama-challenges.shared :as shared])
  (:import [com.rpl.rama.helpers TopologyScheduler]))

(defn test-mode? [] false)

(defrecord ScheduledPost [id time-millis post schedule-id])

(defmodule TimedNotificationsModule
  [setup topologies]
  (declare-depot setup *scheduled-post-depot (hash-by :id))
  (if shared/REPLACE-TICK-DEPOTS
    (declare-depot setup *tick :random {:global? true})
    (declare-tick-depot setup *tick 1000))
  (let [topology (stream-topology topologies "core")
        scheduler (TopologyScheduler. "$$scheduled")]
    (declare-pstate
      topology
      $$feeds
      {String (vector-schema String {:subindex? true})})
    (declare-pstate
      topology
      $$delivered
      {String (set-schema String {:subindex? true})})
    (.declarePStates scheduler topology)
   (<<sources topology
     (source> *scheduled-post-depot :> {:keys [*time-millis] :as *scheduled-post})
     (java-macro! (.scheduleItem scheduler "*time-millis" "*scheduled-post"))

     (source> *tick)
     (java-macro!
      (.handleExpirations
        scheduler
        "*scheduled-post"
        "*current-time-millis"
        (java-block<-
          (identity *scheduled-post :> {:keys [*id *post *schedule-id]})
          (local-select> [(keypath *id) (view contains? *schedule-id)] $$delivered :> *delivered?)
          (<<if (not *delivered?)
            (local-transform> [(keypath *id) NONE-ELEM (termval *schedule-id)] $$delivered)
            (local-transform> [(keypath *id) AFTER-ELEM (termval *post)] $$feeds))
          )))
     )))
