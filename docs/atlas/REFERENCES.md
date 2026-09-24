# Reference implementation inventory

This authoring inventory covers only the Clojure examples in
[Next-level Backends](https://github.com/okwalerie/next-level-backends-with-rama-clj/tree/1b2e0430539962f1b8798b157e3491ccb8b821fe)
and [Demo Gallery](https://github.com/okwalerie/rama-demo-gallery/tree/7b988d986af132a12e5281107e7c0c69287ed704).
Java counterparts and every other mounted repository are excluded. There are
11 distinct examples, represented by 12 module definitions: the music catalog's
two definitions are successive versions of one application, not two challenges.

## Next-level Backends: five new packages

Paths below are relative to the pinned Next-level Backends checkout. Each own
blog post was read in full. Posts explain intent; Clojure source and observed
behavior govern the imports when prose and code differ.

| Package | Implementation | Associated upstream tests | Last source change | Own blog and bounded problem |
| --- | --- | --- | --- | --- |
| family-tree | `src/nlb/family_tree.clj` | `test/nlb/family_tree_test.clj` | 65f7276, 2025-04-15 | [Graphs](https://blog.redplanetlabs.com/2025/03/26/next-level-backends-with-rama-graphs/): two-parent family graph and generation-bounded ancestor/descendant queries |
| collaborative-document-editor | `src/nlb/collaborative_document_editor.clj` | `test/nlb/collaborative_document_editor_test.clj` | b05c963, 2025-04-01 | [Collaborative editor](https://blog.redplanetlabs.com/2025/04/01/massively-scalable-collaborative-text-editor-backend-with-rama-in-120-loc/): versioned additions/removals and atomic document/version reads |
| who-to-follow | `src/nlb/who_to_follow.clj` | `test/nlb/who_to_follow_test.clj` | 3dc0d14, 2025-04-08 | [Recommendations](https://blog.redplanetlabs.com/2025/04/08/next-level-backends-with-rama-recommendation-engine-in-80-loc/): periodic shared-follow ranking, candidate cap and exclusions |
| timed-notifications | `src/nlb/timed_notifications.clj` | `test/nlb/timed_notifications_test.clj` | 7eb28db, 2025-04-16 | [Timed notifications](https://blog.redplanetlabs.com/2025/04/16/next-level-backends-with-rama-fault-tolerant-timed-notifications-in-25-loc/): schedule feed appends against a clock |
| content-moderation | `src/nlb/content_moderation.clj` | `test/nlb/content_moderation_test.clj` | b5b05a8, 2025-04-29 | [Personalized moderation](https://blog.redplanetlabs.com/2025/04/29/next-level-backends-with-rama-personalized-content-moderation-in-60-loc/): reversible per-reader mutes and filtered pagination |

The existing social-graph/fanout challenges overlap in graph primitives, not in
family traversal, recommendation ranking or read-time moderation contracts.
HLD notification/job-scheduler and auction challenges overlap in timers but
have different delivery/state-machine problems. HLD file-sync is not a
character-level collaborative editor. These are separate package boundaries.
No Mastodon source was used, including for recommendations.

## Demo Gallery: four new packages and two existing overlaps

Implementation paths are under `src/main/clj/rama/gallery/`; associated tests
are under `src/test/clj/rama/gallery/`. The repository README, source comments
and matching Clojure tests supply the documentation. They are sufficient for
these boundaries; no HLD case study inference was needed.

| Package/disposition | Implementation file | Associated test file | Last source change | Observable boundary |
| --- | --- | --- | --- | --- |
| profile-module, new | `profile_module.clj` | `profile_module_test.clj` | 0a7a616, 2023-12-18 | Username claims, generated IDs, partial field edits; repeated accepted UUID allocates a new ID in this source |
| top-users-module, new | `top_users_module.clj` | `top_users_module_test.clj` | c4e8483, 2023-10-10 | Cumulative nonnegative spending and top 500 distinct users |
| rest-api-integration-module, new | `rest_api_integration_module.clj` | `rest_api_integration_module_test.clj` | 172a92c, 2024-08-05 | Asynchronous HTTP GET and latest response body; status codes differ from transport failures |
| music-catalog-migration, new | `migrations_music_catalog_modules.clj` | `migrations_music_catalog_modules_test.clj` | d455703, 2024-09-27 | One live catalog evolving from strings to structured songs through module update |
| bank-transfer-module, existing | `bank_transfer_module.clj` | `bank_transfer_module_test.clj` | 8fa840f, 2025-03-11 | Balances and incoming/outgoing transfer outcomes; existing challenge already covers it |
| time-series-module-hard, existing | `time_series_module.clj` | `time_series_module_test.clj` | 0f4f7f1, 2024-05-30 | Multi-granularity latency aggregates and efficient half-open range reads; existing challenge omits the source's last-value field |

The two existing packages are retained, not duplicated or used as import
answers. Gallery migrations preserve both source generations. Per-package
`test-resources/PROVENANCE.md` records integration changes; private test
receipts distinguish tested updates from untested crash recovery. The NLB
project declared Rama 1.0.0; these packages run against this repository's Rama
1.9.0 and helpers 0.10.0. The recommendation cursor needs a documented
`rseq`-to-`last` compatibility adaptation for Rama's sorted-map view.

## Authoring and solver boundary

This atlas contains reference decisions. It is evaluator-facing, not part of
the public challenge contract. `scripts/isolate_solver.py` copies an explicit
public allowlist; it excludes the atlas, review corpus, private test/reference
directories, Git history and sibling reference mounts. `run-challenges` now
refuses solver launches without `--isolate-network` while `docs/atlas` or
`review` exists. This also prevents access to the live atlas Portal and the
upstream GitHub/blog hosts through the supported network policy. Strict mode
currently supports Claude and OpenCode/OpenRouter, not Codex or Pi. It is not
a guarantee against knowledge already present in a model or every possible
side channel through an allowed provider/documentation host.

Serve with `python3 scripts/serve_atlas.py --port 8765`. New source links read
this checkout through the restricted `/source/` endpoint and display a file
digest. They do not pretend unpushed files are available on GitHub. Existing
atlas diagrams retain their original pinned revision links.
