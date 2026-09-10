# YSM Epic Fight Compat

[日本語](README.ja.md)

Use the player model selected in official Yes Steve Model with Epic Fight combat animations. Outside Epic Fight's combat renderer, official YSM handles normal player rendering.

## Version branches

| Minecraft | Loader | Status | Branch |
| --- | --- | --- | --- |
| 1.20.1 | Forge | Implemented | `mc/1.20.1` |
| 1.21.1 | NeoForge | Implemented | `mc/1.21.1` |

The `main` branch contains the shared README sections, implementation documentation, maintenance policy, licensing, and reusable project assets. Buildable mod sources are maintained on the matching `mc/<minecraft-version>` branch.

Select the appropriate version branch before building the mod. Each `mc/*` branch maintains its own requirements.

## Features

- Yes Steve Model (YSM) player models in Epic Fight's first- and third-person combat rendering.
- Folder models and supported `.ysm` packages, including encrypted packages.
- Model-authored movement, secondary motion, item switches, controllers, sounds, and particles.
- Custom weapons, bow actions, projectiles, fishing hooks, and vehicles.
- Multiplayer synchronization of selected models, textures, and cosmetic animation state.
- Optional integrations with Touhou Little Maid + EpicFight：TouhouLittleMaid, ParCool!, Epic ParCool, SWEM, and Oculus / Iris Shaders, where available for the target Minecraft version and loader.

## Optional integrations

These mods are not required for player-model compatibility. Install each integration's own dependencies as well.

