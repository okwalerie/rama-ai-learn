# Music catalog migration

Update an album catalog from song strings to structured songs containing
`:name` and `:featured-artists`. Use one module lifecycle. Your `create-module`
result must provide the initial module and an `:update-module` hook for the
schema migration. Keep the client protocol usable before and after the update.

Artists and album names are strings; songs are a vector of strings. Identify
albums by `[artist name]`. `album` returns `{:name name :songs [...]}`, or
`nil` if the pair is absent. Before the update, `:songs` contains the supplied
strings. Afterwards, each song exposes `:name` and `:featured-artists`.
Adding an existing pair replaces that album. `add-album!` returns `nil`.

## Parse song credits

Split each string on the case-sensitive regular expression `\s*(ft|feat)\.*`.
This matches `ft` or `feat` with zero or more preceding whitespace characters
and following periods. It needs no word boundary: `feature` matches `feat`.

Use the first segment as the song name and the second as feature text.
Discard later segments. Split feature text on commas, trim each feature and
discard empty features. Without a matching marker, keep the original string
as the name and return an empty feature vector.

For example, `Song ft. A feat. B` becomes name `Song` and features `["A"]`.
Keep existing structured songs unchanged during migration. Parse new appends
after the update. Repeated updates must preserve migrated and newly written albums.

## Interface

Your factory returns
`{:module <initial module> :update-module <new module> :wrap-client ...}`.
The client implements `music-catalog-migration.protocol/MusicCatalog` and
`rama-challenges.harness/Synchronizable`. Its processing barrier works for
both module generations. State must survive the update.

Write `implementations/music-catalog-migration/src/music_catalog_migration/module.clj`. Run the private harness with `clojure -X:test-private-harness` from this directory.
