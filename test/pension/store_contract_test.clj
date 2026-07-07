(ns pension.store-contract-test
  "The Store contract, run against BOTH backends. Proving MemStore and
  the Datomic-backed (langchain.db) store satisfy the same contract is
  what makes 'swap the SSoT for Datomic / kotoba-server' a configuration
  change, not a rewrite -- see `cloud-itonami-isic-6511`'s
  `underwriting.store-contract-test` for the same pattern on the sibling
  actor."
  (:require [clojure.test :refer [deftest is testing]]
            [pension.store :as store]))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "田中 一郎" (:name (store/member s "member-1"))))
      (is (= "JPN" (:jurisdiction (store/member s "member-1"))))
      (is (= :defined-benefit (:plan-type (store/member s "member-1"))))
      (is (true? (:vested? (store/member s "member-1"))))
      (is (false? (:vested? (store/member s "member-3"))))
      (is (= :in-payout (:status (store/member s "member-4"))))
      (is (true? (:proof-of-life-hit? (store/member s "member-5"))))
      (is (= ["member-1" "member-2" "member-3" "member-4" "member-5" "member-6"]
             (mapv :id (store/all-members s))))
      (is (nil? (store/disbursement s "disb-1")))
      (is (nil? (store/proof-of-life-of s "member-1")))
      (is (nil? (store/assessment-of s "member-1")))
      (is (= [] (store/ledger s)))
      (is (= [] (store/payment-history s)))
      (is (= [] (store/continuation-history s)))
      (is (zero? (store/next-sequence s "JPN")))
      (is (zero? (store/continuation-sequence s "JPN")))
      (is (false? (store/disbursement-already-paid? s "disb-1"))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "partial upsert merges, preserving untouched fields"
        (store/commit-record! s {:effect :member/upsert
                                 :value {:id "member-1" :status :ready}})
        (is (= :ready (:status (store/member s "member-1"))))
        (is (= "田中 一郎" (:name (store/member s "member-1"))) "name preserved"))
      (testing "assessment payloads commit and read back"
        (store/commit-record! s {:effect :assessment/set :path ["member-1"]
                                 :payload {:jurisdiction "JPN" :checklist ["a" "b"]}})
        (is (= {:jurisdiction "JPN" :checklist ["a" "b"]} (store/assessment-of s "member-1"))))
      (testing "disbursement filing writes a plain disbursement record (no draft/certificate -- filing moves no capital)"
        (store/commit-record! s {:effect :disbursement/filed
                                 :payload {:id "disb-1" :member-id "member-1" :disbursement-type :lump-sum
                                          :requested-amount 5000000 :status :filed}})
        (is (= :filed (:status (store/disbursement s "disb-1"))))
        (is (= 5000000 (:requested-amount (store/disbursement s "disb-1")))))
      (testing "disbursement payment drafts a payment record, advances the sequence, and advances the member's disbursed-to-date"
        (store/commit-record! s {:effect :disbursement/mark-paid :path ["disb-1"]})
        ;; payment-history holds the inner "record" sub-map (registry/append's
        ;; convention), whose record-id key is "record_id".
        (is (= "JPN-DISB-000000" (get (first (store/payment-history s)) "record_id")))
        (is (= "disbursement-payment-draft" (get (first (store/payment-history s)) "kind")))
        (is (= 5000000 (get (first (store/payment-history s)) "disbursed_amount")))
        (is (= :paid (:status (store/disbursement s "disb-1"))))
        (is (= 5000000.0 (:disbursed-to-date (store/member s "member-1")))
            "running balance advanced by the paid amount")
        (is (= 1 (count (store/payment-history s))))
        (is (= 1 (store/next-sequence s "JPN")))
        (is (true? (store/disbursement-already-paid? s "disb-1")))
        (is (false? (store/disbursement-already-paid? s "disb-2"))))
      (testing "proof-of-life payloads commit and read back"
        (store/commit-record! s {:effect :proof-of-life/set :path ["member-4"]
                                 :payload {:member-id "member-4" :verdict :clear}})
        (is (= {:member-id "member-4" :verdict :clear} (store/proof-of-life-of s "member-4"))))
      (testing "payout continuation drafts a continuation record and advances the continuation sequence, with NO already-continued guard"
        (store/commit-record! s {:effect :payout/mark-continued :path ["member-4"]})
        (is (= "GBR-CONT-000000" (get (first (store/continuation-history s)) "record_id")))
        (is (= "payout-continuation-draft" (get (first (store/continuation-history s)) "kind")))
        (is (= 1 (count (store/continuation-history s))))
        (is (= 1 (store/continuation-sequence s "GBR")))
        (store/commit-record! s {:effect :payout/mark-continued :path ["member-4"]})
        (is (= 2 (count (store/continuation-history s)))
            "a second continuation for the SAME member is allowed -- continuation is recurring, not one-time"))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/member s "nope")))
    (is (= [] (store/all-members s)))
    (is (= [] (store/ledger s)))
    (is (= [] (store/payment-history s)))
    (is (= [] (store/continuation-history s)))
    (is (zero? (store/next-sequence s "JPN")))
    (is (zero? (store/continuation-sequence s "JPN")))
    (store/with-members s {"x" {:id "x" :name "n" :jurisdiction "JPN"
                                :plan-type :defined-contribution :accrued-benefit 1000000
                                :disbursed-to-date 0 :vested? true :status :accruing}})
    (is (= "n" (:name (store/member s "x"))))))
