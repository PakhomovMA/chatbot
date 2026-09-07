package com.personal.chatbot.models.index;

import org.jspecify.annotations.Nullable;

/**
 * Snapshot of the index for status endpoints and health.
 *
 * @param incompatibilityReason why the index is {@link IndexState#INCOMPATIBLE}, if it is
 * @param recoveredFrom         directory a corrupt index was moved to at startup, if that happened
 */
public record IndexInfo(
        IndexState state,
        int chunkCount,
        int documentCount,
        @Nullable String indexPath,
        boolean persistent,
        @Nullable IndexManifest manifest,
        @Nullable String incompatibilityReason,
        @Nullable String recoveredFrom
) {
}
