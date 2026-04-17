# ============================================================
# Скрипт 8. Очистка — остановка инфраструктуры и удаление артефактов
#
# Действия:
#   1. docker compose down --volumes — контейнеры + volumes
#   2. Удаление Maven target/ во всех модулях
#   3. Удаление файлов Kafka Connect (data/connect-output/)
#   4. Удаление временных файлов (tmp/)
#
# Использование:
#   pwsh .\scripts\08-cleanup.ps1
# ============================================================

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
chcp 65001 | Out-Null

$projectRoot = "$PSScriptRoot\.."
Push-Location $projectRoot

Write-Host "`n=== Очистка проекта ===" -ForegroundColor Cyan

# ── Docker ────────────────────────────────────────────────
Write-Host "`n[1/3] Остановка Docker-контейнеров..." -ForegroundColor White
if (Get-Command "docker" -ErrorAction SilentlyContinue) {
    docker compose down --volumes 2>&1 | ForEach-Object { Write-Host "  $_" }
    Write-Host "  [OK] Контейнеры и volumes удалены" -ForegroundColor Green
} else {
    Write-Host "  [SKIP] Docker не найден" -ForegroundColor DarkYellow
}

# ── Maven target/ ────────────────────────────────────────
Write-Host "`n[2/3] Удаление Maven-артефактов (target/)..." -ForegroundColor White
$modules = @("java\shop-api", "java\client-api", "java\streams-filter", "java\analytics")
foreach ($module in $modules) {
    $targetDir = Join-Path $projectRoot "$module\target"
    if (Test-Path $targetDir) {
        Remove-Item -Recurse -Force $targetDir
        Write-Host "  [OK] $module\target" -ForegroundColor Green
    }
}

# ── Файлы пайплайна ─────────────────────────────────────
Write-Host "`n[3/3] Удаление временных файлов..." -ForegroundColor White

$filesToClean = @(
    "data\connect-output\products-filtered.jsonl"
)
foreach ($file in $filesToClean) {
    $path = Join-Path $projectRoot $file
    if (Test-Path $path) {
        Remove-Item -Force $path
        Write-Host "  [OK] $file" -ForegroundColor Green
    }
}

Pop-Location
Write-Host "`n=== Очистка завершена ===" -ForegroundColor Green
