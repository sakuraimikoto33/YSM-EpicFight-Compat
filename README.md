# YSM Epic Fight Compat

**English** | [日本語](README.ja.md)

YSM Epic Fight Compat integrates player models selected in the official Yes Steve Model mod with Epic Fight's combat renderer.

## Version branches

| Minecraft | Loader | Status | Branch |
| --- | --- | --- | --- |
| 1.20.1 | Forge | Implemented | `mc/1.20.1` |
| 1.21.1 | To be implemented | Planned | — |

The `main` branch contains shared repository documentation, maintenance policy, licensing, and reusable project assets. Buildable mod sources and implementation details are maintained on the matching `mc/<minecraft-version>` branch.

Select the appropriate version branch for installation requirements, build instructions, and implementation documentation.

## Shared asset policy

Project artwork, screenshots, documentation templates, and other files reused across Minecraft versions belong in `assets/` on `main` when such files are added.

- Keep editable source files when they are required to update a generated asset.
- Record the author, license, and source for third-party material.
- Use stable, descriptive file names and avoid Minecraft-version-specific content.
- Do not store dependency jars, build outputs, game files, or model packages in shared assets.
- Keep assets used by only one Minecraft version on that version branch.

## License

This project is licensed under the [MIT License](LICENSE).
