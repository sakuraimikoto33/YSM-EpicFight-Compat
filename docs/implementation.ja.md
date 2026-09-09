# 実装詳細

[English](implementation.md)

このドキュメントでは、YSM Epic Fight CompatのMinecraft 1.20.1 Forgeブランチで使用している実装方式を説明します。

## 描画の担当範囲

通常のプレイヤー描画は公式YSMが担当します。`CombatRenderInterceptor` はプレイヤーのEpic Fightパッチが `overrideRender()` を返す場合だけ描画を引き継ぎ、Epic Fightへアーマチュアモデルの描画を要求して、そのフレームの通常プレイヤー描画をキャンセルします。これによりモデルの二重描画を防ぎます。

`CombatPlayerRenderer` はEpic Fightのpatched rendererイベントから登録されます。Epic Fightの人型プレイヤーレンダラーとして動作しつつ、変換済みYSMメッシュの準備ができた場合にメッシュプロバイダーを差し替えます。変換の待機中または失敗時はEpic Fightのデフォルトメッシュを維持します。

## Touhou Little Maid任意連携

メイドアダプターは、Touhou Little MaidとEpicFight_TouhouLittleMaidの両方が導入されている場合だけ有効になります。`TouhouMaidSelectionAccess` は登録エンティティ種別が `touhou_little_maid:maid` と完全一致するものだけを受け入れ、TLMが同期済みの公開状態 `isYsmModel`、`getYsmModelId`、`getYsmModelTexture` をキャッシュしたmethod handleで読み取ります。反射と狭い範囲の文字列target `@Pseudo` Mixinを使用するため、任意連携がない通常経路ではどちらの任意Modのクラスにも直接リンクしません。

`TouhouMaidRendererMixin` はEpicFight_TouhouLittleMaidの `PatchedLivingMaidRenderer` 内だけでアダプターを開始します。`TouhouMaidRenderBridge` は通常と同じ `CombatMeshResolver` と `CombatMeshCache` へ公式YSM選択モデルを要求し、完全に一致するエンティティ、patch、renderer呼び出しの間だけメッシュプロバイダーを置き換えます。選択がない場合、変換の待機・失敗時、予期しないpatch、スコープ検査の失敗時はEpicFight_TouhouLittleMaidの元のメイドメッシュを変更しません。スコープ外の描画はTouhou Little MaidとEpicFight_TouhouLittleMaidが引き続き担当します。

EpicFight_TouhouLittleMaidはモデル行列へ `0.8` のスケールを適用します。変換メッシュだけが逆数の `1.25` を局所適用し、既存メイドレイヤーの描画前にPoseStackを復元します。手持ち品の平行移動にも、その変換メッシュが実際に描画された場合だけ同じ補正を適用します。EFTLMのアーマチュアはEpic Fightの20個の人型jointより後ろへ追加jointを持つ場合があります。`ModelPoseRetargeter` は変換メッシュを参照できない拡張専用サブツリーを無視し、人型jointを内包する不正な拡張は拒否します。また拡張アーマチュアではモデル由来の上腕bind pivotを使い、メイド側のbind位置を中心に肩が回転して分離することを防ぎます。

## プレイヤーの選択状態

`PlayerSelectionNbt` は、公式YSMがプレイヤーのForge capabilityデータへ保存したモデルIDとテクスチャ名を読み取ります。統合サーバーでは、クライアントから対応するサーバープレイヤーを直接解決します。専用サーバーでは `SelectionBroadcaster` が、この互換Modを導入しているクライアントへサイズ制限付きの選択状態を送信します。

選択状態の通信に含まれるのはIDだけです。モデルパッケージやテクスチャデータは含みません。

## モデル入力元と解析

`LocalModelRepository` は公式YSMのモデルカタログからモデルを探索します。`ysm.json` で構成される現行フォルダモデル、`main.json`・`arm.json`・PNGテクスチャを同じ階層に置く旧フォルダモデル、`.ysm` パッケージに対応します。フォルダモデルのジオメトリとアニメーションはBedrock JSONとして解析します。パッケージは `PackageEnvelopeDecoder` がメモリ内で展開し、`BinaryPackageParser` が同じ内部表現である `ModelBundle` へ変換します。

モデル内のMolang関数は、manifestの `files.function_path` ディレクトリ（既定は `functions`）、またはパッケージ内の関数エントリから保持します。`ModelFunctionAssets` が正規化名とUTF-8ソースを検証し、関数数4,096、ソースごと1 MiB、合計16 MiBに制限します。解析済みbundleはアニメーションとControllerに加え、モデルの `merge_multiline_expr`、`all_cutout`、`render_layers_first` プロパティも保持します。

選択可能な各ベーステクスチャには、任意のLabPBR normal・specular補助テクスチャを保持できます。フォルダモデルでは対応する `ysm.json` のtexture object、パッケージモデルでは型付きsubtextureから取得します。補助テクスチャが欠落していれば省略し、フォールバック用補助画像のデコードに失敗しても使用可能なベーステクスチャは無効にしません。PBRスキーマのsaltをローカル入力元fingerprintへ含め、補助テクスチャを保持していなかった旧ディスクエントリはそのまま再利用せず再構築します。

公式のプライマリモデルである `default` 以外の各モデルでは、`OfficialDefaultAnimationLibrary` が、導入済みの公式YSM Mod内にあるプライマリモデルのアセットから、モデル側で定義されていないアニメーション名だけを補います。プライマリアセットのdigestと継承処理のrevisionもローカルモデルキャッシュのfingerprintへ含めるため、継承元アニメーションが変化した場合は古いクリップを再利用せず、対象の解析済みキャッシュを無効化します。

解析と通信の境界では、入力サイズ、要素数、パス、階層の深さ、数値を検証します。問題が発生した場合はその選択モデルだけを失敗として扱い、通常のフォールバックへ移行します。

## メッシュ変換

`SkinMeshCompiler` はBedrockのボーン階層を走査し、Epic Fightの `HumanoidMesh` が必要とする頂点配列とインデックス付きパーツを生成します。ボーンのピボットと回転を累積してから座標と法線を出力します。頂点の共有判定には座標、法線、UV、ジョイント割り当てを使用するため、UVの境界は別頂点として保持されます。

大文字と小文字を区別する名前が `ysmGlow` で始まるボーン自身に定義されたジオメトリは、別のpart集合へコンパイルします。`CompatHumanoidMesh` は通常メッシュと同じskin行列、表示状態、テクスチャ、material経路を適用しながら、そのpartをフルブライトで描画します。このマーカーは子ボーンのジオメトリへ暗黙には継承されません。

