package com.personal.chatbot.service.index;

import com.embabel.agent.rag.model.NavigableDocument;
import com.personal.chatbot.models.embedding.EmbeddingFingerprint;
import com.personal.chatbot.models.index.IndexState;

import java.util.List;
import java.util.Set;

/**
 * The write side of the knowledge index, as ingestion needs it (docs/system-plan.md §5). Deliberately
 * narrow: no searching, no expansion, no manifest. Writes are serialised by the implementation, so a
 * holder of this contract can assume single-writer semantics (INV-11).
 */
public interface KnowledgeIndexWriter {

    /**
     * Indexes a document, replacing any previous content under the same URI. Either every chunk is
     * written with its vector, or nothing of the document remains (INV-09).
     *
     * @return the ids of the chunks written
     */
    List<String> writeDocument(NavigableDocument document);

    /** @return true if something was removed */
    boolean deleteDocument(String uri);

    /** URIs of every document root currently in the index. */
    Set<String> documentUris();

    IndexState state();

    /** Embedding fingerprint the index is pinned to (INV-05). */
    EmbeddingFingerprint fingerprint();

    /** Drops all content and starts a fresh index under the current fingerprint. */
    void rebuild();
}
