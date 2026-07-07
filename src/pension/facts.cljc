(ns pension.facts
  "Per-jurisdiction pension-disbursement regulatory catalog -- the G2-
  style spec-basis table the Pension Governor checks every jurisdiction/
  assess proposal against ('did the advisor cite an OFFICIAL public
  source for this jurisdiction's benefit-disbursement/withholding
  requirements, or did it invent one?').

  Coverage is reported HONESTLY (see `coverage`), the same discipline
  every sibling insurance-adjacent actor's `facts` namespace uses: a
  jurisdiction not in this table has NO spec-basis, full stop -- the
  advisor must not fabricate one, and the governor holds if it tries.

  Seed values are drawn from each jurisdiction's official pension
  regulator (see `:provenance`); they are a STARTING catalog, not a
  from-scratch survey of all ~194 jurisdictions. Extending coverage is
  additive: add one map to `catalog`, cite a real source, done -- never
  invent a jurisdiction's requirements to make coverage look bigger.

  Unlike the insurance-adjacent siblings' catalogs (which use a
  `USA-NY` exemplar entry because insurance regulation in the US is
  per-state), private-sector pension benefit disbursement in the US is
  federally regulated under ERISA -- so this catalog's US entry is
  `USA`, a genuine national authority, not a state exemplar.")

(def catalog
  "iso3 -> requirement map. `:required-evidence` mirrors the generic
  benefit-claim/withholding/proof-of-life evidence set submitted in
  some form; `:legal-basis` / `:owner-authority` / `:provenance` are
  the G2 citation the governor requires before any :jurisdiction/assess
  proposal can commit."
  {"JPN" {:name "Japan"
          :owner-authority "厚生労働省 (Ministry of Health, Labour and Welfare)"
          :legal-basis "確定給付企業年金法・確定拠出年金法 (Defined Benefit/Defined Contribution Corporate Pension Acts)"
          :national-spec "厚生労働省 年金給付関係事務処理要領"
          :provenance "https://www.mhlw.go.jp/"
          :required-evidence ["年金請求書 (benefit claim form)"
                              "生存確認書 (proof-of-life certificate)"
                              "源泉徴収に関する申告書 (tax withholding election)"
                              "受給権者本人確認書類 (beneficiary identity verification)"]}
   "USA" {:name "United States"
          :owner-authority "U.S. Department of Labor -- Employee Benefits Security Administration (EBSA)"
          :legal-basis "Employee Retirement Income Security Act of 1974 (ERISA)"
          :national-spec "ERISA §205 (spousal consent) + IRS minimum-distribution rules"
          :provenance "https://www.dol.gov/agencies/ebsa"
          :required-evidence ["Benefit claim form"
                              "Spousal consent (ERISA §205, where applicable)"
                              "IRS tax withholding election (Form W-4P)"
                              "Proof-of-life certification"]}
   "GBR" {:name "United Kingdom"
          :owner-authority "The Pensions Regulator (TPR)"
          :legal-basis "Pensions Act 2004 / Pension Schemes Act 1993"
          :national-spec "TPR Code of Practice -- benefit payment and scheme administration"
          :provenance "https://www.thepensionsregulator.gov.uk/"
          :required-evidence ["Benefit claim form"
                              "Proof-of-life certification"
                              "HMRC tax code / withholding election"
                              "Nomination of beneficiary form"]}
   "DEU" {:name "Germany"
          :owner-authority "Bundesanstalt für Finanzdienstleistungsaufsicht (BaFin)"
          :legal-basis "Betriebsrentengesetz (BetrAVG)"
          :national-spec "BaFin Rundschreiben zur betrieblichen Altersversorgung"
          :provenance "https://www.bafin.de/"
          :required-evidence ["Rentenantrag (benefit claim form)"
                              "Lebensbescheinigung (proof-of-life certificate)"
                              "Steuerliche Freistellungsbescheinigung (tax withholding election)"
                              "Begünstigtenerklärung (beneficiary declaration)"]}})

(defn spec-basis
  "The jurisdiction's requirement map, or nil -- nil means NO spec-basis,
  and the governor must hold any proposal that tries to pay a
  disbursement on it."
  [iso3]
  (get catalog iso3))

(defn coverage
  "Honest coverage report: how many of the requested jurisdictions actually
  have a spec-basis entry. Never report a missing jurisdiction as covered."
  ([] (coverage (keys catalog)))
  ([iso3s]
   (let [have (filter catalog iso3s)
         missing (remove catalog iso3s)]
     {:requested (count iso3s)
      :covered (count have)
      :covered-jurisdictions (vec (sort have))
      :missing-jurisdictions (vec (sort missing))
      :note (str "cloud-itonami-isic-6530 R0: " (count catalog)
                 " jurisdictions seeded with an official spec-basis. "
                 "This is a starting catalog, not a survey of all ~194 "
                 "jurisdictions -- extend `pension.facts/catalog`, "
                 "never fabricate a jurisdiction's requirements.")})))

(defn required-evidence-satisfied?
  "Does `submitted` (a set/coll of evidence keywords or strings) satisfy
  every evidence item listed for `iso3`? Missing spec-basis -> never
  satisfied."
  [iso3 submitted]
  (when-let [{:keys [required-evidence]} (spec-basis iso3)]
    (let [need (count required-evidence)
          have (count (filter (set submitted) required-evidence))]
      (= need have))))

(defn evidence-checklist [iso3]
  (:required-evidence (spec-basis iso3) []))
