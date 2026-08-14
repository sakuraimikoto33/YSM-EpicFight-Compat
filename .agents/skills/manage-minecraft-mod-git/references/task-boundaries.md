# Task and commit boundaries

Read this reference only when work is pending, the user changes tasks, or a commit is requested.

A commit boundary exists when the user explicitly requests a commit, or when isolated work A is pending and the user requests independent work B. Completion, review, correction, or validation alone is not a boundary.

Before editing B, inspect A's diff and baseline, define B's reproducer and failing path, and classify B as `A-derived`, `A-required`, `independent`, or `indeterminate`.

- Keep `A-derived` and `A-required` work with A.
- Commit isolated A before editing independent B when the task-change authorization permits a single-branch boundary.
- Present evidence and ask before editing an indeterminate B.
- Require an explicit commit instruction before splitting pending work that spans main and Minecraft branches.

Timing and file overlap are not proof of ownership or causation. Never stage, commit, or edit B while its boundary is unresolved.
