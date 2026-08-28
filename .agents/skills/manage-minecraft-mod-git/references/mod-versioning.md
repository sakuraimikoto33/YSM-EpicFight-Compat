# Mod version policy

Read this reference only when selecting, changing, or releasing a mod version. Minecraft target, loader, dependency, and toolchain versions are separate concerns.

Use [Semantic Versioning 2.0.0](https://semver.org/lang/ja/) as the normative specification. The [Japanese introductory guide](https://www.tohoho-web.com/ex/semver.html) is explanatory, not normative.

## Stable 1.0.0 decision

- Only the user decides when the stable `1.0.0` release occurs. Never infer readiness, recommend promotion as an automatic next step, or select it from change classification alone.
- `1.0.0` with optional build metadata but no pre-release identifier is the same stable release decision.
- A `1.0.0-*` pre-release is not the stable release, but it still requires ordinary contract-version authorization.
- Pass `-StableReleaseAuthorized` only when the user explicitly selected the stable `1.0.0` release. Stable `1.0.0` also requires the ordinary `-ContractVersionAuthorized` permission for the version mutation.

## SemVer rules

- Write versions as `MAJOR.MINOR.PATCH`, optionally followed by a valid `-pre-release` and/or `+build.metadata`. Do not use leading zeroes in numeric identifiers.
- Treat `0.y.z` as initial development without a stable public API. SemVer does not decide every `0.y.z` increment; use an existing repository policy or ask when the next version is ambiguous. Never promote it to stable `1.0.0` automatically.
- After `1.0.0`, increment MAJOR for an incompatible public-contract change, MINOR for a backward-compatible addition or deprecation, and PATCH for a backward-compatible bug fix. Reset lower components to zero as required by SemVer.
- Base classification on the repository's declared public API or compatibility contract. If that surface or compatibility impact is unclear, stop and ask instead of guessing.
- A pre-release has lower precedence than its associated normal version. Build metadata does not affect precedence.
- Never change the contents of an already released version. Make every correction a new version.
- Do not reuse or decrease a released version. Record the compatibility reason for the chosen increment in the task handoff.
