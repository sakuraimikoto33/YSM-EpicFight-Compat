# Implementation details

[日本語](implementation.ja.md)

This document describes the implementation used by the Minecraft 1.20.1 Forge branch of YSM Epic Fight Compat.

## Rendering ownership

Official YSM remains responsible for normal player rendering. `CombatRenderInterceptor` takes ownership only when the player's Epic Fight patch reports `overrideRender()`. It asks Epic Fight to render the armature model and cancels the normal player pass for that frame, preventing duplicate models.

`CombatPlayerRenderer` is registered through Epic Fight's patched-renderer event. It behaves like Epic Fight's humanoid player renderer but replaces its mesh provider with a converted YSM mesh when one is ready. If conversion is pending or fails, the renderer retains Epic Fight's default mesh.

## Player selection

`PlayerSelectionNbt` reads the model identifier and texture name serialized by official YSM in the player's Forge capability data. On an integrated server, the client resolves the matching server player directly. On a dedicated server, `SelectionBroadcaster` sends bounded selection updates to clients that have this compatibility mod.

The selection channel carries identifiers only. It does not carry model packages or texture data.

## Model sources and parsing

`LocalModelRepository` looks up models in official YSM's model catalogs. It supports current folder models described by `ysm.json`, legacy flat folder models containing `main.json`, `arm.json`, and PNG textures, and `.ysm` packages. Folder geometry and animations are parsed from their Bedrock JSON representation. Package envelopes are opened in memory by `PackageEnvelopeDecoder`, then decoded into the same internal `ModelBundle` representation by `BinaryPackageParser`.

For every model other than the official primary `default` model, `OfficialDefaultAnimationLibrary` fills only missing animation names from the primary model assets in the installed official YSM mod. The primary-asset digest and inheritance revision participate in the local model-cache fingerprint, so a changed inherited animation invalidates the affected parsed cache instead of reusing stale clips.

Input sizes, counts, paths, hierarchy depth, and numeric values are checked at parsing and transfer boundaries. A failure is isolated to the selected model and causes normal fallback behavior.

## Mesh conversion

`SkinMeshCompiler` traverses the Bedrock bone hierarchy and produces the vertex arrays and indexed parts required by Epic Fight's `HumanoidMesh`. Bone pivots and rotations are accumulated before positions and normals are emitted. Vertices are deduplicated by position, normal, UV, and joint binding so UV seams remain distinct.

`HumanoidRig` reserves Epic Fight's 20 biped joints for a strict set of humanoid major bones. `AuxiliaryBoneLayout` assigns accessory bones parent-first to additional skinning indices, up to Epic Fight's 1,000-matrix limit. Each auxiliary bone is anchored to its nearest major joint while retaining its authored bind hierarchy.

## Animation and Molang runtime

`ParallelAnimationProgram` evaluates YSM animation data without writing into Epic Fight's animator. `AuxiliaryPoseMatrices` accepts Epic Fight's major-joint skin matrices as the combat seam, expands them to the converted model's complete bind hierarchy, and composes parallel, whole-model, and held-item layers. In the ordinary Epic Fight-owned path, compatible YSM auxiliary deltas and visibility scales are added around the live combat joints. A full-body owner instead evaluates the connected authored hierarchy so the root, torso, limbs, hair, skirt, tail, and other descendants cannot split across unrelated coordinate spaces.

`MovementAnimationType` resolves the configurable locomotion states `walk`, `run`, `sneak_idle`, `sneak_move`, `jump`, `creative_flight`, `elytra_flight`, `swim`, `water_idle`, `crawl_idle`, `crawl_move`, `ladder_idle`, `ladder_up`, and `ladder_down`. When the model owner's resolved policy enables the current state, `AutomaticAnimationSelector` selects the matching official YSM main clip and `ParallelAnimationProgram` evaluates its pre/main/post controller layers, matching hold layers, and parallel layers as one full-body composition. Movement clocks follow official loop behavior rather than restarting on every frame.

`EpicFightPoseOwnership` checks Epic Fight entity-state flags, exact action motions, visible main-frame and rebound animations, aiming, item use, swinging, hurt, and knockdown state. These actions revoke configured movement and item-switch ownership immediately; custom-bow use and release remain the separate model-authored action path described below. `MovementPoseTransition` blends ordinary movement-owner changes over three ticks from the exact previously displayed complete skin, but never delays an Epic Fight action. Creative flight applies the official body-yaw convention only while its YSM movement or item-switch pose owns the model; elytra flight and action transforms retain Epic Fight's outer orientation.

