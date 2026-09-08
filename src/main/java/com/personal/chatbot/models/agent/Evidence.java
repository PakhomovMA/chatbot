package com.personal.chatbot.models.agent;

import com.personal.chatbot.models.retrieval.RetrievalResult;
import com.personal.chatbot.models.retrieval.RetrievedChunk;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Blackboard artifact: what deterministic retrieval found for the question (INV-01, INV-06), plus the
 * comparison of its sources once {@code compareSources} has looked at them (Phase 9d).
 */
public record Evidence(UserQuestion question, RetrievalResult retrieval, @Nullable SourceComparison comparison) {

    public Evidence(UserQuestion question, RetrievalResult retrieval) {
        this(question, retrieval, null);
    }

    public List<RetrievedChunk> hits() {
        return retrieval.hits();
    }

    public boolean isEmpty() {
        return retrieval.hits().isEmpty();
    }

    /** Whether retrieval scores alone suggest the corpus covers the question (calibrated floor). */
    public boolean sufficientByScore() {
        return retrieval.evidenceSufficient();
    }

    /** Whether this evidence already merges a widened second search (docs/system-plan.md Phase 9a). */
    public boolean expanded() {
        return retrieval.expanded();
    }

    /** Documents the passages come from; the comparison branch needs at least two of them (Phase 9d). */
    public long documentCount() {
        return hits().stream().map(hit -> hit.provenance().documentId()).distinct().count();
    }

    /** Whether the sources have been compared; always true once the branch ran, even if it found nothing. */
    public boolean compared() {
        return comparison != null;
    }

    /** The same evidence with the comparison of its sources attached; the passages are untouched. */
    public Evidence withComparison(SourceComparison comparison) {
        return new Evidence(question, retrieval, comparison);
    }
}