`HumanoidRig` は、厳密に分類したボーンの役割をEpic Fightの20個の予約済みbiped jointへ対応付けます。`AuxiliaryBoneLayout` はモデルボーンも親から子の順で専用スキニング番号へ割り当て、モデル定義のbind階層を維持します。行列の合計上限は1,000で、モデルボーンには最大980個を使用します。

人型経路では、`AllHead` をchest基準に維持して首のジオメトリへ頭蓋の回転を加えず、`MHead` または `Head` からhead skinningを開始します。モデル定義のhead-control階層は、YSMのカメラ追従とその遷移状態へ引き続き参加します。

`RigBindingPlan` は、モデルIDやボーンの別名だけでなくジオメトリ構造から、Epic Fightへリターゲットするボーンとモデル定義の連続したサブツリーを分離します。自動判定の対象は、人型の身体構造を持たない適格なrootと、左右のhand・Tool階層が腕ではなく頭の配下にある身体ブランチです。これにより、通常の人型ブランチではEpic Fightの戦闘姿勢を維持しつつ、非人型フォームではモデル定義の姿勢を使用できます。単独のhand・Toolだけを持つ疎なモデルは従来のリターゲットを維持し、曖昧な構造や解析上限へ達した場合も既存のフォールバックを使用します。任意の非人型リグを推定したり、動物用の新しい戦闘アニメーションを生成したりする機能ではありません。内部の明示的root指定経路も、ユーザー設定可能なリグprofileではありません。

## アニメーションとMolangランタイム

`ParallelAnimationProgram` はEpic Fightのanimatorを書き換えずにYSMアニメーションデータを評価します。`AuxiliaryPoseMatrices` はEpic Fightの主要jointスキニング行列を戦闘姿勢との接続点として受け取り、変換モデルの完全なbind階層まで展開したうえで、parallel・全身・手持ち品の各レイヤーを合成します。通常のEpic Fight担当経路では、現在の戦闘jointを基準に互換性のあるYSM補助差分と表示スケールを追加します。全身姿勢の担当経路では、root、胴体、手足、髪、スカート、尻尾などが異なる座標空間へ分離しないよう、モデル定義の連続した階層として評価します。

モデル定義のサブツリーでは、別の数値姿勢へ連続したYSM階層を保持し、モデル空間または一つの外部Epic Fight装着基準へ接続します。共有する祖先の式は一度だけ実行し、その評価値を両方の姿勢領域へ渡します。構造を維持するmain・hold・Controllerの評価は、人型の戦闘姿勢を奪わずpose-onlyデータとして継続できます。この経路は変数代入と入れ子の関数のデータフローを維持しますが、サウンド、パーティクル、`ysm.sync` の出力は抑止し、抑止中のtimelineイベントを後から再生しません。

`MovementAnimationType` は、設定対象の移動状態として `walk`、`run`、`sneak_idle`、`sneak_move`、`jump`、`creative_flight`、`elytra_flight`、`swim`、`water_idle`、`crawl_idle`、`crawl_move`、`ladder_idle`、`ladder_up`、`ladder_down` を解決します。特殊状態は公式YSMと同じく、水泳、匍匐、はしごの順で優先します。匍匐の待機・移動は描画用walk-animation速度の絶対値を閾値 `0.05` で分け、はしごの上昇・下降・停止はdead zoneを設けず現在のY座標と前tickのY座標の差の符号で分けます。水中待機は水中かつ接地していない場合だけです。専用はしご状態は `ladder_up`、`ladder_down`、`ladder_stillness` だけを使用してからidleクリップへフォールバックし、似た名前の匍匐クリップを流用しません。

モデル使用者の解決済み方針で現在の状態が有効な場合、`AutomaticAnimationSelector` が対応する公式YSMのmainクリップを選択し、`ParallelAnimationProgram` がpre・main・postのControllerレイヤー、対応するholdレイヤー、装備条件レイヤー、parallelレイヤーを一つの全身構成として評価します。選択済みの意味上の状態は、その評価における公式の移動用 `ctrl.*` Queryにも固定し、リモートクライアントで別のmain-controller分岐を選ぶことを防ぎます。移動クリップの時計は各フレームで先頭から再生し直さず、公式のループ動作に従います。

`EpicFightPoseOwnership` は、Epic Fightのentity stateフラグ、正確なaction motion、表示中のmain-frame・reboundアニメーション、照準、アイテム使用、腕振り、ダメージ、knockdown状態を確認します。これらのアクションは、設定された移動姿勢と持ち替え姿勢の担当を即座に解除します。独自弓の使用・リリースだけは後述する別のモデル定義アクション経路です。通常のEpic Fight `CLIMB` motionが立てる受動的な `INACTION`・`MOVEMENT_LOCKED` フラグだけではアクション扱いにせず、設定済みのYSMはしご姿勢を解除しません。`MovementPoseTransition` は通常の移動姿勢担当へ入る際、直前に表示した完全なskinから3tickかけてブレンドします。匍匐またははしごの下位状態が交互に変化しただけでは開始ブレンドを再始動せず、Epic Fightのアクション開始も遅延させません。

YSMが対応する移動または持ち替え姿勢を担当している間、外側のレンダラーはクリエイティブ飛行と匍匐に公式のbody yawを適用し、接触中の昇降ブロックから水平方向を取得できる場合だけはしご姿勢を壁へ向け、Epic Fightが水泳姿勢へ追加するpitchを相殺します。匍匐では公式YSMが最後に加えるカメラpitchとyawを適用します。歩行、走行、スニーク、ジャンプ、クリエイティブ飛行、エリトラ飛行ではモデルが定義していない軸だけにカメラpitch・yawを追加し、はしごでは未定義のpitchだけを追加します。水泳と水中待機ではカメラによる頭部追従を追加しません。モデル自身のルート・上半身・頭部のtrackを優先し、Epic Fightアクション中はEpic Fight側の向きを維持します。

初期状態で有効な自然なはしご方針は、YSMが専用はしごクリップを担当し、そのクリップが左腕の動作を定義している場合だけ使用します。対応するHOLDレイヤーを外して両腕を昇降へ使い、右腕が未定義の場合は左腕controlのサブツリーだけをミラーして生成します。通常のEpic Fight手持ち品はclimb用の収納位置を維持し、有効なモデル独自手持ち品のサブツリーは強制的に非表示として重複するEpic Fight品も抑止します。専用のはしご腕動作がないモデルは従来のHOLD動作を維持します。自然なはしごを無効にした場合、通常アイテムを論理上のTool手へ戻し、メインハンドの弓だけはEpic Fightの左手補正を使用します。モデルが定義した残りのはしご腕動作は維持し、互換フォールバックで必要な場合だけ、未定義の右腕を定義済み左腕からミラーします。

