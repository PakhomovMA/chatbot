package com.personal.chatbot.observability;

import com.embabel.agent.spi.support.ExecutorAsyncer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class AsyncCorrelationTest {
    @Test
    void nestedMdcAndEmptySnapshotsRestoreTheWorker() throws Exception {
        try (var _ = RequestContext.with(RequestContext.REQUEST_ID, "outer")) {
            try (var _ = RequestContext.with(RequestContext.REQUEST_ID, "inner")) {
                assertThat(MDC.get(RequestContext.REQUEST_ID)).isEqualTo("inner");
            }
            assertThat(MDC.get(RequestContext.REQUEST_ID)).isEqualTo("outer");
        }
        ExecutionContext empty = ExecutionContext.capture();
        try (var _ = RequestContext.with(RequestContext.REQUEST_ID, "worker")) {
            empty.in(() -> {
                assertThat(MDC.get(RequestContext.REQUEST_ID)).isNull();
                return null;
            });
            assertThat(MDC.get(RequestContext.REQUEST_ID)).isEqualTo("worker");
        }
        assertThat(MDC.get(RequestContext.REQUEST_ID)).isNull();
    }

    @Test
    void twoExecutionsUseTheActualEmbabelParallelExecutorWithoutMixingDiagnosticsOrMdc() throws Exception {
        ExecutionPropagation propagation = new ExecutionPropagation();
        propagation.register();
        try (var workers = Executors.newFixedThreadPool(4); var callers = Executors.newFixedThreadPool(2)) {
            ExecutorAsyncer asyncer = new ExecutorAsyncer(workers);
            CountDownLatch arrived = new CountDownLatch(2);
            var runs = List.of("alpha", "beta").stream().map(id -> callers.submit(() -> {
                ExecutionDiagnostics diagnostics = ExecutionDiagnostics.open();
                try (var _ = RequestContext.with(RequestContext.CONVERSATION_ID, id); var _ = diagnostics.install()) {
                    return asyncer.parallelMap(List.of(1, 2), 2, part -> {
                        arrived.countDown();
                        try { assertThat(arrived.await(5, TimeUnit.SECONDS)).isTrue(); }
                        catch (InterruptedException e) { throw new RuntimeException(e); }
                        assertThat(MDC.get(RequestContext.CONVERSATION_ID)).isEqualTo(id);
                        assertThat(ExecutionDiagnostics.current()).isSameAs(diagnostics);
                        return part;
                    });
                }
            })).toList();
            for (var run : runs) assertThat(run.get(10, TimeUnit.SECONDS)).containsExactly(1, 2);
            assertThat(asyncer.async(() -> MDC.get(RequestContext.CONVERSATION_ID)).get(5, TimeUnit.SECONDS)).isNull();
            assertThat(asyncer.async(ExecutionDiagnostics::current).get(5, TimeUnit.SECONDS)).isNull();
        } finally {
            propagation.unregister();
        }
    }

    @Test
    void callbackContextCarriesRealSpanAndRestoresItEvenWhenWorkThrows() throws Exception {
        try (var provider = SdkTracerProvider.builder().build(); var workers = Executors.newSingleThreadExecutor()) {
            var tracer = provider.get("test");
            Span parent = tracer.spanBuilder("parent").startSpan();
            ExecutionContext captured;
            try (var _ = parent.makeCurrent()) { captured = ExecutionContext.capture(); }
            workers.submit(() -> {
                Span previous = tracer.spanBuilder("worker").startSpan();
                try (var _ = previous.makeCurrent()) {
                    try {
                        captured.in(() -> {
                            assertThat(Span.current().getSpanContext()).isEqualTo(parent.getSpanContext());
                            throw new IllegalStateException("expected");
                        });
                    } catch (IllegalStateException ignored) { }
                    assertThat(Span.current().getSpanContext()).isEqualTo(previous.getSpanContext());
                } finally { previous.end(); }
                assertThat(Span.current().getSpanContext().isValid()).isFalse();
            }).get(5, TimeUnit.SECONDS);
            parent.end();
        }
    }
}
