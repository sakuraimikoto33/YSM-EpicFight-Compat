# Integration contract

- Target only the configured Minecraft/Forge target until the user explicitly opens another target.
- Target the official `yes_steve_model` mod and do not branch on derivative implementation classes.
- Use Epic Fight's biped armature and `HumanoidMesh` seam. Do not overwrite Epic Fight animation poses through official-YSM bone internals.
- Leave official YSM rendering untouched unless Epic Fight's player patch has `overrideRender()` enabled.
- Keep first-person and third-person mesh selection consistent.
- Treat folder models and `.ysm` packages as untrusted local input: validate bounds, cap allocations, fail per model, and fall back to Epic Fight's default mesh.
- Preserve model and texture selection across integrated and dedicated servers. Protocol changes require a compatibility decision and tests.
- Never ship an official YSM jar, model package, native extraction, runtime-obfuscated name, decompiler output, or private Mapping API report.
- YSM-Mapping-API remains a separate required dependency and must not be embedded. Keep `ysm_mapping_api_version` and `ysm_mapping_api_version_range` at 0.1.3, use the former to select the newest stable release tag whose Minecraft suffix exactly matches the target, and generate loader metadata from the latter. Allow an explicitly configured compatible local checkout for cross-repository development. Do not use implicit sibling or workspace discovery and do not lower any dependency version.
- Converted meshes and Molang runtime state stay session-local in memory. Reuse official YSM's client texture cache through semantic Mapping API contracts. A dedicated server may supply only bounded, texture-free geometry for a model selected by an online player; never relay package bytes or textures. Any package-decoding fallback must stay in memory and be invalidated on resource reload.
- Build success proves compilation and packaging only. User runtime tests remain required for animations, event ordering, first person, equipment, reload, and multiplayer behavior.