`AutomaticAnimationSelector` はさらに、YSMの状態、装備条件、手持ち品条件、乗り物、同乗者用クリップを保持します。モデル使用者の解決済み設定によりYSMが騎乗中の乗り物を描画する場合、騎乗状態は完全姿勢の経路で処理し、Epic Fightの騎乗ポーズを二重に適用しません。YSM乗り物経路を無効にした場合は乗り物・同乗者用クリップとlocator補正も適用せず、その通常騎乗経路をEpic Fightまたはバニラへ委ねます。後述する別設定のSWEM騎乗者アニメーション経路は独立しています。ルーレットクリップはモデル空間のroot・胴体移動を維持します。プレイヤーのルーレット音声は公式YSMが担当しますが、EFTLMレンダラーが公式メイド描画を迂回する対応メイドでは互換ランタイムが音声を担当し、監視したルーレットgenerationにより同名アニメーションの再開始を識別します。手持ち品用クリップは後述の置換・エフェクトのみ・持ち替え規則に従います。通常アイテムはYSMの持ち替え姿勢だけが再生されている間もEpic Fightのアイテムレイヤーに残ります。

アニメーションクリップでは、ループまたは最終フレーム保持の再生方式、Molang `blend_weight`、キーフレーム補間、ループ境界をまたぐタイムラインの時系列順を保持します。ジオメトリに存在しないMolang疑似ボーンのトラックも、変数更新の副作用を維持するため定義順に評価しますが、姿勢行列は割り当てません。入れ子のMolang関数は呼び出し階層ごとに引数フレームを分離し、内側の関数が呼び出し元の引数を書き換えないようにします。

`ExpressionEngine` は、これらのクリップに必要なMolang演算子、対応する公式数学関数と読み取り専用Query、`ysm.first_order`・`ysm.second_order`・`ysm.perlin_noise` などのYSM補助関数を実装します。エンティティ、装備、アイテム、バイオーム、ブロック、カメラ距離、アニメーション時間の値を読み取り専用Queryとして公開します。通常変数では `v.*` と `variable.*`、永続roaming変数では `v.roaming.*` と `variable.roaming.*` を同一のものとして扱います。設定変数のスナップショットをリモートプレイヤーにも同期し、変数による表示と条件アニメーションを所有プレイヤーと一致させます。対応メイドではメイド自身の `LivingEntity` Queryとモデル内ランタイム変数を使用できますが、所有者の公式YSM設定変数やroaming変数の状態は継承しません。

`OfficialGroundSpeedQuery` は、既に生成されたクライアントのプレイヤーcontextに対し、Mapping APIを通じて公式の `ysm.ground_speed2` 最終結果を読み取ります。公式の移動状態を再構築することはなく、contextやmappingが取得できない場合と、プレイヤー以外のエンティティでは0を返します。`ysm.in_shield_block_cooldown` は、エンティティの同一性を検証したサーバーからの盾防御成功通知を使用し、受信からエンティティの5tick間だけtrueになります。盾を使用しているだけではこの期間を開始しません。

`DisplayedBoneQueries` は、`ysm.bone_rot`、`ysm.bone_pos`、`ysm.bone_scale`、`ysm.bone_pivot_abs` 向けに完了済みフレームの不変スナップショットを公開します。正確なボーン名に対する `x`、`y`、`z` 成分を返し、回転は度、位置はモデル定義の単位で表します。評価中の姿勢自身ではなく直前の完了済みスナップショットを読み、存在しないボーンは各成分0、親の縮退により復元できないデータは直前またはbind値を使用します。この正規のモデル空間スナップショットへ一人称のview変換は含めません。`ctrl.playing_extra_animation` は、対応するローカルクリップがなくても公式のルーレット再生状態に従います。

型付きエンティティ参照は、`EntityReferenceEnvironment` を通じて読み取り専用の `->` 評価を行い、参照先モデルの変数、入力、スクリプト、出力は公開しません。`ysm.projectile_owner` の参照元は、投射物の所有者が同じワールドの有効なプレイヤーである場合だけ解決します。現在のプレイヤー・メイド描画contextではnullになり、互換Modが投射物描画を担当する経路を追加するものではありません。

## モデル内スクリプト

`MolangScriptRuntime` は、`fn.<name>` の呼び出し可能な関数と、`@player_init`、`@player_update`、`@sync`、対応する `@player_ctrl_<slot>` の購読をコンパイルします。初期化、フレーム更新、待機中の同期callbackの順で実行します。ソースは引数フレームと呼び出し深度上限を持つ制限付きMolang評価器の中で実行し、JVMやOSのコードとしては実行しません。

Controller hookは `ctrl.set_animation`、`ctrl.reset`、`ctrl.indicate_reload`、`ctrl.set_beginning_transition_length` を使用し、`ctrl.state_continue`、`ctrl.state_stop`、`ctrl.state_pause`、`ctrl.state_bypass` の明示的なreturnで動作を選択します。hookは自動providerをカスタマイズしますが、モデル定義のBedrock Controllerがある場合はそちらを優先し、現在の `ysm-builtin` 状態がproviderへ委譲している場合だけhookを適用します。スクリプトの開始遷移と `ysm-builtin` 対応Controller slotの遷移では、旧クリップを再評価せず保存済み姿勢をブレンドし、イベントと再生時計を独立して維持します。

`ysm.sync` は最大16個の有限な数値引数だけを送信します。ローカルプレイヤーの現在の選択モデルだけが送信でき、`ServerScriptEvents` が選択状態とレート上限を検証してから、送信者と追跡クライアントへ同じ認証済みスナップショットを中継します。送信者は返信前にイベントをローカルでechoしません。エンティティの同一性、モデル、sequence、キュー数、有効期限の検証により古い配信を拒否します。このイベント通信にソースコード、任意オブジェクト、メイド所有者のスクリプト中継は含めません。

## ParCool・SWEMの任意プレイヤーアニメーション連携

`ModAnimationResolver` は、リモートプレイヤーを含め、プレイヤーだけについて対応するnativeアニメーション状態を読み取ります。メイドアダプターは拡張しません。`ModAnimationClips` は選択を既知の公式YSM `parcool:*`・`swem:*` クリップ名へ限定します。選択モデルには、モデル自身の定義または通常のdefaultアニメーション継承経路から得られた、使用可能なボーントラック付きの対応クリップが必要です。任意APIがない場合、未対応nativeアニメーション、対応クリップの欠落、使用者設定の無効時は、既存の描画経路を維持します。変更するのは変換済みプレイヤーの表示姿勢だけで、移動、hitbox、Epic Fightのゲームプレイ用アーマチュア、馬のレンダラーは変更しません。

