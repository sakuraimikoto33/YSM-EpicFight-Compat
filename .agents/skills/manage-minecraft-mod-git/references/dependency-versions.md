# Dependency and version policy

Read this reference only when a Minecraft, loader, library, Gradle plugin, wrapper, or toolchain version may change.

- A named Minecraft version authorizes branch selection or creation; it is not a product version bump.
- Keep dependencies and toolchains fixed unless the requested feature, fix, compatibility work, or build repair requires the smallest viable update.
- Record the evidence and pass it with `-DependencyVersionReason` when validation or a mutation sees a guarded dependency diff.
- Never update dependencies opportunistically.
- Product, release, protocol, public API, schema, fingerprint, and definition revisions require their repository's explicit contract authorization.
