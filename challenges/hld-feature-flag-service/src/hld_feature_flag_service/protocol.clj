(ns hld-feature-flag-service.protocol
  "Protocol definition for the hld-feature-flag-service challenge.

   A flag is addressed by [tenant env flag-key], all non-empty strings.
   Each flag holds one whole configuration map at a time:

     {:revision      <positive int>
      :killed?       <boolean>
      :off-value     <value>          ; served when killed or unsafe
      :default-value <value>          ; served when nothing else applies
      :rules         [{:attribute <str> :operator <keyword> :value <v> :serve <v>} ...]
      :rollout       nil | {:threshold <int 0..10000> :serve <value>}}

   Values (:off-value, :default-value, :serve, rule :value, attribute
   values) are arbitrary Clojure values compared with =; tests use strings,
   booleans, and integers. The only known operator is :eq.

   See README.md for the evaluation order and the exact bucketing hash with
   worked numbers.")

(defprotocol FeatureFlagService
  "Revisioned flag configuration with deterministic evaluation."

  (put-flag-config! [this tenant env flag-key config]
    "Write. Replace the flag's whole configuration with `config` iff
     (:revision config) is strictly greater than the stored revision (or no
     configuration is stored yet). An equal or lower revision is a stale
     write and is a no-op. There is no partial update and no delete.")

  (get-flag-config [this tenant env flag-key]
    "Read. Returns the stored configuration map exactly as accepted, or nil
     if the flag has never been configured.")

  (compute-bucket [this tenant env flag-key subject-id]
    "Pure. Returns the rollout bucket, an integer in [0, 10000), computed
     as: SHA-256 over the concatenation, in order, of tenant, env, flag-key,
     subject-id, each encoded as its UTF-8 bytes preceded by the byte length
     as a 4-byte big-endian unsigned integer; take the first 8 bytes of the
     digest as a big-endian unsigned 64-bit integer; reduce modulo 10000.
     The revision is not part of the hash. Requires no stored state.")

  (evaluate [this tenant env flag-key subject-id attributes]
    "Read. `attributes` is a map from attribute name (string) to value.
     Returns a map with at least :value, :revision, and :reason. Decided in
     order against the stored configuration:
       1. no configuration            -> {:value nil :revision nil :reason :missing}
       2. :killed? true               -> {:value off-value :reason :killed}
       3. any rule (at any position) has an operator other than :eq
                                      -> {:value off-value :reason :unknown-operator}
       4. first rule, in order, whose :attribute is present in `attributes`
          and whose :value = the attribute's value
                                      -> {:value (:serve rule) :reason :rule :rule-index <i>}
          A missing attribute is a mismatch, never an error.
       5. :rollout present and (compute-bucket ...) < :threshold
                                      -> {:value (:serve rollout) :reason :rollout :bucket <b>}
       6. otherwise                   -> {:value default-value :reason :default}
          with :bucket <b> included iff :rollout is present.
     :revision is the stored configuration's :revision for reasons 2-6."))
