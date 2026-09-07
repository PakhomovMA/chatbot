package com.personal.chatbot.models.index;

import com.personal.chatbot.models.embedding.EmbeddingFingerprint;

import java.time.Instant;

/**
 * Sidecar describing the vector space and chunking an index was built with (INV-05). Stored as
 * {@code index/manifest.json}; any mismatch with the running configuration makes the index
 * {@link IndexState#INCOMPATIBLE}.
 */
public record IndexManifest(
        int schemaVersion,
        Embedding embedding,
        Chunker chunker,
        String luceneVersion,
        Instant createdAt,
        Instant updatedAt
) {

    public static final int SCHEMA_VERSION = 1;

    public record Embedding(String provider, String model, int dimensions, String fingerprint) {

        public static Embedding of(EmbeddingFingerprint fingerprint) {
            return new Embedding(fingerprint.provider(), fingerprint.model(), fingerprint.dimensions(), fingerprint.value());
        }
    }

    public record Chunker(int maxChunkSize, int overlapSize, String transformerVersion) {
    }

    public static IndexManifest create(EmbeddingFingerprint fingerprint, Chunker chunker, String luceneVersion, Instant now) {
        return new IndexManifest(SCHEMA_VERSION, Embedding.of(fingerprint), chunker, luceneVersion, now, now);
    }

    /** True when an index built under this manifest can be used with the given configuration. */
    public boolean isCompatibleWith(EmbeddingFingerprint fingerprint, Chunker current) {
        return schemaVersion == SCHEMA_VERSION
                && embedding.fingerprint().equals(fingerprint.value())
                && chunker.equals(current);
    }
}
