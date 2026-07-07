# ADR-0001: cloud-itonami-isic-6530 -- Pension-LLM as a contained intelligence node

- Status: Accepted (2026-07-07)
- Related: `cloud-itonami-isic-6511`/`6512`/`6621`/`6622`/`6629`/`6520`
  ADR-0001s (the pattern this ADR ports; `6512`'s and `6622`'s ADRs
  establish the "write the lesson down, don't just fix it" discipline
  this build reapplies to a NEW bug), ADR-2607032000 (`cloud-itonami`
  insurance (ISIC 65/66) + real-estate (ISIC 68) coverage push -- the
  blueprint scaffold this ADR deepens), langgraph-clj ADR-0001 (Pregel
  superstep + interrupt + Datomic checkpoint)
- Context: `cloud-itonami-isic-6530` published a business/operator-model
  blueprint (ADR-2607032000's insurance coverage push) but stopped at
  `:blueprint` maturity -- no governed actor implementation. This ADR
  deepens it to `:implemented`, the seventh insurance-adjacent actor in
  the fleet, continuing the SAME "pick a new ISIC blueprint vertical"
  direction that produced `6512`/`6621`/`6622`/`6629`/`6520`.

## Problem

Pension funding bundles two genuinely distinct real-world acts under
one governed workflow, one of which is a kind this actor family has not
faced before:

1. **Jurisdiction benefit-disbursement/withholding disclosure
   correctness** -- is the required evidence for paying a disbursement
   based on an official regulator, or invented?
2. **Entitlement arithmetic correctness** -- does a requested
   disbursement amount actually exceed the member's own independently-
   computed remaining entitlement (a lump-sum cap, or a period-certain
   annuity-installment division)? Structurally similar to `cloud-
   itonami-isic-6629`'s/`6520`'s "never trust a claimed number,
   independently re-derive it" checks, but expressed as an upper-bound
   CAP (like `casualty.governor/claim-exceeds-coverage-violations`)
   rather than an exact-match check.
3. **A genuinely NEW kind of actuation: recurring continuation, not a
   one-time bind/pay.** Every prior sibling's second actuation event (a
   claim settlement, a recovery payment, a commission booking) is a
   ONE-TIME act, guarded by a double-payment/double-booking check. This
   actor's second actuation event -- authorizing a benefit payout stream
   to CONTINUE past a periodic proof-of-life check -- is explicitly
   RECURRING: the same member is re-screened and re-authorized on every
   cycle, with no "already done" concept to guard.
4. **Real actuation, twice** -- paying a real benefit disbursement and
   authorizing a real payout continuation are both irreversible acts a
   retiree will rely on.

An LLM has no authority or grounding for any of these. The design
problem is therefore not "run pension-fund benefit administration with
an LLM" but "seal the LLM inside a trust boundary and layer evidence-
sufficiency, entitlement-arithmetic correctness, member-lifecycle
correctness, a recurring proof-of-life gate, audit and human-approval
on top of it, while structurally fixing both real actuation events as
human-only."

## Decision

### 1. Pension-LLM is sealed into the bottom node; it never pays or continues directly

`pension.pensionllm` returns exactly six kinds of proposal: intake
normalization, jurisdiction benefit-disbursement/withholding checklist,
disbursement-filing normalization, disbursement-payment draft, proof-
of-life screening, and payout-continuation draft. No proposal writes
the SSoT or commits a real disbursement payment / payout continuation
directly.

### 2. OperationActor = langgraph-clj StateGraph, 1 run = 1 pension-funding operation

`pension.operation/build` is the SAME StateGraph shape as every sibling
actor's operation namespace, copied verbatim.

### 3. The MEMBER folds the role a separate policyholder/party record plays in sibling actors

Unlike every insurance-adjacent sibling, `pension.store` has no
separate `party` concept: a pension fund's core entity, the member,
already bundles what a policyholder/claimant/broker record plays
elsewhere -- there is no distinct insured-property or counterparty to
track apart from the member themselves. The member record therefore
carries both the accrual/entitlement facts AND the demo `:proof-of-
life-hit?` ground-truth flag a screening proposal detects, the same
role `casualty.store`'s `party`'s `:sanctions-hit?` plays -- just
folded into one entity instead of two. This is a genuine domain
difference from `6629`'s "no party at all" (there is still a
screening concept here, proof-of-life, unlike `6629`'s pure
arithmetic-only failure mode) -- it is folded into the SAME entity
being paid, not absent.

### 4. `disbursement-exceeds-entitlement-violations` blends the CAP-check shape with the independent-recompute pattern

