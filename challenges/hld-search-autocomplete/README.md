# HLD Search Autocomplete Challenge

Build the durable core of a typeahead service: per-locale corpora
published as generational snapshots, a trending overlay built from
session-deduplicated search events on the current generation, a
persistent policy blocklist applied before top-k selection, and a
prefix query that returns the top-k phrases with exact scores and
deterministic tie-breaking.

## Source and attribution

This challenge is adapted from "Design Search Autocomplete (Typeahead
Suggestions)" by the Handbook Academy contributors:
https://hld.handbook.academy/curriculum/case-studies/search-autocomplete/

Prose adapted from that case study is licensed under Creative Commons
Attribution-ShareAlike 4.0 International (CC BY-SA 4.0):
https://creativecommons.org/licenses/by-sa/4.0/ . This README and the
protocol docstrings are derived works published under the same license.

Modifications and exclusions relative to the source:

- No fuzzy matching or spellcheck, no personalization, no geo signals,
  no ML re-ranking, no time decay of trends.
- No CDN or cache tiers, no debouncing, no client concerns.
- The nightly batch pipeline is replaced by a caller-published full
  snapshot per generation (bounded to 10,000 phrases). The streaming
  overlay is replaced by session-deduplicated search events scored
  exactly (10 points per unique session).
- Phrases arrive already normalized (lowercase ASCII words separated by
  single spaces); no tokenization or Unicode normalization.
- Policy filtering is a manual block/unblock list; no classifiers.

## Scope

- **Locales** are independent namespaces. Each has at most one current
  generation.
- **Snapshots.** `publish-snapshot!` with a generation strictly greater
  than the locale's current generation replaces the whole corpus and
  resets all trend counts. Equal or lower generations are ignored. An
  empty `entries` vector is allowed: the corpus becomes empty, the
  generation still advances, and counts still reset.
- **Events.** `record-search!` counts a (phrase, session) pair at most
  once per (locale, generation). Events whose generation is not the
  locale's current generation are ignored without trace. Events may
  introduce phrases absent from the snapshot ("novel" phrases).
- **Score.** `score = base + 10 * unique-sessions`, where `base` is the
  snapshot value (0 for novel phrases) and `unique-sessions` counts
  distinct session-ids for the phrase in the current generation.
- **Policy.** Blocked phrases are excluded from `suggest` results but
  still accumulate counts. The block list persists across generations.
- **Suggest.** Top-k candidates whose phrase starts with the prefix,
  ordered by score descending then phrase ascending (`String.compareTo`).
  Candidates are phrases in the current corpus or with at least one
  counted event in the current generation, excluding blocked phrases.

## Input grammar and bounds

| Input | Constraint |
|---|---|
| `locale` | String matching `[a-z]{2}(-[a-z]{2})?` |
| `generation` | Long in `[1, 2^31)` |
| `entries` | Vector of 0..10,000 `[phrase base]` with distinct phrases (empty allowed) |
| `phrase` | String, 1..64 chars, `[a-z0-9]+( [a-z0-9]+)*` |
| `base` | Long in `[1, 10^6]` |
| `session-id` | String, 1..64 chars of `[A-Za-z0-9_-]` |
| `prefix` | String, 1..64 chars of `[a-z0-9 ]` |
| `k` | Long in `[1, 10]` |

Callers only send inputs that satisfy the grammar; the only "invalid"
inputs tests send are the explicit stale-generation and duplicate cases
described in the protocol.

## Workload

- 200 locales. Snapshots are published at most a few times per day per
  locale.
- Search events peak at roughly 100,000 per second across locales. A
  phrase may accumulate up to 1,000,000 unique sessions per generation;
  a generation may accumulate up to 100,000 novel phrases.
- `suggest` peaks at roughly 500,000 per second and dominates load. A
  single-character prefix may match thousands of candidates.
- A locale's block list holds up to 10,000 phrases.

## Bounded-work contracts (enforced)

Tests do not measure wall-clock latency. They enforce the work bounds
below by construction (large corpora, hot prefixes) and by inspection.

- `suggest` read work is independent of the number of candidates
  matching the prefix and of corpus size. It must not scan matching
  phrases at query time.
- `record-search!` processing is fixed work per event, bounded by the
  phrase length and independent of corpus size and session counts.
- `publish-snapshot!` processing may be proportional to the snapshot
  size but must not depend on the size of the previous generation's
  trend data.
- `get-phrase` and `get-generation` do fixed read work.
- Work and storage are balanced across tasks; the harness launches the
  module with 2 or 4 tasks, and tests run with both.

## Production latency aspirations (NOT acceptance thresholds)

- `suggest`: about 20 ms.
- `get-phrase`, `get-generation`: about 50 ms.

These figures describe the production target the design should meet.
No private test asserts them.

## Protocol

Your implementation must satisfy
`hld-search-autocomplete.protocol/Autocomplete`. See
`src/hld_search_autocomplete/protocol.clj`. Every docstring rule is
part of the contract.

Write methods (`!` suffix) return `nil`; their outcomes are observed
through the read methods after `wait-for-processing!`. Read methods must
not wait and must not mutate state.

## Contract: `create-module`

Your namespace must provide a `create-module` function returning:

```clojure
{:module       <RamaModule instance>
 :wrap-client  (fn [ipc] -> <Autocomplete implementation>)}
```

- `:module` — the Rama module to deploy
- `:wrap-client` — given a started IPC cluster, returns a reified
  `Autocomplete` implementation

You choose all internal names (depots, PStates, topologies) freely. All
business state must live in the module: the client wrapper holds no
business data.

## Synchronization: `Synchronizable`

Your `wrap-client` must reify `rama-challenges.harness/Synchronizable`.
See the docstring on that protocol for implementation requirements.
Tests call `(harness/wait-for-processing! client)` after writes, before
reads. Tests never rely on reads observing a write before that call.

## Ordering, clients, and tasks

- Sequential write calls from one client to the same logical owner
  (locale) are processed in invocation order.
- Write calls from different clients may be serialized in any order,
  but every write that returned before a `wait-for-processing!` barrier
  is processed before any write issued after that barrier returns.
- Tests run every scenario with both 2 and 4 tasks, and some scenarios
  drive the module through a second client. Every rule holds in every
  configuration.

## Namespace

Your solution must be in namespace `hld-search-autocomplete.module`.

## File Location

Write your solution to:
```
implementations/hld-search-autocomplete/src/hld_search_autocomplete/module.clj
```

## nREPL

Start an nREPL with the test classpath:

```bash
clj -M:nrepl
```
