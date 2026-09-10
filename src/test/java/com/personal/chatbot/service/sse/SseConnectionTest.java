package com.personal.chatbot.service.sse;

import com.personal.chatbot.observability.SseObservations;
import com.personal.chatbot.support.TestObservations;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** C06: what a connection guarantees to the ingestion worker, the agent thread and the scheduler. */
class SseConnectionTest {

    @Test
    void aSenderCancelledBeforeStartingStillReleasesItsConnectionExactlyOnce() throws Exception {
        var gate = new java.util.concurrent.CountDownLatch(1);
        var releases = new AtomicInteger();
        try (var executor = Executors.newSingleThreadExecutor()) {
            executor.submit(() -> { try { gate.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } });
            try {
                var connection = new SseConnection("test", new SlowClient(), 4, abandoned::add,
                        releases::incrementAndGet, observations).start(executor);
                connection.abandon("client left");
                connection.abandon("again");
                assertThat(releases).hasValue(1);
                assertThat(abandoned).containsExactly("client left");
            } finally { gate.countDown(); }
        }
    }

    @Test
    void senderUsesEachEventsSpanAndMessageThenCleansTheWorker() throws Exception {
        var seen = new CopyOnWriteArrayList<String>();
        try (var provider = io.opentelemetry.sdk.trace.SdkTracerProvider.builder().build();
             var executor = Executors.newSingleThreadExecutor()) {
            var span = provider.get("test").spanBuilder("chatbot.chat.request").startSpan();
            var client = new SseEmitter() {
                @Override public void send(SseEventBuilder builder) {
                    seen.add(org.slf4j.MDC.get("messageId") + ":" + io.opentelemetry.api.trace.Span.current().getSpanContext().getSpanId());
                }
            };
            var connection = new SseConnection("test", client, 4, abandoned::add, () -> { }, observations).start(executor);
            try (var _ = span.makeCurrent();
                 var _ = com.personal.chatbot.observability.RequestContext.with("messageId", "event-message")) {
                connection.send("delta", null, "text");
            }
            connection.complete();
            executor.submit(() -> {
                assertThat(org.slf4j.MDC.get("messageId")).isNull();
                assertThat(io.opentelemetry.api.trace.Span.current().getSpanContext().isValid()).isFalse();
            }).get(5, TimeUnit.SECONDS);
            assertThat(seen).containsExactly("event-message:" + span.getSpanContext().getSpanId());
            span.end();
        }
    }

    private final TestObservations observed = TestObservations.create();
    private final SseObservations observations = observed.sseObservations();
    private final SimpleMeterRegistry meters = observed.meters();
    private final ExecutorService senders = Executors.newCachedThreadPool();
    private final List<String> abandoned = new CopyOnWriteArrayList<>();
    private final List<Runnable> finished = new CopyOnWriteArrayList<>();

    /** Stands in for a browser: records what was written and can be held mid-write, like a client that stopped reading. */
    private static final class SlowClient extends SseEmitter {

        private final List<String> written = new CopyOnWriteArrayList<>();
        private final Semaphore reading = new Semaphore(Integer.MAX_VALUE);
        /** Writes started, including the one a client that stopped reading is holding up. */
        private final AtomicInteger started = new AtomicInteger();
        private volatile boolean completed;

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            started.incrementAndGet();
            try {
                if (!reading.tryAcquire(20, TimeUnit.SECONDS)) {
                    throw new IOException("test client never read");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
            written.add(builder.build().stream().map(data -> String.valueOf(data.getData())).collect(Collectors.joining()));
        }

        @Override
        public void complete() {
            completed = true;
        }

        void stopReading() {
            reading.drainPermits();
        }

        void readAgain() {
            reading.release(Integer.MAX_VALUE);
        }
    }

    private SseConnection connect(SlowClient client, int bufferSize) {
        return new SseConnection("test", client, bufferSize, abandoned::add, () -> finished.add(() -> { }),
                observations).start(senders);
    }

    @AfterEach
    void tearDown() {
        senders.shutdownNow();
    }

    @Test
    void deliversInOrderAndCompletesOnlyOnceTheQueueHasDrained() {
        SlowClient client = new SlowClient();
        SseConnection connection = connect(client, 16);

        connection.send("status", null, "one");
        connection.send("delta", null, "two");
        connection.send("final", null, "three");
        connection.complete();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(client.completed).isTrue());
        assertThat(client.written).hasSize(3);
        assertThat(String.join("|", client.written)).contains("one").contains("two").contains("three");
        assertThat(client.written.getFirst()).contains("one");
        assertThat(client.written.getLast()).contains("three");
        assertThat(abandoned).isEmpty();
    }

    @Test
    void aClientThatStoppedReadingDoesNotHoldUpTheProducer() {
        SlowClient client = new SlowClient();
        client.stopReading();
        SseConnection connection = connect(client, 64);

        long started = System.nanoTime();
        for (int i = 0; i < 32; i++) {
            connection.send("delta", null, "fragment " + i);
        }
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertThat(elapsedMs).as("producing must not wait for the network").isLessThan(1_000);
        assertThat(connection.isOpen()).isTrue();
        client.readAgain();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(client.written).hasSize(32));
    }

    @Test
    void aConnectionThatFallsTooFarBehindIsDroppedAndItsWorkCancelled() {
        SlowClient client = new SlowClient();
        client.stopReading();
        SseConnection connection = connect(client, 2);

        for (int i = 0; i < 8; i++) {
            connection.send("delta", null, "fragment " + i);
        }

        assertThat(abandoned).containsExactly("send buffer full");
        assertThat(connection.isOpen()).isFalse();
        assertThat(meters.get("chatbot.sse.overflows").tag("stream", "test").counter().count()).isEqualTo(1);
        // Nothing more is queued for a connection that is gone.
        connection.send("final", null, "late");
        assertThat(abandoned).hasSize(1);
    }

    /**
     * C10: ending the stream is not an event that has to fit in the buffer. A client that is exactly
     * one event behind used to lose the answer it was waiting for, because completing overflowed the
     * queue and dropped everything in it.
     */
    @Test
    void completingAFullBufferStillDeliversWhatIsInIt() {
        SlowClient client = new SlowClient();
        client.stopReading();
        SseConnection connection = connect(client, 2);
        // One event is with the sender, two fill the buffer: nothing more fits.
        connection.send("status", null, "one");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(client.started).hasValue(1));
        connection.send("delta", null, "two");
        connection.send("final", null, "three");

        connection.complete();
        connection.complete(); // idempotent

        assertThat(abandoned).isEmpty();
        assertThat(meters.get("chatbot.sse.overflows").tag("stream", "test").counter().count()).isZero();
        client.readAgain();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(client.completed).isTrue());
        assertThat(client.written).hasSize(3);
        assertThat(client.written.getLast()).contains("three");
    }

    @Test
    void heartbeatsDoNotPileUpBehindAClientThatIsNotReading() {
        SlowClient client = new SlowClient();
        client.stopReading();
        SseConnection connection = connect(client, 2);

        for (int i = 0; i < 100; i++) {
            connection.heartbeat();
        }

        assertThat(abandoned).as("a pending heartbeat is not queued again").isEmpty();
        assertThat(connection.isOpen()).isTrue();
    }
}
