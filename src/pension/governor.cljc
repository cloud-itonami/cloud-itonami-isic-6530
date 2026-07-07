(ns pension.governor
  "Pension Governor -- the independent compliance layer that earns the
  Pension-LLM the right to commit. The LLM has no notion of
  jurisdictional benefit-disbursement/withholding disclosure law,
  whether a member is actually vested before a disbursement is filed,
  whether a requested disbursement amount actually exceeds the member's
  own independently-computed entitlement, whether a member's payout
  stream is actually in-payout before it is continued, whether a
  proof-of-life check has actually failed, or when an act stops being a
  draft and becomes a real-world benefit disbursement or a real
  continuation of a payout stream, so this MUST be a separate system
  able to *reject* a proposal and fall back to HOLD -- the pension-
  funding analog of `cloud-itonami-isic-6512`'s CasualtyGovernor.

  Seven checks, in priority order. The first six are HARD violations: a
  human approver CANNOT override them (you don't get to approve your
  way past a fabricated jurisdiction spec-basis, incomplete withholding
  evidence, a disbursement filed for an unvested member, a nonexistent
  disbursement, a disbursement that would exceed this vehicle's own
  independent entitlement recompute, or continuing a payout for a
  member who was never in-payout). The seventh, proof-of-life-failed, is
  also HARD and un-overridable -- you do not get to approve continuing
  a payout past a failed proof-of-life check. The confidence/actuation
  gate is SOFT: it asks a human to look (low confidence / actuation),
  and the human may approve -- but see `pension.phase`: for
  `:stake :actuation/pay-disbursement`/`:actuation/continue-payout` (a
  real benefit disbursement or a real payout continuation) NO phase
  ever allows auto-commit either. Two independent layers agree that
  actuation is always a human call.

    1. Spec-basis                  -- did the jurisdiction proposal cite
                                       an OFFICIAL source (`pension.
                                       facts`), or invent one?
    2. Evidence incomplete         -- for `:disbursement/pay`, are the
                                       jurisdiction's required benefit-
                                       claim/withholding docs actually
                                       satisfied?
    3. Member not vested           -- for `:disbursement/file`, is the
                                       referenced member actually
                                       vested? A disbursement cannot be
                                       filed against an entitlement that
                                       has not vested.
    4. Disbursement missing        -- for `:disbursement/pay`, does the
                                       referenced disbursement actually
                                       exist on file?
    5. Disbursement exceeds
       entitlement                  -- for `:disbursement/pay`, does the
                                       requested amount exceed
                                       `pension.registry/compute-max-
                                       disbursement`'s independent
                                       recompute of the member's own
                                       remaining entitlement? Never
                                       trusts a requested figure as-is --
                                       the SAME 'independently re-derive,
                                       never trust a claimed number'
                                       discipline `cloud-itonami-isic-
                                       6629`'s `apportionment-mismatch-
                                       violations` and `cloud-itonami-
                                       isic-6520`'s `recovery-
                                       calculation-mismatch-violations`
                                       apply, expressed here as a CAP
                                       (like `casualty.governor/claim-
                                       exceeds-coverage-violations`)
                                       rather than an exact-match check.
    6. Member not in payout        -- for `:payout/continue`, is the
                                       referenced member actually
                                       `:status :in-payout`? A payout
                                       stream cannot be continued for a
                                       member who was never receiving
                                       one.
    7. Proof-of-life failed        -- for `:payout/continue`, does THIS
                                       proposal itself report a
                                       proof-of-life failure (a
                                       `:proof-of-life/screen` that just
                                       found one), or does the member
                                       already carry a failed
                                       proof-of-life verdict on file?
                                       Evaluated UNCONDITIONALLY (not
                                       scoped to a specific op), the
                                       SAME discipline `casualty.
                                       governor/sanctions-violations`
                                       established and `intermediation.
                                       governor`/`adjustment.governor`'s
                                       conflict-of-interest checks
                                       reused -- scoping this to
                                       `:payout/continue` alone would
                                       silently prevent the screening op
                                       (`:proof-of-life/screen`) from
                                       ever HARD-holding on its own
                                       finding.
    8. Confidence floor / actuation
       gate                          -- LLM confidence below threshold,
                                       OR the op is `:disbursement/pay`/
                                       `:payout/continue` (REAL legal/
                                       financial acts) -> escalate.

  One more guard, double-payment prevention, is enforced but NOT listed
  as a numbered HARD check above because it needs no upstream/member
  comparison at all -- `double-payment-violations` refuses to pay the
  SAME disbursement twice, off this actor's own payment history."
  (:require [pension.facts :as facts]
            [pension.registry :as registry]
            [pension.store :as store]))

(def confidence-floor 0.6)

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Paying a real benefit disbursement and continuing a real payout
  stream past a proof-of-life check are the two real-world actuation
  events this actor performs."
  #{:actuation/pay-disbursement :actuation/continue-payout})

;; ----------------------------- checks -----------------------------

(defn- spec-basis-violations
  "A `:jurisdiction/assess` (or `:disbursement/pay`) proposal with no
  spec-basis citation is a HARD violation -- never invent a
  jurisdiction's benefit-disbursement/withholding requirements. For
  `:disbursement/pay`, only applies when the disbursement actually
  exists: unlike every sibling actor's `:policy/bind`-shaped op (which
  has no 'entity is missing' HARD check sharing its op), `:disbursement/
  pay` ALSO carries `disbursement-missing-violations` on the same op --
  without this guard, a nonexistent disbursement's proposal naturally
  has empty `:cites` (nothing to cite), so `:no-spec-basis` would fire
  alongside `:disbursement-missing` for the WRONG reason, muddying the
  audit trail with a rule that doesn't actually describe what went
  wrong. See `pension.governor`'s own ADR-0001 for this lesson."
  [{:keys [op subject]} proposal st]
  (when (contains? #{:jurisdiction/assess :disbursement/pay} op)
    (when (or (not= op :disbursement/pay) (store/disbursement st subject))
      (let [value (:value proposal)]
        (when (or (empty? (:cites proposal))
                  (and (contains? value :spec-basis) (nil? (:spec-basis value))))
          [{:rule :no-spec-basis
            :detail "公式spec-basisの引用が無い提案は法域要件として扱えない"}])))))

(defn- evidence-incomplete-violations
  "For `:disbursement/pay`, the jurisdiction's required benefit-claim/
  withholding evidence must actually be satisfied for the disbursement's
  own member -- do not trust the advisor's self-reported confidence
  alone."
  [{:keys [op subject]} st]
  (when (= op :disbursement/pay)
    (when-let [d (store/disbursement st subject)]
      (let [m (store/member st (:member-id d))
            assessment (store/assessment-of st (:member-id d))]
        (when-not (and assessment
                       (facts/required-evidence-satisfied?
                        (:jurisdiction m) (:checklist assessment)))
          [{:rule :evidence-incomplete
            :detail "法域の必要書類(年金請求書/源泉徴収申告書等)が充足していない状態での支払提案"}])))))

(defn- member-not-vested-violations
  "For `:disbursement/file`, the referenced member must actually be
  vested (`:vested? true`) -- a disbursement cannot be filed against an
  entitlement that has not vested."
  [{:keys [op member-id]} st]
  (when (= op :disbursement/file)
    (when-not (:vested? (store/member st member-id))
      [{:rule :member-not-vested
        :detail (str member-id " は権利確定(vested)していないため、給付金の請求は受理できない")}])))

(defn- disbursement-missing-violations
  "For `:disbursement/pay`, the referenced disbursement must actually
  exist on file -- refuses to pay out a fabricated/nonexistent
  disbursement id."
  [{:keys [op subject]} st]
  (when (= op :disbursement/pay)
    (when-not (store/disbursement st subject)
      [{:rule :disbursement-missing
        :detail (str subject " という給付金請求は登録されていない")}])))

(defn- disbursement-exceeds-entitlement-violations
  "For `:disbursement/pay`, INDEPENDENTLY recompute the member's max
  entitlement via `pension.registry/compute-max-disbursement` and
  refuse if the requested amount exceeds it -- never trusts a
  requested figure as-is."
  [{:keys [op subject]} st]
  (when (= op :disbursement/pay)
    (when-let [d (store/disbursement st subject)]
      (let [m (store/member st (:member-id d))
            max-entitlement (registry/compute-max-disbursement m (:disbursement-type d))]
        (when (> (double (:requested-amount d)) max-entitlement)
          [{:rule :disbursement-exceeds-entitlement
            :detail (str subject " の請求額(" (:requested-amount d)
                        ")が独自再計算の権利上限(" max-entitlement ")を超過している")}])))))

(defn- member-not-in-payout-violations
  "For `:payout/continue`, the referenced member must actually be
  `:status :in-payout` -- a payout stream cannot be continued for a
  member who was never receiving one. Like `reinsurance.governor/
  treaty-not-bound-violations`, a member's status never regresses out
  of `:in-payout` once entered, so checking `:status` directly here
  carries none of `cloud-itonami-isic-6622`'s placement-status-
  lifecycle risk."
  [{:keys [op subject]} st]
  (when (= op :payout/continue)
    (when-not (= :in-payout (:status (store/member st subject)))
      [{:rule :member-not-in-payout
        :detail (str subject " は年金支払中(in-payout)ではないため、継続支払いは承認できない")}])))

(defn- proof-of-life-failed-violations
  "A proof-of-life failure -- reported by THIS proposal (e.g. a
  `:proof-of-life/screen` that itself just found one), or already on
  file in the store for the member (`:payout/continue`) -- is a HARD,
  un-overridable hold, evaluated UNCONDITIONALLY (not scoped to a
  specific op) so the screening op itself can HARD-hold on its own
  finding -- the same fix `casualty.governor/sanctions-violations`
  first established and `adjustment.governor`/`intermediation.
  governor`'s conflict-of-interest checks reused."
  [{:keys [op subject]} proposal st]
  (let [hit-in-proposal? (= :failed (get-in proposal [:value :verdict]))
        member-id (when (= op :payout/continue) subject)
        hit-on-file? (and member-id (= :failed (:verdict (store/proof-of-life-of st member-id))))]
    (when (or hit-in-proposal? hit-on-file?)
      [{:rule :proof-of-life-failed
        :detail "生存確認(proof-of-life)に失敗した会員を含む提案は進められない"}])))

(defn- double-payment-violations
  "For `:disbursement/pay`, refuses to pay the SAME disbursement twice,
  off this actor's own payment history -- needs no upstream/member
  comparison at all."
  [{:keys [op subject]} st]
  (when (= op :disbursement/pay)
    (when (store/disbursement-already-paid? st subject)
      [{:rule :double-payment
        :detail (str subject " は既に給付金支払い済み")}])))

(defn check
  "Censors a Pension-LLM proposal against the governor rules. Returns
   {:ok? bool :violations [..] :confidence c :escalate? bool :high-stakes? bool
    :hard? bool}."
  [request _context proposal st]
  (let [hard (into []
                   (concat (spec-basis-violations request proposal st)
                           (evidence-incomplete-violations request st)
                           (member-not-vested-violations request st)
                           (disbursement-missing-violations request st)
                           (disbursement-exceeds-entitlement-violations request st)
                           (member-not-in-payout-violations request st)
                           (proof-of-life-failed-violations request proposal st)
                           (double-payment-violations request st)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (high-stakes (:stake proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
