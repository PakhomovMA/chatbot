package com.personal.chatbot.service.index;

import com.embabel.agent.rag.model.ContentElement;
import com.embabel.agent.rag.model.Retrievable;
import com.embabel.agent.rag.service.CoreSearchOperations;
import com.embabel.agent.rag.service.ResultExpander;
import com.embabel.agent.rag.service.TextQueryMode;
import com.embabel.agent.rag.service.TextSearch;
import com.embabel.common.core.types.SimilarityResult;
import com.embabel.common.core.types.TextSimilaritySearchRequest;
import com.personal.chatbot.models.retrieval.RetrievalMode;
import com.personal.chatbot.observability.Measured;
import com.personal.chatbot.observability.RetrievalObservations;
import com.personal.chatbot.observability.RetrievalStage;
import com.personal.chatbot.utils.EmbeddingModeScope;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The store's search capabilities as seen by Embabel tools (Phase 9c). Every call goes through
 * {@link LuceneIndexStore#search}, so tool calls issued by the model honour the same read lock and
 * index-state checks as deterministic retrieval, and vector queries use the query embedding prefix.
 * Only the interfaces the Lucene store really implements are exposed, so {@code ToolishRag} builds
 * exactly the vectorSearch / textSearch / broadenChunk / zoomOut tools.
 *
 * <p>These searches are the retrieval of the agentic branch, and until this checkpoint they were the
 * one retrieval in the application that nothing measured: they do not go through
 * {@code RetrievalService}, so no pass is recorded for them. They are stages of the same shape as a
 * deterministic pass's — one facet each, the read lock and the query embedding included — and are
 * reported as such, with the mode of the facet, because here that facet is the whole search
 * (docs/observability/metric-catalog.json, {@code chatbot.retrieval.stage}). Nothing about what
 * searches, in which order or under which lock changes.
 */
public final class LockedSearchOperations implements CoreSearchOperations, ResultExpander {

    private final LuceneIndexStore store;
    private final RetrievalObservations observations;

    LockedSearchOperations(LuceneIndexStore store, RetrievalObservations observations) {
        this.store = store;
        this.observations = observations;
    }

    @Override
    public boolean supportsType(@NotNull String type) {
        return store.search(ops -> ops.supportsType(type));
    }

    @Override
    public <T extends Retrievable> @NotNull List<SimilarityResult<T>> vectorSearch(@NotNull TextSimilaritySearchRequest request, @NotNull Class<T> clazz) {
        return measured(RetrievalStage.VECTOR, RetrievalMode.VECTOR,
                () -> EmbeddingModeScope.inQueryMode(() -> store.search(ops -> ops.vectorSearch(request, clazz))));
    }

    @Override
    public <T extends Retrievable> @NotNull List<SimilarityResult<T>> textSearch(@NotNull TextSimilaritySearchRequest request, @NotNull Class<T> clazz) {
        return measured(RetrievalStage.TEXT, RetrievalMode.TEXT, () -> store.search(ops -> ops.textSearch(request, clazz)));
    }

    private <T> T measured(RetrievalStage stage, RetrievalMode mode, Supplier<T> search) {
        try (Measured measure = observations.startStage(stage, mode)) {
            try {
                T results = search.get();
                measure.succeeded();
                return results;
            } catch (RuntimeException e) {
                measure.failed(e);
                throw e;
            }
        }
    }

    @Override
    public @NotNull Set<TextQueryMode> getSupportedQueryModes() {
        return store.search(TextSearch::getSupportedQueryModes);
    }

    @Override
    public @NotNull TextQueryMode getQueryMode() {
        return store.search(TextSearch::getQueryMode);
    }

    @Override
    public @NotNull String getLuceneSyntaxNotes() {
        return store.search(TextSearch::getLuceneSyntaxNotes);
    }

    @Override
    public @NotNull List<ContentElement> expandResult(@NotNull String id, @NotNull Method method, int elementsToAdd) {
        return store.expand(id, method, elementsToAdd);
    }
}
