# History rewrite policy

- Require an explicit rewrite request and name every affected local branch.
- Use one timestamp for the operation. Name backups `archive/<branch>-before-rewrite-YYYYMMDD-HHmmss`, preserving slashes in the branch name.
- Before rewriting, record affected tips and trees, the current branch, index/worktree status, stashes, worktrees, remotes, and non-target refs.
- Preflight every archive name before creating the first archive. Confirm every archive points to the recorded old tip.
- Prefer non-interactive commands and explicit commit parents. Stop rather than resolving an unexpected conflict.
- After rewriting, require `main` to be an ancestor of every active `mc/*` branch and require main-owned paths to match on those branches.
- Rewritten commit IDs may differ, but implementation trees must differ from their archive only by the requested ownership and documentation corrections.
- Preserve archives permanently unless the user separately requests their deletion. A successful rewrite never authorizes a push.