`AutomaticAnimationSelector` also retains YSM state, armor-condition, held-item condition, vehicle, and passenger clips. Mounted states use a complete-pose path so Epic Fight's riding pose is not applied a second time. Roulette clips retain their model-space root and body motion while official YSM remains responsible for roulette audio. Held-item clips follow the replacement, effect-only, and item-switch rules described below; an ordinary item remains in Epic Fight's item layer even when only its authored YSM switch pose is playing.

Animation clips retain loop or hold-on-last-frame playback, Molang `blend_weight`, keyframe interpolation, and chronological timeline dispatch across loop boundaries. Tracks on non-geometry Molang pseudo-bones are evaluated in source order for their variable side effects but never receive a pose matrix. Nested Molang functions use stacked argument frames, preventing an inner call from overwriting its caller's arguments.

`ExpressionEngine` implements the Molang operators needed by these clips, the supported official math functions and read-only queries, and YSM helpers such as `ysm.first_order`, `ysm.second_order`, and `ysm.perlin_noise`. Entity, equipment, item, biome, block, camera-distance, and animation-time inputs are exposed through read-only query paths. Ordinary variables accept both `v.*` and `variable.*`; persistent roaming variables accept both `v.roaming.*` and `variable.roaming.*`. Configuration-variable snapshots are synchronized for remote players so visibility and conditional animation state match the owning player.

## Model-authored held items

`CustomHeldItemPolicy` examines the model's automatic hold, use, and swing clips together with its default visibility. It treats a condition as a replacement only when that condition reveals a normally hidden renderable hand subtree, or when it hides the authored Tool locator for the clip while animating a renderable hand prop. The decision comes from model and animation semantics rather than a model-ID allowlist. A bow use clip that only reveals an effect, such as a magic circle, augments Epic Fight's retained bow and does not become a replacement by itself.

When a replacement is active, `ParallelAnimationProgram` evaluates only the authored prop roots and their required parent chain. The prop is rebased onto Epic Fight's live left or right Tool joint while preserving the displayed fist position and item-specific Epic Fight correction. `PatchedItemInHandLayerMixin` suppresses Epic Fight's item for that hand only inside the exact converted-mesh render scope. If no replacement is detected, the local policy disables it, or the renderer falls back to Epic Fight's default mesh, Epic Fight continues to render the item normally.

Item changes are observed per hand with the same damaged-stack and full-stack comparisons used by official YSM's held-item provider. A model-authored replacement shares the held-item model preference with its transition. If Epic Fight keeps the ordinary item, the independent item-switch animation preference decides whether the current YSM main state, matching hold clip, and active pre/hold/post controller layers temporarily form one full-body pose. The item remains rendered by Epic Fight; only its attachment transform follows the authored Tool locator. An ordinary main-hand bow mirrors that temporary hold path onto Epic Fight's off-arm Tool joint, while a custom YSM bow keeps its right-hand convention.

Epic Fight actions, roulette playback, custom full-body actions, and another complete-pose owner cancel an in-progress item-switch transition rather than suspending it. Entry and exit use the complete-skin transition path, and a Tool locator collapsed by the authored pose temporarily suppresses the corresponding ordinary item. The resolved replacement and switch-animation booleans are synchronized per hand; the model owner's item IDs, tags, main toggles, and exclusion tables are not transmitted.

Detected custom bows temporarily remain on the YSM-authored main-hand/right-hand attachment instead of being moved to Epic Fight's left-hand bow convention. During draw and release, the complete authored YSM body pose replaces the combat pose so partial arm overrides cannot detach the shoulders. Aim yaw is derived from the live Epic Fight model orientation, the one-shot release continues after Epic Fight's short rebound signal ends, and the saved final authored pose blends back to Epic Fight instead of switching in one frame. Effect-only bow geometry is attached to Epic Fight's left Tool joint without suppressing its bow or replacing the body pose.

`AttackAnimationSoundMixin` redirects only Epic Fight's attack-phase swing sound; hit and impact sounds are unchanged. `ServerAttackSoundRouter` preserves the attacking player, hand, exact Epic Fight sound, pitch, and sequence for each tracking client. A client suppresses that fallback only when the active converted model replaces the attacking item and its YSM timeline either plays or declares an authored attack-sound route. If no route becomes active after the bounded discovery delay, the original Epic Fight swing sound is played locally.

## Animation controllers and auxiliary outputs

`BedrockAnimationControllerParser` and `AnimationControllerProgram` implement the supported controller state machine: initial state selection, ordered transitions, animation weights, `on_entry`, `on_exit`, fixed or curved blending, and shortest-path rotation blending. State `variables` are evaluated into a frame-local overlay before the state's clips. A `remap_curve` is sorted by input, clamped to its endpoint values outside the declared range, and linearly interpolated between adjacent points.

