# HLD metrics pipeline idiom review

Reviewed at baseline `43abd3fccff8777d8995f8a29658f3c4c97df01a` on branch
`orb/idiom-hld-metrics-pipeline`.

## Scope and method

Sources read in full:

- **hld-metrics-pipeline:** `README.md`, `src/hld_metrics_pipeline/protocol.clj`,
  `test-private/hld_metrics_pipeline/` (both challenge tests and both support
  namespaces), the reference `test-resources/hld_metrics_pipeline/module.clj`,
  and its design rationale `test-resources/development/PLAN.md`.
- **Comparison references:** `auction-module` and `chat-app`.
- **Sibling HLD references:** `hld-hotel-reservation`, `hld-stock-exchange`,
  and `hld-payment-system`, read only for the sequencing blocks cited in §0.
- **Skill:** `plugins/rama-skill/skills/rama/SKILL.md`, and
  `references/batch.md` for `+group-by` semantics.

Paths are relative to `challenges/hld-metrics-pipeline/` unless another
challenge is named. `module.clj` alone means this challenge's reference.

This document supersedes the first draft on this branch. That draft's
conclusions survive verification. This revision adds sources for the audit
premise (§0), corrects the auction characterization (§3), records the
PLAN.md I/O trade-off (§2), and discloses a blind spot in the efficiency
tests (§2).

## 0. The audited constructs belong to sibling modules, not this one

The audit brief names `:ingress-seq`, `+group-by`, `+vec-agg`, a sort, and a
`loop<-` repartition. **None of these occurs in hld-metrics-pipeline.**
`grep` over the challenge (excluding `development/` notes) finds only one
`sort-by`, at `module.clj:75`, which runs in memory over a map of at most
3 entries (§2). Nothing reads or writes a sequence number, and there is no
`<<batch`, aggregator, `loop<-`, or partitioner in the ETL.

The pattern exists, nearly verbatim, in three sibling references. Each has a
`<<batch` block inside a microbatch topology that:

1. reads and bumps a per-entity `:ingress-seq` to number each command;
2. uses `+group-by` on the entity key with `aggs/+vec-agg` to gather
   `[position command]` pairs. In a batch block, `+group-by` hash-partitions
   by the grouping key automatically (`plugins/.../references/batch.md:218`),
   and this is the repartition;
3. sorts the gathered pairs by position (`sort-by first`);
4. applies the commands one at a time in a `loop<-` / `continue>`.

| Reference | depot | `:ingress-seq` read/write | `+group-by` + `+vec-agg` | sort helper | `loop<-` … `continue>` |
|---|---|---|---|---|---|
| `hld-hotel-reservation/test-resources/hld_hotel_reservation/module.clj` | `:60` hash `:property-id` | `:65` schema, `:94-96` | `:98` | `:42-43`, used `:99` | `:100` … `:249` |
| `hld-stock-exchange/test-resources/hld_stock_exchange/module.clj` | `:77` hash `:symbol` | `:89` schema, `:106-108` | `:110` | `:35`, used `:111` | `:112` … `:211` |
| `hld-payment-system/test-resources/hld_payment_system/module.clj` | `:107` hash `:tenant-id` | `:112` schema, `:122-124` | `:126-127` | `:103-104`, used `:128` | `:129` … `:181` |

Every one of these depots is already hash-partitioned by the same key that
`+group-by` groups on. Any assessment of whether that ordering scheme is
necessary belongs in those challenges' reviews. hld-metrics-pipeline gets
per-series ordering without any of it (§1).

## 1. Single multiplexed depot + one microbatch topology — idiomatic, keep

**Evidence.**
- One depot, `*series-events`, uses `(hash-by :series)` (`module.clj:101`).
- One microbatch topology, `"metrics"` (`:103`), has one source and takes
  `%microbatch` directly (`:126-127`), with no `<<batch` block.
- `<<subsource` dispatches between `AdvanceClock` and `IngestSample`
  (`:128-129,157`).
- Every PState access is a `local-select>` or `local-transform>` on the
  task that already owns `*series`. There is no partitioner.
- `series-key` puts labels into a sorted map (`:23-29`). Depot routing and
  the PState key therefore see one canonical identity, as the README
  "Domain model" requires ("label order is irrelevant").

**Assessment.**
- README "Write ordering and synchronization" requires same-series writes to
  apply in client order. Placing both write kinds on one depot partition
  keyed by series provides that order. The ETL never leaves the task, so each
  record's reads and writes finish before the next record's.
- The private suite checks both advance-then-ingest and ingest-then-advance
  inside one sync phase (`functional_test_support.clj:405-427`).
- Splitting the write kinds across depots, or regrouping records as in §0,
  would require an explicit sequencing scheme like the siblings'.
