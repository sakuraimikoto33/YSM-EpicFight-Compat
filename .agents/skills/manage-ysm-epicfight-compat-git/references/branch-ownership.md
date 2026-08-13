# Branch ownership

Read this reference for ownership questions, mixed root files, or work spanning `main` and a Minecraft branch.

## Main

Commit `AGENTS.md`, `.agents/`, `.gitignore`, `LICENSE`, project-wide README content, and assets reused across Minecraft versions on `main`. Edit `.agents/active-minecraft-branches.txt` only on `main`; Minecraft branches receive it through a merge from `main`.

An `assets/` directory on `main` contains actual shared assets only. Keep its management policy in the root README rather than a nested README.

## Minecraft branches

Commit `src/`, `docs/`, the Gradle wrapper, root build files, resources, tests, and Minecraft-specific configuration on the matching `mc/<minecraft-version>` branch.

## Mixed files

Treat `README.md` and localized root README files by semantic hunk. General project and shared-asset guidance belongs to `main`. Installation, dependency, build, artifact, and implementation links that apply to one Minecraft version belong to that `mc/*` branch.

If path classification and semantic ownership disagree, treat the result as mixed and inspect it manually. Never resolve a mixed-file conflict by choosing an entire side without reviewing the hunks.
