"""
Аналитика маркетплейса: HDFS → Apache Spark → рекомендации → HDFS
Читает данные из HDFS через WebHDFS REST API, обрабатывает Spark,
записывает рекомендации обратно в HDFS.

Запуск: python analytics.py
Зависимости: pip install pyspark requests
"""
import json
import os
import sys
import tempfile
from urllib.parse import urlparse, urlunparse

# Переопределяем SPARK_HOME на директорию pyspark-пакета,
# иначе системная переменная может указывать на несуществующий путь
import pyspark as _pyspark
os.environ.setdefault("SPARK_HOME", _pyspark.__path__[0])
# Windows: Python-воркеры запускаются как "python3", которого нет → указываем текущий интерпретатор
os.environ.setdefault("PYSPARK_PYTHON", sys.executable)
os.environ.setdefault("PYSPARK_DRIVER_PYTHON", sys.executable)

# Java 9+ (включая Java 25) требует явного открытия модулей для PySpark
_jvm_opens = " ".join([
    "--add-opens=java.base/java.lang=ALL-UNNAMED",
    "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED",
    "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
    "--add-opens=java.base/java.io=ALL-UNNAMED",
    "--add-opens=java.base/java.net=ALL-UNNAMED",
    "--add-opens=java.base/java.nio=ALL-UNNAMED",
    "--add-opens=java.base/java.util=ALL-UNNAMED",
    "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED",
    "--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED",
    "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
    "--add-opens=java.base/sun.nio.cs=ALL-UNNAMED",
    "--add-opens=java.base/sun.security.action=ALL-UNNAMED",
    "--add-opens=java.base/sun.util.calendar=ALL-UNNAMED",
    "-Dio.netty.tryReflectionSetAccessible=true",
])
os.environ.setdefault("JAVA_TOOL_OPTIONS", _jvm_opens)

import requests
from pyspark.sql import SparkSession
from pyspark.sql.functions import col, desc, row_number
from pyspark.sql.window import Window

NAMENODE = os.environ.get("HDFS_WEBHDFS_URL", "http://localhost:9870")
WEBHDFS  = NAMENODE + "/webhdfs/v1"


# ── WebHDFS helpers ────────────────────────────────────────────

_inside_docker = os.path.exists("/.dockerenv")

def _fix_redirect(url: str) -> str:
    """Внутри Docker редирект на datanode работает напрямую.
    На хосте заменяем Docker-hostname на localhost (порт проброшен)."""
    if _inside_docker:
        return url
    p = urlparse(url)
    return urlunparse(p._replace(netloc=f"localhost:{p.port}"))


def hdfs_download_dir(hdfs_path: str, local_dir: str) -> list[str]:
    """Скачивает все файлы из директории HDFS в local_dir."""
    r = requests.get(f"{WEBHDFS}{hdfs_path}?op=LISTSTATUS")
    if r.status_code == 404:
        return []
    r.raise_for_status()
    statuses = r.json()["FileStatuses"]["FileStatus"]
    local_files = []
    for s in statuses:
        if s["type"] != "FILE":
            continue
        file_path = f"{hdfs_path}/{s['pathSuffix']}"
        r2 = requests.get(f"{WEBHDFS}{file_path}?op=OPEN", allow_redirects=False)
        if r2.status_code != 307:
            continue
        data = requests.get(_fix_redirect(r2.headers["Location"]))
        data.raise_for_status()
        local_path = os.path.join(local_dir, s["pathSuffix"])
        with open(local_path, "w", encoding="utf-8") as f:
            f.write(data.text)
        local_files.append(local_path)
    return local_files


def hdfs_mkdirs(path: str) -> None:
    requests.put(f"{WEBHDFS}{path}?op=MKDIRS").raise_for_status()


