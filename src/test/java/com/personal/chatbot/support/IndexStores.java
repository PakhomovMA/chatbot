package com.personal.chatbot.support;

import com.personal.chatbot.models.index.IndexManifest;
import com.personal.chatbot.service.embedding.EmbabelEmbeddingServiceAdapter;
import com.personal.chatbot.service.embedding.PromptedEmbeddingService;
import com.personal.chatbot.service.embedding.TextEmbedder;
import com.personal.chatbot.service.index.LuceneIndexStore;
import com.personal.chatbot.service.parsing.ProvenanceChunkTransformer;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;

/** Builds index stores over fake embedders for tests. */
public final class IndexStores {

    public static final IndexManifest.Chunker CHUNKER = new IndexManifest.Chunker(400, 50, ProvenanceChunkTransformer.TRANSFORMER_VERSION);

    private IndexStores() {
    }

    public static PromptedEmbeddingService embeddings(TextEmbedder backend) {
        return new PromptedEmbeddingService(backend, 8, 1, true,
                TestObservations.embedding(backend.provider(), backend.modelName()));
    }

    /** Unopened store; call {@link LuceneIndexStore#open()}. */
    public static LuceneIndexStore store(@Nullable Path indexDir, TextEmbedder backend) {
        return store(indexDir, backend, CHUNKER);
    }

    public static LuceneIndexStore store(@Nullable Path indexDir, TextEmbedder backend, IndexManifest.Chunker chunker) {
        PromptedEmbeddingService embeddings = embeddings(backend);
        return new LuceneIndexStore(indexDir, new EmbabelEmbeddingServiceAdapter(embeddings), embeddings.fingerprint(),
                chunker, 8, new ProvenanceChunkTransformer());
    }
}
