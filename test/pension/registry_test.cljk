(ns pension.registry-test
  (:require [clojure.test :refer [deftest is]]
            [pension.registry :as r]))

;; ----------------------------- compute-max-disbursement -----------------------------

(deftest lump-sum-max-is-accrued-benefit-minus-disbursed-to-date
  (is (= 12000000.0 (r/compute-max-disbursement {:accrued-benefit 12000000 :disbursed-to-date 0} :lump-sum)))
  (is (= 7000000.0 (r/compute-max-disbursement {:accrued-benefit 12000000 :disbursed-to-date 5000000} :lump-sum)))
  (is (= 0.0 (r/compute-max-disbursement {:accrued-benefit 5000000 :disbursed-to-date 5000000} :lump-sum))
      "fully disbursed -> zero remaining entitlement"))

(deftest annuity-installment-max-is-a-period-certain-division
  (is (= 600000.0 (r/compute-max-disbursement {:accrued-benefit 12000000 :remaining-payment-periods 20} :annuity-installment)))
  (is (= 12000000.0 (r/compute-max-disbursement {:accrued-benefit 12000000 :remaining-payment-periods 1} :annuity-installment))))

(deftest compute-max-disbursement-validation-rules
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/compute-max-disbursement {:accrued-benefit -1} :lump-sum)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/compute-max-disbursement {:accrued-benefit 100 :disbursed-to-date -1} :lump-sum)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/compute-max-disbursement {:accrued-benefit 100 :remaining-payment-periods 0} :annuity-installment)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/compute-max-disbursement {:accrued-benefit 100} :unknown-type))))

;; ----------------------------- register-disbursement-payment -----------------------------

(deftest disbursement-payment-is-a-draft-not-a-real-payment
  (let [result (r/register-disbursement-payment "member-1" "disb-1" :lump-sum 5000000 "JPN" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest disbursement-payment-assigns-disbursement-number
  (let [result (r/register-disbursement-payment "member-1" "disb-1" :lump-sum 5000000 "JPN" 7)]
    (is (= (get result "disbursement_number") "JPN-DISB-000007"))
    (is (= (get-in result ["record" "member_id"]) "member-1"))
    (is (= (get-in result ["record" "disbursement_id"]) "disb-1"))
    (is (= (get-in result ["record" "disbursed_amount"]) 5000000))
    (is (= (get-in result ["record" "kind"]) "disbursement-payment-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest disbursement-payment-validation-rules
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-disbursement-payment "" "disb-1" :lump-sum 5000000 "JPN" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-disbursement-payment "member-1" "" :lump-sum 5000000 "JPN" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-disbursement-payment "member-1" "disb-1" :unknown-type 5000000 "JPN" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-disbursement-payment "member-1" "disb-1" :lump-sum -1 "JPN" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-disbursement-payment "member-1" "disb-1" :lump-sum 5000000 "" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-disbursement-payment "member-1" "disb-1" :lump-sum 5000000 "JPN" -1))))

(deftest payment-history-is-append-only
  (let [p1 (r/register-disbursement-payment "member-1" "disb-1" :lump-sum 5000000 "JPN" 0)
        hist (r/append [] p1)
        p2 (r/register-disbursement-payment "member-1" "disb-2" :lump-sum 1000000 "JPN" 1)
        hist2 (r/append hist p2)]
    (is (= 2 (count hist2)))
    (is (= "JPN-DISB-000000" (get-in hist2 [0 "record_id"])))
    (is (= "JPN-DISB-000001" (get-in hist2 [1 "record_id"])))))

;; ----------------------------- register-payout-continuation -----------------------------

(deftest payout-continuation-is-a-draft-not-a-real-continuation
  (let [result (r/register-payout-continuation "member-4" "GBR" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest payout-continuation-assigns-continuation-number
  (let [result (r/register-payout-continuation "member-4" "GBR" 7)]
    (is (= (get result "continuation_number") "GBR-CONT-000007"))
    (is (= (get-in result ["record" "member_id"]) "member-4"))
    (is (= (get-in result ["record" "kind"]) "payout-continuation-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest payout-continuation-validation-rules
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-payout-continuation "" "GBR" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-payout-continuation "member-4" "" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-payout-continuation "member-4" "GBR" -1))))

(deftest continuation-history-is-append-only
  (let [c1 (r/register-payout-continuation "member-4" "GBR" 0)
        hist (r/append [] c1)
        c2 (r/register-payout-continuation "member-4" "GBR" 1)
        hist2 (r/append hist c2)]
    (is (= 2 (count hist2)))
    (is (= "GBR-CONT-000000" (get-in hist2 [0 "record_id"])))
    (is (= "GBR-CONT-000001" (get-in hist2 [1 "record_id"])))))
