package com.marketplace.shopapi.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketplace.shopapi.model.Product;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.List;

/** Читает массив товаров из JSON-файла (путь задаётся через SHOP_PRODUCTS_FILE). */
@Service
public class ProductFileReader {

    private static final Logger log = LoggerFactory.getLogger(ProductFileReader.class);

    @Value("${shop.products.file}")
    private String productsFilePath;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<Product> readProducts() throws IOException {
        File file = new File(productsFilePath);
        if (!file.exists()) {
            throw new IllegalStateException(
                "Файл с товарами не найден: " + file.getAbsolutePath() +
                ". Убедитесь, что SHOP_PRODUCTS_FILE задан корректно."
            );
        }
        List<Product> products = objectMapper.readValue(file, new TypeReference<List<Product>>() {});
        log.info("Прочитано товаров из {}: {}.", file.getName(), products.size());
        return products;
    }
}
