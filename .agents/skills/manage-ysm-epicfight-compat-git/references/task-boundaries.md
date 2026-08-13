# Task and commit boundaries

- Read-only inspection and validation are always allowed.
- Editing task-owned files does not authorize staging, committing, merging, rebasing, tagging, or pushing.
- A commit request authorizes only inspected task-owned changes. Preserve pre-existing staged content unless the user explicitly includes it.
- Keep shared and Minecraft-owned changes in separate commits on their owning branches. A shared commit is propagated to active `mc/*` branches by merging `main`; it is not recreated independently.
- A push request must name or unambiguously imply a remote and ref. It never authorizes force or force-with-lease.
- The sole active Minecraft branch is `mc/1.20.1`. Do not prepare another target speculatively.
- Mapping API is a separate repository. Inspect its Git state and load its own Skill before editing it.
- Stop rather than auto-resolving conflicts or deleting worktrees, branches, archives, caches, generated user models, or runtime configurations.
