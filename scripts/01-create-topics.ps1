# ============================================================
# Скрипт 1. Создание топиков через Yandex Cloud CLI (yc)
#
# Создаёт 4 топика для аналитической платформы маркетплейса:
#   - shop-products       — товары от магазинов (SHOP API)
#   - products-filtered   — отфильтрованные товары (Kafka Streams)
#   - client-queries      — запросы клиентов (CLIENT API)
#   - recommendations     — рекомендации (аналитика)
#
# Требования:
#   - yc CLI установлен и аутентифицирован (yc init)
#   - Заполненный .env в корне проекта (KAFKA_CLUSTER_ID обязателен)
#
# Установка yc CLI (один раз):
#   iex (New-Object System.Net.WebClient).DownloadString('https://storage.yandexcloud.net/yandexcloud-yc/install.ps1')
#   yc init
#
# Использование:
#   .\scripts\01-create-topics.ps1
# ============================================================

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
chcp 65001 | Out-Null

# ── Проверка yc CLI ────────────────────────────────────────
if (-not (Get-Command "yc" -ErrorAction SilentlyContinue)) {
    Write-Host "yc CLI не найден. Установите:" -ForegroundColor Red
    Write-Host "  iex (New-Object System.Net.WebClient).DownloadString('https://storage.yandexcloud.net/yandexcloud-yc/install.ps1')" -ForegroundColor Yellow
    Write-Host "  Затем авторизуйтесь: yc init" -ForegroundColor Yellow
    exit 1
}

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
        Set-Variable -Name $key.Trim() -Value $val.Trim() -Scope Script
    }
}

# ── Определение ID кластера: из .env или авто-поиск ───────
$clusterIdVar = Get-Variable -Name 'KAFKA_CLUSTER_ID' -Scope Script -ErrorAction SilentlyContinue
$KAFKA_CLUSTER_ID = if ($clusterIdVar) { $clusterIdVar.Value } else { $null }

if (-not $KAFKA_CLUSTER_ID) {
    Write-Host "KAFKA_CLUSTER_ID не найден в .env — ищу кластер автоматически..." -ForegroundColor Yellow
    # stderr (WARNING) отделяем от stdout (JSON) — не используем 2>&1
    $clustersJson = yc managed-kafka cluster list --format json
    try { $clusters = @($clustersJson | ConvertFrom-Json) } catch { $clusters = @() }
    if ($clusters.Count -eq 1) {
        $KAFKA_CLUSTER_ID = $clusters[0].id
        Write-Host "  Кластер найден: $($clusters[0].name) ($KAFKA_CLUSTER_ID)" -ForegroundColor Green
    } elseif ($clusters.Count -gt 1) {
        Write-Host "Найдено несколько кластеров. Добавьте в .env: KAFKA_CLUSTER_ID=<id>" -ForegroundColor Red
        $clusters | ForEach-Object { Write-Host "  $($_.id) — $($_.name)" }
        exit 1
    } else {
        Write-Host "Кластер не найден. Проверьте: yc managed-kafka cluster list" -ForegroundColor Red
        exit 1
    }
}

Write-Host "`n=== Создание топиков в YC Kafka ===" -ForegroundColor Cyan
Write-Host "Cluster ID: $KAFKA_CLUSTER_ID`n"

# ── Конфигурация топиков ───────────────────────────────────
$topics = @(
    @{
        name              = "shop-products"
        description       = "Товары от магазинов (SHOP API)"
        partitions        = 3
        replicationFactor = 3
        retentionMs       = 604800000   # 7 дней
        compressionType   = "lz4"
        cleanupPolicy     = "delete"
        minInsyncReplicas = 2
    },
    @{
        name              = "products-filtered"
        description       = "Отфильтрованные товары (Kafka Streams)"
        partitions        = 3
        replicationFactor = 3
        retentionMs       = 604800000
        compressionType   = "lz4"
        cleanupPolicy     = "delete"
        minInsyncReplicas = 2
    },
    @{
        name              = "client-queries"
        description       = "Запросы клиентов (CLIENT API)"
        partitions        = 3
        replicationFactor = 3
        retentionMs       = 86400000    # 1 день
        compressionType   = "uncompressed"
        cleanupPolicy     = "delete"
        minInsyncReplicas = 2
    },
    @{
        name              = "recommendations"
        description       = "Рекомендации (аналитика)"
        partitions        = 3
        replicationFactor = 3
        retentionMs       = 86400000
        compressionType   = "uncompressed"
        cleanupPolicy     = "delete"
        minInsyncReplicas = 2
    },
    # ── Внутренние топики Kafka Connect ──
    @{
        name              = "connect-configs"
        description       = "Kafka Connect: конфигурация коннекторов"
        partitions        = 1
        replicationFactor = 3
        retentionMs       = 604800000
        compressionType   = "uncompressed"
        cleanupPolicy     = "compact"
        minInsyncReplicas = 2
    },
    @{
        name              = "connect-offsets"
        description       = "Kafka Connect: оффсеты коннекторов"
        partitions        = 3
        replicationFactor = 3
        retentionMs       = 604800000
        compressionType   = "uncompressed"
        cleanupPolicy     = "compact"
        minInsyncReplicas = 2
    },
    @{
        name              = "connect-status"
        description       = "Kafka Connect: статус коннекторов"
        partitions        = 5
        replicationFactor = 3
        retentionMs       = 604800000
        compressionType   = "uncompressed"
        cleanupPolicy     = "compact"
        minInsyncReplicas = 2
    }
)

foreach ($topic in $topics) {
    Write-Host "Создание: $($topic.name) — $($topic.description)" -ForegroundColor White

    $result = yc managed-kafka topic create $topic.name `
        --cluster-id $KAFKA_CLUSTER_ID `
        --partitions $topic.partitions `
        --replication-factor $topic.replicationFactor `
        --min-insync-replicas $topic.minInsyncReplicas `
        --cleanup-policy $topic.cleanupPolicy `
        --compression-type $topic.compressionType `
        --retention-ms $topic.retentionMs 2>&1

    if ($LASTEXITCODE -eq 0) {
        Write-Host "  [OK] $($topic.name)" -ForegroundColor Green
    } else {
        # Топик уже существует — это нормально
        if ($result -match "already exists|уже существует") {
            Write-Host "  [SKIP] $($topic.name) — уже существует" -ForegroundColor DarkYellow
        } else {
            Write-Host "  [WARN] $result" -ForegroundColor Yellow
        }
    }
}

# ── Проверка ────────────────────────────────────────────────
Write-Host "`n--- Топики в кластере ---" -ForegroundColor Cyan
yc managed-kafka topic list --cluster-id $KAFKA_CLUSTER_ID

Write-Host "`n=== Топики созданы ===" -ForegroundColor Green
