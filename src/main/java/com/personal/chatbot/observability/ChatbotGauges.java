package com.personal.chatbot.observability;

import com.personal.chatbot.models.knowledge.DocumentStatus;
import com.personal.chatbot.service.chat.ConversationStore;
import com.personal.chatbot.service.index.IndexStatus;
import com.personal.chatbot.service.knowledge.DocumentRegistry;
import com.personal.chatbot.service.knowledge.IngestionOperations;
import com.personal.chatbot.service.retrieval.RetrievalTraceStore;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Point-in-time gauges for the RAG state (docs/system-plan.md D14). */
@Component
class ChatbotGauges {

    ChatbotGauges(MeterRegistry registry, IndexStatus indexStore, DocumentRegistry documents,
                  IngestionOperations ingestion, ConversationStore conversations, RetrievalTraceStore traces) {
        Gauge.builder("chatbot.index.chunks", indexStore, s -> s.info().chunkCount())
                .description("Chunks in the Lucene index").register(registry);
        Gauge.builder("chatbot.index.documents", indexStore, s -> s.info().documentCount())
                .description("Document roots in the Lucene index").register(registry);
        Gauge.builder("chatbot.index.writable", indexStore, s -> s.state().isWritable() ? 1 : 0)
                .description("1 when the index accepts writes and searches").register(registry);
        for (DocumentStatus status : DocumentStatus.values()) {
            Gauge.builder("chatbot.documents", documents, d -> d.countByStatus().getOrDefault(status, 0L))
                    .tag("status", status.name().toLowerCase())
                    .description("Documents in the registry by status").register(registry);
        }
        Gauge.builder("chatbot.ingestion.queue", ingestion, i -> i.queueStatus().pending())
                .description("Documents waiting for the ingestion worker").register(registry);
        Gauge.builder("chatbot.ingestion.active", ingestion, i -> i.queueStatus().activeDocumentId() != null ? 1 : 0)
                .description("1 while the ingestion worker is processing a document").register(registry);
        Gauge.builder("chatbot.conversations", conversations, ConversationStore::size)
                .description("Conversations held in memory").register(registry);
        Gauge.builder("chatbot.retrieval.traces", traces, RetrievalTraceStore::size)
                .description("Retrieval traces retained for diagnostics").register(registry);
    }
}
