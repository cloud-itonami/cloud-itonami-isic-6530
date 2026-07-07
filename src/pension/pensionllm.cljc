(ns pension.pensionllm
  "Pension-LLM client -- the *contained intelligence node* for the
  pension-funding actor.

  It normalizes member intake, drafts a per-jurisdiction benefit-claim/
  withholding-document checklist, normalizes disbursement filing, drafts
  the disbursement-payment action, screens members for a proof-of-life
  signal, and drafts the payout-continuation action. CRITICAL: it is a
  smart-but-untrusted advisor. It returns a *proposal* (with a rationale
  + the fields it cited), never a committed record or a real
  disbursement payment/payout continuation. Every output is censored
  downstream by `pension.governor` before anything touches the SSoT,
  and `:disbursement/pay`/`:payout/continue` proposals NEVER auto-commit
  at any phase -- see README `Actuation`.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the spec-basis gate
     :cites      [kw|str ..]    ; facts/sources the LLM used -- SCANNED too
     :effect     kw             ; how a commit would mutate the SSoT
     :stake      kw|nil         ; :actuation/pay-disbursement | :actuation/continue-payout | nil
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [pension.facts :as facts]
            [pension.registry :as registry]
            [pension.store :as store]
            [langchain.model :as model]))

(defn- normalize-intake
  "Directory upsert -- the LLM only normalizes/validates the patch; it
  does not invent the member, plan type, accrued benefit or
  jurisdiction. High confidence, low stakes."
  [_db {:keys [patch]}]
  {:summary    (str "会員記録更新: " (pr-str (keys patch)))
   :rationale  "入力 patch の正規化のみ。新規事実の生成なし。"
   :cites      (vec (keys patch))
   :effect     :member/upsert
   :value      patch
   :stake      nil
   :confidence 0.97})

