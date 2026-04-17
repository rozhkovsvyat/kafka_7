package com.marketplace.streamsfilter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.Produced;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.nio.file.Path;
import java.util.Properties;
import java.util.Scanner;
import java.util.Set;

/**
 * Kafka Streams: фильтрует товары из shop-products, пропускает в products-filtered
 * только разрешённые. Список запрещённых управляется через CLI (add/remove/list/stop).
 */
@SpringBootApplication
public class StreamsFilterApplication implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(StreamsFilterApplication.class);

    @Value("${kafka.bootstrap-servers}")       private String bootstrapServers;
    @Value("${kafka.security-protocol}")       private String securityProtocol;
    @Value("${kafka.sasl-mechanism}")          private String saslMechanism;
    @Value("${kafka.sasl-jaas-config}")        private String saslJaasConfig;
    @Value("${kafka.ssl-truststore-location}") private String truststoreLocation;
    @Value("${kafka.ssl-truststore-password}") private String truststorePassword;
    @Value("${topics.source}")                 private String sourceTopic;
    @Value("${topics.sink}")                   private String sinkTopic;
    @Value("${banned-list-file}")              private String bannedListFile;

    public static void main(String[] args) {
        SpringApplication.run(StreamsFilterApplication.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        BannedList bannedList = new BannedList(Path.of(bannedListFile));

        KafkaStreams streams = buildStreams(bannedList);
        streams.start();
        Runtime.getRuntime().addShutdownHook(new Thread(streams::close));

        log.info("=== Streams Filter запущен ===");
        log.info("Топик-источник : {}", sourceTopic);
        log.info("Топик-приёмник : {}", sinkTopic);
        log.info("Список запрещённых: {}", bannedListFile);
        printHelp();

        Scanner scanner = new Scanner(System.in);
        while (scanner.hasNextLine()) {
            String line = scanner.nextLine().trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split("\\s+", 2);
            switch (parts[0].toLowerCase()) {
                case "add" -> {
                    if (parts.length < 2) { log.warn("Использование: add <product_id>"); break; }
                    bannedList.add(parts[1]);
                    log.info("[OK] Добавлен в список запрещённых: {}", parts[1]);
                }
                case "remove" -> {
                    if (parts.length < 2) { log.warn("Использование: remove <product_id>"); break; }
                    if (bannedList.remove(parts[1])) log.info("[OK] Удалён из запрещённых: {}", parts[1]);
                    else log.warn("[--] Не найден в списке: {}", parts[1]);
                }
                case "list" -> {
                    Set<String> all = bannedList.getAll();
                    if (all.isEmpty()) log.info("Список запрещённых пуст");
                    else { log.info("Запрещённые товары:"); all.forEach(id -> log.info("  - {}", id)); }
                }
                case "stop" -> { streams.close(); System.exit(0); }
                default -> printHelp();
            }
        }
    }

    private KafkaStreams buildStreams(BannedList bannedList) {
        ObjectMapper mapper = new ObjectMapper();

        StreamsBuilder builder = new StreamsBuilder();
        builder.stream(sourceTopic, Consumed.with(Serdes.String(), Serdes.String()))
            .filter((key, value) -> {
                if (value == null) return false;
                try {
                    JsonNode node = mapper.readTree(value);
                    String productId = node.path("product_id").asText(null);
                    String name      = node.path("name").asText(null);
                    boolean isBanned = bannedList.contains(productId) || bannedList.contains(name);
                    if (isBanned) {
                        log.info("[FILTER] Запрещён: {} / {}", productId, name);
                    }
                    return !isBanned;
                } catch (Exception e) {
                    log.warn("[FILTER] Не удалось разобрать JSON (key={}): {}", key, e.getMessage());
                    return true;
                }
            })
            .to(sinkTopic, Produced.with(Serdes.String(), Serdes.String()));

        return new KafkaStreams(builder.build(), streamsProps());
    }

    private Properties streamsProps() {
        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG,      "streams-filter");
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG,   bootstrapServers);
        props.put("security.protocol",                       securityProtocol);
        props.put("sasl.mechanism",                          saslMechanism);
        props.put("sasl.jaas.config",                        saslJaasConfig);
        props.put("ssl.truststore.location",                 truststoreLocation);
        props.put("ssl.truststore.password",                 truststorePassword);
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG,   Serdes.String().getClass());
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass());
        return props;
    }

    private void printHelp() {
        log.info("Команды: add <id>  |  remove <id>  |  list  |  stop");
    }
}
