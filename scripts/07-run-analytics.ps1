# ============================================================
# Скрипт 7. Аналитический пайплайн: Kafka → HDFS → Spark → Kafka
#
# Шаги:
#   1. Ingest: читает shop-products, client-queries из kafka-local → HDFS
#   2. Spark:  обрабатывает данные в HDFS → рекомендации → HDFS
#   3. Produce: читает рекомендации из HDFS → топик recommendations (YC)
#
# Использование:
#   .\scripts\07-run-analytics.ps1
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

Write-Host "`n=== Analytics Pipeline ===" -ForegroundColor Cyan

# ── Сборка JAR ─────────────────────────────────────────────
$analyticsDir = (Resolve-Path "$PSScriptRoot\..\java\analytics").Path
Push-Location $analyticsDir
try {
    Write-Host "Сборка analytics.jar..." -ForegroundColor Yellow
    mvn package -DskipTests --no-transfer-progress -q
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    $jar = (Get-ChildItem target\analytics-*.jar | Select-Object -First 1).FullName
} finally { Pop-Location }

# ── Шаг 1: Ingest ──────────────────────────────────────────
Write-Host "`n[1/3] Ingest: kafka-local → HDFS" -ForegroundColor Cyan
java -jar $jar ingest
if ($LASTEXITCODE -ne 0) { Write-Host "Ingest failed" -ForegroundColor Red; exit $LASTEXITCODE }

# ── Шаг 2: Spark ───────────────────────────────────────────
Write-Host "`n[2/3] Analytics: HDFS → recommendations" -ForegroundColor Cyan
docker compose exec spark python /scripts/analytics.py
if ($LASTEXITCODE -ne 0) { Write-Host "Analytics failed" -ForegroundColor Red; exit $LASTEXITCODE }

# ── Шаг 3: Produce ─────────────────────────────────────────
Write-Host "`n[3/3] Produce: HDFS → Kafka YC (recommendations)" -ForegroundColor Cyan
java -jar $jar produce
if ($LASTEXITCODE -ne 0) { Write-Host "Produce failed" -ForegroundColor Red; exit $LASTEXITCODE }

Write-Host "`n=== Pipeline complete! Проверьте топик recommendations в Kafka UI ===" -ForegroundColor Green