`ParCoolAnimationStateAccess` は、キャッシュした反射を通じてParCoolのcapability、現在のanimator、終了判定、時計、方向別状態を読み取ります。対応する壁移動、vault、roll、dodge、ぶら下がり、水泳などの定義済みアクションを正確なクリップへ対応付け、native animator自体は進めません。`SwemAnimationAccess` は、生存中のSWEM馬に直接乗る生存中のプレイヤーを対象に、有効な `swem:animations` PlayerAnimatorレイヤーを読み取ります。対応する騎乗者クリップは `idle`、`walk`、`trot`、`canter`、`canter_ext`、`gallop`、`jump_lv1`～`jump_lv5` です。どちらもnativeの経過時間と再開始の識別情報を使用し、repeatクリップはループ、1回再生はnativeアクション終了まで最終姿勢を保持、長さ未定義の式クリップもnativeのアニメーション時計を維持します。SWEM騎乗を古いParCool状態より優先し、ほかの乗客もParCoolクリップを選択しません。

許可されたModクリップは、全身のmain・Controller構成、公式のbody yawと終端の頭部補正を使用し、持ち替え遷移をキャンセルします。ダメージ、死亡、睡眠、spin attack、実際のEpic Fight戦闘アクション、ルーレット、モデル独自の全身アクションは、それぞれの優先順位を維持します。Epic Fightの一般的な移動lockや `INACTION` だけでは、独立して観測したnativeのparkour・騎乗姿勢を拒否しません。さらに `EpicParCoolAnimationAccess` は、Epic ParCoolのChain movement・Wall movementに対応する特定のアニメーションIDを、表示中のcurrent・next・link先を含めて予約します。これらのアドオン姿勢では、通常のYSM移動や持ち替えへのフォールバックも抑止します。ぶら下がり、wall running、wall jumping全体を一律に除外するものではありません。

## モデル独自の手持ち品

`CustomHeldItemPolicy` は、モデルの自動hold・use・swingクリップと既定表示状態を併せて調べます。条件によって通常は非表示の描画可能な手ボーン配下が表示される場合、またはクリップ全体でモデル定義のTool locatorを非表示にしながら描画可能な手持ちプロップを動かす場合だけ置換として扱います。モデルIDの許可リストではなく、モデルとアニメーションの意味から判定します。魔法陣などのエフェクトだけを表示する弓useクリップは、それだけでは置換とせず、Epic Fightの弓を残したまま追加描画します。

置換が有効な場合、`ParallelAnimationProgram` はモデル定義のプロップrootと必要な親階層だけを評価します。プロップをEpic Fightの現在の左右Tool jointへrebaseし、描画されている拳位置とEpic Fightのアイテム固有補正を維持します。`PatchedItemInHandLayerMixin` は、同じ変換メッシュを描画している厳密なスコープ内だけ、その手のEpic Fightアイテムを抑止します。置換を検出できない場合、ローカル設定で無効にした場合、Epic Fightのデフォルトメッシュへフォールバックした場合は、Epic Fightが通常どおりアイテムを描画します。

YSMが移動姿勢を担当している間は、対応する通常のHOLDクリップも移動mainの後へpose-onlyの全身データとして合成します。これにより、Epic Fightが描画する通常アイテムを維持したまま、モデル定義の腕・手・Tool locatorを一致させます。この経路ではHOLDクリップのtimelineを進行・発火させないため、timelineで定義されたサウンドやパーティクルを再出力しません。前述の自然なはしご方針だけは明示的な例外です。

モデル定義フォームの手持ちlocatorを持つジオメトリでは、`HandLocatorSelection` が身体の遷移後の完成済み表示skinとモデル定義の表示状態から、物理的な左右のTool候補を解決します。表示中かつ縮退していない候補が正確に一つ必要です。候補が欠落、曖昧、不正な場合は装着を上書きせず、全候補が非表示の場合はそのアイテムを抑止します。口、足、尻尾のlocator自身にジオメトリがなくても構いません。通常の表示中の人型locatorは、既存のEpic Fightの握り位置を維持します。`RenderFrameContext` が利き腕を使ってモデル定義フォームのlocatorを論理上の手へ対応付け、アイテムごとの一時装着スコープを開くため、両手持ちレンダラーがもう一方の手を移動させません。このフォーム装着は三人称だけで使用し、モデル定義の表示状態の判定には一人称のパーツ非表示を含めません。また、独自アイテム置換や自然なはしご方針を上書きしません。

アイテム変更は、公式YSMの手持ち品providerと同じ、破損スタック・完全スタック比較を使って手ごとに検出します。モデル独自の置換品では、その遷移も手持ち品モデル設定を共有します。Epic Fightが通常アイテムを描画する場合は、独立した持ち替えアニメーション設定によって、現在のYSM main状態、対応するholdクリップ、有効なpre・hold・postのControllerレイヤーを一時的に一つの全身姿勢として構成するかを決定します。アイテム自体の描画はEpic Fightに残し、装着変換だけをモデル定義のTool locatorへ追従させます。通常のメインハンド弓では、この一時的なhold経路をEpic Fightの反対腕側Tool jointへ反転し、独自YSM弓では右手規則を維持します。

Epic Fightのアクション、ルーレット再生、独自の全身アクション、ほかの完全姿勢担当が開始すると、進行中の持ち替え遷移は一時停止せずキャンセルします。開始・終了時は完全skinの遷移経路を使用し、モデル定義の姿勢でTool locatorが縮退した場合は、対応する通常アイテムを一時的に抑止します。プレイヤーでは解決済みの置換表示と持ち替えアニメーションの真偽値だけを手ごとに同期し、モデル使用者のアイテムID、タグ、メイン設定、除外テーブルは送信しません。メイド所有者の同期では、後述する別の認証済み状態fingerprintを使用します。

検出した独自弓は、Epic Fightの左手弓規則へ移さず、一時的にYSM定義のメインハンド・右手位置へ取り付けます。引き絞りとリリース中は、腕だけの上書きで肩が分離しないよう、YSM定義の全身ポーズで戦闘ポーズを置き換えます。照準yawは現在のEpic Fightモデル方向から算出し、Epic Fightの短いrebound信号が終わった後も1回限りのリリースを継続し、保存したYSM最終ポーズからEpic Fightへ1フレームで切り替えずにブレンドします。弓のエフェクトだけを持つジオメトリは、弓や全身ポーズを置き換えずにEpic Fightの左Tool jointへ取り付けます。

