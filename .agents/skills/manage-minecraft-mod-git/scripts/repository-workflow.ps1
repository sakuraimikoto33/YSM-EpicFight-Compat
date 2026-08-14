[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateSet("Inspect", "Classify", "PrepareMinecraftBranch", "PrepareActiveWorktrees",
        "CaptureActiveWorktreeChanges", "CleanupActiveWorktrees", "Validate", "Snapshot",
        "CompareSnapshot", "Commit", "MergeMain", "PropagateMain", "Push", "Audit")]
    [string]$Operation,
    [string]$RepoRoot = "",
    [string[]]$Path = @(),
    [string]$ExpectedBranch = "",
    [string]$Message = "",
    [ValidateSet("ExplicitUser", "TaskBoundary")]
    [string]$Authorization,
    [switch]$ConfirmExecution,
    [switch]$ContractVersionAuthorized,
    [string]$DependencyVersionReason = "",
    [string]$MinecraftVersion = "",
    [string[]]$MinecraftBranch = @(),
    [string]$BaseBranch = "",
    [string]$SnapshotPath = "",
    [string]$FinalBranch = "",
    [string]$Remote = "",
    [string]$RefSpec = "",
    [ValidateSet("None", "ForceWithLease", "Force")]
    [string]$ForceMode = "None",
    [string]$ForceAuthorization = "",
    [string[]]$ValidationRepositoryRoot = @(),
    [switch]$AllowDirty,
    [switch]$SkipBuild
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Invoke-Git {
    param([Parameter(Mandatory)][string[]]$Arguments, [string]$Root = $script:RepositoryRoot,
        [switch]$AllowFailure)
    $start = [Diagnostics.ProcessStartInfo]::new("git")
    $start.UseShellExecute = $false
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    [void]$start.ArgumentList.Add("-C")
    [void]$start.ArgumentList.Add($Root)
    foreach ($argument in $Arguments) { [void]$start.ArgumentList.Add($argument) }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $start
    [void]$process.Start()
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    $process.WaitForExit()
    $text = $stdout.GetAwaiter().GetResult().TrimEnd([char[]]"`r`n")
    $errorText = $stderr.GetAwaiter().GetResult().TrimEnd([char[]]"`r`n")
    $lines = if ($text) { @($text -split "`r?`n") } else { @() }
    if ($process.ExitCode -ne 0 -and -not $AllowFailure) {
        throw "git $($Arguments -join ' ') failed ($($process.ExitCode)): $errorText $text"
    }
    [pscustomobject]@{ ExitCode = $process.ExitCode; Lines = $lines; Text = $text; ErrorText = $errorText }
}
function Assert-PolicyKeys {
    param([Collections.IDictionary]$Data, [string[]]$Required, [string[]]$Optional = @(), [string]$Label)
    $missing = @($Required | Where-Object { -not $Data.Contains($_) })
    $known = @($Required) + @($Optional)
    $unknown = @($Data.Keys | Where-Object { $_ -notin $known })
    if ($missing.Count) { throw "$Label is missing required keys: $($missing -join ', ')." }
    if ($unknown.Count) { throw "$Label contains unknown keys: $($unknown -join ', ')." }
}
function Assert-RelativePolicyPath {
    param([string]$Value, [string]$Label, [switch]$AllowPrefix)
    $normalized = $Value.Replace("\", "/").Trim()
    if (-not $normalized -or [IO.Path]::IsPathRooted($normalized) -or $normalized -eq "." -or
        $normalized -match '(^|/)\.\.(/|$)' -or $normalized.IndexOfAny([char[]]"*?[") -ge 0 -or
        $normalized.StartsWith(":") -or (-not $AllowPrefix -and $normalized.EndsWith("-"))) {
        throw "$Label contains an unsafe repository-relative path: '$Value'."
    }
    $normalized
}
function Load-RepositoryPolicy {
    $corePath = Join-Path $PSScriptRoot "repository-policy.psd1"
    $profilePath = Join-Path $script:RepositoryRoot ".agents/repository-profile.psd1"
    $ownershipPath = Join-Path $script:RepositoryRoot ".agents/branch-ownership.psd1"
    if (-not (Test-Path -LiteralPath $profilePath -PathType Leaf)) { throw "Repository profile missing: $profilePath" }
    if (-not (Test-Path -LiteralPath $ownershipPath -PathType Leaf)) { throw "Branch ownership policy missing: $ownershipPath" }
    $core = Import-PowerShellDataFile -LiteralPath $corePath
    $profile = Import-PowerShellDataFile -LiteralPath $profilePath
    $ownership = Import-PowerShellDataFile -LiteralPath $ownershipPath
    $coreKeys = @("MainBranch", "MinecraftBranchPattern", "ActiveMinecraftBranchesFile", "ContractVersionPatterns",
        "DependencyVersionPatterns")
    $profileKeys = @("Name", "ForbiddenTrackedPatterns", "ValidationRepositories", "RepositoryVerifier",
        "RepositoryVerifierProfiles", "MainValidation", "MinecraftValidation")
    $ownershipKeys = @("MainOnlyPaths", "SharedPaths", "MinecraftPaths", "MixedPaths")
    Assert-PolicyKeys -Data $core -Required $coreKeys -Label "Core repository policy"
    Assert-PolicyKeys -Data $profile -Required $profileKeys -Label "Repository profile"
    Assert-PolicyKeys -Data $ownership -Required $ownershipKeys -Label "Branch ownership policy"
    if ([string]$profile.Name -notmatch '^[0-9A-Za-z][0-9A-Za-z._-]*$') {
        throw "Repository profile Name must be a safe logical identifier."
    }
    if ([string]$core.MainBranch -ne "main") { throw "Core repository policy must use main as MainBranch." }
    try { [void][regex]::new([string]$core.MinecraftBranchPattern) }
    catch { throw "Invalid MinecraftBranchPattern: $($_.Exception.Message)" }
    foreach ($property in @("ContractVersionPatterns", "DependencyVersionPatterns")) {
        foreach ($pattern in @($core[$property])) {
            try { [void][regex]::new([string]$pattern) }
            catch { throw "Invalid $property regex '$pattern': $($_.Exception.Message)" }
        }
    }
    foreach ($pattern in @($profile.ForbiddenTrackedPatterns)) {
        try { [void][regex]::new([string]$pattern) }
        catch { throw "Invalid ForbiddenTrackedPatterns regex '$pattern': $($_.Exception.Message)" }
    }
    $dependencies = @($profile.ValidationRepositories | ForEach-Object { ([string]$_).Trim() })
    if (@($dependencies | Where-Object { $_ -notmatch '^[0-9A-Za-z][0-9A-Za-z._-]*$' }).Count) {
        throw "ValidationRepositories contains an unsafe logical repository name."
    }
    if (@($dependencies | Sort-Object -Unique).Count -ne $dependencies.Count) {
        throw "ValidationRepositories contains duplicate entries."
    }
    $verifierProfiles = @($profile.RepositoryVerifierProfiles | ForEach-Object { ([string]$_).Trim() })
    if (@($verifierProfiles | Where-Object { $_ -notin @("Main", "Minecraft") }).Count -or
        @($verifierProfiles | Sort-Object -Unique).Count -ne $verifierProfiles.Count) {
        throw "RepositoryVerifierProfiles must contain unique Main or Minecraft values."
    }
    foreach ($validationKey in @("MainValidation", "MinecraftValidation")) {
        foreach ($task in @($profile[$validationKey])) {
            if ([string]$task -notmatch '^[0-9A-Za-z][0-9A-Za-z:._-]*$') {
                throw "$validationKey contains an unsafe Gradle task name."
            }
        }
    }
    $seen = @{}
    foreach ($category in $ownershipKeys) {
        $values = @($ownership[$category])
        if ($category -ne "MainOnlyPaths" -and -not $values.Count) {
            throw "Branch ownership policy $category must not be empty."
        }
        foreach ($value in $values) {
            $normalized = if ($category -eq "MainOnlyPaths") {
                Assert-RelativePolicyPath -Value ([string]$value) -Label $category
            } else {
                Assert-RelativePolicyPath -Value ([string]$value) -Label $category -AllowPrefix
            }
            $key = $normalized.ToLowerInvariant()
            if ($seen.ContainsKey($key)) {
                throw "Ownership path '$normalized' appears in both $($seen[$key]) and $category."
            }
            $seen[$key] = $category
        }
    }
    $verifier = Assert-RelativePolicyPath -Value ([string]$profile.RepositoryVerifier) -Label "RepositoryVerifier"
    $verifierPath = [IO.Path]::GetFullPath((Join-Path $script:RepositoryRoot $verifier))
    $rootPrefix = [IO.Path]::GetFullPath($script:RepositoryRoot).TrimEnd([IO.Path]::DirectorySeparatorChar) +
        [IO.Path]::DirectorySeparatorChar
    if (-not $verifierPath.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase) -or
        -not (Test-Path -LiteralPath $verifierPath -PathType Leaf)) {
        throw "RepositoryVerifier is missing or escapes the repository: $verifier"
    }
    $merged = [ordered]@{}
    foreach ($key in $coreKeys) { $merged[$key] = $core[$key] }
    foreach ($key in $profileKeys) { $merged[$key] = $profile[$key] }
    foreach ($key in $ownershipKeys) { $merged[$key] = $ownership[$key] }
    $merged
}

