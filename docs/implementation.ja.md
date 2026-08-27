# 実装詳細

[English](implementation.md)

このドキュメントでは、YSM Epic Fight CompatのMinecraft 1.20.1 Forgeブランチで使用している実装方式を説明します。

## 描画の担当範囲

通常のプレイヤー描画は公式YSMが担当します。`CombatRenderInterceptor` はプレイヤーのEpic Fightパッチが `overrideRender()` を返す場合だけ描画を引き継ぎ、Epic Fightへアーマチュアモデルの描画を要求して、そのフレームの通常プレイヤー描画をキャンセルします。これによりモデルの二重描画を防ぎます。

`CombatPlayerRenderer` はEpic Fightのpatched rendererイベントから登録されます。Epic Fightの人型プレイヤーレンダラーとして動作しつつ、変換済みYSMメッシュの準備ができた場合にメッシュプロバイダーを差し替えます。変換の待機中または失敗時はEpic Fightのデフォルトメッシュを維持します。

## プレイヤーの選択状態

`PlayerSelectionNbt` は、公式YSMがプレイヤーのForge capabilityデータへ保存したモデルIDとテクスチャ名を読み取ります。統合サーバーでは、クライアントから対応するサーバープレイヤーを直接解決します。専用サーバーでは `SelectionBroadcaster` が、この互換Modを導入しているクライアントへサイズ制限付きの選択状態を送信します。

選択状態の通信に含まれるのはIDだけです。モデルパッケージやテクスチャデータは含みません。

## モデルの探索と解析

`LocalModelRepository` は公式YSMのモデルカタログからモデルを探索します。`ysm.json` で構成される現行フォルダモデル、`main.json`・`arm.json`・PNG材質を同じ階層に置く旧フォルダモデル、`.ysm` パッケージに対応します。フォルダモデルのジオメトリとアニメーションはBedrock JSONとして解析します。パッケージは `PackageEnvelopeDecoder` がメモリ内で展開し、`BinaryPackageParser` が同じ内部表現である `ModelBundle` へ変換します。

公式のプライマリモデルである `default` 以外の各モデルでは、`OfficialDefaultAnimationLibrary` が、導入済みの公式YSM Mod内にあるプライマリモデルのアセットから、モデル側で定義されていないアニメーション名だけを補います。プライマリアセットのdigestと継承処理のrevisionもローカルモデルキャッシュのfingerprintへ含めるため、継承元アニメーションが変化した場合は古いクリップを再利用せず、対象の解析済みキャッシュを無効化します。

解析と通信の境界では、入力サイズ、要素数、パス、階層の深さ、数値を検証します。問題が発生した場合はその選択モデルだけを失敗として扱い、通常のフォールバックへ移行します。

## メッシュ変換

`SkinMeshCompiler` はBedrockのボーン階層を走査し、Epic Fightの `HumanoidMesh` が必要とする頂点配列とインデックス付きパーツを生成します。ボーンのピボットと回転を累積してから座標と法線を出力します。頂点の共有判定には座標、法線、UV、ジョイント割り当てを使用するため、UVの境界は別頂点として保持されます。

`HumanoidRig` は、厳密に分類した人型の主要ボーンだけにEpic Fightの20個のbiped jointを割り当てます。`AuxiliaryBoneLayout` は装飾用の補助ボーンを親から子の順で追加のスキニング番号へ割り当てます。上限はEpic Fightの行列上限である1,000です。各補助ボーンは最も近い主要jointを基準にしつつ、モデルが定義したbind階層を維持します。

## アニメーションとMolangランタイム

`ParallelAnimationProgram` はEpic Fightのanimatorを書き換えずにYSMアニメーションデータを評価します。`AuxiliaryPoseMatrices` はEpic Fightの主要jointスキニング行列を戦闘姿勢との接続点として受け取り、変換モデルの完全なbind階層まで展開したうえで、parallel・全身・手持ち品の各レイヤーを合成します。通常のEpic Fight担当経路では、現在の戦闘jointを基準に互換性のあるYSM補助差分と表示スケールを追加します。全身姿勢の担当経路では、root、胴体、手足、髪、スカート、尻尾などが異なる座標空間へ分離しないよう、モデル定義の連続した階層として評価します。

