package com.marketplace.clientapi;

import com.marketplace.clientapi.model.ClientQuery;
import com.marketplace.clientapi.model.QueryType;
import com.marketplace.clientapi.service.ProductSearchService;
import com.marketplace.clientapi.service.QueryProducer;
import com.marketplace.clientapi.service.QueryStorage;
import com.marketplace.clientapi.service.RecommendationConsumer;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@SpringBootApplication
public class ClientApiApplication implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(ClientApiApplication.class);

    private final QueryProducer producer;
    private final QueryStorage storage;
    private final ProductSearchService searchService;
    private final RecommendationConsumer recommendationConsumer;

    public ClientApiApplication(QueryProducer producer, QueryStorage storage,
                                 ProductSearchService searchService,
                                 RecommendationConsumer recommendationConsumer) {
        this.producer = producer;
        this.storage = storage;
        this.searchService = searchService;
        this.recommendationConsumer = recommendationConsumer;
    }

    public static void main(String[] args) {
        SpringApplication.run(ClientApiApplication.class, args);
    }

    @Override
    public void run(String... args) {
        if (args.length < 2) {
            printUsage();
            return;
        }

        String command = args[0];
        String argument = args[1];
        String userId = args.length > 2 ? args[2] : "anonymous";

        switch (command.toLowerCase()) {
            case "search" -> handleSearch(argument, userId);
            case "recommend" -> handleRecommend(argument);
            default -> {
                log.error("Неизвестная команда: {}.", command);
                printUsage();
            }
        }
    }

    private void handleSearch(String term, String userId) {
        log.info("=== Поиск товаров: \"{}\" ===", term);

        ClientQuery query = new ClientQuery(
            UUID.randomUUID().toString(),
            userId,
            QueryType.SEARCH,
            term,
            Instant.now().toString()
        );
        producer.send(query);
        storage.save(query);

        List<JsonNode> results = searchService.search(term);
        if (results.isEmpty()) {
            System.out.println("Товары не найдены по запросу: \"" + term + "\"");
        } else {
            System.out.printf("Найдено товаров: %d%n%n", results.size());
            results.forEach(product -> System.out.printf(
                "  [%s] %s — %.2f %s (в наличии: %d)%n",
                product.path("product_id").asText(),
                product.path("name").asText(),
                product.path("price").path("amount").asDouble(),
                product.path("price").path("currency").asText("RUB"),
                product.path("stock").path("available").asInt()
            ));
        }
    }

    private void handleRecommend(String userId) {
        log.info("=== Получение рекомендаций для пользователя: {} ===", userId);

        ClientQuery query = new ClientQuery(
            UUID.randomUUID().toString(),
            userId,
            QueryType.RECOMMEND,
            userId,
            Instant.now().toString()
        );
        producer.send(query);
        storage.save(query);

        System.out.println("Запрос рекомендаций отправлен. Ожидание ответа...");
        List<String> recommendations = recommendationConsumer.fetchForUser(userId);
        if (recommendations.isEmpty()) {
            System.out.println("Рекомендации пока недоступны. Попробуйте позже.");
        } else {
            System.out.printf("Рекомендации для %s:%n", userId);
            recommendations.forEach(recommendation -> System.out.println("  " + recommendation));
        }
    }

    private void printUsage() {
        System.out.println("""
            CLIENT API — Аналитическая платформа маркетплейса

            Использование:
              search <запрос> [userId]   — поиск товара по названию
              recommend <userId>         — получить рекомендации

            Примеры:
              search "Умные часы" user123
              recommend user123
            """);
    }
}
