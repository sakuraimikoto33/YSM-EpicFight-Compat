[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repository = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..')).Path
$agents = Get-Content -Raw -LiteralPath (Join-Path $repository 'AGENTS.md')
$expected = @(
    'maintain-ysm-epicfight-integration',
    'manage-ysm-epicfight-compat-git',
    'rewrite-ysm-epicfight-compat-history'
)
$errors = [System.Collections.Generic.List[string]]::new()

foreach ($name in $expected) {
    $root = Join-Path $repository ".agents\skills\$name"
    foreach ($relative in @('SKILL.md', 'agents\openai.yaml')) {
        if (-not (Test-Path -LiteralPath (Join-Path $root $relative) -PathType Leaf)) {
            $errors.Add("$name is missing $relative")
        }
    }
    if ($agents -notmatch [regex]::Escape($name)) {
        $errors.Add("AGENTS.md does not route to $name")
    }
}

foreach ($relative in @(
    '.agents\repository-profile.psd1',
    '.agents\skills\manage-ysm-epicfight-compat-git\references\branch-ownership.md',
    '.agents\skills\manage-ysm-epicfight-compat-git\references\task-boundaries.md',
    '.agents\skills\rewrite-ysm-epicfight-compat-history\references\history-policy.md'
)) {
    if (-not (Test-Path -LiteralPath (Join-Path $repository $relative) -PathType Leaf)) {
        $errors.Add("Missing repository instruction: $relative")
    }
}

$instructionFiles = Get-ChildItem -LiteralPath (Join-Path $repository '.agents\skills') -Recurse -File |
    Where-Object { $_.Name -in @('SKILL.md', 'openai.yaml') }
foreach ($file in $instructionFiles) {
    if ((Get-Content -Raw -LiteralPath $file.FullName) -match '\bTODO\b') {
        $errors.Add("Unresolved TODO in $($file.FullName)")
    }
}

$active = @(Get-Content -LiteralPath (Join-Path $repository '.agents\active-minecraft-branches.txt') |
    ForEach-Object { $_.Trim() } | Where-Object { $_ })
if ($active.Count -ne 1 -or $active[0] -ne 'mc/1.20.1') {
    $errors.Add('Active Minecraft branches must contain only mc/1.20.1.')
}

if ($errors.Count -gt 0) {
    $errors | ForEach-Object { Write-Error $_ }
    exit 1
}

[pscustomobject]@{ success = $true; skills = $expected } | ConvertTo-Json -Compress
