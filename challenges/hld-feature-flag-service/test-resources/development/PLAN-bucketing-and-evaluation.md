# Bucketing and evaluation — completed plan and validation

The existing revisioned store supplies one whole current configuration by the
full `[tenant env flag-key]` identity. Evaluation is a client-side pure function
of that one read, the subject ID, and the supplied attribute map. No second
depot, PState, topology, or subject cache is needed. A missing flag needs the
same one-flag lookup. Rule inspection costs O(number of supplied rules),
independent of other flags; bucketing has no state access.

For the bucket, encode each of the four components as a four-byte big-endian
UTF-8 byte length and bytes, digest with SHA-256, interpret the first eight
digest bytes as an unsigned big-endian integer, and reduce modulo 10000.
Never include revision or config. Validate against published vectors 7336,
6729, 798, 598, 2961 and independently calculated Unicode 3610 and
length-prefix collision pair 5866/9548.

For evaluation, read the current config once. Missing precedes kill; kill
precedes a full scan for unknown operators; that scan precedes first matching
rule by vector index; rollout (if non-nil) precedes default. Presence of an
attribute is checked separately from its value, so false is matchable.
Compute the bucket only after no rule matches and rollout is present. The
default without rollout must omit `:bucket`. The returned fields specified
for each reason are required; the protocol permits additional metadata.

Validation: one point read per evaluation; no read for standalone bucket;
no persistent state on this path. The chosen placement inherits the validated
revisioned-store plan. No cross-partition aggregation or yieldable unbounded
loop exists. Thresholds 0, equality, and 10000 have discriminating fixtures.
This plan was written after the completed config plan and before final
integration validation; the public README and protocol remain authoritative.

PHASE_VALIDATION:pass