`MovementAnimationType` は、設定対象の移動状態として `walk`、`run`、`sneak_idle`、`sneak_move`、`jump`、`creative_flight`、`elytra_flight`、`swim`、`water_idle`、`crawl_idle`、`crawl_move`、`ladder_idle`、`ladder_up`、`ladder_down` を解決します。モデル使用者の解決済み方針で現在の状態が有効な場合、`AutomaticAnimationSelector` が対応する公式YSMのmainクリップを選択し、`ParallelAnimationProgram` がpre・main・postのControllerレイヤー、対応するholdレイヤー、parallelレイヤーを一つの全身構成として評価します。移動クリップの時計は各フレームで再開せず、公式のループ動作に従います。

`EpicFightPoseOwnership` は、Epic Fightのentity stateフラグ、正確なaction motion、表示中のmain-frame・reboundアニメーション、照準、アイテム使用、腕振り、ダメージ、knockdown状態を確認します。これらのアクションは、設定された移動姿勢と持ち替え姿勢の担当を即座に解除します。独自弓の使用・リリースだけは後述する別のモデル定義アクション経路です。`MovementPoseTransition` は、通常の移動姿勢担当が切り替わる際に、直前に表示した完全なskinから3tickかけてブレンドしますが、Epic Fightのアクション開始は遅延させません。クリエイティブ飛行ではYSMの移動姿勢または持ち替え姿勢がモデルを担当している間だけ公式のbody yaw規則を適用し、エリトラ飛行とアクションの外側変換はEpic Fight側を維持します。

`AutomaticAnimationSelector` はさらに、YSMの状態、装備条件、手持ち品条件、乗り物、同乗者用クリップを保持します。騎乗状態は完全姿勢の経路で処理し、Epic Fightの騎乗ポーズを二重に適用しません。ルーレットクリップはモデル空間のroot・胴体移動を維持し、ルーレット音声は引き続き公式YSMが担当します。手持ち品用クリップは後述の置換・エフェクトのみ・持ち替え規則に従います。通常アイテムはYSMの持ち替え姿勢だけが再生されている間もEpic Fightのアイテムレイヤーに残ります。

アニメーションクリップでは、ループまたは最終フレーム保持の再生方式、Molang `blend_weight`、キーフレーム補間、ループ境界をまたぐタイムラインの時系列順を保持します。ジオメトリに存在しないMolang疑似ボーンのトラックも、変数更新の副作用を維持するため定義順に評価しますが、姿勢行列は割り当てません。入れ子のMolang関数は呼び出し階層ごとに引数フレームを分離し、内側の関数が呼び出し元の引数を書き換えないようにします。

`ExpressionEngine` は、これらのクリップに必要なMolang演算子、対応する公式数学関数と読み取り専用Query、`ysm.first_order`・`ysm.second_order`・`ysm.perlin_noise` などのYSM補助関数を実装します。エンティティ、装備、アイテム、バイオーム、ブロック、カメラ距離、アニメーション時間の値を読み取り専用Queryとして公開します。通常変数では `v.*` と `variable.*`、永続roaming変数では `v.roaming.*` と `variable.roaming.*` を同一のものとして扱います。設定変数のスナップショットをリモートプレイヤーにも同期し、変数による表示と条件アニメーションを所有プレイヤーと一致させます。

## モデル独自の手持ち品

`CustomHeldItemPolicy` は、モデルの自動hold・use・swingクリップと既定表示状態を併せて調べます。条件によって通常は非表示の描画可能な手ボーン配下が表示される場合、またはクリップ全体でモデル定義のTool locatorを非表示にしながら描画可能な手持ちプロップを動かす場合だけ置換として扱います。モデルIDの許可リストではなく、モデルとアニメーションの意味から判定します。魔法陣などのエフェクトだけを表示する弓useクリップは、それだけでは置換とせず、Epic Fightの弓を残したまま追加描画します。