- Microbatch is the skill default (`SKILL.md:63`). No write returns data
  (`protocol.clj:16-37`). Appends use `:append-ack` (`module.clj:233`), and
  readers wait on `wait-for-microbatch-processed-count` (`:254-256`).
- That count is module-wide, so the counter atom is per-`create-module`
  (`:258-265`) and every wrapper of one IPC waits for the shared total.
  `functional_test_support.clj:429-455` exercises this with a late wrapper.

**Comparison.**
- Auction uses two depots (`auction-module/.../module.clj:70-71`) because
  listings and bids have no common ordering requirement.
- Chat multiplexes its user actions onto one depot hashed by `:user-id`
  (`chat-app/.../module.clj:160,196-201`), so its registration gate is a
  local read.
- The idiom is to put write kinds that must be ordered or colocated on the
  same key into one depot. This module does that.

## 2. One series-keyed PState with retention-bounded collections — idiomatic

**Evidence.** `$$series` (`module.clj:104-123`) maps each `SeriesKey` to a
`fixed-keys-schema` record:

| Field | Shape | Live bound (derived from README rules) |
|---|---|---|
| `:clock` + 4 counters | scalars | 5 values |
| `:raw` | subindexed sorted map ts→value, `:track-size? false` | ≤ 300 (`C-300 < ts ≤ C`) |
| `:buckets-60` | subindexed sorted map start→agg | ≤ 121 (`C-7259 ≤ start ≤ C`) |
| `:buckets-3600` | **plain** map start→agg | ≤ 3 (`C-10799 ≤ start ≤ C`) |

Expiry runs only on the series being advanced. For `:raw` and `:buckets-60`
it is one range read followed by point `NONE>` deletes (`module.clj:135-149`).
For `:buckets-3600` it reads the map, prunes it, and writes it back with
`termval` (`:151-155`).

**Assessment.**
- **No inner collection grows without bound.** The two collections above
  about 100 entries are subindexed (`SKILL.md:59`). The collection below 50
  entries is not subindexed, which `SKILL.md:59` also requires ("Don't
  subindex small collections").
- **The plain 3600-wide map is a documented I/O trade-off.**
  - `development/PLAN.md:291-296` gives the rationale:
    - At most 3 live entries, "far under the 50–100-element subindex
      threshold".
    - A fold costs 1 read plus a write-only `termval`.
    - Expiry costs 1 read plus a write-only `termval`.
    - A query costs 1 read, then filters and sorts at most 3 entries in
      memory.
    - Each width is its own record field, so there is no extra hop through
      a width-keyed map.
  - `PLAN.md:303-306` costs the alternatives:
    - **Option A** subindexes both widths under a width-keyed map. It was
      rejected because it "pays a seek per accepted sample (the dominant
      write) for a uniform code path", citing `SKILL.md:30`: "Never trade
      I/O efficiency for code simplicity."
    - **Option B** stores `:raw` as a plain blob. It was rejected because
      it rewrites up to 300 entries per sample.
  - The price is three small pure helpers (`fold-into-map`, `prune-buckets`,
    `bucket-rows-in`, `module.clj:70-92`) and a separate query branch
    (`:211-213`). The `sort-by` at `:75` is that branch's in-memory sort of
    at most 3 entries.
  - I agree with the trade-off. Folding the 3600-wide map with `+compound`
    as well would unify the code, but it would not remove the read, so there
    is no I/O reason to change it.
- **I/O idioms follow `SKILL.md:65-68`.**
  - Counters use `(term inc)` because the old value is not otherwise needed
    (`module.clj:181`).
  - The raw store is a write-only `termval` (`:172`).
  - The 3600-wide map is read once, then written back with `termval`
    (`:177-179`).
  - The 60-wide fold is one `+compound` read-modify-write (`:176`).
- **Top-level keyspace growth is inherent in the contract.**
  - Every distinct series needs a durable clock and counters that never
    decrease (README "Series info").
  - The README excludes cardinality caps ("What was excluded") and defines
    no deletion API.
  - Evicting idle series would therefore change observable results.
- **Efficiency-test blind spot (disclosed; tests not modified).**
  - `efficiency_test_support.clj:18-21` states the limitation directly. It
    counts RocksDB `:rocks-read`/`:rocks-iterator`/`:rocks-iterator-read`
    events (`:28-41`), and those events carry no payload size.
  - A design that stores a whole series as one non-subindexed blob and
    filters it in memory costs one read regardless of size. It would pass
    the growth checks at `:166-190` even though it violates the README
    "Efficiency contract".
  - The reference does not depend on this gap: its only non-subindexed
    collection holds at most 3 entries.
  - Candidate solutions could still exploit it. Closing the gap would need
    a payload-size or entries-read signal, which is outside this review's
    scope because private tests are not to be edited.

**Comparison.**
- Auction subindexes its growing per-user and per-listing collections
  (`auction-module/.../module.clj:78-93`).
