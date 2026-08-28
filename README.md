# YSM Epic Fight Compat

[日本語](README.ja.md)

YSM Epic Fight Compat is a Forge mod that renders player models selected in the official Yes Steve Model mod with Epic Fight combat animations. Official YSM continues to own normal player rendering outside Epic Fight's combat renderer. An optional adapter also applies the same converted models to supported Touhou Little Maid entities while EpicFight_TouhouLittleMaid owns their combat rendering.

## Requirements

- Minecraft 1.20.1
- Forge 47.4.20 or later
- [Yes Steve Model](https://modrinth.com/mod/yes-steve-model) 2.6.0 or later for Forge 1.20.1
- [Epic Fight](https://modrinth.com/mod/epic-fight) 20.14.17 or later for Forge 1.20.1
- [YSM Mapping API](https://github.com/sakuraimikoto33/YSM-Mapping-API) 0.1.5 or later

Optional maid integration requires both:

- [Touhou Little Maid](https://modrinth.com/mod/touhou-little-maid) 1.5.x
- [EpicFight_TouhouLittleMaid](https://modrinth.com/mod/epicfight_touhoulittlemaid) 1.1.x through 1.3.x

## Features

- Converts YSM folder models and `.ysm` packages, including encrypted packages, into Epic Fight combat meshes.
- Uses the model and texture selected by each player in single-player and multiplayer.
- Keeps parsed local models, validated remote models, and generated server transfer data in separate bounded disk caches without writing model JSON or standalone texture images.
- Supports Epic Fight's third-person and first-person combat rendering for players.
- Optionally replaces EpicFight_TouhouLittleMaid's third-person combat mesh for a maid that has an official-YSM model selected. The original maid mesh remains the fallback while conversion is unavailable or the maid has no usable YSM selection.
- Uses model-authored YSM full-body movement for walking, running, sneaking, jumping, creative and elytra flight, swimming, crawling, and ladder movement. It is enabled by default and supports per-model state exclusions; Epic Fight actions immediately regain pose ownership from configured movement.
- Applies YSM auxiliary-bone, automatic, conditional, roulette, item-switch, and Animation Controller motion through pose paths appropriate to each animation. Model-authored riding motion uses its complete-pose path while YSM owns the matching vehicle model.
- Uses a model-authored YSM weapon or tool when the selected model actually defines one for the held item; otherwise Epic Fight keeps rendering the item.
- Preserves model-authored YSM projectiles, fishing hooks, and vehicles in battle mode when the model owner's resolved local policy selects them. Otherwise, their original Epic Fight or vanilla rendering remains active, and a disabled YSM vehicle also leaves the matching rider pose to Epic Fight.
- Plays an authored YSM full-body hold transition, when the model provides one, after an item changes. Model-authored replacements follow the held-item model setting, while ordinary items rendered by Epic Fight use an independent switch-animation setting.
- Applies the complete authored YSM draw and release pose for detected custom bows, and prevents duplicate Epic Fight/YSM attack-swing audio when a custom replacement owns the sound.
- Evaluates the supported official YSM Molang math functions, read-only queries, auxiliary physics functions, and model variables, including the `v.*`/`variable.*` and `v.roaming.*`/`variable.roaming.*` aliases.
- Supports Animation Controller state variables and `remap_curve`, model-local sound output, Molang particle helpers, and declarative Bedrock `particle_effects`.
- Synchronizes player model selection, model-variable state, resolved movement ownership, each model owner's resolved per-hand replacement and switch-animation state, and owner-resolved projectile, fishing-hook, and vehicle display state so remote players see the same cosmetic result without receiving the owner's local rules.
- Applies compatible YSM movement, auxiliary, roulette, held-item, item-switch, sound, and particle behavior to supported maids. Viewers receive the maid owner's resolved held-item, item-switch, and movement decisions with a bounded source fingerprint; local settings, exclusions, and tag rules are never synchronized.
- Can hide official YSM's top-left overlay while Epic Fight battle mode is active.
- Returns player rendering to official YSM when Epic Fight no longer overrides it. Maid rendering remains owned by Touhou Little Maid and EpicFight_TouhouLittleMaid outside the adapter's exact patched-renderer scope.
- Refreshes converted models after resource reloads and YSM model reload commands.
- Falls back to Epic Fight's default player mesh for players, or EpicFight_TouhouLittleMaid's original maid mesh for supported maids, when a selected model cannot be prepared.
- Limits YSM's Epic Fight compatibility warning to its first display on a client installation.

For converted player models, armor, head equipment, and elytra are hidden because their biped attachment points do not match arbitrary YSM bodies. Capes, arrows, bee stingers, and held items without an active model-authored replacement continue through Epic Fight's patched layers. Equipment rendering remains unchanged when the default Epic Fight player mesh is used. The optional maid adapter retains EpicFight_TouhouLittleMaid's existing layers and suppresses only a hand whose active model-authored replacement owns that item.

## Installation

Install this mod and all requirements in the `mods` directory. For multiplayer, install YSM Epic Fight Compat on both the dedicated server and every participating client so that player selections and server-provided models can be resolved consistently.

[Configured](https://www.curseforge.com/minecraft/mc-mods/configured) is optional. When it is installed, client options such as YSM's battle-mode overlay, model-cache limits, YSM held-item replacements, YSM item-switch animations, YSM movement animations, YSM projectile and vehicle models, and their model-specific exclusions can be changed in game. An exclusion disables the matching behavior while its main YSM setting is enabled and never enables it while that setting is disabled. The model-specific editors appear inside the normal Client settings and add the currently selected model ID as an editable entry. The mod starts normally without Configured, but that settings screen is unavailable.

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

## License

This project is licensed under the [MIT License](LICENSE).
