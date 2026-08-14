[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateSet("Inspect", "Snapshot", "CreateBackups", "Compare")]
    [string]$Operation,
    [string]$RepoRoot = "",
    [string[]]$Branch = @(),
    [string]$Timestamp = "",
    [string]$SnapshotPath = "",
    [switch]$ConfirmExecution
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$script:GitExecutable = (Get-Command git -CommandType Application).Source

function Git {
    param([string[]]$Arguments, [switch]$AllowFailure)
    $lines = @(& $script:GitExecutable -C $script:Root @Arguments 2>&1 | ForEach-Object { "$_" })
    $code = $LASTEXITCODE
    if ($code -ne 0 -and -not $AllowFailure) { throw "git $($Arguments -join ' ') failed ($code): $($lines -join [Environment]::NewLine)" }
    [pscustomobject]@{ ExitCode = $code; Lines = $lines; Text = $lines -join [Environment]::NewLine }
}
if (-not $RepoRoot) {
    $value = @(& $script:GitExecutable rev-parse --show-toplevel 2>&1)
    if ($LASTEXITCODE -ne 0) { throw "Not inside a Git worktree." }
    $RepoRoot = "$($value[-1])"
}
$script:Root = (Resolve-Path -LiteralPath $RepoRoot).Path
function Result { param($Value) $Value | ConvertTo-Json -Depth 12 -Compress }
function Create-ArchiveRefs {
    param($Plans)
    $start = [Diagnostics.ProcessStartInfo]::new($script:GitExecutable)
    $start.UseShellExecute = $false
    $start.RedirectStandardInput = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    foreach ($argument in @("-C", $script:Root, "update-ref", "--stdin")) { [void]$start.ArgumentList.Add($argument) }
    $process = [Diagnostics.Process]::new(); $process.StartInfo = $start; [void]$process.Start()
    $process.StandardInput.NewLine = "`n"
    foreach ($plan in $Plans) {
        $process.StandardInput.WriteLine("create refs/heads/$($plan.archive) $($plan.tip)")
    }
    $process.StandardInput.Close()
    $stdout = $process.StandardOutput.ReadToEndAsync(); $stderr = $process.StandardError.ReadToEndAsync()
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) {
        throw "Atomic archive creation failed ($($process.ExitCode)): $($stderr.GetAwaiter().GetResult()) $($stdout.GetAwaiter().GetResult())"
    }
}
function Safe-Branches {
    if (-not $Branch.Count) { throw "Name every affected local branch with -Branch." }
    $values = @($Branch | Sort-Object -Unique)
    foreach ($name in $values) {
        if ($name -notmatch '^[0-9A-Za-z._/-]+$' -or $name.StartsWith("-") -or $name.Contains("..") -or
            (Git -Arguments @("show-ref", "--verify", "--quiet", "refs/heads/$name") -AllowFailure).ExitCode -ne 0) {
            throw "Unsafe or missing affected branch: '$name'."
        }
    }
    $values
}
function In-Progress {
    $found = [Collections.Generic.List[string]]::new()
    foreach ($name in @("MERGE_HEAD", "CHERRY_PICK_HEAD", "REVERT_HEAD", "BISECT_LOG", "rebase-merge", "rebase-apply")) {
        $path = (Git -Arguments @("rev-parse", "--git-path", $name)).Text.Trim()
        if (-not [IO.Path]::IsPathRooted($path)) { $path = Join-Path $script:Root $path }
        if (Test-Path -LiteralPath $path) { $found.Add($name) }
    }
    @($found)
}
function Worktree-Associations {
    $items = [Collections.Generic.List[object]]::new(); $path = $null; $branch = $null
    foreach ($line in (Git -Arguments @("worktree", "list", "--porcelain")).Lines + "") {
        if ($line.StartsWith("worktree ")) { $path = $line.Substring(9); $branch = $null }
        elseif ($line.StartsWith("branch refs/heads/")) { $branch = $line.Substring(18) }
        elseif (-not $line -and $path) { $items.Add([ordered]@{ path = $path; branch = $branch }); $path = $null }
    }
    @($items)
}
function Untracked-State {
    $items = [Collections.Generic.List[object]]::new()
    foreach ($path in (Git -Arguments @("ls-files", "--others", "--exclude-standard")).Lines) {
        $absolute = Join-Path $script:Root $path
        $items.Add([ordered]@{ path = $path; sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $absolute).Hash })
    }
    @($items)
}
function Ref-State {
    $map = [ordered]@{}
    foreach ($line in (Git -Arguments @("for-each-ref", "--format=%(refname)%09%(objectname)", "refs/heads", "refs/stash")).Lines) {
        $parts = $line -split "`t", 2; $map[$parts[0]] = $parts[1]
    }
    $map
}
function Plans {
    param([string[]]$Branches, [string]$Stamp)
    @($Branches | ForEach-Object {
        $archive = "archive/$_-before-rewrite-$Stamp"
        [ordered]@{ branch = $_; tip = (Git -Arguments @("rev-parse", $_)).Text.Trim();
            tree = (Git -Arguments @("rev-parse", "${_}^{tree}")).Text.Trim(); archive = $archive;
            collision = ((Git -Arguments @("show-ref", "--verify", "--quiet", "refs/heads/$archive") -AllowFailure).ExitCode -eq 0) }
    })
}
function Inspect-Rewrite {
    $branches = Safe-Branches
    if (-not $Timestamp) { $Timestamp = Get-Date -Format "yyyyMMdd-HHmmss" }
    [ordered]@{ operation = "Inspect"; repository = $script:Root; timestamp = $Timestamp;
        currentBranch = (Git -Arguments @("branch", "--show-current")).Text.Trim();
        dirty = [bool](Git -Arguments @("status", "--porcelain=v1", "--untracked-files=all")).Lines.Count;
        inProgress = @(In-Progress); plans = @(Plans -Branches $branches -Stamp $Timestamp);
        worktrees = @(Worktree-Associations); stashes = @((Git -Arguments @("stash", "list", "--format=%gd%x09%H%x09%s")).Lines) }
}
function New-RewriteSnapshot {
    $inspection = Inspect-Rewrite
    if (@($inspection.plans | Where-Object collision).Count) { throw "At least one archive name already exists; no backup may be created." }
    $root = Join-Path ([IO.Path]::GetTempPath()) ("history-safety-" + [guid]::NewGuid().ToString("N"))
    [void](New-Item -ItemType Directory -Force -Path $root)
    $manifest = [ordered]@{ format = 1; repository = $script:Root; timestamp = $inspection.timestamp;
        currentBranch = $inspection.currentBranch; status = @((Git -Arguments @("status", "--porcelain=v2", "--untracked-files=all")).Lines);
        untracked = @(Untracked-State); refs = Ref-State; worktrees = @(Worktree-Associations); plans = @($inspection.plans) }
    $path = Join-Path $root "manifest.json"
    $manifest | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $path -Encoding utf8
    [ordered]@{ operation = "Snapshot"; snapshot = $path; plans = @($inspection.plans) }
}
function Load-Manifest {
    if (-not $SnapshotPath) { throw "-SnapshotPath is required." }
    $path = (Resolve-Path -LiteralPath $SnapshotPath).Path
    $value = Get-Content -Raw -LiteralPath $path | ConvertFrom-Json
    if ([IO.Path]::GetFullPath([string]$value.repository) -ne [IO.Path]::GetFullPath($script:Root)) { throw "Snapshot belongs to another repository." }
    $value
}
function Create-Backups {
    if (-not $ConfirmExecution) { throw "CreateBackups requires -ConfirmExecution." }
    $manifest = Load-Manifest
    if (@(In-Progress).Count -or @((Git -Arguments @("status", "--porcelain=v1", "--untracked-files=all")).Lines).Count) {
        throw "Backup creation requires clean, stable Git state."
    }
    foreach ($plan in $manifest.plans) {
        if ((Git -Arguments @("rev-parse", [string]$plan.branch)).Text.Trim() -ne [string]$plan.tip) { throw "Branch moved: $($plan.branch)" }
        if ((Git -Arguments @("show-ref", "--verify", "--quiet", "refs/heads/$($plan.archive)") -AllowFailure).ExitCode -eq 0) {
            throw "Archive collision: $($plan.archive)"
        }
    }
    Create-ArchiveRefs -Plans @($manifest.plans)
    $created = @($manifest.plans | ForEach-Object { [ordered]@{ archive = $_.archive; tip = $_.tip } })
    [ordered]@{ operation = "CreateBackups"; created = $created }
}
function Compare-Rewrite {
    $manifest = Load-Manifest; $differences = [Collections.Generic.List[object]]::new()
    foreach ($plan in $manifest.plans) {
        $archive = Git -Arguments @("rev-parse", [string]$plan.archive) -AllowFailure
        if ($archive.ExitCode -ne 0 -or $archive.Text.Trim() -ne [string]$plan.tip) {
            $differences.Add([ordered]@{ item = $plan.archive; reason = "archive-tip"; expected = $plan.tip; actual = $archive.Text.Trim() })
        }
        $tree = Git -Arguments @("rev-parse", "$($plan.branch)^{tree}") -AllowFailure
        if ($tree.ExitCode -ne 0 -or $tree.Text.Trim() -ne [string]$plan.tree) {
            $differences.Add([ordered]@{ item = $plan.branch; reason = "active-tree"; expected = $plan.tree; actual = $tree.Text.Trim() })
        }
    }
    $targets = @($manifest.plans | ForEach-Object { "refs/heads/$($_.branch)" })
    $archives = @($manifest.plans | ForEach-Object { "refs/heads/$($_.archive)" })
    $currentRefs = Ref-State
    foreach ($property in $manifest.refs.PSObject.Properties) {
        if ($property.Name -notin $targets -and $property.Name -notin $archives -and
            (-not $currentRefs.Contains($property.Name) -or $currentRefs[$property.Name] -ne $property.Value)) {
            $differences.Add([ordered]@{ item = $property.Name; reason = "unaffected-ref"; expected = $property.Value;
                actual = if ($currentRefs.Contains($property.Name)) { $currentRefs[$property.Name] } else { $null } })
        }
    }
    foreach ($property in $currentRefs.GetEnumerator()) {
        if (-not $manifest.refs.PSObject.Properties[$property.Key] -and $property.Key -notin $targets -and $property.Key -notin $archives) {
            $differences.Add([ordered]@{ item = $property.Key; reason = "unexpected-ref"; expected = $null; actual = $property.Value })
        }
    }
    $currentBranch = (Git -Arguments @("branch", "--show-current")).Text.Trim()
    if ($currentBranch -ne [string]$manifest.currentBranch) {
        $differences.Add([ordered]@{ item = "currentBranch"; reason = "branch"; expected = $manifest.currentBranch; actual = $currentBranch })
    }
    $status = @((Git -Arguments @("status", "--porcelain=v2", "--untracked-files=all")).Lines)
    if (($status | ConvertTo-Json -Compress) -ne (@($manifest.status) | ConvertTo-Json -Compress)) {
        $differences.Add([ordered]@{ item = "worktree"; reason = "status"; expected = @($manifest.status); actual = $status })
    }
    $untracked = @(Untracked-State)
    if (($untracked | ConvertTo-Json -Depth 4 -Compress) -ne (@($manifest.untracked) | ConvertTo-Json -Depth 4 -Compress)) {
        $differences.Add([ordered]@{ item = "untracked"; reason = "content" })
    }
    $worktrees = @(Worktree-Associations)
    if (($worktrees | ConvertTo-Json -Depth 4 -Compress) -ne (@($manifest.worktrees) | ConvertTo-Json -Depth 4 -Compress)) {
        $differences.Add([ordered]@{ item = "worktrees"; reason = "association" })
    }
    [ordered]@{ operation = "Compare"; match = -not [bool]$differences.Count; differences = @($differences) }
}

try {
    $value = switch ($Operation) {
        "Inspect" { Inspect-Rewrite }
        "Snapshot" { New-RewriteSnapshot }
        "CreateBackups" { Create-Backups }
        "Compare" { Compare-Rewrite }
    }
    Result -Value $value
} catch {
    Result -Value ([ordered]@{ operation = $Operation; success = $false; error = $_.Exception.Message })
    exit 1
}
