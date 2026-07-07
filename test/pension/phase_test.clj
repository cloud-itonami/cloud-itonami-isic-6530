(ns pension.phase-test
  "The phase table as executable tests. The invariant this repo cannot
  regress on: `:disbursement/pay`/`:payout/continue` must NEVER be a
  member of any phase's `:auto` set."
  (:require [clojure.test :refer [deftest is testing]]
            [pension.phase :as phase]))

(deftest disbursement-pay-never-auto-at-any-phase
  (testing "structural invariant: no phase, now or in the future entries, auto-commits a real disbursement payment"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :disbursement/pay))
          (str "phase " n " must not auto-commit :disbursement/pay")))))

(deftest payout-continue-never-auto-at-any-phase
  (testing "structural invariant: no phase auto-continues a real payout stream"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :payout/continue))
          (str "phase " n " must not auto-commit :payout/continue")))))

(deftest proof-of-life-screen-never-auto-at-any-phase
  (testing "screening moves no capital, but is still never auto-eligible, matching every sibling KYC/conflict screen"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :proof-of-life/screen))
          (str "phase " n " must not auto-commit :proof-of-life/screen")))))

(deftest phase-0-is-fully-read-only
  (is (empty? (:writes (get phase/phases 0)))))

(deftest phase-3-auto-commits-only-no-capital-risk-ops
  (testing ":member/intake and :disbursement/file move no capital -- auto-eligible"
    (is (= #{:member/intake :disbursement/file} (:auto (get phase/phases 3))))))

(deftest gate-hold-always-wins
  (is (= :hold (:disposition (phase/gate 3 {:op :member/intake} :hold)))))

(deftest gate-escalates-a-clean-non-auto-write
  (is (= :escalate (:disposition (phase/gate 3 {:op :disbursement/pay} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :payout/continue} :commit)))))

(deftest gate-holds-a-write-disabled-in-this-phase
  (is (= :hold (:disposition (phase/gate 0 {:op :member/intake} :commit)))))