置換が有効な場合、`ParallelAnimationProgram` はモデル定義のプロップrootと必要な親階層だけを評価します。プロップをEpic Fightの現在の左右Tool jointへrebaseし、描画されている拳位置とEpic Fightのアイテム固有補正を維持します。`PatchedItemInHandLayerMixin` は、同じ変換メッシュを描画している厳密なスコープ内だけ、その手のEpic Fightアイテムを抑止します。置換を検出できない場合、ローカル設定で無効にした場合、Epic Fightのデフォルトメッシュへフォールバックした場合は、Epic Fightが通常どおりアイテムを描画します。

アイテム変更は、公式YSMの手持ち品providerと同じ、破損スタック・完全スタック比較を使って手ごとに検出します。モデル独自の置換品では、その遷移も手持ち品モデル設定を共有します。Epic Fightが通常アイテムを描画する場合は、独立した持ち替えアニメーション設定によって、現在のYSM main状態、対応するholdクリップ、有効なpre・hold・postのControllerレイヤーを一時的に一つの全身姿勢として構成するかを決定します。アイテム自体の描画はEpic Fightに残し、装着変換だけをモデル定義のTool locatorへ追従させます。通常のメインハンド弓では、この一時的なhold経路をEpic Fightの反対腕側Tool jointへ反転し、独自YSM弓では右手規則を維持します。

Epic Fightのアクション、ルーレット再生、独自の全身アクション、ほかの完全姿勢担当が開始すると、進行中の持ち替え遷移は一時停止せずキャンセルします。開始・終了時は完全skinの遷移経路を使用し、モデル定義の姿勢でTool locatorが縮退した場合は、対応する通常アイテムを一時的に抑止します。解決済みの置換表示と持ち替えアニメーションの真偽値だけを手ごとに同期し、モデル使用者のアイテムID、タグ、既定値、例外テーブルは送信しません。

検出した独自弓は、Epic Fightの左手弓規則へ移さず、暫定的にYSM定義のメインハンド・右手位置へ取り付けます。引き絞りとリリース中は、腕だけの上書きで肩が分離しないよう、YSM定義の全身ポーズで戦闘ポーズを置き換えます。照準yawは現在のEpic Fightモデル方向から算出し、Epic Fightの短いrebound信号が終わった後も1回限りのリリースを継続し、保存したYSM最終ポーズからEpic Fightへ1フレームで切り替えずにブレンドします。弓のエフェクトだけを持つジオメトリは、弓や全身ポーズを置き換えずにEpic Fightの左Tool jointへ取り付けます。

`AttackAnimationSoundMixin` が差し替えるのはEpic Fightの攻撃フェーズの振り音だけで、命中音と衝撃音は変更しません。`ServerAttackSoundRouter` は攻撃者、手、正確なEpic Fightサウンド、pitch、シーケンスを各追跡クライアントへ保持して送ります。クライアントは、有効な変換モデルが攻撃アイテムを置き換え、そのYSMタイムラインが音声を実際に再生したか、モデル定義の攻撃音声経路を持つ場合だけEpic Fightの代替音を抑止します。制限付きの確認待ち時間内に音声経路が有効にならなければ、元のEpic Fight振り音をローカル再生します。

## Animation Controllerと補助出力

`BedrockAnimationControllerParser` と `AnimationControllerProgram` は、対応するControllerステートマシンとして、初期状態の選択、定義順の遷移、アニメーションweight、`on_entry`、`on_exit`、固定時間またはカーブによるブレンド、最短経路の回転ブレンドを実装します。状態の `variables` は、その状態のクリップより先にフレームローカルな変数オーバーレイへ評価します。`remap_curve` は入力順に並べ、定義範囲外では端点の値に固定し、隣接点の間では線形補間します。