`AttackAnimationSoundMixin` が差し替えるのはEpic Fightの攻撃フェーズの振り音だけで、命中音と衝撃音は変更しません。`ServerAttackSoundRouter` は対応する攻撃エンティティのID・UUID、手、正確なEpic Fightサウンド、pitch、シーケンスを、認可済みの各追跡クライアントへ保持して送ります。クライアントは、有効な変換モデルが攻撃アイテムを置き換え、そのYSMタイムラインが音声を実際に再生したか、モデル定義の攻撃音声経路を持つ場合だけEpic Fightの代替音を抑止します。制限付きの確認待ち時間内に音声経路が有効にならなければ、元のEpic Fight振り音をローカル再生します。

## モデル定義の投射物・釣り針・乗り物

モデル固有の投射物、釣り針、乗り物ジオメトリは引き続き公式YSMが描画し、互換Mod側でモデルを再構築しません。`YsmProjectileRendererMixin`、`YsmFishingHookRendererMixin`、`YsmVehicleRendererMixin` は、Epic Fight戦闘モード中に公式YSMへそのエンティティの描画を継続させるか、Epic Fightまたはバニラの元のレンダラーへ戻すかを決定します。`YsmVehiclePreviewMixin` は同じ決定をYSMの乗客locator変換にも適用し、非表示にしたYSM乗り物が表示中の騎乗者だけを移動させることを防ぎます。戦闘モード外ではこれらの制御は公式YSMを変更しません。4つの対象メソッドとオーバーレイメソッドはYSM Mapping APIの管理済み意味キーと安定したsource aliasだけを通じて参照し、このプロジェクトは実行時の難読化名を保持しません。

投射物の担当判定では、モデル定義の手持ち品とエフェクトのみのジオメトリを区別します。変換済みモデルが弓またはトライデントの通常のHOLD状態で表示される置換モデルを定義している場合、その投射物はYSM手持ち品モデル方針に従います。定義していない場合は、`common.models.useYsmProjectileModels` と `common.models.exclusions.projectileModelExclusions` が投射物専用ジオメトリを維持するかを決定します。チャージ済みstackの状態をアイテムIDだけから再構築できないため、クロスボウの投射物は常に投射物専用方針を使用します。釣り針は釣竿に対する手持ち品モデル方針に従います。乗り物は `common.models.useYsmVehicleModels` と `common.models.exclusions.vehicleModelExclusions` に従い、Epic Fight側を選択した場合はYSMの乗り物とlocatorと同時に、対応する騎乗アニメーション経路も無効にします。

専用サーバーは投射物の発射時に所有者、選択モデル、元アイテム、エンティティタイプをsnapshotとして確定するため、発射後の持ち替えや追跡中の所有者を一時的に取得できない状況で、飛行中エンティティの担当が変化しません。所有者を取得できる間はEpic Fightモードを更新し、短い追跡の空白では最後に同期した値を維持します。乗り物は現在の最初のプレイヤー乗客に追従します。`SubEntityPreferenceBroadcaster` は該当モデル使用者のクライアントだけにローカル方針の解決を要求し、不透明な所有者epochとsource revisionに対して応答を認証してから、容量制限付きの解決済みsnapshotを追跡クライアントへ配信します。不明または古い判定は、戦闘モード中は元のレンダラーへ安全側にフォールバックします。設定トグルと、エンティティタイプタグselectorを含むモデル別除外テーブルはローカルに残り、同期しません。

## Animation Controllerと補助出力

`BedrockAnimationControllerParser` と `AnimationControllerProgram` は、対応するControllerステートマシンとして、初期状態の選択、定義順の遷移、アニメーションweight、`on_entry`、`on_exit`、固定時間またはカーブによるブレンド、最短経路の回転ブレンドを実装します。状態の `variables` は、その状態のクリップより先にフレームローカルな変数オーバーレイへ評価します。`remap_curve` は入力順に並べ、定義範囲外では端点の値に固定し、隣接点の間では線形補間します。

`ControllerOrder` はトップレベルのphaseを、pre-parallel、pre・main・post-main、pre・hold・post-hold、pre・swing・post-swing、pre・use・post-use、passenger、その他のController、parallelの順へ並べます。同じphase内は元のController名の辞書順です。動的suffixは、対応するトップレベルの `player.pre_*_suffix`、`player.post_*_suffix`、`player.pre_parallel_suffix`、`player.parallel_suffix` 群だけで認識し、固定parallel slot `0`～`7` も維持します。動的Controllerは固定の手持ち品slotではなく独立したレイヤーです。空のController状態は循環を検出する上限付きの1ステップ内で連続遷移でき、空の状態ごとに1フレームの遅延を追加しません。

アニメーションタイムラインとController状態は、モデル内音源データをディスクへ保存せずにサウンドを出力できます。`ClientSoundOutput` はMapping APIの契約を使用し、公式YSMのメモリ内サウンドキャッシュからモデル内音源を解決します。名前空間付きのMinecraftサウンドイベントも使用できます。サウンドはクリップまたはController状態のスコープ単位で管理し、スコープ終了時に停止し、モデルまたはセッションの無効化時に消去します。一時停止と再開はMinecraftのサウンドエンジンが担当します。プレイヤーのルーレット音声は公式YSMが担当するため互換レンダラーから二重に開始しません。EFTLMのpatched rendererが本来音声を担当する公式メイド描画を迂回するため、限定されたメイド経路では互換ランタイムがルーレット音声を出力します。

パーティクルは、Molangの `ysm.particle`・`ysm.abs_particle` 補助関数、またはBedrockアニメーション・Controllerの `particle_effects` から出力できます。宣言型エントリでは `effect`、`locator`、`pre_effect_script`、`bind_to_actor` を保持します。Controller状態に属するパーティクルは状態終了時に削除し、actorへbindしたパーティクルはエンティティへ追従します。一般的な人型locatorには一定範囲内に制限した身体相対の近似位置を使用します。任意モデルのボーンlocator行列はパーティクルエンジンへ公開されていないため、未知のlocatorはエンティティ中央へフォールバックします。

## インベントリプレビューの分離

