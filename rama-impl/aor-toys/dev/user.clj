(ns user
  "REPL helpers: (start!) launches AorToysModule in an in-process cluster with
  the AOR UI on :1975; (invoke \"JevTriage\" \"...\") runs an agent."
  (:require
   [com.rpl.agent-o-rama :as aor]
   [com.rpl.rama :as rama]
   [com.rpl.rama.test :as rtest]
   [waler.aor-toys.module :as m]))

(defonce state (atom nil))

(defn start! []
  (when-not @state
    (let [ipc (rtest/create-ipc)
          _   (rtest/launch-module! ipc m/AorToysModule {:tasks 1 :threads 1})
          ui  (aor/start-ui ipc {:port 1975 :no-input-before-close true})
          mgr (aor/agent-manager ipc (rama/get-module-name m/AorToysModule))]
      (reset! state {:ipc ipc :ui ui :mgr mgr})))
  :started)

(defn stop! []
  (when-let [{:keys [ipc ui]} @state]
    (.close ^java.io.Closeable ui)
    (.close ^java.io.Closeable ipc)
    (reset! state nil))
  :stopped)

(defn client [agent-name] (aor/agent-client (:mgr @state) agent-name))

(defn invoke [agent-name & args]
  (apply aor/agent-invoke (client agent-name) args))
