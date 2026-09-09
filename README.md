# YSM Epic Fight Compat

[日本語](README.ja.md)

YSM Epic Fight Compat is a Forge mod that renders player models selected in the official Yes Steve Model mod with Epic Fight combat animations. Official YSM continues to own normal player rendering outside Epic Fight's combat renderer. An optional adapter also applies the same converted models to supported Touhou Little Maid entities while EpicFight_TouhouLittleMaid owns their combat rendering.

## Version branches

| Minecraft | Loader | Status | Branch |
| --- | --- | --- | --- |
| 1.20.1 | Forge | Implemented | `mc/1.20.1` |
| 1.21.1 | To be implemented | Planned | — |

The `main` branch contains the shared README sections, implementation documentation, maintenance policy, licensing, and reusable project assets. Buildable mod sources are maintained on the matching `mc/<minecraft-version>` branch.

Select the appropriate version branch before building the mod. Each `mc/*` branch maintains its own requirements.

## Features

- Converts YSM folder models and `.ysm` packages, including encrypted packages, into Epic Fight combat meshes.
- Preserves model-authored motion for structurally recognized non-humanoid bodies and alternate forms instead of forcing those branches onto the biped skeleton. Third-person ordinary held items can follow the current form's visible Tool locator, including a mouth or paw; this does not generate new non-humanoid combat animations.
- Renders geometry authored directly on bones whose case-sensitive names begin with `ysmGlow` as full-bright mesh parts and carries model-authored normal/specular textures through the optional Oculus/Iris LabPBR path when fallback textures are required.
- Honors the model's `all_cutout` back-face-culling and `render_layers_first` layer-order settings while preserving the selected material's transparency behavior.
- Uses the model and texture selected by each player in single-player and multiplayer.
- Keeps parsed local models, validated remote models, and generated server transfer data in separate bounded disk caches without writing model JSON or standalone texture images.
- Supports Epic Fight's third-person and first-person combat rendering for players.
- Optionally replaces EpicFight_TouhouLittleMaid's third-person combat mesh for a maid that has an official-YSM model selected. The original maid mesh remains the fallback while conversion is unavailable or the maid has no usable YSM selection.
- Uses model-authored YSM full-body movement for walking, running, sneaking, jumping, creative and elytra flight, swimming, crawling, and ladder movement. It is enabled by default and supports per-model state exclusions; Epic Fight actions immediately regain pose ownership from configured movement. An additional default-enabled natural ladder option keeps an authored two-handed climbing pose, stows ordinary Epic Fight items, and hides active YSM item replacements when the model provides dedicated ladder arm motion.
- Applies YSM auxiliary-bone, automatic, conditional, roulette, item-switch, and Animation Controller motion through pose paths appropriate to each animation. Model-authored riding motion uses its complete-pose path while YSM owns the matching vehicle model.
- Optionally uses supported YSM ParCool action and SWEM rider clips in player battle-mode rendering. Both animation settings default to enabled and have independent per-model, per-clip exclusions, separate from ordinary movement settings. Actual attacks, guarding, and hit reactions retain Epic Fight's pose; Epic ParCool's Chain movement and Wall movement always retain the addon's pose when it is loaded.
- Uses a model-authored YSM weapon or tool when the selected model actually defines one for the held item; otherwise Epic Fight keeps rendering the item.
- Preserves model-authored YSM projectiles, fishing hooks, and vehicles in battle mode when the model owner's resolved local policy selects them. Otherwise, their original Epic Fight or vanilla rendering remains active, and a disabled YSM vehicle also leaves the matching rider pose to Epic Fight.
- Plays an authored YSM full-body hold transition, when the model provides one, after an item changes. Model-authored replacements follow the held-item model setting, while ordinary items rendered by Epic Fight use an independent switch-animation setting.
- Applies the complete authored YSM draw and release pose for detected custom bows, and prevents duplicate Epic Fight/YSM attack-swing audio when a custom replacement owns the sound.
- Evaluates the supported official YSM Molang math functions, read-only queries, auxiliary physics functions, and model variables, including the `v.*`/`variable.*` and `v.roaming.*`/`variable.roaming.*` aliases.
- Supports Animation Controller state variables and `remap_curve`, model-local sound output, Molang particle helpers, and declarative Bedrock `particle_effects`.
- Runs supported model-local Molang functions, initialization/update/sync events, and player animation-controller hooks in a bounded interpreter. Player script sync events carry validated numeric arguments through the server; scripts cannot execute arbitrary native code.
- Synchronizes player model selection, model-variable state, semantic movement state, resolved movement ownership and the current natural-ladder request, the active ParCool/SWEM animation family, clip name, and resolved pose decision, each model owner's resolved per-hand replacement and switch-animation state, and owner-resolved projectile, fishing-hook, and vehicle display state so remote players see the same cosmetic result without receiving the owner's local rules.
- Applies compatible YSM movement, auxiliary, roulette, held-item, item-switch, sound, and particle behavior to supported maids. Viewers receive the maid owner's resolved held-item, item-switch, and movement decisions with a bounded source fingerprint; local settings, exclusions, and tag rules are never synchronized.
- Can hide official YSM's top-left overlay while Epic Fight battle mode is active.
- Returns player rendering to official YSM when Epic Fight no longer overrides it. Maid rendering remains owned by Touhou Little Maid and EpicFight_TouhouLittleMaid outside the adapter's exact patched-renderer scope.
- Refreshes converted models after resource reloads and YSM model reload commands.
- Falls back to Epic Fight's default player mesh for players, or EpicFight_TouhouLittleMaid's original maid mesh for supported maids, when a selected model cannot be prepared.
- Limits YSM's Epic Fight compatibility warning to its first display on a client installation.