if (-not $RepoRoot) {
    $root = @(& git rev-parse --show-toplevel 2>&1)
    if ($LASTEXITCODE -ne 0) { throw "Not inside a Git worktree: $($root -join [Environment]::NewLine)" }
    $RepoRoot = "$($root[-1])"
}
$script:RepositoryRoot = (Resolve-Path -LiteralPath $RepoRoot).Path
$reported = (Invoke-Git -Arguments @("rev-parse", "--show-toplevel")).Text.Trim()
if ([IO.Path]::GetFullPath($reported) -ne [IO.Path]::GetFullPath($script:RepositoryRoot)) {
    throw "Repository root mismatch: expected '$script:RepositoryRoot', found '$reported'."
}
function Write-Result { param($Value) $Value | ConvertTo-Json -Depth 14 -Compress }
try {
    $script:Policy = Load-RepositoryPolicy
    $script:LogRoot = Join-Path ([IO.Path]::GetTempPath()) (($script:Policy.Name -replace '[^A-Za-z0-9.-]', '-') + "-workflow")
} catch {
    Write-Result -Value ([ordered]@{ operation = $Operation; success = $false; error = $_.Exception.Message })
    exit 1
}
function Current-Branch {
    param([string]$Root = $script:RepositoryRoot)
    $value = Invoke-Git -Root $Root -Arguments @("symbolic-ref", "--quiet", "--short", "HEAD") -AllowFailure
    if ($value.ExitCode -eq 0) { return $value.Text.Trim() }
    return $null
}
function Resolve-ValidationRepositoryMap {
    $required = @($script:Policy.ValidationRepositories)
    $resolved = @{}
    foreach ($candidate in @($ValidationRepositoryRoot)) {
        if ([string]::IsNullOrWhiteSpace($candidate)) { continue }
        $root = (Resolve-Path -LiteralPath $candidate).Path
        $reported = (Invoke-Git -Root $root -Arguments @("rev-parse", "--show-toplevel")).Text.Trim()
        if ([IO.Path]::GetFullPath($reported) -ne [IO.Path]::GetFullPath($root)) {
            throw "Validation repository root mismatch: expected '$root', found '$reported'."
        }
        $profilePath = Join-Path $root ".agents/repository-profile.psd1"
        if (-not (Test-Path -LiteralPath $profilePath -PathType Leaf)) {
            throw "Validation repository profile missing: $profilePath"
        }
        $profile = Import-PowerShellDataFile -LiteralPath $profilePath
        $name = ([string]$profile.Name).Trim()
        if ($name -notmatch '^[0-9A-Za-z][0-9A-Za-z._-]*$') { throw "Unsafe validation repository Name '$name'." }
        if ($resolved.ContainsKey($name)) { throw "Duplicate validation repository Name '$name'." }
        $resolved[$name] = $root
    }
    $missing = @($required | Where-Object { -not $resolved.ContainsKey([string]$_) })
    if ($missing.Count -and -not $SkipBuild) {
        throw "Missing explicit -ValidationRepositoryRoot values for: $($missing -join ', ')."
    }
    $resolved
}
function Branch-Exists {
    param([Parameter(Mandatory)][string]$Name)
    (Invoke-Git -Arguments @("show-ref", "--verify", "--quiet", "refs/heads/$Name") -AllowFailure).ExitCode -eq 0
}
function Assert-MainExists {
    $main = [string]$script:Policy.MainBranch
    if (-not (Branch-Exists -Name $main)) { throw "Required branch '$main' is missing." }
}
function Minecraft-Branches {
    @((Invoke-Git -Arguments @("for-each-ref", "--format=%(refname:short)", "refs/heads")).Lines |
        Where-Object { $_ -match [string]$script:Policy.MinecraftBranchPattern } | Sort-Object -Unique)
}
function Worktree-Records {
    $records = [Collections.Generic.List[object]]::new()
    $path = $null; $head = $null; $branch = $null
    foreach ($line in @((Invoke-Git -Arguments @("worktree", "list", "--porcelain")).Lines) + "") {
        if (-not $line) {
            if ($path) {
                $records.Add([pscustomobject]@{ Path = [IO.Path]::GetFullPath($path); Head = $head; Branch = $branch })
            }
            $path = $null; $head = $null; $branch = $null
        } elseif ($line.StartsWith("worktree ")) {
            $path = $line.Substring(9)
        } elseif ($line.StartsWith("HEAD ")) {
            $head = $line.Substring(5)
        } elseif ($line.StartsWith("branch refs/heads/")) {
            $branch = $line.Substring(18)
        }
    }
    @($records)
}
function Active-Branch-State {
    $errors = [Collections.Generic.List[string]]::new()
    $relative = [string]$script:Policy.ActiveMinecraftBranchesFile
    $main = [string]$script:Policy.MainBranch
    $source = "main-ref"
    $text = $null
    $mainRecord = @(Worktree-Records | Where-Object Branch -eq $main)
    if ($mainRecord.Count -gt 1) {
        $errors.Add("Branch '$main' is checked out in more than one worktree.")
    } elseif ($mainRecord.Count -eq 1) {
        $candidate = Join-Path $mainRecord[0].Path $relative
        $source = "main-worktree"
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            $text = [IO.File]::ReadAllText($candidate)
        } else {
            $errors.Add("Active Minecraft branch file is missing from the main worktree: $relative")
        }
    } else {
        $shown = Invoke-Git -Arguments @("show", "${main}:$relative") -AllowFailure
        if ($shown.ExitCode -eq 0) { $text = $shown.Text }
        else { $errors.Add("Active Minecraft branch file is missing from ${main}: $relative") }
    }
    $branches = [Collections.Generic.List[string]]::new()
    $seen = @{}
    if ($null -ne $text) {
        foreach ($raw in @($text -split "`r?`n")) {
            $value = $raw.Trim()
            if (-not $value) { continue }
            if ($value -notmatch [string]$script:Policy.MinecraftBranchPattern) {
                $errors.Add("Invalid active Minecraft branch '$value'.")
                continue
            }
            if ($seen.ContainsKey($value)) {
                $errors.Add("Duplicate active Minecraft branch '$value'.")
                continue
            }
            $seen[$value] = $true
            $branches.Add($value)
            if (-not (Branch-Exists -Name $value)) { $errors.Add("Active Minecraft branch does not exist: $value") }
        }
    }
    if (-not $branches.Count) { $errors.Add("At least one active Minecraft branch is required.") }
    [pscustomobject]@{ File = $relative; Source = $source; Branches = @($branches); Errors = @($errors);
        Valid = -not [bool]$errors.Count }
}
function Active-Minecraft-Branches {
    $state = Active-Branch-State
    if (-not $state.Valid) { throw ($state.Errors -join " ") }
    @($state.Branches)
}
function Selected-Minecraft-Edit-Branches {
    $selected = @($MinecraftBranch | ForEach-Object { @(([string]$_) -split ',') } |
        ForEach-Object { ([string]$_).Trim() })
    if ($selected.Count -lt 2) {
        throw "PrepareActiveWorktrees requires at least two directly edited -MinecraftBranch values."
    }
    if (@($selected | Where-Object { $_ -notmatch [string]$script:Policy.MinecraftBranchPattern }).Count) {
        throw "Every -MinecraftBranch must be a complete mc/<version> branch name."
    }
    if (@($selected | Sort-Object -Unique).Count -ne $selected.Count) {
        throw "PrepareActiveWorktrees refuses duplicate -MinecraftBranch values."
    }
    $active = @(Active-Minecraft-Branches)
    $inactive = @($selected | Where-Object { $_ -notin $active })
    if ($inactive.Count) {
        throw "Direct edit branches must be active: $($inactive -join ', ')."
    }
    @($selected)
}
function Managed-Worktree-Root {
    $parent = Split-Path -Parent $script:RepositoryRoot
    [IO.Path]::GetFullPath((Join-Path (Join-Path $parent ".worktrees") (Split-Path -Leaf $script:RepositoryRoot)))
}
function Managed-Worktree-Path {
    param([Parameter(Mandatory)][string]$Branch)
    if ($Branch -notmatch '^mc/(.+)$') { throw "Not a Minecraft branch: $Branch" }
    [IO.Path]::GetFullPath((Join-Path (Managed-Worktree-Root) $Matches[1]))
}
function Pending-State {
    param([string]$Root = $script:RepositoryRoot)
    $staged = @((Invoke-Git -Root $Root -Arguments @("diff", "--cached", "--name-only")).Lines | Where-Object { $_ })
    $unstaged = @((Invoke-Git -Root $Root -Arguments @("diff", "--name-only")).Lines | Where-Object { $_ })
    $untracked = @((Invoke-Git -Root $Root -Arguments @("ls-files", "--others", "--exclude-standard")).Lines | Where-Object { $_ })
    [pscustomobject]@{ Staged = $staged; Unstaged = $unstaged; Untracked = $untracked;
        All = @($staged + $unstaged + $untracked | Sort-Object -Unique) }
}
function In-Progress {
    param([string]$Root = $script:RepositoryRoot)
    $found = [Collections.Generic.List[string]]::new()
    foreach ($name in @("MERGE_HEAD", "CHERRY_PICK_HEAD", "REVERT_HEAD", "BISECT_LOG", "rebase-merge", "rebase-apply")) {
        $gitPath = (Invoke-Git -Root $Root -Arguments @("rev-parse", "--git-path", $name)).Text.Trim()
        if (-not [IO.Path]::IsPathRooted($gitPath)) { $gitPath = Join-Path $Root $gitPath }
        if (Test-Path -LiteralPath $gitPath) { $found.Add($name) }
    }
    @($found)
}
function Assert-StableGit {
    $progress = @(In-Progress)
    if ($progress.Count) { throw "Git operation already in progress: $($progress -join ', ')." }
}
function Normalize-Paths {
    param([Parameter(Mandatory)][string[]]$Values)
    if (-not $Values.Count) { throw "At least one exact repository-relative -Path is required." }
    $result = [Collections.Generic.List[string]]::new()
    foreach ($item in $Values) {
        $value = $item.Replace("\", "/").Trim()
        while ($value.StartsWith("./")) { $value = $value.Substring(2) }
        $value = $value.TrimEnd("/")
        if (-not $value -or [IO.Path]::IsPathRooted($value) -or $value -eq "." -or
            $value -match '(^|/)\.\.(/|$)' -or $value.IndexOfAny([char[]]"*?[") -ge 0 -or
            $value.StartsWith(":")) { throw "Unsafe or non-exact repository path: '$item'." }
        $result.Add($value)
    }
    @($result | Sort-Object -Unique)
}
function In-Scope {
    param([string]$Candidate, [string[]]$Scopes)
    foreach ($scope in $Scopes) {
        if ($Candidate -eq $scope -or $Candidate.StartsWith("$scope/", [StringComparison]::Ordinal)) { return $true }
    }
    return $false
}
function Policy-Category {
    param([Parameter(Mandatory)][string]$Candidate)
    foreach ($pattern in @($script:Policy.ForbiddenTrackedPatterns)) {
        if ($Candidate -match $pattern) { return "Forbidden" }
    }
    foreach ($prefix in @($script:Policy.MainOnlyPaths)) {
        if ($Candidate -eq $prefix -or $Candidate.StartsWith("$prefix/", [StringComparison]::Ordinal)) {
            return "MainOnly"
        }
    }
    foreach ($prefix in @($script:Policy.SharedPaths)) {
        if ($Candidate -eq $prefix -or $Candidate.StartsWith("$prefix/", [StringComparison]::Ordinal) -or
            ($prefix.EndsWith("-") -and $Candidate.StartsWith($prefix, [StringComparison]::Ordinal))) { return "Shared" }
    }
    foreach ($prefix in @($script:Policy.MinecraftPaths)) {
        if ($Candidate -eq $prefix -or $Candidate.StartsWith("$prefix/", [StringComparison]::Ordinal) -or
            ($prefix.EndsWith("-") -and $Candidate.StartsWith($prefix, [StringComparison]::Ordinal))) { return "Minecraft" }
    }
    foreach ($prefix in @($script:Policy.MixedPaths)) {
        if ($Candidate -eq $prefix -or $Candidate.StartsWith("$prefix/", [StringComparison]::Ordinal)) { return "Mixed" }
    }
    return "Unknown"
}
function MainOnly-Paths {
    $values = @($script:Policy.MainOnlyPaths)
    if (-not $values.Count) { return @() }
    @(Normalize-Paths -Values $values)
}
function Shared-ComparisonPathspecs {
    $result = [Collections.Generic.List[string]]::new()
    foreach ($path in @(Normalize-Paths -Values @($script:Policy.SharedPaths | Where-Object { -not $_.EndsWith('-') }))) {
        $result.Add($path)
    }
    foreach ($path in @(MainOnly-Paths)) { $result.Add(":(exclude)$path") }
    @($result)
}
function Test-MainOnlyPathPresent {
    param([Parameter(Mandatory)][string]$Revision, [string]$Root = $script:RepositoryRoot)
    $paths = @(MainOnly-Paths)
    if (-not $paths.Count) { return $false }
    [bool]@((Invoke-Git -Root $Root -Arguments (@("ls-tree", "-r", "--name-only", $Revision, "--") + $paths)).Lines |
        Where-Object { $_ }).Count
}
function Remove-MainOnlyPaths {
    param([string]$Root = $script:RepositoryRoot)
    $paths = @(MainOnly-Paths)
    if ($paths.Count) {
        [void](Invoke-Git -Root $Root -Arguments (@("rm", "-r", "--ignore-unmatch", "--") + $paths))
    }
}
function Start-MainMergeExcludingMainOnly {
    param([Parameter(Mandatory)][string]$Root, [Parameter(Mandatory)][string]$Main,
        [Parameter(Mandatory)][string]$Context)
    $merge = Invoke-Git -Root $Root -Arguments @("merge", "--no-ff", "--no-commit", $Main) -AllowFailure
    if ($merge.ExitCode -ne 0) {
        $unmerged = @((Invoke-Git -Root $Root -Arguments @("diff", "--name-only", "--diff-filter=U", "--")).Lines |
            Where-Object { $_ })
        $unexpected = @($unmerged | Where-Object { (Policy-Category -Candidate $_) -ne "MainOnly" })
        if (-not $unmerged.Count -or $unexpected.Count) {
            throw "${Context}: $($merge.Text) $($merge.ErrorText)"
        }
    }
    Remove-MainOnlyPaths -Root $Root
    $remaining = @((Invoke-Git -Root $Root -Arguments @("diff", "--name-only", "--diff-filter=U", "--")).Lines |
        Where-Object { $_ })
    if ($remaining.Count) { throw "${Context}: unresolved paths remain: $($remaining -join ', ')." }
    [ordered]@{ mergeInProgress = (Invoke-Git -Root $Root -Arguments @("rev-parse", "--verify", "-q", "MERGE_HEAD") -AllowFailure).ExitCode -eq 0;
        output = $merge.Text; error = $merge.ErrorText }
}
function Contract-Version-Lines {
    $diff = (Invoke-Git -Arguments @("diff", "--no-ext-diff", "--unified=0", "HEAD", "--")).Lines
    $findings = [Collections.Generic.List[string]]::new()
    foreach ($line in $diff) {
        if ($line.StartsWith("+++")) { continue }
        foreach ($pattern in @($script:Policy.ContractVersionPatterns)) {
            if ($line -match $pattern) { $findings.Add($line); break }
        }
    }
    @($findings)
}
function Assert-Contract-Version {
    $matches = @(Contract-Version-Lines)
    if ($matches.Count -and -not $ContractVersionAuthorized) {
        throw "Product contract version changes require explicit authorization: $($matches -join ' | ')"
    }
    @($matches)
}
function Dependency-Version-Lines {
    $diff = (Invoke-Git -Arguments @("diff", "--no-ext-diff", "--unified=0", "HEAD", "--")).Lines
    $findings = [Collections.Generic.List[string]]::new()
    foreach ($line in $diff) {
        if ($line.StartsWith("+++")) { continue }
        foreach ($pattern in @($script:Policy.DependencyVersionPatterns)) {
            if ($line -match $pattern) { $findings.Add($line); break }
        }
    }
    @($findings)
}
function Assert-Dependency-Version {
    $findings = @(Dependency-Version-Lines)
    if ($findings.Count -and [string]::IsNullOrWhiteSpace($DependencyVersionReason)) {
        throw "Dependency version changes require a recorded necessity in -DependencyVersionReason: $($findings -join ' | ')"
    }
    [ordered]@{ lines = $findings; reason = if ($findings.Count) { $DependencyVersionReason.Trim() } else { "" } }
}
function Assert-Minecraft-Version {
    $findings = [Collections.Generic.List[object]]::new()
    foreach ($line in (Invoke-Git -Arguments @("diff", "--no-ext-diff", "--unified=0", "HEAD", "--")).Lines) {
        if ($line -match '^\+(?!\+).*\b(?:minecraftVersion|minecraft_version)\s*=\s*[`"'']?([0-9A-Za-z][0-9A-Za-z._+-]*)') {
            $findings.Add([ordered]@{ line = $line; version = $Matches[1] })
        }
    }
    $versions = @($findings | ForEach-Object version | Sort-Object -Unique)
    if ($versions.Count) {
        $expected = @($versions | ForEach-Object { "mc/$_" })
        if ($versions.Count -ne 1 -or (Current-Branch) -ne $expected[0]) {
            throw "Minecraft version changes belong on their matching branch: expected=$($expected -join ', '), current=$(Current-Branch)."
        }
    }
    @($findings)
}
function Assert-Policy {
    param([switch]$PermitDirty)
    Assert-MainExists
    $main = [string]$script:Policy.MainBranch
    $allMinecraft = @(Minecraft-Branches)
    $active = @(Active-Minecraft-Branches)
    foreach ($branch in $active) {
        if ((Invoke-Git -Arguments @("merge-base", "--is-ancestor", $main, $branch) -AllowFailure).ExitCode -ne 0) {
            throw "$main is not an ancestor of $branch."
        }
        $shared = @(Shared-ComparisonPathspecs)
        $different = Invoke-Git -Arguments (@("diff", "--quiet", "$main..$branch", "--") + $shared) -AllowFailure
        if ($different.ExitCode -eq 1) { throw "Shared paths differ between $main and $branch." }
        if ($different.ExitCode -gt 1) { throw "Unable to compare shared paths for ${branch}: $($different.ErrorText)" }
        if (-not $PermitDirty -and (Test-MainOnlyPathPresent -Revision $branch)) {
            throw "Main-only paths are tracked on ${branch}: $((MainOnly-Paths) -join ', ')."
        }
    }
    $badMerge = @((Invoke-Git -Arguments @("log", "--first-parent", "--merges", "--format=%H%x09%s", $main)).Lines |
        Where-Object { $_ -match '(?i)merge.*mc/' })
    if ($badMerge.Count) { throw "$main contains a merge that names an mc/* branch: $($badMerge -join ' | ')" }
    foreach ($tracked in (Invoke-Git -Arguments @("ls-files")).Lines) {
        foreach ($pattern in @($script:Policy.ForbiddenTrackedPatterns)) {
            if ($tracked -match $pattern) { throw "Forbidden tracked path: $tracked" }
        }
    }
    $minecraftChanges = @(Assert-Minecraft-Version)
    $contract = @(Assert-Contract-Version)
    $dependencies = Assert-Dependency-Version
    $dirty = (Pending-State).All
    if ($dirty.Count -and -not $PermitDirty) { throw "Working tree is dirty: $($dirty -join ', ')." }
    [ordered]@{ mainExists = $true; minecraftBranches = $allMinecraft; activeMinecraftBranches = $active;
        inactiveMinecraftBranches = @($allMinecraft | Where-Object { $_ -notin $active });
        contractVersionLines = $contract; dependencyVersionLines = @($dependencies.lines);
        dependencyVersionReason = $dependencies.reason; minecraftVersionLines = @($minecraftChanges) }
}
function New-Log {
    param([string]$Label)
    [void](New-Item -ItemType Directory -Force -Path $script:LogRoot)
    Join-Path $script:LogRoot ("{0}-{1}-{2}.log" -f (Get-Date -Format "yyyyMMdd-HHmmss-fff"), $Label,
        [guid]::NewGuid().ToString("N"))
}
function Run-DomainVerifier {
    param([string]$Root, [string]$Profile)
    if ($Profile -notin @($script:Policy.RepositoryVerifierProfiles)) { return $null }
    $path = Join-Path $Root ([string]$script:Policy.RepositoryVerifier)
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Repository verifier missing: $path" }
    $log = New-Log -Label "domain-policy"
    $arguments = @{
        RepoRoot = $Root
        AllowContractVersionChange = [bool]$ContractVersionAuthorized
    }
    $command = Get-Command -Name $path -CommandType ExternalScript
    if ($command.Parameters.ContainsKey("SkipBuild")) { $arguments.SkipBuild = [bool]$SkipBuild }
    Push-Location -LiteralPath $Root
    try {
        & $path @arguments *> $log
        $succeeded = $?
    } finally { Pop-Location }
    if (-not $succeeded) { throw "Domain policy failed. log=$log" }
    return $log
}
function Run-Build {
    param([string]$Root, [string]$Profile)
    if ($SkipBuild) { return $null }
    $tasks = if ($Profile -eq "Main") { @($script:Policy.MainValidation) }
        elseif ($Profile -eq "Minecraft") { @($script:Policy.MinecraftValidation) }
        else { throw "Unknown validation profile: $Profile" }
    if (-not @($tasks).Count) { return $null }
    $wrapper = Join-Path $Root "gradlew.bat"
    if (-not (Test-Path -LiteralPath $wrapper -PathType Leaf)) { throw "Gradle wrapper missing: $wrapper" }
    $log = New-Log -Label "$Profile-build"
    Push-Location -LiteralPath $Root
    try {
        & $wrapper @tasks --no-daemon --no-parallel --no-problems-report *> $log
        $code = $LASTEXITCODE
    } finally { Pop-Location }
    if ($code -ne 0) { throw "Gradle validation failed ($code). log=$log" }
    return $log
}
function Run-Validation {
    param(
        [string]$Root = $script:RepositoryRoot,
        [switch]$PermitDirty,
        [switch]$SkipRepositoryPolicy,
        [string]$ProfileOverride = ""
    )
    foreach ($args in @(@("diff", "--check"), @("diff", "--cached", "--check"))) {
        $check = Invoke-Git -Root $Root -Arguments $args -AllowFailure
        if ($check.ExitCode -ne 0) { throw "Whitespace validation failed: $($check.Text)" }
    }
    $policy = if ($SkipRepositoryPolicy) { $null } else { Assert-Policy -PermitDirty:$PermitDirty }
    $branch = Current-Branch -Root $Root
    $profile = if ($ProfileOverride) { $ProfileOverride }
        elseif ($branch -eq [string]$script:Policy.MainBranch) { "Main" }
        elseif ($branch -and $branch -match [string]$script:Policy.MinecraftBranchPattern) { "Minecraft" }
        else { throw "Unsupported branch '$branch'." }
    $logs = [Collections.Generic.List[string]]::new()
    if (-not $SkipRepositoryPolicy) { $domain = Run-DomainVerifier -Root $Root -Profile $profile; if ($domain) { $logs.Add($domain) } }
    $build = Run-Build -Root $Root -Profile $profile; if ($build) { $logs.Add($build) }
    [ordered]@{ profile = $profile; policy = $policy; logs = @($logs) }
}
function Inspect-Result {
    $pending = Pending-State
    $activeState = Active-Branch-State
    $allMinecraft = @(Minecraft-Branches)
    $branches = @((Invoke-Git -Arguments @("for-each-ref", "--format=%(refname:short)%09%(objectname)%09%(upstream:short)",
        "refs/heads")).Lines | ForEach-Object {
            $parts = $_ -split "`t", 3
            [ordered]@{ name = $parts[0]; head = $parts[1]; upstream = if ($parts.Count -gt 2) { $parts[2] } else { "" } }
        })
    [ordered]@{ operation = "Inspect"; repository = $script:RepositoryRoot; policy = $script:Policy.Name;
        branch = Current-Branch; head = (Invoke-Git -Arguments @("rev-parse", "HEAD")).Text.Trim(); dirty = [bool]$pending.All.Count;
        staged = @($pending.Staged); unstaged = @($pending.Unstaged); untracked = @($pending.Untracked); branches = $branches;
        minecraftBranches = $allMinecraft; activeMinecraftBranches = @($activeState.Branches);
        inactiveMinecraftBranches = @($allMinecraft | Where-Object { $_ -notin @($activeState.Branches) });
        activeMinecraftBranchFile = [ordered]@{ path = $activeState.File; source = $activeState.Source;
            valid = $activeState.Valid; errors = @($activeState.Errors) };
        mainExists = (Branch-Exists -Name ([string]$script:Policy.MainBranch));
        remotes = @((Invoke-Git -Arguments @("remote", "-v")).Lines);
        worktrees = @((Invoke-Git -Arguments @("worktree", "list", "--porcelain")).Lines); inProgress = @(In-Progress) }
}
function Classify-Result {
    $scopes = Normalize-Paths -Values $Path
    $items = @($scopes | ForEach-Object { [ordered]@{ path = $_; category = Policy-Category -Candidate $_ } })
    [ordered]@{ operation = "Classify"; paths = $items; requiresSemanticReview = [bool]@($items |
        Where-Object { $_.category -in @("Mixed", "Unknown") }).Count }
}
function Prepare-MinecraftBranch {
    if ($MinecraftVersion -notmatch '^[0-9A-Za-z][0-9A-Za-z._+-]*$') { throw "A safe -MinecraftVersion is required." }
    Assert-MainExists
    Assert-StableGit
    $pending = Pending-State
    if ($pending.All.Count) { throw "Branch preparation requires a clean worktree: $($pending.All -join ', ')." }
    $target = "mc/$MinecraftVersion"
    if (Branch-Exists -Name $target) {
        if ($ConfirmExecution) {
            if ($Authorization -ne "ExplicitUser") { throw "Branch switching requires Authorization=ExplicitUser." }
            [void](Invoke-Git -Arguments @("switch", $target))
        }
        return [ordered]@{ operation = "PrepareMinecraftBranch"; target = $target; exists = $true;
            switched = [bool]$ConfirmExecution; branch = Current-Branch }
    }
    $candidates = @((Invoke-Git -Arguments @("for-each-ref", "--format=%(refname:short)%09%(objectname)", "refs/heads")).Lines)
    if (-not $BaseBranch) {
        return [ordered]@{ operation = "PrepareMinecraftBranch"; target = $target; exists = $false;
            requiresBaseSelection = $true; candidates = $candidates }
    }
    if (-not (Branch-Exists -Name $BaseBranch)) { throw "Selected base branch does not exist: $BaseBranch" }
    if (-not $ConfirmExecution) {
        return [ordered]@{ operation = "PrepareMinecraftBranch"; target = $target; exists = $false;
            requiresConfirmation = $true; base = $BaseBranch; candidates = $candidates }
    }
    if ($Authorization -ne "ExplicitUser") { throw "Branch creation requires Authorization=ExplicitUser." }
    $baseTip = (Invoke-Git -Arguments @("rev-parse", $BaseBranch)).Text.Trim()
    $main = [string]$script:Policy.MainBranch
    $mainTip = (Invoke-Git -Arguments @("rev-parse", $main)).Text.Trim()
    $needsMain = (Invoke-Git -Arguments @("merge-base", "--is-ancestor", $main, $BaseBranch) -AllowFailure).ExitCode -ne 0
    $temp = Join-Path ([IO.Path]::GetTempPath()) ("mc-branch-preflight-" + [guid]::NewGuid().ToString("N"))
    [void](Invoke-Git -Arguments @("worktree", "add", "--detach", $temp, $BaseBranch))
    try {
        if ($needsMain) {
            [void](Start-MainMergeExcludingMainOnly -Root $temp -Main $main -Context "main merge preflight failed")
        } else {
            Remove-MainOnlyPaths -Root $temp
        }
        $expectedTree = (Invoke-Git -Root $temp -Arguments @("write-tree")).Text.Trim()
    } finally {
        [void](Invoke-Git -Root $temp -Arguments @("merge", "--abort") -AllowFailure)
        [void](Invoke-Git -Arguments @("worktree", "remove", "--force", $temp) -AllowFailure)
    }
    if (Branch-Exists -Name $target) { throw "Target branch appeared during preflight: $target" }
    if ((Invoke-Git -Arguments @("rev-parse", $BaseBranch)).Text.Trim() -ne $baseTip) { throw "Base branch moved during preflight: $BaseBranch" }
    if ((Invoke-Git -Arguments @("rev-parse", $main)).Text.Trim() -ne $mainTip) { throw "$main moved during preflight." }
    [void](Invoke-Git -Arguments @("switch", "-c", $target, $baseTip))
    $mergeState = if ($needsMain) {
        Start-MainMergeExcludingMainOnly -Root $script:RepositoryRoot -Main $mainTip -Context "main merge failed for $target"
    } else {
        Remove-MainOnlyPaths
        [ordered]@{ mergeInProgress = $false }
    }
    $pendingTree = (Invoke-Git -Arguments @("write-tree")).Text.Trim()
    $headTree = (Invoke-Git -Arguments @("rev-parse", "HEAD^{tree}")).Text.Trim()
    if ([bool]$mergeState.mergeInProgress -or $pendingTree -ne $headTree) {
        $commitMessage = if ([bool]$mergeState.mergeInProgress) {
            "Merge branch 'main' into $target"
        } else {
            "Remove main-only paths from $target"
        }
        [void](Invoke-Git -Arguments @("commit", "-m", $commitMessage))
    }
    $actualTree = (Invoke-Git -Arguments @("rev-parse", "HEAD^{tree}")).Text.Trim()
    if ($actualTree -ne $expectedTree) { throw "Created branch tree differs from preflight for $target." }
    [ordered]@{ operation = "PrepareMinecraftBranch"; target = $target; exists = $false; created = $true;
        base = $BaseBranch; baseTip = $baseTip; mainTip = $mainTip; mainMerged = $needsMain; branch = Current-Branch }
}
function Write-JsonManifest {
    param([Parameter(Mandatory)]$Value, [Parameter(Mandatory)][string]$Root, [Parameter(Mandatory)][string]$Name)
    [void](New-Item -ItemType Directory -Force -Path $Root)
    $path = Join-Path $Root $Name
    $Value | ConvertTo-Json -Depth 14 | Set-Content -LiteralPath $path -Encoding utf8
    $path
}
function Read-JsonManifest {
    param([Parameter(Mandatory)][string]$ManifestPath, [Parameter(Mandatory)][string]$ExpectedOperation)
    $resolved = (Resolve-Path -LiteralPath $ManifestPath).Path
    $manifest = Get-Content -Raw -LiteralPath $resolved | ConvertFrom-Json
    if ([string]$manifest.operation -ne $ExpectedOperation) {
        throw "Expected a $ExpectedOperation manifest, found '$($manifest.operation)'."
    }
    if ([IO.Path]::GetFullPath([string]$manifest.repository) -ne [IO.Path]::GetFullPath($script:RepositoryRoot)) {
        throw "Manifest belongs to another repository."
    }
    [pscustomobject]@{ Path = $resolved; Value = $manifest }
}
function Working-Tree-Snapshot {
    param([string]$Root, [string]$BaseTree = "")
    $pending = Pending-State -Root $Root
    if ($pending.Staged.Count) { throw "Snapshot refuses staged changes in '$Root': $($pending.Staged -join ', ')." }
    [void](Invoke-Git -Root $Root -Arguments @("add", "--all"))
    try {
        $tree = (Invoke-Git -Root $Root -Arguments @("write-tree")).Text.Trim()
        $patch = if ($BaseTree) {
            (Invoke-Git -Root $Root -Arguments @("diff", "--cached", "--binary", "--full-index", $BaseTree, "--")).Text
        } else { "" }
        $paths = if ($BaseTree) {
            @((Invoke-Git -Root $Root -Arguments @("diff", "--cached", "--name-only", $BaseTree, "--")).Lines |
                Where-Object { $_ })
        } else { @() }
    } finally {
        [void](Invoke-Git -Root $Root -Arguments @("reset", "--quiet"))
    }
    [pscustomobject]@{ Tree = $tree; Patch = $patch; Paths = $paths; PendingPaths = @($pending.All) }
}
function Write-PatchFile {
    param([Parameter(Mandatory)][string]$Path, [AllowEmptyString()][string]$Text)
    $content = if ($Text) { $Text.TrimEnd("`r", "`n") + "`n" } else { "" }
    [IO.File]::WriteAllText($Path, $content, [Text.UTF8Encoding]::new($false))
}
function Apply-Patch {
    param([string]$Root, [string]$PatchPath)
    if ((Get-Item -LiteralPath $PatchPath).Length -eq 0) { return }
    $apply = Invoke-Git -Root $Root -Arguments @("apply", "--3way", "--whitespace=nowarn", $PatchPath) -AllowFailure
    if ($apply.ExitCode -ne 0) { throw "Patch application failed in '$Root': $($apply.Text) $($apply.ErrorText)" }
    [void](Invoke-Git -Root $Root -Arguments @("reset", "--quiet"))
}
function Copy-Overlay-Untracked {
    param([string]$Root, $Entries)
    foreach ($entry in @($Entries)) {
        $destination = Join-Path $Root ([string]$entry.path)
        if (Test-Path -LiteralPath $destination) { throw "Overlay untracked path already exists: $($entry.path)" }
        [void](New-Item -ItemType Directory -Force -Path (Split-Path -Parent $destination))
        Copy-Item -LiteralPath ([string]$entry.source) -Destination $destination
    }
}
function Apply-Overlay {
    param([string]$Root, $Manifest)
    Apply-Patch -Root $Root -PatchPath ([string]$Manifest.patch)
    Copy-Overlay-Untracked -Root $Root -Entries @($Manifest.untracked)
    (Working-Tree-Snapshot -Root $Root).Tree
}
function Assert-ManagedWorktree {
    param([string]$Branch, [string]$Path, [switch]$RequireClean)
    $expected = Managed-Worktree-Path -Branch $Branch
    if ([IO.Path]::GetFullPath($Path) -ne $expected) { throw "Unexpected managed worktree path for ${Branch}: $Path" }
    $records = @(Worktree-Records | Where-Object Branch -eq $Branch)
    if ($records.Count -ne 1 -or $records[0].Path -ne $expected) {
        throw "Expected exactly one managed worktree for ${Branch}: $expected"
    }
    if ((Current-Branch -Root $expected) -ne $Branch) { throw "Managed worktree branch mismatch for $Branch." }
    if ($RequireClean -and (Pending-State -Root $expected).All.Count) {
        throw "Managed worktree is dirty for ${Branch}: $((Pending-State -Root $expected).All -join ', ')"
    }
}
function Prepare-ActiveWorktrees {
    if (-not $ConfirmExecution -or $Authorization -ne "ExplicitUser") {
        throw "PrepareActiveWorktrees requires explicit authorization and -ConfirmExecution."
    }
    Assert-MainExists
    Assert-StableGit
    $mainBranch = [string]$script:Policy.MainBranch
    if ((Current-Branch) -ne $mainBranch) { throw "PrepareActiveWorktrees must start on $mainBranch." }
    $active = @(Selected-Minecraft-Edit-Branches)
    $pending = Pending-State
    if ($pending.Staged.Count) { throw "Active worktree preparation refuses staged changes: $($pending.Staged -join ', ')." }
    $scopes = if ($Path.Count) { @(Normalize-Paths -Values $Path) } else { @() }
    if (-not @($scopes).Count -and @($pending.All).Count) {
        throw "Main has pending changes but no exact overlay paths were declared: $($pending.All -join ', ')."
    }
    $outside = @($pending.All | Where-Object { -not (In-Scope -Candidate $_ -Scopes $scopes) })
    if ($outside.Count) { throw "Pending main paths outside the overlay scope: $($outside -join ', ')." }
    foreach ($scope in $scopes) {
        $category = Policy-Category -Candidate $scope
        if ($category -notin @("Shared", "Mixed")) { throw "Overlay path is not main-owned or mixed: $scope ($category)." }
    }
    [void](Assert-Policy -PermitDirty)
    $main = (Invoke-Git -Arguments @("rev-parse", $mainBranch)).Text.Trim()
    foreach ($branch in $active) {
        if ((Invoke-Git -Arguments @("merge-base", "--is-ancestor", $main, $branch) -AllowFailure).ExitCode -ne 0) {
            throw "Active branch must contain the current committed main before overlay work starts: $branch"
        }
    }
    $root = Join-Path $script:LogRoot ("active-overlay-" + (Get-Date -Format "yyyyMMdd-HHmmss-fff") + "-" +
        [guid]::NewGuid().ToString("N"))
    [void](New-Item -ItemType Directory -Force -Path $root)
    $patchPath = Join-Path $root "main.patch"
    $patch = if (@($scopes).Count) {
        (Invoke-Git -Arguments (@("diff", "--binary", "--full-index", "HEAD", "--") + $scopes)).Text
    } else { "" }
    Write-PatchFile -Path $patchPath -Text $patch
    $untracked = [Collections.Generic.List[object]]::new()
    foreach ($file in @($pending.Untracked | Where-Object { In-Scope -Candidate $_ -Scopes $scopes })) {
        $source = Join-Path $script:RepositoryRoot $file
        $copy = Join-Path (Join-Path $root "untracked") $file
        [void](New-Item -ItemType Directory -Force -Path (Split-Path -Parent $copy))
        Copy-Item -LiteralPath $source -Destination $copy
        $untracked.Add([ordered]@{ path = $file; source = $copy })
    }
    $mainSnapshot = Working-Tree-Snapshot -Root $script:RepositoryRoot
    $overlay = [ordered]@{ patch = $patchPath; untracked = @($untracked) }
    $preflight = [Collections.Generic.List[object]]::new()
    foreach ($branch in $active) {
        $temp = Join-Path ([IO.Path]::GetTempPath()) ("active-overlay-preflight-" + [guid]::NewGuid().ToString("N"))
        [void](Invoke-Git -Arguments @("worktree", "add", "--detach", $temp, $branch))
        try {
            $tree = Apply-Overlay -Root $temp -Manifest ([pscustomobject]$overlay)
            $preflight.Add([ordered]@{ branch = $branch;
                baseHead = (Invoke-Git -Arguments @("rev-parse", $branch)).Text.Trim(); overlayTree = $tree })
        } finally {
            [void](Invoke-Git -Arguments @("worktree", "remove", "--force", $temp) -AllowFailure)
        }
    }
    $managedRoot = Managed-Worktree-Root
    [void](New-Item -ItemType Directory -Force -Path $managedRoot)
    $prepared = [Collections.Generic.List[object]]::new()
    foreach ($item in $preflight) {
        $branch = [string]$item.branch
        $target = Managed-Worktree-Path -Branch $branch
        $records = @(Worktree-Records | Where-Object Branch -eq $branch)
        if ($records.Count) {
            if ($records.Count -ne 1 -or $records[0].Path -ne $target) {
                throw "Active branch is checked out outside its managed path: $branch"
            }
            Assert-ManagedWorktree -Branch $branch -Path $target -RequireClean
        } else {
            if (Test-Path -LiteralPath $target) { throw "Unregistered managed worktree path already exists: $target" }
            [void](Invoke-Git -Arguments @("worktree", "add", $target, $branch))
        }
        if ((Invoke-Git -Arguments @("rev-parse", $branch)).Text.Trim() -ne [string]$item.baseHead) {
            throw "Active branch moved during overlay preflight: $branch"
        }
        $actualTree = Apply-Overlay -Root $target -Manifest ([pscustomobject]$overlay)
        if ($actualTree -ne [string]$item.overlayTree) { throw "Overlay tree differs from preflight for $branch." }
        $prepared.Add([ordered]@{ branch = $branch; path = $target; baseHead = $item.baseHead;
            overlayTree = $actualTree })
    }
    $manifest = [ordered]@{ operation = "PrepareActiveWorktrees"; format = 2; repository = $script:RepositoryRoot;
        mainBranch = $mainBranch; mainBase = $main; mainOverlayTree = $mainSnapshot.Tree; scopes = $scopes;
        patch = $patchPath; untracked = @($untracked); directEditBranches = $active;
        worktreeRoot = $managedRoot; worktrees = @($prepared) }
    $manifestPath = Write-JsonManifest -Value $manifest -Root $root -Name "manifest.json"
    [ordered]@{ operation = "PrepareActiveWorktrees"; snapshot = $manifestPath; main = $main;
        mainOverlayTree = $mainSnapshot.Tree; worktrees = @($prepared) }
}
function Capture-ActiveWorktreeChanges {
    if (-not $SnapshotPath) { throw "CaptureActiveWorktreeChanges requires the overlay -SnapshotPath." }
    $loaded = Read-JsonManifest -ManifestPath $SnapshotPath -ExpectedOperation "PrepareActiveWorktrees"
    $overlay = $loaded.Value
    if ((Current-Branch) -ne [string]$overlay.mainBranch) { throw "Capture must run from the primary main worktree." }
    if ((Invoke-Git -Arguments @("rev-parse", "HEAD")).Text.Trim() -ne [string]$overlay.mainBase) {
        throw "Main moved after active worktree preparation."
    }
    $mainTree = (Working-Tree-Snapshot -Root $script:RepositoryRoot).Tree
    if ($mainTree -ne [string]$overlay.mainOverlayTree) { throw "Main working tree differs from the overlay manifest." }
    $root = Join-Path $script:LogRoot ("active-capture-" + (Get-Date -Format "yyyyMMdd-HHmmss-fff") + "-" +
        [guid]::NewGuid().ToString("N"))
    [void](New-Item -ItemType Directory -Force -Path $root)
    $captured = [Collections.Generic.List[object]]::new()
    foreach ($item in @($overlay.worktrees)) {
        $branch = [string]$item.branch; $worktree = [string]$item.path
        Assert-ManagedWorktree -Branch $branch -Path $worktree
        if ((Invoke-Git -Root $worktree -Arguments @("rev-parse", "HEAD")).Text.Trim() -ne [string]$item.baseHead) {
            throw "Active branch moved before capture: $branch"
        }
        $snapshot = Working-Tree-Snapshot -Root $worktree -BaseTree ([string]$item.overlayTree)
        foreach ($changed in @($snapshot.Paths)) {
            $category = Policy-Category -Candidate $changed
            if ($category -in @("MainOnly", "Shared", "Forbidden", "Unknown")) {
                throw "Version work changed a non-version path on ${branch}: $changed ($category)."
            }
        }
        if (-not @($snapshot.Paths).Count) {
            throw "Selected branch has no direct Minecraft edit after removing the main overlay: $branch"
        }
        $patchPath = Join-Path $root (($branch -replace '[^0-9A-Za-z._+-]', '-') + ".patch")
        Write-PatchFile -Path $patchPath -Text $snapshot.Patch
        $captured.Add([ordered]@{ branch = $branch; path = $worktree; baseHead = $item.baseHead;
            overlayTree = $item.overlayTree; versionTree = $snapshot.Tree; versionPatch = $patchPath;
            versionPaths = @($snapshot.Paths); pendingPaths = @($snapshot.PendingPaths) })
    }
    $manifest = [ordered]@{ operation = "CaptureActiveWorktreeChanges"; format = 2;
        repository = $script:RepositoryRoot; overlayManifest = $loaded.Path; mainBranch = $overlay.mainBranch;
        mainBase = $overlay.mainBase; mainOverlayTree = $overlay.mainOverlayTree; scopes = @($overlay.scopes);
        directEditBranches = @($overlay.directEditBranches);
        worktreeRoot = $overlay.worktreeRoot; worktrees = @($captured) }
    $manifestPath = Write-JsonManifest -Value $manifest -Root $root -Name "manifest.json"
    [ordered]@{ operation = "CaptureActiveWorktreeChanges"; snapshot = $manifestPath; worktrees = @($captured) }
}
function New-Snapshot {
    $scopes = Normalize-Paths -Values $Path
    $selected = @((Pending-State).All | Where-Object { In-Scope -Candidate $_ -Scopes $scopes })
    if (-not $selected.Count) { throw "No pending files exist in the declared snapshot paths." }
    $root = Join-Path $script:LogRoot ("snapshot-" + (Get-Date -Format "yyyyMMdd-HHmmss-fff") + "-" + [guid]::NewGuid().ToString("N"))
    $files = Join-Path $root "files"; [void](New-Item -ItemType Directory -Force -Path $files)
    $entries = [Collections.Generic.List[object]]::new()
    foreach ($file in $selected) {
        $absolute = Join-Path $script:RepositoryRoot $file
        $exists = Test-Path -LiteralPath $absolute -PathType Leaf
        $tracked = (Invoke-Git -Arguments @("ls-files", "--error-unmatch", "--", $file) -AllowFailure).ExitCode -eq 0
        $hash = $null
        if ($exists) {
            $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $absolute).Hash
            $destination = Join-Path $files $file
            [void](New-Item -ItemType Directory -Force -Path (Split-Path -Parent $destination))
            Copy-Item -LiteralPath $absolute -Destination $destination
        }
        $entries.Add([ordered]@{ path = $file; exists = $exists; tracked = $tracked; sha256 = $hash })
    }
    $manifest = [ordered]@{ format = 1; repository = $script:RepositoryRoot; branch = Current-Branch;
        head = (Invoke-Git -Arguments @("rev-parse", "HEAD")).Text.Trim(); scopes = $scopes; entries = @($entries) }
    $manifestPath = Join-Path $root "manifest.json"
    $manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $manifestPath -Encoding utf8
    [ordered]@{ operation = "Snapshot"; snapshot = $manifestPath; files = $entries.Count; head = $manifest.head }
}
function Compare-Snapshot {
    if (-not $SnapshotPath) { throw "CompareSnapshot requires -SnapshotPath." }
    $manifestPath = (Resolve-Path -LiteralPath $SnapshotPath).Path
    $manifest = Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json
    if ([IO.Path]::GetFullPath([string]$manifest.repository) -ne [IO.Path]::GetFullPath($script:RepositoryRoot)) {
        throw "Snapshot belongs to another repository."
    }
    $differences = [Collections.Generic.List[object]]::new()
    $trackingChanges = [Collections.Generic.List[object]]::new()
    foreach ($entry in $manifest.entries) {
        $absolute = Join-Path $script:RepositoryRoot ([string]$entry.path)
        $exists = Test-Path -LiteralPath $absolute -PathType Leaf
        $tracked = (Invoke-Git -Arguments @("ls-files", "--error-unmatch", "--", [string]$entry.path) -AllowFailure).ExitCode -eq 0
        $hash = if ($exists) { (Get-FileHash -Algorithm SHA256 -LiteralPath $absolute).Hash } else { $null }
        if ($tracked -ne [bool]$entry.tracked) {
            $trackingChanges.Add([ordered]@{ path = $entry.path; before = [bool]$entry.tracked; after = $tracked })
        }
        if ($exists -ne [bool]$entry.exists -or $hash -ne $entry.sha256) {
            $differences.Add([ordered]@{ path = $entry.path; expectedExists = [bool]$entry.exists; actualExists = $exists;
                expectedSha256 = $entry.sha256; actualSha256 = $hash })
        }
    }
    $expected = @($manifest.entries | ForEach-Object { [string]$_.path })
    foreach ($extra in @((Pending-State).All | Where-Object { (In-Scope -Candidate $_ -Scopes @($manifest.scopes)) -and $_ -notin $expected })) {
        $differences.Add([ordered]@{ path = $extra; reason = "unexpected-path" })
    }
    [ordered]@{ operation = "CompareSnapshot"; snapshot = $manifestPath; match = -not [bool]$differences.Count;
        differences = @($differences); trackingChanges = @($trackingChanges) }
}
function Commit-Task {
    if (-not $ConfirmExecution) { throw "Commit requires -ConfirmExecution." }
    if ($Authorization -notin @("ExplicitUser", "TaskBoundary")) { throw "Commit requires an allowed -Authorization." }
    if (-not $ExpectedBranch -or -not $Message) { throw "Commit requires -ExpectedBranch and -Message." }
    Assert-StableGit
    if ((Current-Branch) -ne $ExpectedBranch) { throw "Expected '$ExpectedBranch', found '$(Current-Branch)'." }
    $scopes = Normalize-Paths -Values $Path
    $pending = Pending-State
    if ($pending.Staged.Count) { throw "Commit refuses pre-existing staged changes: $($pending.Staged -join ', ')." }
    $outside = @($pending.All | Where-Object { -not (In-Scope -Candidate $_ -Scopes $scopes) })
    if ($outside.Count) { throw "Pending paths outside declared task scope: $($outside -join ', ')." }
    $categories = @($scopes | ForEach-Object { Policy-Category -Candidate $_ } | Sort-Object -Unique)
    if ($categories -contains "Forbidden" -or $categories -contains "Unknown") { throw "Commit scope contains forbidden or unknown ownership." }
    $current = Current-Branch
    if (@($categories | Where-Object { $_ -in @("Shared", "MainOnly") }).Count -and
        $current -ne [string]$script:Policy.MainBranch) {
        throw "Shared and main-only paths may be committed only on main."
    }
    if ($categories -contains "Minecraft" -and $current -notmatch [string]$script:Policy.MinecraftBranchPattern) {
        throw "Minecraft-owned paths may be committed only on their matching mc/* branch."
    }
    if ($Authorization -eq "TaskBoundary" -and (($categories -contains "Mixed") -or $categories.Count -gt 1)) {
        throw "TaskBoundary cannot authorize mixed or cross-cutting work."
    }
    $validation = Run-Validation -PermitDirty
    $start = (Invoke-Git -Arguments @("rev-parse", "HEAD")).Text.Trim()
    [void](Invoke-Git -Arguments (@("add", "--all", "--") + $scopes))
    $staged = @((Invoke-Git -Arguments @("diff", "--cached", "--name-only")).Lines | Where-Object { $_ })
    if (-not $staged.Count) { throw "Declared paths produced no staged changes." }
    if (@($staged | Where-Object { -not (In-Scope -Candidate $_ -Scopes $scopes) }).Count) { throw "Staging escaped scope." }
    [void](Invoke-Git -Arguments @("commit", "-m", $Message))
    $head = (Invoke-Git -Arguments @("rev-parse", "HEAD")).Text.Trim()
    if ((Invoke-Git -Arguments @("rev-parse", "HEAD^1")).Text.Trim() -ne $start) { throw "Unexpected commit parent." }
    [ordered]@{ operation = "Commit"; branch = Current-Branch; commit = $head; parent = $start;
        authorization = $Authorization; paths = $staged; validation = $validation }
}
function Checked-OutBranches {
    @((Invoke-Git -Arguments @("worktree", "list", "--porcelain")).Lines |
        Where-Object { $_.StartsWith("branch refs/heads/") } | ForEach-Object { $_.Substring(18) })
}
function Clear-CapturedWorkingTree {
    param([string]$Root, $Item)
    $pending = Pending-State -Root $Root
    $expected = @($Item.pendingPaths | ForEach-Object { [string]$_ } | Sort-Object -Unique)
    $actual = @($pending.All | Sort-Object -Unique)
    if (($expected | ConvertTo-Json -Compress) -ne ($actual | ConvertTo-Json -Compress)) {
        throw "Pending paths changed after capture in '$Root': expected=$($expected -join ', '); actual=$($actual -join ', ')."
    }
    $tracked = [Collections.Generic.List[string]]::new()
    $untracked = [Collections.Generic.List[string]]::new()
    foreach ($file in $actual) {
        if ((Invoke-Git -Root $Root -Arguments @("ls-files", "--error-unmatch", "--", $file) -AllowFailure).ExitCode -eq 0) {
            $tracked.Add($file)
        } else {
            $untracked.Add($file)
        }
    }
    if ($tracked.Count) {
        [void](Invoke-Git -Root $Root -Arguments (@("restore", "--source=HEAD", "--staged", "--worktree", "--") + @($tracked)))
    }
    $rootPath = [IO.Path]::GetFullPath($Root).TrimEnd([IO.Path]::DirectorySeparatorChar) +
        [IO.Path]::DirectorySeparatorChar
    foreach ($file in @($untracked)) {
        $absolute = [IO.Path]::GetFullPath((Join-Path $Root $file))
        if (-not $absolute.StartsWith($rootPath, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Captured untracked path escapes its worktree: $file"
        }
        if (Test-Path -LiteralPath $absolute -PathType Leaf) { Remove-Item -LiteralPath $absolute -Force }
        elseif (Test-Path -LiteralPath $absolute) { throw "Captured untracked path is not a file: $file" }
    }
    $remaining = (Pending-State -Root $Root).All
    if ($remaining.Count) { throw "Unable to clean captured worktree '$Root': $($remaining -join ', ')." }
}
function Merge-Main {
    if (-not $ConfirmExecution -or $Authorization -ne "ExplicitUser") { throw "MergeMain requires explicit authorization and -ConfirmExecution." }
    Assert-MainExists
    Assert-StableGit
    $mainBranch = [string]$script:Policy.MainBranch
    if ((Current-Branch) -ne $mainBranch) { throw "MergeMain must start on $mainBranch." }
    if ((Pending-State).All.Count) { throw "MergeMain requires a clean worktree." }
    if (-not $SnapshotPath) { throw "MergeMain requires a CaptureActiveWorktreeChanges -SnapshotPath." }
    $loaded = Read-JsonManifest -ManifestPath $SnapshotPath -ExpectedOperation "CaptureActiveWorktreeChanges"
    $capture = $loaded.Value
    $active = @(Active-Minecraft-Branches)
    $capturedBranches = @($capture.worktrees | ForEach-Object { [string]$_.branch })
    $declaredBranches = @($capture.directEditBranches | ForEach-Object { [string]$_ })
    if ($capturedBranches.Count -lt 2 -or
        ($capturedBranches | ConvertTo-Json -Compress) -ne ($declaredBranches | ConvertTo-Json -Compress)) {
        throw "Capture manifest must contain the same two or more declared direct-edit branches."
    }
    $inactive = @($capturedBranches | Where-Object { $_ -notin $active })
    if ($inactive.Count) {
        throw "Capture manifest contains branches that are no longer active: $($inactive -join ', ')."
    }
    $main = (Invoke-Git -Arguments @("rev-parse", $mainBranch)).Text.Trim()
    $mainTree = (Invoke-Git -Arguments @("rev-parse", "$main^{tree}")).Text.Trim()
    if ($mainTree -ne [string]$capture.mainOverlayTree) { throw "Committed main tree differs from the captured overlay tree." }
    $baseTree = (Invoke-Git -Arguments @("rev-parse", "$($capture.mainBase)^{tree}")).Text.Trim()
    if ([string]$capture.mainOverlayTree -eq $baseTree) {
        if ($main -ne [string]$capture.mainBase) { throw "Main moved even though the captured task had no main overlay." }
    } else {
        $parent = (Invoke-Git -Arguments @("rev-parse", "$main^1")).Text.Trim()
        if ($parent -ne [string]$capture.mainBase) { throw "Committed main parent differs from the overlay base." }
    }
    $preflight = [Collections.Generic.List[object]]::new()
    foreach ($item in @($capture.worktrees)) {
        $branch = [string]$item.branch; $target = [string]$item.path
        Assert-ManagedWorktree -Branch $branch -Path $target
        if ((Invoke-Git -Arguments @("rev-parse", $branch)).Text.Trim() -ne [string]$item.baseHead) {
            throw "Target branch moved before merge preflight: $branch"
        }
        $currentTree = (Working-Tree-Snapshot -Root $target).Tree
        if ($currentTree -ne [string]$item.versionTree) { throw "Version worktree differs from its capture: $branch" }
        $temp = Join-Path ([IO.Path]::GetTempPath()) ("main-merge-preflight-" + [guid]::NewGuid().ToString("N"))
        [void](Invoke-Git -Arguments @("worktree", "add", "--detach", $temp, $branch))
        try {
            [void](Start-MainMergeExcludingMainOnly -Root $temp -Main $main -Context "Merge preflight failed for $branch")
            Apply-Patch -Root $temp -PatchPath ([string]$item.versionPatch)
            $finalTree = (Working-Tree-Snapshot -Root $temp).Tree
            $validation = Run-Validation -Root $temp -PermitDirty -SkipRepositoryPolicy -ProfileOverride "Minecraft"
            $preflight.Add([ordered]@{ branch = $branch; path = $target; baseHead = $item.baseHead;
                finalTree = $finalTree; validation = $validation; capture = $item })
        } finally {
            [void](Invoke-Git -Root $temp -Arguments @("merge", "--abort") -AllowFailure)
            [void](Invoke-Git -Arguments @("worktree", "remove", "--force", $temp) -AllowFailure)
        }
    }
    if ((Invoke-Git -Arguments @("rev-parse", $mainBranch)).Text.Trim() -ne $main) { throw "$mainBranch moved during merge preflight." }
    foreach ($item in $preflight) {
        if ((Invoke-Git -Arguments @("rev-parse", [string]$item.branch)).Text.Trim() -ne [string]$item.baseHead) {
            throw "Target branch moved during merge preflight: $($item.branch)"
        }
    }
    $merged = [Collections.Generic.List[object]]::new()
    foreach ($item in $preflight) {
        $target = [string]$item.path; $branch = [string]$item.branch
        Clear-CapturedWorkingTree -Root $target -Item $item.capture
        $mergeState = Start-MainMergeExcludingMainOnly -Root $target -Main $main -Context "Merge failed for $branch"
        $pendingTree = (Invoke-Git -Root $target -Arguments @("write-tree")).Text.Trim()
        $headTree = (Invoke-Git -Root $target -Arguments @("rev-parse", "HEAD^{tree}")).Text.Trim()
        if ([bool]$mergeState.mergeInProgress -or $pendingTree -ne $headTree) {
            [void](Invoke-Git -Root $target -Arguments @("commit", "-m", "Merge branch 'main' into $branch"))
        }
        Apply-Patch -Root $target -PatchPath ([string]$item.capture.versionPatch)
        $tree = (Working-Tree-Snapshot -Root $target).Tree
        if ($tree -ne [string]$item.finalTree) { throw "Actual restored tree differs from preflight for $branch." }
        $merged.Add([ordered]@{ branch = $branch; path = $target;
            commit = (Invoke-Git -Root $target -Arguments @("rev-parse", "HEAD")).Text.Trim();
            tree = $tree; versionPaths = @($item.capture.versionPaths) })
    }
    $policy = Assert-Policy -PermitDirty
    [ordered]@{ operation = "MergeMain"; main = $main; merged = @($merged); skipped = @();
        finalBranch = Current-Branch; policy = $policy }
}
function Propagate-Main {
    if (-not $ConfirmExecution -or $Authorization -ne "ExplicitUser") {
        throw "PropagateMain requires explicit authorization and -ConfirmExecution."
    }
    Assert-MainExists
    Assert-StableGit
    $mainBranch = [string]$script:Policy.MainBranch
    if ((Current-Branch) -ne $mainBranch) { throw "PropagateMain must start on $mainBranch." }
    if ((Pending-State).All.Count) { throw "PropagateMain requires a clean primary worktree." }
    $records = @(Worktree-Records)
    if ($records.Count -ne 1 -or $records[0].Path -ne [IO.Path]::GetFullPath($script:RepositoryRoot) -or
        $records[0].Branch -ne $mainBranch) {
        throw "PropagateMain refuses additional or unexpected Git worktrees."
    }
    $active = @(Active-Minecraft-Branches)
    $main = (Invoke-Git -Arguments @("rev-parse", $mainBranch)).Text.Trim()
    $baseHeads = [ordered]@{}
    $required = [Collections.Generic.List[string]]::new()
    $skipped = [Collections.Generic.List[object]]::new()
    $shared = @(Shared-ComparisonPathspecs)
    foreach ($branch in $active) {
        $head = (Invoke-Git -Arguments @("rev-parse", $branch)).Text.Trim()
        $baseHeads[$branch] = $head
        if ((Invoke-Git -Arguments @("merge-base", "--is-ancestor", $main, $branch) -AllowFailure).ExitCode -eq 0) {
            if (Test-MainOnlyPathPresent -Revision $branch) {
                $required.Add($branch)
            } else {
                $different = Invoke-Git -Arguments (@("diff", "--quiet", "$main..$branch", "--") + $shared) -AllowFailure
                if ($different.ExitCode -ne 0) { throw "Already-propagated branch has different shared paths: $branch" }
                $skipped.Add([ordered]@{ branch = $branch; head = $head; reason = "main-already-ancestor" })
            }
        } else {
            $required.Add($branch)
        }
    }
    $validationRepositories = Resolve-ValidationRepositoryMap
    $preflight = [Collections.Generic.List[object]]::new()
    foreach ($branch in @($required)) {
        $temp = Join-Path ([IO.Path]::GetTempPath()) ("main-propagate-preflight-" + [guid]::NewGuid().ToString("N"))
        [void](New-Item -ItemType Directory -Path $temp)
        try {
            $leaf = Split-Path -Leaf $script:RepositoryRoot
            $clone = Join-Path $temp $leaf
            [void](Invoke-Git -Root $temp -Arguments @("clone", "--quiet", "--no-hardlinks", "--no-checkout",
                $script:RepositoryRoot, $clone))
            [void](Invoke-Git -Root $clone -Arguments @("config", "user.name", "Propagation Preflight"))
            [void](Invoke-Git -Root $clone -Arguments @("config", "user.email", "preflight@example.invalid"))
            [void](Invoke-Git -Root $clone -Arguments @("switch", "--quiet", "--detach", "origin/$branch"))
            $siblingClones = [Collections.Generic.List[string]]::new()
            if (-not $SkipBuild) {
                foreach ($sibling in @($script:Policy.ValidationRepositories)) {
                    $source = [string]$validationRepositories[[string]$sibling]
                    $destination = Join-Path $temp ([string]$sibling)
                    [void](Invoke-Git -Root $temp -Arguments @("clone", "--quiet", "--no-hardlinks", "--no-checkout",
                        $source, $destination))
                    $siblingBranch = Invoke-Git -Root $destination -Arguments @("rev-parse", "--verify",
                        "origin/$branch") -AllowFailure
                    if ($siblingBranch.ExitCode -ne 0) {
                        throw "Validation repository lacks ${branch}: $sibling"
                    }
                    [void](Invoke-Git -Root $destination -Arguments @("switch", "--quiet", "--detach", "origin/$branch"))
                    $siblingClones.Add($destination)
                }
            }
            [void](Start-MainMergeExcludingMainOnly -Root $clone -Main "origin/$mainBranch" `
                -Context "Propagation preflight merge failed for $branch")
            $tree = (Invoke-Git -Root $clone -Arguments @("write-tree")).Text.Trim()
            $siblingValidations = [Collections.Generic.List[object]]::new()
            foreach ($siblingClone in @($siblingClones)) {
                $siblingValidations.Add((Run-Validation -Root $siblingClone -PermitDirty -SkipRepositoryPolicy -ProfileOverride "Minecraft"))
            }
            $validation = Run-Validation -Root $clone -PermitDirty -SkipRepositoryPolicy -ProfileOverride "Minecraft"
            $preflight.Add([ordered]@{ branch = $branch; baseHead = $baseHeads[$branch];
                tree = $tree; siblingValidations = @($siblingValidations); validation = $validation })
        } finally {
            $resolved = [IO.Path]::GetFullPath($temp)
            $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
            if ($resolved.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase) -and
                (Test-Path -LiteralPath $resolved)) {
                Remove-Item -LiteralPath $resolved -Recurse -Force
            }
        }
    }
    if ((Invoke-Git -Arguments @("rev-parse", $mainBranch)).Text.Trim() -ne $main) {
        throw "$mainBranch moved during propagation preflight."
    }
    foreach ($item in $preflight) {
        if ((Invoke-Git -Arguments @("rev-parse", [string]$item.branch)).Text.Trim() -ne [string]$item.baseHead) {
            throw "Target branch moved during propagation preflight: $($item.branch)"
        }
    }
    $merged = [Collections.Generic.List[object]]::new()
    try {
        foreach ($item in $preflight) {
            $branch = [string]$item.branch
            [void](Invoke-Git -Arguments @("switch", "--quiet", $branch))
            $mergeState = Start-MainMergeExcludingMainOnly -Root $script:RepositoryRoot -Main $main `
                -Context "Propagation merge failed for $branch"
            $pendingTree = (Invoke-Git -Arguments @("write-tree")).Text.Trim()
            $headTree = (Invoke-Git -Arguments @("rev-parse", "HEAD^{tree}")).Text.Trim()
            if ([bool]$mergeState.mergeInProgress -or $pendingTree -ne $headTree) {
                $commitMessage = if ([bool]$mergeState.mergeInProgress) {
                    "Merge branch 'main' into $branch"
                } else {
                    "Remove main-only paths from $branch"
                }
                [void](Invoke-Git -Arguments @("commit", "-m", $commitMessage))
            }
            $tree = (Invoke-Git -Arguments @("rev-parse", "HEAD^{tree}")).Text.Trim()
            if ($tree -ne [string]$item.tree) { throw "Propagation tree differs from preflight for $branch." }
            $merged.Add([ordered]@{ branch = $branch;
                commit = (Invoke-Git -Arguments @("rev-parse", "HEAD")).Text.Trim(); tree = $tree })
        }
    } finally {
        if ((Current-Branch) -ne $mainBranch) {
            if (@(In-Progress).Count) { [void](Invoke-Git -Arguments @("merge", "--abort") -AllowFailure) }
            [void](Invoke-Git -Arguments @("switch", "--quiet", $mainBranch))
        }
    }
    if ((Current-Branch) -ne $mainBranch) { throw "PropagateMain failed to return to $mainBranch." }
    $policy = Assert-Policy
    [ordered]@{ operation = "PropagateMain"; main = $main; merged = @($merged); skipped = @($skipped);
        finalBranch = Current-Branch; worktreeCount = @(Worktree-Records).Count; policy = $policy }
}
function Cleanup-ActiveWorktrees {
    if (-not $ConfirmExecution -or $Authorization -ne "ExplicitUser") {
        throw "CleanupActiveWorktrees requires explicit authorization and -ConfirmExecution."
    }
    Assert-MainExists
    if ((Current-Branch) -ne [string]$script:Policy.MainBranch) {
        throw "CleanupActiveWorktrees must run from the primary main worktree."
    }
    $removed = [Collections.Generic.List[object]]::new()
    $retained = [Collections.Generic.List[object]]::new()
    foreach ($branch in @(Active-Minecraft-Branches)) {
        $target = Managed-Worktree-Path -Branch $branch
        $record = @(Worktree-Records | Where-Object { $_.Path -eq $target })
        if (-not $record.Count) {
            if (Test-Path -LiteralPath $target) {
                $retained.Add([ordered]@{ branch = $branch; path = $target; reason = "unregistered-path" })
            }
            continue
        }
        if ($record.Count -ne 1 -or $record[0].Branch -ne $branch) {
            $retained.Add([ordered]@{ branch = $branch; path = $target; reason = "association-mismatch" })
            continue
        }
        $pending = (Pending-State -Root $target).All
        $progress = @(In-Progress -Root $target)
        if ($pending.Count -or $progress.Count) {
            $retained.Add([ordered]@{ branch = $branch; path = $target; reason = "not-clean";
                pending = @($pending); inProgress = $progress })
            continue
        }
        $remove = Invoke-Git -Arguments @("worktree", "remove", $target) -AllowFailure
        if ($remove.ExitCode -eq 0) {
            $removed.Add([ordered]@{ branch = $branch; path = $target })
        } else {
            $retained.Add([ordered]@{ branch = $branch; path = $target; reason = "remove-refused";
                error = "$($remove.Text) $($remove.ErrorText)".Trim() })
        }
    }
    [ordered]@{ operation = "CleanupActiveWorktrees"; removed = @($removed); retained = @($retained) }
}
function Push-Refs {
    if (-not $ConfirmExecution -or $Authorization -ne "ExplicitUser") { throw "Push requires explicit authorization and -ConfirmExecution." }
    Assert-MainExists
    [void](Assert-Policy -PermitDirty)
    if (-not $Remote -or $Remote.StartsWith("-") -or $Remote -match '\s' -or -not $RefSpec -or
        $RefSpec -match '[*?\[\s]' -or $RefSpec.StartsWith(":") -or $RefSpec.EndsWith(":") -or
        $RefSpec.StartsWith("+") -or $RefSpec.StartsWith("-") -or $RefSpec.StartsWith("^")) {
        throw "Push requires one exact non-deletion -Remote and -RefSpec."
    }
    if ((Invoke-Git -Arguments @("remote", "get-url", $Remote) -AllowFailure).ExitCode -ne 0) { throw "Unknown remote '$Remote'." }
    $arguments = [Collections.Generic.List[string]]::new(); $arguments.Add("push")
    if ($ForceMode -ne "None") {
        if ($ForceAuthorization -ne "ExplicitForceUser") { throw "Force push requires ForceAuthorization=ExplicitForceUser." }
        $arguments.Add($(if ($ForceMode -eq "ForceWithLease") { "--force-with-lease" } else { "--force" }))
    }
    $arguments.Add($Remote); $arguments.Add($RefSpec)
    $result = Invoke-Git -Arguments @($arguments)
    [ordered]@{ operation = "Push"; remote = $Remote; refSpec = $RefSpec; forceMode = $ForceMode; output = $result.Text }
}

try {
    $result = switch ($Operation) {
        "Inspect" { Inspect-Result }
        "Classify" { Classify-Result }
        "PrepareMinecraftBranch" { Prepare-MinecraftBranch }
        "PrepareActiveWorktrees" { Prepare-ActiveWorktrees }
        "CaptureActiveWorktreeChanges" { Capture-ActiveWorktreeChanges }
        "CleanupActiveWorktrees" { Cleanup-ActiveWorktrees }
        "Validate" { $validation = Run-Validation -PermitDirty:$AllowDirty; [ordered]@{ operation = "Validate"; success = $true;
                branch = Current-Branch; validation = $validation } }
        "Snapshot" { New-Snapshot }
        "CompareSnapshot" { Compare-Snapshot }
        "Commit" { Commit-Task }
        "MergeMain" { Merge-Main }
        "PropagateMain" { Propagate-Main }
        "Push" { Push-Refs }
        "Audit" { $policyResult = Assert-Policy -PermitDirty:$AllowDirty; [ordered]@{ operation = "Audit"; success = $true;
                inspect = Inspect-Result; policy = $policyResult } }
    }
    Write-Result -Value $result
} catch {
    $message = $_.Exception.Message
    if ($message.Length -gt 3000) { $message = $message.Substring(0, 3000) + "..." }
    Write-Result -Value ([ordered]@{ operation = $Operation; success = $false; error = $message;
        line = $_.InvocationInfo.ScriptLineNumber; stack = $_.ScriptStackTrace })
    exit 1
}
