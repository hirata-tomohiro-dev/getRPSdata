[CmdletBinding()]
param(
    [string]$BaseConfigPath = '',
    [ValidateRange(1, 100000)]
    [int]$TableLimit = 5
)

$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if (-not $BaseConfigPath) {
    $BaseConfigPath = Join-Path $repoRoot 'config\rps-sync.properties'
}

$BaseConfigPath = [System.IO.Path]::GetFullPath($BaseConfigPath)
if (-not (Test-Path $BaseConfigPath)) {
    throw "Base config file was not found: $BaseConfigPath"
}

$configDir = Join-Path $repoRoot 'config'
$tempConfigPath = Join-Path $configDir ("rps-sync-smoketest." + [Guid]::NewGuid().ToString('N').Substring(0, 8) + ".properties")

Copy-Item -Path $BaseConfigPath -Destination $tempConfigPath -Force

try {
    $appendText = @(
        '',
        '# Auto-generated smoke test overrides',
        "sync.maxTables=$TableLimit",
        'result.dir=result-smoketest',
        'pg.table=rps_table_inventory_smoketest',
        ''
    ) -join [Environment]::NewLine

    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::AppendAllText($tempConfigPath, $appendText, $utf8NoBom)

    & (Join-Path $PSScriptRoot 'Run-RpsSync.ps1') -ConfigPath $tempConfigPath
    exit $LASTEXITCODE
}
finally {
    if (Test-Path $tempConfigPath) {
        Remove-Item -Path $tempConfigPath -Force -ErrorAction SilentlyContinue
    }
}
