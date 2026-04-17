# ============================================================
# Скрипт 2. Создание пользователей и прав доступа через yc CLI
#
# Создаёт сервисных пользователей с минимально необходимыми правами:
#
# Пользователь       Топик               Роль
# ─────────────────────────────────────────────────────────
# shop-producer    → shop-products       producer
# streams-app      → shop-products       consumer
#                  → products-filtered   producer
# kafka-connect    → products-filtered   consumer
# mirrormaker      → products-filtered   consumer
# analytics        → recommendations     producer
# client-producer  → client-queries      producer
# client-consumer  → recommendations     consumer
#
# Требования:
#   - yc CLI установлен и аутентифицирован (yc init)
#   - Заполненный .env (KAFKA_CLUSTER_ID, KAFKA_PASS_APP обязательны)
#
# Использование:
#   .\scripts\02-setup-acl.ps1
# ============================================================

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
chcp 65001 | Out-Null

# ── Проверка yc CLI ────────────────────────────────────────
if (-not (Get-Command "yc" -ErrorAction SilentlyContinue)) {
    Write-Host "yc CLI не найден." -ForegroundColor Red
    Write-Host "  iex (New-Object System.Net.WebClient).DownloadString('https://storage.yandexcloud.net/yandexcloud-yc/install.ps1')" -ForegroundColor Yellow
    Write-Host "  Затем: yc init" -ForegroundColor Yellow
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

$appPassVar = Get-Variable -Name 'KAFKA_PASS_APP' -Scope Script -ErrorAction SilentlyContinue
$KAFKA_PASS_APP = if ($appPassVar) { $appPassVar.Value } else { $null }
if (-not $KAFKA_PASS_APP) {
    Write-Host "KAFKA_PASS_APP не задан в .env — пароль для сервисных аккаунтов" -ForegroundColor Red
    exit 1
}

Write-Host "`n=== Создание пользователей YC Kafka ===" -ForegroundColor Cyan
Write-Host "Cluster ID: $KAFKA_CLUSTER_ID`n"

# ── Создание пользователя (идемпотентно: create → если существует → grant) ─
function New-KafkaUser {
    param(
        [string]$Username,
        [string[]]$Permissions
    )
    Write-Host "Пользователь: $Username" -ForegroundColor White

    $permFlags = $Permissions | ForEach-Object { "--permission", $_ }

    $result = yc managed-kafka user create $Username `
        --cluster-id $KAFKA_CLUSTER_ID `
        --password $KAFKA_PASS_APP `
        @permFlags 2>&1

    if ($LASTEXITCODE -eq 0) {
        Write-Host "  [OK] $Username создан" -ForegroundColor Green
        return
    }

    if ($result -match "already exists|уже существует") {
        Write-Host "  [EXISTS] $Username — уже существует, применяю права..." -ForegroundColor DarkYellow
        Grant-KafkaPermissions $Username $Permissions
    } else {
        Write-Host "  [WARN] $result" -ForegroundColor Yellow
    }
}

# ── Выдача прав существующему пользователю (по одному — ограничение YC API) ─
function Grant-KafkaPermissions {
    param(
        [string]$Username,
        [string[]]$Permissions
    )
    foreach ($perm in $Permissions) {
        $result = yc managed-kafka user grant-permission $Username `
            --cluster-id $KAFKA_CLUSTER_ID `
            --permission $perm 2>&1

        if ($LASTEXITCODE -eq 0) {
            Write-Host "  [OK] $perm" -ForegroundColor Green
        } elseif ($result -match "no changes") {
            Write-Host "  [SKIP] $perm — уже есть" -ForegroundColor DarkGray
        } else {
            Write-Host "  [WARN] $perm — $result" -ForegroundColor Yellow
        }
    }
}

# ── Чтение имён сервисных аккаунтов из .env ────────────────
function Get-EnvOrDefault($Name, $Default) {
    $v = Get-Variable -Name $Name -Scope Script -ErrorAction SilentlyContinue
    if ($v) { $v.Value } else { $Default }
}
$U_SHOP    = Get-EnvOrDefault 'KAFKA_USER_SHOP'            'shop-producer'
$U_STREAMS = Get-EnvOrDefault 'KAFKA_USER_STREAMS'         'streams-app'
$U_CONNECT = Get-EnvOrDefault 'KAFKA_USER_CONNECT'         'kafka-connect'
$U_MIRROR  = Get-EnvOrDefault 'KAFKA_USER_MIRROR'          'mirrormaker'
$U_ANALYTICS = Get-EnvOrDefault 'KAFKA_USER_ANALYTICS'     'analytics'
$U_CLIENT_P  = Get-EnvOrDefault 'KAFKA_USER_CLIENT_PRODUCER' 'client-producer'
$U_CLIENT_C  = Get-EnvOrDefault 'KAFKA_USER_CLIENT_CONSUMER' 'client-consumer'

# ── Создание пользователей ─────────────────────────────────
New-KafkaUser $U_SHOP @(
    "topic=shop-products,role=producer"
)

New-KafkaUser $U_STREAMS @(
    "topic=shop-products,role=consumer",
    "topic=products-filtered,role=producer"
)

New-KafkaUser $U_CONNECT @(
    "topic=products-filtered,role=consumer",
    "topic=connect-configs,role=producer",
    "topic=connect-configs,role=consumer",
    "topic=connect-offsets,role=producer",
    "topic=connect-offsets,role=consumer",
    "topic=connect-status,role=producer",
    "topic=connect-status,role=consumer"
)

New-KafkaUser $U_MIRROR @(
    "topic=shop-products,role=ACCESS_ROLE_CONSUMER",
    "topic=client-queries,role=ACCESS_ROLE_CONSUMER",
    "topic=products-filtered,role=ACCESS_ROLE_CONSUMER"
)

New-KafkaUser $U_ANALYTICS @(
    "topic=recommendations,role=producer"
)

New-KafkaUser $U_CLIENT_P @(
    "topic=client-queries,role=producer"
)

New-KafkaUser $U_CLIENT_C @(
    "topic=recommendations,role=consumer"
)

# ── Проверка ────────────────────────────────────────────────
Write-Host "`n--- Пользователи в кластере ---" -ForegroundColor Cyan
yc managed-kafka user list --cluster-id $KAFKA_CLUSTER_ID

Write-Host "`n=== Пользователи созданы ===" -ForegroundColor Green
