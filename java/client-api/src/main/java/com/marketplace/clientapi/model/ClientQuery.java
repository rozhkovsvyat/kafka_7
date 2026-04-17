package com.marketplace.clientapi.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ClientQuery(
    @JsonProperty("query_id") String queryId,
    @JsonProperty("user_id") String userId,
    QueryType type,
    @JsonProperty("query_text") String queryText,
    String timestamp
) {}
