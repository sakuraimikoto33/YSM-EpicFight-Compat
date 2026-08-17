# YSM Epic Fight Compat

[English](README.md)

YSM Epic Fight Compat は、公式 Yes Steve Model で選択したプレイヤーモデルを Epic Fight の戦闘アニメーションで描画する Forge Mod です。Epic Fight が戦闘用レンダラーを使用していない場面では、引き続き公式 YSM が通常のプレイヤー描画を担当します。

## 必要環境

- Minecraft 1.20.1
- Forge 47.4.20 以降
- Yes Steve Model 2.6.0 以降（Forge 1.20.1版）
- Epic Fight 20.14.17 以降（Forge 1.20.1版）
- [YSM Mapping API](https://github.com/sakuraimikoto33/YSM-Mapping-API) 0.1.1 以降

## 機能

- YSMのフォルダモデルと、暗号化されたものを含む `.ysm` パッケージをEpic Fightの戦闘用メッシュへ変換します。
- シングルプレイとマルチプレイで、各プレイヤーが選択したモデルとテクスチャを使用します。
- Epic Fightの三人称および一人称の戦闘描画に対応します。
- Epic Fightの主要jointポーズを置き換えず、補助ボーンの `pre_parallel`・`parallel` 動作を維持します。
- Epic Fightがプレイヤー描画を上書きしなくなると、公式YSMの描画へ戻します。
- リソース再読み込みとYSMのモデル再読み込みコマンド後に変換モデルを更新します。
- 選択モデルを準備できない場合はEpic Fightのデフォルトプレイヤーメッシュへフォールバックします。
- YSMが表示するEpic Fight互換性警告を、そのクライアント環境での初回表示だけに制限します。

変換モデルの使用中は、任意形状のYSMモデルと二足歩行モデル用の装着位置が一致しないため、防具、頭装備、エリトラを非表示にします。手持ち品はEpic Fightのアイテムレイヤーで描画されます。Epic Fightのデフォルトメッシュへフォールバックした場合、装備描画は変更されません。

## 導入

このModと必要な依存Modを `mods` ディレクトリへ導入してください。マルチプレイでは、プレイヤーの選択状態とサーバー提供モデルを正しく解決するため、専用サーバーと参加する全クライアントの両方へYSM Epic Fight Compatを導入してください。

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
build/libs/ysm-epicfight-compat-mc1.20.1-0.1.0-all.jar
```

## ドキュメント

- [実装詳細](docs/implementation.ja.md)

## ライセンス

このプロジェクトには [MIT License](LICENSE) が適用されます。
