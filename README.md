# cloud-itonami-isic-6530

Open Business Blueprint for **ISIC Rev.5 6530**: Pension funding. This
repository publishes a pension and retirement-benefit fund actor --
member enrollment, benefit-accrual tracking, benefit-disbursement
payment and proof-of-life-gated payout continuation -- as an OSS
business that any qualified, licensed pension fund can fork, deploy,
run, improve and sell.

Built on this workspace's
[`langgraph-clj`](https://github.com/com-junkawasaki/langgraph-clj)
StateGraph runtime (portable `.cljc`, supervised superstep loop,
interrupts, Datomic/in-mem checkpoints) -- the same actor pattern as
[`cloud-itonami-isic-6511`](https://github.com/cloud-itonami/cloud-itonami-isic-6511)
(life insurance), [`cloud-itonami-isic-6512`](https://github.com/cloud-itonami/cloud-itonami-isic-6512)
(non-life insurance), [`cloud-itonami-isic-6621`](https://github.com/cloud-itonami/cloud-itonami-isic-6621)
(independent loss adjustment), [`cloud-itonami-isic-6622`](https://github.com/cloud-itonami/cloud-itonami-isic-6622)
(insurance intermediation), [`cloud-itonami-isic-6629`](https://github.com/cloud-itonami/cloud-itonami-isic-6629)
(insurance auxiliary services) and [`cloud-itonami-isic-6520`](https://github.com/cloud-itonami/cloud-itonami-isic-6520)
(reinsurance). Here it is **Pension-LLM ⊣ Pension Governor**.

> **Why an actor layer at all?** An LLM is great at drafting a benefit-
> claim summary, normalizing member intake, and running the entitlement
> arithmetic for a disbursement -- but it has **no notion of which
> jurisdiction's benefit-disbursement/withholding requirements are
> official, no license to pay a real benefit disbursement or authorize
> a real payout continuation, and no way to know on its own whether a
> requested disbursement actually exceeds the member's remaining
> entitlement**. Letting it pay a disbursement or continue a payout
> directly invites fabricated jurisdiction citations, silently-wrong
> entitlement math that a retiree would actually rely on, and liability
> for whoever runs it. This project seals the Pension-LLM into a single
> node and wraps it with an independent **Pension Governor**, a human
> **approval workflow**, and an immutable **audit ledger**.

## Scope: what this actor does and does not do

This actor covers member intake through disbursement payment, and
proof-of-life screening through payout continuation, for both defined-
benefit and defined-contribution plan types. It does **not**, by
itself, hold a license to administer a pension plan in any
jurisdiction, and it does not claim to. It also does **not** model a
full plan's real-world actuarial terms -- no mortality tables, no
discount-rate/interest crediting, no cost-of-living adjustments (see
`pension.registry/compute-max-disbursement`'s own docstring for the
honest simplification this makes: a period-certain division for
annuity installments, not a full actuarial calculation). Whoever
deploys and operates a live instance (a licensed pension fund) supplies
the jurisdiction-specific license, the real actuarial expertise and the
real benefit-payment integrations, and bears that jurisdiction's
liability -- the software supplies the governed, spec-cited, audited
execution scaffold so that operator does not have to build the
compliance layer from scratch for every new market.

### Actuation

**Paying a real benefit disbursement and authorizing a real payout
continuation past a proof-of-life check are never autonomous, at any
phase, by construction.** Two independent layers enforce this
(`pension.governor`'s `:actuation/pay-disbursement`/`:actuation/
continue-payout` high-stakes gate and `pension.phase`'s phase table,
which never puts `:disbursement/pay`/`:payout/continue` in any phase's
`:auto` set) -- see `pension.phase`'s docstring and `test/pension/
phase_test.clj`'s `disbursement-pay-never-auto-at-any-phase`/
`payout-continue-never-auto-at-any-phase`. The actor may draft, check
and recommend; a human pension administrator is always the one who
actually pays a disbursement or authorizes a payout continuation.

## The core contract

```
member intake + jurisdiction facts (pension.facts, spec-cited)
        |
        v
   ┌──────────────┐   proposal      ┌───────────────────────┐
   │ Pension-LLM  │ ─────────────▶ │ Pension Governor          │  (independent system)
   │  (sealed)    │  + citations    │ spec-basis · evidence-     │
   └──────────────┘                 │ incomplete · member-not-   │
                             commit ◀────┼──────────▶ hold │ vested · disbursement-
                                 │             │           │ missing · exceeds-
                           record + ledger  escalate ─▶ human   entitlement (independent
                                             (ALWAYS for         recompute) · not-in-payout ·
                                              :disbursement/pay / proof-of-life-failed
                                              :payout/continue)   (un-overridable) ·
                                                                  double-payment
```

**The Pension-LLM never pays a disbursement or continues a payout
stream the Pension Governor would reject, and never does so without a
human sign-off.** Hard violations (fabricated jurisdiction
requirements; unsupported benefit-claim/withholding evidence; a
disbursement filed for an unvested member; a disbursement amount that
exceeds this vehicle's own independent entitlement recompute; a
continuation attempt for a member who was never in-payout; a failed
proof-of-life check; a double payment) force **hold** and *cannot* be
approved past; a clean payment/continuation proposal still always
routes to a human.

## Run

```bash
clojure -M:dev:run     # walk two clean lifecycles (disbursement payment, payout continuation) + seven HARD-hold cases through the actor
clojure -M:dev:test    # governor contract · phase invariants · store parity · registry conformance · facts coverage
clojure -M:lint        # clj-kondo (errors fail; CI mirrors this)
```

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here an in-person verification
kiosk robot performs the physical proof-of-life check required before
continuing a retiree's payout, under the actor, gated by the
independent **Pension Governor**. The governor never dispatches
hardware itself; `:high`/`:safety-critical` actions require human
sign-off.

## Open business

This repository is not only source code. It is a public, forkable
business model:

| Layer | What is open |
|---|---|
| OSS core | Actor runtime, Pension Governor, disbursement-payment + payout-continuation draft records, audit ledger |
| Business blueprint | Customer, offer, pricing, unit economics, sales motion |
| Operator playbook | How to fork, license, deploy and support the service in a jurisdiction |
| Trust controls | Governance, security reporting, actuation invariant, audit requirements |

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md) to start this as an
open business on itonami.cloud, and
[`docs/adr/0001-architecture.md`](docs/adr/0001-architecture.md) for the
full architecture and decision record.

## Capability layer

This blueprint resolves its technology stack via
[`kotoba-lang/industry`](https://github.com/kotoba-lang/industry) (ISIC
`6530`). Related capability contracts (policy/premium/claim shapes) are
published as [`kotoba-lang/insurance`](https://github.com/kotoba-lang/insurance);
this actor's `pension.*` namespaces are a self-contained governed
implementation -- it does not require the capability lib directly, the
same "self-contained sibling" relationship its insurance-adjacent
siblings have toward the same lib.

## Layout

| File | Role |
|---|---|
| `src/pension/store.cljc` | **Store** protocol -- `MemStore` ‖ `DatomicStore` (`langchain.db`) + append-only audit ledger + disbursement-payment/payout-continuation history. No separate party concept -- the member entity bundles the role a separate policyholder/party record plays in sibling actors |
| `src/pension/registry.cljc` | Disbursement-payment + payout-continuation draft records, plus `compute-max-disbursement` (REAL, simplified lump-sum-cap and period-certain-annuity formulas -- see docstring for what it does not model) |
| `src/pension/facts.cljc` | Per-jurisdiction benefit-disbursement/withholding catalog with an official spec-basis citation per entry, honest coverage reporting |
| `src/pension/pensionllm.cljc` | **Pension-LLM Advisor** -- `mock-advisor` ‖ `llm-advisor`; intake/assessment/filing/payment/screening/continuation proposals |
| `src/pension/governor.cljc` | **Pension Governor** -- 7 HARD checks (spec-basis · evidence-incomplete · member-not-vested · disbursement-missing · disbursement-exceeds-entitlement, independent recompute · member-not-in-payout · proof-of-life-failed, unconditional evaluation) + double-payment guard + 1 soft (confidence/actuation gate) |
| `src/pension/phase.cljc` | **Phase 0→3** -- read-only → assisted intake → assisted assess → supervised (payment/continuation always human; member intake + disbursement filing auto-eligible, no capital risk) |
| `src/pension/operation.cljc` | **OperationActor** -- langgraph-clj StateGraph |
| `src/pension/sim.cljc` | demo driver |
| `test/pension/*_test.clj` | governor contract · phase invariants · store parity · registry conformance · facts coverage |
| `wasm/disbursement_entitlement.kotoba` | PoC: a WASM-compiled (`kotoba-lang/kotoba` -> `kotoba-lang/kototama`'s `actor:host` ABI) port of `pension.registry/compute-max-disbursement` + `pension.governor`'s `disbursement-exceeds-entitlement-violations` comparison -- see `wasm/README.md` for scope, the input/output ABI, and what's out of scope |

## Business-process coverage (honest)

This actor covers member intake through disbursement payment, and
proof-of-life screening through payout continuation -- the core
governed lifecycle this blueprint's own `docs/business-model.md` names
as its Offer:

| Covered | Not covered (out of scope for this R0) |
|---|---|
| Member intake + per-jurisdiction benefit-claim/withholding checklisting, HARD-gated on an official spec-basis citation (`:member/intake`/`:jurisdiction/assess`) | Full actuarial mortality-table-based annuity calculation, discount-rate/interest crediting, cost-of-living adjustments (see `compute-max-disbursement`'s docstring) |
| Disbursement filing against a vested member (`:disbursement/file`, HARD-gated on the member actually being vested) | Real transfer-agent/banking-payment integration, tax/regulatory reporting |
| Disbursement payment, independently re-verified against this vehicle's OWN entitlement-cap recompute, with a double-payment guard (`:disbursement/pay`) | Vesting-schedule computation itself (this R0 takes `:vested?` as a given fact, not a computed one) |
| Proof-of-life screening and payout-continuation authorization for members already in-payout (`:proof-of-life/screen`/`:payout/continue`, HARD-gated on the member actually being in-payout) | Contribution collection / benefit-accrual computation (this R0 takes `:accrued-benefit` as a given fact) |
| Immutable audit ledger for every intake/assessment/filing/payment/screening/continuation decision | |

Extending coverage is additive: add the next gate (e.g. contribution
intake or vesting-schedule computation) as its own governed op with its
own HARD checks and tests, following the SAME "an independent governor
re-verifies against the actor's own records before any real-world act"
pattern this repo's flagship op already establishes.

## Jurisdiction coverage (honest)

`pension.facts/coverage` reports how many requested jurisdictions
actually have an official spec-basis in `pension.facts/catalog` --
currently 4 seeded (JPN, USA, GBR, DEU) out of ~194 jurisdictions
worldwide. This is a starting catalog to prove the governor contract
end-to-end, not a claim of global coverage. Adding a jurisdiction is
additive: one map entry in `pension.facts/catalog`, citing a real
official source -- never fabricate a jurisdiction's requirements to make
coverage look bigger.

## Maturity

`:implemented` -- `Pension-LLM` + `Pension Governor` run as real,
tested code (see `Run` above), promoted from the originally-published
`:blueprint`-tier scaffold, modeled closely on the sibling
`cloud-itonami-isic-6511`/`6512`/`6621`/`6622`/`6629`/`6520`'s
architecture. See `docs/adr/0001-architecture.md` for the history and
design.

## License

Code and implementation templates are AGPL-3.0-or-later.
