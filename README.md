# YSM Epic Fight Compat

[日本語](README.ja.md)

YSM Epic Fight Compat is a Forge mod that renders player models selected in the official Yes Steve Model mod with Epic Fight combat animations. Official YSM continues to own normal player rendering outside Epic Fight's combat renderer.

## Requirements

- Minecraft 1.20.1
- Forge 47.4.20 or later
- Yes Steve Model 2.6.0 or later for Forge 1.20.1
- Epic Fight 20.14.17 or later for Forge 1.20.1
- [YSM Mapping API](https://github.com/sakuraimikoto33/YSM-Mapping-API) 0.1.1 or later

## Features

- Converts YSM folder models and `.ysm` packages, including encrypted packages, into Epic Fight combat meshes.
- Uses the model and texture selected by each player in single-player and multiplayer.
- Supports Epic Fight's third-person and first-person combat rendering.
- Preserves auxiliary-bone `pre_parallel`/`parallel` motion without replacing Epic Fight's major-joint poses.
- Returns rendering to official YSM when Epic Fight no longer overrides the player renderer.
- Refreshes converted models after resource reloads and YSM model reload commands.
- Falls back to Epic Fight's default player mesh when a selected model cannot be prepared.
- Limits YSM's Epic Fight compatibility warning to its first display on a client installation.

When a converted model is active, armor, head equipment, and elytra are hidden because their biped attachment points do not match arbitrary YSM bodies. Held items continue to use Epic Fight's item layer. Equipment rendering remains unchanged when the default Epic Fight mesh is used.

## Installation

Install this mod and all requirements in the `mods` directory. For multiplayer, install YSM Epic Fight Compat on both the dedicated server and every participating client so that player selections and server-provided models can be resolved consistently.

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
build/libs/ysm-epicfight-compat-mc1.20.1-0.1.0-all.jar
```

## Documentation

- [Implementation details](docs/implementation.md)

## License

This project is licensed under the [MIT License](LICENSE).
