(ns pension.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 for `cloud-itonami-isic-6530`: this
  repo previously had NO demo page and no generator at all. This
  namespace drives the REAL actor stack (`pension.operation` ->
  `pension.governor` -> `pension.phase` -> `pension.store`) through
  `langgraph.graph/run*`, exactly the way this repo's own
  `pension.sim` demo driver does (`clojure -M:dev:run`, confirmed to
  run green against the real seeded member ids `member-1`..`member-6`
  BEFORE this file was written), and renders the resulting store +
  audit ledger.

  EVERYTHING on the page is real output of the run performed at build
  time: member records, disbursement records, jurisdiction spec-basis
  citations, entitlement caps, governor violation rules and their
  Japanese detail strings, the draft disbursement-payment /
  payout-continuation record ids, and the approver attribution. Nothing
  is hand-typed. Where a value cannot be obtained from the store, the
  page SAYS SO rather than inventing one -- see `attribution-rows`,
  which derives whether an approver actually survived into each written
  record by looking for the key, so the page self-corrects if the store
  is later changed.

  Determinism: no timestamps, no wall-clock, no map-iteration order
  leaks (every collection is explicitly sorted or comes from an
  append-ordered vector). Two consecutive runs against the same seed
  produce byte-identical output.

  Build-time invariant: `-main` REFUSES to write the page unless the
  run produced at least one HARD governor hold that actually carries a
  violation. The check is two-stage on purpose -- a rollout
  phase-gating hold (`:phase-reason :phase-disabled`) is also written
  as a `:governor-hold` fact but carries an EMPTY `:violations` vector,
  so counting `:governor-hold` facts alone would be satisfied by a run
  in which the compliance governor never refused anything.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jp-go-dds.skin]
            [langgraph.graph :as g]
            [pension.facts :as facts]
            [pension.governor :as governor]
            [pension.operation :as op]
            [pension.phase :as phase]
            [pension.store :as store]))

;; ----------------------------- the run -----------------------------

(def ^:private administrator
  "The licensed pension administrator who resumes an interrupted run."
  "administrator-tanaka")

(def ^:private actuation-officer
  "A second named human. `:disbursement/pay` / `:payout/continue` are
  the two real-world money-moving acts; this scenario routes them to a
  different approver so the page can show WHICH human authorized WHICH
  actuation (and, below, whether the store kept that fact)."
  "administrator-yamada")

(defn- ctx [ph] {:actor-id "op-1" :actor-role :pension-administrator :phase ph})

