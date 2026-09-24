# Source provenance

Upstream checkout: `/home/user/workspace/repos/rama-demo-gallery`, HEAD `7b988d986af132a12e5281107e7c0c69287ed704`.

| Retained upstream path | SHA-256 | Retained here | Adaptation |
|---|---|---|---|
| `src/main/clj/rama/gallery/profile_module.clj` | `2788fc5f128c5a41b800c3501120bfe3587a43397eef8a3c0d697847604838a5` | `rama/gallery/profile_module.clj` | Source namespace, Rama module, record shapes, depots, stream, ID generator, registration check/ack, edits preserved. Explanatory comments shortened; imports trimmed to used dependencies. |
| `src/test/clj/rama/gallery/profile_module_test.clj` | `6f64fe1656c0d61e4c9202cb9195b6dd97991c34e8b435e390996741ac4132e2` | Not copied as executable test | Its stated scenarios informed the independent protocol tests; upstream network-free IPC test omitted in favor of private tests. |

`profile_module/module.clj` adapts raw depots/PState to the standalone challenge protocol. It uses the upstream registration UUID semantics exactly: same username and same UUID is accepted again and generates a fresh ID. No retry/idempotency repair is made. Private tests explicitly expose the surprising result.

The upstream test performs an immediate state read after appends; this package adds an explicit stream-processing barrier. No upstream source links appear in the learner README.