| Mod | Integration |
| --- | --- |
| [Configured](https://www.curseforge.com/minecraft/mc-mods/configured) | Provides an in-game settings screen; optional version 2.2.3+ for 1.20.1 Forge or 2.6.3+ for 1.21.1 NeoForge. |
| [Touhou Little Maid](https://www.curseforge.com/minecraft/mc-mods/touhou-little-maid) + [EpicFight：TouhouLittleMaid](https://modrinth.com/mod/epicfight_touhoulittlemaid) | Lets maids use YSM models while performing the Epic Fight task. |
| [ParCool! ~ Minecraft Parkour ~](https://www.curseforge.com/minecraft/mc-mods/parcool) | Uses the YSM model's matching animations during parkour. |
| [\[Official\] Epic ParCool](https://www.curseforge.com/minecraft/mc-mods/official-epic-fight-x-parcool) | Keeps Epic ParCool's own animations for Chain movement and Wall movement. Other supported actions can use YSM animations. |
| [Star Worm Equestrian (Upgrading Horses)](https://www.curseforge.com/minecraft/mc-mods/swem) (SWEM) | Uses YSM riding animations when riding a SWEM horse. The horse's appearance and movement are unchanged. |
| [Oculus](https://www.curseforge.com/minecraft/mc-mods/oculus) / [Iris Shaders](https://www.irisshaders.dev/) | Oculus on 1.20.1 Forge and Iris on 1.21.1 NeoForge use the same PBR bridge to expose the YSM model's surface-detail and shine textures to compatible shaders. |

EpicFight：TouhouLittleMaid has no 1.21.1 release. Its adapter code is retained for a future port and stays inactive without the required mods; this does not establish compatibility with a future release.

ParCool and SWEM integration changes only the player's appearance and requires matching animations in the YSM model. Movement and combat mechanics are unchanged; attacks, guarding, and hit reactions use Epic Fight's animations.

## Configuration

Settings are stored below `config/ysm_epicfight_compat/`:

| File | Contents |
| --- | --- |
| `ysm_epicfight_compat-client.toml` | Client display, animation, model, exclusion, and client-cache settings. The `[common.*]` tables below are also in this client file. |
| `ysm_epicfight_compat-common.toml` | Global cache settings for integrated and dedicated servers, under `[server.cache]`. This is not a per-world `serverconfig` file. |

Use Configured's screen or edit the TOML files. Saved/reloaded settings take effect without restarting the game. Model-owner preferences and exclusion lists stay on that owner's client; multiplayer sends resolved display/pose decisions rather than the rule lists.

### Client display and evaluation — `[client]`

| Key | Default | Effect / allowed values |
| --- | --- | --- |
| `suppressBattleModeOverlay` | `true` | Hides official YSM's top-left player overlay during Epic Fight battle mode. |
| `animationEvaluationRateLimitHz` | `60` | Target maximum model animation, script, and controller evaluations per second: integer `30–240`, or `0` for Unlimited. State changes can trigger earlier evaluations. |

The evaluation-rate setting does not cap rendering FPS or game ticks, and does not change animation playback speed. Lower values reduce model-update smoothness and can make script/controller output less frequent. Unlimited disables this setting's restriction; distance-based scheduling of eligible remote models still applies.

Configured's slider runs from **30 Hz through 240 Hz to Unlimited at the right end**. Unlimited is saved as `0`; resetting the setting selects `60`.

### Animation behavior — `[common.animations]`

| Key | Default | Effect |
| --- | --- | --- |
| `useYsmHeldItemSwitchAnimations` | `true` | Plays available YSM item-switch poses for ordinary items rendered by Epic Fight. Model-authored replacement items instead follow the held-item model setting. |
| `useYsmMovementAnimations` | `true` | Uses available YSM full-body locomotion animations. Epic Fight combat actions take priority. |
| `useNaturalLadderAnimations` | `true` | Uses both arms when YSM owns a dedicated ladder animation with arm motion. Ordinary items are stowed and YSM replacement items are hidden. Requires movement to be enabled and not excluded; applies to players. |
| `useYsmParCoolAnimations` | `true` | Enables supported YSM ParCool action animations. Independent of ordinary movement settings. |
| `useYsmSwemAnimations` | `true` | Enables supported YSM SWEM rider animations. Independent of ordinary movement and vehicle-model settings. |

ParCool/SWEM settings and their exclusion editors are available only with the corresponding mod installed. Existing saved values are retained when that mod is absent; missing optional entries are not generated.

### Model replacements — `[common.models]`

| Key | Default | Effect |
| --- | --- | --- |
| `useYsmHeldItemModels` | `true` | Uses available model-authored weapons and tools, including their replacement animations. Items without a replacement remain rendered by Epic Fight. |
| `useYsmProjectileModels` | `true` | Uses available model-authored projectiles when a corresponding YSM held-item replacement does not control them. |
| `useYsmVehicleModels` | `true` | Uses available model-authored vehicles and their matching riding poses. Disabling it also returns that mounted-pose path to Epic Fight. |

Fishing hooks follow the fishing rod's held-item policy. Bow/trident projectiles follow that policy when the model defines the corresponding held-item replacement; otherwise they use the projectile setting. Crossbow projectiles use the projectile setting.

### Model-specific exclusions

All seven tables default to empty. Each maps a **selected YSM model ID** to a list of values that disable the corresponding enabled feature. An exclusion never enables a feature whose main setting is off.

| Table in the client file | Values for each model |
| --- | --- |
| `common.models.exclusions.heldItemModelExclusions` | Item IDs or `#item_tags`. |
| `common.models.exclusions.projectileModelExclusions` | Entity-type IDs or `#entity_type_tags`. |
| `common.models.exclusions.vehicleModelExclusions` | Entity-type IDs or `#entity_type_tags`. |
| `common.animations.exclusions.heldItemSwitchAnimationExclusions` | Item IDs or `#item_tags`; `minecraft:air` targets switching to an empty hand. |
| `common.animations.exclusions.movementAnimationExclusions` | Movement state names listed below. |
| `common.animations.exclusions.parcoolAnimationExclusions` | Short ParCool action names listed below. |
| `common.animations.exclusions.swemAnimationExclusions` | Short SWEM rider-animation names listed below. |

Quote each model ID so slashes and dots remain part of the key. Model IDs are matched after trimming whitespace and normalizing case; wildcard model IDs are not supported. Each table accepts up to 256 models. Item/entity tables accept up to 256 selectors per model; model IDs and these selectors are limited to 256 characters each.

Example (replace `example/model` with your selected model's ID):

```toml
[common.models.exclusions.heldItemModelExclusions]
"example/model" = ["minecraft:diamond_sword", "#forge:tools/bows"]

[common.models.exclusions.projectileModelExclusions]
"example/model" = ["minecraft:arrow"]

[common.models.exclusions.vehicleModelExclusions]
"example/model" = ["minecraft:boat"]

[common.animations.exclusions.heldItemSwitchAnimationExclusions]
"example/model" = ["minecraft:air"]

[common.animations.exclusions.movementAnimationExclusions]
"example/model" = ["run", "ladder_up"]

[common.animations.exclusions.parcoolAnimationExclusions]
"example/model" = ["fast_running", "roll_front"]

[common.animations.exclusions.swemAnimationExclusions]
"example/model" = ["gallop", "jump_lv2"]
```

Movement values:

```text
walk, run, sneak_idle, sneak_move, jump, creative_flight, elytra_flight,
swim, water_idle, crawl_idle, crawl_move, ladder_idle, ladder_up, ladder_down
```

ParCool values:

```text
backward_wall_jump, cat_leap, climb_up, cling_to_cliff,
cling_to_cliff_left, cling_to_cliff_right, dive_animation_host, dive_into_water,
dodge_front, dodge_back, dodge_left, dodge_right, fast_running, fast_swim,
flipping_front, flipping_back, horizontal_wall_run_right, horizontal_wall_run_left,
vertical_wall_run, jump_from_bar, hang, hang_vertical, kong_vault,
speed_vault_left, speed_vault_right, roll_front, roll_back, roll_left, roll_right,
sliding, tap, wall_jump_left, wall_jump_right, wall_slide_left, wall_slide_right,
jump_charging, charge_jump, ride_zipline
```

SWEM values:

```text
idle, walk, trot, canter, canter_ext, gallop,
jump_lv1, jump_lv2, jump_lv3, jump_lv4, jump_lv5
```

Movement and mod-animation lists accept only their listed names, not tags or wildcards. Use short names for ParCool/SWEM without `parcool:` or `swem:`; names from the other family are not accepted.

Configured shows saved model entries and adds the currently selected model as an editable row. Empty rows are not saved. Select a model in YSM to make it available in these editors.

### Client caches — `[client.cache]`

| Key | Default | Effect / allowed values |
| --- | --- | --- |
| `clientModelMemoryCacheTargetCount` | `64` | Target number of converted models retained in memory, `8–512`. Selected and pending models are protected, so this is not a hard limit. |
| `clientModelDiskCacheMiB` | `64` | Disk limit for parsed local models, `0–4096` MiB. `0` disables and clears this disk cache. |
| `remoteModelDiskCacheMiB` | `64` | Disk limit for models received from servers, `0–4096` MiB. `0` disables and clears this disk cache. |

### Server cache — `[server.cache]`

These keys belong to `ysm_epicfight_compat-common.toml`.

| Key | Default | Effect / allowed values |
| --- | --- | --- |
| `serverModelDiskCacheEnabled` | `true` | Persists generated model-transfer data. `false` bypasses the disk cache without deleting its files; the bounded in-memory transfer cache stays active. |
| `serverModelDiskCacheMiB` | `256` | Disk limit for generated transfer data, `0–4096` MiB. `0` disables persistence and clears entries during cache maintenance while `serverModelDiskCacheEnabled` is `true`. |

Disk caches use `config/ysm_epicfight_compat/cache/client`, `remote`, and `server`. Each size limit applies independently. Cleanup happens during cache access or cache maintenance, not necessarily when a setting is saved.

### Automatically managed state — `[client]`

`epicFightCompatibilityWarningShown` defaults to `false` and records whether the official YSM/Epic Fight warning has been displayed. It is an acknowledgement record, not a model or animation toggle, and normally needs no manual editing.

## Model and equipment notes

Features depend on the selected model's geometry and animations. Structurally recognized non-humanoid forms retain authored motion; this mod does not generate new combat animations for them.

Converted player models hide armor and head equipment. Elytra require exactly one usable `ElytraLocator`. Ordinary held items can follow supported model-authored attachment points or be hidden by their visibility rules. If model conversion is unavailable, Epic Fight's default mesh and equipment remain the fallback.

## Building

Select the matching Minecraft source branch first. Git and Java 17 for `mc/1.20.1` or Java 21 for `mc/1.21.1` are required. The branch's `gradle.properties` specifies its loader and dependency versions; the 1.21.1 NeoForge baseline follows YSM-Mapping-API.

```powershell
./gradlew.bat build
```

The distributable jar is written to `build/libs/ysm-epicfight-compat-<mc-version>-<mod-version>-all.jar`.

## Documentation

- [Implementation details](docs/implementation.md)

## Shared asset policy

Project artwork, screenshots, documentation templates, and other files reused across Minecraft versions belong in `assets/` on `main` when such files are added.

- Keep editable source files when they are required to update a generated asset.
- Record the author, license, and source for third-party material.
- Use stable, descriptive file names and avoid Minecraft-version-specific content.
- Do not store dependency jars, build outputs, game files, or model packages in shared assets.
- Keep assets used by only one Minecraft version on that version branch.

## Credits

- [Yes Steve Model](https://modrinth.com/mod/yes-steve-model) — YesSteveModel team.
- [Epic Fight](https://www.curseforge.com/minecraft/mc-mods/epic-fight-mod) — Antikythera Studios.

## License

This project is licensed under the [MIT License](LICENSE).
