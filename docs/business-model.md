# Business Model: Pension funding

## Classification

- Repository: `cloud-itonami-isic-6530`
- ISIC Rev.5: `6530`
- Activity: pension and retirement-benefit funds -- contribution collection, benefit-accrual tracking, and annuity/benefit disbursement
- Social impact: financial inclusion, data sovereignty, transparent audit

## Customer

- occupational pension funds
- cooperative and mutual retirement funds
- municipal pension administrators
- employers self-administering a defined-contribution or defined-benefit plan

## Offer

- member enrollment and contribution intake
- benefit-accrual and vesting tracking
- annuity/benefit disbursement proposal
- proof-of-life-gated payout continuation
- immutable audit ledger

## Revenue

- self-host setup: one-time implementation fee
- managed hosting: monthly subscription per member
- support: monthly retainer with SLA
- migration: import from an incumbent pension-administration system
- disbursement-processing fee

## Trust Controls

- no benefit is disbursed and no payout continuation is authorized
  without human sign-off
- a fabricated jurisdiction benefit-disbursement/withholding citation,
  unsupported evidence, a disbursement filed for an unvested member, a
  requested amount that exceeds this vehicle's own independent
  entitlement recompute, or a continuation attempt for a member who was
  never in-payout -- each forces a hold, not an override
- a failed proof-of-life check halts continuing payout until reviewed,
  never auto-approved
- a disbursement cannot be paid twice: a double-payment attempt is held
  off this actor's own payment history alone, with no upstream
  comparison needed
- every intake, assessment, filing, payment, screening and
  continuation path is auditable
- emergency manual override paths remain outside LLM control
