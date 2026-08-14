[CmdletBinding()]
param([string]$HistoryScript = (Join-Path $PSScriptRoot "history-safety.ps1"))

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$history = (Resolve-Path -LiteralPath $HistoryScript).Path
$pwsh = (Get-Process -Id $PID).Path
$git = (Get-Command git -CommandType Application).Source
$root = Join-Path ([IO.Path]::GetTempPath()) ("history-safety-tests-" + [guid]::NewGuid().ToString("N"))
$script:Passed = 0
function Assert-True { param([bool]$Condition, [string]$Message) if (-not $Condition) { throw $Message }; $script:Passed++ }
function Git {
    param([string[]]$Arguments, [switch]$AllowFailure)
    $output = @(& $git -C $root @Arguments 2>&1 | ForEach-Object { "$_" }); $code = $LASTEXITCODE
    if ($code -ne 0 -and -not $AllowFailure) { throw "fixture git failed: $($output -join [Environment]::NewLine)" }
    [pscustomobject]@{ ExitCode = $code; Lines = $output; Text = $output -join [Environment]::NewLine }
}
function Invoke-History {
    param([string[]]$Arguments, [switch]$ExpectFailure)
    $output = @(& $pwsh -NoProfile -File $history -RepoRoot $root @Arguments 2>&1 | ForEach-Object { "$_" }); $code = $LASTEXITCODE
    if (-not $ExpectFailure -and $code -ne 0) { throw "history workflow failed: $($output -join [Environment]::NewLine)" }
    if ($ExpectFailure -and $code -eq 0) { throw "history workflow unexpectedly succeeded: $($Arguments -join ' '): $($output -join ' ')" }
    [pscustomobject]@{ ExitCode = $code; Json = ($output[-1] | ConvertFrom-Json) }
}
try {
    & $git init -q -b main $root
    [void](Git @("config", "user.name", "History Fixture")); [void](Git @("config", "user.email", "fixture@example.invalid"))
    [IO.File]::WriteAllText((Join-Path $root "value.txt"), "same`n", [Text.UTF8Encoding]::new($false))
    [void](Git @("add", ".")); [void](Git @("commit", "-q", "-m", "base"))
    [void](Git @("branch", "mc/1.21.1"))
    $oldTip = (Git @("rev-parse", "main")).Text.Trim(); $oldTree = (Git @("rev-parse", "main^{tree}")).Text.Trim()

    $inspect = Invoke-History @("-Operation", "Inspect", "-Branch", "main", "-Timestamp", "20000101-000000")
    Assert-True ($inspect.Json.operation -eq "Inspect") "Inspect operation missing."
    Assert-True ($inspect.Json.timestamp -eq "20000101-000000") "Inspect timestamp mismatch."
    Assert-True (-not $inspect.Json.dirty) "Clean fixture reported dirty."
    Assert-True (@($inspect.Json.inProgress).Count -eq 0) "Unexpected in-progress operation."
    Assert-True (@($inspect.Json.plans).Count -eq 1) "Plan count mismatch."
    Assert-True ($inspect.Json.plans[0].tip -eq $oldTip) "Planned tip mismatch."
    Assert-True (-not $inspect.Json.plans[0].collision) "Unexpected archive collision."
    $minecraftInspect = Invoke-History @("-Operation", "Inspect", "-Branch", "mc/1.21.1", "-Timestamp", "20000101-000000")
    Assert-True ($minecraftInspect.Json.plans[0].archive -eq "archive/mc/1.21.1-before-rewrite-20000101-000000") "Minecraft archive name mismatch."

    $snapshot = Invoke-History @("-Operation", "Snapshot", "-Branch", "main", "-Timestamp", "20000101-000001")
    Assert-True (Test-Path -LiteralPath $snapshot.Json.snapshot) "History manifest missing."
    $archive = [string]$snapshot.Json.plans[0].archive
    $denied = Invoke-History @("-Operation", "CreateBackups", "-SnapshotPath", $snapshot.Json.snapshot) -ExpectFailure
    Assert-True ($denied.Json.error -match "ConfirmExecution") "Backup confirmation gate failed."
    Assert-True ((Git @("show-ref", "--verify", "--quiet", "refs/heads/$archive") -AllowFailure).ExitCode -ne 0) "Denied backup was created."
    $created = Invoke-History @("-Operation", "CreateBackups", "-SnapshotPath", $snapshot.Json.snapshot, "-ConfirmExecution")
    Assert-True (@($created.Json.created).Count -eq 1) "Backup creation count mismatch."
    Assert-True ((Git @("rev-parse", $archive)).Text.Trim() -eq $oldTip) "Backup tip mismatch."
    $same = Invoke-History @("-Operation", "Compare", "-SnapshotPath", $snapshot.Json.snapshot)
    Assert-True ($same.Json.match) "Unchanged history state should match."

    [void](Git @("commit", "--amend", "-q", "-m", "rewritten metadata"))
    Assert-True ((Git @("rev-parse", "main")).Text.Trim() -ne $oldTip) "Amend did not rewrite the commit."
    Assert-True ((Git @("rev-parse", "main^{tree}")).Text.Trim() -eq $oldTree) "Metadata rewrite changed the tree."
    $rewritten = Invoke-History @("-Operation", "Compare", "-SnapshotPath", $snapshot.Json.snapshot)
    Assert-True ($rewritten.Json.match) "Tree-equivalent rewrite should match."
    Assert-True ((Git @("rev-parse", $archive)).Text.Trim() -eq $oldTip) "Archive moved after rewrite."

    [void](Git @("branch", "unexpected-ref", $oldTip))
    $unexpectedRef = Invoke-History @("-Operation", "Compare", "-SnapshotPath", $snapshot.Json.snapshot)
    Assert-True (@($unexpectedRef.Json.differences | Where-Object reason -eq "unexpected-ref").Count -eq 1) "Unexpected ref was not detected."
    [void](Git @("branch", "-D", "unexpected-ref"))
    [void](Git @("switch", "-q", "mc/1.21.1"))
    $unexpectedBranch = Invoke-History @("-Operation", "Compare", "-SnapshotPath", $snapshot.Json.snapshot)
    Assert-True (@($unexpectedBranch.Json.differences | Where-Object reason -eq "branch").Count -eq 1) "Current branch change was not detected."
    [void](Git @("switch", "-q", "main"))

    [IO.File]::WriteAllText((Join-Path $root "value.txt"), "different`n", [Text.UTF8Encoding]::new($false))
    [void](Git @("add", ".")); [void](Git @("commit", "-q", "-m", "change tree"))
    $different = Invoke-History @("-Operation", "Compare", "-SnapshotPath", $snapshot.Json.snapshot)
    Assert-True (-not $different.Json.match) "Tree-changing rewrite should differ."
    Assert-True (@($different.Json.differences | Where-Object reason -eq "active-tree").Count -eq 1) "Active-tree difference missing."

    [void](Git @("branch", "archive/main-before-rewrite-20000101-000002", $oldTip))
    $collision = Invoke-History @("-Operation", "Inspect", "-Branch", "main", "-Timestamp", "20000101-000002")
    Assert-True ($collision.Json.plans[0].collision) "Existing archive collision was not detected."

    $batchOutput = @(& $history -RepoRoot $root -Operation Snapshot -Branch @("main", "mc/1.21.1") -Timestamp "20000101-000003")
    $batch = [pscustomobject]@{ Json = ($batchOutput[-1] | ConvertFrom-Json) }
    [void](Git @("branch", "archive/mc/1.21.1-before-rewrite-20000101-000003", "mc/1.21.1"))
    $batchCollision = Invoke-History @("-Operation", "CreateBackups", "-SnapshotPath", $batch.Json.snapshot, "-ConfirmExecution") -ExpectFailure
    Assert-True ($batchCollision.Json.error -match "collision") "Batch collision was not reported."
    Assert-True ((Git @("show-ref", "--verify", "--quiet", "refs/heads/archive/main-before-rewrite-20000101-000003") -AllowFailure).ExitCode -ne 0) "Partial archive was created."

    [ordered]@{ operation = "TestHistorySafety"; passed = $script:Passed; status = "PASS" } | ConvertTo-Json -Compress
} finally {
    if (Test-Path -LiteralPath $root) { Remove-Item -LiteralPath $root -Recurse -Force -ErrorAction SilentlyContinue }
}
