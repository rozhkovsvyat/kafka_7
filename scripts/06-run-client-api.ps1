# ============================================================
# Скрипт 6. Запуск CLIENT API
#
# Использование:
#   pwsh .\scripts\06-run-client-api.ps1 search "Умные часы" user123
#   pwsh .\scripts\06-run-client-api.ps1 recommend user123
# ============================================================

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
chcp 65001 | Out-Null

# ── Загрузка .env ──────────────────────────────────────────
$envFile = "$PSScriptRoot\..\.env"
if (-not (Test-Path $envFile)) {
    Write-Host "Файл .env не найден." -ForegroundColor Red; exit 1
}
Get-Content $envFile | ForEach-Object {
    $line = $_.Trim()
    if ($line -and -not $line.StartsWith("#")) {
        $key, $val = $line -split "=", 2
        $val = $val -replace '%USERPROFILE%', $env:USERPROFILE
        [System.Environment]::SetEnvironmentVariable($key.Trim(), $val.Trim(), "Process")
    }
}

$productsFile = (Resolve-Path "$PSScriptRoot\..\data\products.json").Path
[System.Environment]::SetEnvironmentVariable("SHOP_PRODUCTS_FILE", $productsFile, "Process")

$storageFile = "$((Resolve-Path "$PSScriptRoot\..").Path)\data\client-queries.jsonl"
[System.Environment]::SetEnvironmentVariable("CLIENT_STORAGE_FILE", $storageFile, "Process")

Write-Host "`n=== CLIENT API ===" -ForegroundColor Cyan
Write-Host "Bootstrap : $env:BOOTSTRAP_SERVER"
Write-Host "Команда   : $args`n"

# ── Сборка и запуск ────────────────────────────────────────
$clientApiDir = (Resolve-Path "$PSScriptRoot\..\java\client-api").Path
Push-Location $clientApiDir
try {
    mvn package -DskipTests --no-transfer-progress -q
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    $jar = Get-ChildItem target\client-api-*.jar | Select-Object -First 1
    java -jar $jar.FullName $args
} finally {
    Pop-Location
}
