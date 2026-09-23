# Oracle review — September 23, 2026

Requested review inspected the full reference, protocol, README and private
functional/performance support. No reference correctness defect was found by
static inspection. These findings require BUILD follow-through; they are not
executed mutation evidence.

## Known performance repairs

Remove depot-read/exact800/equal-cost assertions. Use public bounded-growth
budgets, threads=tasks for commit attribution, and aggregate point/iterator
read work rather than mandatory point reads. Use same-wrapper config/check
pairs or a barrier between them. Measure earliest/latest/missing decisions,
retries and denials at both history sizes. Event metadata cannot measure
opaque serialized history size: disclose the review limitation, do not
invent byte counters or force schemas.

## Missing adversarial assertions

1. User-only over-capacity: enforcing user capacity2, endpoint capacity5,
   refill0. Cost3 at100 must be cost-exceeds-capacity, not insufficient;
   remaining2/5. Unknown endpoint at101 must be allowed=false, remaining=nil.
   After each, status at0 must remain clock0/full. Mutants: drop user-capacity
   condition; hardcode unknown endpoint allowed=true.
2. Future shadow denials: shadow user capacity4/refill1, endpoint6/refill1.
   Debit3 at10 leaves1/3. Cost3 at11 is insufficient, projected2/4; cost5
   at1000 exceeds capacity, projected4/6; unknown endpoint at1000 returns
   nil remaining. All are allowed=true/would-allow=false. After EACH, status
   at0 must remain clock10 and1/3. Mutants: advance clock based on allowed
   instead of would-allow; persist projected balances on denial.
3. Payable changed-body replay across versions: enforcing v1 user/endpoints
   a,b capacity10/refill0. Queue paid(a,3,10), paid(b,2,100) via same wrapper
   before barrier. Only first debits: clock10,user7,a7,b10. Then denied(a,8,11)
   records insufficiency without advancing clock. Install shadow v2 retaining
   only b; reset full, clock10. Other wrapper retries both IDs on b with
   cost1/future ticks. Both original maps survive (including denied allowed=false),
   buckets remain full and clock10. Mutants: treat denied cache as absent;
   re-evaluate cached IDs after config change; debit changed body while retaining
   old decision.

## Positive static evidence

evaluate-check returns no limiter update on denials and updates both buckets
and clock together on debit. new-limiter preserves clock. Duplicate lookup
precedes evaluation, and decisions are stored separately from replaced limiter.
Existing tests already cover both directions of one-sided insufficiency and
ordinary clock preservation across configuration replacement.