アニメーションタイムラインとController状態は、モデル内音源データをディスクへ保存せずにサウンドを出力できます。`ClientSoundOutput` はMapping APIの契約を使用し、公式YSMのメモリ内サウンドキャッシュからモデル内音源を解決します。名前空間付きのMinecraftサウンドイベントも使用できます。サウンドはクリップまたはController状態のスコープ単位で管理し、スコープ終了時に停止し、モデルまたはセッションの無効化時に消去します。一時停止と再開はMinecraftのサウンドエンジンが担当します。ルーレットの音声は公式YSMが担当し、互換レンダラーから同じ音声を二重に開始しません。

パーティクルは、Molangの `ysm.particle`・`ysm.abs_particle` 補助関数、またはBedrockアニメーション・Controllerの `particle_effects` から出力できます。宣言型エントリでは `effect`、`locator`、`pre_effect_script`、`bind_to_actor` を保持します。Controller状態に属するパーティクルは状態終了時に削除し、actorへbindしたパーティクルはエンティティへ追従します。一般的な人型locatorにはサイズ制限付きの身体相対近似位置を使用します。任意モデルのボーンlocator行列はパーティクルエンジンへ公開されていないため、未知のlocatorはエンティティ中央へフォールバックします。

## Molang評価のスケジューリング

スカラー式とベクトル式は `AnimationClip` の作成時に一度だけコンパイルします。コンパイラーは、各式が使用する変数、Query、関数、文字列引数、代入の情報も記録します。この依存情報を、スナップショットの範囲とレンダースレッド外で評価できるかの判定に使用します。

ローカルプレイヤーと、状態変更、タイムライン出力、文字列・ワールドアクセス、乱数、その他レンダースレッド専用関数を含む式は同期評価を維持します。副作用のないリモートプレイヤーのポーズは、不変の `SnapshotExpressionEnvironment` から制限付きdaemonワーカープールで評価でき、新しい評価の完了待ちでは直前の完成フレームを描画します。距離LODでは、16ブロック以内を毎フレーム、16～32ブロックを1tick間隔、32～64ブロックを2tick間隔、64ブロックより遠方を4tick間隔で評価します。このため、状態を持つ物理式と出力式は評価順を維持し、プレイヤーが遠いという理由だけで非同期化されません。

## テクスチャ解決

`OfficialTextureResolver` は、公式YSMのメモリ内テクスチャキャッシュから選択中のテクスチャを取得します。必要なYSMメンバーへのアクセスはYSM Mapping APIの意味ベースキーとして宣言し、実行時にmethod handleとして解決します。このプロジェクトは実行時の難読化名を保持しません。

互換レンダラーは公式YSMのテクスチャ位置を最優先し、同じGPU画像を二重に登録せずにYSMのテクスチャ選択へ追従します。解析済みまたはサーバー提供の各 `ModelBundle` にも、サイズ制限付きのエンコード済みテクスチャデータを保持します。公式の位置を取得できない場合は、`CombatMeshCache` がそのデータを別スレッドでデコードし、レンダースレッドで時間制限付きの動的テクスチャ登録を行います。公式テクスチャへ切り替えたときは重複したGPU登録だけを解放し、フォールバック元は対応するメモリキャッシュが削除されるまで保持するため、後から公式キャッシュを取得できなくなってもモデルのテクスチャが失われません。

## サーバーから提供されるモデルデータ

オンラインプレイヤーが選択したモデルをクライアントが持っていない場合、クライアントは専用サーバーへそのモデルを要求します。`ServerModelTransfers` はモデルが存在し、現在オンラインプレイヤーに選択されていることを確認したうえで、サーバーtick外で解析し、`GeometryTransferCodec` でエンコードします。

通信には、サイズ制限付きで圧縮されたジオメトリ、スケール設定、互換レンダラーが必要とするアニメーションクリップ、Animation Controllerデータ、宣言された全テクスチャを含めます。クリップ時間、blend weight、タイムライン、Controller変数、remap curve、サウンド参照、パーティクル宣言、テクスチャ数、個別テクスチャサイズ、テクスチャ合計サイズを明示的な上限付きでエンコードします。元の `.ysm` パッケージとモデル内音源データは含めません。データを制限付きのチャンクへ分割し、クライアント側でも同時組み立て数、合計サイズ、タイムアウト、ハッシュ、展開後サイズを検証してから `ModelBundle` として受け入れます。

