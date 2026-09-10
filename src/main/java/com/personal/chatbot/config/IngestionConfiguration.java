package com.personal.chatbot.config;

import com.personal.chatbot.models.knowledge.Document;
import com.personal.chatbot.service.index.LuceneIndexStore;
import com.personal.chatbot.service.knowledge.DocumentIngestionPipeline;
import com.personal.chatbot.service.knowledge.DocumentRegistry;
import com.personal.chatbot.service.knowledge.DocumentStatusUpdater;
import com.personal.chatbot.service.knowledge.BlobStore;
import com.personal.chatbot.service.knowledge.IndexReconciler;
import com.personal.chatbot.service.knowledge.IngestionFailureLog;
import com.personal.chatbot.service.knowledge.IngestionQueue;
import com.personal.chatbot.observability.IngestionObservations;
import com.personal.chatbot.service.parsing.DocumentParser;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * The ingestion pipeline and the single-writer queue in front of it (docs/system-plan.md §5).
 * The queue is wired last because it drives the pipeline, not the other way round.
 */
@Configuration(proxyBeanMethods = false)
class IngestionConfiguration {

    @Bean
    DocumentStatusUpdater documentStatusUpdater(DocumentRegistry registry, ApplicationEventPublisher events) {
        return new DocumentStatusUpdater(registry, events);
    }

    @Bean
    IngestionFailureLog ingestionFailureLog() {
        return new IngestionFailureLog();
    }

    @Bean
    DocumentIngestionPipeline documentIngestionPipeline(DocumentRegistry registry, BlobStore blobStore,
                                                        DocumentParser parser, LuceneIndexStore indexStore,
                                                        DocumentStatusUpdater status, IngestionFailureLog failures,
                                                        Clock clock, IngestionObservations observations) {
        return new DocumentIngestionPipeline(registry, blobStore, parser, indexStore, status, failures, clock, observations);
    }

    /**
     * The rebuild runs as a command of this queue rather than beside it, so it cannot delete the
     * result of a document still being ingested (docs/concurrency-plan.md C03). No destroy method:
     * the worker is stopped by the shutdown sequence, before anything it writes to is closed (C08).
     */
    @Bean
    IngestionQueue ingestionQueue(DocumentIngestionPipeline pipeline, LuceneIndexStore indexStore,
                                  DocumentRegistry registry, IngestionObservations observations) {
        return new IngestionQueue(pipeline::process, () -> {
            indexStore.rebuild();
            return registry.findAll().stream().map(Document::id).toList();
        }, observations);
    }

    @Bean
    IndexReconciler indexReconciler(DocumentRegistry registry, LuceneIndexStore indexStore, DocumentStatusUpdater status,
                                    IngestionQueue queue, ChatbotProperties.Ingestion settings, Clock clock) {
        return new IndexReconciler(registry, indexStore, status, queue, settings, clock);
    }
}
