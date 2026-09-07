package com.personal.chatbot.service.index;

import com.embabel.agent.rag.model.ContentElement;
import com.embabel.agent.rag.model.Retrievable;
import com.embabel.agent.rag.service.CoreSearchOperations;
import com.embabel.agent.rag.service.ResultExpander;
import com.embabel.agent.rag.service.TextQueryMode;
import com.embabel.common.core.types.SimilarityResult;
import com.embabel.common.core.types.TextSimilaritySearchRequest;
import com.personal.chatbot.utils.EmbeddingModeScope;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Set;

/**
 * The store's search capabilities as seen by Embabel tools (Phase 9c). Every call goes through
 * {@link LuceneIndexStore#search}, so tool calls issued by the model honour the same read lock and
 * index-state checks as deterministic retrieval, and vector queries use the query embedding prefix.
 * Only the interfaces the Lucene store really implements are exposed, so {@code ToolishRag} builds
 * exactly the vectorSearch / textSearch / broadenChunk / zoomOut tools.
 */
public final class LockedSearchOperations implements CoreSearchOperations, ResultExpander {

    private final LuceneIndexStore store;

    LockedSearchOperations(LuceneIndexStore store) {
        this.store = store;
    }

    @Override
    public boolean supportsType(@NotNull String type) {
        return store.search(ops -> ops.supportsType(type));
    }

    @Override
    public <T extends Retrievable> @NotNull List<SimilarityResult<T>> vectorSearch(@NotNull TextSimilaritySearchRequest request, @NotNull Class<T> clazz) {
        return EmbeddingModeScope.inQueryMode(() -> store.search(ops -> ops.vectorSearch(request, clazz)));
    }

    @Override
    public <T extends Retrievable> @NotNull List<SimilarityResult<T>> textSearch(@NotNull TextSimilaritySearchRequest request, @NotNull Class<T> clazz) {
        return store.search(ops -> ops.textSearch(request, clazz));
    }

    @Override
    public @NotNull Set<TextQueryMode> getSupportedQueryModes() {
        return store.search(ops -> ops.getSupportedQueryModes());
    }

    @Override
    public @NotNull TextQueryMode getQueryMode() {
        return store.search(ops -> ops.getQueryMode());
    }

    @Override
    public @NotNull String getLuceneSyntaxNotes() {
        return store.search(ops -> ops.getLuceneSyntaxNotes());
    }

    @Override
    public @NotNull List<ContentElement> expandResult(@NotNull String id, @NotNull Method method, int elementsToAdd) {
        return store.expand(id, method, elementsToAdd);
    }
}
