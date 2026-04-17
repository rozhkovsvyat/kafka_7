FROM python:3.11-slim
RUN apt-get update \
    && apt-get install -y --no-install-recommends default-jre-headless \
    && rm -rf /var/lib/apt/lists/*
RUN pip install --no-cache-dir pyspark==3.5.4 requests==2.31.0
WORKDIR /scripts
CMD ["tail", "-f", "/dev/null"]
