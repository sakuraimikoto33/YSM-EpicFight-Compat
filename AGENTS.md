## Always-On Rules

- Preserve user changes. Never discard, overwrite, stage, or commit unrelated work.
- Run `.agents/skills/manage-ysm-epicfight-compat-git/scripts/repository-workflow.ps1 -Operation Inspect` at the start of repository work and use its result before choosing a branch or editing.
- Keep shared repository instructions, `.agents/`, `.gitignore`, licensing, and project-wide documentation on `main`. Keep Minecraft implementation, build files, resources, tests, and version-specific documentation on the matching `mc/*` branch. Read the Git Skill's branch-ownership reference for mixed root files.
- Merge shared changes from `main` into every branch listed by main's `.agents/active-minecraft-branches.txt`; never merge an `mc/*` branch back into `main`.
- The only active Minecraft implementation target is Forge 1.20.1. Do not create or modify another `mc/*` branch until the user explicitly opens that target.
- Support only the official Yes Steve Model distribution. Do not add compatibility branches for derivative implementations.
- Load `$maintain-ysm-epicfight-integration` for render hooks, mesh conversion, model packages, synchronization, Epic Fight APIs, Mapping API dependencies, Mixins, or distribution validation.
- Any access to an obfuscated official-YSM class or member must use YSM-Mapping-API aliases or public mapping APIs. Never commit runtime names, private-derived graphs, decompiler output, or a private scanner/cache.
- Do not lower YSM-Mapping-API's Mixin or other dependency versions. Change that repository only when a minimal new semantic contract is proven necessary.
- Never track or distribute official YSM jars, proprietary model packages, native libraries extracted from them, or private fixture output.
- Completion alone never authorizes a commit. Load `$manage-ysm-epicfight-compat-git` for pending work, task changes, branches, commits, merges, validation, or pushes.
- Load `$rewrite-ysm-epicfight-compat-history` only for an explicit amend, squash, rebase, reset, commit reconstruction, or other history-rewrite request.
- Do not push without an explicit remote and ref instruction. Ordinary push permission never authorizes force or force-with-lease.
- After changing Git or history instruction assets, run the Skill parity verifier, repository audit, and the applicable branch validator.

## User Input

- When calling the `request_user_input` tool, never set `autoResolutionMs`. Wait for the user to answer explicitly.
