---
name: manage-ysm-epicfight-compat-git
description: Inspect and manage YSM-EpicFight-Compat Git state, shared main ownership, Minecraft branch ownership, task boundaries, validation, merges, commits, and explicitly requested pushes. Use before repository edits or Git operations and whenever work may belong on main versus mc/*. Do not use for history rewriting or integration design.
---

# Manage YSM Epic Fight Compat Git

## Start and classify

1. Run `scripts/repository-workflow.ps1 -Operation Inspect` from the repository root.
2. Preserve unrelated changes and stop if the task cannot be isolated safely.
3. Read [branch-ownership.md](references/branch-ownership.md) before editing mixed root files or moving work between `main` and `mc/*`.
4. Read [task-boundaries.md](references/task-boundaries.md) when work is pending, the user changes tasks, or a commit is requested.

## Branch workflow

- Keep repository policy, `.agents/`, licensing, and shared documentation on `main`.
- Keep implementation, build files, resources, tests, and version-specific documentation on the matching `mc/*` branch.
- Treat root README files as mixed: project-wide sections belong to `main`; version-only sections belong to the relevant Minecraft branch.
- Read active Minecraft branches from main's `.agents/active-minecraft-branches.txt`. Do not infer active targets from local branch names.
- Merge only `main` into active `mc/*` branches. Never merge an `mc/*` branch into `main`.
- Do not create or modify another `mc/*` target unless the user explicitly names it.

## Mutating workflow

- Completion alone never authorizes a commit.
- Commit only inspected task-owned paths on their owning branch.
- After an authorized shared commit, propagate it to every active Minecraft branch with a normal `--no-ff` merge and stop on conflicts.
- Use pushes only for an explicitly named remote and refspec. Force modes require separate explicit authorization.
- Stop on a conflict, validation failure, unexpected ref, changed remote, or ownership mismatch.

## Validation

Run `Validate` on the current branch before an authorized commit or handoff. Run `Audit` after branch or history work to verify main ancestry and shared-file parity.

```powershell
& .\.agents\skills\manage-ysm-epicfight-compat-git\scripts\repository-workflow.ps1 -Operation Inspect
& .\.agents\skills\manage-ysm-epicfight-compat-git\scripts\repository-workflow.ps1 -Operation Validate
& .\.agents\skills\manage-ysm-epicfight-compat-git\scripts\repository-workflow.ps1 -Operation Audit
```