`InventoryRenderScopeMixin` は明示的なインベントリ内のエンティティプレビューを識別し、`InventoryRenderScope` は一致する主要な三人称描画だけにそのcontextを使用させます。入れ子のワールド描画や一人称描画はこれを継承しません。`RenderContextState` は、ワールドとプレビューの時計、スクリプト変数、Controller状態、bone query、キャッシュ姿勢、姿勢遷移を分離します。プレビューは `ysm.rendering_in_inventory` を公開し、ローカルなスクリプト状態を維持しますが、ワールドのサウンド、パーティクル、roaming変数への書き込み、スクリプト同期を出力しません。エンティティやモデルの無効化時には両contextを破棄します。

## Molang評価のスケジューリング

スカラー式とベクトル式は `AnimationClip` の作成時に一度だけコンパイルします。コンパイラーは、各式が使用する変数、Query、関数、文字列引数、代入の情報も記録します。この依存情報を、スナップショットの範囲とレンダースレッド外で評価できるかの判定に使用します。

ローカルプレイヤーと、状態変更、タイムライン出力、文字列・ワールドアクセス、乱数、その他レンダースレッド専用関数を含む式は同期評価を維持します。リモートプレイヤーや対応メイドを含む、ほかの対応 `LivingEntity` の副作用がないポーズは、不変の `SnapshotExpressionEnvironment` から制限付きdaemonワーカープールで評価でき、新しい評価の完了待ちでは直前の完成フレームを描画します。距離LODでは、16ブロック以内を毎フレーム、16～32ブロックを1tick間隔、32～64ブロックを2tick間隔、64ブロックより遠方を4tick間隔で評価します。このため、状態を持つ物理式と出力式は評価順を維持し、エンティティが遠いという理由だけで非同期化されません。

## テクスチャ解決

`OfficialTextureResolver` は、公式YSMのメモリ内テクスチャキャッシュから選択中のテクスチャを取得します。必要なYSMメンバーへのアクセスはYSM Mapping APIの意味ベースキーとして宣言し、実行時にmethod handleとして解決します。このプロジェクトは実行時の難読化名を保持しません。

互換レンダラーは公式YSMのテクスチャ位置を最優先し、同じGPU画像を二重に登録せずにYSMのテクスチャ選択へ追従します。解析済みまたはサーバー提供の各 `ModelBundle` にも、サイズ制限付きのエンコード済みテクスチャデータを保持します。公式の位置を取得できない場合は、`CombatMeshCache` がそのデータを別スレッドでデコードし、レンダースレッドで上限管理された動的テクスチャ登録を行います。公式テクスチャへ切り替えたときは重複したGPU登録だけを解放し、フォールバック元は対応するメモリキャッシュが削除されるまで保持するため、後から公式キャッシュを取得できなくなってもモデルのテクスチャが失われません。

フォールバック用ベーステクスチャにnormalまたはspecular補助テクスチャがある場合、`CompatPbrTexture` が不変のデコード済み補助データを保持し、`OculusPbrBridge` が利用可能なIris/Oculus API名前空間を通じて任意のexact-class LabPBR loaderを登録します。シェーダーテクスチャの再読み込み後は、このloaderが補助GPUテクスチャを再生成します。Iris/Oculusは必須依存ではなく、APIが存在しない場合、未対応名前空間の場合、loaderの処理に失敗した場合も、PBR補助なしのベーステクスチャで描画を継続します。

## サーバーから提供されるモデルデータ

オンラインプレイヤーまたは同期済みの対応メイドが選択したモデルをクライアントが持っていない場合、クライアントは専用サーバーへそのモデルを要求します。`ModelRequestMessage` は対象エンティティのIDとUUIDを指定します。`ServerModelTransfers` は要求受付時と配信直前の両方でその組を検証し、受信者が対象を追跡していることと、現在の公式YSM選択が要求モデルと一致することを確認してから、サーバーtick外でモデルを解析し `GeometryTransferCodec` でエンコードします。受信者ごとの要求数、待機処理数、データ量を制限して、このエンティティ認可付き転送経路を保護します。

通信には、サイズ制限付きで圧縮されたジオメトリ、スケール・描画設定、互換レンダラーが必要とするアニメーションクリップ、Animation Controllerデータ、Molang関数ソース、宣言された全ベース・normal・specularテクスチャを含めます。クリップ時間、blend weight、タイムライン、Controller変数、remap curve、関数数とソースサイズ、サウンド参照、パーティクル宣言、テクスチャ数、個別テクスチャサイズ、テクスチャ合計サイズを明示的な上限付きでエンコードします。元の `.ysm` パッケージとモデル内音源データは含めません。データを制限付きのチャンクへ分割し、クライアント側でも同時組み立て数、合計サイズ、タイムアウト、ハッシュ、展開後サイズを検証してから `ModelBundle` として受け入れます。

`ModelRequestMessage` はremoteディスクキャッシュのSHA-256を任意で送信します。サーバーは完全な `DATA`、`UNCHANGED`、`UNAVAILABLE` のいずれかを返します。`UNCHANGED` は現在のサーバー名前空間にある一致済みデータだけを使用可能にします。キャッシュが欠落、破損、不一致の場合は削除し、ハッシュなしで再要求します。初回公開までは互換Modのネットワークプロトコルとシリアライズ済み転送形式をバージョン `1` に固定します。

一部の公式パッケージは、有限の終端を持たないアニメーション尺を正の無限大で表します。エンコーダーはこの宣言を0へ正規化し、可能な場合は実行時に保持したキーフレームから有効な尺を算出します。デコーダーは引き続き、ネットワークから受信した非有限値を拒否します。

## キャッシュと再読み込み

`CombatMeshCache` は制限付きワーカープールでモデルを遅延変換します。完成したメッシュとフォールバック用テクスチャ元はセッション中だけメモリへ登録し、`client.cache.clientModelMemoryCacheTargetCount` で制御するLRUキャッシュで保持します。変換に失敗したモデルは、モデルの入力元が変化した場合だけ再試行します。キャッシュから削除するときはGPUリソースと一時テクスチャの参照を解放します。

永続データは `config/ysm_epicfight_compat/cache` 以下へ分けます。`client` は解析済みローカル `ModelBundle`、`remote` は現在のマルチプレイサーバーで検証済みのデータ、`server` は生成済み転送データを保存します。ベーステクスチャと任意のnormal・specular補助テクスチャは同じサイズ制限付きペイロード内に保持します。clientとremoteは、ハッシュ化した名前、形式識別子、元データまたはサーバー検証用SHA-256、ペイロードSHA-256、圧縮済みモデルデータを持つ互換Mod独自のバイナリエンベロープを使用します。単独のPNG、JSON、`.ysm` ファイルは生成しません。serverも整合性用エンベロープを使用しますが、内容の秘匿は目的としません。いずれも暗号化やDRMではありません。

