package com.personal.chatbot.observability;

import com.personal.chatbot.models.embedding.EmbeddingMode;
import com.personal.chatbot.models.retrieval.RetrievalMode;
import com.personal.chatbot.support.TestObservations;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O03 gate: the boundaries the remaining capabilities publish — the retrieval workflow and its
 * stages, embedding, ingestion and the SSE transport (docs/observability/metric-catalog.json).
 *
 * <p>Nothing here sleeps: one mock clock drives both the timers Micrometer records and the elapsed
 * time the facades measure. Nothing here configures tracing either — these are the meters an
 * application with no tracer, no sampling and no exporter publishes.
 */
class CapabilityLifecycleTest {

    private final TestObservations observed = TestObservations.create();
    private final SimpleMeterRegistry meters = observed.meters();

    // ---- retrieval ------------------------------------------------------------------------------

    @Test
    void aWorkflowMeasuresItsWholeBranchWhileTheLegacyProjectionStopsAtTheMerge() {
        RetrievalWorkflow workflow = observed.retrievalObservations().startWorkflow(RetrievalStrategy.DECOMPOSITION);
        observed.advance(Duration.ofMillis(700)); // splitting the question
        observed.advance(Duration.ofMillis(300)); // the passes
        workflow.merging();
        long tookMs = workflow.tookMs();
        observed.advance(Duration.ofMillis(120)); // merging and recording the trace
        workflow.succeeded();
        workflow.close();

        assertThat(tookMs).isEqualTo(1000);
        assertThat(meters.get("chatbot.retrieval.workflow").tags("strategy", "decomposition", "outcome", "success")
                .timer().totalTime(TimeUnit.MILLISECONDS)).isEqualTo(1120);
    }

    @Test
    void aWorkflowThatWasAbandonedIsNotAFailedOne() {
        try (RetrievalWorkflow workflow = observed.retrievalObservations().startWorkflow(RetrievalStrategy.EXPANSION)) {
            workflow.failed(new IllegalStateException("the model went away"), "client closed the stream");
        }
        assertThat(meters.get("chatbot.retrieval.workflow").tags("strategy", "expansion", "outcome", "cancelled")
                .timer().count()).isEqualTo(1);
    }

    @Test
    void stagesAreMeasuredPerFacetAndCarryNoExceptionInTheirLabels() {
        RetrievalObservations retrieval = observed.retrievalObservations();
        try (Measured vector = retrieval.startStage(RetrievalStage.VECTOR, RetrievalMode.HYBRID)) {
            observed.advance(Duration.ofMillis(30));
            vector.succeeded();
        }
        try (Measured text = retrieval.startStage(RetrievalStage.TEXT, RetrievalMode.HYBRID)) {
            text.failed(new IllegalStateException("the index is not readable"));
        }

        assertThat(meters.get("chatbot.retrieval.stage").tags("stage", "vector", "mode", "hybrid", "outcome", "success")
                .timer().totalTime(TimeUnit.MILLISECONDS)).isEqualTo(30);
        assertThat(meters.get("chatbot.retrieval.stage").tags("stage", "text", "outcome", "error").timer().getId()
                .getTags()).extracting(Tag::getKey).containsExactlyInAnyOrder("stage", "mode", "outcome");
        // A facet that never ran is absent, not zero: the two say different things about the search.
        assertThat(meters.find("chatbot.retrieval.stage").tag("stage", "postprocess").timer()).isNull();
    }

    // ---- embedding ------------------------------------------------------------------------------

    @Test
    void waitingForCapacityIsMeasuredApartFromTheBatchItself() {
        EmbeddingObservations embedding = observed.embeddingObservations("onnx", "embeddinggemma-300m");
        try (Measured wait = embedding.startWait(EmbeddingMode.DOCUMENT)) {
            observed.advance(Duration.ofMillis(400)); // queued behind another batch
            wait.succeeded();
        }
        try (Measured batch = embedding.startBatch(EmbeddingMode.DOCUMENT)) {
            observed.advance(Duration.ofMillis(90));
            batch.succeeded();
        }
        embedding.embedded(16);

        assertThat(meters.get("chatbot.embedding.wait").tags("mode", "document", "outcome", "success").timer()
                .totalTime(TimeUnit.MILLISECONDS)).isEqualTo(400);
        assertThat(meters.get("chatbot.embedding").tags("mode", "document", "provider", "onnx").timer()
                .totalTime(TimeUnit.MILLISECONDS)).isEqualTo(90);
        assertThat(meters.get("chatbot.embedding.texts").tag("provider", "onnx").counter().count()).isEqualTo(16);
    }

