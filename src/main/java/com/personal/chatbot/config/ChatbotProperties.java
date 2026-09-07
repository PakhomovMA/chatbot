package com.personal.chatbot.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;

/**
 * Application-level settings under the {@code chatbot.*} prefix.
 *
 * @param dataDir root directory for persistent local state: Lucene index, document registry,
 *                uploaded originals and embedding model files (docs/system-plan.md §9).
 */
@Validated
@ConfigurationProperties(prefix = "chatbot")
public record ChatbotProperties(@NotNull Path dataDir) {
}
