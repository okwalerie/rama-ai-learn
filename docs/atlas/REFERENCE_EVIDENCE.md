# External-reference import verification

Evaluator-only evidence for the nine packages in [the inventory](REFERENCES.md).
No scored solver evaluation was run, and no candidate implementation was used
as an answer. Reference classpaths replace candidate paths; ordinary private
tests resolve candidate factories and cannot import upstream namespaces.

## Reference harnesses and negative controls

Run from each challenge directory:

```sh
JDK_JAVA_OPTIONS='-Xmx1g -XX:ActiveProcessorCount=2' timeout 240s clojure -X:test-private-harness
```

All suites exercise 2- and 4-task IPC deployments. The editor harness also
runs its retained upstream tests. Baselines completed without failures or
errors. The worker receipts for family, editor and moderation are retained
under each package's `test-private/EVIDENCE.md`; their baselines were also
rerun after integration. Six other packages were tested independently in
disposable copies by `scripts/check_reference_mutations.py`.

Final integrated baseline counts: family 32, editor 49, moderation 50,
recommendations 42, timers 20, spend ranking 14, profiles 28, HTTP 24 and
catalog 24 assertions: 12 tests / 283 assertions total, zero failures/errors.

| Package | Mutation | Failed assertions | Runtime errors |
| --- | --- | ---: | ---: |
| family-tree | Increment returned descendant depth-2 count | 6 | 0 |
| collaborative-document-editor | Prefix actual inserted content with `!` | 16 | 0 |
| content-moderation | Skip actual mute filter | 26 | 0 |
| who-to-follow | Skip already-followed exclusion | 8 | 0 |
| timed-notifications | Replace delivered post text | 14 | 0 |
| top-users-module | Change top cap from 500 to 499 | 4 | 0 |
| profile-module | Store incorrect registration password hash | 14 | 0 |
| rest-api-integration-module | Replace actual HTTP response extraction | 10 | 0 |
| music-catalog-migration | Discard parsed featured artists | 12 | 0 |

These controls modify implementation behavior and run unchanged acceptance
assertions. Earlier disconnected intentional-failure checks were rejected and
removed; they are not counted as mutation evidence. Machine-readable baseline
and mutant counts, source digests and exact edits for six packages are in
[reference-mutations.json](reference-mutations.json). The recommendation and
timer suites subsequently gained return-value assertions and adapters now
explicitly return nil as their public protocols require; final baseline
counts therefore differ from those earlier mutation receipts.

Reproduce those six controls from the repository root:

```sh
python3 scripts/check_reference_mutations.py \
  who-to-follow timed-notifications top-users-module profile-module \
  rest-api-integration-module music-catalog-migration --output /tmp/reference-mutations
```

## What is and is not covered

- Family tests distinguish distinct ancestors from descendant paths, zero
  generations, terminal zeros, missing nodes and state retained through update.
- Editor tests distinguish submitted edits from stored operations, tied
  insertions, split removals, subsumed removals and separate document IDs.
  General overlapping-removal correctness is not qualified. Stale insertion
  inside a deleted span has a demonstrated source defect and is excluded.
- Moderation checks raw versus visible offsets, all-muted and long-hidden
  runs, duplicate appends, directional mutes and historical visibility.
  The adapter's documented beyond-end guard avoids an upstream range error.
- Recommendations check two-hop direction, duplicates, exclusions, skewed
  multi-page sweeps, subsequent graph changes and update retention. Tests do
  not yet distinguish the 1,000-candidate pre-filter boundary or 300-result cap.
- Timers check the 799/800 and 1499/1500 boundaries, account ownership,
  repeated ticks and pending/delivered state through an update.
- Spend ranking checks repeated purchases, cumulative rank changes, update
  retention and 501 users competing for 500 slots.
- Profiles check username conflicts, fresh IDs for repeated accepted UUIDs,
  independent users and last-edit-wins fields.
- HTTP tests use a disposable loopback server: 200/503 bodies, repeated URL
  replacement and a delayed response with a nonblocking caller. They do not
  inject transport failure or verify task-client cleanup under a crash.
- Catalog tests update A→B in place, retain keys, replace an album, repeat B
  update, and distinguish case, embedded and repeated feature markers.

Module updates are not worker/process crashes. No depot replay/failure
injection, distributed-host deployment, throughput measurement, storage-growth
measurement or independent alternative candidate was executed. Tests accept
protocol behavior rather than inspecting candidate topology/PState names.
The source cost analysis records growing edit history, family path expansion,
filtered-feed scans, per-user totals and the bounded top-list output; it is
not an asymptotic benchmark. These limits must remain visible in evaluation
claims rather than being inferred from green IPC tests.

## Isolation and atlas verification

- `bb scripts/run_challenges_test.bb`: 36 tests, 345 assertions, no failures/errors.
- `python3 -m unittest discover -s scripts -p 'test*solver*.py' -v`: 11 tests passed,
  including real bubblewrap denied reads of atlas/review and retained author reads.
- `python3 -m unittest discover -s scripts -p test_reference_packages.py -v`:
  two tests passed across all nine packages, checking actual snapshot contents
  and disjoint candidate/reference classpaths.
- `python3 -m unittest discover -s scripts -p test_serve_atlas.py -v`: source
  rendering, escaped content, line anchors, digest and traversal/symlink denials passed.
- Public protocol/source `clj-kondo`: zero errors and warnings. Private reference
  compilation is exercised by every harness; no whole-repository lint claim.
- Atlas `npm ci`, `npm run build`, and `npm test` passed: 44 routes, 154 graphs,
  1,509 directed edges, valid source ranges and ownership views.
- Chromium browser checks exercised all five tabs, diagrams, fit/zoom/expand,
  accessible SVG/text fallback, hostile labels and page overflow at desktop
  1440px and narrow 390px. This is responsive Chromium, not a physical phone.
  Representative fitted overview, scenario and narrow one-pager screenshots
  were inspected. Existing locked npm dependencies report seven audit findings
  (six moderate, one high); dependency upgrades were not part of this import.

The supervised `reference-atlas` service runs `scripts/serve_atlas.py` on port
8765. Local source links show unshipped checkout content with SHA-256 rather
than linking to nonexistent remote files. The runner refuses both unisolated
and filesystem-only solver launches while atlas/review is present: use
`--isolate-network` (currently Claude or OpenCode). Encryption is not the
access boundary. Allowed provider/documentation hosts and model prior
knowledge remain outside this narrow filesystem/network proof.
