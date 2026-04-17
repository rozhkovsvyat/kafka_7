package com.marketplace.analytics.command;

import com.marketplace.analytics.hdfs.WebHdfsClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Properties;

/** Читает рекомендации из HDFS (результат Spark) → публикует в топик recommendations (YC Kafka). */
@Component
public class ProduceCommand {

    private static final Logger log = LoggerFactory.getLogger(ProduceCommand.class);

    private final WebHdfsClient hdfs;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${kafka.yc.bootstrap-servers}")
    private String bootstrapServers;
    @Value("${kafka.yc.security-protocol}")
    private String securityProtocol;
    @Value("${kafka.yc.sasl-mechanism}")
    private String saslMechanism;
    @Value("${kafka.yc.sasl-jaas-config}")
    private String saslJaasConfig;
    @Value("${kafka.yc.ssl-truststore-location}")
    private String truststoreLocation;
    @Value("${kafka.yc.ssl-truststore-password}")
    private String truststorePassword;
    @Value("${topics.recommendations}")
    private String recommendationsTopic;

    public ProduceCommand(WebHdfsClient hdfs) {
        this.hdfs = hdfs;
    }

    public void run() throws Exception {
        log.info("=== PRODUCE: HDFS → Kafka YC (recommendations) ===");

        String outputPath = "/output/recommendations";
        List<String> files;
        try {
            files = hdfs.listFileNames(outputPath);
        } catch (Exception e) {
            log.warn("No recommendations found in HDFS: {}", e.getMessage());
            return;
        }

        if (files.isEmpty()) {
            log.warn("No part files in {}", outputPath);
            return;
        }

        int sent = 0;
        try (KafkaProducer<String, String> producer = createProducer()) {
            for (String filePath : files) {
                String content = hdfs.read(filePath);
                for (String line : content.split("\n")) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    // Извлекаем userId как ключ (если есть)
                    String key = extractField(line, "userId");
                    producer.send(new ProducerRecord<>(recommendationsTopic, key, line));
                    sent++;
                }
            }
            producer.flush();
        }
        log.info("[OK] Sent {} recommendations to {}", sent, recommendationsTopic);
        log.info("=== PRODUCE complete ===");
    }

    private String extractField(String json, String field) {
        try {
            JsonNode node = mapper.readTree(json);
            JsonNode value = node.path(field);
            return value.isMissingNode() ? null : value.asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    private KafkaProducer<String, String> createProducer() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put("security.protocol", securityProtocol);
        props.put("sasl.mechanism", saslMechanism);
        props.put("sasl.jaas.config", saslJaasConfig);
        props.put("ssl.truststore.location", truststoreLocation);
        props.put("ssl.truststore.password", truststorePassword);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        return new KafkaProducer<>(props);
    }
}