For converted player models, armor and head equipment remain hidden because their biped attachment points do not match arbitrary YSM bodies. Elytra are rendered at the model's final animated `ElytraLocator` when exactly one usable locator exists and are hidden otherwise. Capes, arrows, bee stingers, ordinary held items, and locator-backed elytra follow the final displayed YSM pose while Epic Fight's patched layers remain active. Authored hidden or collapsed hand locators can suppress ordinary items; ambiguous or invalid form locators do not select an arbitrary attachment. Equipment rendering remains unchanged when the default Epic Fight player mesh is used. The optional maid adapter retains EpicFight_TouhouLittleMaid's existing layers while applying the converted model's per-hand replacement and locator-visibility rules.

## Installation

Install this mod and all requirements in the `mods` directory. For multiplayer, install YSM Epic Fight Compat on both the dedicated server and every participating client so that player selections and server-provided models can be resolved consistently.

[Configured](https://www.curseforge.com/minecraft/mc-mods/configured) is optional. When installed, it displays **Animations** (`[common.animations]`) and **Models** (`[common.models]`) under the client config's **Common** group. Each group has an **Exclusions** folder matching its `.exclusions` table: Animations contains model-specific item-switch, movement, ParCool, and SWEM exclusions, alongside their animation toggles and the natural-ladder toggle; Models contains held-item, projectile, and vehicle exclusions, alongside their model toggles. Overlay options belong to `[client]`, and cache options to `[client.cache]`. The settings screen follows the actual TOML hierarchy.

An exclusion disables the matching YSM behavior while its main setting is enabled and never enables it while that setting is disabled. ParCool and SWEM use short clip names such as `fast_running` or `gallop`, without the `parcool:` or `swem:` prefix; wildcards and names from the other animation family are not accepted. The model-specific editors add the currently selected model ID as an editable entry, and empty rows are not saved. Without Configured, the TOML settings still work and the mod starts normally; only the in-game settings screen is unavailable.

ParCool and SWEM are optional and independent. Their animation adapters read the installed mod's player action or riding state and require a usable matching YSM clip; they do not change movement, attacks, or horse behavior. SWEM integration animates the rider, not the horse, and does not depend on the YSM vehicle-model setting. These two adapters do not extend the maid integration. Missing optional mods or unusable clips leave the existing rendering path in place.

Touhou Little Maid integration is optional and activates only when both Touhou Little Maid and EpicFight_TouhouLittleMaid are installed. Neither mod is required when maid combat-model integration is not needed.

## Building

Java 17 and Git are required.

```powershell
.\gradlew.bat build
```

To use a Mapping API checkout under development, provide its path explicitly:

```powershell
.\gradlew.bat build -PysmMappingApiPath=D:\src\YSM-Mapping-API
```

The distributable jar is written to:

```text
build/libs/ysm-epicfight-compat-mc1.20.1-<mod-version>-all.jar
```

## Documentation

- [Implementation details](docs/implementation.md)

## Shared asset policy

Project artwork, screenshots, documentation templates, and other files reused across Minecraft versions belong in `assets/` on `main` when such files are added.

- Keep editable source files when they are required to update a generated asset.
- Record the author, license, and source for third-party material.
- Use stable, descriptive file names and avoid Minecraft-version-specific content.
- Do not store dependency jars, build outputs, game files, or model packages in shared assets.
- Keep assets used by only one Minecraft version on that version branch.

## License

This project is licensed under the [MIT License](LICENSE).
