package com.personal.chatbot.observability;

import com.personal.chatbot.models.index.IndexInfo;
import com.personal.chatbot.models.index.IndexState;
import com.personal.chatbot.service.chat.ConversationStore;
import com.personal.chatbot.service.index.IndexStatus;
import com.personal.chatbot.service.knowledge.DocumentRegistry;
import com.personal.chatbot.service.knowledge.IngestionOperations;
import com.personal.chatbot.service.knowledge.IngestionQueue;
import com.personal.chatbot.service.retrieval.RetrievalTraceStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.*;

class GaugeSnapshotTest {
    @Test
    void scrapesNeverWaitForAnIndexReadBlockedBehindIngestion() throws Exception {
        IndexStatus index = mock(IndexStatus.class);
        DocumentRegistry documents = mock(DocumentRegistry.class);
        IngestionOperations ingestion = mock(IngestionOperations.class);
        when(index.state()).thenReturn(IndexState.READY);
        when(ingestion.queueStatus()).thenReturn(new IngestionQueue.Status(0, null));
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(index.info()).thenAnswer(invocation -> {
            reading.countDown();
            release.await();
            return new IndexInfo(IndexState.READY, 10, 2, null, false, null, null, null);
        });
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            ChatbotGauges gauges = new ChatbotGauges(registry, index, documents, ingestion,
                    mock(ConversationStore.class), mock(RetrievalTraceStore.class), MonotonicClock.SYSTEM);
            try {
                assertThat(reading.await(5, TimeUnit.SECONDS)).isTrue();
                assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
                    for (int i = 0; i < 100; i++) {
                        registry.getMeters().forEach(meter -> meter.measure().forEach(measurement -> measurement.getValue()));
                    }
                });
                verify(index, times(1)).info();
                verify(documents, never()).countByStatus();
                assertThat(registry.get("chatbot.index.chunks").gauge().value()).isNaN();
            } finally {
                release.countDown();
                gauges.close();
            }
        } finally {
            registry.close();
        }
    }
}
