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

Input sizes, counts, paths, hierarchy depth, and numeric values are checked at parsing and transfer boundaries. A failure is isolated to the selected model and causes normal fallback behavior.

## Mesh conversion

`SkinMeshCompiler` traverses the Bedrock bone hierarchy and produces the vertex arrays and indexed parts required by Epic Fight's `HumanoidMesh`. Bone pivots and rotations are accumulated before positions and normals are emitted. Vertices are deduplicated by position, normal, UV, and joint binding so UV seams remain distinct.

`HumanoidRig` reserves Epic Fight's 20 biped joints for a strict set of humanoid major bones. `AuxiliaryBoneLayout` assigns accessory bones parent-first to additional skinning indices, up to Epic Fight's 1,000-matrix limit. Each auxiliary bone is anchored to its nearest major joint while retaining its authored bind hierarchy.

For every frame, `AuxiliaryPoseMatrices` copies Epic Fight's major skin matrices without modification and appends the composed auxiliary matrices. `ParallelAnimationProgram` evaluates `pre_parallel` before `parallel`; position, rotation, and scale transforms are applied only to auxiliary bones. Tracks on non-geometry Molang pseudo-bones are still evaluated in source order for their variable side effects, but can never receive a pose matrix. Nested Molang functions use separate stacked argument frames, so an inner call cannot overwrite its caller's arguments. Timeline events remain chronological across a loop boundary: tail events run before the next cycle's head events. Each clip's Molang `blend_weight` scales its contribution. Rotation channels from all parallel clips accumulate on the authored bind rotation, while the later, higher-priority clip replaces position and scale channels. Tracks on major bones never replace Epic Fight's pose. Scale tracks may still hide a major bone and its descendants, which preserves model variant visibility without moving the combat rig.

## Texture resolution

`OfficialTextureResolver` obtains the selected texture from official YSM's in-memory texture cache. Access to the necessary YSM members is expressed through semantic YSM Mapping API keys and resolved as method handles at runtime; runtime-obfuscated names are not stored in this project.

For a model available locally, `CombatMeshCache` can retain package texture bytes as a session-only fallback when the official cache has not supplied a texture yet. Decoding runs off-thread, while bounded texture registration runs on the render thread. No converted texture or mesh is written to disk.

## Server-only model geometry

If the client does not have a model selected by an online player, it requests that model from the dedicated server. `ServerModelTransfers` verifies that the model exists and is currently selected, parses it outside the server tick, and encodes it with `GeometryTransferCodec`.

The transfer contains bounded, compressed geometry, scale properties, and automatic animation clips, including their blend weights. It never contains a `.ysm` package or texture bytes. Payloads are divided into bounded chunks; the client limits concurrent assemblies, total sizes, timeouts, and decompressed data before accepting a `ModelBundle`. Official YSM remains responsible for providing and caching the corresponding multiplayer texture on the client.

Some official packages represent an animation with no finite declared end by using positive infinity. The encoder normalizes that declaration to zero; the runtime derives an effective duration from retained keyframes where possible. The decoder continues to reject non-finite values received from the network.

## Caching and reloads

`CombatMeshCache` converts models lazily on a bounded worker pool. Completed meshes are registered in memory and kept in a configurable least-recently-used cache. Failed conversions are retried only after the model source changes. GPU resources and temporary texture leases are released when entries are evicted.

Resource reloads, YSM model reload commands, disconnects, and server shutdowns invalidate the related selection, mesh, texture, and transfer state. Generation counters prevent work completed after invalidation from re-entering an active cache.

## First-person and equipment layers

`CombatFirstPersonMixin` selects the same converted mesh for Epic Fight's first-person renderer and applies its configured part visibility. `FirstPersonArmorGateMixin` suppresses the biped armor pass while that converted first-person mesh is active.

The patched third-person renderer suppresses armor, head equipment, and elytra for converted meshes because their biped attachment transforms are not valid for arbitrary model proportions. Held items, capes, arrows, and bee stingers continue through Epic Fight's patched layers. All standard layers remain available when the renderer falls back to Epic Fight's default mesh.

## Compatibility warning

During client load, `YSMCompatibilityWarningFilter` identifies only official YSM's Epic Fight compatibility warning. `YSMCompatibilityWarningState` records its first display in the existing client preferences so subsequent launches suppress that warning without affecting unrelated warnings.

## Source layout

| Area | Package |
| --- | --- |
| Model and texture input | `assets`, `assets.binary`, `geometry` |
| Animation data and default form | `animation` |
| Rig mapping, conversion, and cache | `mesh` |
| Epic Fight rendering and layers | `render`, `render.layer`, `event`, `mixin` |
| Selection and geometry synchronization | `network`, `network.geometry`, `network.message` |
| Client preferences and warning handling | `config`, `compat` |