(defn- step!
  "Runs ONE operation through the compiled actor and records what
  actually happened. `:approve` is `:approved` / `:rejected` / nil
  (leave the thread interrupted). Returns the run record."
  [actor {:keys [tid phase request approve by note]}]
  (let [c        (ctx phase)
        r1       (g/run* actor {:request request :context c} {:thread-id tid})
        paused?  (= :interrupted (:status r1))
        r2       (when (and paused? approve)
                   (g/run* actor {:approval {:status approve :by by}}
                           {:thread-id tid :resume? true}))
        final    (or r2 r1)
        st       (:state final)
        req-fact (last (filter #(= :approval-requested (:t %)) (:audit (:state r1))))]
    {:tid          tid
     :note         note
     :phase        phase
     :op           (:op request)
     :subject      (:subject request)
     :escalated?   paused?
     :escalation-reason (:reason req-fact)
     :approval     (when (and paused? approve) {:status approve :by by})
     :disposition  (if (and paused? (nil? approve)) :awaiting-approval (:disposition st))
     :effect       (get-in st [:record :effect])
     :verdict      (:verdict st)
     :audit        (:audit st)}))

(defn run-demo!
  "Drives a freshly seeded store through a scenario that reaches EVERY
  disposition this actor can produce and EVERY HARD rule this repo's
  governor implements (all eight), plus the two non-governor refusal
  kinds the page must not blur together with them:

    * approved paths  -- a phase-1 member intake (escalates because
                         phase 1 grants no auto rights at all), a JPN
                         jurisdiction assessment, a proof-of-life
                         screen, a real disbursement payment and a real
                         payout continuation. The last two ALWAYS
                         escalate (`:actuation/*`), at every phase.
    * auto-commits    -- `:disbursement/file` at phase 3 (no capital
                         moves when a claim is merely filed).
    * HARD holds      -- `:no-spec-basis`, `:member-not-vested`,
                         `:disbursement-exceeds-entitlement`,
                         `:evidence-incomplete` (alone, and again
                         stacked with the entitlement cap on one
                         proposal), `:proof-of-life-failed`,
                         `:member-not-in-payout`,
                         `:disbursement-missing`, `:double-payment`.
                         None of these ever reaches a human.
    * phase-gate holds -- a write attempted at a phase that does not
                         enable it. This is the rollout gate, NOT a
                         compliance refusal: the fact carries an EMPTY
                         `:violations` vector.
    * human refusal   -- an escalated actuation the named approver
                         REJECTED.

  Returns `{:db .. :runs ..}`; every field the renderer reads comes
  from one of those two."
  []
  (let [db    (store/seed-db)
        actor (op/build db)
        runs  (mapv
               (partial step! actor)
               [;; --- rollout gate: writes not enabled at this phase ---
                {:tid "p0-intake" :phase 0
                 :note "phase 0 is read-only: no write op is enabled at all"
                 :request {:op :member/intake :subject "member-6"
                           :patch {:id "member-6" :status :accruing}}}
                {:tid "p1-assess" :phase 1
                 :note "phase 1 enables :member/intake only"
                 :request {:op :jurisdiction/assess :subject "member-1"}}

                ;; --- approved path, phase 1 (nothing is auto-eligible) ---
                {:tid "p1-intake" :phase 1
                 :note "governor-clean, but phase 1 grants no auto rights"
                 :request {:op :member/intake :subject "member-1"
                           :patch {:id "member-1" :status :accruing}}
                 :approve :approved :by administrator}

                ;; --- approved path, phase 3 ---
                {:tid "assess-m1" :phase 3
                 :note "JPN has an official spec-basis in pension.facts"
                 :request {:op :jurisdiction/assess :subject "member-1"}
                 :approve :approved :by administrator}
                {:tid "file-disb-1" :phase 3
                 :note "filing moves no capital -> auto-commit at phase 3"
                 :request {:op :disbursement/file :subject "disb-1" :member-id "member-1"
                           :disbursement-type :lump-sum :requested-amount 5000000}}
                {:tid "pay-disb-1" :phase 3
                 :note "REAL money leaves the fund -> always a human"
                 :request {:op :disbursement/pay :subject "disb-1"}
                 :approve :approved :by actuation-officer}
                {:tid "screen-m4" :phase 3
                 :note "screening is never auto-eligible, at any phase"
                 :request {:op :proof-of-life/screen :subject "member-4"}
                 :approve :approved :by administrator}
                {:tid "continue-m4" :phase 3
                 :note "REAL periodic payments keep flowing -> always a human"
                 :request {:op :payout/continue :subject "member-4"}
                 :approve :approved :by actuation-officer}

                ;; --- human refusal (NOT a governor refusal) ---
                {:tid "assess-m6" :phase 3
                 :request {:op :jurisdiction/assess :subject "member-6"}
                 :approve :approved :by administrator}
                {:tid "file-disb-6" :phase 3
                 :request {:op :disbursement/file :subject "disb-6" :member-id "member-6"
                           :disbursement-type :lump-sum :requested-amount 1000000}}
                {:tid "pay-disb-6" :phase 3
                 :note "governor clean; the named human declined"
                 :request {:op :disbursement/pay :subject "disb-6"}
                 :approve :rejected :by actuation-officer}

                ;; --- HARD holds: never reach a human ---
                {:tid "assess-m2" :phase 3
                 :note "member-2's jurisdiction ATL is absent from pension.facts/catalog"
                 :request {:op :jurisdiction/assess :subject "member-2"}}
                {:tid "file-disb-2" :phase 3
                 :note "member-3 has never vested"
                 :request {:op :disbursement/file :subject "disb-2" :member-id "member-3"
                           :disbursement-type :lump-sum :requested-amount 500000}}
                {:tid "file-disb-3" :phase 3
                 :note "filing itself is clean -- the cap is checked at payment"
                 :request {:op :disbursement/file :subject "disb-3" :member-id "member-1"
                           :disbursement-type :lump-sum :requested-amount 10000000}}
                {:tid "pay-disb-3" :phase 3
                 :note "member-1 already drew 5,000,000 of a 12,000,000 entitlement"
                 :request {:op :disbursement/pay :subject "disb-3"}}
                {:tid "file-disb-4" :phase 3
                 :request {:op :disbursement/file :subject "disb-4" :member-id "member-4"
                           :disbursement-type :lump-sum :requested-amount 1000000}}
                {:tid "pay-disb-4" :phase 3
                 :note "member-4's GBR evidence checklist was never assessed"
                 :request {:op :disbursement/pay :subject "disb-4"}}
                {:tid "file-disb-5" :phase 3
                 :request {:op :disbursement/file :subject "disb-5" :member-id "member-2"
                           :disbursement-type :lump-sum :requested-amount 5000000}}
                {:tid "pay-disb-5" :phase 3
                 :note "two independent HARD rules fire on ONE proposal"
                 :request {:op :disbursement/pay :subject "disb-5"}}
                {:tid "screen-m5" :phase 3
                 :note "the screening op HARD-holds on its own finding"
                 :request {:op :proof-of-life/screen :subject "member-5"}}
                {:tid "continue-m6" :phase 3
                 :note "member-6 is :accruing, never :in-payout"
                 :request {:op :payout/continue :subject "member-6"}}
                {:tid "pay-disb-999" :phase 3
                 :note "no such disbursement was ever filed"
                 :request {:op :disbursement/pay :subject "disb-999"}}
                {:tid "pay-disb-1-again" :phase 3
                 :note "disb-1 was paid earlier in this same run"
                 :request {:op :disbursement/pay :subject "disb-1"}}])]
    {:db db :runs runs}))

;; --------------------------- derived views ---------------------------

(defn hard-holds
  "Compliance refusals: `:governor-hold` facts that actually carry a
  violation. A rollout phase-gate hold is written with the same `:t`
  but an EMPTY `:violations`, so it is excluded here (and reported in
  its own table)."
  [ledger]
  (filterv #(and (= :governor-hold (:t %)) (seq (:violations %))) ledger))

(defn phase-gate-holds
  "Rollout-gate refusals: a write attempted at a phase that does not
  enable it. Distinct from a compliance refusal -- the governor found
  nothing wrong."
  [ledger]
  (filterv #(and (= :governor-hold (:t %)) (empty? (:violations %))) ledger))

(defn- approval-rejections [ledger]
  (filterv #(= :approval-rejected (:t %)) ledger))

(defn- disbursement-ids
  "Every disbursement id this run touched, in first-seen ledger order --
  derived from the append-ordered ledger, never hand-listed."
  [ledger]
  (->> ledger
       (filter #(#{:disbursement/file :disbursement/pay} (:op %)))
       (map :subject)
       distinct
       vec))

(defn- approver-in
  "The approver attribution ACTUALLY present in a stored record, or nil.
  Scans for any key -- keyword or string -- whose name mentions
  `approv`, so this measures the store's real behaviour instead of
  asserting a known defect: if the store is later changed to keep the
  approver, this finds it with no edit here. Matches are sorted by key
  name so the result never depends on map iteration order."
  [m]
  (when (map? m)
    (->> m
         (keep (fn [[k v]]
                 (let [n (if (keyword? k) (name k) (str k))]
                   (when (re-find #"(?i)approv" n) [n v]))))
         (sort-by first)
         first)))

(defn- written-record
  "The record an approved run actually wrote into the SSoT, looked up
  through the Store protocol by the effect the run committed. Returns
  `[label record]`, or `[label nil]` when the effect writes nowhere the
  protocol can read back."
  [db {:keys [effect subject]}]
  (case effect
    :member/upsert          ["pension.store/member" (store/member db subject)]
    :assessment/set         ["pension.store/assessment-of" (store/assessment-of db subject)]
    :proof-of-life/set      ["pension.store/proof-of-life-of" (store/proof-of-life-of db subject)]
    :disbursement/filed     ["pension.store/disbursement" (store/disbursement db subject)]
    :disbursement/mark-paid ["pension.store/payment-history"
                             (let [hits (filterv #(= subject (get % "disbursement_id"))
                                                 (store/payment-history db))]
                               (when (= 1 (count hits)) (first hits)))]
    :payout/mark-continued  ["pension.store/continuation-history"
                             (let [hits (filterv #(= subject (get % "member_id"))
                                                 (store/continuation-history db))]
                               (when (= 1 (count hits)) (first hits)))]
    [(str effect) nil]))

(defn attribution-audit
  "MEASURED approver attribution, one row per approved run: who the
  human was (from the run + the `:approval-granted` audit fact), where
  the commit landed, and whether that stored record actually kept the
  approver. Nothing here is assumed about the store."
  [db runs]
  (for [{:keys [op subject effect approval audit] :as r} runs
        :when (= :approved (:status approval))
        :let [granted (last (filter #(= :approval-granted (:t %)) audit))
              [where rec] (written-record db r)
              found (approver-in rec)]]
    {:op op :subject subject :effect effect
     :approver-in-run (:by approval)
     :approver-in-ledger (:by granted)
     :where where
     :record-present? (some? rec)
     :approver-key (first found)
     :approver-value (second found)}))

;; ----------------------------- formatting -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- group3 [s]
  (let [neg?  (str/starts-with? s "-")
        s     (if neg? (subs s 1) s)
        [i f] (str/split s #"\." 2)
        gi    (->> (reverse i)
                   (partition-all 3)
                   (map #(apply str (reverse %)))
                   reverse
                   (str/join ","))]
    (str (when neg? "-") gi (when f (str "." f)))))

(defn- num* [n]
  (cond
    (nil? n) "—"
    (not (number? n)) (str n)
    (== (double n) (Math/floor (double n))) (group3 (str (long n)))
    :else (group3 (str n))))

(defn- kw* [k] (if (keyword? k) (name k) (str k)))

(defn- code [v] (str "<code>" (esc v) "</code>"))

(defn- row [& cells]
  (str "        <tr>" (apply str (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- tbl [headers rows]
  (str "    <table>\n"
       "      <thead><tr>" (apply str (map #(str "<th>" % "</th>") headers)) "</tr></thead>\n"
       "      <tbody>\n"
       (if (seq rows)
         (str (str/join "\n" rows) "\n")
         (str "        <tr><td colspan=\"" (count headers)
              "\" class=\"muted\">no rows produced by this run</td></tr>\n"))
       "      </tbody>\n"
       "    </table>\n"))

(defn- section [title lede body]
  (str "  <section class=\"card\">\n"
       "    <h2>" title "</h2>\n"
       (when lede (str "    <p class=\"muted\">" lede "</p>\n"))
       body
       "  </section>\n"))

(defn- yes-no [b] (if b "<span class=\"ok\">yes</span>" "<span class=\"muted\">no</span>"))

;; ----------------------------- sections -----------------------------

(defn- member-rows [db ledger]
  (for [m (store/all-members db)
        :let [last-fact (last (filter #(= (:id m) (:subject %)) ledger))]]
    (row (code (:id m)) (esc (:name m)) (esc (:employer m))
         (esc (kw* (:plan-type m)))
         (str "<span class=\"num\">" (num* (:accrued-benefit m)) "</span>")
         (str "<span class=\"num\">" (num* (:disbursed-to-date m)) "</span>")
         (if (:vested? m) "<span class=\"ok\">vested</span>"
             "<span class=\"critical\">not vested</span>")
         (esc (kw* (:status m)))
         (code (:jurisdiction m))
         (cond
           (nil? last-fact) "<span class=\"muted\">no ledger activity</span>"
           (= :committed (:t last-fact)) "<span class=\"ok\">committed</span>"
           (= :approval-rejected (:t last-fact)) "<span class=\"warn\">human declined</span>"
           (seq (:violations last-fact))
           (str "<span class=\"critical\">HARD hold · "
                (esc (kw* (:rule (first (:violations last-fact))))) "</span>")
           :else "<span class=\"warn\">phase-gate hold</span>"))))

(defn- coverage-rows [db]
  (let [in-play (sort (distinct (map :jurisdiction (store/all-members db))))]
    (for [iso3 in-play
          :let [sb (facts/spec-basis iso3)]]
      (row (code iso3)
           (if sb (esc (:name sb)) "<span class=\"critical\">not in catalog</span>")
           (if sb (esc (:owner-authority sb)) "<span class=\"muted\">—</span>")
           (if sb (esc (:legal-basis sb)) "<span class=\"muted\">—</span>")
           (if sb (str "<span class=\"num\">" (count (:required-evidence sb)) "</span>")
               "<span class=\"muted\">—</span>")
           (if sb
             (str "<a href=\"" (esc (:provenance sb)) "\">" (esc (:provenance sb)) "</a>")
             "<span class=\"critical\">no official source — the governor HARD-holds any proposal citing one</span>")))))

(defn- gate-rows [runs]
  (let [stakes (into {} (for [{:keys [op verdict]} runs
                              :when (some? (:high-stakes? verdict))]
                          [op (:high-stakes? verdict)]))
        ordered [:member/intake :jurisdiction/assess :proof-of-life/screen
                 :disbursement/file :disbursement/pay :payout/continue]]
    (for [o ordered
          :let [writes (filterv #(contains? (:writes (get phase/phases %)) o) (sort (keys phase/phases)))
                autos  (filterv #(contains? (:auto (get phase/phases %)) o) (sort (keys phase/phases)))]]
      (row (code o)
           (if (seq writes) (esc (str/join ", " (map #(str "phase " %) writes)))
               "<span class=\"muted\">none</span>")
           (if (seq autos)
             (str "<span class=\"ok\">" (esc (str/join ", " (map #(str "phase " %) autos))) "</span>")
             "<span class=\"warn\">never — human approval at every phase</span>")
           (case (get stakes o)
             true  "<span class=\"critical\">yes — actuation</span>"
             false "<span class=\"muted\">no</span>"
             "<span class=\"muted\">not exercised by this run</span>")))))

(defn- run-rows [runs]
  (for [{:keys [tid phase op subject disposition escalated? escalation-reason approval note]} runs]
    (row (code tid)
         (str "<span class=\"num\">" phase "</span>")
         (code op)
         (code subject)
         (case disposition
           :commit   "<span class=\"ok\">commit</span>"
           :hold     "<span class=\"critical\">hold</span>"
           :awaiting-approval "<span class=\"warn\">awaiting approval</span>"
           (str "<span class=\"muted\">" (esc (kw* disposition)) "</span>"))
         (cond
           (not escalated?) "<span class=\"muted\">not escalated</span>"
           (nil? escalation-reason) "<span class=\"warn\">escalated</span>"
           :else (str "<span class=\"warn\">" (esc (kw* escalation-reason)) "</span>"))
         (if approval
           (str (if (= :approved (:status approval))
                  "<span class=\"ok\">approved</span>" "<span class=\"critical\">rejected</span>")
                " by " (code (:by approval)))
           "<span class=\"muted\">—</span>")
         (if note (esc note) "<span class=\"muted\">—</span>"))))

(defn- hard-hold-rows [ledger]
  (apply concat
         (for [f (hard-holds ledger)]
           (for [v (:violations f)]
             (row (code (:op f)) (code (:subject f))
                  (str "<span class=\"critical\">" (esc (kw* (:rule v))) "</span>")
                  (esc (:detail v))
                  (str "<span class=\"num\">" (esc (:confidence f)) "</span>")
                  "<span class=\"critical\">never reaches a human</span>")))))

(defn- phase-hold-rows [ledger]
  (for [f (phase-gate-holds ledger)]
    (row (code (:op f)) (code (:subject f))
         (str "<span class=\"num\">" (esc (:phase f)) "</span>")
         (str "<span class=\"warn\">" (esc (kw* (:phase-reason f))) "</span>")
         (esc (str "writes enabled at this phase: "
                   (let [w (:writes (get phase/phases (:phase f)))]
                     (if (seq w) (str/join ", " (sort (map kw* w))) "none"))))
         "<span class=\"muted\">empty — the compliance governor found nothing wrong</span>")))

(defn- rejection-rows [ledger runs]
  (for [f (approval-rejections ledger)
        :let [r (last (filter #(and (= (:op f) (:op %)) (= (:subject f) (:subject %))
                                    (= :rejected (get-in % [:approval :status])))
                              runs))]]
    (row (code (:op f)) (code (:subject f))
         (if r (code (get-in r [:approval :by]))
             "<span class=\"muted\">approver not recoverable from this run</span>")
         (esc (str/join ", " (map (comp kw* :rule) (:violations f))))
         "<span class=\"warn\">reached a human, who declined — NOT a governor refusal</span>")))

(defn- disbursement-rows [db ledger]
  (for [id (disbursement-ids ledger)
        :let [d (store/disbursement db id)]]
    (if d
      (row (code id) (code (:member-id d)) (esc (kw* (:disbursement-type d)))
           (str "<span class=\"num\">" (num* (:requested-amount d)) "</span>")
           (if (= :paid (:status d))
             "<span class=\"ok\">paid</span>"
             (str "<span class=\"warn\">" (esc (kw* (:status d))) "</span>"))
           (if (:disbursement-number d) (code (:disbursement-number d))
               "<span class=\"muted\">—</span>"))
      (row (code id) "<span class=\"muted\">—</span>" "<span class=\"muted\">—</span>"
           "<span class=\"muted\">—</span>"
           "<span class=\"critical\">not on file</span>"
           "<span class=\"muted\">—</span>"))))

(defn- assessment-rows [db]
  (for [m (store/all-members db)
        :let [a (store/assessment-of db (:id m))]
        :when a]
    (row (code (:id m)) (code (:jurisdiction a))
         (str "<span class=\"num\">" (count (:checklist a)) "</span>")
         (esc (str/join " / " (:checklist a)))
         (if (:spec-basis a)
           (str "<a href=\"" (esc (:spec-basis a)) "\">" (esc (:spec-basis a)) "</a>")
           "<span class=\"critical\">none</span>"))))

(defn- proof-of-life-rows [db]
  (for [m (store/all-members db)
        :let [p (store/proof-of-life-of db (:id m))]
        :when p]
    (row (code (:id m)) (esc (:name m))
         (case (:verdict p)
           :clear  "<span class=\"ok\">clear</span>"
           :failed "<span class=\"critical\">failed</span>"
           (str "<span class=\"warn\">" (esc (kw* (:verdict p))) "</span>")))))

(defn- payment-rows [db]
  (for [r (store/payment-history db)]
    (row (code (get r "record_id")) (esc (get r "kind"))
         (code (get r "member_id")) (code (get r "disbursement_id"))
         (esc (get r "disbursement_type"))
         (str "<span class=\"num\">" (num* (get r "disbursed_amount")) "</span>")
         (code (get r "jurisdiction"))
         (yes-no (get r "immutable")))))

(defn- continuation-rows [db]
  (for [r (store/continuation-history db)]
    (row (code (get r "record_id")) (esc (get r "kind"))
         (code (get r "member_id")) (code (get r "jurisdiction"))
         (yes-no (get r "immutable")))))

(defn- attribution-rows [db runs]
  (for [{:keys [op subject effect approver-in-run approver-in-ledger where
                record-present? approver-key approver-value]}
        (attribution-audit db runs)]
    (row (code op) (code subject) (code effect)
         (code approver-in-run)
         (if approver-in-ledger (code approver-in-ledger)
             "<span class=\"critical\">absent from the audit fact too</span>")
         (code where)
         (cond
           (not record-present?)
           "<span class=\"warn\">record not uniquely readable back through the Store protocol</span>"
           approver-key
           (str "<span class=\"ok\">kept · " (code approver-key) " = " (code approver-value) "</span>")
           :else
           "<span class=\"critical\">DROPPED — the record carries no approver key</span>"))))

(defn- ledger-rows [ledger]
  (map-indexed
   (fn [i {:keys [t op subject disposition basis violations phase-reason confidence summary]}]
     (row (str "<span class=\"num\">" i "</span>")
          (code (kw* t))
          (code (kw* (or op :n-a)))
          (code subject)
          (esc (kw* (or disposition "")))
          (cond
            (seq violations) (esc (str/join ", " (map (comp kw* :rule) violations)))
            phase-reason (esc (kw* phase-reason))
            (seq basis) (esc (str/join ", " (map #(if (keyword? %) (name %) (str %)) basis)))
            :else "<span class=\"muted\">—</span>")
          (str "<span class=\"num\">" (if (some? confidence) (esc confidence) "—") "</span>")
          (if summary (esc summary) "<span class=\"muted\">—</span>")))
   ledger))

;; ----------------------------- document -----------------------------

(defn render
  "Renders the whole operator console from a completed `run-demo!`
  result. Reads only `db` (through the Store protocol) and `runs`."
  [{:keys [db runs]}]
  (let [ledger   (vec (store/ledger db))
        hard     (hard-holds ledger)
        rules    (->> hard (mapcat :violations) (map :rule) distinct sort vec)
        cov      (facts/coverage (sort (distinct (map :jurisdiction (store/all-members db)))))
        attrib   (attribution-audit db runs)
        kept     (count (filter :approver-key attrib))
        dropped  (- (count attrib) kept)]
    (str
     "<!doctype html>\n"
     "<html lang=\"en\"><head><meta charset=\"utf-8\">\n"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
     "<title>cloud-itonami-isic-6530 · pension funding — Operator Console</title>\n"
     "<style>\n" (jp-go-dds.skin/dds+skin) "\n</style>\n"
     "</head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Pension funding (ISIC 6530) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · disbursement payment &amp; payout continuation always human-approved</span>\n"
     "</header>\n"
     "<p class=\"subtitle\">Every table below is generated at build time by <code>pension.render-html</code> "
     "(<code>clojure -M:dev:render-html</code>) from ONE real run of the actor stack "
     "<code>pension.operation</code> &rarr; <code>pension.governor</code> &rarr; <code>pension.phase</code> "
     "&rarr; <code>pension.store</code>, driven through <code>langgraph.graph/run*</code>. "
     "No number, id, member name, violation reason or record id on this page was typed by hand; "
     "where a value cannot be read back out of the store, the page says so instead of inventing one. "
     "The page contains no timestamps, so two consecutive builds against the same seed are byte-identical.</p>\n"
     "<main>\n"

     (section "Run summary" nil
              (tbl ["Measure" "Value"]
                   [(row "operations driven through the actor"
                         (str "<span class=\"num\">" (count runs) "</span>"))
                    (row "audit-ledger facts written"
                         (str "<span class=\"num\">" (count ledger) "</span>"))
                    (row "HARD governor holds (carry a violation, un-overridable)"
                         (str "<span class=\"critical num\">" (count hard) "</span>"))
                    (row "distinct HARD rules exercised"
                         (str "<span class=\"num\">" (count rules) "</span> · "
                              (str/join ", " (map #(code (kw* %)) rules))))
                    (row "rollout phase-gate holds (empty violations — NOT a compliance refusal)"
                         (str "<span class=\"warn num\">" (count (phase-gate-holds ledger)) "</span>"))
                    (row "escalations declined by the named human"
                         (str "<span class=\"warn num\">" (count (approval-rejections ledger)) "</span>"))
                    (row "draft disbursement-payment records"
                         (str "<span class=\"num\">" (count (store/payment-history db)) "</span>"))
                    (row "draft payout-continuation records"
                         (str "<span class=\"num\">" (count (store/continuation-history db)) "</span>"))
                    (row "approved commits whose stored record kept the approver"
                         (str "<span class=\"num\">" kept " of " (count attrib) "</span>"
                              (when (pos? dropped)
                                (str " · <span class=\"critical\">" dropped
                                     " dropped (measured below, not assumed)</span>"))))]))

     (section "Members (SSoT)"
              "Read back through <code>pension.store/all-members</code> AFTER the run — <code>disbursed-to-date</code> already reflects the disbursement paid during this run."
              (tbl ["Member" "Name" "Employer" "Plan" "Accrued benefit" "Disbursed to date"
                    "Vesting" "Status" "Jurisdiction" "Last ledger fact"]
                   (member-rows db ledger)))

     (section "Jurisdiction spec-basis coverage (honest)"
              (str "<code>pension.facts/coverage</code> over the jurisdictions the seeded members actually live in: "
                   "<strong>" (:covered cov) " of " (:requested cov) "</strong> covered"
                   (when (seq (:missing-jurisdictions cov))
                     (str ", missing " (str/join ", " (map code (:missing-jurisdictions cov)))))
                   ". A jurisdiction absent from the catalog has NO spec-basis — the advisor must not invent one, and the governor HARD-holds if it tries.")
              (tbl ["ISO3" "Jurisdiction" "Owner authority" "Legal basis" "Required evidence items" "Official source"]
                   (coverage-rows db)))

     (section "Op gate — rollout phase table &times; actuation"
              "Derived from <code>pension.phase/phases</code> and from the <code>:high-stakes?</code> flag the governor actually returned in this run. <code>:disbursement/pay</code> and <code>:payout/continue</code> are absent from every phase's <code>:auto</code> set — a permanent structural fact, not a rollout milestone still to come."
              (tbl ["Op" "May write at" "May auto-commit at" "High-stakes actuation?"]
                   (gate-rows runs)))

     (section "Operations driven in this run"
              "One row per <code>langgraph.graph/run*</code> invocation, in execution order."
              (tbl ["Thread" "Phase" "Op" "Subject" "Disposition" "Escalation reason" "Human decision" "Why this case is here"]
                   (run-rows runs)))

     (section "HARD governor holds — compliance refusals"
              "Un-overridable. A human approver cannot approve past any of these; the run never reaches the approval node at all. One row per violation, so a proposal that trips two rules appears twice."
              (tbl ["Op" "Subject" "Rule" "Detail (as emitted by pension.governor)" "Advisor confidence" "Override"]
                   (hard-hold-rows ledger)))

     (section "Rollout phase-gate holds — NOT compliance refusals"
              "A different thing entirely: the compliance governor found nothing wrong, but the rollout phase does not enable that write yet. These facts share the <code>:governor-hold</code> tag and carry an <strong>empty</strong> <code>:violations</code> vector, which is why the build-time invariant in <code>-main</code> counts violations rather than holds."
              (tbl ["Op" "Subject" "Phase" "Reason" "Writes enabled at that phase" "Violations"]
                   (phase-hold-rows ledger)))

     (section "Escalations the human declined"
              "The third refusal kind. The governor cleared the proposal, the rollout gate allowed it, a named administrator looked at it and said no."
              (tbl ["Op" "Subject" "Declined by" "Recorded rule" "Kind"]
                   (rejection-rows ledger runs)))

     (section "Disbursement register"
              "Every disbursement id this run touched, in first-seen ledger order. <code>disb-999</code> is on the page because a payment was proposed against it and HARD-held — it is genuinely absent from the store."
              (tbl ["Disbursement" "Member" "Type" "Requested amount" "Status" "Disbursement number"]
                   (disbursement-rows db ledger)))

     (section "Committed jurisdiction assessments"
              "<code>pension.store/assessment-of</code> per member. Members with no row were never assessed — which is exactly why a payment against <code>disb-4</code> HARD-held on <code>:evidence-incomplete</code>."
              (tbl ["Member" "Jurisdiction" "Checklist items" "Required evidence" "Cited official source"]
                   (assessment-rows db)))

     (section "Committed proof-of-life verdicts"
              "<code>pension.store/proof-of-life-of</code> per member. A <em>failed</em> screening never commits — the governor HARD-holds the screening op on its own finding — so a failure appears in the hold table above, not here."
              (tbl ["Member" "Name" "Verdict"]
                   (proof-of-life-rows db)))

     (section "Draft disbursement-payment records"
              "Built by <code>pension.registry/register-disbursement-payment</code> — the record a fund would keep. Unsigned drafts: signature is the licensed fund's act, not this actor's."
              (tbl ["Record id" "Kind" "Member" "Disbursement" "Type" "Disbursed amount" "Jurisdiction" "Immutable"]
                   (payment-rows db)))

     (section "Draft payout-continuation records"
              "Built by <code>pension.registry/register-payout-continuation</code>. Continuation is recurring: each proof-of-life cycle is its own independent authorization, so there is deliberately no 'already continued' guard."
              (tbl ["Record id" "Kind" "Member" "Jurisdiction" "Immutable"]
                   (continuation-rows db)))

     (section "Approver attribution — measured, not assumed"
              (str "For each approved commit, the page looks the written record back up through the Store protocol and searches it for any key mentioning <code>approv</code>. "
                   "It does not hard-code a claim about this store: if <code>pension.store</code> is later changed to keep the approver on more effects, this table changes with it. "
                   "Measured in this run: <strong>" kept " kept, " dropped " dropped</strong> of " (count attrib) " approved commits. "
                   "Where the record dropped it, the approver is still recoverable from the append-only audit ledger's <code>:approval-granted</code> fact — the gap is labelled rather than hidden, "
                   "because silently omitting it would leave a reader unable to tell &quot;nobody approved this&quot; from &quot;the store did not keep who did&quot;. "
                   "<code>:disbursement/filed</code> is absent from this table by construction: <code>:disbursement/file</code> is auto-eligible at the only phase that enables it, "
                   "so no approval can ever attach to it and this run cannot measure it.")
              (tbl ["Op" "Subject" "Effect" "Approver (run)" "Approver (audit ledger)" "Read back from" "Approver in the stored record"]
                   (attribution-rows db runs)))

     (section "Audit ledger (append-only, this run)"
              "Every decision fact the run wrote, in append order. Holds are written by the <code>:hold</code> node with no SSoT mutation; commits are written by the <code>:commit</code> node, the only node that touches the store."
              (tbl ["#" "Fact" "Op" "Subject" "Disposition" "Basis / rules / phase reason" "Confidence" "Summary"]
                   (ledger-rows ledger)))

     "</main>\n"
     "<footer>\n"
     "  <p>cloud-itonami-isic-6530 · pension funding actor · generated by <code>pension.render-html</code> from a real "
     "<code>langgraph.graph/run*</code> execution of <code>pension.operation</code>. "
     "Certificates produced by <code>pension.registry</code> are UNSIGNED drafts — signature is the licensed pension fund's act, not this actor's.</p>\n"
     "</footer>\n"
     "</body></html>\n")))

;; ------------------------------- main -------------------------------

(defn -main
  "Regenerates `docs/samples/operator-console.html` from a real run.

  Build-time invariant, deliberately two-stage: the page must show the
  compliance governor actually refusing something. Counting
  `:governor-hold` facts alone is NOT enough -- a rollout phase-gating
  hold is written with the same tag but an EMPTY `:violations` vector,
  so a run in which the governor never objected to anything would
  still satisfy a naive count. We therefore require BOTH at least one
  hold AND at least one hold carrying a non-empty violation with a
  named rule. The page is not written when this fails."
  [& args]
  (let [out    (or (first args) "docs/samples/operator-console.html")
        result (run-demo!)
        ledger (vec (store/ledger (:db result)))
        holds  (filterv #(= :governor-hold (:t %)) ledger)
        hard   (hard-holds ledger)
        ruled  (filterv #(seq (remove nil? (map :rule (:violations %)))) hard)]
    (when (empty? holds)
      (throw (ex-info "render-html: the scenario produced NO governor-hold facts at all -- refusing to write a console that cannot show a refusal"
                      {:ledger-facts (count ledger)})))
    (when (empty? ruled)
      (throw (ex-info (str "render-html: " (count holds) " governor-hold fact(s), but none carries a named violation -- "
                           "these are rollout phase-gating holds, not compliance refusals. Refusing to write the console.")
                      {:holds (count holds)
                       :hard-holds (count hard)
                       :phase-gate-holds (count (phase-gate-holds ledger))})))
    (let [html (render result)]
      (io/make-parents out)
      (spit out html)
      (println "wrote" out
               (str "(" (count ledger) " ledger facts, "
                    (count hard) " HARD governor holds over "
                    (count (distinct (map :rule (mapcat :violations hard)))) " distinct rules, "
                    (count (phase-gate-holds ledger)) " phase-gate holds, "
                    (count (store/payment-history (:db result))) " disbursement-payment drafts, "
                    (count (store/continuation-history (:db result))) " payout-continuation drafts)")))))
