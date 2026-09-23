# Implicit Spec

<!-- Phase 0 artifact for hld-search-autocomplete. Requirements only; no design. -->

Domain: per-locale typeahead core. Entities: **locale** (current
generation, corpus, block list), **phrase-in-generation** (locale +
generation + phrase: base, unique sessions), **event** (locale +
generation + phrase + session-id). Harness runs 2 or 4 tasks and a
second client. There is no logical time; ordering is the module's
processing order.

Ordering: sequential writes from one client to the same logical owner
(locale) process in invocation order; writes from different clients
serialize arbitrarily but a write that returned before a
`wait-for-processing!` barrier processes before any write issued after
it. Latency figures below are production aspirations, never acceptance
thresholds; tests enforce only bounded-work contracts.

## Operations

### publish-snapshot! [locale generation entries]

- **Latency**: observable after wait; production aspiration seconds
  (batch publication; not enforced). Processing may be proportional to
  entries (≤ 10,000).
- **Throughput**: a few per day per locale; bursts when many locales
  republish together.
- **Invariants**:
  - Applied iff generation > current (first snapshot: any generation
    ≥ 1). Equal generation is a retry and must not reset counts or
    re-apply.
  - The switch is atomic from a reader's view: `suggest` never mixes
    the old corpus with the new, or old counts with the new
    generation. (Readers before `wait-for-processing!` may see either
    the old or the new generation entirely.)
  - Counts of the previous generation are discarded from scoring;
    novel phrases of the previous generation cease to be candidates
    unless present in the new snapshot or re-counted.
  - Block list survives.
  - Processing retries must not reset counts a second time (events
    counted after the switch must survive a replayed publish).
  - Processing must not depend on the size of the previous generation's
    trend data (no scan-and-delete of 100,000 novel phrases or
    1,000,000 sessions).
- **Data growth**: 0..10,000 entries per snapshot; 200 locales; the
  corpus is replaced, not accumulated.
- **Concurrency**: events for the new generation arriving from another
  client concurrently with the publish: those processed before the
  publish are ignored (stale/future), those after are counted. From
  one client, publish then event for that generation always counts.
- **Edge cases**: empty entries (allowed: generation advances, corpus
  empty, counts reset; `suggest` returns only novel phrases counted
  afterwards; `get-phrase :in-corpus? false` for every phrase);
  generation 1 then 3 then 2 (2 ignored); generation
  equal with different entries (ignored); snapshot omitting a phrase
  that had events in the old generation (phrase disappears until new
  events); snapshot including a phrase that is blocked (blocked, not
  suggested, `get-phrase :in-corpus? true`); single-entry snapshot; two
  locales sharing a phrase (independent).

### record-search! [locale generation session-id phrase]

- **Latency**: observable after wait; production aspiration freshness
  within minutes (not enforced).
- **Throughput**: ~100,000/s; hot phrases receive thousands/s.
- **Invariants**:
  - Counted iff generation = current, and (phrase, session) not yet
    counted in it.
  - Each counted event raises the phrase's score by exactly 10; the
    score is exact, never approximate.
  - Suggest results reflect counted events after wait for every prefix
    of the phrase (all 1..64 prefixes).
  - Processing retries must not count an event twice.
  - Ignored events leave no trace: a later publish of that generation
    does not retroactively count them.
- **Data growth**: unique sessions per phrase up to 1,000,000; novel
  phrases per generation up to 100,000. The set of counted sessions per
  phrase must not be scanned per event or per read.
- **Concurrency**: the same (phrase, session) from two clients: counted
  once. Different sessions: each counted; final score = base + 10 ×
  distinct sessions. Events from one client for one locale process in
  invocation order.
- **Edge cases**: novel phrase's first event (score 10, candidate);
  event with generation = current + 1 before publish (ignored, not
  buffered); event after a newer publish carrying the old generation
  (ignored); same session searching two phrases (both counted); 64-char
  phrase (64 prefixes updated); phrase equal to another phrase's prefix
  ("apple" and "apple pie" are independent candidates).

### block-phrase! / unblock-phrase! [locale phrase]

- **Latency**: observable after wait.
- **Throughput**: rare.
- **Invariants**: block list is per locale, boolean per phrase, survives
  publishes; affects suggest immediately after wait for every prefix of
  the phrase; never changes base or sessions; unblock restores the
  phrase with its accumulated score.
- **Edge cases**: block a phrase that is not a candidate (nothing
  visible in suggest; `get-phrase :blocked? true`); block then publish
  a snapshot containing it (still blocked); block the top-1 phrase for
  a prefix (the previous top-2 becomes top-1, and the k-th result is
  the previous (k+1)-th: results never shrink below k when at least k
  unblocked candidates match); unblock twice.

### suggest [locale prefix k]

- **Latency**: production aspiration ~20 ms (not enforced); the hot
  path; read work independent of candidate count and corpus size
  (enforced).
