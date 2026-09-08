package com.personal.chatbot.config;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Ensures {@code chatbot.data-dir} exists at startup so later phases (index, registry, blobs)
 * can rely on it, and logs the resolved location.
 */
@Component
class DataDirectoryInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataDirectoryInitializer.class);

    private final ChatbotProperties properties;

    DataDirectoryInitializer(ChatbotProperties properties) {
        this.properties = properties;
    }

    @Override
    public void run(@NonNull ApplicationArguments args) {
        Path dataDir = properties.dataDir().toAbsolutePath().normalize();
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create chatbot data directory " + dataDir, e);
        }
        log.info("Chatbot data directory: {}", dataDir);
    }
}
