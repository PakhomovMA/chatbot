package com.personal.chatbot.observability;

import com.personal.chatbot.models.knowledge.DocumentStatus;
import com.personal.chatbot.models.index.IndexState;
import com.personal.chatbot.service.chat.ConversationStore;
import com.personal.chatbot.service.index.IndexStatus;
import com.personal.chatbot.service.knowledge.DocumentRegistry;
import com.personal.chatbot.service.knowledge.IngestionOperations;
import com.personal.chatbot.service.retrieval.RetrievalTraceStore;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Point-in-time gauges for the RAG state (docs/system-plan.md D14). */
@Component
class ChatbotGauges {

    private record Counts(double chunks, double documents, Map<DocumentStatus, Long> statuses, long refreshed) {}

    private volatile Counts counts;
    private final MonotonicClock clock;
    private final ScheduledExecutorService snapshots = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon(true).name("metrics-snapshot").factory());

    ChatbotGauges(MeterRegistry registry, IndexStatus indexStore, DocumentRegistry documents,
                  IngestionOperations ingestion, ConversationStore conversations, RetrievalTraceStore traces,
                  MonotonicClock clock) {
        this.clock = clock;
        this.counts = new Counts(Double.NaN, Double.NaN, Map.of(), clock.nanoTime());
        Gauge.builder("chatbot.index.chunks", this, s -> s.counts.chunks())
                .description("Chunks in the Lucene index").register(registry);
        Gauge.builder("chatbot.index.documents", this, s -> s.counts.documents())
                .description("Document roots in the Lucene index").register(registry);
        Gauge.builder("chatbot.index.writable", indexStore, s -> s.state().isWritable() ? 1 : 0)
                .description("1 when the index accepts writes and searches").register(registry);
        Gauge.builder("chatbot.index.maintenance", indexStore, s -> s.state() == IndexState.REBUILDING ? 1 : 0)
                .description("1 during an index rebuild").register(registry);
        Gauge.builder("chatbot.metrics.snapshot.age", this,
                        s -> s.clock.since(s.counts.refreshed()).toNanos() / 1e9)
                .baseUnit("seconds").description("Age of the background index and document counts").register(registry);
        for (DocumentStatus status : DocumentStatus.values()) {
            Gauge.builder("chatbot.documents", this,
                            s -> Double.isNaN(s.counts.chunks()) ? Double.NaN : s.counts.statuses().getOrDefault(status, 0L))
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
        // A slow index read may wait for ingestion here, but never on a Prometheus scrape thread.
        snapshots.scheduleWithFixedDelay(() -> refresh(indexStore, documents), 0, 5, TimeUnit.SECONDS);
    }

    void refresh(IndexStatus index, DocumentRegistry documents) {
        try {
            var info = index.info();
            counts = new Counts(info.chunkCount(), info.documentCount(), Map.copyOf(documents.countByStatus()),
                    clock.nanoTime());
        } catch (RuntimeException ignored) {
            // Keep the last successful snapshot; its increasing age exposes a stalled reader.
        }
    }

    @PreDestroy
    void close() {
        snapshots.shutdownNow();
    }
}
