package com.marketplace.streamsfilter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.stream.Collectors;

/**
 * Thread-safe in-memory список запрещённых товаров с персистенцией в файл.
 * Поддерживает горячее обновление без рестарта приложения.
 */
public class BannedList {

    private static final Logger log = LoggerFactory.getLogger(BannedList.class);

    private final Set<String> banned = new CopyOnWriteArraySet<>();
    private final Path filePath;

    public BannedList(Path filePath) {
        this.filePath = filePath;
        load();
    }

    private void load() {
        if (!Files.exists(filePath)) return;
        try {
            Files.readAllLines(filePath).stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .map(String::toLowerCase)
                .forEach(banned::add);
        } catch (IOException e) {
            log.warn("Не удалось загрузить список запрещённых: {}", e.getMessage());
        }
    }

    public void add(String id) {
        banned.add(id.toLowerCase());
        save();
    }

    public boolean remove(String id) {
        boolean removed = banned.remove(id.toLowerCase());
        if (removed) save();
        return removed;
    }

    public boolean contains(String id) {
        return id != null && banned.contains(id.toLowerCase());
    }

    public Set<String> getAll() {
        return Collections.unmodifiableSet(banned);
    }

    private void save() {
        try {
            Files.writeString(filePath,
                banned.stream().sorted().collect(Collectors.joining("\n")));
        } catch (IOException e) {
            log.warn("Не удалось сохранить список запрещённых: {}", e.getMessage());
        }
    }
}
