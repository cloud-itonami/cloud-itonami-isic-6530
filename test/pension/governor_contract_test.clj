(ns pension.governor-contract-test
  "The governor contract as executable tests -- the pension-funding
  analog of `cloud-itonami-isic-6512`'s `casualty.governor-contract-
  test`. The single invariant under test:

    Pension-LLM never pays a disbursement or continues a payout stream
    the Pension Governor would reject, `:disbursement/pay`/`:payout/
    continue` NEVER auto-commit at any phase, `:member/intake`/
    `:disbursement/file` (no capital risk) MAY auto-commit when clean,
    and every decision (commit OR hold) leaves exactly one ledger
    fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [pension.store :as store]
            [pension.operation :as op]))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(def operator {:actor-id "op-1" :actor-role :pension-administrator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn- assess-member1!
  "Walks member-1 through assess -> approve, leaving an assessment on
  file. Uses distinct thread-ids per call site by suffixing
  `tid-prefix`."
  [actor tid-prefix]
  (exec-op actor (str tid-prefix "-assess") {:op :jurisdiction/assess :subject "member-1"} operator)
  (approve! actor (str tid-prefix "-assess")))

(defn- file-disbursement!
  [actor tid disbursement-id member-id requested-amount]
  (exec-op actor tid {:op :disbursement/file :subject disbursement-id :member-id member-id
                      :disbursement-type :lump-sum :requested-amount requested-amount} operator))

(deftest clean-intake-auto-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :member/intake :subject "member-1"
                   :patch {:id "member-1" :status :ready}} operator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= :ready (:status (store/member db "member-1"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))))

(deftest jurisdiction-assess-always-needs-approval
  (testing "assess is never in any phase's :auto set -- always human approval, even when clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t2" {:op :jurisdiction/assess :subject "member-1"} operator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t2")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (some? (store/assessment-of db "member-1")))))))

(deftest fabricated-jurisdiction-is-held
  (testing "a jurisdiction/assess proposal with no official spec-basis -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t3"
                    {:op :jurisdiction/assess :subject "member-1" :no-spec? true} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:no-spec-basis} (-> (store/ledger db) first :basis)))
      (is (nil? (store/assessment-of db "member-1")) "no assessment written"))))

(deftest disbursement-file-against-unvested-member-is-held
  (testing "a disbursement filed for a never-vested member -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (file-disbursement! actor "t4" "disb-1" "member-3" 500000)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:member-not-vested} (-> (store/ledger db) first :basis)))
      (is (nil? (store/disbursement db "disb-1")) "no disbursement written"))))

(deftest disbursement-file-against-vested-member-auto-commits
  (testing ":disbursement/file moves no capital yet -- auto-eligible at phase 3, once the member is vested"
    (let [[db actor] (fresh)
          res (file-disbursement! actor "t5" "disb-1" "member-1" 5000000)]
      (is (= :commit (get-in res [:state :disposition])))
      (is (= :filed (:status (store/disbursement db "disb-1"))) "SSoT actually updated"))))

(deftest disbursement-pay-without-assessment-is-held
  (testing "disbursement/pay before any jurisdiction assessment -> HOLD (evidence incomplete)"
    (let [[db actor] (fresh)
          _ (file-disbursement! actor "t6pre" "disb-1" "member-1" 5000000)
          res (exec-op actor "t6" {:op :disbursement/pay :subject "disb-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:evidence-incomplete} (-> (store/ledger db) last :basis))))))

(deftest disbursement-pay-with-missing-disbursement-is-held
  (testing "paying a disbursement id that was never filed -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t7" {:op :disbursement/pay :subject "disb-999"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:disbursement-missing} (-> (store/ledger db) first :basis)))
      (is (empty? (store/payment-history db))))))

(deftest disbursement-pay-exceeding-entitlement-is-held
  (testing "a disbursement whose requested amount exceeds the member's own independently-recomputed entitlement -> HOLD"
    (let [[db actor] (fresh)
          _ (assess-member1! actor "t8pre")
          _ (file-disbursement! actor "t8file" "disb-1" "member-1" 20000000)
          res (exec-op actor "t8" {:op :disbursement/pay :subject "disb-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:disbursement-exceeds-entitlement} (-> (store/ledger db) last :basis)))
      (is (empty? (store/payment-history db))))))

(deftest disbursement-pay-always-escalates-then-human-decides
  (testing "a clean, fully-assessed, within-entitlement disbursement still ALWAYS interrupts for human approval -- actuation/pay-disbursement is never auto"
    (let [[db actor] (fresh)
          _ (assess-member1! actor "t9pre")
          _ (file-disbursement! actor "t9file" "disb-1" "member-1" 5000000)
          r1 (exec-op actor "t9" {:op :disbursement/pay :subject "disb-1"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, disbursement-payment record drafted, member's running balance advances"
        (let [r2 (approve! actor "t9")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (= :paid (:status (store/disbursement db "disb-1"))))
          (is (= 5000000.0 (:disbursed-to-date (store/member db "member-1"))))
          (is (= 1 (count (store/payment-history db))) "one draft payment record")))))
  (testing "reject -> hold, nothing paid"
    (let [[db actor] (fresh)
          _ (assess-member1! actor "t10pre")
          _ (file-disbursement! actor "t10file" "disb-1" "member-1" 5000000)
          _ (exec-op actor "t10" {:op :disbursement/pay :subject "disb-1"} operator)
          r2 (g/run* actor {:approval {:status :rejected :by "op-1"}}
                     {:thread-id "t10" :resume? true})]
      (is (= :hold (get-in r2 [:state :disposition])))
      (is (empty? (store/payment-history db)) "nothing paid on reject"))))

(deftest disbursement-pay-double-payment-is-held
  (testing "paying the same disbursement twice -> HOLD on the second attempt, even though the figures match cleanly"
    (let [[db actor] (fresh)
          _ (assess-member1! actor "t11pre")
          _ (file-disbursement! actor "t11file" "disb-1" "member-1" 5000000)
          _ (exec-op actor "t11a" {:op :disbursement/pay :subject "disb-1"} operator)
          _ (approve! actor "t11a")
          res (exec-op actor "t11" {:op :disbursement/pay :subject "disb-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:double-payment} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/payment-history db))) "still only the one earlier payment"))))

(deftest proof-of-life-failure-is-held-and-unoverridable
  (testing "a proof-of-life failure on a member -> HOLD, and never reaches request-approval"
    (let [[db actor] (fresh)
          res (exec-op actor "t12" {:op :proof-of-life/screen :subject "member-5"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:proof-of-life-failed} (-> (store/ledger db) first :basis)))
      (is (nil? (store/proof-of-life-of db "member-5")) "no clearance written"))))

(deftest payout-continue-for-member-never-in-payout-is-held
  (testing "continuing a payout for a member who was never in-payout -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t13" {:op :payout/continue :subject "member-6"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:member-not-in-payout} (-> (store/ledger db) first :basis))))))

(deftest payout-continue-always-escalates-then-human-decides
  (testing "a clean, proof-of-life-cleared continuation still ALWAYS interrupts for human approval -- actuation/continue-payout is never auto"
    (let [[db actor] (fresh)
          _ (exec-op actor "t14pre" {:op :proof-of-life/screen :subject "member-4"} operator)
          _ (approve! actor "t14pre")
          r1 (exec-op actor "t14" {:op :payout/continue :subject "member-4"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, payout-continuation record drafted"
        (let [r2 (approve! actor "t14")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (= 1 (count (store/continuation-history db))) "one draft continuation record")))))
  (testing "reject -> hold, nothing continued"
    (let [[db actor] (fresh)
          _ (exec-op actor "t15pre" {:op :proof-of-life/screen :subject "member-4"} operator)
          _ (approve! actor "t15pre")
          _ (exec-op actor "t15" {:op :payout/continue :subject "member-4"} operator)
          r2 (g/run* actor {:approval {:status :rejected :by "op-1"}}
                     {:thread-id "t15" :resume? true})]
      (is (= :hold (get-in r2 [:state :disposition])))
      (is (empty? (store/continuation-history db)) "nothing continued on reject"))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N operations -> N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :member/intake :subject "member-1"
                          :patch {:id "member-1" :status :ready}} operator)
      (exec-op actor "b" {:op :jurisdiction/assess :subject "member-1" :no-spec? true} operator)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))
