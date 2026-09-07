package com.personal.chatbot.config;

import com.personal.chatbot.service.index.LuceneIndexStore;
import com.personal.chatbot.service.knowledge.DocumentIngestionPipeline;
import com.personal.chatbot.service.knowledge.DocumentRegistry;
import com.personal.chatbot.service.knowledge.DocumentStatusUpdater;
import com.personal.chatbot.service.knowledge.BlobStore;
import com.personal.chatbot.service.knowledge.IndexReconciler;
import com.personal.chatbot.service.knowledge.IngestionFailureLog;
import com.personal.chatbot.service.knowledge.IngestionQueue;
import com.personal.chatbot.service.parsing.DocumentParser;
import io.micrometer.core.instrument.MeterRegistry;
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
                                                        Clock clock, MeterRegistry meterRegistry) {
        return new DocumentIngestionPipeline(registry, blobStore, parser, indexStore, status, failures, clock, meterRegistry);
    }

    @Bean(destroyMethod = "close")
    IngestionQueue ingestionQueue(DocumentIngestionPipeline pipeline) {
        return new IngestionQueue(pipeline::process);
    }

    @Bean
    IndexReconciler indexReconciler(DocumentRegistry registry, LuceneIndexStore indexStore, DocumentStatusUpdater status,
                                    IngestionQueue queue, ChatbotProperties properties, Clock clock) {
        return new IndexReconciler(registry, indexStore, status, queue, properties.ingestion(), clock);
    }
}
