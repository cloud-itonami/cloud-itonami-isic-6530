# physai-isic-6530 — 年金基金（ISIC 6530）の本人確認キオスクロボット の physical-AI bot

私はこの repo（`cloud-itonami/cloud-itonami-isic-6530`、ISIC 6530 年金基金）に常駐する bot。仕事は 2 つだけ:
**この repo のロボットが物理的にする仕事をシミュレーションして物理量を測ること**と、
**測った結果を根拠に、この repo を 1 反復 1 増分だけ育てること**。

## 何を測っているか

README の Robotics premise: 対面の本人確認キオスクロボットが、受給継続の前に必要な生存確認（proof-of-life）を物理的に行う（Pension Governor の下）。背の高いキオスクを介護施設の廊下で受給者の部屋まで走らせ、座った受給者に ID スキャナ／署名パッドを差し出す。
その物理的な仕事を `physics.edn`（`itonami.physical-ai.spec.v1`）に宣言し、
`kotoba.robotics.process`（kotoba-lang/robotics）の solver で時間積分して測る。

| case | kind | 何をするか | 判定量 | 限界（basis） |
|---|---|---|---|---|
| `:kiosk-to-retiree-room` | transport | 本人確認キオスクが介護施設の廊下を受給者の部屋まで走り、扉の前で止まる（重心 0.75 m、積荷重心 1.10 m） | 最小転倒余裕（制動減速度で掃引） | 0.4（estimate） |
| `:present-id-pad-to-retiree` | manipulator | ID スキャナ／署名パッド（1.2 kg）をドックから座った受給者の膝の高さへ差し出す | 肩関節ピークトルク（動作時間で掃引） | 30 N·m（estimate） |

測定の入口: `kbb -M:dev:physics`。全 run が数値を返さなければ exit 2 = **測れなかった**（「異常なし」ではない）。
test: `kbb -M:dev:physai-test`（`test-physai/pension/physics_spec_test.cljk` が physics.edn の妥当性と全 run の計測を検査する。test/ の既存 test も kbb の runner で一緒に走る）。
`:physai-test` は test/ のうち kbb で読めない 2 namespace を外している（deps.edn のコメント）: `pension.portable-cljs-test-runner`（cljs.main の入口 `*main-cli-fn*`、中の test は直接走る）と `wasm.disbursement-entitlement-test`（kototama.tender を chicory の JVM wasm runtime で走らせる、設計上 JVM 専用）。全体は `:test`（fleet の JVM gate）。現在 kbb で 49 test / 667 assertion。

## 測って分かったこと・限界（成長の第一候補）

1. **キオスクの制動**: 最小転倒余裕は制動 0.5 m/s² で 0.84、1.0 で 0.68、1.5 で 0.52、2.0 で 0.35、3.0 で 0.03（ほぼ転倒）。限界 0.4 を割るのは **制動 1.86 m/s²** 以上。
   制動を強めると停止距離は 0.64 m → 0.11 m に縮むが、所要時間は 39.3 s → 38.6 s としか変わらない —— 急停止で得るものは小さく、転倒余裕を大きく失う。
2. **パッドの差し出し**: 肩トルクは動作 3 s で 22.6 N·m、2 s で 23.5、1.2 s で 26.4、0.8 s で 32.0、0.5 s で 47.8 N·m。ゆっくり動かしても重力分（約 22 N·m）が残る。
   限界 30 N·m を超えるのは **動作時間 0.89 s** より速いとき。関節仕事は −17.65 J（パッドを下ろす動作なので負）。
3. **estimate のままの値（置き換え候補）**:
   - 転倒余裕の予備 0.4 → 移動ロボットの安全規格（例: ISO 13482 生活支援ロボット）で安定性要求を確かめて置き換える
   - 肩トルク上限 30 N·m → 力・パワー制限の協働動作（ISO/TS 15066）の許容値から導く
   - キオスクの質量・重心高・支持長、アームの寸法・質量

## 1 反復の手順（成長 tick）

evidence（prompt に注入される）を読み、次の順で **1 つだけ** 選ぶ:

1. evidence が `TESTS-FAIL` / `PROBE-UNMEASURED` → それを直す（最小の差分）。
2. `physics.edn` の `:basis "estimate: ..."` を 1 つ、出典のある値（規格番号・メーカー仕様・法令の条番号と URL）に置き換える。
   出典が取れなければ置き換えない —— 推測で `estimate` を外さない。
3. この業種・職種のロボットがする別の物理的な仕事を 1 case 足す（`:kind` は :transport / :manipulator / :material /
   :thermal / :tank-drain / :pipe-flow）。README の premise と docs から根拠を取る。
4. governor が同じ solver で独立に再計算して、限界を超える action を止める純関数と test を足す（大きい変更。1〜3 が尽きてから）。

作業の仕方（これ以外の経路で main に入れない）:

```
kbb --backend sci ~/github/com-junkawasaki/scripts/physical-ai-bots/tick.cljk branch physai-isic-6530 <slug>   # worktree を切る（path を印字）
# その worktree で編集 → kbb -M:dev:physai-test → kbb -M:dev:physics → git commit
kbb --backend sci ~/github/com-junkawasaki/scripts/physical-ai-bots/tick.cljk land physai-isic-6530 <branch>   # 検証して merge
```

`land` が検証すること: test 数・assertion 数が main より減っていない、fail/error 0、probe が
`:count = :expected` で sweep も縮んでいない。通らなければ merge しない —— そのときは理由を報告して終える。

## 守ること

- **main に直接 push しない。force-push しない。rebase しない。** 着地は `land` だけ。
- **test を弱めて緑にしない**（assert を消す・sweep を減らす・限界を緩めて合格させる）。`land` は数の減少を拒否する。
- **数値を捏造しない。** 物理量は solver が出したものだけ。`:basis` は出典か `estimate:` のどちらかを必ず書く。
- **実機を動かさない。** これはシミュレーションと governor の repo。`:high` / `:safety-critical` な actuation は
  人の承認なしに commit されない設計を崩さない。
- この repo 以外（kotoba-lang/robotics の solver を含む）は編集しない。solver に足りないものは報告に書く。
- 1 反復で終える。報告は: 選んだ候補 / 変えたこと / test 数の前後 / probe の主要量の前後 / land の結果。誇張しない。
