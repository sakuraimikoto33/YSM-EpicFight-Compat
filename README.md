# YSM Epic Fight Compat

[日本語](README.ja.md)

YSM Epic Fight Compat is a Forge mod that renders player models selected in the official Yes Steve Model mod with Epic Fight combat animations. Official YSM continues to own normal player rendering outside Epic Fight's combat renderer.

## Requirements

- Minecraft 1.20.1
- Forge 47.4.20 or later
- Yes Steve Model 2.6.0 or later for Forge 1.20.1
- Epic Fight 20.14.17 or later for Forge 1.20.1
- [YSM Mapping API](https://github.com/sakuraimikoto33/YSM-Mapping-API) 0.1.5 or later

## Features

- Converts YSM folder models and `.ysm` packages, including encrypted packages, into Epic Fight combat meshes.
- Uses the model and texture selected by each player in single-player and multiplayer.
- Keeps parsed local models, validated remote models, and generated server transfer data in separate bounded disk caches without writing model JSON or standalone texture images.
- Supports Epic Fight's third-person and first-person combat rendering.
- Uses model-authored YSM full-body movement by default for walking, running, sneaking, jumping, creative and elytra flight, swimming, crawling, and ladder movement, with per-model state exceptions. Epic Fight actions immediately regain pose ownership from configured movement.
- Applies YSM auxiliary-bone, automatic, conditional, riding, roulette, item-switch, and Animation Controller motion through pose paths appropriate to each animation.
- Uses a model-authored YSM weapon or tool when the selected model actually defines one for the held item; otherwise Epic Fight keeps rendering the item.
- Plays an authored YSM full-body hold transition, when the model provides one, after an item changes. Model-authored replacements follow the held-item model setting, while ordinary items rendered by Epic Fight use an independent switch-animation setting.
- Applies the complete authored YSM draw and release pose for detected custom bows, and prevents duplicate Epic Fight/YSM attack-swing audio when a custom replacement owns the sound.
- Evaluates the supported official YSM Molang math functions, read-only queries, auxiliary physics functions, and model variables, including the `v.*`/`variable.*` and `v.roaming.*`/`variable.roaming.*` aliases.
- Supports Animation Controller state variables and `remap_curve`, model-local sound output, Molang particle helpers, and declarative Bedrock `particle_effects`.
- Synchronizes model selection, model-variable state, resolved movement ownership, and each model owner's resolved per-hand replacement and switch-animation state so remote players see the same cosmetic result without receiving the owner's local rules.
- Can hide official YSM's top-left overlay while Epic Fight battle mode is active.
- Returns rendering to official YSM when Epic Fight no longer overrides the player renderer.
- Refreshes converted models after resource reloads and YSM model reload commands.
- Falls back to Epic Fight's default player mesh when a selected model cannot be prepared.
- Limits YSM's Epic Fight compatibility warning to its first display on a client installation.

When a converted model is active, armor, head equipment, and elytra are hidden because their biped attachment points do not match arbitrary YSM bodies. Capes, arrows, bee stingers, and held items without an active model-authored replacement continue through Epic Fight's patched layers. Equipment rendering remains unchanged when the default Epic Fight mesh is used.

## Installation

Install this mod and all requirements in the `mods` directory. For multiplayer, install YSM Epic Fight Compat on both the dedicated server and every participating client so that player selections and server-provided models can be resolved consistently.

[Configured](https://www.curseforge.com/minecraft/mc-mods/configured) is optional. When it is installed, client options such as YSM's battle-mode overlay, model-cache limits, the held-item replacement and item-switch animation policies, YSM movement ownership, and their model-specific exceptions can be changed in game. The model-specific editors appear inside the normal Client settings and add the currently selected model ID as an editable entry. The mod starts normally without Configured, but that settings screen is unavailable.

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
