# Integration contract

- Target only the configured Minecraft/Forge target until the user explicitly opens another target.
- Target the official `yes_steve_model` mod and do not branch on derivative implementation classes.
- Use Epic Fight's biped armature and `HumanoidMesh` seam. Do not overwrite Epic Fight animation poses through official-YSM bone internals.
- Leave official YSM rendering untouched unless Epic Fight's player patch has `overrideRender()` enabled.
- Keep first-person and third-person mesh selection consistent.
- Treat folder models and `.ysm` packages as untrusted local input: validate bounds, cap allocations, fail per model, and fall back to Epic Fight's default mesh.
- Preserve model and texture selection across integrated and dedicated servers. Protocol changes require a compatibility decision and tests.
- Never ship an official YSM jar, model package, native extraction, runtime-obfuscated name, decompiler output, or private Mapping API report.
- YSM-Mapping-API remains a separate required dependency and must not be embedded. Keep `ysm_mapping_api_version` and `ysm_mapping_api_version_range` at 0.1.5, use the former to select the newest stable release tag whose Minecraft suffix exactly matches the target, and generate loader metadata from the latter. Allow an explicitly configured compatible local checkout for cross-repository development. Do not use implicit sibling or workspace discovery and do not lower any dependency version.
- Converted meshes, GPU textures, and Molang runtime state stay session-local in memory. Reuse official YSM's client texture cache through semantic Mapping API contracts when it is available, but retain bounded encoded fallback textures with the parsed model so a cold official cache does not leave the combat mesh untextured.
- A dedicated server may supply a bounded encoded `ModelBundle`, including declared textures but never the original package or model-local audio bytes, only for a model selected by an online player. Keep compatibility-owned network and serialized format versions fixed at `1` until the first public release, authenticate conditional cache reuse with SHA-256, and reject malformed counts, sizes, chunks, hashes, and expanded payloads.
- Persistent compatibility caches may contain parsed local models, server-validated remote models, and generated server transfer payloads under separate bounded directories. Client and remote entries must use hashed names and an atomic versioned binary envelope rather than standalone model JSON or texture images. Validate source or server digests before reuse, overwrite changed models atomically, reject symbolic links, remove corrupt entries, and keep zero-size cache operation memory-only.
- Build success proves compilation and packaging only. User runtime tests remain required for animations, event ordering, first person, equipment, reload, and multiplayer behavior.