`pension.registry/compute-max-disbursement` independently recomputes a
member's maximum entitlement via the member's OWN lump-sum-remaining or
period-certain-annuity formula, and the governor refuses if the
requested amount EXCEEDS it -- the same "never trust a claimed number,
independently re-derive it" discipline `cloud-itonami-isic-6629`'s
`apportionment-mismatch-violations` and `cloud-itonami-isic-6520`'s
`recovery-calculation-mismatch-violations` established, but expressed
as an upper-bound CAP (an inequality, like `casualty.governor/claim-
exceeds-coverage-violations`'s static coverage-amount check) rather
than an exact-match. Unlike `casualty`'s static `:coverage-amount`
field, the cap here is COMPUTED, and the member's own `:disbursed-to-
date` running balance is advanced by `pension.store/pay-disbursement!`
after every lump-sum payment commits -- without this, a SECOND
disbursement against the same member would compute its cap against a
stale, pre-payment balance. This is verified directly in the demo:
`disb-3`'s payment attempt (requesting 10,000,000 against member-1)
correctly holds against the REMAINING entitlement of 7,000,000 (12M
accrued minus the 5M already paid by `disb-1`), not the original 12M.

### 5. Payout continuation is RECURRING, with deliberately NO "already continued" guard

Unlike `disbursement/pay` (one-time per disbursement, double-payment-
protected) and every sibling's second actuation event, `:payout/
continue` has no analogous double-booking check -- `pension.store/
continue-payout!` allows an unlimited number of continuation records
for the SAME member, by design, since each periodic proof-of-life cycle
is its own independent authorization event. `test/pension/
store_contract_test.clj` asserts this explicitly (a second continuation
for the same member succeeds), the mirror image of every sibling's
double-payment/double-booking test asserting the opposite.

### 6. `member-not-in-payout-violations` checks `:status :in-payout` directly, safely -- reusing `6520`'s reasoning, not `6622`'s bug

Like `reinsurance.governor/treaty-not-bound-violations`, a member's
status never regresses out of `:in-payout` once entered (there is no
further status transition analogous to `6622`'s placement advancing
past `:bound` to `:commission-booked`), so checking `:status` directly
here is safe -- the same reasoning `cloud-itonami-isic-6520`'s ADR
already wrote down for its own treaty-status check, reapplied here to
a THIRD lifecycle (policy status in `6512`, treaty status in `6520`,
member status here) without needing to rediscover it by a failing
demo.

### 7. `proof-of-life-failed-violations` reuses the UNCONDITIONAL-evaluation fix, and this build gets it right from the start

Like `6621`'s and `6622`'s conflict-of-interest checks reusing
`casualty.governor/sanctions-violations`'s fix, `proof-of-life-failed-
violations` evaluates a proof-of-life failure UNCONDITIONALLY (not
scoped to `:payout/continue` alone) so the screening op itself
(`:proof-of-life/screen`) can HARD-hold on its own finding -- applied
correctly from the first draft, per the established lesson.

### 8. A REAL bug WAS caught during demo verification -- a NEW one, arising from a combination no sibling had tried

`spec-basis-violations` is scoped to `#{:jurisdiction/assess
:disbursement/pay}`, mirroring `casualty.governor`'s `#{:jurisdiction/
assess :policy/bind}` scoping. But unlike `casualty` (where `:policy/
bind` has no "entity is missing" HARD check sharing its op --
policies are always pre-seeded, never dynamically absent),
`:disbursement/pay` ALSO carries `disbursement-missing-violations` on
the SAME op. The first demo run showed `disbursement/pay disb-999`
(a nonexistent disbursement) holding with basis `[:no-spec-basis
:disbursement-missing]` -- `:no-spec-basis` fired for the WRONG reason
(the proposal naturally has empty `:cites` when the disbursement
can't be found, unrelated to any jurisdiction citation), muddying the
audit trail with a rule that doesn't actually describe what went
wrong. Fixed by guarding `spec-basis-violations`' `:disbursement/pay`
branch on the disbursement actually existing first (deferring to
`disbursement-missing-violations` when it doesn't):

```clojure
(when (contains? #{:jurisdiction/assess :disbursement/pay} op)
  (when (or (not= op :disbursement/pay) (store/disbursement st subject))
    ...))
```

The disposition was ALWAYS correctly `:hold` either way -- this was not
a governance failure (nothing ever committed that shouldn't have), but
an audit-trail-accuracy bug, caught only by reading the actual ledger
output rather than trusting a passing test suite (the same demo-
verification discipline that caught `6512`'s sanctions-scoping bug and
`6622`'s status-lifecycle bug, applied here to a genuinely NEW
combination -- bundling MORE HARD checks onto ONE actuation op than
any predecessor did).

### 9. Real actuation is structurally always human-only (enforced by two independent layers)

`pension.governor`'s `high-stakes` set has two members
(`:actuation/pay-disbursement` and `:actuation/continue-payout`,
matching `6512`'s/`6622`'s/`6520`'s dual-actuation shape, not `6511`'s/
`6621`'s/`6629`'s single-actuation one), and `pension.phase`'s phase
table never puts `:disbursement/pay`/`:payout/continue` in any phase's
`:auto` set. `:proof-of-life/screen` is likewise never auto-eligible at
any phase, matching every sibling's KYC/conflict-screening op posture
even though screening itself moves no capital.

### 10. No fabricated international disbursement/continuation-number standard

Same discipline as every sibling's registry: there is no single
international check-digit standard for a disbursement-payment or
payout-continuation reference number. `pension.registry` therefore does
not invent one; it validates required fields and assigns a
jurisdiction-scoped sequence number only.

### 11. Relationship to `kotoba-lang/insurance`

Same self-contained-sibling relationship every prior insurance-adjacent
actor in this fleet has to the shared capability lib -- no code
dependency.

## Consequences

- (+) Pension funding gets the same governed, auditable-actor treatment
  as the six other insurance-adjacent actors, without centralizing
  liability in one vendor -- any licensed pension fund can fork and run
  their own instance.
- (+) The actuation invariant (governor + phase, two layers) is
  regression-tested by `test/pension/phase_test.clj`'s
  `disbursement-pay-never-auto-at-any-phase` / `payout-continue-never-
  auto-at-any-phase`.
- (+) `MemStore` ‖ `DatomicStore` parity is proven by
  `test/pension/store_contract_test.clj`, the same `:db-api`-driven
  swap pattern every sibling actor uses, including a dedicated
  assertion that the member's running `:disbursed-to-date` balance
  advances after payment, and that continuation has NO double-guard.
- (+) The disbursement-exceeds-entitlement check is a genuine
  contribution blending two established patterns (independent
  recompute + upper-bound cap) -- proven by a dedicated demo scenario
  where the SECOND disbursement against a member correctly accounts
  for the running balance from the first.
- (+) The recovered audit-trail-accuracy bug (Decision 8) is documented
  as a NEW lesson distinct from `6512`'s and `6622`'s: bundling more
  HARD checks onto one actuation op than any predecessor risks a
  spurious co-firing when a check scoped by empty-citations overlaps
  with a check scoped by entity-existence.
- (-) This R0 seeds only 4 jurisdictions (JPN, USA, GBR, DEU) with an
  official spec-basis, out of ~194 worldwide; `pension.facts/coverage`
  reports this honestly rather than claiming broader coverage.
- (-) `compute-max-disbursement` models only a lump-sum remaining-
  balance cap and a period-certain annuity division, not a full plan's
  real-world actuarial terms (mortality tables, discount-rate/interest
  crediting, cost-of-living adjustments are out of scope -- see that
  fn's own docstring); vesting-schedule computation, contribution
  intake/accrual computation, real banking-payment integration, and
  tax/regulatory reporting are all out of scope for this OSS actor --
  each operator's responsibility (see README's coverage table).
- 40 tests / 195 assertions, lint clean.

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| Keep `cloud-itonami-isic-6530` at `:blueprint` only | ❌ | Leaves pension funding without an `:implemented` reference actor, unlike six of its ISIC siblings |
| Model member and policyholder/party as two separate records, for consistency with most siblings | ❌ | A pension fund's core entity IS the member; there is no distinct insured-property or counterparty to justify a second record -- forcing an unused split would be premature abstraction, the same judgment `6629`'s ADR made for its own core entity shape |
| Add a double-continuation guard for `:payout/continue`, defensively matching every other actuation op's double-guard | ❌ | Continuation is explicitly RECURRING by domain design (each proof-of-life cycle is independent); adding a guard against re-continuing the SAME member would break the actual real-world workflow, not protect it |
| Scope `spec-basis-violations` to ONLY `:jurisdiction/assess`, dropping it from `:disbursement/pay` entirely to sidestep the overlap bug | ❌ | Would remove a legitimate belt-and-suspenders check (a disbursement payment proposal genuinely SHOULD be rejected if it somehow lost its jurisdiction citation) -- the correct fix is to scope the entity-existence guard precisely, not to remove the check |
| Model a full actuarial mortality-table-based annuity for conformance-test rigor | ❌ | Genuinely more complex real-world pension-actuarial calculation that this R0 does not claim to model correctly -- honestly scoped to a period-certain division instead, same as every sibling's "starting catalog, not exhaustive" posture |
| Require `kotoba.insurance` (the capability lib) directly from `pension.*` | ❌ | No sibling actor requires its capability lib directly; keeping the actor self-contained matches the established pattern |
