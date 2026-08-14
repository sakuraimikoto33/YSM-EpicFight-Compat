---
name: manage-minecraft-mod-git
description: Inspect and manage Git state, task boundaries, branch ownership, Minecraft branch creation, exact-path commits, worktree-free main propagation, selected multi-version worktrees, validation, audits, and explicitly requested pushes in a configured Minecraft mod repository. Use before repository edits and for pending changes, task changes, Minecraft targets, commits, merges, propagation, validation, or pushes. Do not use for history rewriting or domain design policy.
---

# Manage Minecraft Mod Git

Use the scripts as the source of deterministic repository state. Do not read script bodies before running them.

## Start and classify

1. Run `scripts/repository-workflow.ps1 -Operation Inspect` from the repository root.
2. Preserve unrelated changes and stop if the task cannot be isolated safely.
3. Run `Classify -Path <exact paths>` before editing when ownership is not obvious.
4. Read the repository's ownership notes only when `Classify` reports `Mixed` or semantic ownership remains ambiguous.
5. Read [task-boundaries.md](references/task-boundaries.md) only for pending work, a new task, or a commit.

## Select and mutate

- Change Minecraft branches only when the user explicitly names a Minecraft version.
- Use `PrepareMinecraftBranch` before creating a missing target; never infer the version or base branch.
- Completion alone never authorizes a commit. Commit only exact task-owned paths on the expected owning branch.
- Keep main-only work on `main`; after an authorized main commit, use `PropagateMain` for every configured active branch.
- Read [cross-version-workflow.md](references/cross-version-workflow.md) only when direct edits are required on at least two active Minecraft branches.
- Read [dependency-versions.md](references/dependency-versions.md) only when a dependency or toolchain version may change.
- Merge only `main` into `mc/*`. Never merge an `mc/*` branch into `main`.
- Supply every required validation repository explicitly with `-ValidationRepositoryRoot`; never infer sibling paths.
- Push only an explicitly named remote and refspec. Force modes require separate explicit permission.
- Stop on conflicts, validation failures, unexpected paths or refs, snapshot mismatches, or newly discovered irreversible effects.

## Handoff

Run `Validate` at task scope and `Audit`. Report the active branch, pending paths, validation logs, and policy warnings.

After changing a generated workflow asset, run `scripts/verify-agent-workflows.ps1` and its fixture. Change common assets only in the central source repository.