Animation timelines and controller states can emit sounds without persisting model-local audio bytes. `ClientSoundOutput` resolves model-local audio through official YSM's in-memory sound cache using Mapping API contracts; namespaced Minecraft sound events are also accepted. Sounds are scoped to their clip or controller state, stopped when that scope exits, and cleared when the model or session is invalidated. Minecraft's sound engine owns pause and resume. Roulette audio remains owned by official YSM so the compatibility renderer does not start a duplicate stream.

Particles can be emitted with the `ysm.particle` and `ysm.abs_particle` Molang helpers or with Bedrock animation/controller `particle_effects`. Declarative entries retain `effect`, `locator`, `pre_effect_script`, and `bind_to_actor`. Entry-scoped particles are removed when their controller state exits, and bound particles follow the actor. Common humanoid locators use bounded body-relative approximations; an unknown locator falls back to the entity center because arbitrary model-bone locator matrices are not exposed to the particle engine.

## Molang evaluation scheduling

Scalar and vector expressions are compiled once when an `AnimationClip` is created. The compiler also records the variables, queries, functions, text arguments, and assignments used by each expression. This metadata controls both snapshot size and whether a pose evaluation can leave the render thread.

The local player and any expression with state changes, timeline output, text/world access, randomness, or another render-thread-only function remain synchronous. A side-effect-free remote-player pose can be evaluated from an immutable `SnapshotExpressionEnvironment` on a bounded daemon worker pool; the renderer keeps the last completed frame while newer work is pending. Distance LOD schedules those remote evaluations every frame within 16 blocks, at one-tick intervals from 16 to 32 blocks, two-tick intervals from 32 to 64 blocks, and four-tick intervals beyond 64 blocks. Stateful physics and output expressions therefore retain their ordering and are never made asynchronous merely because the player is distant.

## Texture resolution

`OfficialTextureResolver` obtains the selected texture from official YSM's in-memory texture cache. Access to the necessary YSM members is expressed through semantic YSM Mapping API keys and resolved as method handles at runtime; runtime-obfuscated names are not stored in this project.

Official YSM's texture location has first priority so the compatibility renderer follows YSM texture selection without registering the same GPU image twice. Every parsed or server-provided `ModelBundle` also retains bounded encoded texture data. If the official location is unavailable, `CombatMeshCache` decodes that data off-thread and performs bounded dynamic-texture registration on the render thread. Selecting an official texture releases only the duplicate GPU registration; the fallback source remains available until the corresponding memory-cache entry is evicted, so a later loss of the official cache does not leave the model untextured.

## Server-provided model data

If the client does not have a model selected by an online player, it requests that model from the dedicated server. `ServerModelTransfers` verifies that the model exists and is currently selected, parses it outside the server tick, and encodes it with `GeometryTransferCodec`.

The transfer contains bounded, compressed geometry, scale properties, retained animation clips, animation-controller data, and every declared texture required by the compatibility renderer. Clip timing, blend weights, timelines, controller variables, remap curves, sound references, particle declarations, texture count, individual texture size, and total texture size are encoded with explicit bounds. It never contains the original `.ysm` package or model-local audio bytes. Payloads are divided into bounded chunks; the client limits concurrent assemblies, total sizes, timeouts, hashes, and decompressed data before accepting a `ModelBundle`.

`ModelRequestMessage` may include the SHA-256 of a remote disk-cache entry. The server replies with full `DATA`, `UNCHANGED`, or `UNAVAILABLE` status. An `UNCHANGED` response only authorizes the matching payload from the current server namespace; a missing, corrupt, or mismatched entry is removed and requested again without a hash. The compatibility network protocol and serialized transfer format remain fixed at version `1` until the first public release.

Some official packages represent an animation with no finite declared end by using positive infinity. The encoder normalizes that declaration to zero; the runtime derives an effective duration from retained keyframes where possible. The decoder continues to reject non-finite values received from the network.

## Caching and reloads

`CombatMeshCache` converts models lazily on a bounded worker pool. Completed meshes and their fallback texture sources are session-local and kept in a least-recently-used cache controlled by `clientModelMemoryCacheSize`. Failed conversions are retried only after the model source changes. GPU resources and temporary texture leases are released when entries are evicted.

Persistent data is split below `config/ysm_epicfight_compat/cache`: `client` stores parsed local `ModelBundle` payloads, `remote` stores payloads validated against the current multiplayer server, and `server` stores generated transfer payloads. Client and remote entries use a compatibility-owned binary envelope with hashed names, a format marker, source or server-validation SHA-256, payload SHA-256, and compressed model data. They are not standalone PNG, JSON, or `.ysm` files. Server entries use the same integrity envelope without claiming content secrecy. No cache is encryption or DRM.

