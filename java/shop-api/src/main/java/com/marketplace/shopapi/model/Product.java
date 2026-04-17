package com.marketplace.shopapi.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Product(
    @JsonProperty("product_id") String productId,
    String name,
    String description,
    Price price,
    String category,
    String brand,
    Stock stock,
    String sku,
    List<String> tags,
    List<Map<String, String>> images,
    Map<String, Object> specifications,
    @JsonProperty("created_at") String createdAt,
    @JsonProperty("updated_at") String updatedAt,
    String index,
    @JsonProperty("store_id") String storeId
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Price(double amount, String currency) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Stock(int available, int reserved) {}
}
