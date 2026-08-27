# YSM Epic Fight Compat

[English](README.md)

YSM Epic Fight Compat は、公式 Yes Steve Model で選択したプレイヤーモデルを Epic Fight の戦闘アニメーションで描画する Forge Mod です。Epic Fight が戦闘用レンダラーを使用していない場面では、引き続き公式 YSM が通常のプレイヤー描画を担当します。任意アダプターにより、EpicFight_TouhouLittleMaid が戦闘描画を担当している対応メイドにも同じ変換モデルを適用できます。

## 必要環境

- Minecraft 1.20.1
- Forge 47.4.20 以降
- Yes Steve Model 2.6.0 以降（Forge 1.20.1版）
- Epic Fight 20.14.17 以降（Forge 1.20.1版）
- [YSM Mapping API](https://github.com/sakuraimikoto33/YSM-Mapping-API) 0.1.5 以降

## 機能

- YSMのフォルダモデルと、暗号化されたものを含む `.ysm` パッケージをEpic Fightの戦闘用メッシュへ変換します。
- シングルプレイとマルチプレイで、各プレイヤーが選択したモデルとテクスチャを使用します。
- 解析済みローカルモデル、検証済みリモートモデル、生成済みサーバー転送データを個別の容量制限付きディスクキャッシュへ保存し、モデルJSONや単独のテクスチャ画像は生成しません。
- プレイヤーのEpic Fight三人称・一人称戦闘描画に対応します。
- 公式YSMモデルを選択したメイドについて、EpicFight_TouhouLittleMaidの三人称戦闘メッシュを任意で置き換えます。変換モデルを準備できない間や使用可能なYSM選択がない場合は、元のメイドメッシュを維持します。
- 歩行、走行、スニーク、ジャンプ、クリエイティブ・エリトラ飛行、水泳、匍匐、はしご移動では、モデル定義のYSM全身移動アニメーションを使用します。この設定は初期状態で有効で、モデル別に移動状態を除外できます。Epic Fightのアクションが始まると、設定された移動姿勢の担当を即座にEpic Fightへ戻します。
- YSMの補助ボーン、自動・条件・騎乗・ルーレット・持ち替え・Animation Controllerアニメーションを、それぞれに適した姿勢合成経路で適用します。
- 選択モデルが手持ち品に対応する独自のYSM武器・ツールを実際に定義している場合はそのモデルを使用し、定義がない場合はEpic Fightの手持ち品描画を維持します。
- モデルに対応するアニメーションがある場合、アイテム変更後にYSM定義の全身hold遷移を再生します。モデル独自の置換品は手持ち品モデル設定に従い、Epic Fightが描画する通常アイテムの持ち替えアニメーションは独立した設定に従います。
- 独自弓を検出した場合はYSM定義の引き絞り・リリース全身ポーズを適用し、独自武器が音声を担当する攻撃ではEpic FightとYSMの振り音が二重に再生されることを防ぎます。
- 公式YSMで使用されるMolang数学関数、読み取り専用Query、補助物理関数、モデル変数の対応部分を評価します。`v.*`・`variable.*`、`v.roaming.*`・`variable.roaming.*` の各略記も同様に扱います。
- Animation Controllerの状態変数と `remap_curve`、モデル内サウンド出力、Molangパーティクル補助関数、Bedrockの宣言型 `particle_effects` に対応します。
- プレイヤーのモデル選択、モデル変数、解決済みの移動姿勢担当、モデル使用者が解決した手ごとの置換表示・持ち替えアニメーション状態を同期し、ローカル設定ルール自体を送信せずにリモートプレイヤーの表示結果を一致させます。
- 対応メイドでは互換性のあるYSM移動・補助・ルーレット・手持ち品・持ち替え・音声・パーティクル処理を適用します。閲覧者にはメイド所有者が解決した手持ち品・持ち替え・移動判定とサイズ制限付きの状態fingerprintを送りますが、ローカル設定、除外、タグ規則は同期しません。
- Epic Fightの戦闘モード中に公式YSMの左上オーバーレイを非表示にできます。
- Epic Fightがプレイヤー描画を上書きしなくなると、公式YSMの描画へ戻します。メイドは任意アダプターの正確なpatched rendererスコープ外では、Touhou Little MaidとEpicFight_TouhouLittleMaidの既存描画へ戻ります。
- リソース再読み込みとYSMのモデル再読み込みコマンド後に変換モデルを更新します。
- 選択モデルを準備できない場合、プレイヤーはEpic Fightのデフォルトプレイヤーメッシュ、対応メイドはEpicFight_TouhouLittleMaidの元のメイドメッシュへフォールバックします。
- YSMが表示するEpic Fight互換性警告を、そのクライアント環境での初回表示だけに制限します。

変換済みプレイヤーモデルの使用中は、任意形状のYSMモデルと二足歩行モデル用の装着位置が一致しないため、防具、頭装備、エリトラを非表示にします。マント、刺さった矢、ハチの針、モデル独自の置換が有効でない手持ち品はEpic Fightのpatched layerで描画を続けます。Epic Fightのデフォルトプレイヤーメッシュへフォールバックした場合、装備描画は変更されません。任意のメイドアダプターはEpicFight_TouhouLittleMaidの既存レイヤーを維持し、有効なモデル独自置換が担当する手だけ元の手持ち品描画を抑止します。

## 導入

このModと必要な依存Modを `mods` ディレクトリへ導入してください。マルチプレイでは、プレイヤーの選択状態とサーバー提供モデルを正しく解決するため、専用サーバーと参加する全クライアントの両方へYSM Epic Fight Compatを導入してください。

[Configured](https://www.curseforge.com/minecraft/mc-mods/configured) は任意です。導入すると、YSMの戦闘モード用オーバーレイ、モデルキャッシュ容量、YSMの手持ち品モデル、YSMの持ち替えアニメーション、YSMの移動アニメーション、およびそれぞれのモデル別除外をゲーム内で変更できます。除外対象はメインのYSM設定が有効な場合にその機能を無効として扱い、メイン設定が無効な場合に機能を有効化することはありません。モデル別エディターは通常のClient設定内に表示され、現在選択しているモデルIDを編集可能な項目として自動追加します。ConfiguredがなくてもModは正常に起動しますが、設定画面は利用できません。

Touhou Little Maid連携は任意で、Touhou Little MaidとEpicFight_TouhouLittleMaidの両方を導入した場合だけ有効になります。メイドの戦闘モデル連携を使用しない場合、どちらも必須ではありません。

## ビルド

Java 17とGitが必要です。

```powershell
.\gradlew.bat build
```

開発中のMapping API checkoutを使用する場合は、そのパスを明示してください。

```powershell
.\gradlew.bat build -PysmMappingApiPath=D:\src\YSM-Mapping-API
```

配布用jarは次の場所に生成されます。

```text
build/libs/ysm-epicfight-compat-mc1.20.1-<mod-version>-all.jar
```

## ドキュメント

- [実装詳細](docs/implementation.ja.md)

## ライセンス

このプロジェクトには [MIT License](LICENSE) が適用されます。
