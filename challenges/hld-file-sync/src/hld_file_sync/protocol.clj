(ns hld-file-sync.protocol
  "Protocol definition for the hld-file-sync challenge. README.md is the
   authoritative contract; these docstrings summarize it.")

(defprotocol FileSyncModule
  "Metadata service for a block-deduplicated, versioned file sync system.

   All ids are non-empty Strings. A file-id is either client form (<= 128
   chars, no ~) or generated form (~ followed by a valid request-id, the
   form of every conflict-copy id). Commands take a client-chosen
   request-id scoped to the namespace, return nil, and produce one
   durable outcome readable with get-outcome after wait-for-processing!.
   Structural validation precedes request-id handling: invalid arguments
   throw IllegalArgumentException synchronously, append nothing, and
   leave an unused request-id unused and an existing outcome untouched;
   queries throw the same on out-of-bounds arguments. The payload is the
   command type plus every argument except this and request-id, compared
   with =. Replay/conflict handling precedes business validation: same
   request-id + same payload replays the original outcome with no effect;
   same request-id + different payload has no effect and increments
   :conflicting-attempts on the original outcome."
  (register-blocks! [this request-id ns-id blocks]
    "Register block metadata in ns-id. blocks is a vector of 1..1024
     {:hash String :size Long} maps. Rejected as a whole with
     :size-mismatch if any hash is already registered with a different
     size. Accepted outcome includes :registered (count newly added).")
  (commit-file! [this request-id ns-id file-id path blocklist parent-version]
    "Record a version of file-id with ordered blocklist (0..1024 hashes,
     repeats allowed). parent-version nil creates the file; otherwise it
     is the head version the client last saw. Rejections in order:
     :file-exists, :no-such-file, :unknown-parent (parent > head),
     :need-blocks (outcome carries :need-blocks, missing hashes in
     first-occurrence order, deduplicated). parent nil requires a
     client-form file-id; non-nil parent accepts either form. parent =
     head -> new head version. parent < head -> conflict copy: original
     untouched, new file (str \"~\" request-id) at version 1 with path
     (str prefix \" (conflicted copy \" request-id \")\") where prefix is
     path truncated so the result is <= 1024 chars, and :conflict-of
     file-id (the targeted id). :conflict-of is immutable provenance
     (nil for client-created files) carried by every version, journal
     entry, and outcome of that file; :conflict-copy? is true only when
     this command created a copy. Every accepted commit appends one
     namespace journal entry. Accepted outcome: :file-id :version :seq
     :size-bytes :conflict-copy? :conflict-of.")
  (get-outcome [this ns-id request-id]
    "Outcome map {:status :accepted|:rejected :command kw :reason kw
     :conflicting-attempts Long ...} or nil if never processed.")
  (get-block-size [this ns-id hash]
    "Registered size (Long) of hash in ns-id, or nil.")
  (get-file [this ns-id file-id]
    "Head version record {:file-id :version :path :blocklist :size-bytes
     :request-id :seq :conflict-of} or nil.")
  (get-file-version [this ns-id file-id version]
    "Record for the given version (same shape as get-file) or nil.")
  (get-changes [this ns-id after-seq limit]
    "Journal entries with :seq > after-seq, ascending, at most limit
     (1..500): {:seq :file-id :version :path :size-bytes :request-id
     :conflict-of}. Seqs start at 1 and are contiguous. Empty vector when
     nothing follows after-seq or the namespace is unknown."))
