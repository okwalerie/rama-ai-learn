import { graph as g } from '../graph.mjs';
const source = (slug, lines) => [{ path: `challenges/${slug}/test-resources/${slug.replaceAll('-', '_')}/solution.clj`, lines }];
function algorithm(slug, title, summary, stages, nodes, edges, lines) {
  return [g(`${slug}-lr`, `${title}: function overview`, 'lr', 'solve(input) is a Rama deframafn.', source(slug, lines), `
    input | event | Input text passed to solve
    ${stages.map((label, i) => `step${i} | etl | ${label}`).join('\n')}
    output | end | Result string returned to caller
  `, `input | step0 | parse input
    ${stages.slice(1).map((_, i) => `step${i} | step${i + 1} | function data/control transition`).join('\n')}
    step${stages.length - 1} | output | format answer`),
  g(`${slug}-td`, `${title}: branches and loops`, 'td', summary, source(slug, lines), nodes, edges)];
}
const qa = slug => [{ path: `challenges/${slug}/README.md`, lines: '1-3' }, { path: `challenges/${slug}/corpus-claims.edn`, lines: '1-1' }];
const runtime = [{ path: 'challenges/diagnose-stream-runtime-fail/test-resources/diagnose_stream_runtime_fail/src/com/rpl/challenges/stream_ack_fail.clj', lines: '7-27' }];

