# Family tree

Maintain a finite acyclic UUID-keyed two-parent family graph. A person has
`:id`, `:parent1`, `:parent2` (either may be `nil`) and `:name` (string).
Add each ID once, with parents before children. Adding a person records the
person and adds that ID to each parent's child set. Reads run after the caller
has waited for the writes to process. Generation arguments are nonnegative
integers; missing IDs may be queried.

`ancestors(id, generations)` returns a set of reachable parent IDs.
`generations=0` returns the immediate
parents, and `1` returns parents and grandparents. A missing ID or root with
no parents returns `nil`, not an empty set. Repeated reachability is deduplicated.

`descendants-count(id, generations)` returns `{depth count}`. It counts child
paths, not distinct people, at each depth: depth 0 counts direct children;
depth 1 counts their children. Generation 0 returns `{}`. For positive
generations, a leaf or missing ID returns `{0 0}`. Traversal includes a zero
count at the first terminal level if that level is within the requested depth,
but no further levels. The root is not counted as a descendant. Reaching a
child by two paths contributes twice.

The source replaces a person's record but does not retract old parent child
links. Reassignment of parents is outside the supported contract; do not infer
deletion or parent-update semantics. Keep family size and requested depth
bounded: descendant traversal expands paths, so a branching graph can cause
work exponential in depth. This package makes no wall-clock latency or
unbounded-depth guarantee.

Implement `family-tree.protocol/FamilyTree` and provide `create-module`
returning `{:module ..., :wrap-client ...}`. The client must implement
`rama-challenges.harness/Synchronizable`. See source method docstrings for
exact query semantics.