def hdfs_write(hdfs_path: str, content: str) -> None:
    r = requests.put(
        f"{WEBHDFS}{hdfs_path}?op=CREATE&overwrite=true",
        allow_redirects=False
    )
    if r.status_code != 307:
        raise RuntimeError(f"Expected 307, got {r.status_code}: {r.text}")
    dn_url = _fix_redirect(r.headers["Location"])
    r2 = requests.put(dn_url, data=content.encode("utf-8"),
                      headers={"Content-Type": "application/octet-stream"})
    r2.raise_for_status()


# ── Main ───────────────────────────────────────────────────────

print("=== Marketplace Analytics: Spark job started ===")

spark = SparkSession.builder \
    .appName("MarketplaceAnalytics") \
    .master("local[2]") \
    .config("spark.sql.shuffle.partitions", "4") \
    .config("spark.driver.memory", "512m") \
    .getOrCreate()
spark.sparkContext.setLogLevel("WARN")

with tempfile.TemporaryDirectory() as tmp:
    products_dir = os.path.join(tmp, "shop-products")
    queries_dir  = os.path.join(tmp, "client-queries")
    os.makedirs(products_dir)
    os.makedirs(queries_dir)

    print("Downloading data from HDFS...")
    p_files = hdfs_download_dir("/data/shop-products", products_dir)
    q_files = hdfs_download_dir("/data/client-queries",  queries_dir)

    if not p_files or not q_files:
        print("No data in HDFS — run ingest first")
        spark.stop()
        sys.exit(0)

    # spark.read.json() вызывает Hadoop FileSystem → Subject.getSubject() → падает на Java 24+
    # Читаем файлы в Python, создаём DataFrame через createDataFrame (без Hadoop FS)
    def _load_json_dir(directory: str) -> list:
        rows = []
        for fname in sorted(os.listdir(directory)):
            fpath = os.path.join(directory, fname)
            if not os.path.isfile(fpath):
                continue
            with open(fpath, encoding="utf-8") as f:
                for line in f:
                    line = line.strip()
                    if line:
                        try:
                            rows.append(json.loads(line))
                        except json.JSONDecodeError:
                            pass
        return rows

    products_rows = _load_json_dir(products_dir)
    queries_rows  = _load_json_dir(queries_dir)
    if not products_rows or not queries_rows:
        print("No data in HDFS — run ingest first")
        spark.stop()
        sys.exit(0)
    products_df = spark.createDataFrame(products_rows)
    queries_df  = spark.createDataFrame(queries_rows)

    products_df.createOrReplaceTempView("products")
    queries_df.createOrReplaceTempView("queries")

    print(f"Products loaded: {products_df.count()}")
    print(f"Queries loaded:  {queries_df.count()}")

    # Рекомендации: JOIN по совпадению запроса с названием/категорией товара
    recs = spark.sql("""
        SELECT
            q.user_id         AS userId,
            p.product_id      AS productId,
            p.name            AS productName,
            p.price.amount    AS price,
            p.category
        FROM queries q
        JOIN products p
          ON lower(p.name)     LIKE concat('%', lower(q.query_text), '%')
          OR lower(p.category) LIKE concat('%', lower(q.query_text), '%')
        WHERE q.user_id    IS NOT NULL
          AND p.product_id IS NOT NULL
    """)

    # Топ-3 товара на пользователя по убыванию цены
    window = Window.partitionBy("userId").orderBy(desc("price"))
    recs_top3 = (
        recs
        .dropDuplicates(["userId", "productId"])
        .withColumn("rn", row_number().over(window))
        .filter(col("rn") <= 3)
        .drop("rn")
    )

    count = recs_top3.count()
    print(f"Recommendations generated: {count}")
    recs_top3.show(10, truncate=False)

    if count > 0:
        # Собираем результаты и пишем в HDFS через WebHDFS
        rows = recs_top3.toJSON().collect()
        hdfs_mkdirs("/output/recommendations")
        hdfs_write("/output/recommendations/part-000.json", "\n".join(rows))
        print("Results written to HDFS /output/recommendations/")

spark.stop()
print("=== Analytics complete ===")
