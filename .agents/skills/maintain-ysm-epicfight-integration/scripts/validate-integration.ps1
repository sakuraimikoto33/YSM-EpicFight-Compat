[CmdletBinding()]
param(
    [string]$RepoRoot = "",
    [switch]$AllowContractVersionChange,
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$repository = if ($RepoRoot) { (Resolve-Path -LiteralPath $RepoRoot).Path } else { (Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..')).Path }
$branch = (& git -C $repository branch --show-current).Trim()
if ($LASTEXITCODE -ne 0) { throw "Unable to determine the current branch." }
$activePath = Join-Path $repository '.agents/active-minecraft-branches.txt'
$activeBranches = @([IO.File]::ReadAllLines($activePath) | ForEach-Object { $_.Trim() } | Where-Object { $_ -and -not $_.StartsWith('#') })
if ($branch -notin $activeBranches) { throw "Integration validation requires a branch listed in .agents/active-minecraft-branches.txt; current branch is '$branch'." }
$minecraftVersion = $branch.Substring(3)
$errors = [System.Collections.Generic.List[string]]::new()

function Require-Text {
    param([string]$Path, [string]$Pattern, [string]$Message)
    $full = Join-Path $repository $Path
    if (-not (Test-Path -LiteralPath $full -PathType Leaf)) {
        $errors.Add("Missing $Path")
        return
    }
    if (-not (Select-String -LiteralPath $full -Pattern $Pattern -Quiet)) {
        $errors.Add($Message)
    }
}

Require-Text 'gradle.properties' ("^minecraft_version=" + [regex]::Escape($minecraftVersion) + "$") "Minecraft target must match branch '$branch'."
Require-Text 'gradle.properties' '^forge_version=47\.4\.20$' 'Forge baseline changed unexpectedly.'
Require-Text 'build.gradle' "compileOnly 'net\.okitsu\.ysmmapping:api:0\.1\.0'" 'Mapping API must remain compile-only.'
Require-Text 'src/main/resources/META-INF/mods.toml' 'modId="ysm_mapping_api"' 'Distribution metadata must require Mapping API.'
Require-Text 'src/main/resources/META-INF/mods.toml' 'modId="yes_steve_model"' 'Distribution metadata must require official YSM.'
Require-Text 'src/main/resources/META-INF/mods.toml' 'modId="epicfight"' 'Distribution metadata must require Epic Fight.'

$sourceRoots = @(
    (Join-Path $repository 'src'),
    (Join-Path $repository 'build.gradle'),
    (Join-Path $repository 'gradle.properties')
)
$forbidden = 'com\.elfmcys|rip\.ysm|touhoulittlemaid|modern_ysm|open_ysm|libs[\\/].*ysm.*\.jar'
foreach ($root in $sourceRoots) {
    if (-not (Test-Path -LiteralPath $root)) { continue }
    $files = if (Test-Path -LiteralPath $root -PathType Container) {
        @(Get-ChildItem -LiteralPath $root -Recurse -File)
    } else {
        @((Get-Item -LiteralPath $root))
    }
    $matches = @($files | ForEach-Object {
        Select-String -LiteralPath $_.FullName -Pattern $forbidden -ErrorAction SilentlyContinue
    })
    if ($matches) {
        $errors.Add("Unsupported/private implementation reference found: $($matches[0].Path):$($matches[0].LineNumber)")
    }
}

$tracked = @(& git -C $repository ls-files)
if ($LASTEXITCODE -ne 0) { throw 'Unable to enumerate tracked files.' }
$forbiddenArtifacts = @($tracked | Where-Object {
    $_ -ne 'gradle/wrapper/gradle-wrapper.jar' -and
    ($_ -match '\.(jar|ysm|dll|so|dylib)$')
})
if ($forbiddenArtifacts) {
    $errors.Add("Repository contains forbidden binary/model artifact: $($forbiddenArtifacts[0])")
}

if ($errors.Count -gt 0) {
    $errors | ForEach-Object { Write-Error $_ }
    exit 1
}

if (-not $SkipBuild) {
    $wrapper = Join-Path $repository 'gradlew.bat'
    if (-not (Test-Path -LiteralPath $wrapper -PathType Leaf)) {
        throw 'gradlew.bat is missing; regenerate the checked-in Gradle wrapper.'
    }
    & $wrapper check --console=plain
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

[pscustomobject]@{
    success = $true
    target = $branch
    officialYsmOnly = $true
    buildSkipped = [bool]$SkipBuild
} | ConvertTo-Json -Compress