- **Throughput**: ~500,000/s.
- **Invariants**: exact top-k by (score desc, phrase asc); candidates
  as defined; blocked excluded; prefix match is byte-prefix on the
  phrase string (prefix "app" matches "apple" and "app store"; prefix
  "app " matches "app store" only); result count = min(k, unblocked
  matching candidates); never waits or mutates; same inputs with no
  intervening write → identical result.
- **Edge cases**: prefix equal to a full phrase (that phrase is a
  match); prefix longer than any phrase (empty); k = 1 tie on score
  (lex smallest); locale with no generation ([]); scores tied among
  more than k phrases (lex order decides which are included); prefix
  ending in a space; a novel phrase outranking snapshot phrases
  (score 10 × sessions vs base).

### get-phrase [locale phrase]

- **Latency**: production aspiration ~50 ms (not enforced); fixed work
  (enforced).
- **Invariants**: fields consistent with the current generation; score
  = base + 10 × sessions; `:blocked?` independent of generation.
- **Edge cases**: phrase from a previous generation only (in-corpus?
  false, sessions 0, score 0); blocked novel phrase with events
  (in-corpus? false, sessions n, score 10n, blocked? true).

### get-generation [locale]

- **Latency**: production aspiration ~50 ms (not enforced); fixed work.
- **Invariants**: nil before first publish; then the highest published
  generation.

## Entity State × Write Matrix

### Entity: locale

States: **empty** (no generation), **live(g)**. Reads: `suggest`,
`get-phrase`, `get-generation`.

```
empty x publish-snapshot! (g)
  - get-generation: g; suggest: top-k from snapshot bases; get-phrase: per entries
empty x publish-snapshot! (g, entries [])
  - get-generation: g; suggest: []; get-phrase: in-corpus? false for all
empty x record-search! (any g)
  - get-generation: nil; suggest: []; get-phrase: sessions 0 (ignored)
empty x block-phrase!
  - get-phrase: blocked? true, generation nil; suggest: []
live(g) x publish-snapshot! (g' > g)
  - get-generation: g'; suggest: from new corpus only, all sessions 0
  - get-phrase(old novel phrase): in-corpus? false, sessions 0, score 0
  - get-phrase(blocked phrase): blocked? still true
live(g) x publish-snapshot! (g' > g, entries [])
  - get-generation: g'; suggest: [] until new events; all sessions 0
live(g) x publish-snapshot! (g' <= g)
  - everything unchanged
live(g) x record-search! (generation g, new pair)
  - get-phrase: sessions +1, score +10; suggest: reflects for all prefixes
live(g) x record-search! (generation != g)
  - unchanged
live(g) x block-phrase! / unblock-phrase!
  - suggest: phrase excluded / included; get-phrase: blocked? toggled;
    get-generation: unchanged
```

### Entity: phrase-in-generation (locale + current generation + phrase)

States: **absent** (not in corpus, no events), **corpus-only(base)**,
**novel(n)** (base 0, n ≥ 1 sessions), **corpus+trend(base, n)**; each
optionally **blocked**. Reads: `suggest`, `get-phrase`.

```
absent x record-search! (current generation, new session)
  - get-phrase: in-corpus? false, sessions 1, score 10; suggest: candidate
absent x publish-snapshot! (newer, containing it with base b)
  - get-phrase: in-corpus? true, base b, sessions 0, score b
corpus-only(b) x record-search! (new session)
  - get-phrase: sessions 1, score b + 10
corpus-only(b) x record-search! (duplicate session)
  - unchanged
corpus-only(b) x publish-snapshot! (newer, without it)
  - get-phrase: absent view (in-corpus? false, base 0, score 0); suggest: gone
corpus-only(b) x publish-snapshot! (newer, base b2)
  - get-phrase: base b2, sessions 0, score b2
novel(n) x publish-snapshot! (newer, without it)
  - get-phrase: sessions 0, score 0; suggest: not a candidate
novel(n) x publish-snapshot! (newer, with base b)
  - get-phrase: base b, sessions 0, score b
novel(n) x record-search! (new session)
  - sessions n+1, score 10(n+1)
corpus+trend(b, n) x record-search! (stale generation)
  - unchanged
any x block-phrase!
  - suggest: excluded for every prefix; get-phrase: blocked? true, score unchanged
blocked any x unblock-phrase!
  - suggest: included again at its current score
blocked any x publish-snapshot! (newer)
  - get-phrase: blocked? true (persists); counts reset as for unblocked
```

### Entity: event (locale + generation + phrase + session-id)

States: **uncounted**, **counted**. Reads: `get-phrase`, `suggest`.

```
uncounted x record-search! (generation current)
  - get-phrase: sessions +1; event becomes counted
uncounted x record-search! (generation not current)
  - unchanged; stays uncounted (no trace, even if that generation later
    becomes current)
counted x record-search!
  - unchanged
counted x publish-snapshot! (newer)
  - no longer contributes (generation superseded); a new event with the
    same session under the new generation counts again
```
