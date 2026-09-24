(ns timed-notifications.module
  (:require [rama-challenges.harness :as harness]
            [timed-notifications.protocol :as protocol]
            [nlb.timed-notifications :as source])
  (:use [com.rpl.rama]
        [com.rpl.rama.path]))

(defn make-client [ipc]
  (let [name (get-module-name source/TimedNotificationsModule)
        scheduled (foreign-depot ipc name "*scheduled-post-depot")
        tick (foreign-depot ipc name "*tick")
        feeds (foreign-pstate ipc name "$$feeds")]
    (reify protocol/TimedNotifications
      (schedule-post! [_ account-id time-millis post]
        (foreign-append! scheduled (source/->ScheduledPost account-id time-millis post)) nil)
      (tick! [_] (foreign-append! tick nil))
      (feed [_ account-id] (or (foreign-select [(keypath account-id) ALL] feeds) []))
      harness/Synchronizable
      (wait-for-processing! [_] nil))))

(defn create-module [] {:module source/TimedNotificationsModule :wrap-client make-client})
