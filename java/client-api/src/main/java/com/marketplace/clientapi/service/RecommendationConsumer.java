package com.marketplace.clientapi.service;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Читает персонализированные рекомендации из топика recommendations.
 * Создаёт временный consumer, ищет последние 100 сообщений на каждой партиции,
 * фильтрует по userId. Таймаут задаётся через poll-seconds.
 */
@Service
public class RecommendationConsumer {

    private static final Logger log = LoggerFactory.getLogger(RecommendationConsumer.class);

    private final ConsumerFactory<String, String> consumerFactory;

    @Value("${client.recommendations.topic:recommendations}")
    private String topic;

    @Value("${client.recommendations.poll-seconds:5}")
    private int pollSeconds;

    public RecommendationConsumer(ConsumerFactory<String, String> consumerFactory) {
        this.consumerFactory = consumerFactory;
    }

    public List<String> fetchForUser(String userId) {
        List<String> result = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer =
                 (KafkaConsumer<String, String>) consumerFactory.createConsumer("client-api-" + System.currentTimeMillis(), "")) {

            List<TopicPartition> partitions = consumer.partitionsFor(topic).stream()
                .map(partitionInfo -> new TopicPartition(topic, partitionInfo.partition()))
                .toList();
            consumer.assign(partitions);

            Map<TopicPartition, Long> endOffsets = consumer.endOffsets(partitions);
            Map<TopicPartition, Long> beginOffsets = consumer.beginningOffsets(partitions);

            // Читаем последние 100 сообщений из каждой партиции.
            for (TopicPartition partition : partitions) {
                long end = endOffsets.getOrDefault(partition, 0L);
                long begin = beginOffsets.getOrDefault(partition, 0L);
                long seekTo = Math.max(begin, end - 100);
                consumer.seek(partition, seekTo);
            }

            long deadline = System.currentTimeMillis() + Duration.ofSeconds(pollSeconds).toMillis();
            while (System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    if (userId.equals(record.key()) || record.value().contains(userId)) {
                        result.add(record.value());
                    }
                }
                if (!records.isEmpty() && !result.isEmpty()) break;
            }
        } catch (Exception e) {
            log.warn("Не удалось получить рекомендации из Kafka: {}.", e.getMessage());
        }
        return result;
    }
}
