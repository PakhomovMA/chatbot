package com.personal.chatbot.observability;

import com.personal.chatbot.models.index.IndexInfo;
import com.personal.chatbot.service.index.IndexStatus;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** {@code luceneIndex} health: OUT_OF_SERVICE while INCOMPATIBLE or REBUILDING, UP otherwise. */
@Component("luceneIndex")
class IndexHealthIndicator implements HealthIndicator {

    private final IndexStatus indexStore;

    IndexHealthIndicator(IndexStatus indexStore) {
        this.indexStore = indexStore;
    }

    @Override
    public Health health() {
        IndexInfo info = indexStore.info();
        Health.Builder builder = switch (info.state()) {
            case READY, EMPTY -> Health.up();
            case INCOMPATIBLE, REBUILDING -> Health.outOfService();
        };
        builder.withDetail("state", info.state().name())
                .withDetail("chunks", info.chunkCount())
                .withDetail("documents", info.documentCount())
                .withDetail("persistent", info.persistent());
        if (info.indexPath() != null) {
            builder.withDetail("path", info.indexPath());
        }
        if (info.manifest() != null) {
            builder.withDetail("fingerprint", info.manifest().embedding().fingerprint());
        }
        if (info.incompatibilityReason() != null) {
            builder.withDetail("reason", info.incompatibilityReason());
        }
        if (info.recoveredFrom() != null) {
            builder.withDetail("recoveredFrom", info.recoveredFrom());
        }
        return builder.build();
    }
}
