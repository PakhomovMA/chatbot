package com.personal.chatbot.service.cache;

import com.personal.chatbot.models.chat.Citation;
import com.personal.chatbot.models.chat.Grounding;
import com.personal.chatbot.models.retrieval.RetrievalResult;
import com.personal.chatbot.models.retrieval.RetrievedChunk;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;

/**
 * An answer the answer cache keeps (docs/cache-plan.md §3.4): what its response carried, the retrieval it
 * was verified against and the knowledge-base revision it is valid at. Everything here stays in the
 * process; the question text is kept for the semantic layer, never for a log, a metric or a span.
 *
 * @param question       the question as it was asked
 * @param retrieval      the retrieval behind the citations, recorded again when the entry is served, so
 *                       the trace id of the response keeps leading to it
 * @param revision       the knowledge-base revision the answer was computed and verified at
 * @param createdAt      when it was stored
 * @param questionVector reserved for the semantic layer (K05); null until then
 */
public record CachedAnswer(
        String question,
        String answer,
        Grounding grounding,
        List<Citation> citations,
        @Nullable String notes,
        RetrievalResult retrieval,
        long revision,
        Instant createdAt,
        float @Nullable [] questionVector
) {

    /** Allowance for everything {@link #weight} does not count: ids, scores, timings and the objects themselves. */
    static final int OVERHEAD_BYTES = 1024;

    /**
     * Approximate size in bytes, which {@code chatbot.cache.answer.max-weight} bounds: the text of the
     * entry at two bytes a character — an upper bound for Java strings — plus a fixed overhead.
     */
    public int weight() {
        long chars = question.length() + answer.length() + (notes == null ? 0 : notes.length())
                + retrieval.query().length();
        for (Citation citation : citations) {
            chars += citation.quote().length() + citation.documentTitle().length() + citation.sectionTitle().length();
        }
        for (RetrievedChunk hit : retrieval.hits()) {
            chars += hit.text().length() + hit.provenance().documentTitle().length()
                    + hit.provenance().sectionTitle().length();
        }
        return (int) Math.min(Integer.MAX_VALUE, OVERHEAD_BYTES + 2 * chars);
    }
}
