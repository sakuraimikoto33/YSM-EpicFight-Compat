---
name: rewrite-ysm-epicfight-compat-history
description: Safely prepare, back up, execute, and verify an explicitly requested YSM-EpicFight-Compat Git history rewrite such as amend, squash, rebase, reset, commit reconstruction, or ancestry correction. Use only when the user explicitly requests rewriting existing history. Do not use for ordinary commits, merges, branch creation, or pushes.
---

# Rewrite YSM Epic Fight Compat History

Read [history-policy.md](references/history-policy.md) before changing any affected ref. A rewrite request authorizes only the described local rewrite, never a push.

## Workflow

1. Load `$manage-ysm-epicfight-compat-git` and inspect branch tips, status, worktrees, stashes, remotes, and in-progress operations.
2. Resolve the exact affected branches and record each original commit and tree.
3. Create unique timestamped `archive/<branch>-before-rewrite-<timestamp>` branches before moving an affected ref.
4. Preserve unrelated refs and refuse to mix dirty state into the rewrite.
5. Reconstruct commits non-interactively with explicit parents. Never guess conflict resolution.
6. Compare final trees and shared-path ownership against the recorded state, then run the Git Skill's `Validate` and `Audit` operations.
7. Report the before/after graph, archive refs, validation results, and whether remotes were untouched.

## Stop conditions

- Stop if an archive name already exists or an affected tip changes after inspection.
- Stop on unexpected dirty state, worktrees, stashes, refs, conflicts, hooks, or validation failures.
- Never delete, reuse, rename, reset, or overwrite an archive branch.
- Never push rewritten history without separate remote/ref and force-mode authorization.