独立した `client.cache.clientModelDiskCacheMiB`、`client.cache.remoteModelDiskCacheMiB`、`server.cache.serverModelDiskCacheMiB` の既定値は、それぞれ64、64、256 MiBです。各ディレクトリが個別に最終使用時刻が最も古いファイルを削除します。0にすると読み書きを無効化し、整理時に既存データを削除します。単一データが自身の上限を超える場合も現在のメモリセッションでは利用できますが、ディスクには保存しません。`server.cache.serverModelDiskCacheEnabled` を無効にしてもサーバーの永続層だけを迂回し、制限付きセッションメモリキャッシュとモデル単位の同時要求集約は維持します。

ローカルとサーバーのキャッシュは、元モデル内容のdigestが一致する場合だけ使用します。モデル更新時は一度だけ解析・エンコードし、以前のデータを原子的に上書きします。書き込みは通常ファイルの一時データから、対応環境ではatomic moveで置き換えます。キャッシュ探索ではシンボリックリンクを拒否し、破損データを削除します。ファイルI/O、ハッシュ計算、デコード、LRU整理はレンダースレッドとサーバーtickの外で行います。

リソース再読み込み、YSMのモデル再読み込みコマンド、サーバーからの切断、サーバー停止時には、有効なディスクキャッシュを保持したまま、関連する選択状態、メッシュ、テクスチャ、進行中の通信状態を無効化します。generation counterにより、無効化後に完了した処理が現行キャッシュへ戻ることを防ぎます。

## プレイヤーの一人称と装備レイヤー

`CombatFirstPersonMixin` はEpic Fightの一人称レンダラーでも同じ変換メッシュを選択し、一人称設定のパーツ表示状態を適用します。`FirstPersonArmorGateMixin` は変換済み一人称メッシュの使用中にbiped用防具描画を抑止します。

プレイヤー用patched rendererでは、二足歩行モデル用の装着変換を任意比率のモデルへ適用できないため、変換済みプレイヤーメッシュの防具と頭装備を非表示にします。`ConvertedElytraLayer` は代わりに、使用可能な `ElytraLocator` が一つだけ存在する場合、その最終アニメーション姿勢とスケールへバニラのエリトラを取り付けます。曖昧でないlocatorを持たない変換モデルでは、エリトラを非表示のままにします。マント、刺さった矢、ハチの針、通常の手持ち品はEpic Fightのpatched layerで描画を続けます。変換済みモデルの非アクションフレームと、YSMが完全姿勢を担当するアクションでは、これらのEpic Fight装着行列を最終表示骨格から投影します。Epic Fightが担当するアクションでは、前述のスコープ付きモデル定義フォームTool経路を除き、Epic Fightの装着姿勢を維持します。`ElytraLocator` 対応のエリトラは、代わりに専用の `elytraLocatorPose` 経路を使用します。手持ち品の抑止は、有効なモデル置換とモデル使用者の設定、または前述のモデル定義locatorの非表示規則に従います。プレイヤーレンダラーがEpic Fightのデフォルトメッシュへフォールバックした場合は、すべての標準レイヤーを利用できます。メイドアダプターはEFTLMの既存レイヤーとスケール補正を維持しつつ、同じスコープ付き手持ち品置換とモデル定義フォームlocator規則を適用します。

モデルの `all_cutout` プロパティは、対応する身体render typeの裏面カリングだけを変更し、shader、alpha、描画順ソート、materialの動作を維持します。outlineや未対応の拡張render typeは元の状態を維持します。`render_layers_first` が有効な場合、`ModelLayerOrder` が最終装着姿勢の公開後かつ身体ジオメトリの描画前に、既存の三人称レイヤーを正確に一度だけ実行します。不変の描画スナップショットとスコープ付きguardにより、レイヤーの入れ子描画が現在の身体姿勢を上書きすることを防ぎます。どちらのプロパティも既定値はfalseで、モデル転送とキャッシュにも保持します。

## 互換性警告

クライアントの読み込み時に、`YSMCompatibilityWarningFilter` は公式YSMのEpic Fight互換性警告だけを識別します。`YSMCompatibilityWarningState` が初回表示を既存のクライアント設定へ記録し、無関係な警告へ影響を与えずに次回以降の同じ警告を抑止します。

## クライアント設定

クライアント設定は `config/ysm_epicfight_compat/ysm_epicfight_compat-client.toml`、統合・専用サーバー共通のキャッシュ設定は同じ階層の `ysm_epicfight_compat-common.toml` に保存します。`CombatOverlayMixin` はYSM Mapping APIの管理済み `ysm.client.renderer.model_preview.render_player_overlay.method` キーを通じて公式YSMのオーバーレイメソッドへ到達し、各フレームを `CombatOverlayPolicy` へ委譲します。クライアント設定は、Epic Fightの戦闘モード中だけ左上のYSMプレイヤーオーバーレイを抑止します。値は各オーバーレイフレームで読み取るため、設定のライブ再読み込みは再起動せずに反映されます。

`common.models.useYsmHeldItemModels` と `common.animations.useYsmHeldItemSwitchAnimations` はどちらも初期状態で有効です。`common.models.exclusions.heldItemModelExclusions` と `common.animations.exclusions.heldItemSwitchAnimationExclusions` は独立したモデルIDテーブルで、値にはアイテムIDまたは `#item_tag` のselectorを指定します。一致したselectorは、対応するメイン設定が有効な場合にその機能を無効として扱います。メイン設定が無効な場合、除外対象がYSMの機能を有効化することはありません。`minecraft:air` を使うと空手への持ち替えを対象にできます。モデル独自の置換品とそのアニメーションは必ず手持ち品モデル方針を使用し、持ち替えアニメーション方針はEpic Fightが通常アイテムを維持する場合だけ使用します。

`common.models.useYsmProjectileModels` と `common.models.useYsmVehicleModels` も初期状態で有効です。`common.models.exclusions.projectileModelExclusions` と `common.models.exclusions.vehicleModelExclusions` は、モデルIDごとにエンティティIDまたは `#entity_type_tag` selectorを指定します。手持ち品テーブルと同様、一致したselectorは有効なメイン設定を無効化することだけができます。クライアントは認可された各投射物、釣り針、乗り物について解決済みの表示判定だけを送信し、トグルとテーブルはローカルに維持します。釣り針と、実際のモデル定義の弓・トライデントに制御される投射物は、これらの投射物ルールではなく手持ち品方針を使用します。

