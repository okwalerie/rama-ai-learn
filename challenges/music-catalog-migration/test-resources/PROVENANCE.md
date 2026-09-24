# Source provenance

Upstream checkout: `/home/user/workspace/repos/rama-demo-gallery`, HEAD `7b988d986af132a12e5281107e7c0c69287ed704`.

| Retained upstream path | SHA-256 | Retained here | Adaptation |
|---|---|---|---|
| `src/main/clj/rama/gallery/migrations_music_catalog_modules.clj` | `83403e305aae292a1a0da3898ae92156a4c96f8d97e5743fd19c7b3a91d42fbc` | `rama/gallery/migrations_music_catalog_modules.clj` | Both same-name module generations, depot/PState schemas, exact `parse-song` regex, first-element migration guard, migration ID, and write-time parsing preserved. Comments shortened. |
| `src/test/clj/rama/gallery/migrations_music_catalog_modules_test.clj` | `29dd12387c0f12d851bd3cae695653ff616b4dce7ec20bedac1f1cda64f354b0` | Not copied as executable test | Lifecycle scenario used as a guide; assertions independently hand-derived and extended with repeated update/readback and edge parser examples. |

`music_catalog_migration/module.clj` adapts the two Rama module values into one lifecycle factory (`:module`, `:update-module`) and a client that remains attached to the stable module name. `update-module!` is part of the private lifecycle test, not a separate challenge. The source parser is intentionally naive: case-sensitive `ft|feat`, optional periods, split on first match, comma trim/filter. Do not infer a richer music-title grammar.
