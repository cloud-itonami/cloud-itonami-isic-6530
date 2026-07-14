# wasm/ — kotoba-wasm deployment of the disbursement-exceeds-entitlement check

`disbursement_entitlement.kotoba` is a port of `pension.governor/
disbursement-exceeds-entitlement-violations`'s independent recompute --
does a requested benefit-disbursement amount exceed the member's OWN
remaining entitlement, per `pension.registry/compute-max-disbursement`'s
real, simplified lump-sum-cap / period-certain-annuity-installment
formula pair? (see `src/pension/governor.cljc` lines ~186-199 and
`src/pension/registry.cljc`'s `compute-max-disbursement`, lines ~53-89) --
into the minimal `.kotoba` language subset, compiled to a real WASM
module via `kotoba wasm emit`, and hosted via `kototama.tender`
(`test/wasm/disbursement_entitlement_test.clj`).

This follows the same `kotoba wasm emit` → `kototama.tender` pipeline
`cloud-itonami-isic-6492`'s `wasm/affordability.kotoba`,
`cloud-itonami-isic-6511`'s `wasm/underwriting_decision.kotoba`,
`cloud-itonami-isic-6512`'s `wasm/claim_coverage.kotoba` and
`cloud-itonami-isic-6630`'s `wasm/fee_accrual.kotoba` established
(ADR-2607062330 addendum 5) -- a sixth sibling actor's hot-path decision
function ported to real WASM, and the closest analog to `fee_accrual.
kotoba`: a formula recompute over integer inputs (here, a TWO-branch
formula dispatched by disbursement type), compared against a claimed
figure, no host imports.

## Why the source differs from `pension.registry/compute-max-disbursement`

The `.kotoba` compiler's actual WASM code-generator supports only a small,
empirically-verified subset: the special forms `do`/`let`/`if` plus
`+ - * quot / rem mod = < > <= >= zero? not inc dec` (confirmed by reading
`compile-wasm-expr` in `kotoba-lang/kotoba/src/kotoba/runtime.clj` -- no
`pos?`/`neg?`/`and`/`or`/`when`/`case`, unlike the broader tree-walking
interpreter, same finding `cloud-itonami-isic-6492`/`-6511`/`-6512`/
`-6630` document; confirmed working, not merely assumed, by this port
actually calling THREE nested user-defined helper `defn`s from `main` --
`wasm-binary` builds the whole module's function-index table across all
top-level `defn`s before compiling any single function body, so forward/
mutual calls between `.kotoba` functions are a real, working feature of
the subset, not just a single-helper convention the earlier five ports
happened to use). The port therefore:

- Uses plain positional args instead of `{:keys [...]}` map destructuring
  and dispatches on a plain `0`/nonzero integer flag (`disbursement-type`)
  instead of the `:lump-sum`/`:annuity-installment` keyword `case` the
  original `compute-max-disbursement` uses -- no maps, no keywords, no
  `case` in the wasm-compilable subset, so the two-branch dispatch is
  rewritten as a single `if` in `max-entitlement`.
- Drops the two `throw`/`ex-info` precondition guards (`accrued-benefit`/
  `disbursed-to-date` must be `>= 0`) entirely -- a WASM export can't
  throw a JVM exception; precondition validation stays the real
  `pension.registry/compute-max-disbursement`'s job, the same "the guest
  only ever sees facts a governor already validated" posture
  `underwriting_decision.kotoba`/`fee_accrual.kotoba` document.
- KEEPS one guard, though, as an explicit `if` rather than a dropped
  precondition: `max-annuity-installment` only divides when
  `remaining-payment-periods > 0`, else returns `0`. Unlike the two
  dropped `>= 0` guards above (which the original code enforces via
  `throw` and whose absence just lets a malformed input flow through to a
  comparison that will not match), a non-positive divisor here would hit
  WASM's `i32.div_s`, which TRAPS (aborts the whole guest call) rather
  than producing a wrong-but-harmless integer -- the same "guard the
  divisor, not merely validate it" shape `affordability.kotoba`'s
  `(if (> annual-income 0) ... 0)` zero-guard already established for
  THIS fleet's first formula-with-a-division port. `0` is the safe
  fallback polarity: a `0` cap means any positive `requested-amount`
  reads as exceeding entitlement (denies), never as silently approving
  one.
- `max-lump-sum` ports the original's `(max 0.0 (- accrued disbursed))`
  clamp as `(if (> accrued-benefit disbursed-to-date) (- ...) 0)` --
  equivalent over integers (when `disbursed-to-date` has caught up to or
  passed `accrued-benefit`, the remaining cap is exactly `0`).
- `max-annuity-installment` recomputes `accrued-benefit quot remaining-
  payment-periods` -- integer FLOOR division -- where the original does a
  true (double) division, `(/ accrued (double remaining))`. This is an
  intentional, honest simplification, not an oversight: `accrued-benefit`
  is already the smallest currency unit (cents), so a real payout system
  cannot disburse a fractional cent either, and floor division can only
  ever produce a cap that is EQUAL TO or SMALLER than the true continuous
  value -- the same "the CAP direction is the safe direction" discipline
  the whole `disbursement-exceeds-entitlement` check exists to enforce
  (never let a requested amount slip through above the member's real
  entitlement). A `requested-amount` that the floor-division cap approves
  was always within the true continuous cap too; the only inputs where
  the two formulas could disagree are ones the floor-division port
  reads as MORE conservative (an extra fractional-cent HARD hold), never
  less.
- Compares `requested-amount <= max-entitlement(...)` directly with a
  `<=` (the "within entitlement, i.e. NOT a violation" polarity) instead
  of `governor.cljc`'s `(> (double (:requested-amount d)) max-entitlement)`
  -- the same inverted-for-boolean-export polarity convention
  `claim_coverage.kotoba`'s `claim-within-coverage?` and `fee_accrual.
  kotoba`'s `fee-accrual-matches?` use: `main()` returns `1` for "OK,
  proceed" and `0` for "HARD violation", the more natural boolean-export
  shape than a violation-list.

## ABI — parameterized invocation

`kotoba wasm emit` rejects any `main` with parameters (`:main-arity` --
the compiler only ever exports a 0-arity `main`, see `compile-wasm-expr`
in `kotoba-lang/kotoba/src/kotoba/runtime.clj`), so real inputs are passed
through the guest's exported linear memory instead -- the same convention
every prior port in this fleet uses. A host writes five little-endian i32
values before calling `main()`:

| offset | field                        | unit / meaning                                                    |
|--------|------------------------------|---------------------------------------------------------------------|
| 0      | `disbursement-type`          | `0` = `:lump-sum`, nonzero = `:annuity-installment`                |
| 4      | `accrued-benefit`             | smallest currency unit (cents)                                     |
| 8      | `disbursed-to-date`           | cents -- only read on the `:lump-sum` branch                       |
| 12     | `remaining-payment-periods`   | count -- only read on the `:annuity-installment` branch            |
| 16     | `requested-amount`            | cents -- the disbursement draft's own requested payout             |

`main()` returns `1` (`requested-amount` is within the independently-
recomputed entitlement cap) or `0` (it EXCEEDS the cap --
`pension.governor`'s `:disbursement-exceeds-entitlement` HARD violation).
All five offsets are well below `heap-base` (2048), so they never
collide with anything the compiler itself places in memory.

## Rebuilding

```sh
cd ../../kotoba-lang/kotoba   # sibling checkout, west-managed
bin/kotoba-clj wasm emit ../../cloud-itonami/cloud-itonami-isic-6530/wasm/disbursement_entitlement.kotoba \
  --package-lock kotoba.lock.edn \
  --output ../../cloud-itonami/cloud-itonami-isic-6530/wasm/disbursement_entitlement.wasm --json
```

Fleet deployment: not attempted in this pass — see cloud-itonami-isic-6492/6511 for the established pattern.
