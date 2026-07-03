# cloud-itonami-6530

Open Business Blueprint for **ISIC Rev.5 6530**: Pension funding.

This repository designs a forkable OSS business for pension and retirement-benefit funds -- contribution collection, benefit-accrual tracking, and annuity/benefit disbursement -- run by a qualified, licensed operator so a community or
independent professional never surrenders customer data and ledgers to a
closed SaaS.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot performs
the physical domain work**. Here an in-person verification kiosk robot performs the physical proof-of-life check required before continuing a retiree's payout,
under an actor that proposes actions and an independent **Pension Governor**
that gates them. The governor never dispatches hardware itself;
`:high`/`:safety-critical` actions require human sign-off.

## Core Contract

```text
intake + identity + case/policy records
        |
        v
Pension-LLM -> Pension Governor -> hold, proceed, or human approval
        |
        v
case/policy ledger + evidence record + audit
```

No automated proposal, by itself, can complete the following without governor
approval and audit evidence: disbursing an annuity/benefit payment, or approving continuing payout after a proof-of-life check.

## Capability layer

This blueprint resolves its technology stack via
[`kotoba-lang/industry`](https://github.com/kotoba-lang/industry) (ISIC
`6530`). Required capabilities are implemented by:

- [`kotoba-lang/insurance`](https://github.com/kotoba-lang/insurance)
  -- policy, premium, claim and underwriting-decision contracts

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## Maturity

`:blueprint` -- this repository is the published business/operator design.
The governed actor implementation (`Pension-LLM` + `Pension Governor` as
running code) is a follow-up, same as any other `:blueprint`-tier
`cloud-itonami-*` entry in `kotoba-lang/industry`'s registry.

## License

Code and implementation templates are AGPL-3.0-or-later.
