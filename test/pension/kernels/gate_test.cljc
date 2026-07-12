(ns pension.kernels.gate-test
  "The safety kernel's executable spec, three ways:

  1. battery lock — the kernel's own in-subset battery must pass
     case-for-case (`battery-case-count` == `(battery-pass-count)`),
     so a silently dropped case can't survive review.
  2. parity matrix — the kernel's phase core is compared against an
     independent reference copy of the ORIGINAL set-based cond logic
     over the FULL input space (all phases incl. out-of-range, all op
     codes incl. unknown, all governor dispositions). The façade
     delegates, so this is the guard that delegation didn't change
     semantics.
  3. governor boundary — the confidence floor boundary and the
     fail-closed treatment of out-of-range confidence, exercised
     through the real `pension.governor/check` façade."
  (:require [clojure.test :refer [deftest is testing]]
            [pension.governor :as governor]
            [pension.kernels.gate :as gate]))

(deftest battery-lock
  (is (= gate/battery-case-count (gate/battery-pass-count))
      "every battery case must pass; update battery-case-count only when adding cases"))

(deftest confidence-floor-pinned-to-facade-constant
  (is (= gate/confidence-floor-x100
         (Math/round (* 100.0 governor/confidence-floor)))
      "the façade's documented 0.6 and the kernel's deciding 60 must not drift"))

;; ---------------------------------------------------------------
;; Independent oracle for the parity matrix: the pre-kernel phase
;; logic (sets + cond) restated over wire codes, PLUS the kernel's
;; fail-closed contract for out-of-range phases (no writes at all).
;; The original façade normalized an unknown phase to default-phase 3
;; BEFORE this logic and still does — so out-of-range rows here pin
;; the kernel's own contract, not a façade behavior change. Op code 0
;; is the kernel's reserved read lane; this actor's read-ops set is
;; empty so the façade never emits 0, but the kernel contract (reads
;; pass through) is pinned here all the same.

(def ^:private ref-read-ops #{0})
(def ^:private ref-phases
  {0 {:writes #{}            :auto #{}}
   1 {:writes #{1}           :auto #{}}
   2 {:writes #{1 2 3}       :auto #{}}
   3 {:writes #{1 2 3 4 5 6} :auto #{1 4}}})

(defn- ref-gate [phase op gov]
  (let [{:keys [writes auto]} (get ref-phases phase {:writes #{} :auto #{}})]
    (cond
      (= gov 2)                        {:d 2 :r 0}
      (contains? ref-read-ops op)      {:d gov :r 0}
      (not (contains? writes op))      {:d 2 :r 1}
      (and (= gov 0)
           (not (contains? auto op)))  {:d 1 :r 2}
      :else                            {:d gov :r 0})))

(deftest phase-parity-matrix
  (testing "kernel == reference over the full input space (216 combos)"
    (doseq [phase [-1 0 1 2 3 4 7 100 -99]
            op    [0 1 2 3 4 5 6 7]
            gov   [0 1 2]]
      (let [expected (ref-gate phase op gov)]
        (is (= (:d expected) (gate/phase-disposition phase op gov))
            (str "disposition mismatch at phase=" phase " op=" op " gov=" gov))
        (is (= (:r expected) (gate/phase-reason phase op gov))
            (str "reason mismatch at phase=" phase " op=" op " gov=" gov))))))

(deftest pay-continue-and-screen-auto-enabled-nowhere
  (testing "op 5 (:disbursement/pay), op 6 (:payout/continue) and op 3
            (:proof-of-life/screen) are auto-enabled at NO phase — kernel
            restates the phase table's permanent structural invariants"
    (doseq [phase [-1 0 1 2 3 4 7]
            op    [3 5 6]]
      (is (= 0 (gate/op-auto-enabled phase op))))))

;; ---------------------------------------------------------------
;; Governor boundary through the real façade. op :member/intake
;; touches neither the store nor the spec/evidence/disbursement/payout
;; checks (and the unconditional proof-of-life check only reads the
;; proposal for non-:payout/continue ops), so the verdict is decided
;; purely by confidence/actuation — nil store is safe.

(defn- verdict [proposal]
  (governor/check {:op :member/intake :subject "member-x"} {} proposal nil))

(deftest confidence-floor-boundary
  (testing "0.59 escalates, 0.60 clears (kernel decides at integer x100)"
    (is (true?  (:escalate? (verdict {:confidence 0.59}))))
    (is (false? (:ok? (verdict {:confidence 0.59}))))
    (is (true?  (:ok? (verdict {:confidence 0.6}))))
    (is (false? (:escalate? (verdict {:confidence 0.6}))))))

(deftest out-of-range-confidence-fails-closed
  (testing "an advisor reporting impossible confidence gets MORE scrutiny,
            not auto-commit (kernel is deliberately stricter than the old
            inline `(< conf floor)` here)"
    (is (true? (:escalate? (verdict {:confidence 1.5}))))
    (is (false? (:ok? (verdict {:confidence 1.5}))))
    (is (true? (:escalate? (verdict {:confidence -0.2}))))))

(deftest actuation-still-escalates-and-hard-still-wins
  (is (true? (:escalate? (verdict {:confidence 0.99 :stake :actuation/pay-disbursement}))))
  (is (true? (:escalate? (verdict {:confidence 0.99 :stake :actuation/continue-payout}))))
  (testing "a hard violation dominates actuation escalation"
    (let [v (governor/check {:op :jurisdiction/assess :subject "member-x"} {}
                            {:confidence 0.99 :stake :actuation/pay-disbursement
                             :cites []} nil)]
      (is (true? (:hard? v)))
      (is (false? (:escalate? v)))
      (is (some #{:no-spec-basis} (mapv :rule (:violations v)))))))
