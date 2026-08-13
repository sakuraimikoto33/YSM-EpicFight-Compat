---
name: maintain-ysm-epicfight-integration
description: Maintain and validate the Forge 1.20.1 bridge between official Yes Steve Model and Epic Fight, including model-package loading, Bedrock-to-SkinnedMesh conversion, render ownership, player model synchronization, Mapping API boundaries, Mixins, caches, and distributions. Use for implementation or review of compatibility behavior. Do not use for Git history operations.
---

# Maintain YSM Epic Fight Integration

Read [integration-contract.md](references/integration-contract.md) before changing rendering, model loading, synchronization, Mixins, dependencies, or distributions.

## Workflow

1. Work on `mc/1.20.1` and run `scripts/validate-integration.ps1 -SkipBuild` before editing.
2. Trace the affected path end to end: official YSM selection and package, conversion/cache, Epic Fight mesh or renderer, texture, reload, and multiplayer synchronization.
3. Keep normal-mode rendering owned by official YSM. Take ownership only when Epic Fight's player patch overrides rendering.
4. Prefer public Forge and Epic Fight events/APIs. If an official-YSM obfuscated symbol is unavoidable, add the smallest semantic YSM-Mapping-API contract and consumer alias; never use a runtime name directly.
5. Keep model conversion lazy, cache writes atomic, render-thread GL work bounded, and resource reload invalidation complete.
6. Add or update focused tests where behavior can be isolated. Run the validator without `-SkipBuild` after editing.
7. Report compile/distribution results separately from Minecraft runtime results. Never claim gameplay validation without running the game.

```powershell
& .\.agents\skills\maintain-ysm-epicfight-integration\scripts\validate-integration.ps1
```
