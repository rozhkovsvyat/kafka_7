package com.marketplace.shopapi;

import com.marketplace.shopapi.model.Product;
import com.marketplace.shopapi.service.ProductFileReader;
import com.marketplace.shopapi.service.ShopProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.List;

@SpringBootApplication
public class ShopApiApplication implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(ShopApiApplication.class);

    private final ProductFileReader fileReader;
    private final ShopProducer producer;

    public ShopApiApplication(ProductFileReader fileReader, ShopProducer producer) {
        this.fileReader = fileReader;
        this.producer = producer;
    }

    public static void main(String[] args) {
        SpringApplication.run(ShopApiApplication.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        List<Product> products = fileReader.readProducts();

        log.info("=== Отправка товаров в Kafka ===");
        for (Product product : products) {
            producer.send(product);
        }

        producer.flush();
        log.info("=== Готово: {} товаров отправлено в shop-products ===", products.size());
    }
}
