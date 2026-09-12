# Implementation details

[日本語](implementation.ja.md)

User-facing options, defaults, ranges, and exclusion syntax are listed in [README: Configuration](../README.md#configuration).

Maid integration is unavailable for this 1.21.1 target. Maid-specific descriptions below document the retained, inactive adapter code.

## Rendering ownership

Official Yes Steve Model (YSM) owns normal player rendering. When Epic Fight's player patch enables `overrideRender()`, `CombatRenderInterceptor` routes the player through Epic Fight's patched renderer and cancels the normal pass. Epic Fight's vanilla-model debugging mode is excluded.

`CombatPlayerRenderer` supplies a converted YSM `HumanoidMesh` through Epic Fight's patched-renderer API. `CombatMeshResolver` uses the player's selected model and texture; a missing, pending, or failed conversion leaves Epic Fight's default mesh available. The first-person renderer uses the same model resolver.

YSM's obfuscated members are accessed through semantic YSM-Mapping-API contracts and source aliases. The Mapping API is a separate required dependency. Optional integrations use scoped adapters, reflection, and string-targeted `@Pseudo` mixins so an absent optional mod does not enter ordinary class linkage.

## Model loading and input boundaries

`LocalModelRepository` resolves models from official YSM's catalogs. Supported inputs are manifest folders containing `ysm.json`, flat folders containing `main.json`, `arm.json`, and PNG textures, and `.ysm` packages. Folder Bedrock JSON and in-memory package decoding converge on `ModelBundle`.

Legacy `YSGP` V2 packages use `V2PackageDecoder` to validate the envelope checksum and decode bounded named assets in memory. Their geometry, scales, animations, and player textures follow the same rules as flat folders; missing animations still inherit the official defaults. The format follows the [official legacy reader](https://github.com/YesSteveModel/LgeacyYSM/blob/1.20.1-forge/src/main/java/com/elfmcys/yesstevemodel/util/YesModelUtils.java). V3 envelopes retain `V3PackageDecoder` and `V3BinaryPackageParser`. Both paths use the existing parsed-model cache and `GeometryTransferCodec` transfer. V2 cache digests include decoded asset names and contents, independent of entry order and randomized encryption material.

A bundle retains geometry, model scale, animations, controllers, Molang function sources, declared textures, and the `merge_multiline_expr`, `all_cutout`, and `render_layers_first` properties. Base textures can include LabPBR normal and specular companions.

JSON box UVs retain geometrically nonzero faces even when a subpixel cube dimension rounds down to a zero-width or zero-height texture rectangle. Such a face samples a texture point or row; it does not hide the cube. Explicit per-face zero `uv_size` still omits that authored face. Folder and V2 source digests invalidate caches made before this correction; V3 fingerprints and transfer semantics are unchanged.

Model functions are read from `files.function_path` (default `functions`) or package function entries. `ModelFunctionAssets` checks canonical names and UTF-8 source with limits of 4,096 functions, 1 MiB per source, and 16 MiB total.

`OfficialDefaultAnimationLibrary` fills missing animation names in non-primary models from the installed official primary `default` assets. A model's own clip wins. Local cache fingerprints include the relevant source content, inherited assets, and parsing properties.

Parsing and transfer boundaries validate paths, sizes, counts, hierarchy depth, and numeric bounds before allocating or accepting data. Invalid input is isolated to that model and follows the renderer's fallback path. Original packages and model-local audio bytes are not written into compatibility caches or sent in model transfers.

## Mesh conversion and pose composition

`SkinMeshCompiler` accumulates Bedrock bone transforms and emits indexed vertex data for Epic Fight's `HumanoidMesh`. Deduplication includes position, normal, UV, and joint binding, preserving UV seams.

`HumanoidRig` maps supported bone roles to Epic Fight's 20 humanoid joints. `AuxiliaryBoneLayout` assigns parent-first model-bone skinning indices within a total 1,000-pose limit, providing up to 980 independent model-bone entries. On the humanoid path, `AllHead` is chest-anchored; head skinning begins at `MHead` or `Head`.

`RigBindingPlan` separates humanoid combat bindings from connected model-authored subtrees by geometry structure. Eligible non-humanoid roots and body branches with hand/Tool chains under the head can retain their authored hierarchy alongside a humanoid combat branch. Ambiguous structures keep the fallback binding; this is bounded structural detection, not a universal animal rig mapper or a user-configurable rig profile.

`ParallelAnimationProgram` evaluates model animation separately from Epic Fight's animator. `AuxiliaryPoseMatrices` composes its results with the live Epic Fight skin matrices:

| Pose domain | Composition |
| --- | --- |
| Epic Fight-owned humanoid | Combat major joints with compatible auxiliary deltas and visibility scales. |
| YSM-owned full body | One connected authored pose covering root, body, limbs, and their descendants. |
| Authored subtree | Model-space hierarchy, or one external Epic Fight attachment anchor. |
| Model-authored held prop | Required authored parent chain rebased onto the applicable live Tool joint. |

Shared ancestor expressions are sampled once for the connected pose domains. Structural pose-only sampling preserves expression assignments and nested function dataflow while suppressing sounds, particles, and script-sync outputs.

## Animation selection and transitions

`AutomaticAnimationSelector` selects main, held-item, equipment-condition, state, vehicle, passenger, and roulette clips. Playback supports one-shot, loop, hold-on-last-frame, Molang blend weights, step/linear/Catmull-Rom interpolation, and chronological timeline dispatch across loop boundaries.

`MovementAnimationType` recognizes walk, run, sneak idle/move, jump, creative flight, elytra flight, swim, water idle, crawl idle/move, and ladder idle/up/down. The owner's resolved policy determines whether the selected state may use YSM's full-body composition. Its pre/main/post, hold, equipment, and parallel layers are evaluated together.

Swimming, crawling, and ladder detection follow that priority. Crawl uses rendered walk speed; ladder direction uses the current-versus-previous-tick Y position. Dedicated ladder selection uses `ladder_up`, `ladder_down`, or `ladder_stillness`, with idle fallback.

`EpicFightPoseOwnership` gives combat actions priority over configurable movement and ordinary item-switch poses. It considers entity state, actual animations, aiming, item use, swinging, hurt, and knockdown. Passive `CLIMB` movement locks alone do not disqualify a YSM ladder pose. Entry into ordinary YSM movement blends from the last displayed complete skin over three ticks; Epic Fight actions take ownership immediately.

The renderer applies the body orientation required by YSM-owned flight, crawl, and ladder poses and avoids applying Epic Fight's swimming pitch twice. Head tracking respects authored axes: swim and water-idle do not add camera tracking, ladder adds only unauthored pitch, and supported land/flight states add only unauthored axes. Epic Fight-owned actions retain Epic Fight's orientation.

Natural ladder handling requires a dedicated YSM ladder clip with authored left-arm motion. It removes the matching HOLD layer, mirrors a missing right-arm subtree where needed, stows ordinary items, and hides active custom held props without drawing duplicate Epic Fight items. Models without suitable ladder arm motion use the ordinary HOLD composition.

YSM-owned mounted poses use a complete-pose path. Disabling YSM vehicle rendering also disables the corresponding vehicle/passenger clips and passenger-locator adjustment. Optional SWEM rider animation has its own independent policy.

Roulette playback retains authored root/body motion and follows official playback state. Player roulette audio belongs to official YSM; the compatibility runtime supplies that audio for the supported maid path.

## Molang, controllers, and model scripts

`ExpressionEngine` implements supported Molang operators, math functions, read-only entity/world queries, and YSM helpers including `ysm.first_order`, `ysm.second_order`, and `ysm.perlin_noise`. Model pseudo-bones without geometry can execute expression side effects without receiving pose matrices.

Ordinary variables accept `v.*` and `variable.*`; roaming variables accept `v.roaming.*` and `variable.roaming.*`. Remote players receive configuration-variable snapshots. Supported maids use their own entity queries and model-local variables, without inheriting their owner's YSM configuration or roaming state.

`DisplayedBoneQueries` publishes completed-frame model-space snapshots for `ysm.bone_rot`, `ysm.bone_pos`, `ysm.bone_scale`, and `ysm.bone_pivot_abs`. Queries read the previous completed pose by exact bone name; rotations are degrees and positions are authored model units. First-person view transforms are excluded.

`ysm.ground_speed2` reads official YSM's final result through Mapping API from an available client player context; unavailable contexts and non-player entities return zero. `ysm.in_shield_block_cooldown` follows an identity-checked successful-block notification for five entity ticks, not shield use alone. Typed entity-reference `->` queries expose read-only referenced-entity data, not that entity's scripts or writable variables.

`AnimationControllerProgram` implements initial states, ordered transitions, weighted clips, `on_entry`/`on_exit`, fixed or curved blending, shortest-path rotation blending, and frame-local state variables with remap curves. Empty states can chain within a bounded step with cycle checks.

`ControllerOrder` composes pre-parallel, main, hold, swing, use, passenger, remaining controllers, and parallel phases, including supported pre/post layers. Names sort lexicographically inside each phase. Supported top-level pre/post and parallel groups accept dynamic suffixes; parallel slots 0–7 are also recognized.

`MolangScriptRuntime` compiles `fn.<name>` functions and `@player_init`, `@player_update`, `@sync`, and supported `@player_ctrl_<slot>` handlers. Initialization precedes update and queued sync callbacks. Nested calls use stacked argument frames with a depth limit of 32; scripts run in the Molang evaluator, not as JVM or operating-system code.

Controller handlers can select/reset animations, request reloads, and set beginning transitions, returning the supported continue/stop/pause/bypass state constants. Authored Bedrock controllers take precedence unless their `ysm-builtin` state delegates to an automatic provider. Beginning transitions blend saved poses while retaining independent playback and event clocks.

`ysm.sync` transmits at most 16 finite numeric arguments from the local player's selected model. `ServerScriptEvents` validates sender selection and rate limits before relaying an authenticated snapshot to the sender and tracking clients. Identity, model, sequence, queue, and expiry checks reject stale events; the sender waits for that reply rather than executing a local echo.

## Sounds, particles, and preview isolation

`ClientSoundOutput` resolves model-local sound references through official YSM's in-memory sound cache and accepts namespaced Minecraft sound events. Clip/controller scopes stop their sounds on exit, and invalidation clears active outputs. The Minecraft sound engine owns pause/resume.

`ClientParticleOutput` supports `ysm.particle`, `ysm.abs_particle`, and declarative animation/controller `particle_effects`, including locators, pre-effect scripts, and actor binding. Entry-scoped effects end with their controller state. Common humanoid locators use body-relative approximations; unknown locators fall back to the entity center.

`AttackAnimationSoundMixin` routes Epic Fight's attack-phase swing sound independently of hit/impact sounds. A converted model suppresses the fallback only when it replaces the attacking item and provides an active authored attack-sound route. Otherwise the original swing sound is played after bounded route discovery.

`InventoryRenderScope` claims only the matching primary third-person inventory preview. World and preview clocks, variables, controller state, bone queries, cached poses, and transitions are separate. Preview scripts see `ysm.rendering_in_inventory` but cannot emit world sounds, particles, roaming writes, or script-sync messages.

## Evaluation scheduling and secondary motion

`CompiledAnimationTrack` stores immutable keyframe sampling arrays. Ordered tracks use binary search; unordered input retains its source order. Each evaluation scratch owns a bounded exact-time numeric XYZ cache. Only finite-time, multi-key, expression-free samples are reusable; expression evaluation and side effects are not memoized by that cache.

`AnimationControllerProgram` retains lazily compiled expression handles for its own lifetime, including expressions reached after the global compiler cache is full. Runtime resets keep those handles; each entity still evaluates values, transitions, and callbacks in the original order with their original side effects. Variable-slot identities are unchanged.

`AnimationEvaluationRateLimiter` controls complete compatibility animation evaluations per entity/render context. `client.animationEvaluationRateLimitHz` accepts 30–240 Hz or `0` (unlimited). It is not a game FPS cap: intervening draws reuse completed YSM evaluation outputs while composing against live Epic Fight joints.

Deadlines retain their phase and coalesce missed admissions without replaying a batch of intermediate samples. Elapsed animation time advances to the admitted sample. Context changes, item changes, restarted clips, pending script-sync callbacks, and relevant transition endpoints can force an evaluation. World/preview state and incompatible view or pose-owner contexts do not share stale evaluation results.

Animation clocks remain monotonic across partial-tick changes unless the entity's tick clock resets. Stateful auxiliary filters retain their state across admitted samples and use elapsed time with bounded integration steps.

For a capped model rendered in the current or preceding tick, the next draw supplies the model-orientation basis for secondary motion; the maintenance tick does not alternate that input with a different tick-only basis. After a missed render tick, tick-driven output updates resume using the same timeline and physics state.

The local player, inventory previews, scripts, stateful/output expressions, and other render-thread-dependent paths are synchronous. Eligible side-effect-free remote-player or maid pose work uses an immutable `SnapshotExpressionEnvironment` on a bounded daemon pool. Only one evaluation is pending per runtime state; rendering can retain its last completed result.

Distance scheduling further limits eligible remote work:

| Camera distance | Minimum scheduling interval |
| --- | --- |
| Up to 16 blocks | No additional distance interval |
| Over 16, up to 32 blocks | 1 tick |
| Over 32, up to 64 blocks | 2 ticks |
| Over 64 blocks | 4 ticks |

The evaluation-rate setting and remote-distance scheduling are independent. `0` disables only the configurable rate limiter.

## Held items and equipment

`CustomHeldItemPolicy` detects model-authored replacements from geometry visibility and hold/use/swing semantics, not a model-ID allowlist. A replacement reveals a renderable hand prop or hides its Tool locator while animating that prop. Effect-only bow geometry can augment an ordinary bow without replacing it.

`PatchedItemInHandLayerMixin` suppresses only the replaced hand inside the exact converted-mesh scope. Custom props follow the applicable Tool attachment and item corrections. Detected custom bows retain their authored right-hand convention and use complete-body draw/release poses, then blend back from the saved final authored pose.

Ordinary items stay in Epic Fight's item layer. Independently configurable YSM item-switch animations temporarily compose the main/hold/controller pose and update the item attachment. Combat actions or another complete-pose owner cancel that transition. A YSM movement HOLD composition can align an ordinary item without dispatching that HOLD clip's timeline.

`HandLocatorSelection` supports authored-form mouth, paw, or tail Tool attachments in third person. Exactly one visible, non-collapsed candidate is required; ambiguous/invalid candidates do not override the item, and an entirely hidden candidate set suppresses it. Logical-hand mapping and per-item scopes prevent a two-handed renderer from moving the other hand's attachment.

During Epic Fight actions, each item draw receives Tool positions aligned with the displayed hands while retaining the Tool's animation rotation, scale, and independent motion. The same temporary pose view serves renderers that use the layer's pose argument and those that read the armature again, including skinned addon weapons. This view ends with the item draw, preserves non-Tool joints, and keeps authored-form attachments as the first choice.

Armor and head-equipment layers are suppressed for converted player meshes. `ConvertedElytraLayer` renders elytra at one unambiguous usable `ElytraLocator`; otherwise it stays hidden. Capes, arrows, bee stingers, and ordinary held items use Epic Fight's patched layers. Their attachments follow the displayed skeleton on non-action frames and YSM-owned full-body actions; Epic Fight-owned actions retain Epic Fight attachments outside the scoped held-item Tool paths.

First person uses the converted mesh's part visibility and suppresses its biped armor pass. Epic Fight's default mesh retains its ordinary equipment layers whenever conversion fallback is active.

## Textures, materials, and skinning

`OfficialTextureResolver` prefers official YSM's selected in-memory texture location. `CombatMeshCache` retains encoded fallback sources, decodes them off-thread, and queues bounded render-thread GPU registration when no official location is available or an invalid Iris normal/specular companion is detected. During PBR recovery, the current official base texture remains selected until the matching fallback has uploaded. Once recovery is no longer needed, adopting the official texture releases duplicate GPU registration without discarding the fallback source.

Geometry directly attached to case-sensitive `ysmGlow*` bones is emitted as a separate full-bright submesh. Descendants do not inherit that marker automatically. Base and glow geometry share animation, visibility, texture, and material selection.

`all_cutout` changes back-face culling on supported body render types while preserving shader, transparency, sorting, and other material state. Outline and unsupported extended render types retain their own state. `render_layers_first` renders third-person layers once before body geometry, after final attachment poses are available. Immutable draw snapshots protect the outer pose during nested layer rendering.

`CompatHumanoidMesh` uses Epic Fight's live compute-skinning setting and available compute setup. It checks the actual composed pose count plus each base/glow submesh's part count against Epic Fight's palette capacity. With Iris or nonpersistent buffer uploads, each submesh must also fit the 256-part visibility limit. That additional part limit does not apply when persistent mapping is active without Iris. Both submeshes use the same capacity decision. An unavailable or over-capacity compute path falls back to Epic Fight's triangle-based CPU skinning.

The optional `OculusPbrBridge` registers a loader for `CompatPbrTexture` in Iris Shaders through a supported Iris PBR API namespace. Normal/specular companions can be recreated after shader texture reloads. Missing APIs, loader failures, or unusable companions leave the base texture available.

## Selection and display-policy synchronization

`PlayerSelectionNbt` reads official YSM's model/texture selection from serialized NeoForge player attachment data. The integrated-server path resolves the server player directly; `SelectionBroadcaster` supplies dedicated-server selection updates to compatible clients. This selection message carries identifiers, not model payloads.

Player model-specific rules are resolved on the owner's client. The compatibility channels send resolved per-hand replacement/item-switch decisions and movement/mod-animation ownership, not the owner's option values or exclusion tables. Movement messages carry the semantic state; optional-mod authorization includes the exact clip family and name.

Official YSM owns its custom projectile, fishing-hook, and vehicle geometry. Scoped renderer gates decide whether to retain that rendering in Epic Fight battle mode; outside battle mode they leave it untouched. The passenger-locator gate uses the vehicle decision as well.

Steady model-authored bow/trident replacements use the held-item policy for their projectiles. Other projectile-only geometry and crossbow projectiles use the projectile policy. Fishing hooks follow the fishing-rod held-item policy; vehicles use their separate policy.

The server records a projectile launch snapshot of owner, model, source item, and entity type. Vehicles follow their current first player passenger. `SubEntityPreferenceBroadcaster` asks the owner to resolve the applicable rule and authenticates the reply against its owner epoch and source revision. Unknown or stale battle-mode decisions return rendering to Epic Fight or vanilla.

For supported owned maids, the server separately requests the owner's held-item/item-switch and movement decisions against the maid's current state. Independent epochs and source fingerprints isolate those policy channels. Missing owners or invalid/stale responses do not authorize YSM replacements or movement ownership.

## Server model transfers

A client missing a selected model can request it from the dedicated server for an online player or supported synchronized maid. `ServerModelTransfers` checks source entity ID/UUID, tracking relation, and current selection both before work and before delivery. Parsing/encoding happens outside the server tick, with bounded requests, pending work, and recipient data volume.

Chunk slicing and payload preparation run on the sender workers. On 1.20.1 Forge, those workers also serialize the channel message, and each recipient gets an independent read-only view of the prepared wire bytes. On 1.21.1 NeoForge, recipients share the immutable prepared payload and each connection encodes it on its network thread. The server tick submits at most 16 DATA chunks (8 MiB of payload) per tick and rechecks the same connection, tracking relation, and selection immediately before each submission.

Preparation and pending delivery share a 32-transfer limit and a byte budget for the compressed source, prepared payload, and framing. That budget admits one maximum-size model; it does not include decoded models, the separate session cache, or the connection's outgoing queue. Clearing invalidates unfinished preparation but keeps it charged until the worker finishes. When capacity is unavailable, the client's existing retry path can resume the request. Network and persistent-cache formats are unchanged.

`GeometryTransferCodec` sends compressed geometry, scale/render properties, animation/controller data, Molang functions, sound/particle references, and declared base/PBR textures. It does not send the original package or model-local audio bytes.

The client reserves capacity before disk lookup or a network request. At most two reservations cover lookup, receipt, decoding, unconsumed results, and conversion through its client-side publication or disposal after GPU initialization. A full queue waits for capacity instead of clearing completed results; handing a model to the conversion worker does not free its reservation. Cancelled workers keep their reservation until completion, including across reloads and reconnects.

The client records bounded demand (256 model IDs and 1,024 entity sources), shares a result between sources selecting the same model, and removes demand when its last source leaves or remains unseen for 30 seconds. Waiting requests retry within the same reservation after five seconds; retries do not extend the 30-second idle-transfer deadline, while incoming DATA progress does. Each reservation charges one maximum-sized compressed payload and one working copy, for a total compressed-byte budget of 256 MiB across two reservations; expanded Java objects and the process heap are separate from that budget.

The client checks chunk counts, concurrent assemblies, sizes, timeouts, SHA-256, and expanded payload limits before accepting a bundle. Infinite source animation-duration declarations are encoded as zero; effective duration can come from retained keys, while network decoding rejects non-finite values.

Requests may include a remote-cache payload SHA-256. Replies are `DATA`, `UNCHANGED`, or `UNAVAILABLE`. `UNCHANGED` authorizes only matching cached content in the current server namespace; unusable entries require a full request.

## Caches and lifecycle

`CombatMeshCache` lazily converts models on a bounded worker pool. Its memory layer holds completed meshes, encoded fallback sources, and animation runtimes for the session; GPU resources follow that lifecycle. Parsed model payloads can also be persisted separately. Memory retention uses a configurable model-count target; eviction releases the corresponding resources. A failed local conversion with an unchanged source metadata stamp is not repeatedly submitted; a changed stamp or invalidation allows reconsideration. The existing retry condition is retained.

Persistent data is separated under `config/ysm_epicfight_compat/cache`:

| Directory | Contents and reuse condition |
| --- | --- |
| `client` | Parsed local model payloads validated against source content. |
| `remote` | Remote payloads namespaced by server and revalidated by server digest. |
| `server` | Generated transfer payloads validated against server-side source content. |

Entries use hashed filenames and a versioned binary envelope containing integrity digests and compressed payloads. Textures remain inside that envelope, not standalone PNG/JSON/package files. These caches provide integrity checks, not encryption or DRM.

Each persistent layer has its own size limit and least-recently-used eviction. Zero-size enabled caches operate without disk reads/writes and remove entries during maintenance. Disabling server persistence bypasses that disk layer without deleting its files; session caching and coalescing of simultaneous model requests remain active. Oversized entries can still be used in memory.

Writes use temporary regular files and atomic replacement where supported. Cache paths reject symbolic links; corrupt entries are removed. Bulk model parsing, content hashing, decoding, and cache maintenance use worker paths. Source-existence checks and retry metadata checks also occur when initiating work; filesystem access is not completely absent from the calling thread.

Resource/model reloads and session shutdown invalidate affected selection, mesh, texture, animation, and in-flight transfer state. Generation checks reject late worker results. Valid persistent entries remain subject to their normal reuse checks.

## Optional adapters and client UI

| Integration | Implemented boundary |
| --- | --- |
| Touhou Little Maid + EpicFight_TouhouLittleMaid | Both mods are required for the maid adapter. It reads the registered maid's public synchronized YSM selection and restricts mesh replacement to EFTLM's patched renderer. Missing conversions retain EFTLM's mesh. |
| ParCool | Reads supported player native action state and clocks without advancing its animator; selects available `parcool:*` model clips. |
| Epic ParCool | Reserves its specific Chain movement and Wall movement animations so those poses retain their own priority. |
| SWEM | Reads the active rider-animation layer for a direct player passenger of a live SWEM horse and selects available `swem:*` clips. |
| Iris Shaders (Iris PBR API) | Supplies the optional fallback-texture LabPBR bridge described above. |
| Configured | Supplies dynamic exclusion-table editors and the animation evaluation-rate slider; TOML settings also work without its screen. |

The maid adapter compensates EFTLM's `0.8` model scale with a mesh-local `1.25` factor and a matching held-item translation adjustment after a converted draw. Retargeting handles extension-only armature subtrees without assigning them converted humanoid indices; malformed overlapping extensions are rejected. EFTLM retains its equipment layers.

ParCool/SWEM adapters apply only to players, including remote players. Matching usable model clips and owner authorization are required. Native clocks and restart identity drive playback; SWEM riding takes precedence over stale ParCool state. These adapters change the displayed pose, not movement, hitboxes, the gameplay armature, or the horse renderer. Epic Fight combat actions and other higher-priority authored actions retain ownership.

`ClientPreferences` owns client-local appearance and scheduling policy. `ServerPreferences` owns the separate common-file server cache settings. Configured's slider runs from 30 through 240 Hz with unlimited at the rightmost position, stored as `0`; exclusion editors add an editable selected-model row and omit empty rows when saving.

`ClientPreferences` compares each rule table with an independent immutable snapshot and reuses its decoded map while the content is unchanged. Structural comparison still runs, so direct edits to the same Config/List remain visible on the next getter call. Setters, TOML reloads, invalid-input fallback, and optional-mod defaults retain their behavior; oversized tables/lists are rejected by the existing decoders without first copying their contents. Unloaded configuration is not retained as a loaded snapshot.

`CombatOverlayPolicy` applies the configured YSM overlay visibility during battle mode. `YSMCompatibilityWarningState` records acknowledgment of official YSM's Epic Fight warning without suppressing unrelated warnings. `ClosingScreenClickPolicy` consumes otherwise unhandled clicks that close a screen before they reach gameplay input.

## Scope and verification

Supported rendering for this target is limited to official YSM player models. The implemented Molang/query/controller subset, rig analysis, particle locators, equipment attachment rules, and optional API boundaries determine whether a model feature can be reproduced. Missing model features or optional APIs use the documented fallback paths rather than guaranteeing identical output for every model.

Compilation, automated tests, and distribution checks establish their respective code/package properties. They do not establish gameplay results; animation appearance, first person, equipment, shader behavior, reloads, and multiplayer still require in-game verification.

## Source layout

Package names below are relative to `src/main/java/net/okitsu/ysmepicfightcompat`.

| Area | Packages/classes |
| --- | --- |
| Model input and texture lookup | `assets`, `assets.binary`, `geometry` |
| Animation, Molang, controllers, scripts, outputs | `animation` |
| Conversion, pose composition, draw dispatch | `mesh` |
| Persistent model payloads | `cache` |
| Player rendering, layers, input, lifecycle | `render`, `render.layer`, `event`, `input`, `mixin` |
| Selection, display policy, script events, model transfer | `network`, `network.geometry`, `network.message` |
| Optional adapters | `integration.tlm`, `integration.parcool`, `integration.swem`, `integration.oculus` |
| Configuration and warning handling | `config`, `integration.configured`, `compat` |
| Official-YSM semantic aliases | `ysmref`; Mapping API requests in `src/main/resources/META-INF/ysm-mapping-api/requests-v1.json` |
