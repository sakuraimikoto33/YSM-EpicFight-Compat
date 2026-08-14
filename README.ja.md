# YSM Epic Fight Compat

[English](README.md)

YSM Epic Fight Compat は、公式 Yes Steve Model で選択したプレイヤーモデルを Epic Fight の戦闘アニメーションで描画する Forge Mod です。Epic Fight が戦闘用レンダラーを使用していない場面では、引き続き公式 YSM が通常のプレイヤー描画を担当します。

## 必要環境

- Minecraft 1.20.1
- Forge 47.4.20 以降
- Yes Steve Model 2.6.0 以降（Forge 1.20.1版）
- Epic Fight 20.14.17 以降（Forge 1.20.1版）
- YSM Mapping API 0.1.1 以降

## 機能

- YSMのフォルダモデルと、暗号化されたものを含む `.ysm` パッケージをEpic Fightの戦闘用メッシュへ変換します。
- シングルプレイとマルチプレイで、各プレイヤーが選択したモデルとテクスチャを使用します。
- Epic Fightの三人称および一人称の戦闘描画に対応します。
- Epic Fightがプレイヤー描画を上書きしなくなると、公式YSMの描画へ戻します。
- リソース再読み込みとYSMのモデル再読み込みコマンド後に変換モデルを更新します。
- 選択モデルを準備できない場合はEpic Fightのデフォルトプレイヤーメッシュへフォールバックします。
- YSMが表示するEpic Fight互換性警告を、そのクライアント環境での初回表示だけに制限します。

変換モデルの使用中は、任意形状のYSMモデルと二足歩行モデル用の装着位置が一致しないため、防具、頭装備、エリトラを非表示にします。手持ち品はEpic Fightのアイテムレイヤーで描画されます。Epic Fightのデフォルトメッシュへフォールバックした場合、装備描画は変更されません。

## 導入

このModと必要な依存Modを `mods` ディレクトリへ導入してください。マルチプレイでは、プレイヤーの選択状態とサーバー提供モデルを正しく解決するため、専用サーバーと参加する全クライアントの両方へYSM Epic Fight Compatを導入してください。

## ビルド

Java 17とGitが必要です。ビルド時にMinecraft 1.20.1用のYSM Mapping API安定版から0.1.1以上の最新版を選び、ソースを自動取得してビルドします。`ysm_mapping_api_version` はタグ選択、`ysm_mapping_api_version_range` はLoader依存下限に使用し、両方に同じ安定版SemVerを指定します。

```powershell
.\gradlew.bat check
```

複数リポジトリをまたぐ開発やオフラインビルドでは、互換性のあるMapping API checkoutを明示します。`minecraftVersion`は1.20.1、安定版`modVersion`は0.1.1以上である必要があります。

```powershell
.\gradlew.bat check -PysmMappingApiPath=D:\src\YSM-Mapping-API
```

配布用jarは次の場所に生成されます。

```text
build/libs/ysm-epicfight-compat-mc1.20.1-0.1.0-all.jar
```

## ドキュメント

- [Implementation details (English)](docs/implementation.md)
- [実装詳細](docs/implementation.ja.md)

## ライセンス

このプロジェクトには [MIT License](LICENSE) が適用されます。
