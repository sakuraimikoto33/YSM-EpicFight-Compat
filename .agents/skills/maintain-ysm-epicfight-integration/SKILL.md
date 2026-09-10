---
name: maintain-ysm-epicfight-integration
description: Maintain and validate the the configured Minecraft/Forge target bridge between official Yes Steve Model and Epic Fight, including model-package loading, Bedrock-to-SkinnedMesh conversion, render ownership, player model synchronization, Mapping API boundaries, Mixins, caches, and distributions. Use for implementation or review of compatibility behavior. Do not use for Git history operations.
---

# Maintain YSM Epic Fight Integration

Read [integration-contract.md](references/integration-contract.md) before changing rendering, model loading, synchronization, Mixins, dependencies, or distributions.

## Workflow

1. Work on the configured active `mc/*` branch for implementation changes and run `scripts/validate-integration.ps1 -SkipBuild` before editing.
2. Trace the affected path end to end: official YSM selection and package, conversion/cache, Epic Fight mesh or renderer, texture, reload, and multiplayer synchronization. For an explicit optional entity adapter, include its synchronized selection and patched-renderer boundary.
3. Keep normal-mode rendering owned by official YSM. Take ownership only when Epic Fight's player patch overrides rendering or while an explicit optional entity adapter is inside its Epic Fight patched renderer.
4. Prefer public Forge and Epic Fight events/APIs. If an official-YSM obfuscated symbol is unavoidable, add the smallest semantic YSM-Mapping-API contract and consumer alias; never use a runtime name directly.
5. Keep model conversion lazy, cache writes atomic, render-thread GL work bounded, and resource reload invalidation complete.
6. Add or update focused tests where behavior can be isolated. Run the validator without `-SkipBuild` after editing.
7. Report compile/distribution results separately from Minecraft runtime results. Never claim gameplay validation without running the game.

## Documentation ownership

- `README.md`, `README.ja.md`, `docs/implementation.md`, and `docs/implementation.ja.md` are Mixed documentation. Keep project-wide coverage, shared-asset management, and target-selection policy on `main`; each `mc/*` document describes only that Minecraft target and its implementation.
- Author shared documentation changes on `main`, then merge `main` into every active `mc/*` branch. Preserve each branch's existing version-specific documentation during the merge and adapt only content required for its target; never replace an `mc/*` document wholesale with `main`'s version. Do not copy or merge documentation from `mc/*` into `main`.
- The Documentation section of each README links only to the implementation document in the same language: English to `docs/implementation.md`, and Japanese to `docs/implementation.ja.md`.

```powershell
& .\.agents\skills\maintain-ysm-epicfight-integration\scripts\validate-integration.ps1
```