`ModelRequestMessage` はremoteディスクキャッシュのSHA-256を任意で送信します。サーバーは完全な `DATA`、`UNCHANGED`、`UNAVAILABLE` のいずれかを返します。`UNCHANGED` は現在のサーバー名前空間にある一致済みデータだけを使用可能にします。キャッシュが欠落、破損、不一致の場合は削除し、ハッシュなしで再要求します。初回公開までは互換Modのネットワークプロトコルとシリアライズ済み転送形式をバージョン `1` に固定します。

一部の公式パッケージは、有限の終端を持たないアニメーション尺を正の無限大で表します。エンコーダーはこの宣言を0へ正規化し、可能な場合は実行時に保持したキーフレームから有効な尺を算出します。デコーダーは引き続き、ネットワークから受信した非有限値を拒否します。

## キャッシュと再読み込み

`CombatMeshCache` は制限付きワーカープールでモデルを遅延変換します。完成したメッシュとフォールバック用テクスチャ元はセッション中だけメモリへ登録し、`clientModelMemoryCacheSize` で制御するLRUキャッシュで保持します。変換に失敗したモデルは、元ファイルが変更された場合だけ再試行します。キャッシュから削除するときはGPUリソースと一時テクスチャの参照を解放します。

永続データは `config/ysm_epicfight_compat/cache` 以下へ分けます。`client` は解析済みローカル `ModelBundle`、`remote` は現在のマルチプレイサーバーで検証済みのデータ、`server` は生成済み転送データを保存します。clientとremoteは、ハッシュ化した名前、形式識別子、元データまたはサーバー検証用SHA-256、ペイロードSHA-256、圧縮済みモデルデータを持つ互換Mod独自のバイナリエンベロープを使用します。単独のPNG、JSON、`.ysm` ファイルは生成しません。serverも整合性用エンベロープを使用しますが、内容の秘匿は目的としません。いずれも暗号化やDRMではありません。

独立した `clientModelDiskCacheMiB`、`remoteModelDiskCacheMiB`、`serverModelDiskCacheMiB` の既定値は、それぞれ64、64、256 MiBです。各ディレクトリが個別に最終使用の古いファイルを削除します。0にすると読み書きを無効化し、整理時に既存データを削除します。単一データが自身の上限を超える場合も現在のメモリセッションでは利用できますが、ディスクには保存しません。`serverModelDiskCacheEnabled` を無効にしてもサーバーの永続層だけを迂回し、制限付きセッションメモリキャッシュとモデル単位の同時要求集約は維持します。

ローカルとサーバーのキャッシュは、元モデル内容のdigestが一致する場合だけ使用します。モデル更新時は一度だけ解析・エンコードし、以前のデータを原子的に上書きします。書き込みは通常ファイルの一時データから、対応環境ではatomic moveで置き換えます。キャッシュ探索ではシンボリックリンクを拒否し、破損データを削除します。ファイルI/O、ハッシュ計算、デコード、LRU整理はレンダースレッドとサーバーtickの外で行います。

リソース再読み込み、YSMのモデル再読み込みコマンド、サーバーからの切断、サーバー停止時には、有効なディスクキャッシュを保持したまま、関連する選択状態、メッシュ、テクスチャ、進行中の通信状態を無効化します。generation counterにより、無効化後に完了した処理が現行キャッシュへ戻ることを防ぎます。

## 一人称と装備レイヤー

`CombatFirstPersonMixin` はEpic Fightの一人称レンダラーでも同じ変換メッシュを選択し、一人称設定のパーツ表示状態を適用します。`FirstPersonArmorGateMixin` は変換済み一人称メッシュの使用中にbiped用防具描画を抑止します。

三人称のpatched rendererでは、二足歩行モデル用の装着変換を任意比率のモデルへ適用できないため、変換メッシュの防具、頭装備、エリトラを非表示にします。マント、刺さった矢、ハチの針はEpic Fightのpatched layerで描画を続けます。手持ち品も、有効なモデル独自ルールとモデル使用者が解決した設定によってその手のアイテムを置き換える場合を除き、同じレイヤーで描画します。Epic Fightのデフォルトメッシュへフォールバックした場合は、すべての標準レイヤーを利用できます。

