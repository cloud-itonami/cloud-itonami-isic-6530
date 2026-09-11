(ns pension.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean member through
  intake -> jurisdiction benefit-disbursement assessment -> disbursement
  filing (auto-commits; no capital risk) -> disbursement-payment
  proposal (always escalates) -> human approval -> commit, then a clean
  in-payout member through proof-of-life screening -> payout-
  continuation proposal (always escalates) -> human approval -> commit,
  then shows seven HARD holds (a jurisdiction with no spec-basis, a
  disbursement filed for an unvested member, a second disbursement that
  would exceed the member's own remaining entitlement after the first
  payment, a failed proof-of-life check, a payout continuation attempt
  for a member who was never in-payout, a payment of a nonexistent
  disbursement, and a double-payment of an already-paid disbursement)
  that never reach a human at all, and prints the audit ledger + the
  draft disbursement-payment and payout-continuation records."
  (:require [langgraph.graph :as g]
            [pension.store :as store]
            [pension.operation :as op]))

(def operator {:actor-id "op-1" :actor-role :pension-administrator :phase 3})

(defn- exec! [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        actor (op/build db)]
    (println "== member/intake member-1 (JPN, defined-benefit, clean) ==")
    (println (exec! actor "t1" {:op :member/intake :subject "member-1"
                                :patch {:id "member-1" :status :accruing}} operator))

    (println "== jurisdiction/assess member-1 (escalates -- human approves) ==")
    (println (exec! actor "t2" {:op :jurisdiction/assess :subject "member-1"} operator))
    (println (approve! actor "t2"))

    (println "== disbursement/file disb-1 against member-1 (vested; 5,000,000 of 12,000,000 entitlement; auto-commits, no capital risk) ==")
    (println (exec! actor "t3" {:op :disbursement/file :subject "disb-1" :member-id "member-1"
                                :disbursement-type :lump-sum :requested-amount 5000000} operator))

    (println "== disbursement/pay disb-1 (always escalates -- actuation/pay-disbursement) ==")
    (let [r (exec! actor "t4" {:op :disbursement/pay :subject "disb-1"} operator)]
      (println r)
      (println "-- human pension administrator approves --")
      (println (approve! actor "t4")))

    (println "== proof-of-life/screen member-4 (clean; escalates -- human approves) ==")
    (println (exec! actor "t5" {:op :proof-of-life/screen :subject "member-4"} operator))
    (println (approve! actor "t5"))

    (println "== payout/continue member-4 (always escalates -- actuation/continue-payout) ==")
    (let [r (exec! actor "t6" {:op :payout/continue :subject "member-4"} operator)]
      (println r)
      (println "-- human pension administrator approves --")
      (println (approve! actor "t6")))

    (println "== jurisdiction/assess member-2 (no spec-basis -> HARD hold) ==")
    (println (exec! actor "t7" {:op :jurisdiction/assess :subject "member-2" :no-spec? true} operator))

    (println "== disbursement/file disb-2 against member-3 (never vested -> HARD hold, never reaches a human) ==")
    (println (exec! actor "t8" {:op :disbursement/file :subject "disb-2" :member-id "member-3"
                                :disbursement-type :lump-sum :requested-amount 500000} operator))

    (println "== disbursement/file disb-3 against member-1 (10,000,000 requested; filing itself auto-commits, no capital risk) ==")
    (println (exec! actor "t9a" {:op :disbursement/file :subject "disb-3" :member-id "member-1"
                                 :disbursement-type :lump-sum :requested-amount 10000000} operator))

    (println "== disbursement/pay disb-3 (10,000,000 exceeds member-1's remaining entitlement of 7,000,000 after disb-1 -> HARD hold) ==")
    (println (exec! actor "t9" {:op :disbursement/pay :subject "disb-3"} operator))

    (println "== proof-of-life/screen member-5 (proof-of-life failure -> HARD hold, never reaches a human) ==")
    (println (exec! actor "t10" {:op :proof-of-life/screen :subject "member-5"} operator))

    (println "== payout/continue member-6 (never in-payout -> HARD hold, never reaches a human) ==")
    (println (exec! actor "t11" {:op :payout/continue :subject "member-6"} operator))

    (println "== disbursement/pay disb-999 (nonexistent disbursement -> HARD hold) ==")
    (println (exec! actor "t12" {:op :disbursement/pay :subject "disb-999"} operator))

    (println "== disbursement/pay disb-1 AGAIN (double-payment of an already-paid disbursement -> HARD hold) ==")
    (println (exec! actor "t13" {:op :disbursement/pay :subject "disb-1"} operator))

    (println "== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "== draft disbursement-payment records ==")
    (doseq [r (store/payment-history db)] (println r))

    (println "== draft payout-continuation records ==")
    (doseq [r (store/continuation-history db)] (println r))))
