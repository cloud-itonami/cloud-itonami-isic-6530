(ns pension.registry
  "Pure-function disbursement-payment and payout-continuation record
  construction -- an append-only pension book-of-record draft.

  Like every sibling actor's registry, there is no single international
  check-digit standard for a disbursement or a payout-continuation
  reference number -- every fund/jurisdiction assigns its own reference
  format. This namespace does NOT invent one; it builds a jurisdiction-
  scoped sequence number and validates the record's required fields,
  the same honest, non-fabricating discipline `pension.facts` uses.

  `compute-max-disbursement` is a REAL, well-known pair of formulas (a
  lump-sum entitlement cap, and a period-certain annuity-installment
  division), not an invented placeholder default -- see its own
  docstring for the honest simplification it makes vs. a real plan's
  full actuarial terms (no mortality tables, no discount-rate/interest
  crediting, no cost-of-living adjustments). This is the SAME
  'reimplement the well-known math independently, so a downstream
  governor can cross-check a claimed figure against it' pattern
  `cloud-itonami-isic-6629`'s `auxiliary.registry/apportion-general-
  average` and `cloud-itonami-isic-6520`'s `reinsurance.registry/
  compute-recovery` establish -- applied here to a THIRD domain-specific
  formula, as an entitlement CAP rather than an exact-match check (the
  requested disbursement must not EXCEED the independently-computed
  entitlement, the same inequality shape `casualty.governor/claim-
  exceeds-coverage-violations` uses against a static coverage-amount
  field, but here against a COMPUTED entitlement rather than a stored
  constant).

  This namespace is pure data + pure functions -- no I/O, no network
  call to any pension-administration or benefit-payment system. It
  builds the RECORD a pension fund would keep, not the act of paying
  the disbursement or continuing the payout itself (those are
  `pension.operation`'s `:disbursement/pay` and `:payout/continue`,
  always human-gated -- see README `Actuation`)."
  (:require [clojure.string :as str]))

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is the
  licensed pension fund's act, not this actor's. See README `Actuation`."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn compute-max-disbursement
  "Pure computation of the maximum entitlement a member may draw for a
  given disbursement type, dispatched by type -- a REAL, simplified
  pair of formulas (see ns docstring for what a full plan's terms
  additionally model that this does not):

    :lump-sum            -- max = accrued-benefit - disbursed-to-date.
                            The member's remaining, not-yet-paid-out
                            entitlement.
    :annuity-installment  -- max = accrued-benefit / remaining-payment-
                            periods. A simple PERIOD-CERTAIN division,
                            not a full actuarial mortality-table-based
                            annuity calculation (no life-expectancy
                            tables, no discount-rate/interest crediting,
                            no cost-of-living adjustments).

  `member` -- a map with `:accrued-benefit` plus (for `:lump-sum`)
  `:disbursed-to-date` (defaults to 0) or (for `:annuity-installment`)
  `:remaining-payment-periods`."
  [member disbursement-type]
  (let [accrued (double (get member :accrued-benefit 0))]
    (when (neg? accrued)
      (throw (ex-info "compute-max-disbursement: accrued-benefit must be >= 0" {})))
    (case disbursement-type
      :lump-sum
      (let [disbursed (double (get member :disbursed-to-date 0))]
        (when (neg? disbursed)
          (throw (ex-info "compute-max-disbursement: disbursed-to-date must be >= 0" {})))
        (max 0.0 (- accrued disbursed)))

      :annuity-installment
      (let [remaining (get member :remaining-payment-periods 0)]
        (when-not (pos? remaining)
          (throw (ex-info "compute-max-disbursement: remaining-payment-periods must be > 0" {})))
        (/ accrued (double remaining)))

      (throw (ex-info (str "compute-max-disbursement: unknown disbursement-type " disbursement-type) {})))))

(defn register-disbursement-payment
  "Validate + construct the DISBURSEMENT-PAYMENT registration DRAFT --
  the pension fund's own legal act of paying a real benefit
  disbursement to a member. Pure function -- does not touch any real
  benefit-payment/banking system; it builds the RECORD a fund would
  keep. `pension.governor` independently re-verifies the disbursed
  amount does not exceed `compute-max-disbursement`, and blocks a
  double-payment of the same disbursement, before this is ever allowed
  to commit."
  [member-id disbursement-id disbursement-type disbursed-amount jurisdiction sequence]
  (when-not (and member-id (not= member-id ""))
    (throw (ex-info "disbursement-payment: member_id required" {})))
  (when-not (and disbursement-id (not= disbursement-id ""))
    (throw (ex-info "disbursement-payment: disbursement_id required" {})))
  (when-not (contains? #{:lump-sum :annuity-installment} disbursement-type)
    (throw (ex-info "disbursement-payment: disbursement-type must be :lump-sum or :annuity-installment" {})))
  (when (neg? disbursed-amount)
    (throw (ex-info "disbursement-payment: disbursed-amount must be >= 0" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "disbursement-payment: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "disbursement-payment: sequence must be >= 0" {})))
  (let [disbursement-number (str (str/upper-case jurisdiction) "-DISB-" (zero-pad sequence 6))
        record {"record_id" disbursement-number
                "kind" "disbursement-payment-draft"
                "member_id" member-id
                "disbursement_id" disbursement-id
                "disbursement_type" (name disbursement-type)
                "disbursed_amount" disbursed-amount
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "disbursement_number" disbursement-number
     "certificate" (unsigned-certificate "DisbursementPaymentCertificate" disbursement-number disbursement-number)}))

(defn register-payout-continuation
  "Validate + construct the PAYOUT-CONTINUATION registration DRAFT --
  the pension fund's own act of authorizing a recurring benefit
  payout stream to continue past a proof-of-life check. Pure function
  -- does not touch any real benefit-payment system; it builds the
  RECORD a fund would keep."
  [member-id jurisdiction sequence]
  (when-not (and member-id (not= member-id ""))
    (throw (ex-info "payout-continuation: member_id required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "payout-continuation: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "payout-continuation: sequence must be >= 0" {})))
  (let [continuation-number (str (str/upper-case jurisdiction) "-CONT-" (zero-pad sequence 6))
        record {"record_id" continuation-number
                "kind" "payout-continuation-draft"
                "member_id" member-id
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "continuation_number" continuation-number
     "certificate" (unsigned-certificate "PayoutContinuationCertificate" continuation-number continuation-number)}))

(defn append
  "Append a disbursement-payment/payout-continuation record, returning a
  NEW list (never mutate history in place)."
  [history result]
  (conj (vec history) (get result "record")))
