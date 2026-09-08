package com.personal.chatbot.service.index;

import com.embabel.agent.rag.model.Chunk;
import com.embabel.agent.rag.model.ContentElement;
import com.embabel.agent.rag.model.Retrievable;
import com.embabel.agent.rag.service.CoreSearchOperations;
import com.embabel.agent.rag.service.ResultExpander;
import com.embabel.agent.rag.service.SectionReader;
import com.embabel.agent.rag.service.SectionSummary;
import com.embabel.agent.rag.service.TextQueryMode;
import com.embabel.common.core.types.SimilarityResult;
import com.embabel.common.core.types.TextSimilaritySearchRequest;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.Nullable;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * One agentic run's view of the store (docs/system-plan.md Phase 9c follow-up). It adds two things to
 * {@link LockedSearchOperations}, both of which only make sense per request:
 *
 * <ul>
 *   <li><b>Scope.</b> A question restricted to some documents has to stay inside them. Embabel applies
 *   its {@code metadataFilter} to vector and text search only — it builds the section and expansion
 *   tools without it — so the section tools are filtered here instead.</li>
 *   <li><b>A ledger</b> of which families of tool the model actually used. The catalogue tool reports
 *   nothing to the {@code ResultsListener} because it returns no chunks, so this is the only record
 *   that the model read the table of contents rather than answering out of thin air.</li>
 * </ul>
 *
 * <p>{@code SectionReader} lives here rather than on {@link LockedSearchOperations} so that the section
 * tools can be switched off: handing {@code ToolishRag} the plain store view leaves it with exactly the
 * four search tools it built before.
 *
 * <p>Embabel may run the tool calls of one request on more than one thread, so the ledger is guarded.
 */
public final class ScopedSearchOperations implements CoreSearchOperations, ResultExpander, SectionReader {

    /** Coarse enough to survive Embabel renaming a tool, precise enough to tell browsing from research. */
    public enum ToolFamily {
        SEARCH, EXPAND, CATALOGUE, SECTION_READ
    }

    private final LockedSearchOperations delegate;
    private final SectionCatalog catalog;
    private final @Nullable Set<String> documentIds;
    private final Set<ToolFamily> used = EnumSet.noneOf(ToolFamily.class);
    private final Object lock = new Object();

    /**
     * @param documentIds documents this question is restricted to, null or empty for the whole corpus
     */
    public ScopedSearchOperations(LockedSearchOperations delegate, SectionCatalog catalog,
                                  @Nullable Set<String> documentIds) {
        this.delegate = delegate;
        this.catalog = catalog;
        this.documentIds = documentIds;
    }

    @Override
    public boolean supportsType(@NotNull String type) {
        return delegate.supportsType(type);
    }

    @Override
    public <T extends Retrievable> @NotNull List<SimilarityResult<T>> vectorSearch(@NotNull TextSimilaritySearchRequest request,
                                                                                   @NotNull Class<T> clazz) {
        record(ToolFamily.SEARCH);
        return delegate.vectorSearch(request, clazz);
    }

    @Override
    public <T extends Retrievable> @NotNull List<SimilarityResult<T>> textSearch(@NotNull TextSimilaritySearchRequest request,
                                                                                 @NotNull Class<T> clazz) {
        record(ToolFamily.SEARCH);
        return delegate.textSearch(request, clazz);
    }

    @Override
    public @NotNull Set<TextQueryMode> getSupportedQueryModes() {
        return delegate.getSupportedQueryModes();
    }

    @Override
    public @NotNull TextQueryMode getQueryMode() {
        return delegate.getQueryMode();
    }

    @Override
    public @NotNull String getLuceneSyntaxNotes() {
        return delegate.getLuceneSyntaxNotes();
    }

    /**
     * Not filtered by the document scope, and it does not need to be: expansion starts from a chunk the
     * model was already shown, and both neighbours and the parent section stay inside its document.
     */
    @Override
    public @NotNull List<ContentElement> expandResult(@NotNull String id, @NotNull Method method, int elementsToAdd) {
        record(ToolFamily.EXPAND);
        return delegate.expandResult(id, method, elementsToAdd);
    }

    @Override
    public @NotNull List<SectionSummary> listSections(@Nullable String documentTitle) {
        record(ToolFamily.CATALOGUE);
        return catalog.list(documentTitle, documentIds);
    }

    @Override
    public @NotNull List<Chunk> readSection(@NotNull String sectionTitle, @Nullable String documentTitle) {
        record(ToolFamily.SECTION_READ);
        return catalog.read(sectionTitle, documentTitle, documentIds);
    }

    /**
     * True when the model did nothing but read the table of contents: no search, no expansion, no
     * section read. An answer built on that cites nothing and still says something true about the
     * knowledge base, which is why it is allowed to be published uncited (INV-03).
     */
    public boolean onlyBrowsedCatalogue() {
        synchronized (lock) {
            return used.equals(EnumSet.of(ToolFamily.CATALOGUE));
        }
    }

    /** What the model reached for, for diagnostics and eval. */
    public Set<ToolFamily> toolsUsed() {
        synchronized (lock) {
            return Set.copyOf(used);
        }
    }

    private void record(ToolFamily family) {
        synchronized (lock) {
            used.add(family);
        }
    }
}
