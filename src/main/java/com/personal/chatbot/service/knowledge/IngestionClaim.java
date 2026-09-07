package com.personal.chatbot.service.knowledge;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * One claimed ingestion request, handed to the pipeline by {@link IngestionQueue}. It answers a
 * single question: may this run still publish its result, or has a newer request, a deletion or an
 * index rebuild taken the document over?
 */
public interface IngestionClaim {

    String documentId();

    /** True while this run is still the one that owns the document. */
    boolean isCurrent();

    /**
     * Runs {@code publish} only if the claim is still current, holding off deletions, rebuilds and
     * newer requests for its duration — so a result and the decision to publish it cannot be
     * separated by another writer. {@code publish} may take the registry lock (queue monitor →
     * registry lock); it must not wait for the index or for the queue itself.
     *
     * @return the result of {@code publish}, or empty if the claim had already been superseded
     */
    <T> Optional<T> ifCurrent(Supplier<T> publish);
}