The independent `clientModelDiskCacheMiB`, `remoteModelDiskCacheMiB`, and `serverModelDiskCacheMiB` limits default to 64, 64, and 256 MiB. Each directory evicts its least-recently-used files independently. A zero limit disables reads and writes and removes existing entries during maintenance. An entry larger than its own limit remains usable in the current memory session but is not persisted. Disabling `serverModelDiskCacheEnabled` bypasses only the persistent server layer; the bounded session memory cache and per-model coalescing of simultaneous requests remain active.

Local and server cache entries are accepted only when their source-content digest still matches. A changed model is parsed and encoded once, then atomically replaces the previous entry. All writes use a temporary regular file followed by an atomic move where supported. Cache traversal refuses symbolic links, corrupt entries are removed, and file I/O, hashing, decoding, and eviction run outside the render and server tick paths.

Resource reloads, YSM model reload commands, disconnects, and server shutdowns invalidate the related selection, mesh, texture, and in-flight transfer state while keeping valid disk entries. Generation counters prevent work completed after invalidation from re-entering an active cache.

## First-person and equipment layers

`CombatFirstPersonMixin` selects the same converted mesh for Epic Fight's first-person renderer and applies its configured part visibility. `FirstPersonArmorGateMixin` suppresses the biped armor pass while that converted first-person mesh is active.

The patched third-person renderer suppresses armor, head equipment, and elytra for converted meshes because their biped attachment transforms are not valid for arbitrary model proportions. Capes, arrows, and bee stingers continue through Epic Fight's patched layers. Held items also continue through that layer unless the active model-authored rule and the model owner's resolved preference replace the item for that hand. All standard layers remain available when the renderer falls back to Epic Fight's default mesh.

## Compatibility warning

During client load, `YSMCompatibilityWarningFilter` identifies only official YSM's Epic Fight compatibility warning. `YSMCompatibilityWarningState` records its first display in the existing client preferences so subsequent launches suppress that warning without affecting unrelated warnings.

## Client configuration

The client config is stored at `config/ysm_epicfight_compat/ysm_epicfight_compat-client.toml`; the global integrated/dedicated-server cache config is stored beside it as `ysm_epicfight_compat-common.toml`. `CombatOverlayMixin` delegates each official-YSM overlay frame to `CombatOverlayPolicy`. The client option suppresses the top-left YSM player overlay only while Epic Fight battle mode is active and is read for every overlay frame, so a live configuration reload takes effect without restarting.

`useYsmHeldItemModels` and `useYsmHeldItemSwitchAnimations` both default to enabled. `heldItemModelExclusions` and `heldItemSwitchAnimationExclusions` are independent model-ID tables whose values are item IDs or `#item_tag` selectors. A matching selector disables its behavior while the corresponding main setting is enabled. An exclusion never enables YSM behavior while the main setting is disabled. `minecraft:air` can target a switch to an empty hand. A model-authored replacement and its animation always use the held-item model policy; the switch-animation policy applies only while Epic Fight retains the ordinary item.

`useYsmMovementAnimations` also defaults to enabled. `movementAnimationExclusions` maps each model ID to any of the movement state names listed in the animation section. A listed state disables YSM movement only while the main setting is enabled and cannot enable movement ownership while that setting is disabled. `ClientMovementAnimationPreferences` sends the current normalized model ID, semantic movement state, and resolved ownership bit. `MovementAnimationPreferenceBroadcaster` relays that result to tracking clients because remote velocity and creative-flight ability are not authoritative enough to reconstruct every owner's movement locally.

All model-specific rules remain on the model owner's client. `ClientHeldItemModelPreferences` sends only the resolved main-hand and off-hand replacement and switch-animation booleans, and `HeldItemPreferenceBroadcaster` relays them to tracking clients. Observers therefore reproduce the cosmetic pose decision without receiving another player's defaults, model rules, item IDs, or item tags.

Configured 2.2.3 or later is optional. String-targeted `@Pseudo` mixins replace only Configured's unsupported dynamic table leaves with `ConfiguredHeldItemRules`, keeping all Configured API linkage inside that optional integration boundary. The held-item replacement, item-switch animation, and movement exclusion editors appear under the regular Client folder and add the currently selected model ID as an empty editable row; empty rows are not written. When Configured is absent, those target classes are never loaded and only the in-game settings screen is unavailable.

## Source layout

| Area | Package |
| --- | --- |
| Model and texture input | `assets`, `assets.binary`, `geometry` |
| Animation, Molang, controllers, sound, and particles | `animation` |
| Rig mapping, conversion, and cache | `mesh`, `cache` |
| Epic Fight rendering and layers | `render`, `render.layer`, `event`, `mixin` |
| Selection, geometry, model variables, movement ownership, and held-item display synchronization | `network`, `network.geometry`, `network.message` |
| Client preferences, optional Configured integration, and warning handling | `config`, `integration.configured`, `compat` |
