# ============================================================
# Скрипт 4. Kafka Streams фильтр запрещённых товаров
#
#   shop-products → [filter] → products-filtered
#
# Использование:
#   .\scripts\04-run-streams-filter.ps1
# ============================================================

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
chcp 65001 | Out-Null

# ── Загрузка .env ──────────────────────────────────────────
$envFile = "$PSScriptRoot\..\.env"
if (-not (Test-Path $envFile)) { Write-Host ".env не найден" -ForegroundColor Red; exit 1 }
Get-Content $envFile | ForEach-Object {
    $line = $_.Trim()
    if ($line -and -not $line.StartsWith("#")) {
        $key, $val = $line -split "=", 2
        $val = $val -replace '%USERPROFILE%', $env:USERPROFILE
        [System.Environment]::SetEnvironmentVariable($key.Trim(), $val.Trim(), "Process")
    }
}
$certDir = (Resolve-Path "$env:USERPROFILE\.kafka").Path
[System.Environment]::SetEnvironmentVariable("CERT_DIR", $certDir, "Process")

# ── Сборка ─────────────────────────────────────────────────
$moduleDir = (Resolve-Path "$PSScriptRoot\..\java\streams-filter").Path
Push-Location $moduleDir
try {
    Write-Host "Сборка streams-filter.jar..." -ForegroundColor Yellow
    mvn package -DskipTests --no-transfer-progress -q
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    $jar = (Get-ChildItem target\streams-filter-*.jar | Select-Object -First 1).FullName
} finally { Pop-Location }

# ── Запуск ─────────────────────────────────────────────────
Write-Host "`n=== Kafka Streams Filter ===" -ForegroundColor Cyan
Write-Host "shop-products → [filter] → products-filtered" -ForegroundColor Yellow
java -jar $jar
