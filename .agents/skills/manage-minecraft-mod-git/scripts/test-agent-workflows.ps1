[CmdletBinding()]
param([string]$VerifierScript = (Join-Path $PSScriptRoot "verify-agent-workflows.ps1"))

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$verifier = (Resolve-Path -LiteralPath $VerifierScript).Path
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ("agent-workflow-verifier-tests-" + [guid]::NewGuid().ToString("N"))
$git = (Get-Command git -CommandType Application).Source
$pwsh = (Get-Process -Id $PID).Path
$script:Passed = 0

function Assert-True { param([bool]$Condition, [string]$Message) if (-not $Condition) { throw $Message }; $script:Passed++ }
function Hash-Text {
    param([string]$Text)
    $normalized = $Text.Replace("`r`n", "`n").Replace("`r", "`n")
    [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($normalized))).ToLowerInvariant()
}
function Invoke-Verifier {
    param([string]$Root, [switch]$ExpectFailure)
    $output = @(& $pwsh -NoProfile -File $verifier -RepoRoot $Root 2>&1 | ForEach-Object { "$_" })
    $code = $LASTEXITCODE
    if (-not $ExpectFailure -and $code -ne 0) { throw "Verifier failed: $($output -join [Environment]::NewLine)" }
    if ($ExpectFailure -and $code -eq 0) { throw "Verifier unexpectedly succeeded." }
    [pscustomobject]@{ ExitCode = $code; Output = $output }
}

try {
    [void](New-Item -ItemType Directory -Path $testRoot)
    & $git init -q -b main $testRoot
    $managed = ".agents/skills/manage-minecraft-mod-git/SKILL.md"
    $managedPath = Join-Path $testRoot $managed
    [void](New-Item -ItemType Directory -Force -Path (Split-Path -Parent $managedPath))
    $content = "managed`n"
    [IO.File]::WriteAllText($managedPath, $content, [Text.UTF8Encoding]::new($false))
    $block = "<!-- BEGIN MANAGED: minecraft-mod-agent-workflows -->`n- common`n<!-- END MANAGED: minecraft-mod-agent-workflows -->`n"
    [IO.File]::WriteAllText((Join-Path $testRoot "AGENTS.md"), $block + "`n## Repository Rules`n- local`n",
        [Text.UTF8Encoding]::new($false))
    $lock = [ordered]@{
        schemaVersion = 1
        sourceRevision = "0123456789012345678901234567890123456789"
        agentsBlockSha256 = Hash-Text $block
        managedFiles = @([ordered]@{ path = $managed; sha256 = Hash-Text $content })
    }
    [void](New-Item -ItemType Directory -Force -Path (Join-Path $testRoot ".agents"))
    [IO.File]::WriteAllText((Join-Path $testRoot ".agents/agent-workflows.lock.json"),
        ($lock | ConvertTo-Json -Depth 5) + "`n", [Text.UTF8Encoding]::new($false))
    Assert-True ((Invoke-Verifier $testRoot).ExitCode -eq 0) "Valid fixture was rejected."

    [IO.File]::AppendAllText($managedPath, "drift`n")
    $drift = Invoke-Verifier $testRoot -ExpectFailure
    Assert-True (($drift.Output -join "`n") -match "Managed workflow drift") "File drift was not detected."
    [IO.File]::WriteAllText($managedPath, $content, [Text.UTF8Encoding]::new($false))

    [IO.File]::AppendAllText((Join-Path $testRoot "AGENTS.md"),
        "<!-- BEGIN MANAGED: minecraft-mod-agent-workflows -->`n")
    $marker = Invoke-Verifier $testRoot -ExpectFailure
    Assert-True (($marker.Output -join "`n") -match "exactly one managed") "Marker corruption was not detected."

    [ordered]@{ operation = "TestAgentWorkflows"; passed = $script:Passed; status = "PASS" } |
        ConvertTo-Json -Compress
} finally {
    if (Test-Path -LiteralPath $testRoot) {
        $resolved = [IO.Path]::GetFullPath($testRoot)
        $temp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
        if ($resolved.StartsWith($temp, [StringComparison]::OrdinalIgnoreCase)) {
            Remove-Item -LiteralPath $resolved -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
}
