# Аналитическая платформа для маркетплейса «Покупай выгодно»

Финальный проект курса **Apache Kafka для разработки и архитектуры** (Яндекс Практикум).

Платформа собирает данные о товарах и поведении клиентов, обрабатывает их через Kafka Streams,
реплицирует на второй кластер, анализирует в HDFS + Spark и формирует персонализированные
рекомендации. Результат пишется обратно в Kafka и через Kafka Connect сохраняется в файл.

---

## Содержание

- [Архитектура](#архитектура)
- [Стек технологий](#стек-технологий)
- [Требования](#требования)
- [Быстрый старт](#быстрый-старт)
- [Сервисы и порты](#сервисы-и-порты)
- [Топики Kafka (YC)](#топики-kafka-yc)
- [Описание компонентов](#описание-компонентов)
- [Структура проекта](#структура-проекта)
- [Формат данных](#формат-данных)

---

## Архитектура

```
┌─────────────────────────────────────────────────────────────────────┐
│                      Yandex Cloud Kafka (YC)                        │
│  shop-products  products-filtered  client-queries  recommendations  │
└──┬─────────────────┬─────────────────┬──────────────────┬───────────┘
   │                 │                 │                  │
   ▼                 ▼                 ▼                  ▼
 SHOP API      STREAMS FILTER     CLIENT API        KAFKA CONNECT
(Spring Boot)  (Kafka Streams)    (Spring Boot)     (→ .jsonl файл)
 producer      shop → filtered    search/recommend
   │                 │
   │        ┌────────┴────────┐
   │        │  MirrorMaker 2  │
   │        └────────┬────────┘
   │                 │
   ▼                 ▼
┌─────────────────────────────────────────────────────────────┐
│                 kafka-local (Docker, KRaft)                 │
│               shop-products    client-queries               │
└───────────────────────┬─────────────────────────────────────┘
                        │
                   Analytics (Java)
                        │
                        ▼
             ┌─────────────────────┐
             │    HDFS (Docker)    │
             │ namenode / datanode │
             └──────────┬──────────┘
                        │
             ┌─────────────────────┐
             │    Spark (Docker)   │
             │    PySpark 3.5.4    │
             └──────────┬──────────┘
                        │
                        ▼
            recommendations → YC Kafka
                        │
                        ▼
            CLIENT API → recommend user123

┌──────────────────────────────────────────────────────────────────┐
│                      Мониторинг (Docker)                         │
│  kafka-local JMX → jmx-exporter → Prometheus ─→ Grafana          │
│                                               └→ Alertmanager    │
│                                                     └→ Telegram  │
└──────────────────────────────────────────────────────────────────┘
```

---

## Стек технологий

| Слой | Технология |
|------|-----------|
| Брокер (production) | Yandex Managed Service for Apache Kafka 3.9.1, 3 брокера, SASL_SSL |
| Брокер (local mirror) | `confluentinc/cp-kafka:7.6.0`, KRaft |
| Репликация | MirrorMaker 2 (`IdentityReplicationPolicy`) |
| Потоковая обработка | Kafka Streams 3.9.1 |
| ETL хранилище | Kafka Connect 7.6.0, `FileStreamSinkConnector` |
| Data Lake | Apache HDFS 3.2.1 (Hadoop, Docker) |
| Аналитика | PySpark 3.5.4 + Python 3.11 (Docker) |
| Приложения | Java 17, Spring Boot 3.4.4, kafka-clients 3.9.1 |
| Сборка | Maven 3.8+ |
| Мониторинг | Prometheus, Grafana, Alertmanager, JMX Exporter |
| Уведомления | Telegram Bot API |
| UI | Kafka UI (provectuslabs) |
| Скрипты | PowerShell 5.1+ |

---

## Требования

Перед запуском на новой машине убедитесь, что установлено:

| Инструмент | Версия | Для чего |
|-----------|--------|---------|
| Java (JDK) | 17+ | Сборка и запуск Spring Boot приложений |
| Maven | 3.8+ | Сборка JAR |
| Docker Desktop | последняя | Вся локальная инфраструктура |
| PowerShell 7 (`pwsh`) | 7.x | Запуск скриптов (UTF-8 кириллица) |
| Yandex Cloud CLI (`yc`) | последняя | Создание топиков и пользователей в YC Kafka |

### Установка yc CLI (один раз)

```powershell
iex (New-Object System.Net.WebClient).DownloadString('https://storage.yandexcloud.net/yandexcloud-yc/install.ps1')
yc init
```

### TLS-сертификат для YC Kafka

```powershell
mkdir -Force "$env:USERPROFILE\.kafka"
curl -o "$env:USERPROFILE\.kafka\CA.pem" https://storage.yandexcloud.net/cloud-certs/CA.pem
keytool -importcert -alias YandexCA -file "$env:USERPROFILE\.kafka\CA.pem" `
  -keystore "$env:USERPROFILE\.kafka\client.truststore.jks" `
  -storepass <STORE_PASS> -noprompt
```

---

## Быстрый старт

Все команды выполняются из корня проекта через **PowerShell 7** (`pwsh`).
Скрипты содержат кириллицу в UTF-8, поэтому PowerShell 5.1 (`powershell`) не подходит.

### Шаг 0 — Настройка YC Kafka (обязательно перед docker compose!)

> **Важно:** без этого шага Kafka Connect не запустится — ему нужны топики
> `connect-configs`, `connect-offsets`, `connect-status` и пользователь с ACL.

```powershell
# 1. Создайте .env из шаблона и заполните своими данными
copy .env.example .env
notepad .env

# 2. Создайте топики в YC Kafka
pwsh .\scripts\01-create-topics.ps1

# 3. Создайте сервисных пользователей и настройте ACL
pwsh .\scripts\02-setup-acl.ps1
```

Скрипт `02-setup-acl.ps1` **идемпотентный**: повторный запуск безопасен —
если пользователь уже существует, скрипт автоматически дополнит недостающие права.

### Шаг 1 — Запуск инфраструктуры

```powershell
docker compose up -d
```

Дождитесь, пока все контейнеры перейдут в статус `healthy` (30–60 секунд):

```powershell
docker compose ps
```

### Шаг 2 — Отправка товаров в Kafka

```powershell
pwsh .\scripts\03-run-shop-api.ps1
```

Читает `data/products.json` (12 товаров) и публикует в топик `shop-products`.

### Шаг 3 — Запуск фильтра запрещённых товаров

В **отдельном терминале**:

```powershell
pwsh .\scripts\04-run-streams-filter.ps1
```

Kafka Streams фильтрует `shop-products → products-filtered`.
Управление списком запрещённых прямо в терминале:

```
add P001          # запретить товар по ID или имени
remove P001       # разрешить обратно
list              # посмотреть список
stop              # остановить
```

### Шаг 4 — Подключение Kafka Connect

```powershell
pwsh .\scripts\05-setup-connect.ps1
```

Регистрирует `FileStreamSinkConnector`: записывает `products-filtered` в файл
`data/connect-output/products-filtered.jsonl`.

### Шаг 5 — Запросы клиентов

```powershell
# Поиск товара
pwsh .\scripts\06-run-client-api.ps1 search "Умные часы" user123

# Запрос рекомендаций (после шага 6)
pwsh .\scripts\06-run-client-api.ps1 recommend user123
```

### Шаг 6 — Аналитика и рекомендации

```powershell
pwsh .\scripts\07-run-analytics.ps1
```

Запускает пайплайн из трёх шагов:
1. **Ingest** — читает `shop-products` и `client-queries` из `kafka-local` → HDFS
2. **Spark** — JOIN товаров × запросов, топ-3 по цене → `/output/recommendations/`
3. **Produce** — HDFS → топик `recommendations` в YC Kafka

### Шаг 7 — Очистка

```powershell
pwsh .\scripts\08-cleanup.ps1
```

Останавливает все Docker-контейнеры (с удалением volumes), удаляет Maven-артефакты
(`target/`) и временные файлы пайплайна.

---

## Сервисы и порты

| Сервис | Порт | URL | Описание |
|--------|------|-----|---------|
| Kafka UI | 8080 | http://localhost:8080 | Просмотр топиков YC + local кластеров |
| Kafka Connect | 8083 | http://localhost:8083 | REST API коннекторов |
| HDFS NameNode UI | 9870 | http://localhost:9870 | Веб-интерфейс HDFS |
| kafka-local | 29092 | http://localhost:29092 | Локальный Kafka брокер |
| Prometheus | 9090 | http://localhost:9090 | Метрики и алерты |
| Grafana | 3000 | http://localhost:3000 | Дашборд мониторинга (admin/admin) |
| Alertmanager | 9093 | http://localhost:9093 | Управление алертами |
| JMX Exporter | 5556 | http://localhost:5556/metrics | Kafka JMX метрики |

---

## Топики Kafka (YC)

| Топик | Партиции | RF | min.insync | Retention | Назначение |
|-------|----------|----|-----------|-----------|-----------|
| `shop-products` | 3 | 3 | 2 | 7 дней | Товары от магазинов |
| `products-filtered` | 3 | 3 | 2 | 7 дней | После Kafka Streams фильтрации |
| `client-queries` | 3 | 3 | 2 | 1 день | Запросы и действия клиентов |
| `recommendations` | 3 | 3 | 2 | 1 день | Персонализированные рекомендации |

### Обоснование параметров топиков

**partitions = 3** — совпадает с количеством брокеров в YC-кластере, что даёт равномерное распределение нагрузки и позволяет запустить до 3 параллельных consumer'ов.

**replication-factor = 3** — каждая партиция хранится на всех трёх брокерах. Кластер выдержит отказ любого одного брокера без потери данных.

**min.insync.replicas = 2** — Producer с `acks=all` ждёт подтверждения от 2 из 3 реплик. Баланс: один брокер может лагать или падать, но запись всё равно успешна. При значении 3 любой упавший брокер блокировал бы запись.

**retention.ms:**
- `shop-products`, `products-filtered` → **7 дней** — товарные данные актуальны неделю; Kafka Connect и MirrorMaker успеют прочитать даже при задержке
- `client-queries`, `recommendations` → **1 день** — запросы и рекомендации устаревают быстро; экономим место

**compression-type:**
- `shop-products`, `products-filtered` → **lz4** — JSON товаров хорошо сжимается (50–70%), lz4 быстрее gzip/zstd при сравнимой степени сжатия
- `client-queries`, `recommendations` → **uncompressed** — маленькие сообщения; накладные расходы на сжатие превысят выгоду

---

## Описание компонентов

### SHOP API (`shop-api/`)

Spring Boot `CommandLineRunner`. Читает `data/products.json` и публикует все товары
в топик `shop-products`. Использует SASL_SSL + SCRAM-SHA-512.

Формат сообщения:
```json
{
  "product_id": "P001",
  "name": "Умные часы XYZ Pro",
  "price": { "amount": 14999.99, "currency": "RUB" },
  "category": "Электроника",
  "store_id": "store_001"
}
```

### CLIENT API (`client-api/`)

Spring Boot `CommandLineRunner`. Два режима:
- `search <запрос> <userId>` — ищет товары в `data/products.json`, пишет событие `SEARCH` в `client-queries`
- `recommend <userId>` — пишет событие `RECOMMEND` в `client-queries`, читает ответ из `recommendations`

### Kafka Streams Filter (`streams-filter/`)

Spring Boot + Kafka Streams. Топология: `shop-products` → фильтр → `products-filtered`.

Фильтрует по `product_id` и `name` (без учёта регистра) против in-memory списка запрещённых.
Список хранится в `banned.txt` и обновляется без рестарта через CLI в терминале.

### Kafka Connect

`FileStreamSinkConnector` читает `products-filtered` и пишет каждое сообщение
в `data/connect-output/products-filtered.jsonl` (по одной JSON строке).

### Analytics (`analytics/`)

Java-приложение с двумя командами:
- `ingest` — читает `shop-products` и `client-queries` из `kafka-local`, загружает в HDFS через WebHDFS REST API
- `produce` — читает результаты из HDFS, публикует в топик `recommendations` в YC Kafka

### Spark (`docker/spark/analytics.py`)

PySpark 3.5.4 в Docker. Запускается внутри контейнера `spark`:
- JOIN: продукты × запросы по совпадению категории/имени
- GROUP BY `user_id` → топ-3 товара по убыванию цены
- Результат сохраняется в HDFS `/output/recommendations/`

### MirrorMaker 2

Реплицирует `shop-products` и `client-queries` из YC Kafka в `kafka-local`.
Использует `IdentityReplicationPolicy` — имена топиков не меняются (без префикса `yc.`).

### Мониторинг

| Компонент | Роль |
|-----------|------|
| JMX Exporter | Экспортирует метрики `kafka-local` (UnderReplicatedPartitions, MessageRate, BytesIn/Out и др.) |
| Prometheus | Собирает метрики каждые 15 сек, хранит 7 дней |
| Grafana | Дашборд «Kafka Overview» (8 панелей: статус, партиции, пропускная способность) |
| Alertmanager | 4 алерта: `KafkaBrokerDown`, `KafkaUnderReplicatedPartitions`, `KafkaOfflinePartitions`, `KafkaNoActiveController` |
| Telegram | Уведомления при срабатывании алертов (бот-токен и chat_id в `.env`) |

---

## Структура проекта

```
├── .env.example              # Шаблон переменных окружения
├── .gitignore
├── README.md                 # Документация проекта (этот файл)
├── docker-compose.yml        # Вся локальная инфраструктура
├── pom.xml                   # Родительский Maven POM
│
├── java/
│   ├── shop-api/             # Spring Boot: отправка товаров в Kafka
│   ├── client-api/           # Spring Boot: запросы и рекомендации клиентов
│   ├── streams-filter/       # Spring Boot + Kafka Streams: фильтр запрещённых
│   └── analytics/            # Java: Ingest (Kafka→HDFS) и Produce (HDFS→Kafka)
│
├── data/
│   └── products.json         # 12 товаров маркетплейса
│
├── scripts/
│   ├── 01-create-topics.ps1      # Создание топиков через yc CLI
│   ├── 02-setup-acl.ps1          # Пользователи и ACL через yc CLI
│   ├── 03-run-shop-api.ps1       # Запуск SHOP API
│   ├── 04-run-streams-filter.ps1 # Запуск Kafka Streams фильтра
│   ├── 05-setup-connect.ps1      # Регистрация Kafka Connect коннектора
│   ├── 06-run-client-api.ps1     # Запуск CLIENT API
│   ├── 07-run-analytics.ps1      # Запуск аналитического пайплайна
│   └── 08-cleanup.ps1            # Очистка: docker down + удаление артефактов
│
└── docker/
    ├── hadoop/               # Конфигурация HDFS
    ├── kafka-connect/        # connect-file-3.7.0.jar (FileStreamSink)
    ├── mirrormaker2/         # Конфигурация MirrorMaker 2
    ├── spark/                # Dockerfile + analytics.py (PySpark)
    └── monitoring/
        ├── prometheus/       # prometheus.yml + alert-rules.yml
        ├── grafana/          # datasource.yml + dashboards.yml + kafka-dashboard.json
        ├── alertmanager/     # alertmanager.yml (с Telegram, env-substitution)
        └── jmx-exporter/     # jmx-kafka.yml
```

---

## Формат данных

### Товар (`shop-products`)

```json
{
  "product_id": "P001",
  "name": "Умные часы XYZ Pro",
  "description": "...",
  "price": { "amount": 14999.99, "currency": "RUB" },
  "category": "Электроника",
  "brand": "XYZ",
  "stock": { "available": 150, "reserved": 20 },
  "sku": "XYZ-P001",
  "store_id": "store_001"
}
```

### Запрос клиента (`client-queries`)

```json
{
  "query_id": "uuid",
  "user_id": "user123",
  "type": "SEARCH",
  "query_text": "Умные часы",
  "timestamp": "2026-04-17T10:00:00Z"
}
```

### Рекомендация (`recommendations`)

```json
{
  "userId": "user123",
  "productId": "P001",
  "productName": "Умные часы XYZ Pro",
  "price": 14999.99,
  "reason": "Matches your search query"
}
```
