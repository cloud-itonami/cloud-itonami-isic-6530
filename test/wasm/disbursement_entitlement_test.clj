(ns wasm.disbursement-entitlement-test
  "Hosts wasm/disbursement_entitlement.wasm (compiled from wasm/
  disbursement_entitlement.kotoba, see wasm/README.md) via kototama.tender
  -- proves pension.governor's disbursement-exceeds-entitlement check
  (`disbursement-exceeds-entitlement-violations` in
  src/pension/governor.cljc, which independently recomputes the cap via
  `pension.registry/compute-max-disbursement`) runs as a real WASM guest,
  not just as JVM Clojure.

  ABI: main is 0-arity (kotoba wasm emit rejects a parameterized main --
  :main-arity); the five real i32 inputs are written into the guest's
  exported linear memory at fixed offsets before calling main() -- see
  wasm/disbursement_entitlement.kotoba's ABI doc comment for the offset
  layout."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [kototama.contract :as contract]
            [kototama.tender :as tender]))

(defn- wasm-bytes []
  (.readAllBytes (io/input-stream (io/file "wasm/disbursement_entitlement.wasm"))))

(def ^:private lump-sum 0)
(def ^:private annuity-installment 1)

(defn- run-within-entitlement?
  [disbursement-type accrued-benefit disbursed-to-date remaining-payment-periods requested-amount]
  (let [instance (tender/instantiate (wasm-bytes) [] (contract/host-caps {}))
        memory (.memory instance)]
    (.writeI32 memory 0 disbursement-type)
    (.writeI32 memory 4 accrued-benefit)
    (.writeI32 memory 8 disbursed-to-date)
    (.writeI32 memory 12 remaining-payment-periods)
    (.writeI32 memory 16 requested-amount)
    (tender/call-main instance)))

(deftest lump-sum-wasm-approves-within-cap
  (testing "lump-sum: accrued=100000, disbursed=20000 -> cap=80000; requested=50000 well within -> approves"
    (is (= 1 (run-within-entitlement? lump-sum 100000 20000 0 50000)))))

(deftest lump-sum-wasm-rejects-exceeding-cap
  (testing "lump-sum: same cap=80000; requested=90000 exceeds it -> rejects"
    (is (= 0 (run-within-entitlement? lump-sum 100000 20000 0 90000)))))

(deftest lump-sum-wasm-approves-exact-boundary
  (testing "lump-sum: requested exactly equal to the cap (80000) -> approves (<=)"
    (is (= 1 (run-within-entitlement? lump-sum 100000 20000 0 80000)))))

(deftest lump-sum-wasm-fully-disbursed-denies-any-further-payout
  (testing "lump-sum: disbursed-to-date already >= accrued-benefit -> cap clamps to 0; any positive request exceeds"
    (is (= 0 (run-within-entitlement? lump-sum 100000 100000 0 1)))
    (is (= 1 (run-within-entitlement? lump-sum 100000 100000 0 0)))))

(deftest annuity-installment-wasm-approves-within-cap
  (testing "annuity-installment: accrued=120000, remaining=12 periods -> cap=10000; requested=10000 exact boundary -> approves"
    (is (= 1 (run-within-entitlement? annuity-installment 120000 0 12 10000)))))

(deftest annuity-installment-wasm-rejects-exceeding-cap
  (testing "annuity-installment: same cap=10000; requested=10001 exceeds it -> rejects"
    (is (= 0 (run-within-entitlement? annuity-installment 120000 0 12 10001)))))

(deftest annuity-installment-wasm-invalid-periods-denies
  (testing "annuity-installment: remaining-payment-periods=0 is an invalid divisor -- guest guards it (mirrors affordability.kotoba's zero-guard) and treats the cap as 0 rather than trapping on integer division by zero"
    (is (= 0 (run-within-entitlement? annuity-installment 120000 0 0 1)))
    (is (= 1 (run-within-entitlement? annuity-installment 120000 0 0 0)))))
