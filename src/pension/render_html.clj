(ns pension.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 for `cloud-itonami-isic-6530`: this
  repo previously had NO demo page and no generator at all. This
  namespace drives the REAL actor stack (`pension.operation` ->
  `pension.governor` -> `pension.store`, through `langgraph.graph/run*`
  exactly as `pension.sim` and `pension.governor-contract-test` do) and
  renders the resulting store. Nothing on the page is hand-typed
  domain content: every member, disbursement, entitlement figure,
  violation detail, disbursement-payment/payout-continuation draft
  number and approver attribution is read back out of the store the
  run actually wrote.

  The scenario is a superset of this repo's own `pension.sim` demo
  driver (`clojure -M:dev:run`, confirmed BEFORE writing this file to
  produce a sensible ledger against the real seeded member ids
  `member-1`..`member-6`), extended so that:

    * ALL EIGHT of `pension.governor`'s HARD rules fire at least once
      (sim exercises seven; `:evidence-incomplete` needed a
      disbursement paid for a member with no jurisdiction assessment
      on file -- `disb-4` / `member-6` below), and
    * a PHASE-GATE hold is produced alongside them (`:jurisdiction/
      assess` submitted at phase 1, where that op is not yet
      write-enabled).

  The phase-gate case is on the page deliberately: a phase hold and a
  governor hold are BOTH written to the ledger as `:t :governor-hold`,
  but a phase hold carries an EMPTY `:violations` vector. Counting
  `:governor-hold` facts therefore overstates how many proposals the
  compliance layer actually refused. `hard-holds` / `phase-holds`
  below split them on `(seq :violations)` and the page shows them in
  two separate tables.

  Deterministic: no timestamps, no randomness, no wall-clock -- the
  same seed produces byte-identical output across reruns (verify by
  rendering twice into two scratch files and diffing).

  Usage: `clojure -M:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [jp-go-dds.skin]
            [langgraph.graph :as g]
            [pension.facts :as facts]
            [pension.operation :as op]
            [pension.phase :as phase]
            [pension.store :as store]))

(def ^:private operator
  "The licensed pension administrator this demo runs as. Phase 3 =
  supervised auto (see `pension.phase`)."
  {:actor-id "op-1" :actor-role :pension-administrator :phase 3})

(def ^:private trainee
  "The SAME operator earlier in the rollout: phase 1 (assisted-intake)
  only write-enables `:member/intake`, so anything else HOLDs on the
  phase gate with no governor violation at all."
  (assoc operator :phase 1))

;; ----------------------------- driving the real actor -----------------------------

(defn- approved-by
  "The approver recorded by the actor's own `:request-approval` node in
  this run's audit channel (`{:t :approval-granted :by ..}`), or nil.
  Read per RUN -- never joined back on [op subject], which is not
  unique here (`disb-1` is both paid and, later, refused a second
  payment; `member-4` is screened and then continued)."
  [state]
  (some #(when (= :approval-granted (:t %)) (:by %)) (:audit state)))

(defn- step!
  "Runs ONE operation through the real graph. If the actor interrupts
  for human approval (`interrupt-before #{:request-approval}`) and
  `:approve?` is set, resumes it with a real approval payload -- the
  same two-call handshake `pension.sim` uses. Returns the step record
  the console renders from."
  [actor {:keys [tid label request context approve?] :or {context operator}}]
  (let [res (g/run* actor {:request request :context context} {:thread-id tid})
        final (if (and approve? (= :interrupted (:status res)))
                (g/run* actor {:approval {:status :approved :by (:actor-id context)}}
                        {:thread-id tid :resume? true})
                res)
        state (:state final)]
    {:tid tid
     :label label
     :op (:op request)
     :subject (:subject request)
     :phase (:phase context)
     :interrupted? (= :interrupted (:status res))
     :status (:status final)
     :disposition (:disposition state)
     :approved-by (approved-by state)
     :violations (->> (:audit state)
                      (filter #(= :governor-hold (:t %)))
                      last
                      :violations)
     :phase-reason (->> (:audit state)
                        (filter #(= :governor-hold (:t %)))
                        last
                        :phase-reason)}))

(def ^:private scenario
  "The operation sequence, as data. Order matters: `disb-1` must be
  paid before the double-payment refusal can fire, and `disb-1` must
  be paid before `disb-3` can exceed member-1's REMAINING entitlement."
  [{:tid "t1" :label "会員記録の受付 (clean, no capital risk)"
    :request {:op :member/intake :subject "member-1"
              :patch {:id "member-1" :status :accruing}}}
   {:tid "t2" :label "法域の給付要件アセスメント (human approves)" :approve? true
    :request {:op :jurisdiction/assess :subject "member-1"}}
   {:tid "t3" :label "給付金請求の受付 (vested member; filing moves no capital)"
    :request {:op :disbursement/file :subject "disb-1" :member-id "member-1"
              :disbursement-type :lump-sum :requested-amount 5000000}}
   {:tid "t4" :label "給付金の支払い (ALWAYS escalates; human approves)" :approve? true
    :request {:op :disbursement/pay :subject "disb-1"}}
   {:tid "t5" :label "生存確認スクリーニング (clear; human approves)" :approve? true
    :request {:op :proof-of-life/screen :subject "member-4"}}
   {:tid "t6" :label "年金継続支払い (ALWAYS escalates; human approves)" :approve? true
    :request {:op :payout/continue :subject "member-4"}}

   ;; --- HARD governor holds: one per rule, none reaches a human ---
   {:tid "t7" :label "未登録法域の要件を創作した提案"
    :request {:op :jurisdiction/assess :subject "member-2" :no-spec? true}}
   {:tid "t8" :label "権利未確定(unvested)会員の給付金請求"
    :request {:op :disbursement/file :subject "disb-2" :member-id "member-3"
              :disbursement-type :lump-sum :requested-amount 500000}}
   {:tid "t9" :label "2件目の請求受付 (filing itself is clean)"
    :request {:op :disbursement/file :subject "disb-3" :member-id "member-1"
              :disbursement-type :lump-sum :requested-amount 10000000}}
   {:tid "t10" :label "残存権利額を超える支払い提案"
    :request {:op :disbursement/pay :subject "disb-3"}}
   {:tid "t11" :label "アセスメント未実施会員への請求受付 (clean)"
    :request {:op :disbursement/file :subject "disb-4" :member-id "member-6"
              :disbursement-type :lump-sum :requested-amount 1000000}}
   {:tid "t12" :label "必要書類が未充足のままの支払い提案"
    :request {:op :disbursement/pay :subject "disb-4"}}
   {:tid "t13" :label "生存確認に失敗した会員のスクリーニング"
    :request {:op :proof-of-life/screen :subject "member-5"}}
   {:tid "t14" :label "支払中でない会員の継続支払い提案"
    :request {:op :payout/continue :subject "member-6"}}
   {:tid "t15" :label "存在しない給付金請求の支払い提案"
    :request {:op :disbursement/pay :subject "disb-999"}}
   {:tid "t16" :label "支払い済み請求の二重支払い提案"
    :request {:op :disbursement/pay :subject "disb-1"}}

   ;; --- phase/rollout gate hold: NOT a compliance refusal ---
   {:tid "t17" :label "phase 1 では書き込み対象外の操作" :context trainee
    :request {:op :jurisdiction/assess :subject "member-4"}}])

(defn run-demo!
  "Runs `scenario` against a freshly seeded store through the real
  OperationActor. Returns {:db .. :steps ..}; every field the console
  renders is real governor/store output."
  []
  (let [db (store/seed-db)
        actor (op/build db)]
    {:db db
     :steps (mapv #(step! actor %) scenario)}))

;; ----------------------------- derived views over the real run -----------------------------

(defn- hard-holds
  "Ledger facts the COMPLIANCE layer refused: a hold carrying at least
  one governor violation. A phase-gate hold is also `:t :governor-hold`
  but carries none, so it is excluded here by construction."
  [ledger]
  (filter #(and (= :governor-hold (:t %)) (seq (:violations %))) ledger))

(defn- phase-holds
  "Ledger facts the ROLLOUT PHASE gate held: `:t :governor-hold` with an
  EMPTY violation list. The governor found nothing wrong; the op is
  simply not write-enabled (or not auto-eligible) at that phase."
  [ledger]
  (filter #(and (= :governor-hold (:t %)) (empty? (:violations %))) ledger))

(defn- disbursement-ids
  "Every disbursement id this run touched, in first-seen order, taken
  from the run steps (not hand-listed) so a scenario edit can never
  leave the table stale."
  [steps]
  (->> steps
       (filter #(contains? #{:disbursement/file :disbursement/pay} (:op %)))
       (map :subject)
       distinct
       vec))

(defn- approver-on
  "Any approver key surviving on a committed register value. Registers
  are written with keyword keys (assessment / proof-of-life payloads)
  and the registry drafts with string keys, so both spellings are
  probed -- this is a RENDER-TIME scan, so the page self-corrects if
  the store later starts retaining the approver."
  [v]
  (when (map? v)
    (or (:approved-by v) (get v "approved_by") (get v "approved-by"))))

(defn- register-for
  "The committed register a step produced, as [label value-or-values].
  Used only to ask whether the approver survived onto the record --
  matched by the step's OWN subject and effect, never by joining
  [op subject] across the ledger."
  [db {:keys [op subject]}]
  (case op
    :member/intake        ["member 記録" (store/member db subject)]
    :jurisdiction/assess  ["assessment 台帳" (store/assessment-of db subject)]
    :proof-of-life/screen ["proof-of-life 台帳" (store/proof-of-life-of db subject)]
    :disbursement/file    ["disbursement 記録" (store/disbursement db subject)]
    :disbursement/pay     ["disbursement-payment draft"
                           (filter #(= subject (get % "disbursement_id"))
                                   (store/payment-history db))]
    :payout/continue      ["payout-continuation draft"
                           (filter #(= subject (get % "member_id"))
                                   (store/continuation-history db))]
    [(str (name op) " (register 不明)") nil]))

(defn- attribution-rows
  "One row per step that a human actually approved in this run, with
  the approver MEASURED from the run's audit channel and the retention
  MEASURED from the committed register."
  [db steps]
  (for [s steps
        :when (:approved-by s)
        :let [[label value] (register-for db s)
              values (if (sequential? value) value [value])
              retained (some approver-on values)]]
    (assoc s :register label :retained retained :register-count (count (remove nil? values)))))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- nm
  "Keyword -> its printed name WITHOUT the leading colon but WITH the
  namespace. `clojure.core/name` is wrong here: it renders
  `:disbursement/pay` as \"pay\" and `:disbursement/file` as \"file\",
  dropping exactly the qualifier that says which register the op
  writes."
  [v]
  (if (keyword? v) (subs (str v) 1) (str v)))

(defn- row [& cells]
  (str "        <tr>" (str/join (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- money
  "Thousands-grouped integer. Written out by hand rather than via
  `format \"%,d\"` so the output cannot vary with the JVM's default
  locale -- this page must be byte-identical across reruns and
  machines."
  [n]
  (when n
    (let [digits (str (long n))
          grouped (->> (reverse digits)
                       (partition-all 3)
                       (map (comp str/join reverse))
                       reverse
                       (str/join ","))]
      grouped)))

(defn- member-row [{:keys [id name employer jurisdiction plan-type accrued-benefit
                           disbursed-to-date vested? status proof-of-life-hit?]}]
  (row (str "<code>" (esc id) "</code>")
       (esc name)
       (esc employer)
       (esc jurisdiction)
       (str "<code>" (esc (nm plan-type)) "</code>")
       (esc (money accrued-benefit))
       (esc (money disbursed-to-date))
       (if vested?
         "<span class=\"ok\">vested</span>"
         "<span class=\"warn\">not vested</span>")
       (str "<code>" (esc (nm status)) "</code>")
       (if proof-of-life-hit?
         "<span class=\"critical\">failed</span>"
         "<span class=\"ok\">clear</span>")))

(defn- disbursement-row [db id]
  (let [d (store/disbursement db id)
        m (when d (store/member db (:member-id d)))]
    (row (str "<code>" (esc id) "</code>")
         (if d (str "<code>" (esc (:member-id d)) "</code>") "<span class=\"muted\">—</span>")
         (if m (esc (:name m)) "<span class=\"muted\">—</span>")
         (if d (str "<code>" (esc (nm (:disbursement-type d))) "</code>") "<span class=\"muted\">—</span>")
         (if d (esc (money (:requested-amount d))) "<span class=\"muted\">—</span>")
         (cond
           (nil? d) "<span class=\"critical\">not on file</span>"
           (= :paid (:status d)) "<span class=\"ok\">paid</span>"
           :else "<span class=\"warn\">filed</span>")
         (if (:disbursement-number d)
           (str "<code>" (esc (:disbursement-number d)) "</code>")
           "<span class=\"muted\">—</span>"))))

(defn- disposition-cell [{:keys [disposition violations phase-reason]}]
  (cond
    (and (= :hold disposition) (seq violations))
    "<span class=\"critical\">HARD hold</span>"
    (= :hold disposition)
    (str "<span class=\"warn\">phase hold · " (esc (nm (or phase-reason :phase-gate))) "</span>")
    (= :commit disposition) "<span class=\"ok\">committed</span>"
    (= :escalate disposition) "<span class=\"warn\">awaiting approval</span>"
    :else "<span class=\"muted\">—</span>"))

(defn- step-row [{:keys [tid label op subject phase interrupted? approved-by] :as s}]
  (row (str "<code>" (esc tid) "</code>")
       (esc phase)
       (str "<code>" (esc (nm op)) "</code>")
       (str "<code>" (esc subject) "</code>")
       (esc label)
       (if interrupted?
         "<span class=\"warn\">human gate</span>"
         "<span class=\"muted\">—</span>")
       (disposition-cell s)
       (if approved-by
         (str "<code>" (esc approved-by) "</code>")
         "<span class=\"muted\">—</span>")))

(defn- hard-hold-row [{:keys [op subject violations confidence]}]
  (let [v (first violations)]
    (row (str "<code>" (esc (nm op)) "</code>")
         (str "<code>" (esc subject) "</code>")
         (str "<span class=\"critical\">" (esc (nm (:rule v))) "</span>")
         (esc (:detail v))
         (esc confidence))))

(defn- phase-hold-row [{:keys [op subject phase phase-reason violations]}]
  (row (str "<code>" (esc (nm op)) "</code>")
       (str "<code>" (esc subject) "</code>")
       (esc phase)
       (str "<code>" (esc (nm (or phase-reason :unknown))) "</code>")
       (str (count violations) " <span class=\"muted\">(governor found nothing)</span>")))

(defn- ledger-row [{:keys [t op subject disposition basis]}]
  (row (str "<code>" (esc (nm t)) "</code>")
       (str "<code>" (esc (nm (or op :n-a))) "</code>")
       (str "<code>" (esc subject) "</code>")
       (esc (nm (or disposition "")))
       (esc (str/join " / " (map nm (or basis []))))))

(defn- payment-row [r]
  (row (str "<code>" (esc (get r "record_id")) "</code>")
       (str "<code>" (esc (get r "member_id")) "</code>")
       (str "<code>" (esc (get r "disbursement_id")) "</code>")
       (str "<code>" (esc (get r "disbursement_type")) "</code>")
       (esc (money (get r "disbursed_amount")))
       (esc (get r "jurisdiction"))
       (if (approver-on r)
         (str "<code>" (esc (approver-on r)) "</code>")
         "<span class=\"muted\">not retained</span>")))

(defn- continuation-row [r]
  (row (str "<code>" (esc (get r "record_id")) "</code>")
       (str "<code>" (esc (get r "member_id")) "</code>")
       (esc (get r "jurisdiction"))
       (if (approver-on r)
         (str "<code>" (esc (approver-on r)) "</code>")
         "<span class=\"muted\">not retained</span>")))

(defn- attribution-row [{:keys [op subject approved-by register retained register-count]}]
  (row (str "<code>" (esc (nm op)) "</code>")
       (str "<code>" (esc subject) "</code>")
       (str "<code>" (esc approved-by) "</code>")
       (esc register)
       (esc register-count)
       (if retained
         (str "<span class=\"ok\">retained · " (esc retained) "</span>")
         "<span class=\"warn\">audit only — not retained on record</span>")))

(defn- jurisdiction-row [[iso3 {:keys [name owner-authority legal-basis provenance required-evidence]}]]
  (row (str "<code>" (esc iso3) "</code>")
       (esc name)
       (esc owner-authority)
       (esc legal-basis)
       (esc (count required-evidence))
       (str "<code>" (esc provenance) "</code>")))

(defn- phase-row [[n {:keys [label writes auto]}]]
  (row (esc n)
       (str "<code>" (esc label) "</code>")
       (if (seq writes)
         (str/join " " (map #(str "<code>" (esc (nm %)) "</code>") (sort (map nm writes))))
         "<span class=\"muted\">none</span>")
       (if (seq auto)
         (str/join " " (map #(str "<code>" (esc (nm %)) "</code>") (sort (map nm auto))))
         "<span class=\"muted\">none</span>")))

(defn- table [headers rows]
  (str "    <table>\n"
       "      <thead><tr>" (str/join (map #(str "<th>" % "</th>") headers)) "</tr></thead>\n"
       "      <tbody>\n"
       (str/join "\n" rows) "\n"
       "      </tbody>\n"
       "    </table>\n"))

(defn- section [title lead body]
  (str "  <section class=\"card\">\n"
       "    <h2>" title "</h2>\n"
       "    <p class=\"muted\">" lead "</p>\n"
       body
       "  </section>\n"))

(defn render
  "Renders the whole operator console from a `run-demo!` result. Every
  table below is a projection of the store/ledger that run produced."
  [{:keys [db steps]}]
  (let [ledger (vec (store/ledger db))
        hard (vec (hard-holds ledger))
        phased (vec (phase-holds ledger))
        payments (vec (store/payment-history db))
        continuations (vec (store/continuation-history db))
        attributions (vec (attribution-rows db steps))
        retained? (some :retained attributions)]
    (str
     "<!doctype html>\n"
     "<html lang=\"ja\"><head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
     "<title>cloud-itonami-isic-6530 · pension funding — Operator Console</title><style>"
     (jp-go-dds.skin/dds+skin)
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Pension funding (ISIC 6530) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · disbursement payment / payout continuation are ALWAYS human-approved</span>\n"
     "</header>\n"
     "<main>\n"

     (section "会員台帳 (SSoT)"
              (str "Build-time snapshot generated from <code>pension.store</code> by "
                   "<code>pension.render-html</code> (<code>clojure -M:render-html</code>). "
                   "Balances are the store's own values AFTER this run — <code>member-1</code>'s "
                   "<code>disbursed-to-date</code> was advanced by the disbursement the human approved, "
                   "which is what makes the later over-entitlement refusal fire.")
              (table ["Member" "氏名" "Employer" "法域" "Plan" "Accrued" "Disbursed" "Vesting" "Status" "Proof-of-life"]
                     (map member-row (store/all-members db))))

     (section "給付金請求 (disbursements touched by this run)"
              (str "Ids are taken from the run's own steps and looked up through the Store protocol; "
                   "<code>disb-999</code> is deliberately absent from the register — the governor refuses "
                   "to pay a disbursement that does not exist.")
              (table ["Disbursement" "Member" "氏名" "Type" "Requested" "Status" "支払番号"]
                     (map (partial disbursement-row db) (disbursement-ids steps))))

     (section "オペレーション実行 (this run, in order)"
              (str "Each row is one <code>langgraph.graph/run*</code> through the real OperationActor "
                   "(intake → advise → govern → decide → commit | hold | approval). "
                   "&ldquo;human gate&rdquo; means the graph actually interrupted at "
                   "<code>:request-approval</code> and waited for a person.")
              (table ["Thread" "Phase" "Op" "Subject" "Scenario" "Human gate" "Disposition" "承認者"]
                     (map step-row steps)))

     (section (str "Pension Governor が拒否した提案 — HARD holds (" (count hard) ")")
              (str "Un-overridable compliance refusals: a human approver CANNOT approve past these, and none "
                   "of them ever reached a human. Every one of <code>pension.governor</code>'s eight HARD "
                   "rules is represented. Details are the governor's own violation text.")
              (table ["Op" "Subject" "Rule" "Detail (governor 出力)" "LLM confidence"]
                     (map hard-hold-row hard)))

     (section (str "Rollout phase gate が止めた提案 — NOT compliance refusals (" (count phased) ")")
              (str "These are written to the ledger with the SAME <code>:t :governor-hold</code> fact type as the "
                   "table above, but with an <strong>empty</strong> <code>:violations</code> vector — the governor "
                   "found nothing wrong; the op is simply not write-enabled at that rollout phase. "
                   "Counting <code>:governor-hold</code> facts alone would overstate compliance refusals by "
                   (count phased) ", so this console splits them.")
              (table ["Op" "Subject" "Phase" "Reason" "Violations"]
                     (map phase-hold-row phased)))

     (section "Rollout phase table (<code>pension.phase</code>)"
              (str "Read straight out of <code>pension.phase/phases</code>. Note the structural invariant: "
                   "<code>:disbursement/pay</code> and <code>:payout/continue</code> appear in NO phase's auto "
                   "column, including phase 3 — paying a real benefit and continuing a real payout stream are "
                   "permanently a human pension administrator's call, enforced independently by the governor's "
                   "actuation gate.")
              (table ["Phase" "Label" "Writes enabled" "Auto-commit when clean"]
                     (map phase-row (sort-by key phase/phases))))

     (section "監査台帳 (append-only, this run)"
              "Every decision fact the run appended — proposals that committed, and every refusal."
              (table ["Fact" "Op" "Subject" "Disposition" "Basis"]
                     (map ledger-row ledger)))

     (section "給付金支払いドラフト (pension.registry)"
              (str "Unsigned draft records the fund would keep. Reference numbers are the registry's own "
                   "jurisdiction-scoped sequence, not invented here. The 承認者 column is scanned off the "
                   "record itself at render time.")
              (table ["Record" "Member" "Disbursement" "Type" "Amount" "法域" "承認者"]
                     (map payment-row payments)))

     (section "継続支払いドラフト (pension.registry)"
              "Recurring payout-continuation authorizations — one per proof-of-life cycle, by design."
              (table ["Record" "Member" "法域" "承認者"]
                     (map continuation-row continuations)))

     (section "人による承認の帰属 (measured at render time)"
              (str "The approver is read from THIS run's audit channel (<code>:t :approval-granted</code>), per "
                   "thread — never joined on [op, subject], which is not unique here (<code>disb-1</code> is "
                   "paid and later refused a second payment; <code>member-4</code> is screened and then "
                   "continued). The retention column re-scans the committed register for an approver key, so "
                   "this page self-corrects if the store later starts retaining it. "
                   (if retained?
                     "Measured result: at least one register DOES retain the approver."
                     "Measured result: NO register retains the approver.")
                   " Where a register does not retain it, the attribution is audit-only.")
              (table ["Op" "Subject" "承認者 (audit)" "Committed register" "Records" "Retained on record?"]
                     (map attribution-row attributions)))

     (section "法域 spec-basis カタログ (pension.facts)"
              (str "The catalog the governor checks every jurisdiction proposal against. Coverage is reported "
                   "honestly: " (count facts/catalog) " jurisdictions are seeded with an official source — this "
                   "is a starting catalog, not a survey of all ~194 jurisdictions. A jurisdiction absent here "
                   "(e.g. <code>ATL</code>, member-2's) has NO spec-basis, and the governor HARD-holds any "
                   "proposal that invents one.")
              (table ["ISO3" "Name" "Owner authority" "Legal basis" "必要書類" "Provenance"]
                     (map jurisdiction-row (sort-by key facts/catalog))))

     "</main>\n"
     "<footer>\n"
     "  <p class=\"muted\">Generated by <code>pension.render-html</code> from a real "
     "<code>pension.operation</code> run against a freshly seeded <code>pension.store/MemStore</code>. "
     "No hand-written rows, no timestamps — deterministic across reruns.</p>\n"
     "</footer>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [db steps] :as result} (run-demo!)
        ledger (vec (store/ledger db))
        hard (vec (hard-holds ledger))
        phased (vec (phase-holds ledger))
        rules (sort (distinct (map (comp :rule first :violations) hard)))]
    ;; Build-time invariant, not a convention: a console that shows no
    ;; genuine compliance refusal is not evidence that the governor
    ;; works, so refuse to write the file at all. A phase-gate hold does
    ;; NOT satisfy this -- `hard-holds` requires a non-empty violation
    ;; list, so a scenario that only tripped the rollout gate still
    ;; throws here.
    (when (empty? hard)
      (throw (ex-info "render-html: the run produced ZERO HARD governor holds; refusing to write a console that cannot demonstrate the compliance layer"
                      {:ledger-facts (count ledger)
                       :governor-hold-facts (count (filter #(= :governor-hold (:t %)) ledger))
                       :phase-gate-holds (count phased)})))
    (.mkdirs (java.io.File. (or (.getParent (java.io.File. ^String out)) ".")))
    (spit out (render result))
    (println "wrote" out)
    (println "  steps            " (count steps))
    (println "  ledger facts     " (count ledger))
    (println "  HARD holds       " (count hard) (vec rules))
    (println "  phase-gate holds " (count phased))
    (println "  payment drafts   " (count (store/payment-history db)))
    (println "  continuation     " (count (store/continuation-history db)))
    (doseq [a (attribution-rows db steps)]
      (println "  approver" (:op a) (:subject a) "->" (:approved-by a)
               "| register" (pr-str (:register a))
               "| retained" (pr-str (:retained a))))))
