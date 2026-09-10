package com.personal.chatbot.observability;

import com.personal.chatbot.service.knowledge.IngestionQueue;
import com.personal.chatbot.support.TestObservations;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class IngestionCorrelationTest {
    @Test
    void latestPendingAndRerunKeepTheirOwnCauseAndRestartStartsFresh() throws Exception {
        var recorder = new TracePipelineTest.Recorder();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var done = new CountDownLatch(3);
        var calls = new AtomicInteger();
        try (var provider = SdkTracerProvider.builder().addSpanProcessor(new ExecutionAttributes())
                .addSpanProcessor(SimpleSpanProcessor.create(recorder)).build()) {
            var tracer = provider.get("test");
            var queue = new IngestionQueue(claim -> {
                var span = tracer.spanBuilder("chatbot.ingestion.processing").startSpan();
                if (calls.incrementAndGet() == 1) {
                    entered.countDown();
                    try { assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); }
                    catch (InterruptedException e) { throw new RuntimeException(e); }
                }
                span.end();
                done.countDown();
            }, List::of, TestObservations.ingestion());
            Span first = tracer.spanBuilder("first").startSpan();
            Span second = tracer.spanBuilder("second").startSpan();
            try {
                try (var _ = first.makeCurrent(); var _ = RequestContext.with(RequestContext.REQUEST_ID, "first")) {
                    queue.enqueue("doc");
                }
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                try (var _ = first.makeCurrent(); var _ = RequestContext.with(RequestContext.REQUEST_ID, "superseded")) {
                    queue.enqueue("doc");
                    queue.enqueue("pending");
                }
                try (var _ = second.makeCurrent(); var _ = RequestContext.with(RequestContext.REQUEST_ID, "second")) {
                    queue.enqueue("doc");
                    queue.enqueue("pending");
                }
                release.countDown();
                assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                release.countDown(); queue.stopAccepting(); assertThat(queue.awaitQuiet(Duration.ofSeconds(5))).isTrue();
                first.end(); second.end();
            }
            var processing = recorder.spans.stream().filter(s -> s.getName().equals("chatbot.ingestion.processing")).toList();
            assertThat(processing).hasSize(3).allSatisfy(span -> assertThat(span.getParentSpanContext().isValid()).isFalse());
            assertThat(processing.getFirst().getLinks().getFirst().getSpanContext()).isEqualTo(first.getSpanContext());
            assertThat(processing.subList(1, 3)).allSatisfy(span -> {
                assertThat(span.getLinks().getFirst().getSpanContext()).isEqualTo(second.getSpanContext());
                assertThat(span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("chatbot.request.id")))
                        .isEqualTo("second");
            });
            assertThat(MDC.get(RequestContext.REQUEST_ID)).isNull();
            IngestionEnvelope.capture().in(() -> {
                var restart = tracer.spanBuilder("chatbot.ingestion.processing").startSpan();
                restart.end(); return null;
            });
            assertThat(recorder.spans.getLast().getLinks()).isEmpty();
        }
    }
}
