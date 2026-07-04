# Governance

`cloud-itonami-isic-6530` is an OSS open-business blueprint for pension and retirement-benefit funds -- contribution collection, benefit-accrual tracking, and annuity/benefit disbursement.
Governance covers both the capability layer and the operator model.

## Maintainers

Maintainers may merge changes that preserve these invariants:

- the Pension Governor remains independent of the advisor.
- hard policy violations (fabricated spec-basis, sanctions hit, incomplete
  records) cannot be overridden by human approval.
- disbursing an annuity/benefit payment, or approving continuing payout after a proof-of-life check always escalates to a human -- never automated.
- every hold, approval and disbursement path is auditable.
- personal and customer data stay outside Git.

## Decision Records

Architecture decisions live in `docs/adr/`. Changes to the trust model,
storage contract, public business model, operator certification or license
should add or update an ADR.

## Operator Governance

Anyone may fork and operate independently. itonami.cloud certification is a
separate trust mark and should require security, audit and data-flow review.

Certified operators can lose certification for:

- bypassing the Pension Governor's policy checks
- mishandling customer data
- misrepresenting certification status
- failing to respond to security incidents
- hiding material changes to customer-facing operation
