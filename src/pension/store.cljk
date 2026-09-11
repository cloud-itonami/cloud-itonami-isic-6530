(ns pension.store
  "SSoT for the pension-funding actor, behind a `Store` protocol so the
  backend is a swap, not a rewrite -- the same seam `cloud-itonami-isic-
  6511`'s `underwriting.store` / `cloud-itonami-isic-6512`'s
  `casualty.store` / `cloud-itonami-isic-6621`'s `adjustment.store` /
  `cloud-itonami-isic-6622`'s `intermediation.store` / `cloud-itonami-
  isic-6629`'s `auxiliary.store` / `cloud-itonami-isic-6520`'s
  `reinsurance.store` use:

    - `MemStore`     -- atom of EDN. The deterministic default for
                        dev/tests/demo (no deps).
    - `DatomicStore` -- backed by `langchain.db`, a Datomic-API-compatible
                        EAV store (datalog q / pull / upsert). Pure `.cljc`,
                        so it runs offline AND can be pointed at a real
                        Datomic Local or a kotoba-server pod by swapping
                        `langchain.db`'s `:db-api` (see langchain.kotoba-db).

  Both implement the same protocol and pass the same contract
  (test/pension/store_contract_test.clj), which is the whole point: the
  actor, the Pension Governor and the audit ledger never know which
  SSoT they run on.

  Unlike every insurance-adjacent sibling, this Store has NO separate
  `party` concept -- a pension fund's core entity, the MEMBER, already
  bundles the role a separate policyholder/claimant/broker record plays
  in `casualty.store`/`intermediation.store`: there is no distinct
  insured-property or counterparty to track apart from the member
  themselves. The member record therefore carries both the accrual/
  entitlement facts AND the demo `:proof-of-life-hit?` ground-truth flag
  a screening proposal detects, the same role `casualty.store`'s
  `party`'s `:sanctions-hit?` plays -- just folded into one entity
  instead of two.

  The ledger stays append-only on every backend: 'which disbursement
  was paid to which member on what jurisdictional basis, which payout
  stream was authorized to continue past a proof-of-life check,
  approved by whom' is always a query over an immutable log -- the
  audit trail a member trusting a fund with their retirement benefit
  needs, and the evidence an operator needs if a disbursement or a
  continuation is later disputed."
  (:require [pension.registry :as registry]
            [langchain.db :as d]
            [langchain-store.core :as ls]))

(defprotocol Store
  (member [s id])
  (all-members [s])
  (disbursement [s id])
  (proof-of-life-of [s member-id] "committed proof-of-life screening verdict for a member, or nil")
  (assessment-of [s member-id] "committed jurisdiction disbursement/withholding-requirement assessment, or nil")
  (ledger [s])
  (payment-history [s] "the append-only disbursement-payment history (pension.registry drafts)")
  (continuation-history [s] "the append-only payout-continuation history (pension.registry drafts)")
  (next-sequence [s jurisdiction] "next disbursement-number sequence for a jurisdiction")
  (continuation-sequence [s jurisdiction] "next continuation-number sequence for a jurisdiction")
  (disbursement-already-paid? [s disbursement-id] "has this disbursement already been paid?")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision fact")
  (with-members [s members] "replace/seed the member directory (map id->member)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained member set covering both plan types and both
  lifecycle stages (accruing / in-payout) so the actor + tests run
  offline."
  []
  {:members
   {"member-1" {:id "member-1" :name "田中 一郎" :employer "Sakura Manufacturing"
                :plan-type :defined-benefit :accrued-benefit 12000000 :disbursed-to-date 0
                :remaining-payment-periods 20 :vested? true :status :accruing
                :proof-of-life-hit? false :jurisdiction "JPN"}
    "member-2" {:id "member-2" :name "J. Smith" :employer "Atlantis Corp"
                :plan-type :defined-contribution :accrued-benefit 3000000 :disbursed-to-date 0
                :remaining-payment-periods 15 :vested? true :status :accruing
                :proof-of-life-hit? false :jurisdiction "ATL"}
    "member-3" {:id "member-3" :name "鈴木 花子" :employer "Sakura Manufacturing"
                :plan-type :defined-contribution :accrued-benefit 2000000 :disbursed-to-date 0
                :remaining-payment-periods 15 :vested? false :status :accruing
                :proof-of-life-hit? false :jurisdiction "JPN"}
    "member-4" {:id "member-4" :name "A. Williams" :employer "Britannia Steelworks"
                :plan-type :defined-benefit :accrued-benefit 6000000 :disbursed-to-date 0
                :remaining-payment-periods 10 :vested? true :status :in-payout
                :proof-of-life-hit? false :jurisdiction "GBR"}
    "member-5" {:id "member-5" :name "B. Jones" :employer "Britannia Steelworks"
                :plan-type :defined-benefit :accrued-benefit 6000000 :disbursed-to-date 0
                :remaining-payment-periods 10 :vested? true :status :in-payout
                :proof-of-life-hit? true :jurisdiction "GBR"}
    "member-6" {:id "member-6" :name "佐藤 次郎" :employer "Sakura Manufacturing"
                :plan-type :defined-benefit :accrued-benefit 5000000 :disbursed-to-date 0
                :remaining-payment-periods 20 :vested? true :status :accruing
                :proof-of-life-hit? false :jurisdiction "JPN"}}})

;; ----------------------------- shared commit logic -----------------------------

(defn- pay-disbursement!
  "Backend-agnostic `:disbursement/mark-paid` -- looks up the
  disbursement + its member via the protocol, drafts the disbursement-
  payment record (the member's OWN requested amount -- the governor has
  already verified it does not exceed `registry/compute-max-
  disbursement`'s independent recompute, so this persists what was
  actually paid, not a substituted cap value, the same 'pay what was
  claimed, cap-rejected if it exceeds a limit' behavior `casualty.
  store`'s claim settlement uses), and returns {:result ..
  :disbursement-patch .. :member-patch ..} for the caller to persist.
  `:member-patch` advances the member's own running `:disbursed-to-date`
  balance by the amount just paid -- WITHOUT this, a SECOND lump-sum
  disbursement against the same member would compute its entitlement
  cap against the stale, pre-payment balance, silently allowing more
  than the member's true remaining entitlement to be drawn across
  multiple disbursements. `:annuity-installment` payments do not draw
  down `:accrued-benefit` itself (the whole point of an installment is
  to divide it across `:remaining-payment-periods`, not deplete it), so
  only `:lump-sum` advances this balance."
  [s disbursement-id]
  (let [d (disbursement s disbursement-id)
        m (member s (:member-id d))
        seq-n (next-sequence s (:jurisdiction m))
        result (registry/register-disbursement-payment
                (:member-id d) disbursement-id (:disbursement-type d)
                (:requested-amount d) (:jurisdiction m) seq-n)]
    {:result result
     :disbursement-patch {:status :paid
                          :disbursement-number (get result "disbursement_number")}
     :member-patch (when (= :lump-sum (:disbursement-type d))
                     {:disbursed-to-date (+ (double (:disbursed-to-date m 0))
                                            (double (:requested-amount d)))})}))

(defn- continue-payout!
  "Backend-agnostic `:payout/mark-continued` -- looks up the member via
  the protocol and drafts the payout-continuation record. Unlike
  disbursement payment, continuation is a RECURRING authorization (each
  periodic proof-of-life cycle is its own independent event) -- there is
  no 'already continued' guard to enforce, by design."
  [s member-id]
  (let [m (member s member-id)
        seq-n (continuation-sequence s (:jurisdiction m))
        result (registry/register-payout-continuation member-id (:jurisdiction m) seq-n)]
    {:result result}))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (member [_ id] (get-in @a [:members id]))
  (all-members [_] (sort-by :id (vals (:members @a))))
  (disbursement [_ id] (get-in @a [:disbursements id]))
  (proof-of-life-of [_ id] (get-in @a [:proof-of-life id]))
  (assessment-of [_ member-id] (get-in @a [:assessments member-id]))
  (ledger [_] (:ledger @a))
  (payment-history [_] (:payments @a))
  (continuation-history [_] (:continuations @a))
  (next-sequence [_ jurisdiction] (get-in @a [:sequences jurisdiction] 0))
  (continuation-sequence [_ jurisdiction] (get-in @a [:continuation-sequences jurisdiction] 0))
  (disbursement-already-paid? [_ disbursement-id] (= :paid (get-in @a [:disbursements disbursement-id :status])))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :member/upsert
      (swap! a update-in [:members (:id value)] merge value)

      :assessment/set
      (swap! a assoc-in [:assessments (first path)] payload)

      :proof-of-life/set
      (swap! a assoc-in [:proof-of-life (first path)] payload)

      :disbursement/filed
      (swap! a assoc-in [:disbursements (:id payload)] payload)

      :disbursement/mark-paid
      (let [disbursement-id (first path)
            member-id (:member-id (disbursement s disbursement-id))
            {:keys [result disbursement-patch member-patch]} (pay-disbursement! s disbursement-id)
            jurisdiction (:jurisdiction (member s member-id))]
        (swap! a (fn [state]
                   (cond-> state
                     true (update-in [:sequences jurisdiction] (fnil inc 0))
                     true (update-in [:disbursements disbursement-id] merge disbursement-patch)
                     member-patch (update-in [:members member-id] merge member-patch)
                     true (update :payments registry/append result))))
        result)

      :payout/mark-continued
      (let [member-id (first path)
            {:keys [result]} (continue-payout! s member-id)
            jurisdiction (:jurisdiction (member s member-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:continuation-sequences jurisdiction] (fnil inc 0))
                       (update :continuations registry/append result))))
        result)
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-members [s members] (when (seq members) (swap! a assoc :members members)) s))

(defn seed-db
  "A MemStore seeded with the demo member set. The deterministic default."
  []
  (->MemStore (atom (assoc (demo-data)
                           :assessments {} :proof-of-life {} :ledger [] :sequences {}
                           :disbursements {} :payments [] :continuation-sequences {} :continuations []))))

;; ----------------------------- DatomicStore (langchain.db) -----------------------------

(def ^:private schema
  "DataScript/Datomic-style schema: only constraint attrs are declared.
  Map/compound values (assessment/proof-of-life payloads, ledger facts,
  payment/continuation records) are stored as EDN strings so
  `langchain.db` doesn't expand them into sub-entities -- the same
  convention every sibling actor's store uses."
  {:member/id                       {:db/unique :db.unique/identity}
   :disbursement/id                 {:db/unique :db.unique/identity}
   :assessment/member-id            {:db/unique :db.unique/identity}
   :proof-of-life/member-id         {:db/unique :db.unique/identity}
   :ledger/seq                      {:db/unique :db.unique/identity}
   :payment/seq                     {:db/unique :db.unique/identity}
   :continuation/seq                {:db/unique :db.unique/identity}
   :sequence/jurisdiction           {:db/unique :db.unique/identity}
   :continuation-sequence/jurisdiction {:db/unique :db.unique/identity}})

;; the EDN-blob codec (enc/dec*) is shared machinery -- see
;; kotoba-lang/langchain-store's docstring (ADR-2607141600).
(defn- enc [v] (ls/enc v))
(defn- dec* [s] (ls/dec* s))

(defn- member->tx [{:keys [id name employer plan-type accrued-benefit disbursed-to-date
                          remaining-payment-periods vested? status proof-of-life-hit? jurisdiction]}]
  (cond-> {:member/id id}
    name                       (assoc :member/name name)
    employer                   (assoc :member/employer employer)
    plan-type                  (assoc :member/plan-type plan-type)
    accrued-benefit            (assoc :member/accrued-benefit accrued-benefit)
    disbursed-to-date          (assoc :member/disbursed-to-date disbursed-to-date)
    remaining-payment-periods  (assoc :member/remaining-payment-periods remaining-payment-periods)
    (some? vested?)            (assoc :member/vested? vested?)
    status                     (assoc :member/status status)
    (some? proof-of-life-hit?) (assoc :member/proof-of-life-hit? proof-of-life-hit?)
    jurisdiction               (assoc :member/jurisdiction jurisdiction)))

(def ^:private member-pull
  [:member/id :member/name :member/employer :member/plan-type :member/accrued-benefit
   :member/disbursed-to-date :member/remaining-payment-periods :member/vested?
   :member/status :member/proof-of-life-hit? :member/jurisdiction])

(defn- pull->member [m]
  (when (:member/id m)
    {:id (:member/id m) :name (:member/name m) :employer (:member/employer m)
     :plan-type (:member/plan-type m) :accrued-benefit (:member/accrued-benefit m)
     :disbursed-to-date (:member/disbursed-to-date m)
     :remaining-payment-periods (:member/remaining-payment-periods m)
     :vested? (boolean (:member/vested? m)) :status (:member/status m)
     :proof-of-life-hit? (boolean (:member/proof-of-life-hit? m))
     :jurisdiction (:member/jurisdiction m)}))

(defn- disbursement->tx [{:keys [id member-id disbursement-type requested-amount status disbursement-number]}]
  (cond-> {:disbursement/id id}
    member-id            (assoc :disbursement/member-id member-id)
    disbursement-type    (assoc :disbursement/disbursement-type disbursement-type)
    requested-amount     (assoc :disbursement/requested-amount requested-amount)
    status               (assoc :disbursement/status status)
    disbursement-number  (assoc :disbursement/disbursement-number disbursement-number)))

(def ^:private disbursement-pull
  [:disbursement/id :disbursement/member-id :disbursement/disbursement-type
   :disbursement/requested-amount :disbursement/status :disbursement/disbursement-number])

(defn- pull->disbursement [m]
  (when (:disbursement/id m)
    {:id (:disbursement/id m) :member-id (:disbursement/member-id m)
     :disbursement-type (:disbursement/disbursement-type m)
     :requested-amount (:disbursement/requested-amount m)
     :status (:disbursement/status m) :disbursement-number (:disbursement/disbursement-number m)}))

(defrecord DatomicStore [conn]
  Store
  (member [_ id]
    (pull->member (d/pull (d/db conn) member-pull [:member/id id])))
  (all-members [_]
    (->> (d/q '[:find [?id ...] :where [?e :member/id ?id]] (d/db conn))
         (map #(pull->member (d/pull (d/db conn) member-pull [:member/id %])))
         (sort-by :id)))
  (disbursement [_ id]
    (pull->disbursement (d/pull (d/db conn) disbursement-pull [:disbursement/id id])))
  (proof-of-life-of [_ id]
    (dec* (d/q '[:find ?p . :in $ ?mid
                :where [?k :proof-of-life/member-id ?mid] [?k :proof-of-life/payload ?p]]
              (d/db conn) id)))
  (assessment-of [_ member-id]
    (dec* (d/q '[:find ?p . :in $ ?mid
                :where [?a :assessment/member-id ?mid] [?a :assessment/payload ?p]]
              (d/db conn) member-id)))
  (ledger [_]
    (->> (d/q '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (payment-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :payment/seq ?s] [?e :payment/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (continuation-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :continuation/seq ?s] [?e :continuation/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (next-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :sequence/jurisdiction ?j] [?e :sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (continuation-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :continuation-sequence/jurisdiction ?j] [?e :continuation-sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (disbursement-already-paid? [s disbursement-id]
    (= :paid (:status (disbursement s disbursement-id))))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :member/upsert
      (d/transact! conn [(member->tx value)])

      :assessment/set
      (d/transact! conn [{:assessment/member-id (first path) :assessment/payload (enc payload)}])

      :proof-of-life/set
      (d/transact! conn [{:proof-of-life/member-id (first path) :proof-of-life/payload (enc payload)}])

      :disbursement/filed
      (d/transact! conn [(disbursement->tx payload)])

      :disbursement/mark-paid
      (let [disbursement-id (first path)
            member-id (:member-id (disbursement s disbursement-id))
            {:keys [result disbursement-patch member-patch]} (pay-disbursement! s disbursement-id)
            jurisdiction (:jurisdiction (member s member-id))
            next-n (inc (next-sequence s jurisdiction))]
        (d/transact! conn
                     (cond-> [(disbursement->tx (assoc disbursement-patch :id disbursement-id))
                              {:sequence/jurisdiction jurisdiction :sequence/next next-n}
                              {:payment/seq (count (payment-history s)) :payment/record (enc (get result "record"))}]
                       member-patch (conj (member->tx (assoc member-patch :id member-id)))))
        result)

      :payout/mark-continued
      (let [member-id (first path)
            {:keys [result]} (continue-payout! s member-id)
            jurisdiction (:jurisdiction (member s member-id))
            next-n (inc (continuation-sequence s jurisdiction))]
        (d/transact! conn
                     [{:continuation-sequence/jurisdiction jurisdiction :continuation-sequence/next next-n}
                      {:continuation/seq (count (continuation-history s)) :continuation/record (enc (get result "record"))}])
        result)
      nil)
    s)
  (append-ledger! [s fact]
    (d/transact! conn [{:ledger/seq (count (ledger s)) :ledger/fact (enc fact)}])
    fact)
  (with-members [s members]
    (when (seq members) (d/transact! conn (mapv member->tx (vals members)))) s))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data` ({:members
  ..}); empty when omitted."
  ([] (datomic-store {}))
  ([{:keys [members]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (with-members s members))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo member set -- the Datomic-backed
  analog of `seed-db`, used to prove protocol parity."
  []
  (datomic-store (demo-data)))