- Chat does the same (`chat-app/.../module.clj:172-179,255-277`) and caps
  one index with an explicit trim (`:377-383`).
- This module relies on the spec's retention windows instead of explicit
  trims.

## 3. Reads are single-task query topologies — idiomatic, keep

**Evidence.**
- `query-raw` (`module.clj:184-195`) and `query-rollup` (`:197-214`) each
  follow the same shape:
  1. `(|hash *series)`;
  2. read the clock;
  3. clamp to `[lo,hi)` with overflow-safe window arithmetic (`:45-60`);
  4. one `sorted-map-range` read with `{:allow-yield? true}`
     (`SKILL.md:26`);
  5. `(|origin)`.
- Neither query fans out, so neither needs an aggregator.
- `get-series-info` is one `foreign-select-one` with `submap` over the
  scalar fields (`:240-247`).
- Every public read is one network roundtrip, and no read loops on the
  client.

**Assessment.**
- A rollup or raw answer depends on the current clock, which decides
  completeness and retention, and then on a range read.
- A client-side version would need two roundtrips and could see two
  different clocks. Doing both reads on the owning task follows
  `SKILL.md:39`.
- `get-series-info` has no dependent read, so a direct select is correct.
- Only the queried series' key is touched. The efficiency suite checks this
  at `efficiency_test_support.clj:129-190`, subject to the blind spot in §2.

**Auction comparison (corrected).** The first draft called auction's client
reads (`auction-module/.../module.clj:184-204`) keyed lookups. Only
`get-highest-bid` (`:196-199`) is a point lookup. The other three read whole
collections, unpaged:

| Method | Client call | What it retrieves |
|---|---|---|
| `get-listings` | `foreign-select [(keypath seller-id) ALL]` (`:185`) | all of a seller's listings, a subindexed map |
| `get-bids` | `foreign-select [(keypath listing-partial-id) ALL]` with `{:pkey owner-id}` (`:190-192`) | every bidder of a listing, a subindexed map |
| `get-notifications` | `foreign-select [(keypath user-id) ALL]` (`:204`) | a user's entire notification vector, subindexed and append-only |

Accurate assessment:
- Each call addresses one partition and costs one roundtrip.
- The unpaged result is required by the auction contract:
  `auction-module/src/auction_module/protocol.clj:14-26` defines each method
  as returning the full vector, with no cursor or limit.
- So these calls are not a client-side distributed query. They are
  whole-collection reads whose cost grows with the collection, and they
  would need paging (e.g. `sorted-map-range-from … {:max-amt n}`) if the
  contract allowed it.
- They show that a direct select fits a single partition. They are not an
  example of cheap keyed reads.
- The metrics module's range reads are bounded by the requested range and
  the retention window, so the comparison favors this module.

Chat's `get-online-members` (`chat-app/.../module.clj:564-580`) is the only
client-side multi-roundtrip loop in the comparison set. It pages through a
member set with a server-side online filter per chunk. Nothing in this
module needs that.

## 4. Minor observations (no change recommended)

- **Width fallback.** `query-rollup`'s `(default>)` branch (`module.clj:211`)
  treats every width other than 60 as 3600. This is sound under
  `protocol.clj:54` (width is 60 or 3600). It would silently mis-answer if
  another width were ever added.
- **Expiry deletes.** Expiry materializes the expired keys, then `explode`s
  point `NONE>` deletes (`module.clj:136-141,144-149`). The work is
  proportional to what expires, not to the size of the clock jump. The
  suite checks this with a jump to 10^12
  (`efficiency_test_support.clj:196-204`). A single range-removal transform
  might avoid the intermediate vector. The skill references do not document
  range removal on subindexed maps, so I am not recommending it.

## Verdict and validation

The reference follows the relevant idioms:
- one depot partitioned for per-series ordering and colocation;
- microbatch by default;
- subindexing sized to each collection;
- `term` and write-only `termval` updates that avoid extra reads;
- single-roundtrip, single-task query topologies.

It contains none of the audited sequencing constructs, which live in the
sibling modules listed in §0. **The change is doc-only. The reference,
README, protocol, private tests, and skill are unmodified.**

- **Baseline:** the full private suite (functional and efficiency namespaces,
  2 and 4 tasks each) was run against the unmodified reference with
  `clojure -X:test-private-harness` from the challenge directory:
  **Ran 4 tests containing 312 assertions. 0 failures, 0 errors.** It
  exited 0 in about 1m51s. `implementations/` does not exist, so the
  reference was not shadowed on the classpath. The ERROR log lines emitted
  during the run are Rama worker and Kafka recovery messages from the
  suite's `update-module!` restart test, not test failures.
- **`scripts/test_reference_packages.py`:** does not apply, because this
  challenge is not in its `PACKAGES` list (`scripts/test_reference_packages.py:11-13`).
