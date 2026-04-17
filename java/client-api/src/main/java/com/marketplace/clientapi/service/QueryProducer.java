package com.marketplace.clientapi.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketplace.clientapi.model.ClientQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/** Отправляет запросы клиентов в Kafka-топик client-queries для аналитики. */
@Service
public class QueryProducer {

    private static final Logger log = LoggerFactory.getLogger(QueryProducer.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${client.topic:client-queries}")
    private String topic;

    public QueryProducer(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void send(ClientQuery query) {
        try {
            String json = objectMapper.writeValueAsString(query);
            kafkaTemplate.send(topic, query.userId(), json)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Ошибка отправки запроса {}: {}.", query.queryId(), ex.getMessage());
                    } else {
                        log.info("Запрос отправлен: queryId={} partition={} offset={}.",
                            query.queryId(),
                            result.getRecordMetadata().partition(),
                            result.getRecordMetadata().offset());
                    }
                });
            kafkaTemplate.flush();
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Ошибка сериализации запроса: " + query.queryId(), e);
        }
    }
}
