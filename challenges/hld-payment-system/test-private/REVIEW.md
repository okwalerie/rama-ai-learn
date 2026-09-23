# Private validation review — payment system

The transferred contract manifest matches README, deps, and protocol. The
reference module was inspected without changing it: tenant-keyed commands
write point-addressed subindexed account, charge, request, and journal
entries; accepted journal postings are source-negative then destination-positive;
refunds update only `:refunded-total` in the charge record. No concrete
reference defect was found.

The previous efficiency checks covered one 4-task ledger at 520 transactions,
with absolute read ceilings and no write measurement. The private suite now
compares 200 against 1,000 own accounts/charges/journal rows/requests while
another tenant gains 200 against 1,000 transactions, on both 2 and 4 tasks.
Fixed seven-row page, untouched reader account/balance, original charge,
outcome, and tenant are checked for content. Fund, charge, and refund probes
use fixed amounts and account operands, with fresh request IDs. Hooks sum
RocksDB reads, iterator seeks/steps, and committed write-batch counts over
append through processing barrier. A finite 60 + 1.25× baseline growth
allowance is a heuristic for unbounded scans/fanout, **not** a prescribed
topology, exact operation budget, or latency gate. Instrumentation cannot
measure bytes read/deserialized or rewritten inside an opaque value: a
single growing blob can pass. Source layout inspection supplements, but
does not turn this into a byte-level executable guarantee.

Adversarial business cases now exercise simultaneous missing-account and
wrong-kind/insufficient charge conditions, refund-exceeds before merchant
insufficiency, rejected-command IDs reused for different commands, and an
accepted fund followed by a refund with identical argument vector but the
same request ID. Existing exact journal rows assert fund/charge/refund
posting order and original charge sequence across refunds and module update.
Under the documented ledger operations, a merchant cannot spend except by
refunding its own accepted charges, so a standalone `:insufficient-funds`
refund rejection appears unreachable from valid commands; do not invent a
state-injection fixture to claim this path is covered.

Focused source mutants, run on the selected private test var and reverted:

1. `apply-original` checks merchant insufficiency before refund excess:
   `ledger-two-tasks` → 1 test, 49 assertions, 3 failures, 0 errors.
   `mixed-refund` expected `:refund-exceeds-charge` but got
   `:insufficient-funds` (also caught by an existing refund assertion).
2. `get-balance` scans all journal entries before its point read:
   `bounded-growth-two-tasks` → 1 test, 46 assertions, 1 failure, 0 errors.
   Reads grew 207→1019, exceeding the 318.75 heuristic bound.
3. Cached payload comparison ignores command type and compares only args:
   `ledger-two-tasks` → 1 test, 49 assertions, 1 failure, 0 errors.
   The fund/refund same-args conflict count stayed zero rather than one.

Reference restored byte-for-byte to SHA-256
`5e2b4758eb12a7378ca03c140330fb3f5bfd73149479bff2071cd8f585e12538`.
The full suite is a real IPC check, not crash/replay fault injection and not
proof of all production failure interleavings.
