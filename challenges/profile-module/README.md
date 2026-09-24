# Profile module

Register usernames, assign user IDs and edit profile fields. Implement
`profile-module.protocol/ProfileModule`. Writes become visible after
`rama-challenges.harness/wait-for-processing!`.

Registration takes strings for UUID, username and password hash. Accept the
first UUID for an available username. Return `nil` for a different UUID if
someone already owns the username. Accepting the same UUID again generates
a new ID each time, so retrying registration does not return a stable ID.

Return IDs as Long values. Profile lookup returns `nil` for an unknown ID.
A registered profile contains `:username` and `:pwd-hash`. Optional
`:display-name` and `:height-inches` keys remain absent until edited.

`display-name-edit` and `pwd-hash-edit` take strings; `height-inches-edit`
takes a Long. Apply edits in vector order. If a field occurs more than once,
its last edit wins. `edit-profile!` returns `nil`. Editing an unregistered
ID is outside the contract.

Provide `create-module`, returning `{:module ... :wrap-client ...}`.
The client implements `ProfileModule` and
`rama-challenges.harness/Synchronizable`. Keep profile and registration state
in the module.

Write `implementations/profile-module/src/profile_module/module.clj`. Run tests from this directory with `clojure -X:test` and the private reference harness with `clojure -X:test-private-harness`.
