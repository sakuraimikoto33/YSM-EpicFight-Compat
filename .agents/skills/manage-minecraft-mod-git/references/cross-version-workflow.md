# Cross-version workflow

Read this reference only when task-owned files or semantic hunks must be edited directly on at least two configured active Minecraft branches.

Count only direct Minecraft-owned edits. A main-owned change or main merge is propagation, not a direct Minecraft edit.

1. Finish an isolated preceding main task under the normal task-boundary rule.
2. Keep the primary worktree on `main` and its new task-owned main changes uncommitted.
3. Use `PrepareActiveWorktrees` with the exact main overlay paths and explicitly selected active branches.
4. Implement and validate version work without committing the overlay.
5. On explicit commit authorization, capture every selected worktree.
6. Commit exact main paths when present, then use `MergeMain` to replace overlays with verified `--no-ff` main merges.
7. Commit restored version changes, propagate main to remaining active branches, compare snapshots, validate, audit, and clean managed worktrees.

Stop instead of improvising when a manifest, ref, tree, worktree association, direct-edit gate, or preflight result differs.
