[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateSet('Inspect', 'Validate', 'Audit')]
    [string]$Operation
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repository = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..')).Path
$git = (Get-Command git -CommandType Application).Source
$profile = Import-PowerShellDataFile -LiteralPath (Join-Path $repository '.agents\repository-profile.psd1')

function Invoke-Git {
    param([string[]]$Arguments, [switch]$AllowFailure)
    $output = @(& $git -C $repository @Arguments 2>&1)
    $code = $LASTEXITCODE
    if ($code -ne 0 -and -not $AllowFailure) {
        throw "git $($Arguments -join ' ') failed ($code): $($output -join [Environment]::NewLine)"
    }
    [pscustomobject]@{ Code = $code; Lines = $output; Text = ($output -join [Environment]::NewLine).Trim() }
}

function Assert-MainLayout {
    $tracked = if ($branch -eq $profile.MainBranch) {
        (Invoke-Git @('ls-files')).Lines
    } else {
        (Invoke-Git @('ls-tree', '-r', '--name-only', $profile.MainBranch)).Lines
    }
    foreach ($path in $tracked) {
        foreach ($owned in @($profile.VersionPaths)) {
            if ($path -eq $owned -or $path.StartsWith("$owned/")) {
                throw "Version-owned path is tracked on main: $path"
            }
        }
        foreach ($pattern in @($profile.ForbiddenTrackedPatterns)) {
            if ($path -match $pattern) { throw "Forbidden path is tracked on main: $path" }
        }
    }
    if ($tracked -contains 'assets/README.md' -or $tracked -contains 'assets/README.ja.md') {
        throw 'Shared asset policy must be integrated into the root README.'
    }
}

function Assert-ActiveBranches {
    $activePath = Join-Path $repository '.agents\active-minecraft-branches.txt'
    $active = @(Get-Content -LiteralPath $activePath | ForEach-Object { $_.Trim() } | Where-Object { $_ })
    foreach ($branchName in $active) {
        $exists = Invoke-Git @('show-ref', '--verify', '--quiet', "refs/heads/$branchName") -AllowFailure
        if ($exists.Code -ne 0) { throw "Active Minecraft branch is missing: $branchName" }
        $ancestor = Invoke-Git @('merge-base', '--is-ancestor', $profile.MainBranch, $branchName) -AllowFailure
        if ($ancestor.Code -ne 0) { throw "$branchName is not derived from $($profile.MainBranch)." }
        foreach ($shared in @($profile.SharedPaths)) {
            $difference = Invoke-Git @('diff', '--quiet', "$($profile.MainBranch)..$branchName", '--', $shared) -AllowFailure
            if ($difference.Code -eq 1) { throw "Shared path differs on ${branchName}: $shared" }
            if ($difference.Code -gt 1) { throw "Unable to compare shared path on ${branchName}: $shared" }
        }
    }
    $active
}

$branch = (Invoke-Git @('branch', '--show-current')).Text
$headProbe = Invoke-Git @('rev-parse', '--verify', 'HEAD') -AllowFailure
$head = if ($headProbe.Code -eq 0) { $headProbe.Text } else { $null }
$status = (Invoke-Git @('status', '--porcelain=v1', '--untracked-files=all')).Lines
$worktrees = (Invoke-Git @('worktree', 'list', '--porcelain')).Lines
$activeBranches = @(Get-Content -LiteralPath (Join-Path $repository '.agents\active-minecraft-branches.txt') |
    ForEach-Object { $_.Trim() } | Where-Object { $_ })

if ($Operation -in @('Validate', 'Audit')) {
    & (Join-Path $repository '.agents\skills\manage-ysm-epicfight-compat-git\scripts\verify-skill-parity.ps1')
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
if ($Operation -eq 'Validate') {
    if ($branch -eq $profile.MainBranch) {
        Assert-MainLayout
    } elseif ($branch -in $activeBranches) {
        $ancestor = Invoke-Git @('merge-base', '--is-ancestor', $profile.MainBranch, $branch) -AllowFailure
        if ($ancestor.Code -ne 0) { throw "$branch is not derived from $($profile.MainBranch)." }
        & (Join-Path $repository $profile.RepositoryVerifier)
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    } else {
        throw "Validation is not configured for branch '$branch'."
    }
}
if ($Operation -eq 'Audit') {
    Assert-MainLayout
    $activeBranches = @(Assert-ActiveBranches)
}

[pscustomobject]@{
    operation = $Operation
    repository = $repository
    branch = $branch
    head = $head
    dirty = $status.Count -gt 0
    status = $status
    activeMinecraftBranches = $activeBranches
    worktrees = $worktrees
} | ConvertTo-Json -Depth 5 -Compress