## 互換性警告

クライアントの読み込み時に、`YSMCompatibilityWarningFilter` は公式YSMのEpic Fight互換性警告だけを識別します。`YSMCompatibilityWarningState` が初回表示を既存のクライアント設定へ記録し、無関係な警告へ影響を与えずに次回以降の同じ警告を抑止します。

## クライアント設定

クライアント設定は `config/ysm_epicfight_compat/ysm_epicfight_compat-client.toml`、統合・専用サーバー共通のキャッシュ設定は同じ階層の `ysm_epicfight_compat-common.toml` に保存します。`CombatOverlayMixin` は、公式YSMの各オーバーレイフレームを `CombatOverlayPolicy` へ委譲します。クライアント設定は、Epic Fightの戦闘モード中だけ左上のYSMプレイヤーオーバーレイを抑止します。値は各オーバーレイフレームで読み取るため、設定のライブ再読み込みは再起動せずに反映されます。

`useYsmHeldItemModelsByDefault` と `useYsmHeldItemSwitchAnimationsByDefault` はどちらも既定で有効です。`heldItemModelOverrides` と `heldItemSwitchAnimationOverrides` は独立したモデルIDテーブルで、値にはアイテムIDまたは `#item_tag` のselectorを指定します。一致したselectorはそれぞれの既定値だけを反転します。`minecraft:air` を使うと空手への持ち替えを対象にできます。モデル独自の置換品とそのアニメーションは必ず手持ち品モデル方針を使用し、持ち替えアニメーション方針はEpic Fightが通常アイテムを維持する場合だけ使用します。

`useYsmMovementAnimationsByDefault` も既定で有効です。`movementAnimationOverrides` はモデルIDごとに、アニメーション節で列挙した移動状態名を指定し、一致した状態で既定値を反転します。`ClientMovementAnimationPreferences` は、現在の正規化済みモデルID、意味上の移動状態、解決済みの姿勢担当bitを送信します。リモートプレイヤーの速度とクリエイティブ飛行能力だけではモデル使用者の状態を常に再構築できないため、`MovementAnimationPreferenceBroadcaster` がその結果を追跡クライアントへ中継します。

モデル別ルールはすべてモデル使用者のクライアントだけに残します。`ClientHeldItemModelPreferences` は解決済みのメインハンド・オフハンドの置換表示と持ち替えアニメーションの真偽値だけを送信し、`HeldItemPreferenceBroadcaster` が追跡クライアントへ中継します。これにより、他プレイヤーの既定値、モデル別ルール、アイテムID、アイテムタグを受信せずに、全クライアントで同じ外観上の姿勢を再現します。

Configured 2.2.3以降は任意です。文字列targetの `@Pseudo` Mixinで、Configuredが扱えない動的テーブルのleafだけを `ConfiguredHeldItemRules` へ置き換え、Configured APIへのリンクを任意統合の境界内へ限定します。手持ち品置換、持ち替えアニメーション、移動状態の各例外エディターは通常のClientフォルダー内に表示し、現在選択中のモデルIDを空の編集行として追加します。空の行は設定ファイルへ書きません。Configuredがない場合は対象クラスが読み込まれず、ゲーム内設定画面だけが利用できなくなります。

## ソース構成

| 領域 | パッケージ |
| --- | --- |
| モデルとテクスチャ入力 | `assets`, `assets.binary`, `geometry` |
| アニメーション、Molang、Controller、サウンド、パーティクル | `animation` |
| リグ対応、変換、キャッシュ | `mesh`, `cache` |
| Epic Fight描画とレイヤー | `render`, `render.layer`, `event`, `mixin` |
| 選択状態、ジオメトリ、モデル変数、移動姿勢担当、手持ち品表示の同期 | `network`, `network.geometry`, `network.message` |
| クライアント設定、Configured任意統合、警告処理 | `config`, `integration.configured`, `compat` |
