(ns pension.phase
  "Phase 0->3 staged rollout -- the pension-funding analog of
  `cloud-itonami-isic-6512`'s `casualty.phase`.

    Phase 0  read-only        -- no writes, still governor-gated.
    Phase 1  assisted-intake  -- member intake allowed, every write needs
                                 human approval.
    Phase 2  assisted-assess  -- adds jurisdiction assessment + proof-of-
                                 life screening writes, still approval.
    Phase 3  supervised auto  -- governor-clean, high-confidence
                                 `:member/intake`/`:disbursement/file`
                                 (no capital risk yet) may auto-commit.
                                 `:disbursement/pay`/`:payout/continue`
                                 NEVER auto-commit, at any phase.

  `:disbursement/pay`/`:payout/continue` are deliberately ABSENT from
  every phase's `:auto` set, including phase 3 -- a permanent structural
  fact, not a rollout milestone still to come. Paying a real benefit
  disbursement and continuing a real payout stream are the two
  real-world legal/financial acts this actor performs; both are always
  a human pension administrator's call. `pension.governor`'s
  `:actuation/pay-disbursement`/`:actuation/continue-payout` high-stakes
  gate enforces the same invariant independently -- two layers, not
  one, agree on this. `:member/intake`/`:disbursement/file` move no
  capital yet (still HARD-gated in `pension.governor`, but never
  `high-stakes`), so both ARE auto-eligible at phase 3, the same
  multi-auto-op posture `cloud-itonami-isic-6512`'s `casualty.phase`
  already establishes. `:proof-of-life/screen` is never auto-eligible
  either, at any phase -- the same posture every sibling's KYC/conflict
  screening op has (`casualty.phase`'s `:kyc/screen`, `adjustment.
  phase`'s `:conflict/screen`), even though screening itself moves no
  capital.

  The decision core is delegated to the safety kernel
  `pension.kernels.gate` (integer-coded, fail-closed, safe-kotoba
  subset); this namespace keeps the human-readable phase table (the
  documentation and structural-invariant tests read it) and does the
  keyword<->wire-code mapping at the boundary. The kernel's own battery
  and the parity matrix in `pension.kernels.gate-test` pin the two
  representations together."
  (:require [pension.kernels.gate :as kernel]))

(def read-ops  #{})
(def write-ops #{:member/intake :jurisdiction/assess :proof-of-life/screen
                 :disbursement/file :disbursement/pay :payout/continue})

;; NOTE the invariant: `:disbursement/pay`/`:payout/continue` are members
;; of `write-ops` (governor-gated like any write) but are NEVER members
;; of any phase's `:auto` set below. Do not add them there.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops allowed to
  auto-commit when governor-clean>}."
  {0 {:label "read-only"       :writes #{}                                                          :auto #{}}
   1 {:label "assisted-intake" :writes #{:member/intake}                                             :auto #{}}
   2 {:label "assisted-assess" :writes #{:member/intake :jurisdiction/assess :proof-of-life/screen}   :auto #{}}
   3 {:label "supervised-auto" :writes write-ops
      :auto #{:member/intake :disbursement/file}}})

(def default-phase 3)

;; ---- kernel wire-code bridges (façade-side, not kernel vocabulary) ----

(defn- op->code
  "Kernel op wire code. This actor has no read ops today, so nothing
  maps to the kernel's reserved read code 0. Unknown ops map to 7
  (unknown write) — the kernel never write-enables it, so an
  unrecognized op fails closed to HOLD exactly as the old
  set-membership logic did."
  [op]
  (cond
    (contains? read-ops op)        0
    (= op :member/intake)          1
    (= op :jurisdiction/assess)    2
    (= op :proof-of-life/screen)   3
    (= op :disbursement/file)      4
    (= op :disbursement/pay)       5
    (= op :payout/continue)        6
    :else                          7))

(defn- disposition->code [d]
  (cond (= d :commit) 0 (= d :escalate) 1 (= d :hold) 2 :else 2))

(defn- code->disposition [c]
  (if (= c 0) :commit (if (= c 1) :escalate :hold)))

(defn- code->reason [c]
  (if (= c 1) :phase-disabled (if (= c 2) :phase-approval nil)))

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE (:phase-approval),
    even if the governor was clean.
  - `:disbursement/pay`/`:payout/continue` are never auto-eligible at
    any phase, so they always escalate once the governor clears them
    (or hold if the governor doesn't)."
  [phase {:keys [op]} governor-disposition]
  (let [p (if (contains? phases phase) phase default-phase)
        op-code (op->code op)
        gov-code (disposition->code governor-disposition)
        d (kernel/phase-disposition p op-code gov-code)
        r (kernel/phase-reason p op-code gov-code)]
    {:disposition (code->disposition d)
     :reason (code->reason r)}))

(defn verdict->disposition
  "Map a Pension Governor verdict to a base disposition before the
  phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
