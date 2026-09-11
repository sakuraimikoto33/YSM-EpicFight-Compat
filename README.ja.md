# YSM Epic Fight Compat

[English](README.md)

公式 Yes Steve Model で選択したプレイヤーモデルを、Epic Fight の戦闘アニメーションで表示する Mod です。Epic Fight の戦闘用レンダラーを使用しない場面では、公式 YSM が通常のプレイヤー描画を担当します。

## 必要環境

- Minecraft 1.21.1
- NeoForge 21.1.235 以降
- [Yes Steve Model](https://modrinth.com/mod/yes-steve-model) 2.6.0 以降（NeoForge 1.21.1版）
- [Epic Fight](https://modrinth.com/mod/epic-fight) 21.17.3.1以上、21.18未満（NeoForge 1.21.1版）
- [YSM Mapping API](https://github.com/sakuraimikoto33/YSM-Mapping-API) 0.1.8 以降

1.21.1移植版は、戦闘描画、IrisのPBRとリソース再読込、Configured、ParCool、Epic ParCool、SWEM、統合サーバーを用いた2クライアント間の同期を実機で確認しています。専用サーバーの起動は未検証です。EpicFight：TouhouLittleMaidはこの環境に未対応です。

YSM 2.6.5とIris 1.8.12では、通常のYSM描画でリソース再読込後にPBRが崩れる問題が残っています。当ModとEpic Fightを含まない構成でも再現します。当Modは戦闘描画のPBRを復旧し、通常描画は引き続き公式YSMが担当します。

## 機能

- Epic Fightの一人称・三人称戦闘描画でYes Steve Model（YSM）のプレイヤーモデルを使用。
- フォルダモデルと、暗号化されたものを含む対応形式の `.ysm` パッケージを読み込み。
- モデル定義の移動・揺れ・持ち替え・Controllerアニメーション、音声、パーティクルに対応。
- 独自の武器、弓動作、投射物、釣り針、乗り物を表示。
- マルチプレイでモデル・テクスチャ・外観上のアニメーション状態を同期。
- ParCool!、Epic ParCool、SWEM、Iris Shadersとの任意連携。

## 任意の連携Mod

以下のModはプレイヤーモデルの基本連携には不要です。利用する連携Mod自体の依存関係も満たしてください。

| Mod | 連携内容 |
| --- | --- |
| [Configured](https://www.curseforge.com/minecraft/mc-mods/configured) | ゲーム内設定画面を提供します。導入する場合は2.6.3以降が必要です。 |
| [ParCool! ~ Minecraft Parkour ~](https://www.curseforge.com/minecraft/mc-mods/parcool) | パルクール中に、YSMモデルに用意された対応アニメーションを使用します。 |
| [\[Official\] Epic ParCool](https://www.curseforge.com/minecraft/mc-mods/official-epic-fight-x-parcool) | Chain movement・Wall movementはEpic ParCoolのアニメーションを維持し、その他の対応動作ではYSMアニメーションを使用できます。 |
| [Star Worm Equestrian (Upgrading Horses)](https://www.curseforge.com/minecraft/mc-mods/swem) (SWEM) | SWEMの馬に乗ったときに、YSMの騎乗アニメーションを使用します。馬の見た目や動きは変更しません。 |
| [Iris Shaders](https://www.irisshaders.dev/) | 対応するシェーダーで、YSMモデルに用意された凹凸・光沢の表現を利用できるようにします。 |

ParCool・SWEM連携はプレイヤーの見た目だけを変更し、YSMモデルに対応アニメーションが必要です。移動や戦闘の仕組みは変わらず、攻撃・防御・被弾ではEpic Fightのアニメーションを使用します。

## 設定

設定ファイルは `config/ysm_epicfight_compat/` に保存されます。

| ファイル | 内容 |
| --- | --- |
| `ysm_epicfight_compat-client.toml` | クライアントの表示・アニメーション・モデル・除外・クライアントキャッシュ設定。以下の `[common.*]` テーブルもこのファイル内です。 |
| `ysm_epicfight_compat-common.toml` | `[server.cache]` にある統合・専用サーバー共通のキャッシュ設定。ワールド別の `serverconfig` ファイルではありません。 |

Configuredの画面、またはTOMLファイルで編集できます。保存・再読み込みした設定はゲームを再起動せずに反映されます。モデル使用者の設定や除外リストは本人のクライアントに保持し、マルチプレイではルールの一覧ではなく、判定済みの表示・姿勢状態を同期します。

### クライアント表示・評価頻度 — `[client]`

| キー | 初期値 | 効果・指定範囲 |
| --- | --- | --- |
| `suppressBattleModeOverlay` | `true` | Epic Fightの戦闘モード中に、公式YSMの左上プレイヤー表示を隠します。 |
| `animationEvaluationRateLimitHz` | `60` | モデルのアニメーション・スクリプト・Controller評価の目標となる毎秒上限回数。整数の `30～240`、または無制限を表す `0`。状態変更時は予定より早く評価する場合があります。 |

評価頻度の設定は描画FPSやゲームtickを制限せず、アニメーションの再生速度も変えません。低い値ほどモデルの更新が粗くなり、スクリプト・Controllerの出力頻度も低くなる場合があります。無制限ではこの設定による制限を適用しませんが、対象となるリモートモデルの距離別更新制御は適用されます。

Configuredのスライダーは **30 Hzから240 Hz、その右端が無制限** です。無制限は `0` として保存し、設定をリセットすると `60` になります。

### アニメーション — `[common.animations]`

| キー | 初期値 | 効果 |
| --- | --- | --- |
| `useYsmHeldItemSwitchAnimations` | `true` | Epic Fightが描画する通常アイテムに、使用可能なYSM持ち替え姿勢を適用。モデル独自の置換アイテムは手持ち品モデル設定に従います。 |
| `useYsmMovementAnimations` | `true` | 使用可能なYSM全身移動アニメーションを使用。Epic Fightの戦闘アクションを優先します。 |
| `useNaturalLadderAnimations` | `true` | YSMが担当する専用はしごアニメーションに腕動作がある場合、両腕を使用。通常アイテムは収納し、YSM置換アイテムは非表示にします。移動設定が有効かつ除外対象外である必要があり、プレイヤーに適用されます。 |
| `useYsmParCoolAnimations` | `true` | 対応するYSMのParCool動作を使用。通常移動の設定から独立しています。 |
| `useYsmSwemAnimations` | `true` | 対応するYSMのSWEM騎乗動作を使用。通常移動・乗り物モデル設定から独立しています。 |

ParCool・SWEMの設定と除外エディターは、対応Modの導入時に利用できます。未導入時も保存済みの値は保持し、未作成の任意設定項目は生成しません。

### モデル置換 — `[common.models]`

| キー | 初期値 | 効果 |
| --- | --- | --- |
| `useYsmHeldItemModels` | `true` | モデル定義の武器・道具と、その置換アニメーションを使用。置換モデルがないアイテムはEpic Fightが描画します。 |
| `useYsmProjectileModels` | `true` | 対応するYSM手持ち品置換が制御していない場合に、モデル定義の投射物を使用します。 |
| `useYsmVehicleModels` | `true` | モデル定義の乗り物と、対応する騎乗姿勢を使用。無効にすると、その騎乗姿勢もEpic Fightに委ねます。 |

釣り針は釣り竿の手持ち品設定に従います。弓・トライデントの投射物は、対応する手持ち品置換モデルがある場合はその設定に従い、ない場合は投射物設定を使用します。クロスボウの投射物は投射物設定に従います。

### モデル別の除外設定

7種類すべてのテーブルの初期値は空です。**選択したYSMモデルのID** ごとに、対応する有効な機能を個別に無効化する値を並べます。メイン設定が無効な機能を、除外設定によって有効化することはありません。

| クライアントファイル内のテーブル | モデルごとに指定する値 |
| --- | --- |
| `common.models.exclusions.heldItemModelExclusions` | アイテムID、または `#アイテムタグ`。 |
| `common.models.exclusions.projectileModelExclusions` | エンティティ種別ID、または `#エンティティ種別タグ`。 |
| `common.models.exclusions.vehicleModelExclusions` | エンティティ種別ID、または `#エンティティ種別タグ`。 |
| `common.animations.exclusions.heldItemSwitchAnimationExclusions` | アイテムID、または `#アイテムタグ`。`minecraft:air` で空の手への持ち替えを指定。 |
| `common.animations.exclusions.movementAnimationExclusions` | 下記の移動状態名。 |
| `common.animations.exclusions.parcoolAnimationExclusions` | 下記のParCoolの短い動作名。 |
| `common.animations.exclusions.swemAnimationExclusions` | 下記のSWEMの短い騎乗アニメーション名。 |

モデルIDは引用符で囲み、スラッシュやドットもキーの一部として指定します。前後の空白と大文字・小文字を正規化して照合し、モデルIDのワイルドカードは使用できません。各テーブルは最大256モデル、アイテム・エンティティ系は1モデルあたり最大256指定で、モデルIDとこれらの指定文字列はそれぞれ最大256文字です。

設定例（`example/model` を使用中のモデルIDに置き換えてください）:

```toml
[common.models.exclusions.heldItemModelExclusions]
"example/model" = ["minecraft:diamond_sword", "#c:tools/bow"]

[common.models.exclusions.projectileModelExclusions]
"example/model" = ["minecraft:arrow"]

[common.models.exclusions.vehicleModelExclusions]
"example/model" = ["minecraft:boat"]

[common.animations.exclusions.heldItemSwitchAnimationExclusions]
"example/model" = ["minecraft:air"]

[common.animations.exclusions.movementAnimationExclusions]
"example/model" = ["run", "ladder_up"]

[common.animations.exclusions.parcoolAnimationExclusions]
"example/model" = ["fast_running", "roll_front"]

[common.animations.exclusions.swemAnimationExclusions]
"example/model" = ["gallop", "jump_lv2"]
```

移動状態の指定値:

```text
walk, run, sneak_idle, sneak_move, jump, creative_flight, elytra_flight,
swim, water_idle, crawl_idle, crawl_move, ladder_idle, ladder_up, ladder_down
```

ParCoolの指定値:

```text
backward_wall_jump, cat_leap, climb_up, cling_to_cliff,
cling_to_cliff_left, cling_to_cliff_right, dive_animation_host, dive_into_water,
dodge_front, dodge_back, dodge_left, dodge_right, fast_running, fast_swim,
flipping_front, flipping_back, horizontal_wall_run_right, horizontal_wall_run_left,
vertical_wall_run, jump_from_bar, hang, hang_vertical, kong_vault,
speed_vault_left, speed_vault_right, roll_front, roll_back, roll_left, roll_right,
sliding, tap, wall_jump_left, wall_jump_right, wall_slide_left, wall_slide_right,
jump_charging, charge_jump, ride_zipline
```

SWEMの指定値:

```text
idle, walk, trot, canter, canter_ext, gallop,
jump_lv1, jump_lv2, jump_lv3, jump_lv4, jump_lv5
```

移動・連携アニメーションのリストには、各一覧の名前だけを使用できます。タグやワイルドカードは使えません。ParCool・SWEMには `parcool:`・`swem:` の接頭辞を付けず、別の種別に属する動作名も指定できません。

Configuredでは保存済みモデルの項目に加え、現在選択中のモデルを編集行として表示します。空の行は保存しません。追加したいモデルをYSMで選択すると、各エディターで編集できます。

### クライアントキャッシュ — `[client.cache]`

| キー | 初期値 | 効果・指定範囲 |
| --- | --- | --- |
| `clientModelMemoryCacheTargetCount` | `64` | メモリへ保持する変換モデル数の目標。`8～512`。選択中・処理待ちのモデルは保護するため、厳密な上限ではありません。 |
| `clientModelDiskCacheMiB` | `64` | 解析済みローカルモデルのディスク容量。`0～4096` MiB。`0` でこのディスクキャッシュを無効化・消去します。 |
| `remoteModelDiskCacheMiB` | `64` | サーバーから受け取ったモデルのディスク容量。`0～4096` MiB。`0` でこのディスクキャッシュを無効化・消去します。 |

### サーバーキャッシュ — `[server.cache]`

以下のキーは `ysm_epicfight_compat-common.toml` に属します。

| キー | 初期値 | 効果・指定範囲 |
| --- | --- | --- |
| `serverModelDiskCacheEnabled` | `true` | 生成済みモデル転送データをディスクへ保存。`false` ではファイルを削除せずディスクキャッシュを使用停止し、制限付きメモリ転送キャッシュは維持します。 |
| `serverModelDiskCacheMiB` | `256` | 生成済み転送データのディスク容量。`0～4096` MiB。`0` で保存を無効にし、`serverModelDiskCacheEnabled` が `true` の場合はキャッシュ整理時に既存データを消去します。 |

ディスクキャッシュは `config/ysm_epicfight_compat/cache/client`・`remote`・`server` に分けて保存し、容量も個別に制限します。消去・整理はキャッシュへのアクセスやメンテナンス時に行うため、設定の保存直後とは限りません。

### 自動管理される状態 — `[client]`

`epicFightCompatibilityWarningShown` の初期値は `false` で、公式YSMのEpic Fight互換性警告を表示済みかどうかを記録します。モデルやアニメーションの使用設定ではなく、通常は手動編集する必要はありません。

## モデル・装備に関する注意

各機能は選択モデルのジオメトリとアニメーションに依存します。骨格構造から識別できる非人型の形態ではモデル定義の動作を維持しますが、非人型用の新しい戦闘アニメーションを生成する機能ではありません。

変換済みプレイヤーモデルでは防具と頭装備を非表示にします。エリトラの表示には、使用可能な `ElytraLocator` が一つだけ必要です。通常の手持ち品は対応するモデル定義の装着位置へ追従し、その表示規則によって非表示になる場合があります。変換モデルを準備できない場合は、Epic Fightのデフォルトメッシュと装備描画へフォールバックします。

## ビルド

Java 21とGitを用意し、`mc/1.21.1` ブランチでビルドしてください。

```powershell
./gradlew.bat build
```

配布用jarは `build/libs/ysm-epicfight-compat-<mc-version>-<mod-version>-all.jar` に生成されます。

## ドキュメント

- [実装詳細](docs/implementation.ja.md)

## クレジット

- [Yes Steve Model](https://modrinth.com/mod/yes-steve-model) — YesSteveModel team.
- [Epic Fight](https://www.curseforge.com/minecraft/mc-mods/epic-fight-mod) — Antikythera Studios.

## ライセンス

このプロジェクトには [MIT License](LICENSE) が適用されます。
