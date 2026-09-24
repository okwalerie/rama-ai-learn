(ns profile-module.protocol
  "Contract for username registration and profile edits. The source demo's
   registration UUID is a retry marker, not a stable result key: a repeated
   UUID for the same username is accepted and generates a fresh user ID.")

(defprotocol ProfileModule
  (register! [this uuid username pwd-hash]
    "Register a username. Returns the generated Long user ID, or nil when
     another UUID already owns the username. Repeating the same UUID is
     accepted by the supplied behavior and returns a newly generated ID.
     The ID is a Long and is not a stable UUID-to-ID mapping.")
  (edit-profile! [this user-id edits]
    "Apply ProfileEdit values in vector order to the specified user; later
     edits to the same field win. Returns nil. Editing a missing ID has no
     specified error result, so callers must edit a registered ID.")
  (get-profile [this user-id]
    "Return nil for a missing ID. Otherwise return a map containing :username
     and :pwd-hash plus only optional fields that have been edited; do not
     include absent optional fields with nil values."))

(defrecord ProfileEdit [field value])
(defn display-name-edit [value] (->ProfileEdit :display-name value))
(defn pwd-hash-edit [value] (->ProfileEdit :pwd-hash value))
(defn height-inches-edit [value] (->ProfileEdit :height-inches value))
