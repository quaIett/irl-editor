<#
    One-way sync: bbs-irlights-addon/patches/*.irlights -> this repo's bundled
    resources (src/client/resources/assets/irl-redactor/patches/). The addon is
    the source of truth; never edit in the other direction.

    Paths are relative to this script's location so it works regardless of cwd.
#>

$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$sourceDir = Join-Path $scriptDir '..\..\bbs-irlights-addon\patches'
$destDir   = Join-Path $scriptDir '..\src\client\resources\assets\irl-redactor\patches'

$sourceDir = (Resolve-Path $sourceDir).Path
if (-not (Test-Path $destDir))
{
    New-Item -ItemType Directory -Path $destDir -Force | Out-Null
}
$destDir = (Resolve-Path $destDir).Path

$files = Get-ChildItem -Path $sourceDir -Filter '*.irlights' | Sort-Object Name

$allMatch = $true
$rows = @()

foreach ($file in $files)
{
    $destPath = Join-Path $destDir $file.Name

    # Byte-for-byte copy, no re-encoding.
    Copy-Item -Path $file.FullName -Destination $destPath -Force

    $srcHash  = (Get-FileHash -Path $file.FullName -Algorithm MD5).Hash
    $destHash = (Get-FileHash -Path $destPath -Algorithm MD5).Hash
    $match = $srcHash -eq $destHash
    if (-not $match) { $allMatch = $false }

    $rows += [PSCustomObject]@{
        Name      = $file.Name
        SizeBytes = $file.Length
        SourceMD5 = $srcHash
        DestMD5   = $destHash
        Match     = $match
    }
}

$rows | Format-Table -AutoSize

if ($allMatch)
{
    Write-Host "OK: source == dest for all $($rows.Count) file(s)." -ForegroundColor Green
}
else
{
    Write-Host "MISMATCH detected - see table above." -ForegroundColor Red
}
