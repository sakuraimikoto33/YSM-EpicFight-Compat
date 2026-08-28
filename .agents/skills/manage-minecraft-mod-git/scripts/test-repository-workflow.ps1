[CmdletBinding()]
param(
    [string]$WorkflowScript = (Join-Path $PSScriptRoot "repository-workflow.ps1")
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$workflow = (Resolve-Path -LiteralPath $WorkflowScript).Path
$workflowDirectory = Split-Path -Parent $workflow
$sourceRoot = @(& git -C $workflowDirectory rev-parse --show-toplevel 2>&1)
if ($LASTEXITCODE -ne 0) { throw "Cannot locate the source repository: $($sourceRoot -join [Environment]::NewLine)" }
$sourceRoot = "$($sourceRoot[-1])"
$core = Import-PowerShellDataFile -LiteralPath (Join-Path $workflowDirectory "repository-policy.psd1")
$profile = [ordered]@{
    Name = "workflow-fixture"
    ForbiddenTrackedPatterns = @("(^|/)forbidden/")
    ValidationRepositories = @()
    RepositoryVerifier = ".agents/verify-domain.ps1"
    RepositoryVerifierProfiles = @("Main", "Minecraft")
    MainValidation = @("clean", "build")
    MinecraftValidation = @("clean", "build", "verifyDistributions")
}
$ownership = [ordered]@{
    MainOnlyPaths = @("main-only.txt")
    SharedPaths = @(".agents", "shared")
    MinecraftPaths = @("version")
    MixedPaths = @("mixed.txt", "gradle.properties", "gradlew.bat")
}
$policy = [ordered]@{}
foreach ($key in $core.Keys) { $policy[$key] = $core[$key] }
foreach ($key in $profile.Keys) { $policy[$key] = $profile[$key] }
foreach ($key in $ownership.Keys) { $policy[$key] = $ownership[$key] }
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ("repository-workflow-tests-" + [guid]::NewGuid().ToString("N"))
$script:Passed = 0
$git = (Get-Command git -CommandType Application).Source
$pwsh = (Get-Process -Id $PID).Path

function Assert-True { param([bool]$Condition, [string]$Message) if (-not $Condition) { throw $Message }; $script:Passed++ }
function Git {
    param([string]$Root, [string[]]$Arguments, [switch]$AllowFailure)
    $output = @(& $git -C $Root @Arguments 2>&1 | ForEach-Object { "$_" }); $code = $LASTEXITCODE
    if ($code -ne 0 -and -not $AllowFailure) { throw "fixture git failed: $($Arguments -join ' '): $($output -join [Environment]::NewLine)" }
    [pscustomobject]@{ ExitCode = $code; Lines = $output; Text = $output -join [Environment]::NewLine }
}
function Set-File {
    param([string]$Root, [string]$Relative, [string]$Content)
    $path = Join-Path $Root $Relative; $parent = Split-Path -Parent $path
    if ($parent) { [void](New-Item -ItemType Directory -Force -Path $parent) }
    [IO.File]::WriteAllText($path, $Content, [Text.UTF8Encoding]::new($false))
}
function Install-RepositoryProfile {
    param([string]$Root)
    Set-File $Root ".agents/repository-profile.psd1" @'
@{
    Name = "workflow-fixture"
    ForbiddenTrackedPatterns = @(
        "(^|/)forbidden/"
    )
    ValidationRepositories = @()
    RepositoryVerifier = ".agents/verify-domain.ps1"
    RepositoryVerifierProfiles = @("Main", "Minecraft")
    MainValidation = @("clean", "build")
    MinecraftValidation = @("clean", "build", "verifyDistributions")
}
'@
    Set-File $Root ".agents/branch-ownership.psd1" @'
@{
    MainOnlyPaths = @(
        "main-only.txt"
    )
    SharedPaths = @(
        ".agents"
        "shared"
    )
    MinecraftPaths = @(
        "version"
    )
    MixedPaths = @(
        "mixed.txt"
        "gradle.properties"
        "gradlew.bat"
    )
}
'@
    Set-File $Root ([string]$policy.RepositoryVerifier) "param([string]`$RepoRoot,[switch]`$AllowContractVersionChange)`n'{`"success`":true}'`n"
}
function Invoke-Workflow {
    param([string]$Root, [string[]]$Arguments, [switch]$ExpectFailure)
    $output = @(& $pwsh -NoProfile -File $workflow -RepoRoot $Root @Arguments 2>&1 | ForEach-Object { "$_" }); $code = $LASTEXITCODE
    if (-not $ExpectFailure -and $code -ne 0) { throw "workflow failed: $($output -join [Environment]::NewLine)" }
    if ($ExpectFailure -and $code -eq 0) { throw "workflow unexpectedly succeeded: $($Arguments -join ' ')" }
    $json = $output[-1] | ConvertFrom-Json
    [pscustomobject]@{ ExitCode = $code; Json = $json; Output = $output }
}
function Assert-ProfileRejected {
    param([string]$Root, [string]$Content, [string]$Pattern)
    $path = Join-Path $Root ".agents/repository-profile.psd1"
    $bytes = [IO.File]::ReadAllBytes($path)
    try {
        [IO.File]::WriteAllText($path, $Content, [Text.UTF8Encoding]::new($false))
        $failure = Invoke-Workflow $Root @("-Operation", "Inspect") -ExpectFailure
        Assert-True ($failure.Json.error -match $Pattern) "Invalid profile was not rejected with '$Pattern'."
    } finally {
        [IO.File]::WriteAllBytes($path, $bytes)
    }
}
function Assert-OwnershipRejected {
    param([string]$Root, [string]$Content, [string]$Pattern)
    $path = Join-Path $Root ".agents/branch-ownership.psd1"
    $bytes = [IO.File]::ReadAllBytes($path)
    try {
        [IO.File]::WriteAllText($path, $Content, [Text.UTF8Encoding]::new($false))
        $failure = Invoke-Workflow $Root @("-Operation", "Inspect") -ExpectFailure
        Assert-True ($failure.Json.error -match $Pattern) "Invalid ownership was not rejected with '$Pattern'."
    } finally {
        [IO.File]::WriteAllBytes($path, $bytes)
    }
}

try {
    [void](New-Item -ItemType Directory -Force -Path $testRoot)
    $repo = Join-Path $testRoot "repo"
    & $git init -q -b main $repo
    [void](Git $repo @("config", "user.name", "Workflow Fixture"))
    [void](Git $repo @("config", "user.email", "fixture@example.invalid"))
    [void](Git $repo @("config", "core.autocrlf", "false"))
    Install-RepositoryProfile $repo

    # Use a leaf below a shared directory; the first shared entry itself is
    # .agents, which is already a directory containing the fixture profile.
    $shared = "shared/value.txt"
    $mainOnly = if ($policy.Contains("MainOnlyPaths") -and @($policy.MainOnlyPaths).Count) {
        [string]$policy.MainOnlyPaths[0]
    } else { "" }
    $versionScope = [string]$policy.MinecraftPaths[0]
    $mixed = [string]$policy.MixedPaths[0]
    $activeFile = [string]$policy.ActiveMinecraftBranchesFile
    Set-File $repo $shared "shared`n"
    if ($mainOnly) { Set-File $repo $mainOnly "main only`n" }
    Set-File $repo $mixed "mixed`n"
    Set-File $repo $activeFile "mc/1.21.1`n"
    Set-File $repo ".agents/overlay-shared.txt" "shared base`n"
    Set-File $repo ".agents/delete-overlay.txt" "delete me`n"
    Set-File $repo "gradle.properties" "modVersion=0.1.0`nloaderVersion=1`nminecraftVersion=1.21.1`n"
    Set-File $repo "gradlew.bat" "@echo off`r`nexit /b 0`r`n"
    [void](Git $repo @("add", ".")); [void](Git $repo @("commit", "-q", "-m", "base"))
    [void](Git $repo @("branch", "port-base"))
    [void](Git $repo @("switch", "-q", "-c", "mc/1.21.1"))
    Set-File $repo (Join-Path $versionScope "value.txt") "one`n"
    [void](Git $repo @("add", ".")); [void](Git $repo @("commit", "-q", "-m", "version base"))
    [void](Git $repo @("switch", "-q", "main"))

    $inspect = Invoke-Workflow $repo @("-Operation", "Inspect")
    Assert-True ($inspect.Json.operation -eq "Inspect") "Inspect operation missing."
    Assert-True ($inspect.Json.branch -eq "main") "Inspect branch mismatch."
    Assert-True (-not $inspect.Json.dirty) "Fixture should start clean."
    Assert-True ($inspect.Json.mainExists) "main should exist."
    Assert-True (@($inspect.Json.minecraftBranches) -contains "mc/1.21.1") "Minecraft branch missing."
    Assert-True ($inspect.Json.activeMinecraftBranchFile.valid) "Active branch file should be valid."
    Assert-True (@($inspect.Json.activeMinecraftBranches).Count -eq 1) "Active branch count mismatch."
    Assert-True (@($inspect.Json.activeMinecraftBranches) -contains "mc/1.21.1") "Active branch missing."
    Assert-True (@($inspect.Json.inProgress).Count -eq 0) "Unexpected in-progress state."

    $categories = @{}
    foreach ($candidate in @($shared, $versionScope, $mixed, "unknown.txt")) {
        $classification = Invoke-Workflow $repo @("-Operation", "Classify", "-Path", $candidate)
        $categories[$candidate] = $classification.Json.paths[0].category
    }
    Assert-True ($categories[$shared] -eq "Shared") "Shared classification failed."
    Assert-True ($categories[$versionScope] -eq "Minecraft") "Minecraft classification failed."
    Assert-True ($categories[$mixed] -eq "Mixed") "Mixed classification failed."
    Assert-True ($categories["unknown.txt"] -eq "Unknown") "Unknown classification failed."
    if ($mainOnly) {
        $classification = Invoke-Workflow $repo @("-Operation", "Classify", "-Path", $mainOnly)
        Assert-True ($classification.Json.paths[0].category -eq "MainOnly") "Main-only classification failed."
    }
    foreach ($candidate in @($policy.SharedPaths)) {
        $classification = Invoke-Workflow $repo @("-Operation", "Classify", "-Path", [string]$candidate)
        Assert-True ($classification.Json.paths[0].category -eq "Shared") "Shared profile path was misclassified: $candidate"
    }
    foreach ($candidate in @($policy.MinecraftPaths)) {
        $classification = Invoke-Workflow $repo @("-Operation", "Classify", "-Path", [string]$candidate)
        Assert-True ($classification.Json.paths[0].category -eq "Minecraft") "Minecraft profile path was misclassified: $candidate"
    }
    foreach ($candidate in @($policy.MixedPaths)) {
        $classification = Invoke-Workflow $repo @("-Operation", "Classify", "-Path", [string]$candidate)
        Assert-True ($classification.Json.paths[0].category -eq "Mixed") "Mixed profile path was misclassified: $candidate"
    }
    $mixedClassification = Invoke-Workflow $repo @("-Operation", "Classify", "-Path", $mixed)
    Assert-True ($mixedClassification.Json.requiresSemanticReview) "Mixed paths should require semantic review."

    $existing = Invoke-Workflow $repo @("-Operation", "PrepareMinecraftBranch", "-MinecraftVersion", "1.21.1")
    Assert-True ($existing.Json.exists) "Existing Minecraft branch was not detected."
    Assert-True (-not $existing.Json.switched) "Query must not switch branches."
    Assert-True ((Git $repo @("branch", "--show-current")).Text -eq "main") "Query changed the current branch."
    $missing = Invoke-Workflow $repo @("-Operation", "PrepareMinecraftBranch", "-MinecraftVersion", "1.22.0")
    Assert-True ($missing.Json.requiresBaseSelection) "Missing branch must request a base."
    Assert-True ((Git $repo @("show-ref", "--verify", "--quiet", "refs/heads/mc/1.22.0") -AllowFailure).ExitCode -ne 0) "Query created a branch."
    $planned = Invoke-Workflow $repo @("-Operation", "PrepareMinecraftBranch", "-MinecraftVersion", "1.22.0", "-BaseBranch", "port-base")
    Assert-True ($planned.Json.requiresConfirmation) "Base selection must still require execution confirmation."

    [void](Git $repo @("switch", "-q", "-c", "conflict-base"))
    Set-File $repo $mixed "base conflict`n"
    [void](Git $repo @("add", $mixed)); [void](Git $repo @("commit", "-q", "-m", "conflicting base"))
    [void](Git $repo @("switch", "-q", "main"))
    Set-File $repo $mixed "main conflict`n"
    Set-File $repo "main-marker.txt" "main advance`n"
    [void](Git $repo @("add", ".")); [void](Git $repo @("commit", "-q", "-m", "advance main"))
    $mainBeforeConflict = (Git $repo @("rev-parse", "main")).Text.Trim()
    $baseBeforeConflict = (Git $repo @("rev-parse", "conflict-base")).Text.Trim()
    $conflict = Invoke-Workflow $repo @("-Operation", "PrepareMinecraftBranch", "-MinecraftVersion", "1.23.0",
        "-BaseBranch", "conflict-base", "-Authorization", "ExplicitUser", "-ConfirmExecution") -ExpectFailure
    Assert-True ($conflict.Json.error -match "preflight failed") "Conflicting branch creation did not fail in preflight."
    Assert-True ((Git $repo @("rev-parse", "main")).Text.Trim() -eq $mainBeforeConflict) "Conflict moved main."
    Assert-True ((Git $repo @("rev-parse", "conflict-base")).Text.Trim() -eq $baseBeforeConflict) "Conflict moved its base."
    Assert-True ((Git $repo @("show-ref", "--verify", "--quiet", "refs/heads/mc/1.23.0") -AllowFailure).ExitCode -ne 0) "Conflict created the target ref."
    [void](Git $repo @("switch", "-q", "mc/1.21.1")); [void](Git $repo @("merge", "-q", "--no-ff", "main", "-m", "Merge main"))
    [void](Git $repo @("switch", "-q", "main"))
    $created = Invoke-Workflow $repo @("-Operation", "PrepareMinecraftBranch", "-MinecraftVersion", "1.22.0",
        "-BaseBranch", "port-base", "-Authorization", "ExplicitUser", "-ConfirmExecution")
    Assert-True ($created.Json.created) "Missing branch was not created."
    Assert-True ($created.Json.mainMerged) "Latest main was not merged into the new branch."
    Assert-True ($created.Json.branch -eq "mc/1.22.0") "New branch was not checked out."
    Assert-True ((Git $repo @("merge-base", "--is-ancestor", "main", "mc/1.22.0") -AllowFailure).ExitCode -eq 0) "main is not an ancestor."
    Assert-True ((Git $repo @("show", "mc/1.22.0:main-marker.txt") -AllowFailure).ExitCode -eq 0) "main content is missing."
    if ($mainOnly) {
        Assert-True ((Git $repo @("cat-file", "-e", "mc/1.22.0`:$mainOnly") -AllowFailure).ExitCode -ne 0) `
            "New Minecraft branch retained a main-only path."
    }

    Set-File $repo (Join-Path $versionScope "value.txt") "pending`n"
    $snapshot = Invoke-Workflow $repo @("-Operation", "Snapshot", "-Path", $versionScope)
    Assert-True (Test-Path -LiteralPath $snapshot.Json.snapshot) "Snapshot manifest missing."
    Assert-True ($snapshot.Json.files -eq 1) "Snapshot file count mismatch."
    $same = Invoke-Workflow $repo @("-Operation", "CompareSnapshot", "-SnapshotPath", $snapshot.Json.snapshot)
    Assert-True ($same.Json.match) "Unchanged snapshot should match."
    Set-File $repo (Join-Path $versionScope "value.txt") "different`n"
    $different = Invoke-Workflow $repo @("-Operation", "CompareSnapshot", "-SnapshotPath", $snapshot.Json.snapshot)
    Assert-True (-not $different.Json.match) "Changed snapshot should differ."
    Assert-True (@($different.Json.differences).Count -eq 1) "Expected one snapshot difference."
    [void](Git $repo @("restore", "."))

    $newVersionFile = Join-Path $versionScope "new.txt"
    Set-File $repo $newVersionFile "new pending file`n"
    $newSnapshot = Invoke-Workflow $repo @("-Operation", "Snapshot", "-Path", $newVersionFile)
    [void](Git $repo @("add", "--", $newVersionFile)); [void](Git $repo @("commit", "-q", "-m", "track snapshot fixture"))
    $committedSnapshot = Invoke-Workflow $repo @("-Operation", "CompareSnapshot", "-SnapshotPath", $newSnapshot.Json.snapshot)
    Assert-True ($committedSnapshot.Json.match) "Commit-only tracking transition changed snapshot content."
    Assert-True (@($committedSnapshot.Json.trackingChanges).Count -eq 1) "Tracking transition was not reported."

    [void](Git $repo @("switch", "-q", "main"))
    Set-File $repo "gradle.properties" "modVersion=0.2.0`n"
    $guarded = Invoke-Workflow $repo @("-Operation", "Validate", "-AllowDirty", "-SkipBuild") -ExpectFailure
    Assert-True (-not $guarded.Json.success) "Product version guard should fail."
    Assert-True ($guarded.Json.error -match "contract version") "Guard failure reason missing: $($guarded.Json.error) line=$($guarded.Json.line) stack=$($guarded.Json.stack)"
    $authorized = Invoke-Workflow $repo @("-Operation", "Validate", "-AllowDirty", "-SkipBuild", "-ContractVersionAuthorized")
    Assert-True ($authorized.Json.success) "Authorized product version validation failed."
    Assert-True ($authorized.Json.validation.policy.modVersionChanges[0].version -eq "0.2.0") "SemVer change was not reported."
    Assert-True (-not $authorized.Json.validation.policy.modVersionChanges[0].stableRelease) "Development version was marked stable."
    [void](Git $repo @("restore", "gradle.properties"))

    Set-File $repo "gradle.properties" "modVersion=0.2`n"
    $invalidSemVer = Invoke-Workflow $repo @("-Operation", "Validate", "-AllowDirty", "-SkipBuild",
        "-ContractVersionAuthorized") -ExpectFailure
    Assert-True ($invalidSemVer.Json.error -match "Semantic Versioning 2.0.0") "Invalid SemVer was not rejected."
    [void](Git $repo @("restore", "gradle.properties"))

    Set-File $repo "gradle.properties" "mod_version=0.2.0`n"
    $snakeCaseVersion = Invoke-Workflow $repo @("-Operation", "Validate", "-AllowDirty", "-SkipBuild",
        "-ContractVersionAuthorized")
    Assert-True ($snakeCaseVersion.Json.success) "Snake-case mod version was rejected."
    Assert-True ($snakeCaseVersion.Json.validation.policy.modVersionChanges[0].property -eq "mod_version") `
        "Snake-case mod version was not guarded."
    [void](Git $repo @("restore", "gradle.properties"))

    Set-File $repo "gradle.properties" "modVersion=1.0.0-rc.1`n"
    $preRelease = Invoke-Workflow $repo @("-Operation", "Validate", "-AllowDirty", "-SkipBuild",
        "-ContractVersionAuthorized")
    Assert-True ($preRelease.Json.success) "A valid 1.0.0 pre-release was rejected."
    Assert-True (-not $preRelease.Json.validation.policy.modVersionChanges[0].stableRelease) `
        "A 1.0.0 pre-release was marked as the stable release."
    [void](Git $repo @("restore", "gradle.properties"))

    Set-File $repo "gradle.properties" "modVersion=1.0.0`n"
    $manualStable = Invoke-Workflow $repo @("-Operation", "Validate", "-AllowDirty", "-SkipBuild",
        "-ContractVersionAuthorized") -ExpectFailure
    Assert-True ($manualStable.Json.error -match "manual user decision") "Stable 1.0.0 lacked its manual decision guard."
    $authorizedStable = Invoke-Workflow $repo @("-Operation", "Validate", "-AllowDirty", "-SkipBuild",
        "-ContractVersionAuthorized", "-StableReleaseAuthorized")
    Assert-True ($authorizedStable.Json.success) "Explicitly authorized stable 1.0.0 was rejected."
    Assert-True ($authorizedStable.Json.validation.policy.modVersionChanges[0].stableRelease) `
        "Stable 1.0.0 authorization was not reported."
    [void](Git $repo @("restore", "gradle.properties"))

    Set-File $repo "gradle.properties" "modVersion=0.1.0`nloaderVersion=2`nminecraftVersion=1.21.1`n"
    $unexplainedDependency = Invoke-Workflow $repo @("-Operation", "Validate", "-AllowDirty", "-SkipBuild") -ExpectFailure
    Assert-True ($unexplainedDependency.Json.error -match "Dependency version") "Unexplained dependency update was not rejected."
    $explainedDependency = Invoke-Workflow $repo @("-Operation", "Validate", "-AllowDirty", "-SkipBuild",
        "-DependencyVersionReason", "Required by the fixture fix")
    Assert-True ($explainedDependency.Json.success) "Necessary dependency update was not accepted."
    Assert-True ($explainedDependency.Json.validation.policy.dependencyVersionReason -eq "Required by the fixture fix") "Dependency evidence was not recorded."
    [void](Git $repo @("restore", "gradle.properties"))

    Set-File $repo "gradle.properties" "modVersion=0.1.0`nloaderVersion=1`nminecraftVersion=1.22.0`n"
    $wrongMinecraftBranch = Invoke-Workflow $repo @("-Operation", "Validate", "-AllowDirty", "-SkipBuild") -ExpectFailure
    Assert-True ($wrongMinecraftBranch.Json.error -match "Minecraft version") "Minecraft version changed outside its branch."
    [void](Git $repo @("restore", "gradle.properties"))

    [void](Git $repo @("switch", "-q", "mc/1.22.0"))
    Set-File $repo "gradle.properties" "modVersion=0.1.0`nloaderVersion=1`nminecraftVersion=1.22.0`n"
    $matchingMinecraftBranch = Invoke-Workflow $repo @("-Operation", "Validate", "-AllowDirty", "-SkipBuild")
    Assert-True ($matchingMinecraftBranch.Json.success) "Matching Minecraft branch version was rejected."
    Assert-True (@($matchingMinecraftBranch.Json.validation.policy.contractVersionLines).Count -eq 0) "Minecraft version entered the product contract guard."
    [void](Git $repo @("restore", "gradle.properties"))
    Set-File $repo (Join-Path $versionScope "value.txt") "committed`n"
    Set-File $repo $shared "unrelated staged content`n"
    [void](Git $repo @("add", "--", $shared))
    $stagedDenied = Invoke-Workflow $repo @("-Operation", "Commit", "-Path", $versionScope, "-ExpectedBranch", "mc/1.22.0",
        "-Message", "Update version fixture", "-Authorization", "TaskBoundary", "-ConfirmExecution", "-SkipBuild") -ExpectFailure
    Assert-True ($stagedDenied.Json.error -match "pre-existing staged") "Pre-existing staging was not rejected."
    [void](Git $repo @("reset", "-q", "HEAD", "--", $shared)); [void](Git $repo @("restore", "--", $shared))
    $commit = Invoke-Workflow $repo @("-Operation", "Commit", "-Path", $versionScope, "-ExpectedBranch", "mc/1.22.0",
        "-Message", "Update version fixture", "-Authorization", "TaskBoundary", "-ConfirmExecution", "-SkipBuild")
    Assert-True ($commit.Json.operation -eq "Commit") "Commit operation missing."
    Assert-True ($commit.Json.authorization -eq "TaskBoundary") "Commit authorization mismatch."
    Assert-True (@($commit.Json.paths).Count -eq 1) "Exact-path commit count mismatch."
    Assert-True (-not (Git $repo @("status", "--porcelain=v1")).Lines.Count) "Commit left a dirty tree."
    Set-File $repo $mixed "mixed changed`n"
    $mixedCommit = Invoke-Workflow $repo @("-Operation", "Commit", "-Path", $mixed, "-ExpectedBranch", "mc/1.22.0",
        "-Message", "Change mixed", "-Authorization", "TaskBoundary", "-ConfirmExecution", "-SkipBuild") -ExpectFailure
    Assert-True ($mixedCommit.Json.error -match "mixed or cross-cutting") "Mixed TaskBoundary was not rejected."
    [void](Git $repo @("restore", $mixed))
    Set-File $repo "unknown.txt" "unknown ownership`n"
    $unknownCommit = Invoke-Workflow $repo @("-Operation", "Commit", "-Path", "unknown.txt", "-ExpectedBranch", "mc/1.22.0",
        "-Message", "Change unknown", "-Authorization", "ExplicitUser", "-ConfirmExecution", "-SkipBuild") -ExpectFailure
    Assert-True ($unknownCommit.Json.error -match "unknown ownership") "Unknown ownership was not rejected."
    Remove-Item -LiteralPath (Join-Path $repo "unknown.txt") -Force
    Set-File $repo $activeFile "mc/1.21.1`n# illegal branch edit`n"
    $activeCommit = Invoke-Workflow $repo @("-Operation", "Commit", "-Path", $activeFile, "-ExpectedBranch", "mc/1.22.0",
        "-Message", "Change active branches", "-Authorization", "ExplicitUser", "-ConfirmExecution", "-SkipBuild") -ExpectFailure
    Assert-True ($activeCommit.Json.error -match "only on main") "Active branch file commit outside main was accepted."
    [void](Git $repo @("restore", $activeFile))

    $bare = Join-Path $testRoot "remote.git"; & $git init -q --bare $bare
    [void](Git $repo @("remote", "add", "fixture", $bare))
    $noConfirm = Invoke-Workflow $repo @("-Operation", "Push", "-Remote", "fixture", "-RefSpec", "HEAD:refs/heads/test",
        "-Authorization", "ExplicitUser") -ExpectFailure
    Assert-True ($noConfirm.Json.error -match "ConfirmExecution") "Push confirmation gate failed."
    $push = Invoke-Workflow $repo @("-Operation", "Push", "-Remote", "fixture", "-RefSpec", "HEAD:refs/heads/test",
        "-Authorization", "ExplicitUser", "-ConfirmExecution")
    Assert-True ($push.Json.forceMode -eq "None") "Ordinary push force mode mismatch."
    Assert-True ((Git $bare @("show-ref", "--verify", "--quiet", "refs/heads/test") -AllowFailure).ExitCode -eq 0) "Exact ref was not pushed."
    $forceDenied = Invoke-Workflow $repo @("-Operation", "Push", "-Remote", "fixture", "-RefSpec", "HEAD:refs/heads/test",
        "-Authorization", "ExplicitUser", "-ConfirmExecution", "-ForceMode", "ForceWithLease") -ExpectFailure
    Assert-True ($forceDenied.Json.error -match "ExplicitForceUser") "Force permission gate failed."
    $forcePrefixDenied = Invoke-Workflow $repo @("-Operation", "Push", "-Remote", "fixture", "-RefSpec", "+HEAD:refs/heads/test",
        "-Authorization", "ExplicitUser", "-ConfirmExecution") -ExpectFailure
    Assert-True ($forcePrefixDenied.Json.error -match "exact non-deletion") "Force refspec prefix bypassed the force gate."
    $forceAllowed = Invoke-Workflow $repo @("-Operation", "Push", "-Remote", "fixture", "-RefSpec", "HEAD:refs/heads/test",
        "-Authorization", "ExplicitUser", "-ConfirmExecution", "-ForceMode", "ForceWithLease", "-ForceAuthorization", "ExplicitForceUser")
    Assert-True ($forceAllowed.Json.forceMode -eq "ForceWithLease") "Separately authorized force-with-lease was not accepted."

    [void](Git $repo @("switch", "-q", "main"))
    Set-File $repo $activeFile "mc/1.21.1`nmc/1.22.0`n"
    [void](Git $repo @("add", $activeFile)); [void](Git $repo @("commit", "-q", "-m", "activate fixture branches"))
    [void](Git $repo @("branch", "mc/inactive"))
    $inactiveTip = (Git $repo @("rev-parse", "mc/inactive")).Text.Trim()
    Set-File $repo ".agents/propagate-main-only.txt" "main only`n"
    [void](Git $repo @("add", ".agents/propagate-main-only.txt"))
    [void](Git $repo @("commit", "-q", "-m", "Add main-only fixture"))
    $propagatedMain = (Git $repo @("rev-parse", "main")).Text.Trim()
    $worktreeCountBefore = @((Git $repo @("worktree", "list", "--porcelain")).Lines |
        Where-Object { $_.StartsWith("worktree ") }).Count
    $propagated = Invoke-Workflow $repo @("-Operation", "PropagateMain", "-Authorization", "ExplicitUser",
        "-ConfirmExecution", "-SkipBuild")
    Assert-True (@($propagated.Json.merged).Count -eq 2) "Main-only propagation did not merge every active branch."
    Assert-True ($propagated.Json.finalBranch -eq "main") "Main-only propagation did not return to main."
    Assert-True ([int]$propagated.Json.worktreeCount -eq $worktreeCountBefore) "Main-only propagation changed worktree count."
    foreach ($branch in @("mc/1.21.1", "mc/1.22.0")) {
        Assert-True ((Git $repo @("rev-parse", "$branch^2")).Text.Trim() -eq $propagatedMain) "Propagation was not a no-ff main merge."
        if ($mainOnly) {
            Assert-True ((Git $repo @("cat-file", "-e", "${branch}:$mainOnly") -AllowFailure).ExitCode -ne 0) `
                "Propagation retained a main-only path on $branch."
        }
    }
    Assert-True ((Git $repo @("rev-parse", "mc/inactive")).Text.Trim() -eq $inactiveTip) "Propagation moved an inactive branch."
    if ($mainOnly) {
        Set-File $repo $mainOnly "main-only update`n"
        [void](Git $repo @("add", $mainOnly)); [void](Git $repo @("commit", "-q", "-m", "Update main-only fixture"))
        $mainOnlyUpdate = (Git $repo @("rev-parse", "main")).Text.Trim()
        $mainOnlyPropagation = Invoke-Workflow $repo @("-Operation", "PropagateMain", "-Authorization", "ExplicitUser",
            "-ConfirmExecution", "-SkipBuild")
        Assert-True (@($mainOnlyPropagation.Json.merged).Count -eq 2) "Main-only modify/delete conflicts were not propagated."
        foreach ($branch in @("mc/1.21.1", "mc/1.22.0")) {
            Assert-True ((Git $repo @("rev-parse", "$branch^2")).Text.Trim() -eq $mainOnlyUpdate) `
                "Main-only update was not represented by a no-ff merge on $branch."
            Assert-True ((Git $repo @("cat-file", "-e", "${branch}:$mainOnly") -AllowFailure).ExitCode -ne 0) `
                "Main-only update reintroduced its path on $branch."
        }
    }
    $tipsBeforeOverlay = @{}
    foreach ($ref in @("main", "mc/1.21.1", "mc/1.22.0", "mc/inactive")) {
        $tipsBeforeOverlay[$ref] = (Git $repo @("rev-parse", $ref)).Text.Trim()
    }

    Set-File $repo ".agents/overlay-shared.txt" "shared overlay`n"
    Set-File $repo ".agents/new-overlay.txt" "new overlay`n"
    Remove-Item -LiteralPath (Join-Path $repo ".agents/delete-overlay.txt") -Force
    $overlayPaths = @(".agents")
    $propagateDirty = Invoke-Workflow $repo @("-Operation", "PropagateMain", "-Authorization", "ExplicitUser",
        "-ConfirmExecution", "-SkipBuild") -ExpectFailure
    Assert-True ($propagateDirty.Json.error -match "clean primary worktree") "Dirty main propagation was accepted."
    $noTargets = Invoke-Workflow $repo @("-Operation", "PrepareActiveWorktrees", "-Authorization", "ExplicitUser",
        "-ConfirmExecution", "-SkipBuild", "-Path", ".agents") -ExpectFailure
    Assert-True ($noTargets.Json.error -match "at least two") "Zero direct-edit branches were accepted."
    $oneTarget = Invoke-Workflow $repo @("-Operation", "PrepareActiveWorktrees", "-Authorization", "ExplicitUser",
        "-ConfirmExecution", "-SkipBuild", "-Path", ".agents", "-MinecraftBranch", "mc/1.21.1") -ExpectFailure
    Assert-True ($oneTarget.Json.error -match "at least two") "One direct-edit branch was accepted."
    $duplicateTarget = Invoke-Workflow $repo @("-Operation", "PrepareActiveWorktrees", "-Authorization", "ExplicitUser",
        "-ConfirmExecution", "-SkipBuild", "-Path", ".agents", "-MinecraftBranch", "mc/1.21.1,mc/1.21.1") -ExpectFailure
    Assert-True ($duplicateTarget.Json.error -match "duplicate") "Duplicate direct-edit branches were accepted."
    $inactiveTarget = Invoke-Workflow $repo @("-Operation", "PrepareActiveWorktrees", "-Authorization", "ExplicitUser",
        "-ConfirmExecution", "-SkipBuild", "-Path", ".agents", "-MinecraftBranch", "mc/1.21.1,mc/inactive") -ExpectFailure
    Assert-True ($inactiveTarget.Json.error -match "must be active") "Inactive direct-edit branch was accepted."
    $invalidTarget = Invoke-Workflow $repo @("-Operation", "PrepareActiveWorktrees", "-Authorization", "ExplicitUser",
        "-ConfirmExecution", "-SkipBuild", "-Path", ".agents", "-MinecraftBranch", "mc/1.21.1,invalid") -ExpectFailure
    Assert-True ($invalidTarget.Json.error -match "complete mc/<version>") "Invalid direct-edit branch was accepted."
    $prepared = Invoke-Workflow $repo (@("-Operation", "PrepareActiveWorktrees", "-Authorization", "ExplicitUser",
        "-ConfirmExecution", "-SkipBuild", "-Path") + $overlayPaths +
        @("-MinecraftBranch", "mc/1.21.1,mc/1.22.0"))
    Assert-True (@($prepared.Json.worktrees).Count -eq 2) "Active worktree preparation count mismatch."
    Assert-True ((Git $repo @("branch", "--show-current")).Text.Trim() -eq "main") "Preparation changed the primary branch."
    foreach ($ref in $tipsBeforeOverlay.Keys) {
        Assert-True ((Git $repo @("rev-parse", $ref)).Text.Trim() -eq $tipsBeforeOverlay[$ref]) "Overlay preparation moved $ref."
    }
    foreach ($item in @($prepared.Json.worktrees)) {
        $worktree = [string]$item.path
        Assert-True ([IO.File]::ReadAllText((Join-Path $worktree ".agents/overlay-shared.txt")) -eq "shared overlay`n") "Shared overlay missing."
        Assert-True ([IO.File]::ReadAllText((Join-Path $worktree ".agents/new-overlay.txt")) -eq "new overlay`n") "New overlay file missing."
        Assert-True (-not (Test-Path -LiteralPath (Join-Path $worktree ".agents/delete-overlay.txt"))) "Overlay deletion missing."
    }
    $mergeOnlyCapture = Invoke-Workflow $repo @("-Operation", "CaptureActiveWorktreeChanges",
        "-SnapshotPath", [string]$prepared.Json.snapshot) -ExpectFailure
    Assert-True ($mergeOnlyCapture.Json.error -match "no direct Minecraft edit") "Main overlay counted as a direct Minecraft edit."
    foreach ($item in @($prepared.Json.worktrees)) {
        $worktree = [string]$item.path
        Set-File $worktree (Join-Path $versionScope "active.txt") "version change for $($item.branch)`n"
    }

    $firstWorktree = [string]$prepared.Json.worktrees[0].path
    Set-File $firstWorktree ".agents/overlay-shared.txt" "illegal shared version change`n"
    $sharedRejected = Invoke-Workflow $repo @("-Operation", "CaptureActiveWorktreeChanges",
        "-SnapshotPath", [string]$prepared.Json.snapshot) -ExpectFailure
    Assert-True ($sharedRejected.Json.error -match "non-version path") "Shared overlay modification was not rejected."
    Set-File $firstWorktree ".agents/overlay-shared.txt" "shared overlay`n"

    $capture = Invoke-Workflow $repo @("-Operation", "CaptureActiveWorktreeChanges",
        "-SnapshotPath", [string]$prepared.Json.snapshot)
    Assert-True (@($capture.Json.worktrees).Count -eq 2) "Active worktree capture count mismatch."
    $mainCommit = Invoke-Workflow $repo (@("-Operation", "Commit", "-ExpectedBranch", "main",
        "-Message", "Apply shared overlay fixture", "-Authorization", "ExplicitUser", "-ConfirmExecution", "-SkipBuild",
        "-Path") + $overlayPaths)
    Assert-True ($mainCommit.Json.parent -eq $tipsBeforeOverlay["main"]) "Main overlay commit parent mismatch."

    $merge = Invoke-Workflow $repo @("-Operation", "MergeMain", "-Authorization", "ExplicitUser",
        "-ConfirmExecution", "-SnapshotPath", [string]$capture.Json.snapshot, "-SkipBuild")
    Assert-True (@($merge.Json.merged).Count -eq 2) "MergeMain did not update every active Minecraft branch."
    Assert-True ($merge.Json.finalBranch -eq "main") "MergeMain changed the primary branch."
    foreach ($item in @($merge.Json.merged)) {
        $branch = [string]$item.branch; $worktree = [string]$item.path
        Assert-True ((Git $repo @("merge-base", "--is-ancestor", "main", $branch) -AllowFailure).ExitCode -eq 0) "main was not merged into $branch."
        Assert-True ([IO.File]::ReadAllText((Join-Path $worktree (Join-Path $versionScope "active.txt"))) -eq
            "version change for $branch`n") "Version change was not restored for $branch."
        $versionCommit = Invoke-Workflow $worktree @("-Operation", "Commit", "-Path", $versionScope,
            "-ExpectedBranch", $branch, "-Message", "Apply active version fixture", "-Authorization", "ExplicitUser",
            "-ConfirmExecution", "-SkipBuild")
        Assert-True ($versionCommit.Json.branch -eq $branch) "Version commit branch mismatch."
    }
    Assert-True ((Git $repo @("rev-parse", "mc/inactive")).Text.Trim() -eq $inactiveTip) "Inactive branch moved."

    $dirtyWorktree = [string]$merge.Json.merged[0].path
    Set-File $dirtyWorktree (Join-Path $versionScope "dirty.txt") "retain me`n"
    $cleanup = Invoke-Workflow $repo @("-Operation", "CleanupActiveWorktrees", "-Authorization", "ExplicitUser",
        "-ConfirmExecution")
    Assert-True (@($cleanup.Json.removed).Count -eq 1) "Cleanup should remove one clean worktree."
    Assert-True (@($cleanup.Json.retained).Count -eq 1) "Cleanup should retain one dirty worktree."
    Remove-Item -LiteralPath (Join-Path $dirtyWorktree (Join-Path $versionScope "dirty.txt")) -Force
    $cleanupFinal = Invoke-Workflow $repo @("-Operation", "CleanupActiveWorktrees", "-Authorization", "ExplicitUser",
        "-ConfirmExecution")
    Assert-True (@($cleanupFinal.Json.removed).Count -eq 1) "Final cleanup did not remove the cleaned worktree."

    $mixedBase = [IO.File]::ReadAllText((Join-Path $repo $mixed))
    Set-File $repo $mixed ($mixedBase + "main mixed overlay`n")
    $mixedPrepared = Invoke-Workflow $repo @("-Operation", "PrepareActiveWorktrees", "-Path", $mixed,
        "-MinecraftBranch", "mc/1.21.1,mc/1.22.0",
        "-Authorization", "ExplicitUser", "-ConfirmExecution", "-SkipBuild")
    foreach ($item in @($mixedPrepared.Json.worktrees)) {
        $worktree = [string]$item.path
        Assert-True ([IO.File]::ReadAllText((Join-Path $worktree $mixed)) -eq
            ($mixedBase + "main mixed overlay`n")) "Mixed main overlay missing."
        Set-File $worktree $mixed ($mixedBase + "main mixed overlay`nversion mixed $($item.branch)`n")
    }
    $mixedCapture = Invoke-Workflow $repo @("-Operation", "CaptureActiveWorktreeChanges",
        "-SnapshotPath", [string]$mixedPrepared.Json.snapshot)
    [void](Invoke-Workflow $repo @("-Operation", "Commit", "-Path", $mixed, "-ExpectedBranch", "main",
        "-Message", "Apply mixed overlay fixture", "-Authorization", "ExplicitUser", "-ConfirmExecution", "-SkipBuild"))
    $mixedMerge = Invoke-Workflow $repo @("-Operation", "MergeMain", "-Authorization", "ExplicitUser",
        "-ConfirmExecution", "-SnapshotPath", [string]$mixedCapture.Json.snapshot, "-SkipBuild")
    foreach ($item in @($mixedMerge.Json.merged)) {
        $branch = [string]$item.branch; $worktree = [string]$item.path
        Assert-True ([IO.File]::ReadAllText((Join-Path $worktree $mixed)) -eq
            ($mixedBase + "main mixed overlay`nversion mixed $branch`n")) "Mixed version hunk was not restored."
        [void](Invoke-Workflow $worktree @("-Operation", "Commit", "-Path", $mixed, "-ExpectedBranch", $branch,
            "-Message", "Apply mixed version fixture", "-Authorization", "ExplicitUser", "-ConfirmExecution", "-SkipBuild"))
    }
    $mixedCleanup = Invoke-Workflow $repo @("-Operation", "CleanupActiveWorktrees", "-Authorization", "ExplicitUser",
        "-ConfirmExecution")
    Assert-True (@($mixedCleanup.Json.removed).Count -eq 2) "Mixed worktree cleanup count mismatch."

    $cleanPrepared = Invoke-Workflow $repo @("-Operation", "PrepareActiveWorktrees", "-Authorization", "ExplicitUser",
        "-ConfirmExecution", "-SkipBuild", "-MinecraftBranch", "mc/1.21.1,mc/1.22.0")
    Assert-True (@($cleanPrepared.Json.worktrees).Count -eq 2) "Clean active worktree preparation count mismatch."
    $propagateWithWorktrees = Invoke-Workflow $repo @("-Operation", "PropagateMain", "-Authorization", "ExplicitUser",
        "-ConfirmExecution", "-SkipBuild") -ExpectFailure
    Assert-True ($propagateWithWorktrees.Json.error -match "additional or unexpected") "Propagation accepted extra worktrees."
    foreach ($item in @($cleanPrepared.Json.worktrees)) {
        Assert-True (-not (Git ([string]$item.path) @("status", "--porcelain=v1")).Lines.Count) "Clean preparation created pending changes."
        Set-File ([string]$item.path) (Join-Path $versionScope "no-main.txt") "no main $($item.branch)`n"
    }
    $noMainCapture = Invoke-Workflow $repo @("-Operation", "CaptureActiveWorktreeChanges",
        "-SnapshotPath", [string]$cleanPrepared.Json.snapshot)
    $noMainMerge = Invoke-Workflow $repo @("-Operation", "MergeMain", "-Authorization", "ExplicitUser",
        "-ConfirmExecution", "-SnapshotPath", [string]$noMainCapture.Json.snapshot, "-SkipBuild")
    foreach ($item in @($noMainMerge.Json.merged)) {
        [void](Invoke-Workflow ([string]$item.path) @("-Operation", "Commit", "-Path", $versionScope,
            "-ExpectedBranch", [string]$item.branch, "-Message", "Apply no-main version fixture",
            "-Authorization", "ExplicitUser", "-ConfirmExecution", "-SkipBuild"))
    }
    $noMainCleanup = Invoke-Workflow $repo @("-Operation", "CleanupActiveWorktrees", "-Authorization", "ExplicitUser",
        "-ConfirmExecution")
    Assert-True (@($noMainCleanup.Json.removed).Count -eq 2) "No-main worktree cleanup count mismatch."

    $audit = Invoke-Workflow $repo @("-Operation", "Audit")
    Assert-True ($audit.Json.success) "Final fixture audit failed."
    Assert-True ($audit.Json.policy.mainExists) "Audit did not enforce the required main branch."
    Assert-True (@($audit.Json.policy.activeMinecraftBranches).Count -eq 2) "Audit active branch count mismatch."
    Assert-True (@($audit.Json.policy.inactiveMinecraftBranches) -contains "mc/inactive") "Audit inactive branch missing."
    Assert-True (@($audit.Json.policy.contractVersionLines).Count -eq 0) "Unexpected guarded version lines."

    $activePath = Join-Path $repo $activeFile
    $activeBytes = [IO.File]::ReadAllBytes($activePath)
    try {
        Set-File $repo $activeFile "mc/1.21.1`nmc/1.21.1`n"
        $duplicateActive = Invoke-Workflow $repo @("-Operation", "Audit", "-AllowDirty") -ExpectFailure
        Assert-True ($duplicateActive.Json.error -match "Duplicate active Minecraft branch") "Duplicate active branch was accepted."
        Set-File $repo $activeFile "mc/missing`n"
        $missingActive = Invoke-Workflow $repo @("-Operation", "Audit", "-AllowDirty") -ExpectFailure
        Assert-True ($missingActive.Json.error -match "does not exist") "Missing active branch was accepted."
        Set-File $repo $activeFile "not-a-minecraft-branch`n"
        $invalidActive = Invoke-Workflow $repo @("-Operation", "Audit", "-AllowDirty") -ExpectFailure
        Assert-True ($invalidActive.Json.error -match "Invalid active Minecraft branch") "Invalid active branch was accepted."
    } finally {
        [IO.File]::WriteAllBytes($activePath, $activeBytes)
    }

    $profilePath = Join-Path $repo ".agents/repository-profile.psd1"
    $profileText = [IO.File]::ReadAllText($profilePath)
    Assert-ProfileRejected $repo ([regex]::Replace($profileText,
        '(?m)^\s*RepositoryVerifier\s*=.*\r?\n', "")) "missing required keys"
    Assert-ProfileRejected $repo ([regex]::Replace($profileText, '\}\s*$',
        "    UnexpectedKey = `"value`"`n}`n")) "unknown keys"
    Assert-ProfileRejected $repo ($profileText.Replace(
        "        `"$([string]$profile.ForbiddenTrackedPatterns[0])`"",
        "        `"[`"")) "Invalid ForbiddenTrackedPatterns regex"
    $ownershipPath = Join-Path $repo ".agents/branch-ownership.psd1"
    $ownershipText = [IO.File]::ReadAllText($ownershipPath)
    $sharedRoot = [string]$ownership.SharedPaths[1]
    Assert-OwnershipRejected $repo ($ownershipText.Replace(
        "        `"$sharedRoot`"", "        `"C:/absolute`"")) "unsafe repository-relative path"
    Assert-OwnershipRejected $repo ($ownershipText.Replace(
        "        `"$mixed`"", "        `"$sharedRoot`"")) "appears in both"

    $propagateConflict = Join-Path $testRoot "propagate-conflict"
    & $git init -q -b main $propagateConflict
    [void](Git $propagateConflict @("config", "user.name", "Workflow Fixture"))
    [void](Git $propagateConflict @("config", "user.email", "fixture@example.invalid"))
    [void](Git $propagateConflict @("config", "core.autocrlf", "false"))
    Install-RepositoryProfile $propagateConflict
    Set-File $propagateConflict $shared "base`n"
    Set-File $propagateConflict $mixed "mixed`n"
    Set-File $propagateConflict $activeFile "mc/1.21.1`nmc/1.22.0`n"
    Set-File $propagateConflict "gradle.properties" ([IO.File]::ReadAllText((Join-Path $repo "gradle.properties")))
    Set-File $propagateConflict "gradlew.bat" "@echo off`r`nexit /b 0`r`n"
    [void](Git $propagateConflict @("add", "."))
    [void](Git $propagateConflict @("commit", "-q", "-m", "propagation base"))
    foreach ($branch in @("mc/1.21.1", "mc/1.22.0")) {
        [void](Git $propagateConflict @("switch", "-q", "-c", $branch, "main"))
        Set-File $propagateConflict $shared "$branch`n"
        [void](Git $propagateConflict @("add", $shared))
        [void](Git $propagateConflict @("commit", "-q", "-m", "conflict $branch"))
    }
    [void](Git $propagateConflict @("switch", "-q", "main"))
    Set-File $propagateConflict $shared "main conflict`n"
    [void](Git $propagateConflict @("add", $shared))
    [void](Git $propagateConflict @("commit", "-q", "-m", "main conflict"))
    $conflictRefsBefore = (Git $propagateConflict @("for-each-ref", "--format=%(refname)%09%(objectname)", "refs/heads")).Text
    $propagationConflict = Invoke-Workflow $propagateConflict @("-Operation", "PropagateMain",
        "-Authorization", "ExplicitUser", "-ConfirmExecution", "-SkipBuild") -ExpectFailure
    Assert-True ($propagationConflict.Json.error -match "preflight merge failed") "Propagation conflict was not rejected in preflight."
    Assert-True ((Git $propagateConflict @("for-each-ref", "--format=%(refname)%09%(objectname)", "refs/heads")).Text -eq
        $conflictRefsBefore) "Propagation conflict moved refs."
    Assert-True ((Git $propagateConflict @("branch", "--show-current")).Text.Trim() -eq "main") "Conflict preflight changed branch."

    $noMain = Join-Path $testRoot "no-main"
    & $git init -q -b mc/1.21.1 $noMain
    [void](Git $noMain @("config", "user.name", "Workflow Fixture"))
    [void](Git $noMain @("config", "user.email", "fixture@example.invalid"))
    [void](Git $noMain @("config", "core.autocrlf", "false"))
    Install-RepositoryProfile $noMain
    Set-File $noMain $shared "shared`n"
    Set-File $noMain $mixed "mixed`n"
    Set-File $noMain "gradle.properties" "modVersion=0.1.0`nloaderVersion=1`nminecraftVersion=1.21.1`n"
    Set-File $noMain "gradlew.bat" "@echo off`r`nexit /b 0`r`n"
    Set-File $noMain (Join-Path $versionScope "value.txt") "base`n"
    [void](Git $noMain @("add", "."))
    [void](Git $noMain @("commit", "-q", "-m", "no-main base"))

    $noMainInspect = Invoke-Workflow $noMain @("-Operation", "Inspect")
    Assert-True (-not $noMainInspect.Json.mainExists) "Inspect should report the missing main branch."
    $refsBefore = (Git $noMain @("for-each-ref", "--format=%(refname)%09%(objectname)", "refs/heads")).Text
    Set-File $noMain (Join-Path $versionScope "value.txt") "pending`n"
    $noMainOperations = @(
        @("-Operation", "Validate", "-AllowDirty", "-SkipBuild"),
        @("-Operation", "Audit", "-AllowDirty"),
        @("-Operation", "PrepareMinecraftBranch", "-MinecraftVersion", "1.22.0"),
        @("-Operation", "PrepareActiveWorktrees", "-Authorization", "ExplicitUser", "-ConfirmExecution"),
        @("-Operation", "CleanupActiveWorktrees", "-Authorization", "ExplicitUser", "-ConfirmExecution"),
        @("-Operation", "PropagateMain", "-Authorization", "ExplicitUser", "-ConfirmExecution", "-SkipBuild"),
        @("-Operation", "Commit", "-Path", $versionScope, "-ExpectedBranch", "mc/1.21.1",
            "-Message", "Must not commit", "-Authorization", "ExplicitUser", "-ConfirmExecution", "-SkipBuild"),
        @("-Operation", "MergeMain", "-Authorization", "ExplicitUser", "-ConfirmExecution", "-FinalBranch", "mc/1.21.1", "-SkipBuild"),
        @("-Operation", "Push", "-Remote", "missing", "-RefSpec", "HEAD:refs/heads/test",
            "-Authorization", "ExplicitUser", "-ConfirmExecution")
    )
    foreach ($arguments in $noMainOperations) {
        $failure = Invoke-Workflow $noMain $arguments -ExpectFailure
        Assert-True ($failure.Json.error -eq "Required branch 'main' is missing.") "Missing-main failure was not fail-closed."
    }
    $refsAfter = (Git $noMain @("for-each-ref", "--format=%(refname)%09%(objectname)", "refs/heads")).Text
    Assert-True ($refsAfter -eq $refsBefore) "A missing-main operation changed refs."
    Assert-True (-not (Git $noMain @("diff", "--cached", "--name-only")).Lines.Count) "A missing-main operation changed staging."
    Assert-True ((Git $noMain @("branch", "--show-current")).Text.Trim() -eq "mc/1.21.1") "A missing-main operation changed branches."

    [ordered]@{ operation = "TestRepositoryWorkflow"; passed = $script:Passed; status = "PASS" } | ConvertTo-Json -Compress
} finally {
    if (Test-Path -LiteralPath $testRoot) { Remove-Item -LiteralPath $testRoot -Recurse -Force -ErrorAction SilentlyContinue }
}
