package com.marketplace.shopapi.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketplace.shopapi.model.Product;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/** Отправляет товары в Kafka-топик shop-products (JSON, key = product_id). */
@Service
public class ShopProducer {

    private static final Logger log = LoggerFactory.getLogger(ShopProducer.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${shop.topic:shop-products}")
    private String topic;

    public ShopProducer(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void send(Product product) {
        try {
            String json = objectMapper.writeValueAsString(product);
            kafkaTemplate.send(topic, product.productId(), json)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Ошибка отправки {}: {}.", product.productId(), ex.getMessage(), ex);
                    } else {
                        log.info("Отправлен: {} → partition={} offset={}.",
                            product.productId(),
                            result.getRecordMetadata().partition(),
                            result.getRecordMetadata().offset());
                    }
                });
        } catch (JsonProcessingException e) {
            throw new RuntimeException(
                "Ошибка сериализации товара: " + product.productId(), e);
        }
    }

    public void flush() {
        kafkaTemplate.flush();
    }
}