(defn- assess-jurisdiction
  "Per-jurisdiction benefit-claim/withholding checklist draft. `:no-
  spec?` injects the failure mode we must defend against: proposing a
  checklist for a jurisdiction with NO official spec-basis in `pension.
  facts` -- the Pension Governor must reject this (never invent a
  jurisdiction's law)."
  [db {:keys [subject no-spec?]}]
  (let [m (store/member db subject)
        iso3 (if no-spec? "ATL" (:jurisdiction m))
        sb (facts/spec-basis iso3)]
    (if (nil? sb)
      {:summary    (str iso3 " の公式spec-basisが見つかりません")
       :rationale  "pension.facts に未登録の法域。要件を推測で作らない。"
       :cites      []
       :effect     :assessment/set
       :value      {:jurisdiction iso3 :checklist [] :spec-basis nil}
       :stake      nil
       :confidence 0.9}
      {:summary    (str iso3 " (" (:owner-authority sb) ") 向け必要書類 "
                        (count (:required-evidence sb)) " 件を提案")
       :rationale  (str "公式ソース: " (:provenance sb) " / 法的根拠: " (:legal-basis sb))
       :cites      [(:legal-basis sb) (:provenance sb)]
       :effect     :assessment/set
       :value      {:jurisdiction iso3
                    :checklist (:required-evidence sb)
                    :spec-basis (:provenance sb)
                    :legal-basis (:legal-basis sb)}
       :stake      nil
       :confidence 0.9})))

(defn- propose-disbursement-filing
  "Directory upsert for a new disbursement request -- the LLM only
  normalizes/validates the filed disbursement's fields (member-id,
  disbursement-type, requested-amount); it does not invent them. High
  confidence, low stakes -- filing itself moves no capital, unlike
  paying it."
  [_db {:keys [subject member-id disbursement-type requested-amount]}]
  {:summary    (str subject " (member " member-id ") の給付金請求を受付")
   :rationale  "入力された給付金請求事実の正規化のみ。新規事実の生成なし。"
   :cites      [:member-id :requested-amount]
   :effect     :disbursement/filed
   :value      {:id subject :member-id member-id :disbursement-type disbursement-type
               :requested-amount requested-amount :status :filed}
   :stake      nil
   :confidence 0.95})

(defn- propose-disbursement-payment
  "Draft the actual disbursement-PAYMENT action -- paying out a real
  benefit disbursement to a member. ALWAYS `:stake :actuation/pay-
  disbursement` -- this is a REAL-WORLD act (real money leaves the
  fund), never a draft the actor may auto-run. See README `Actuation`:
  no phase ever adds this op to a phase's `:auto` set (`pension.
  phase`); the governor also always escalates on `:actuation/pay-
  disbursement`. Two independent layers agree, deliberately."
  [db {:keys [subject]}]
  (let [d (store/disbursement db subject)
        m (when d (store/member db (:member-id d)))
        max-entitlement (when (and d m) (registry/compute-max-disbursement m (:disbursement-type d)))
        within-entitlement? (and d max-entitlement (<= (double (:requested-amount d)) max-entitlement))]
    {:summary    (str subject " 向け給付金支払い提案"
                      (when d (str " (requested=" (:requested-amount d) ")")))
     :rationale  (if m
                   (str "member " (:id m) " max-entitlement=" max-entitlement)
                   "disbursementまたはmemberが見つかりません")
     :cites      (if d [(:member-id d)] [])
     :effect     :disbursement/mark-paid
     :value      {:disbursement-id subject}
     :stake      :actuation/pay-disbursement
     :confidence (if within-entitlement? 0.9 0.3)}))

(defn- screen-proof-of-life
  "Proof-of-life screening draft. `:proof-of-life-hit?` on the member
  record injects the failure mode: the Pension Governor must HOLD,
  un-overridably, on any failed proof-of-life check."
  [db {:keys [subject]}]
  (let [m (store/member db subject)]
    (cond
      (nil? m)
      {:summary "対象memberが見つかりません" :rationale "no member record"
       :cites [] :effect :proof-of-life/set :value {:member-id subject :verdict :unknown}
       :stake nil :confidence 0.0}

      (:proof-of-life-hit? m)
      {:summary    (str (:name m) ": 生存確認に失敗")
       :rationale  "スクリーニングが生存未確認を検出。人手確認とホールドが必須。"
       :cites      [:proof-of-life-check]
       :effect     :proof-of-life/set
       :value      {:member-id subject :verdict :failed}
       :stake      nil
       :confidence 0.95}

      :else
      {:summary    (str (:name m) ": 生存確認に成功")
       :rationale  "生存確認チェック合格。"
       :cites      [:proof-of-life-check]
       :effect     :proof-of-life/set
       :value      {:member-id subject :verdict :clear}
       :stake      nil
       :confidence 0.9})))

(defn- propose-payout-continuation
  "Draft the actual payout-CONTINUATION action -- authorizing a
  recurring benefit payout stream to continue past a proof-of-life
  check. ALWAYS `:stake :actuation/continue-payout` -- this is a
  REAL-WORLD act (real periodic payments keep flowing), never a draft
  the actor may auto-run. See README `Actuation`: no phase ever adds
  this op to a phase's `:auto` set (`pension.phase`); the governor also
  always escalates on `:actuation/continue-payout`. Two independent
  layers agree, deliberately."
  [db {:keys [subject]}]
  (let [m (store/member db subject)
        pol (store/proof-of-life-of db subject)
        clear? (= :clear (:verdict pol))]
    {:summary    (str subject " の年金継続支払い提案"
                      (when m (str " (" (:jurisdiction m) ")")))
     :rationale  (if pol
                   (str "proof-of-life verdict: " (:verdict pol))
                   "proof-of-life未実施")
     :cites      (if pol [subject] [])
     :effect     :payout/mark-continued
     :value      {:member-id subject}
     :stake      :actuation/continue-payout
     :confidence (if clear? 0.9 0.3)}))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :subject id ...op-specific...}"
  [db {:keys [op] :as request}]
  (case op
    :member/intake         (normalize-intake db request)
    :jurisdiction/assess    (assess-jurisdiction db request)
    :disbursement/file      (propose-disbursement-filing db request)
    :disbursement/pay       (propose-disbursement-payment db request)
    :proof-of-life/screen   (screen-proof-of-life db request)
    :payout/continue        (propose-payout-continuation db request)
    {:summary "未対応の操作" :rationale (str op) :cites []
     :effect :noop :stake nil :confidence 0.0}))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたは年金基金の給付金支払い・継続支払いエージェントの助言者です。"
       "与えられた事実のみに基づき、提案を1つだけEDNマップで返します。説明や前置きは"
       "一切書かず、EDNだけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) "
       ":effect(:member/upsert|:assessment/set|:disbursement/filed|"
       ":disbursement/mark-paid|:proof-of-life/set|:payout/mark-continued) "
       ":stake(:actuation/pay-disbursement か :actuation/continue-payout か nil) :confidence(0..1)。\n"
       "重要: 登録されていない法域の要件を絶対に創作してはいけません。"
       "spec-basisが無い場合は :cites を空にし confidence を上げないこと。"))

(defn- facts-for [st {:keys [op subject]}]
  (case op
    :jurisdiction/assess  {:member (store/member st subject)}
    :disbursement/pay     {:disbursement (store/disbursement st subject)}
    :proof-of-life/screen {:member (store/member st subject)}
    :payout/continue      {:member (store/member st subject)
                           :proof-of-life (store/proof-of-life-of st subject)}
    {:member (store/member st subject)}))

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure
  yields a safe low-confidence noop so the Pension Governor escalates/
  holds -- an LLM hiccup can never auto-pay a disbursement or
  auto-continue a payout."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :effect :noop :stake nil :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference)."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record -- persisted to the :audit channel."
  [request proposal]
  {:t          :pensionllm-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
