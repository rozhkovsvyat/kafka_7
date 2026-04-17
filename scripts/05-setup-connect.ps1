# ============================================================
# Скрипт 5. Kafka Connect: products-filtered → файл
#
# Регистрирует FileStreamSinkConnector через REST API Connect.
# Запускать после: docker compose up -d kafka-connect
#
# Использование:
#   .\scripts\05-setup-connect.ps1
# ============================================================

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
chcp 65001 | Out-Null

$connectUrl = "http://localhost:8083"
$connectorName = "products-filtered-file-sink"

# ── Ждём готовности Kafka Connect ──────────────────────────
Write-Host "Ожидание Kafka Connect ($connectUrl)..." -ForegroundColor Yellow
$retries = 0
do {
    Start-Sleep -Seconds 5
    $ready = $false
    try {
        $resp = Invoke-RestMethod -Uri "$connectUrl/connectors" -Method Get -ErrorAction SilentlyContinue
        $ready = $true
    } catch { }
    $retries++
} until ($ready -or $retries -ge 24)
if (-not $ready) { Write-Host "[ERROR] Kafka Connect не поднялся" -ForegroundColor Red; exit 1 }
Write-Host "Kafka Connect готов." -ForegroundColor Green

# ── Удаляем старый коннектор если есть ─────────────────────
try {
    Invoke-RestMethod -Uri "$connectUrl/connectors/$connectorName" -Method Delete -ErrorAction SilentlyContinue | Out-Null
    Write-Host "Старый коннектор удалён." -ForegroundColor Yellow
} catch { }

# ── Регистрируем коннектор ──────────────────────────────────
$body = @{
    name   = $connectorName
    config = @{
        "connector.class"   = "org.apache.kafka.connect.file.FileStreamSinkConnector"
        "tasks.max"         = "1"
        "topics"            = "products-filtered"
        "file"              = "/data/products-filtered.jsonl"
        "key.converter"     = "org.apache.kafka.connect.storage.StringConverter"
        "value.converter"   = "org.apache.kafka.connect.storage.StringConverter"
    }
} | ConvertTo-Json -Depth 3

$result = Invoke-RestMethod -Uri "$connectUrl/connectors" -Method Post `
    -ContentType "application/json" -Body $body
Write-Host "Коннектор зарегистрирован: $($result.name)" -ForegroundColor Green

# ── Проверяем статус ────────────────────────────────────────
Start-Sleep -Seconds 3
$status = Invoke-RestMethod -Uri "$connectUrl/connectors/$connectorName/status" -Method Get
Write-Host "Статус: $($status.connector.state)" -ForegroundColor Cyan
Write-Host ""
Write-Host "Данные пишутся в: data/connect-output/products-filtered.jsonl" -ForegroundColor Green
Write-Host "Запустите shop-api и streams-filter чтобы увидеть записи." -ForegroundColor Yellow
