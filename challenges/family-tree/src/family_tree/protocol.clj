(ns family-tree.protocol
  (:refer-clojure :exclude [ancestors]))

(defrecord Person [id parent1 parent2 name])

(defprotocol FamilyTree
  "A family graph keyed by UUID. Add a Person with id, parent1, parent2,
  and name; absent parents are nil. Add each ID once, with parents before
  children. Reads are visible after wait-for-processing!; queries do not wait.
  Generation counts are nonnegative integers on finite acyclic graphs."
  (add-person! [this person]
    "Accepts a Person record; id and non-nil parent IDs are UUIDs and name is a string.")
  (ancestors [this person-id generations]
    "Set of reachable parent UUIDs, or nil when none are reachable. Generation
    zero includes immediate parents; visited IDs are deduplicated.")
  (descendants-count [this person-id generations]
    "Map of depth to number of child paths at that depth. Depth zero counts
    direct children; shared descendants count once per path. Generations zero
    returns {}; positive generations include {0 0} for a leaf or missing ID
    and omit deeper levels after no children remain."))
