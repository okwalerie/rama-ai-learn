# Architecture atlas visual specification

Derived from a bounded Claude Opus 5.5 CLI analysis of the [Rama Twitter-scale article](https://blog.redplanetlabs.com/2023/08/15/how-we-reduced-the-cost-of-building-twitter-at-twitter-scale-by-100x/). The analysis visually inspected 26 images, including module inventories, PState partition grids, ETL dataflow graphs, and deployment nesting. It sampled, rather than inspected, every intermediate animation frame and telemetry screenshot.

- Keep abstraction levels separate. The **LR inventory** has four columns—Depots, ETLs, PStates, Query Topologies—with named nodes and write arrows. The **TD dataflow** describes one end-to-end write/query path with verbs and explicit routing. Do not suggest that an absent category exists.
- In the inventory, use exact source identifiers when verified. Connect depot → consuming ETL → written PState; show queries as read edges only when source-backed. A type or partition claim must have evidence.
- In dataflow, use solid arrows for event/control progression; dashed, labelled arrows for Reads/Writes and routing hops. Clearly mark a key's hash/direct partition and the PState partition it reaches. Show client writes and query paths separately within the TD view. Plain-English labels explain operations.
- Partition views use repeated bounded task/partition boxes and scatter distinct keys across them. Repeated keys in multiple state panels on one task illustrate colocation; do not imply that unrelated keys share a partition or invent record samples.
- Encode roles with shape and color as well as text: depot ingress, ETL processing, durable PState, and query. Group by columns or nested containers, not decorative swimlanes. Favor concise labels and adequate contrast.
- The original article uses LR module inventories and TD ETL flows; its diagrams often omit partition hops and query read edges. This atlas adds those only where supported, and uses “not specified” rather than guessing.
- Inspiration is the diagram grammar, not the article's assets, exact styling, or architectural claims.

## Flow Edition: reviewed interpretation

The study above is preserved as input, not as authority over source or user feedback.
The final user clarification keeps **LR at overview level**: four columns labelled
**Depot / ETL / PState / Query**, named components, and main consume/write/read
connections only. Related PStates and query topologies may share a box to keep this
view compact. No guards, event branches or routing hops belong in LR. A module
without query topologies says so; a direct PState client read is not renamed a query
topology. Algorithm and Q&A entries use function/reasoning overviews instead of
invented module columns.

**TD is the detailed view.** Each scenario starts at an event, request, tick or
accepted continuation; labelled directed edges expose important type cases,
rejections, loops, routing, state writes/reads, and outcomes. Separate partition
close-ups show logical ownership and nested schemas, not invented task placements.
Mirrors and ephemeral memory are explicitly distinguished from owned durable state.

The Portal renders a restricted data-only graph grammar through locally bundled
Mermaid 11.12.0 in strict mode with HTML labels disabled. Identifiers, roles and edge
endpoints are validated; labels cannot provide diagram directives or callbacks.
Reserved punctuation uses visible Unicode equivalents in the SVG, while accessible
transition lists preserve the original label text. Render failures expose an alert
and open the complete transition list. Zoom, pan, expansion, Escape, and SVG export
remain available. TD diagrams have full natural height rather than a cropped nested
vertical window. Wide diagrams remain horizontally explorable on small screens.

### Evidence and repeatable validation

- Handoff: exact eight-file `docs/atlas` tree from the source thread archive,
  SHA-256 `2f56a4020532efa84f844ecf32fe15c7fd83063098df903d1cc392bb1767dad1`.
- Diagram citations bind to repository revision
  [`e2bfe2e`](https://github.com/okwalerie/rama-ai-learn/commit/e2bfe2e0dcca2a5b2683fe818acfe15b0c1b8dc5).
  README/protocol requirements and reference behavior are separate evidence.
- `npm ci && npm run build` from `docs/atlas` reproduces the local Mermaid bundle.
- `npm test` checks all 35 qualifying routes (8 README-provenance LeetCode
  exclusions), source files and line ranges, edges, ownership views, four-column
  overview boundaries, exemplar branch coverage, and invalid-grammar controls.
- Serve `docs/atlas`, open a browser, then run
  `import('./browser-check.mjs').then(m => m.run())` in its console. Repeat at desktop
  and mobile widths. `window.atlasCheck` records every route/graph, five-tab coverage,
  SVG bounds and label containment, overview column order, zoom/expand/Escape,
  accessibility, links, and hostile-label/error-fallback controls.
- Browser geometry tests are supplemented by screenshot inspection of ChatApp,
  Fanout, File Sync, Stock Exchange, Metrics and Enterprise RAG. They do not prove
  Rama runtime behavior: this atlas documents inspected source, not a new cluster run.

Known source caveats are retained alongside the relevant diagrams: ChatApp reference
presence is 30 seconds versus the protocol's 120 seconds; its derived reply gate
checks the root rather than the persisted reply. Fanout cold reconstruction reads
300 followees × 10 posts and does not explicitly merge self posts. The combined
module uses local hash/direct routing rather than mirrors. Auction scheduler
internals remain library-owned. The runtime diagnosis uses the actual fixture and
does not execute its poison-pill setup. HLD exclusions and CC BY-SA provenance are
preserved in the one-pagers and source links.

## Tufte and clean-copy revision

The personal `agent-skills:tufte` and `agent-skills:clean-copy` skills guide the
current rendering. This is a control/data-flow atlas, not a quantitative chart:
node size does not encode volume, cost or throughput. Alphabetical ordering serves
the challenge index; event order and directed edges govern diagrams.

- Remove decorative frames, shadows, dotted backgrounds, display fonts, slogans
  and oversized count tiles. Use a compact coverage sentence and system fonts.
- Use one rust accent for inputs and decision branches. Keep processing and
  routing neutral, PStates lightly shaded, and ephemeral/external boxes dashed.
  Direct labels and shapes carry meaning without a separate color legend.
- Preserve source identifiers, all transitions, partition keys and query/client
  distinctions. Do not shorten a node label at the expense of technical meaning.
- Reduce graph spacing while retaining full-height scenarios and explicit edges.
  Wide graphs can pan; Fit offers a complete view without page overflow.
- Keep source notes and citations next to their diagram. Offer scenario navigation
  for long TD pages, and place general instructions in a collapsed disclosure.
- Align one-pager section labels beside prose on desktop and above it on narrow
  screens. Use sentence-case headings and 14–15px body text.
- Remove repeated headings and captions. Cut algorithm caveats that only repeat
  their function scope; retain numeric limits, discrepancies and provenance.

The browser check also exercises scenario navigation without changing the challenge
route, verifies focus follows the selected diagram, and checks Fit and expanded
views for overflow. Re-run at 1440px and 390px after visual or copy changes.
