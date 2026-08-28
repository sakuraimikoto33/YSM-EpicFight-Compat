<!-- BEGIN MANAGED: minecraft-mod-agent-workflows -->
## Shared Agent Rules

- Preserve user changes. Never discard, overwrite, stage, or commit unrelated work.
- Run `.agents/skills/manage-minecraft-mod-git/scripts/repository-workflow.ps1 -Operation Inspect` before choosing a branch or editing.
- Load `$manage-minecraft-mod-git` for pending work, task changes, branch selection, commits, merges, validation, or pushes. Completion alone never authorizes a commit.
- Never infer or select the stable `1.0.0` mod release; only the user decides when it is released. Route every other mod version decision through Semantic Versioning 2.0.0.
- Treat an explicit request naming a Git or irreversible operation as approval for its known normal workflow. Stop on conflicts, validation failures, unexpected scope, or newly discovered irreversible effects.
- Load `$rewrite-minecraft-mod-history` only for an explicit amend, squash, rebase, reset, commit reconstruction, or other history-rewrite request.
- Do not push without an explicit remote and ref instruction. Ordinary push permission never authorizes force or force-with-lease.
- Centrally managed blocks and common Skill files must be changed in Minecraft-Mod-Agent-Workflows and synchronized; do not hand-edit generated copies.

## User Input

- When calling the `request_user_input` tool, never set `autoResolutionMs`. Wait for the user to answer explicitly.
<!-- END MANAGED: minecraft-mod-agent-workflows -->

## Repository Rules

- Support only the official Yes Steve Model distribution. Do not add compatibility branches for derivative implementations.
- Load `$maintain-ysm-epicfight-integration` for render hooks, mesh conversion, model packages, synchronization, Epic Fight APIs, Mapping API dependencies, Mixins, or distribution validation.
- Any access to an obfuscated official-YSM class or member must use YSM-Mapping-API aliases or public mapping APIs. Never commit runtime names, private-derived graphs, decompiler output, or a private scanner/cache.
- Do not lower YSM-Mapping-API's Mixin or other dependency versions. Change that repository only when a minimal new semantic contract is proven necessary.
- Until the first public release, keep every compatibility-owned network and serialized transfer protocol version fixed at `1`; do not increment it for format changes.
- Never track or distribute official YSM jars, proprietary model packages, native libraries extracted from them, or private fixture output.