`common.animations.useYsmMovementAnimations` も初期状態で有効です。`common.animations.exclusions.movementAnimationExclusions` はモデルIDごとに、アニメーション節で列挙した移動状態名を指定します。指定した状態はメイン設定が有効な場合だけYSM移動アニメーションを無効とし、メイン設定が無効な場合に姿勢担当を有効化することはありません。`common.animations.useNaturalLadderAnimations` は独立して初期状態で有効で、YSMが担当するはしご状態では自然なはしご処理を要求します。実際に両手の構成を使うのは、選択した専用クリップに必要な腕動作があることを描画時に確認できた場合だけです。`ClientMovementAnimationPreferences` は、現在の正規化済みモデルID、意味上の移動状態、解決済みの姿勢担当bit、現在の自然なはしご要求bitを送信します。リモートプレイヤーの速度とクリエイティブ飛行能力だけではモデル使用者の状態を常に再構築できないため、`MovementAnimationPreferenceBroadcaster` がその結果を追跡クライアントへ中継します。`common.animations.useYsmMovementAnimations`、`common.animations.useNaturalLadderAnimations`、`common.animations.exclusions.movementAnimationExclusions` の値自体はローカルに残し、自然なはしご方針は任意のメイドアダプターには適用しません。

`common.animations.useYsmParCoolAnimations` と `common.animations.useYsmSwemAnimations` は独立して初期状態で有効です。既定では空の `common.animations.exclusions.parcoolAnimationExclusions`・`common.animations.exclusions.swemAnimationExclusions` テーブルへ、正規化済みモデルIDごとに `roll_front` や `jump_lv2` などの短いアクション名を指定します。`parcool:`・`swem:` 接頭辞は付けません。対応する `ModAnimationClips` の群で既知の名前だけを受け入れ、wildcard、アイテム、タグのselectorは使用できません。除外は有効な群を無効にするだけで、この2つの方針は通常の移動設定や乗り物モデル設定から独立しています。移動表示メッセージへは、現在のアニメーション群、正規の完全なクリップ名、解決済み姿勢担当bitも含めます。リモート描画では観測したnativeクリップと選択モデルの完全一致を要求し、古い判定や群だけの判定で別クリップを許可しません。トグル、除外テーブル、nativeの再生時計は、この互換通信では送信しません。これらの任意Mod方針はプレイヤーだけに適用し、メイド所有者の設定同期へは含めません。

プレイヤー描画では、モデル別ルールをすべてモデル使用者のクライアントだけに残します。`ClientHeldItemModelPreferences` は解決済みのメインハンド・オフハンドの置換表示と持ち替えアニメーションの真偽値だけを送信し、`HeldItemPreferenceBroadcaster` が追跡クライアントへ中継します。これにより、他プレイヤーの既定値、モデル別ルール、アイテムID、アイテムタグを受信せずに、全クライアントで同じ外観上の姿勢を再現します。

### メイド所有者の設定結果同期

所有者がいる対応メイドについて、サーバーは所有者のクライアントへ、プレイヤー描画と同じ3種類のクライアントローカル方針であるYSM手持ち品置換、YSM持ち替えアニメーション、YSM移動アニメーションをメイドの現在の入力元状態に対して解決するよう要求します。サーバーは認証済みの判定結果と上限付きの入力元状態fingerprintを、対象メイドを追跡中のクライアントへ中継します。所有者の設定トグル、モデル別除外テーブル、アイテムタグ規則は配信しません。所有者が不在、応答が古い、エンティティ・モデル・所有関係を再検証できない場合は、そのメイドのYSM置換・移動処理を無効として扱います。

手持ち品方針と移動方針は、別々の不透明epoch、source revision、pending要求、query、update、判定キャッシュ、リモートfingerprintを使用します。手持ち品fingerprintには所有者、メイド、正規化済みモデルID、両手のアイテムIDを含めます。手持ち品・持ち替え設定、それぞれの除外、クライアントのitem tag generationが変化した場合も、そのepochを更新します。移動fingerprintには所有者、メイド、正規化済みモデルID、サーバーが確定した意味上の移動状態を含め、そのepochは移動設定と除外だけで変化します。一方の方針の更新や応答が、もう一方のpending状態を無効化することはありません。

### Configuredの任意設定画面

Configured 2.2.3以降は任意です。文字列targetの `@Pseudo` Mixinで、Configuredが扱えない動的テーブルのleafを `ConfiguredHeldItemRules` へ置き換えます。設定画面はクライアントファイルの実際のTOML階層に従い、共通配下に次の分類を表示します。

| フォルダ | 設定 | 除外サブフォルダ |
| --- | --- | --- |
| アニメーション (`[common.animations]`) | 持ち替え、移動、ParCool、SWEM、自然なはしご | 持ち替え、移動、ParCool、SWEM |
| モデル (`[common.models]`) | 手持ち品、投射物、乗り物 | 手持ち品、投射物、乗り物 |

オーバーレイと警告の設定は `[client]`、クライアントキャッシュ設定は `[client.cache]`、別ファイルのサーバーキャッシュ設定は `[server.cache]` に属します。`[common.animations]` と `[common.models]` はクライアントファイル内のテーブルで、除外設定はそれぞれ `[common.animations.exclusions]` と `[common.models.exclusions]` に属します。7種類すべての除外エディターへ現在選択中のモデルIDを空の編集行として追加し、空の行は設定ファイルへ書きません。Configured APIへのリンクは任意統合の境界内へ限定します。Configuredがない場合は対象クラスを読み込まず、TOML設定は引き続き有効で、ゲーム内設定画面だけが利用できなくなります。

## ソース構成

| 領域 | パッケージ |
| --- | --- |
| モデルとテクスチャ入力 | `assets`, `assets.binary`, `geometry` |
| アニメーション、Molang、モデル内スクリプト、Controller、サウンド、パーティクル | `animation` |
| リグ対応、変換、キャッシュ | `mesh`, `cache` |
| Epic Fight描画とレイヤー | `render`, `render.layer`, `event`, `mixin` |
| 選択状態、ジオメトリ、モデル変数、スクリプトイベント、移動・Modアニメーション姿勢担当、盾防御通知、手持ち品表示、サブエンティティ表示の同期 | `network`, `network.geometry`, `network.message` |
| Touhou Little Maid・EFTLM任意アダプター | `integration.tlm`、一部の `mixin`・`network` クラス |
| ParCool・Epic ParCoolの任意姿勢参照とSWEM騎乗者アニメーション参照 | `integration.parcool`, `integration.swem` |
| Oculus/Iris任意LabPBRブリッジ | `integration.oculus` |
| クライアント設定、Configured任意統合、警告処理 | `config`, `integration.configured`, `compat` |
