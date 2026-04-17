package com.marketplace.clientapi.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Полнотекстовый поиск товаров по name/category/brand в локальном JSON-файле. */
@Service
public class ProductSearchService {

    private static final Logger log = LoggerFactory.getLogger(ProductSearchService.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${client.products.file:data/products.json}")
    private String productsFile;

    public List<JsonNode> search(String term) {
        File file = new File(productsFile);
        if (!file.exists()) {
            log.warn("Файл товаров не найден: {}. Поиск невозможен.", file.getAbsolutePath());
            return List.of();
        }
        try {
            List<JsonNode> all = objectMapper.readValue(file, new TypeReference<List<JsonNode>>() {});
            List<JsonNode> result = new ArrayList<>();
            String lowerTerm = term.toLowerCase();
            for (JsonNode node : all) {
                String name = node.path("name").asText("").toLowerCase();
                String category = node.path("category").asText("").toLowerCase();
                String brand = node.path("brand").asText("").toLowerCase();
                if (name.contains(lowerTerm) || category.contains(lowerTerm) || brand.contains(lowerTerm)) {
                    result.add(node);
                }
            }
            return result;
        } catch (IOException e) {
            log.error("Ошибка чтения файла товаров: {}.", e.getMessage());
            return List.of();
        }
    }
}
