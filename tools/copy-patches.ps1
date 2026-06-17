<#
.SYNOPSIS
    One-way sync of the .irlights shader patches from IRLite (source of truth) into IRL-redactor.

.DESCRIPTION
    IRLite owns patch GENERATION (Shadres/ + tools/gen-*-patch.ps1). IRL-redactor only
    CONSUMES the finished patches. This script copies every *.irlights from IRLite's
    patches/ folder into the redactor's bundled-resources folder, normalizing to LF so the
    two repos stay byte-identical (a raw `cmp` is then a valid drift check; see .gitattributes).

    NEVER hand-edit the redactor's copies — re-run this instead. Re-running when already in
    sync is a no-op.

.PARAMETER IRLitePatches
    Source folder holding the canonical *.irlights. Defaults to the sibling checkout
    ../IRLite/patches relative to this repo.

.EXAMPLE
    pwsh tools/copy-patches.ps1
    pwsh tools/copy-patches.ps1 -IRLitePatches D:\some\other\IRLite\patches
#>
[CmdletBinding()]
param(
    [string]$IRLitePatches
)

$ErrorActionPreference = 'Stop'

# tools/ sits directly under the repo root.
$repoRoot = Split-Path -Parent $PSScriptRoot
$dest     = Join-Path $repoRoot 'src/client/resources/assets/irl-redactor/patches'

if (-not $IRLitePatches) {
    # Default: sibling IRLite checkout next to this repo (BBS/IRLite, BBS/IRL-redactor).
    $IRLitePatches = Join-Path (Split-Path -Parent $repoRoot) 'IRLite/patches'
}

if (-not (Test-Path -LiteralPath $IRLitePatches)) {
    throw "Source patches folder not found: $IRLitePatches  (pass -IRLitePatches <path>)"
}
if (-not (Test-Path -LiteralPath $dest)) {
    throw "Destination folder not found: $dest"
}

Write-Host "Source: $IRLitePatches"
Write-Host "Dest  : $dest"
Write-Host ""

$utf8NoBom = [System.Text.UTF8Encoding]::new($false)
$sources   = Get-ChildItem -LiteralPath $IRLitePatches -Filter *.irlights -File
if ($sources.Count -eq 0) { throw "No *.irlights found in $IRLitePatches" }

$updated = 0
$unchanged = 0
foreach ($f in $sources) {
    $raw = [IO.File]::ReadAllText($f.FullName)
    $lf  = $raw -replace "`r`n", "`n" -replace "`r", "`n"   # canonical LF

    $target = Join-Path $dest $f.Name
    $old = if (Test-Path -LiteralPath $target) { [IO.File]::ReadAllText($target) } else { $null }

    if ($old -ne $lf) {
        [IO.File]::WriteAllText($target, $lf, $utf8NoBom)
        Write-Host ("  UPDATED   {0}" -f $f.Name)
        $updated++
    } else {
        Write-Host ("  unchanged {0}" -f $f.Name)
        $unchanged++
    }
}

# Flag redactor-side patches that have no source counterpart (possible stale/orphan).
$srcNames = $sources.Name
Get-ChildItem -LiteralPath $dest -Filter *.irlights -File |
    Where-Object { $srcNames -notcontains $_.Name } |
    ForEach-Object { Write-Warning ("orphan (no source in IRLite): {0}" -f $_.Name) }

Write-Host ""
Write-Host ("Done. {0} updated, {1} unchanged." -f $updated, $unchanged)