    /** The batch timer keeps the labels it had before the catalog: no outcome, and a failure is an event. */
    @Test
    void aFailedBatchIsAnEventAndDoesNotSplitTheBatchTimerByOutcome() {
        EmbeddingObservations embedding = observed.embeddingObservations("onnx", "embeddinggemma-300m");
        try (Measured batch = embedding.startBatch(EmbeddingMode.QUERY)) {
            embedding.batchFailed(EmbeddingMode.QUERY);
            batch.failed(new IllegalStateException("the backend returned nothing"));
        }
        try (Measured batch = embedding.startBatch(EmbeddingMode.QUERY)) {
            batch.succeeded();
        }

        assertThat(meters.get("chatbot.embedding").tag("mode", "query").timer().count()).isEqualTo(2);
        assertThat(meters.get("chatbot.embedding").tag("mode", "query").timer().getId().getTags())
                .extracting(Tag::getKey).containsExactlyInAnyOrder("mode", "provider", "model");
        assertThat(meters.get("chatbot.embedding.failures").tags("mode", "query", "provider", "onnx")
                .counter().count()).isEqualTo(1);
    }

    // ---- ingestion ------------------------------------------------------------------------------

    /**
     * The queue wait starts where a request is accepted and stops where it is claimed — two different
     * threads, which is why it opens no context scope: closing one thread's scope from another would
     * corrupt it (docs/observability-plan.md §7.2).
     */
    @Test
    void aRequestWaitsOnOneThreadAndIsClaimedOnAnother() throws InterruptedException {
        Measured wait = observed.ingestionObservations().startQueueWait();
        Thread worker = Thread.ofPlatform().start(() -> {
            observed.advance(Duration.ofMillis(2500));
            wait.succeeded();
            wait.close();
        });
        assertThat(worker.join(Duration.ofSeconds(20))).isTrue();

        assertThat(meters.get("chatbot.ingestion.queue.wait").tag("outcome", "success").timer()
                .totalTime(TimeUnit.MILLISECONDS)).isEqualTo(2500);
    }

    @Test
    void aRunReportsWhatBecameOfItRatherThanLeavingItToBeGuessed() {
        IngestionObservations ingestion = observed.ingestionObservations();
        for (Outcome outcome : new Outcome[]{Outcome.SUCCESS, Outcome.SKIPPED, Outcome.SUPERSEDED,
                Outcome.WAITING_INDEX, Outcome.CANCELLED, Outcome.ERROR}) {
            try (Measured processing = ingestion.startProcessing()) {
                processing.finished(outcome);
            }
        }

        assertThat(meters.find("chatbot.ingestion.processing").timers().stream().mapToLong(Timer::count).sum())
                .isEqualTo(6);
        for (String outcome : new String[]{"success", "skipped", "superseded", "waiting_index", "cancelled", "error"}) {
            assertThat(meters.get("chatbot.ingestion.processing").tag("outcome", outcome).timer().count())
                    .as("one record for %s", outcome).isEqualTo(1);
        }
    }

    /** Stages keep the mixed population they had: parse, index and total, with no outcome to split them. */
    @Test
    void ingestionStagesKeepTheirPreCatalogShape() {
        IngestionObservations ingestion = observed.ingestionObservations();
        try (Measured parse = ingestion.startStage(IngestionStage.PARSE)) {
            observed.advance(Duration.ofMillis(60));
            parse.failed(new IllegalStateException("not a pdf"));
        }
        try (Measured index = ingestion.startStage(IngestionStage.INDEX)) {
            observed.advance(Duration.ofMillis(140));
            index.succeeded();
        }
        ingestion.failed(IngestionObservations.FailedStage.of("indexing"));
        ingestion.failed(IngestionObservations.FailedStage.of("something new the exception invented"));

        assertThat(meters.get("chatbot.ingestion.stage").tag("stage", "parse").timer().getId().getTags())
                .extracting(Tag::getKey).containsExactly("stage");
        assertThat(meters.get("chatbot.ingestion.stage").tag("stage", "parse").timer()
                .totalTime(TimeUnit.MILLISECONDS)).isEqualTo(60);
        assertThat(meters.get("chatbot.ingestion.failures").tag("stage", "indexing").counter().count()).isEqualTo(1);
        // An unknown stage name is folded into the general one rather than becoming a series of its own.
        assertThat(meters.get("chatbot.ingestion.failures").tag("stage", "ingestion").counter().count()).isEqualTo(1);
    }

    // ---- server-sent events ---------------------------------------------------------------------

    @Test
    void aStreamsSendsOverflowsAndConnectionsAreItsOwn() {
        SseObservations sse = observed.sseObservations();
        Runnable closed = sse.opened("chat");
        try (SseObservations.Send _ = sse.startSend("chat")) {
            observed.advance(Duration.ofMillis(5));
        }
        sse.overflowed("chat");
        assertThat(meters.get("chatbot.sse.connections").tag("stream", "chat").gauge().value()).isEqualTo(1);

        closed.run();

        assertThat(meters.get("chatbot.sse.send").tag("stream", "chat").timer()
                .totalTime(TimeUnit.MILLISECONDS)).isEqualTo(5);
        assertThat(meters.get("chatbot.sse.overflows").tag("stream", "chat").counter().count()).isEqualTo(1);
        assertThat(meters.get("chatbot.sse.connections").tag("stream", "chat").gauge().value()).isZero();
        // Another stream is another set of meters, and there are only ever as many as the application names.
        assertThat(meters.find("chatbot.sse.send").timers()).hasSize(1);
    }
}
