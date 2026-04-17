# ============================================================
# Скрипт 3. Запуск SHOP API
#
# Читает data/products.json и отправляет все товары
# в Kafka топик shop-products.
#
# Использование:
#   .\scripts\03-run-shop-api.ps1
# ============================================================

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
chcp 65001 | Out-Null

# ── Загрузка .env ──────────────────────────────────────────
$envFile = "$PSScriptRoot\..\.env"
if (-not (Test-Path $envFile)) {
    Write-Host "Файл .env не найден. Создайте: copy .env.example .env" -ForegroundColor Red
    exit 1
}

Get-Content $envFile | ForEach-Object {
    $line = $_.Trim()
    if ($line -and -not $line.StartsWith("#")) {
        $key, $val = $line -split "=", 2
        $val = $val -replace '%USERPROFILE%', $env:USERPROFILE
        $val = $val -replace '\${USERPROFILE}', $env:USERPROFILE
        [System.Environment]::SetEnvironmentVariable($key.Trim(), $val.Trim(), "Process")
    }
}

# ── Путь к файлу товаров (абсолютный, чтобы не зависеть от CWD) ──
$productsFile = (Resolve-Path "$PSScriptRoot\..\data\products.json").Path
[System.Environment]::SetEnvironmentVariable("SHOP_PRODUCTS_FILE", $productsFile, "Process")

Write-Host "`n=== SHOP API ===" -ForegroundColor Cyan
Write-Host "Bootstrap : $env:BOOTSTRAP_SERVER"
Write-Host "Товары    : $productsFile"
Write-Host "Топик     : $env:TOPIC_SHOP_PRODUCTS`n"

# ── Сборка и запуск ────────────────────────────────────────
$shopApiDir = (Resolve-Path "$PSScriptRoot\..\java\shop-api").Path
Push-Location $shopApiDir
try {
    mvn package -DskipTests --no-transfer-progress
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    $jar = Get-ChildItem target\shop-api-*.jar | Select-Object -First 1
    java -jar $jar.FullName
} finally {
    Pop-Location
}
