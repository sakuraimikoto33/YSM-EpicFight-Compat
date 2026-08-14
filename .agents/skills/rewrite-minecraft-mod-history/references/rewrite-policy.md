# History rewrite policy

Use one local timestamp for the whole operation. Name backups `archive/<branch>-before-rewrite-YYYYMMDD-HHmmss`, preserving slashes in the branch name.

The pre-rewrite snapshot records active branch tips and trees, the current branch, index/worktree status, untracked file hashes, stash refs, worktrees, and non-target refs. It does not copy ignored private artifacts.

Before rewriting:

1. Require every affected local branch to be named explicitly.
2. Preflight all archive names before creating the first one.
3. Confirm each planned archive points to the exact current affected-branch tip.

After rewriting, every archive must still point to its recorded old tip. Rewritten active commit IDs may differ, but their final trees and the recorded file state must match. Unaffected refs, stashes, and worktrees must remain unchanged. Report differences; never repair them by deleting backups or resetting user state.
