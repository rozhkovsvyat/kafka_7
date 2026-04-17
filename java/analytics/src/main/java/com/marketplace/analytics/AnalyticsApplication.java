package com.marketplace.analytics;

import com.marketplace.analytics.command.IngestCommand;
import com.marketplace.analytics.command.ProduceCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AnalyticsApplication implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsApplication.class);

    private final IngestCommand ingest;
    private final ProduceCommand produce;

    public AnalyticsApplication(IngestCommand ingest, ProduceCommand produce) {
        this.ingest = ingest;
        this.produce = produce;
    }

    public static void main(String[] args) {
        SpringApplication.run(AnalyticsApplication.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        if (args.length == 0) {
            log.info("Usage: analytics.jar <ingest|produce>");
            log.info("  ingest  — Kafka (local) → HDFS");
            log.info("  produce — HDFS (Spark output) → Kafka (YC recommendations)");
            return;
        }
        switch (args[0]) {
            case "ingest"  -> ingest.run();
            case "produce" -> produce.run();
            default        -> log.warn("Unknown command: {}", args[0]);
        }
    }
}
