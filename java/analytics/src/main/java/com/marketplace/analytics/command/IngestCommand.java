package com.marketplace.analytics.command;

import com.marketplace.analytics.hdfs.WebHdfsClient;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;

/** Читает shop-products и client-queries из локального Kafka → записывает в HDFS. */
@Component
public class IngestCommand {

    private static final Logger log = LoggerFactory.getLogger(IngestCommand.class);

    private final WebHdfsClient hdfs;

    @Value("${kafka.local.bootstrap-servers}")
    private String bootstrapServers;
    @Value("${kafka.local.group-id}")
    private String groupId;
    @Value("${topics.shop-products}")
    private String shopProductsTopic;
    @Value("${topics.client-queries}")
    private String clientQueriesTopic;
    @Value("${ingest.poll-timeout-seconds}")
    private int pollTimeoutSeconds;

    public IngestCommand(WebHdfsClient hdfs) {
        this.hdfs = hdfs;
    }

    public void run() throws Exception {
        log.info("=== INGEST: Kafka (local) → HDFS ===");

        Map<String, List<String>> collected = new HashMap<>();
        collected.put(shopProductsTopic, new ArrayList<>());
        collected.put(clientQueriesTopic, new ArrayList<>());

        try (KafkaConsumer<String, String> consumer = createConsumer()) {
            List<String> topics = List.of(shopProductsTopic, clientQueriesTopic);
            consumer.subscribe(topics);

            // Ждём назначения партиций
            consumer.poll(Duration.ofSeconds(5));
            Set<TopicPartition> partitions = consumer.assignment();
            if (partitions.isEmpty()) {
                log.warn("No partitions assigned — topics empty or unavailable");
                return;
            }

            // Получаем end offsets и читаем от начала
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> endOffsets = consumer.endOffsets(partitions);

            log.info("Reading from {} partitions...", partitions.size());
            long deadline = System.currentTimeMillis() + pollTimeoutSeconds * 1000L;

            while (System.currentTimeMillis() < deadline && !reachedEnd(consumer, endOffsets)) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(2));
                for (ConsumerRecord<String, String> record : records) {
                    collected.get(record.topic()).add(record.value());
                }
            }
        }

        // Пишем в HDFS
        for (Map.Entry<String, List<String>> entry : collected.entrySet()) {
            String topic = entry.getKey();
            List<String> records = entry.getValue();
            if (records.isEmpty()) {
                log.warn("No data for topic: {}", topic);
                continue;
            }
            String path = "/data/" + topic;
            hdfs.mkdirs(path);
            String content = String.join("\n", records);
            hdfs.write(path + "/part-000.json", content);
            log.info("[OK] {} → HDFS {} ({} records)", topic, path, records.size());
        }
        log.info("=== INGEST complete ===");
    }

    private boolean reachedEnd(KafkaConsumer<String, String> consumer,
                               Map<TopicPartition, Long> endOffsets) {
        for (Map.Entry<TopicPartition, Long> entry : endOffsets.entrySet()) {
            long current = consumer.position(entry.getKey());
            if (current < entry.getValue()) return false;
        }
        return true;
    }

    private KafkaConsumer<String, String> createConsumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId + "-" + System.currentTimeMillis());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        return new KafkaConsumer<>(props);
    }
}
