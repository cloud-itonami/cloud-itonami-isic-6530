# Operator Quick Start

Get the Pension Governor actor running locally in minutes.

## Prerequisites

- **Clojure CLI** (`clojure` command): [install](https://clojure.org/guides/install_clojure)
- **Java 11+** (comes with Clojure)
- This repository: `git clone https://github.com/cloud-itonami/cloud-itonami-isic-6530.git`

If you are part of the monorepo (`com-junkawasaki/`), the `:dev` alias brings local copies of `langgraph-clj` and `langchain-clj`. A standalone fork should override `:deps` in `deps.edn` with published git coordinates.

## Run the Demo

Drive the Pension Governor through two clean lifecycles (disbursement payment, payout continuation) and seven hard-hold cases:

```bash
clojure -M:dev:run
```

This runs `pension.sim`, the demo driver, which exercises the core actor contract:
- **Pension-LLM** drafts proposals (intake, assessment, filing, payment, screening, continuation)
- **Pension Governor** independently verifies and holds or commits each proposal
- Audit ledger records every decision

## Run Tests

Verify the governor contract, phase invariants, store parity, registry conformance, and jurisdiction coverage:

```bash
clojure -M:dev:test
```

Or, to run tests under ClojureScript (the primary gate — our `.cljc` suite runs on a real JavaScript host first):

```bash
clojure -Sdeps '{:paths ["src" "test"]}' -M:dev:cljs \
  -m cljs.main --target node -m pension.portable-cljs-test-runner
```

## Static Analysis

Check for lint errors (this is the CI gate):

```bash
clojure -M:lint
```

## The Governor

The **Pension Governor** sits in `src/pension/governor.cljc`. It enforces seven hard checks before committing any proposal:

1. **spec-basis**: Jurisdiction benefit-disbursement/withholding requirements must cite an official source
2. **evidence-incomplete**: All required evidence must be present
3. **member-not-vested**: Disbursement filing requires a vested member
4. **disbursement-missing**: Must know what amount to disburse
5. **exceeds-entitlement**: Disbursement amount verified against independent recompute
6. **member-not-in-payout**: Payout continuation only for members already in-payout
7. **proof-of-life-failed**: Failed screening halts payout continuation

Additionally:
- **double-payment guard**: Checked against this actor's own payment history alone
- **actuation gate**: Disbursement payment and payout continuation always route to human, never auto-approved

See `test/pension/governor_test.clj` for contract verification.

## The Store

Member records, benefit calculations, disbursement history, and audit ledger are managed by `src/pension/store.cljc`:

- **In-memory**: `MemStore` for local testing
- **Persistent**: `DatomicStore` (via `langchain.db`) for production deployments

No separate party/policyholder record — the member entity is self-contained.

## Demo Data & Facts

- **Member intake** flows through `src/pension/pensionllm.cljc` (mock or real LLM advisor)
- **Jurisdiction facts** (benefit requirements, spec citations) live in `src/pension/facts.cljc`
- Current coverage: JPN, USA, GBR, DEU (4 of ~194 jurisdictions worldwide)

See `pension.facts/coverage` for an honest reporting of which requested jurisdictions have official spec-basis citations.

## Next Steps

1. **Understand the architecture**: Read `docs/adr/0001-architecture.md`
2. **Understand the business model**: Read `docs/business-model.md`
3. **Plan your deployment**: Read `docs/operator-guide.md`
4. **Fork and customize**:
   - Adapt `pension.facts/catalog` to your jurisdiction(s)
   - Configure `pension.governor`'s hold/escalation policy via the store
   - Wire real member intake, disbursement payment, and proof-of-life integration
   - Keep the governor independent and the audit ledger immutable

## Support

- **Source**: [github.com/cloud-itonami/cloud-itonami-isic-6530](https://github.com/cloud-itonami/cloud-itonami-isic-6530)
- **License**: AGPL-3.0-or-later
- **Governance**: See `GOVERNANCE.md` and `CONTRIBUTING.md` in the repository root
