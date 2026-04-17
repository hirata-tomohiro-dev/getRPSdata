[CmdletBinding()]
param(
    [string]$ConfigPath = '',
    [string]$JavaPath = ''
)

$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if (-not $ConfigPath) {
    $ConfigPath = Join-Path $repoRoot 'config\rps-sync.properties'
}

$ConfigPath = [System.IO.Path]::GetFullPath($ConfigPath)
$jarPath = Join-Path $repoRoot 'dist\rps-table-sync.jar'
$jdbcJarPath = Join-Path $repoRoot 'lib\postgresql-42.7.10.jar'

if (-not (Test-Path $ConfigPath)) {
    throw "Config file was not found: $ConfigPath"
}
if (-not (Test-Path $jarPath)) {
    throw "Application jar was not found: $jarPath"
}
if (-not (Test-Path $jdbcJarPath)) {
    throw "PostgreSQL JDBC jar was not found: $jdbcJarPath"
}

if (-not $JavaPath) {
    $javaFromHome = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { '' }
    if ($javaFromHome -and (Test-Path $javaFromHome)) {
        $JavaPath = $javaFromHome
    }
    else {
        $JavaPath = & (Join-Path $PSScriptRoot 'Setup-BundledJavaRuntime.ps1')
    }
}

$classPath = "$jarPath;$jdbcJarPath"
& $JavaPath -cp $classPath jp.co.nksol.rpssync.RpsTableSyncApp $ConfigPath
exit $LASTEXITCODE
