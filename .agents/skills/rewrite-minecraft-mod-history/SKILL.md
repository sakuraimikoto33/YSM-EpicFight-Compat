---
name: rewrite-minecraft-mod-history
description: Safely inspect, snapshot, back up, and verify an explicitly requested Git history rewrite such as amend, squash, rebase, reset, commit reconstruction, ancestry correction, or retroactive correction in a configured Minecraft mod repository. Use only when the user explicitly requests rewriting existing history. Do not use for ordinary commits, merges, branch creation, propagation, or pushes.
---

# Rewrite Minecraft Mod History

History rewriting is critical. The explicit rewrite request authorizes only the described local rewrite, never a push.

## Workflow

1. Read [rewrite-policy.md](references/rewrite-policy.md).
2. Run `scripts/history-safety.ps1 -Operation Inspect -Branch <affected branches>`.
3. Resolve dirty state and branch ambiguity without discarding user work.
4. Run `Snapshot` and retain its temporary manifest.
5. Review all archive names and tips, then run `CreateBackups -ConfirmExecution`.
6. Perform the requested rewrite manually; never automate semantic reconstruction or conflict resolution.
7. Run `Compare` against the manifest and preserve every reported difference.
8. Run the Git Skill's `Audit`. A rewrite instruction never authorizes a push.

## Stop conditions

- Stop before creating backups if any proposed archive already exists.
- Stop on unexpected dirty or in-progress state, refs, worktrees, stashes, archive tips, or file-state differences.
- Never delete, reuse, rename, reset, or overwrite an archive branch.
