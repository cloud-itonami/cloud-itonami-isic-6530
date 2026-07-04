# Contributing

`cloud-itonami-isic-6530` accepts contributions to the OSS blueprint, capability
bindings, policy tests, documentation and operator model.

## Development

The capability layer lives in [`kotoba-lang/insurance`](https://github.com/kotoba-lang/insurance). This repo holds the business blueprint and operator
contracts.

```bash
# in kotoba-lang/insurance:
clojure -M:test
clojure -M:lint
```

Keep changes small and include tests for any capability-layer change.

## Rules

- Do not commit real customer records, credentials, or personal/health data.
- Keep disbursing an annuity/benefit payment, or approving continuing payout after a proof-of-life check behind the Pension Governor.
- Treat this vertical as high-risk: add tests for spec-basis, disbursement,
  disclosure and audit logging.
- Document any new business-model or operator assumption in `docs/`.

## Pull Requests

PRs should describe:

- what behavior changed
- which policy invariant is affected
- how it was tested
- whether operator or certification docs need updates
