# Imported source provenance

The upstream snapshot `rama/gallery/top_users_module.clj` is byte-identical to
rama-demo-gallery at
`7b988d986af132a12e5281107e7c0c69287ed704`. Its own module comments, README
entry, and matching test were used to derive the observable challenge
contract; no other mounted repositories were consulted.

`top_users_module/module.clj` adapts calls to upstream `Purchase`, depot,
and top-spending PState operations, and synchronizes waits on the upstream
`topusers` microbatch. The topology itself is unchanged. The contract assumes
nonnegative purchases; ties are unspecified; there are no purchase IDs or
deduplication semantics. No efficiency, throughput, or recovery measurement
was made.
