package com.personal.chatbot.service.knowledge;

import com.personal.chatbot.models.index.IndexInfo;
import com.personal.chatbot.models.knowledge.dto.KnowledgeBaseStatus;
import com.personal.chatbot.service.embedding.KnowledgeEmbeddingService;
import com.personal.chatbot.service.index.IndexStatus;
import org.springframework.stereotype.Service;

/** Composes registry, index and queue state into the admin-facing {@link KnowledgeBaseStatus}. */
@Service
public class KnowledgeBaseStatusService {

    private final DocumentRegistry registry;
    private final IndexStatus indexStore;
    private final IngestionOperations ingestionService;
    private final KnowledgeEmbeddingService embeddingService;

    public KnowledgeBaseStatusService(DocumentRegistry registry, IndexStatus indexStore,
                                      IngestionOperations ingestionService, KnowledgeEmbeddingService embeddingService) {
        this.registry = registry;
        this.indexStore = indexStore;
        this.ingestionService = ingestionService;
        this.embeddingService = embeddingService;
    }

    public KnowledgeBaseStatus status() {
        IndexInfo index = indexStore.info();
        IngestionQueue.Status queue = ingestionService.queueStatus();
        return new KnowledgeBaseStatus(
                registry.count(),
                registry.countByStatus(),
                new KnowledgeBaseStatus.IndexSummary(index.state(), index.chunkCount(), index.documentCount(),
                        index.indexPath(), index.persistent(), index.incompatibilityReason(), index.recoveredFrom()),
                new KnowledgeBaseStatus.QueueSummary(queue.pending(), queue.activeDocumentId()),
                new KnowledgeBaseStatus.EmbeddingInfo(embeddingService.provider(), embeddingService.modelName(),
                        embeddingService.dimensions(), embeddingService.fingerprint().value()),
                ingestionService.recentFailures().stream()
                        .map(f -> new KnowledgeBaseStatus.RecentFailure(f.documentId(), f.stage(), f.message(), f.at()))
                        .toList());
    }
}