export default {
  'almost-equal': algorithm('almost-equal', 'Almost Equal', 'Explicit-stack DFS over unused strings. Compatibility requires exactly one differing character; count-diffs exits early once its counter exceeds one.', ['Parse N, M and strings', 'DFS frames: used mask, previous index, placed count', 'Accept complete ordering or exhaust stack'], `
    start | event | solve(input): push [0, -1, 0]
    stack | decision | Stack empty?
    no | end | Return No
    pop | etl | Pop frame
    complete | decision | placed = N?
    yes | end | Return Yes
    candidate | etl | For each string index
    used | decision | Index unused AND\n(first placement OR count-diffs = 1)?
    push | etl | Push updated mask, index, placed + 1
    next | etl | Finish candidate loop
  `, `
    start | stack | enter DFS
    stack | no | yes
    stack | pop | no
    pop | complete | inspect frame
    complete | yes | yes
    complete | candidate | no
    candidate | used | test candidate
    used | push | compatible
    used | next | used or incompatible
    push | next | candidate recorded
    next | candidate | candidates remain
    next | stack | all candidates tested
  `, '7-72'),
  anti: algorithm('anti', 'Anti', 'Dynamic programming counts substitutions avoiding the XXxY subsequence modulo 998244353. dp0 tracks no repeated uppercase, dp1 a repeated uppercase pair, dp2 a subsequent lowercase. An uppercase after dp2 is discarded.', ['Trim input; initialize dp0[0]=1 and definite-uppercase set D', 'Scan characters: question mark / uppercase / lowercase', 'Sum surviving dp0, dp1, dp2 modulo 998244353'], `
    start | event | solve(input): initialize DP and D
    more | decision | More characters?
    answer | end | Return sum(dp0) + dp1 + dp2 modulo MOD
    type | decision | Character class?
    wildcard | etl | ?: dp0 allows 26 lowercase or unseen uppercase\ndp1 = 26×old dp1 + sum(n×dp0[n])\ndp2 = 26×old dp1 + 26×old dp2
    definite | decision | Uppercase already in definite set D?
    repeat | etl | dp1 += sum(dp0)\ndp0 := zero; dp2 := zero
    split | etl | Split dp0 by symmetry using inverse(26−size D)\nadvance repeated choices; retain unseen choices\nadd uppercase to D; dp2 := zero
    lower | etl | Lowercase: dp2 += dp1\ndp1 := zero; dp0 unchanged
  `, `
    start | more | character loop
    more | answer | no
    more | type | yes
    type | wildcard | question mark
    type | definite | uppercase
    type | lower | lowercase
    definite | repeat | yes
    definite | split | no
    wildcard | more | next character
    repeat | more | next character
    split | more | next character
    lower | more | next character
  `, '12-149'),
  'atcoder-cards': algorithm('atcoder-cards', 'AtCoder Cards', 'Only letters in atcoder can consume @ wildcards. Non-atcoder letter counts must match exactly; deficits are tracked separately for the two strings.', ['Count character frequencies in S and T', 'Accumulate wildcard deficits for a,t,c,o,d,e,r', 'Check other counts and each string’s @ budget'], `
    start | event | solve: count both strings
    loop | decision | More alphabet characters?
    wildcard | decision | Character is @?
    allowed | decision | Character in atcoder?
    deficit | etl | S excess → T needs more @\nT excess → S needs more @
    equal | decision | Counts equal?
    invalid | etl | Set valid := false
    budget | decision | valid AND S need ≤ S @\nAND T need ≤ T @?
    yes | end | Return Yes
    no | end | Return No
  `, `
    start | loop | iterate alphabet plus @
    loop | wildcard | yes
    wildcard | loop | yes: budget checked later
    wildcard | allowed | no
    allowed | deficit | yes
    deficit | loop | next character
    allowed | equal | no
    equal | invalid | no
    equal | loop | yes
    invalid | loop | continue accumulating
    loop | budget | done
    budget | yes | all checks pass
    budget | no | any check fails
  `, '7-78'),
  attack: algorithm('attack', 'Attack', 'The reference performs direct integer ceiling division. Input constraints supply positive damage; there is no simulated attack loop or rejection branch.', ['Parse health A and damage B as Long', 'Compute integer quotient (A+B−1)/B'], `
    start | event | solve(input)
    parse | etl | Trim and split whitespace; parse A and B
    ceil | etl | result := quot(A + B − 1, B)
    out | end | Return decimal result string
  `, `
    start | parse | two tokens
    parse | ceil | positive constrained inputs
    ceil | out | stringify
  `, '7-12'),
  bitmask: algorithm('bitmask', 'Bitmask', 'Greedy tight-prefix search with a saved wildcard fallback. max-tail precomputes the maximum possible suffix; exceeding N returns the latest fallback or −1.', ['Build max-tail suffix values right-to-left', 'If full maximum exceeds N, walk most-significant bits', 'Keep tight prefix, go loose, or return saved fallback'], `
    start | event | Parse pattern S and bound N; build max-tail
    all | decision | N ≥ max-tail[0]?
    max | end | Return max-tail[0]
    loop | decision | More bits while tight?
    exact | end | Return current prefix value
    bit | decision | Pattern bit / N bit?
    loose | end | Fixed 0 / N 1: return prefix + max suffix
    fallback | end | Fixed 1 / N 0: return fallback, initially −1
    save | etl | ? / N 1: save fallback for choosing 0\nchoose 1 to stay tight
    stay | etl | Matching fixed bit or ? / N 0\nappend matching bit; keep fallback
  `, `
    start | all | compare full maximum
    all | max | yes
    all | loop | no: prefix=0, fallback=−1
    loop | exact | no
    loop | bit | yes
    bit | loose | fixed 0 / 1
    bit | fallback | fixed 1 / 0
    bit | save | wildcard / 1
    bit | stay | matching bit or wildcard / 0
    save | loop | advance bit
    stay | loop | advance bit
  `, '10-87'),
  'fill-the-gaps': algorithm('fill-the-gaps', 'Fill the Gaps', 'Append every integer between adjacent inputs in ascending or descending order, including the next endpoint once. The first input seeds the output.', ['Parse integer vector', 'Choose step +1 or −1 for each adjacent pair', 'Append intervening values; join with spaces'], `
    start | event | Parse numbers; output := first element
    pair | decision | Next adjacent pair exists?
    direction | decision | previous < current?
    up | etl | step := +1
    down | etl | step := −1
    init | etl | j := previous + step
    more | decision | j ≠ current + step?
    append | etl | Append j; j += step
    next | etl | Advance input pair
    end | end | Return space-joined output
  `, `
    start | pair | begin at second input
    pair | end | no
    pair | direction | yes
    direction | up | yes
    direction | down | no
    up | init | ascending
    down | init | descending
    init | more | inclusive endpoint loop
    more | append | yes
    append | more | next integer
    more | next | no
    next | pair | next pair
  `, '7-46'),
  'find-snuke': algorithm('find-snuke', 'Find Snuke', 'Search row-major starting cells and eight directions. Each candidate must stay in bounds and match all five letters. The problem guarantees a match; the final formatter assumes five returned cells.', ['Parse grid; scan cells containing s', 'Check snuke in eight straight directions', 'Return five one-indexed coordinate lines'], `
    start | event | solve: parse grid
    cell | etl | Scan next row-major cell
    s | decision | Cell contains s?
    dir | etl | Try next of eight directions
    bounds | decision | Candidate position in bounds\nand matches target[step]?
    match | decision | Five matched positions?
    step | etl | Increment character step
    next | decision | More directions from this cell?
    result | end | Return five 1-indexed coordinate lines
  `, `
    start | cell | search
    cell | s | inspect character
    s | cell | no
    s | dir | yes
    dir | bounds | start at step 0
    bounds | next | failure
    bounds | match | match: append position
    match | result | yes
    match | step | no
    step | bounds | next position
    next | dir | yes
    next | cell | no
  `, '7-93'),
  'impartial-gift': algorithm('impartial-gift', 'Impartial Gift', 'For each A value, binary-search sorted B for its largest value ≤ a+D, then reject it if below a−D. This maximizes the pair sum for that A; the outer loop keeps the best.', ['Parse A, B, tolerance D; sort B', 'Binary search largest b in [a−D,a+D] for each a', 'Maximum valid a+b, or −1'], `
    start | event | solve: sort B; best := −1
    a | decision | More values in A?
    end | end | Return best as string
    search | etl | Binary search rightmost b ≤ a+D
    mid | decision | left ≤ right?
    compare | decision | B[mid] ≤ upper bound?
    right | etl | Save mid; search right half
    left | etl | Search left half
    valid | decision | Found index AND B[index] ≥ a−D?
    update | etl | best := max(best, a+B[index])
  `, `
    start | a | iterate A
    a | end | no
    a | search | yes
    search | mid | initialize bounds
    mid | compare | yes
    compare | right | yes
    compare | left | no
    right | mid | narrower interval
    left | mid | narrower interval
    mid | valid | search complete
    valid | update | yes
    valid | a | no: skip a
    update | a | next a
  `, '8-66'),
  'overall-winner': algorithm('overall-winner', 'Overall Winner', 'The implementation first counts totals, then performs a second scan only on a tie. It returns the first player to reach the tied final count.', ['Count T and A wins', 'Compare totals; on tie scan running counts', 'Return winner letter'], `
    start | event | solve: parse game string
    count | etl | Loop over characters; count T and A
    totals | decision | Compare final totals
    t | end | Return T
    a | end | Return A
    scan | etl | Reset running counts; scan next game
    type | decision | Winner of next game?
    tc | decision | Increment T; reached tied target?
    ac | decision | Increment A; reached tied target?
  `, `
    start | count | initial scan
    count | totals | complete
    totals | t | T larger
    totals | a | A larger
    totals | scan | equal
    scan | type | next character
    type | tc | T
    type | ac | A
    tc | t | yes
    ac | a | yes
    tc | scan | no
    ac | scan | no
  `, '7-47'),
  pac: algorithm('pac', 'Pac', 'BFS compresses the grid to pairwise key-node distances. Subset DP stores the least cost for each collected mask and last candy; feasibility includes the remaining trip to G.', ['Parse grid; locate S, G and candies', 'BFS from every key node builds distance matrix', 'Subset DP over candy masks; check goal reachability within T'], `
    start | event | solve: build key-node distance matrix
    zero | decision | No candies?
    direct | decision | S→G reachable in ≤T?
    init | etl | Initialize dp[single candy][candy]\nfrom reachable S→candy distances
    state | etl | Enumerate mask and last candy
    reachable | decision | Last candy in mask AND state reachable?
    target | decision | Next candy outside mask\nAND pair distance reachable?
    better | decision | New cost improves DP entry\nor entry absent?
    update | etl | Save minimum cost for extended mask
    scan | etl | After all masks: scan dp + last→G\nkeep max popcount whose cost ≤T
    result | end | Return maximum feasible count\ninclude 0 if direct trip works; else −1
    no | end | Return −1
    yes | end | Return 0
  `, `
    start | zero | distances use −1 for unreachable
    zero | direct | yes
    direct | yes | yes
    direct | no | no
    zero | init | no
    init | state | iterate subset masks
    state | reachable | inspect state
    reachable | state | no: next state
    reachable | target | yes: enumerate next candy
    target | state | no: skip candidate
    target | better | yes: add travel distance
    better | update | yes
    better | state | no
    update | state | next transition
    state | scan | all masks exhausted
    scan | result | test goal reachability and budget
  `, '64-283'),
  'qa-depot-partitioning': [
    g('qa-partition-lr', 'A partitioning design question, not a module', 'lr', 'This flow organizes the repository’s Q&A corpus claims. It describes choices and consequences, not an implemented topology or a mandated recommendation for every workload.', qa('qa-depot-partitioning'), `
      question | event | Which depot partitioner fits the workload?
      scope | decision | Ingress and ordering requirements
      hash | etl | hash-by field / top-level extractor / identity
      random | etl | random: distribute without key affinity
      special | etl | disallow / tick / global for distinct purposes
      consequence | end | Document ownership, ordering and routing cost
    `, `
      question | scope | assess workload
      scope | hash | key locality and local ordering matter
      scope | random | no key affinity needed
      scope | special | internal, timed or singleton ingress
      hash | consequence | align primary PState key
      random | consequence | downstream hash may be needed
      special | consequence | restricted append or throughput semantics
    `),
    g('qa-partition-td', 'Decision flow: ingress restrictions before routing policy', 'td', 'Source is the challenge’s extracted corpus, not a deployed program. Tick depots emit on task 0; an all-task continuation is explicit, not implied. Global depots are for low-throughput single-partition use.', qa('qa-depot-partitioning'), `
      start | event | Design a depot for a workload
      timer | decision | Time-driven impetus?
      tick | end | Tick depot; all-task routing if needed
      internal | decision | Reject external appends?
      disallow | end | disallow; topology-owned writes
      global | decision | Deliberate low-throughput singleton?
      singleton | end | global depot on task 0
      keyed | decision | Key affinity / ordering required?
      hash | etl | hash-by keyword, identity or top-level var
      random | end | random distribution; no cross-record ordering
      match | decision | Primary PState owner key matches?
      local | end | Local reads/writes on aligned task
      hop | end | Explicit hash hop for secondary key\nextra network hop; ordering implications
    `, `
      start | timer | choose ingress semantics
      timer | tick | yes
      timer | internal | no
      internal | disallow | yes
      internal | global | no
      global | singleton | yes
      global | keyed | no
      keyed | random | no
      keyed | hash | yes
      hash | match | inspect state access
      match | local | yes
      match | hop | no
    `, ['Custom defdepotpartitioner is another source-listed option: data and partition count determine a partition index. This decision flow is a reading aid, not an exhaustive policy engine.'])
  ],
  'qa-rama-integration': [
    g('qa-integration-lr', 'Integration seams described by the Q&A corpus', 'lr', 'Conceptual integration alternatives. No challenge reference module instantiates these systems; the named APIs are from the corpus supplied for this question.', qa('qa-rama-integration'), `
      existing | external | Existing application / external log
      choose | decision | Integration direction?
      ingress | etl | ExternalDepot adapter\nnonblocking partition fetches
      client | etl | Reused foreign client handles\nappend / select / query
      resource | etl | TaskGlobal external clients and caches\nasync future calls in topology
      rama | end | Rama state and topology boundary
    `, `
      existing | choose | identify seam
      choose | ingress | consume existing Kafka/Kinesis/log
      choose | client | app-driven reads/writes
      choose | resource | topology needs external resource
      ingress | rama | source consumption + backpressure
      client | rama | protocol API interaction
      resource | rama | per-task lifecycle and async completion
    `),
    g('qa-integration-td', 'Choose a seam, then verify its completion semantics', 'td', 'This is a source-grounded reasoning flow, not runtime evidence. External depots are read-only from Rama’s perspective; blocking external calls would stall a task.', qa('qa-rama-integration'), `
      start | event | Introduce Rama into existing tooling
      log | decision | Must consume an existing partitioned log?
      adapter | etl | ExternalDepot: future-returning fetch API\nprepareForTask / close; capacity backpressure
      api | etl | Application reuses foreign depot/state/query handles
      operation | decision | Read/query or append?
      response | end | Source-side selection/query → app response
      ack | decision | Append consumed by stream or microbatch?
      stream | end | Full stream ack waits for processing effects
      mb | end | Microbatch append ack is durability only\nuse separate processing evidence
      side | decision | Topology invokes external side effects?
      retry | end | Manage task-global client lifecycle\nasync future; version microbatch writes
    `, `
      start | log | establish source ownership
      log | adapter | yes
      log | api | no: application API integration
      api | operation | choose call
      operation | response | read / query
      operation | ack | append
      ack | stream | stream
      ack | mb | microbatch
      adapter | side | consuming topology
      side | retry | yes
      side | response | no: state/query interface
    `)
  ],
  'diagnose-stream-runtime-fail': [
    g('runtime-lr', 'The supplied diagnostic fixture: parse before state mutation', 'lr', 'Unlike the prior atlas’s unknown-state placeholder, the inspected hidden setup includes this exact module. This documents its source, not a live-cluster diagnosis or a claim that it is currently deployed here.', runtime, `
      client | external | External JVM client
      depot | depot | *payments: random partition
      parse | etl | payments stream: Long.parseLong(amount)
      hash | route | hash(user-id)
      state | state | $$payment-totals\nString user-id → Long sum
      ack | end | ack-return status ok\nfull append acknowledgement
      fail | end | Malformed amount: runtime exception\nevent tree cannot complete successfully
    `, `
      client | depot | foreign-append! with :ack
      depot | parse | consume amount string
      parse | hash | numeric string parses
      parse | fail | abc throws NumberFormatException
      hash | state | read current or 0; add parsed amount
      state | ack | transform completes
      ack | client | successful processing response
    `),
    g('runtime-td', 'Poison-pill event: find the failed completion boundary', 'td', 'The required event is user u1 with amount abc. The challenge forbids code edits or redeployments. The diagram explains the source-backed failure and diagnostic workflow without executing that harmful setup script.', [...runtime, { path: 'challenges/diagnose-stream-runtime-fail/README.md', lines: '1-40' }], `
      start | event | Append user-id=u1, amount=abc with :ack
      consume | etl | *payments consumed by payments stream
      parse | decision | Long.parseLong(amount) succeeds?
      route | route | hash(user-id)
      state | state | Read $$payment-totals[user] or 0\nwrite current + parsed amount
      ack | end | Event tree completes; ack-return status ok
      failure | etl | Runtime parse exception before routing/write
      inspect | external | Inspect conductor/runtime errors\ndistinguish processing from depot durability
      answer | end | Write diagnosis; do not modify or redeploy
    `, `
      start | consume | appended event
      consume | parse | amount string
      parse | failure | abc: no
      parse | route | numeric counterfactual: yes
      route | state | local keyed state
      state | ack | successful branch
      failure | inspect | no successful full acknowledgement
      inspect | answer | identify root exception and ack semantics
    `),
    g('runtime-partition', 'Fixture state ownership after parsing', 'partition', 'This close-up is justified by the supplied fixture source, not inferred from the diagnostic README. Random ingress does not determine the final state owner.', runtime, `
      random | depot | *payments: random ingress task
      parse | etl | Parse amount
      hash | route | hash(user-id)
      state | state | $$payment-totals\nString user-id → Long total
    `, `
      random | parse | payments stream
      parse | hash | only on successful parse
      hash | state | local read + transform
    `)
  ]
};
