[CmdletBinding()]
param([string]$RepoRoot = "")

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Normalize-Text {
    param([string]$Text)
    $Text.Replace("`r`n", "`n").Replace("`r", "`n")
}
function Get-NormalizedHash {
    param([string]$Path)
    $text = Normalize-Text ([IO.File]::ReadAllText($Path))
    $bytes = [Text.Encoding]::UTF8.GetBytes($text)
    [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()
}
function Get-AgentsBlock {
    param([string]$Path)
    $begin = "<!-- BEGIN MANAGED: minecraft-mod-agent-workflows -->"
    $end = "<!-- END MANAGED: minecraft-mod-agent-workflows -->"
    $text = Normalize-Text ([IO.File]::ReadAllText($Path))
    if ([regex]::Matches($text, [regex]::Escape($begin)).Count -ne 1 -or
        [regex]::Matches($text, [regex]::Escape($end)).Count -ne 1) {
        throw "AGENTS.md must contain exactly one managed begin marker and one managed end marker."
    }
    $start = $text.IndexOf($begin, [StringComparison]::Ordinal)
    $finish = $text.IndexOf($end, $start, [StringComparison]::Ordinal)
    if ($finish -lt $start) { throw "AGENTS.md managed markers are out of order." }
    $finish += $end.Length
    $text.Substring($start, $finish - $start) + "`n"
}
function Get-TextHash {
    param([string]$Text)
    $bytes = [Text.Encoding]::UTF8.GetBytes((Normalize-Text $Text))
    [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()
}

if (-not $RepoRoot) {
    $root = @(& git rev-parse --show-toplevel 2>&1)
    if ($LASTEXITCODE -ne 0) { throw "Not inside a Git worktree: $($root -join [Environment]::NewLine)" }
    $RepoRoot = "$($root[-1])"
}
$repository = (Resolve-Path -LiteralPath $RepoRoot).Path
$reported = @(& git -C $repository rev-parse --show-toplevel 2>&1)
if ($LASTEXITCODE -ne 0 -or [IO.Path]::GetFullPath("$($reported[-1])") -ne [IO.Path]::GetFullPath($repository)) {
    throw "Repository root mismatch: $repository"
}
$lockPath = Join-Path $repository ".agents/agent-workflows.lock.json"
if (-not (Test-Path -LiteralPath $lockPath -PathType Leaf)) { throw "Generated workflow lock is missing: $lockPath" }
$lock = Get-Content -Raw -LiteralPath $lockPath | ConvertFrom-Json
if ([int]$lock.schemaVersion -ne 1) { throw "Unsupported workflow lock schema: $($lock.schemaVersion)" }
if ([string]$lock.sourceRevision -notmatch '^[0-9a-fA-F]{40,64}$') { throw "Invalid sourceRevision in workflow lock." }

$paths = @($lock.managedFiles | ForEach-Object { [string]$_.path })
if (-not $paths.Count -or @($paths | Sort-Object -Unique).Count -ne $paths.Count -or
    ($paths -join "`n") -cne (($paths | Sort-Object) -join "`n")) {
    throw "Workflow lock managedFiles must be non-empty, unique, and sorted."
}
$verified = [Collections.Generic.List[object]]::new()
foreach ($item in @($lock.managedFiles)) {
    $relative = ([string]$item.path).Replace("\", "/")
    if (-not $relative -or [IO.Path]::IsPathRooted($relative) -or $relative -match '(^|/)\.\.(/|$)') {
        throw "Unsafe managed path in workflow lock: $relative"
    }
    $path = Join-Path $repository $relative
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Managed workflow file is missing: $relative" }
    $actual = Get-NormalizedHash $path
    $expected = ([string]$item.sha256).ToLowerInvariant()
    if ($actual -cne $expected) { throw "Managed workflow drift: $relative" }
    $verified.Add([ordered]@{ path = $relative; sha256 = $actual })
}
$agentsPath = Join-Path $repository "AGENTS.md"
if (-not (Test-Path -LiteralPath $agentsPath -PathType Leaf)) { throw "AGENTS.md is missing." }
$agentsHash = Get-TextHash (Get-AgentsBlock $agentsPath)
if ($agentsHash -cne ([string]$lock.agentsBlockSha256).ToLowerInvariant()) {
    throw "Managed AGENTS.md block drift."
}

[ordered]@{
    operation = "VerifyAgentWorkflows"
    success = $true
    sourceRevision = [string]$lock.sourceRevision
    files = @($verified)
    agentsBlockSha256 = $agentsHash
} | ConvertTo-Json -Depth 6 -Compress
