package com.marketplace.clientapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketplace.clientapi.model.ClientQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

/** Сохраняет запросы клиентов в локальный JSONL-файл для отладки и аудита. */
@Service
public class QueryStorage {

    private static final Logger log = LoggerFactory.getLogger(QueryStorage.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${client.storage.file:data/client-queries.jsonl}")
    private String storageFile;

    public void save(ClientQuery query) {
        File file = new File(storageFile);
        file.getParentFile().mkdirs();
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file, true))) {
            writer.write(objectMapper.writeValueAsString(query));
            writer.newLine();
            log.info("Запрос сохранён в хранилище: queryId={}.", query.queryId());
        } catch (IOException e) {
            log.error("Ошибка сохранения запроса в файл: {}.", e.getMessage());
        }
    }
}
