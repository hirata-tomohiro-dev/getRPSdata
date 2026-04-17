[CmdletBinding()]
param(
    [string]$RuntimeRoot = ''
)

$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if (-not $RuntimeRoot) {
    $RuntimeRoot = Join-Path $repoRoot '.runtime\temurin17-windows-x64'
}

$RuntimeRoot = [System.IO.Path]::GetFullPath($RuntimeRoot)
$javaExe = Join-Path $RuntimeRoot 'bin\java.exe'
$bundleZip = Join-Path $repoRoot 'assets\java\windows-x64\OpenJDK17U-jre_x64_windows_hotspot_17.0.18_8.zip'

if (Test-Path $javaExe) {
    Write-Output $javaExe
    return
}

if (-not (Test-Path $bundleZip)) {
    throw "Bundled Java runtime ZIP was not found: $bundleZip"
}

New-Item -ItemType Directory -Force -Path $RuntimeRoot | Out-Null
$stagingRoot = Join-Path $env:TEMP ("rpssync-jre-" + [Guid]::NewGuid().Guid)

try {
    Expand-Archive -Path $bundleZip -DestinationPath $stagingRoot -Force
    $expandedRoot = Get-ChildItem -Path $stagingRoot -Directory | Select-Object -First 1
    if (-not $expandedRoot) {
        throw "Expanded Java runtime directory was not found under: $stagingRoot"
    }

    Get-ChildItem -Path $expandedRoot.FullName -Force | Move-Item -Destination $RuntimeRoot -Force
}
finally {
    if (Test-Path $stagingRoot) {
        Remove-Item -Path $stagingRoot -Recurse -Force
    }
}

if (-not (Test-Path $javaExe)) {
    throw "java.exe was not extracted to: $javaExe"
}

Write-Output $javaExe
