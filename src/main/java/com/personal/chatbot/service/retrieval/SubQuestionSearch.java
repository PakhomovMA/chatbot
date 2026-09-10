package com.personal.chatbot.service.retrieval;

import com.personal.chatbot.config.ChatbotProperties;
import com.personal.chatbot.models.retrieval.QuestionDecomposition;
import com.personal.chatbot.models.retrieval.RetrievalQuery;
import com.personal.chatbot.models.retrieval.RetrievalResult;
import com.personal.chatbot.models.retrieval.RetrievedChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * The retrieval half of {@code decomposeQuestion} (docs/system-plan.md Phase 9d): searches for the
 * question as a whole and for each part the model split it into, then merges the passes into one
 * evidence list. Which parts to search for is decided above this class; everything here is
 * deterministic and goes through {@link Retriever} like any other search (INV-01).
 *
 * <p>A question that asks for several things retrieves badly as one query: the parts share the
 * candidate budget, and the weaker one is pushed out of the evidence by the stronger one. Searching
 * per part and merging by reciprocal rank ({@link PassFusion}) gives every part its own ranking, so
 * each contributes its best passages to the answer.
 *
 * <p>The passes are run by the caller, which is what lets them run in parallel (Embabel's
 * {@code parallelMap}) while this class stays free of the agent platform.
 */
public class SubQuestionSearch {

    private static final Logger log = LoggerFactory.getLogger(SubQuestionSearch.class);

    private final RetrievalTraceStore traces;
    private final ChatbotProperties.Retrieval settings;
    private final DocumentSpread spread;

    public SubQuestionSearch(RetrievalTraceStore traces, ChatbotProperties.Retrieval settings) {
        this.traces = traces;
        this.settings = settings;
        this.spread = new DocumentSpread(settings.maxDocumentShare());
    }

    /**
     * @param parts   the parts of the question; blanks, duplicates and repetitions of the whole
     *                question are dropped, and an empty result means one plain search
     * @param buildMs time already spent splitting the question (the model call)
     * @param passes  runs the searches, in whatever order and concurrency the caller chooses, and
     *                returns their results in the order the queries were given
     * @return the merged result, recorded as its own trace and carrying a {@link QuestionDecomposition}
     * marker; an undecomposed question comes back as the plain single-pass result, unmarked, so that
     * the widening branch of Phase 9a can still do its own work on it
     */
    public RetrievalResult search(RetrievalQuery whole, List<String> parts, long buildMs,
                                  Function<List<RetrievalQuery>, List<RetrievalResult>> passes) {
        long started = System.nanoTime();
        List<String> subQuestions = parts.stream().map(String::strip).filter(part -> !part.isBlank())
                .filter(part -> !part.equalsIgnoreCase(whole.query().strip())).distinct().toList();
        List<RetrievalQuery> queries = new ArrayList<>(subQuestions.size() + 1);
        queries.add(whole);
        subQuestions.forEach(part -> queries.add(whole.withQuery(part)));

        List<RetrievalResult> results = passes.apply(List.copyOf(queries));
        if (results.size() != queries.size()) {
            throw new IllegalStateException("Expected " + queries.size() + " retrieval passes, got " + results.size());
        }
        if (subQuestions.isEmpty()) {
            return results.getFirst();
        }
        long tookMs = buildMs + (System.nanoTime() - started) / 1_000_000;
        RetrievalResult merged = merge(results, subQuestions, whole, tookMs);
        traces.record(merged);
        log.info("Decomposed search [{}] into {} parts: {} hits (+{} new), sufficient {} -> {} in {} ms",
                merged.traceId(), subQuestions.size(), merged.hits().size(), merged.decomposition().addedHits(),
                results.getFirst().evidenceSufficient(), merged.evidenceSufficient(), tookMs);
        return merged;
    }

    private RetrievalResult merge(List<RetrievalResult> passes, List<String> subQuestions, RetrievalQuery whole,
                                  long tookMs) {
        RetrievalResult first = passes.getFirst();
        int topK = first.topK();
        PassFusion.Merged merged = PassFusion.merge(passes, topK, settings.rrfK(), spread);
        List<RetrievedChunk> hits = merged.hits();
        double maxVector = RetrievalService.maxVectorScore(hits);
        return new RetrievalResult(UUID.randomUUID().toString(),
                PassFusion.query(whole.query().strip(), subQuestions), first.mode(), topK,
                passes.stream().mapToInt(RetrievalResult::candidates).sum(), hits,
                RetrievalService.sufficient(hits, maxVector, settings.sufficientCosine()), maxVector,
                PassFusion.timings(passes, tookMs), Instant.now(), null,
                new QuestionDecomposition(subQuestions, merged.addedHits(), tookMs));
    }
}
