package com.personal.chatbot.config;

import com.embabel.common.ai.model.EmbeddingService;
import com.personal.chatbot.models.index.IndexManifest;
import com.personal.chatbot.observability.RetrievalObservations;
import com.personal.chatbot.service.embedding.KnowledgeEmbeddingService;
import com.personal.chatbot.service.index.LockedSearchOperations;
import com.personal.chatbot.service.index.LuceneIndexStore;
import com.personal.chatbot.service.index.SectionCatalog;
import com.personal.chatbot.service.parsing.DocumentParser;
import com.personal.chatbot.service.parsing.ProvenanceChunkTransformer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/** Wires parsing and the Lucene index store (docs/system-plan.md D4, D8, §9). */
@Configuration(proxyBeanMethods = false)
class IndexConfiguration {

    @Bean
    DocumentParser documentParser() {
        return new DocumentParser();
    }

    @Bean
    ProvenanceChunkTransformer provenanceChunkTransformer() {
        return new ProvenanceChunkTransformer();
    }

    /**
     * The Embabel-facing embedding bean is injected by interface: Embabel wraps it in its own
     * tracking decorator, which still routes through the audit hook used to verify writes.
     */
    @Bean(destroyMethod = "close")
    LuceneIndexStore luceneIndexStore(ChatbotProperties properties, EmbeddingService embabelEmbeddingService,
                                      KnowledgeEmbeddingService knowledgeEmbeddingService,
                                      ProvenanceChunkTransformer transformer) {
        ChatbotProperties.Index index = properties.index();
        Path indexDir = index.inMemory() ? null
                : (index.dir() != null ? index.dir() : properties.dataDir().resolve("index"));
        var chunker = new IndexManifest.Chunker(index.maxChunkSize(), index.overlapSize(), ProvenanceChunkTransformer.TRANSFORMER_VERSION);
        return new LuceneIndexStore(indexDir, embabelEmbeddingService, knowledgeEmbeddingService.fingerprint(),
                chunker, index.embeddingBatchSize(), transformer).open();
    }

    /**
     * The store's search capabilities as the agent sees them: guarded by the store's read lock and
     * index state, so an LLM tool call can never reach Lucene directly (INV-01).
     */
    @Bean
    LockedSearchOperations knowledgeSearchOperations(LuceneIndexStore indexStore, RetrievalObservations observations) {
        return indexStore.searchOperations(observations);
    }

    /**
     * The table of contents behind the agentic section tools: derived from the provenance metadata of
     * the indexed chunks, so it always describes what can actually be searched (INV-02).
     */
    @Bean
    SectionCatalog sectionCatalog(LuceneIndexStore indexStore) {
        return new SectionCatalog(indexStore);
    }
}
