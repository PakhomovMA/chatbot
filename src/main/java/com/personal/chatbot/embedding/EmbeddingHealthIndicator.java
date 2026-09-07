package com.personal.chatbot.embedding;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** Reports the active embedding model and its fingerprint under the {@code embedding} health component. */
@Component("embedding")
class EmbeddingHealthIndicator implements HealthIndicator {

    private final KnowledgeEmbeddingService service;

    EmbeddingHealthIndicator(KnowledgeEmbeddingService service) {
        this.service = service;
    }

    @Override
    public Health health() {
        Health.Builder builder = service.warmupDuration().isPresent() ? Health.up() : Health.unknown();
        builder.withDetail("provider", service.provider())
                .withDetail("model", service.modelName())
                .withDetail("dimensions", service.dimensions())
                .withDetail("fingerprint", service.fingerprint().value());
        service.warmupDuration().ifPresent(d -> builder.withDetail("warmupMs", d.toMillis()));
        return builder.build();
    }
}
