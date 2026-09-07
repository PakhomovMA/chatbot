package com.personal.chatbot.service.sse;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
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
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** C06: what a connection guarantees to the ingestion worker, the agent thread and the scheduler. */
class SseConnectionTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ExecutorService senders = Executors.newCachedThreadPool();
    private final List<String> abandoned = new CopyOnWriteArrayList<>();
    private final List<Runnable> finished = new CopyOnWriteArrayList<>();

    /** Stands in for a browser: records what was written and can be held mid-write, like a client that stopped reading. */
    private static final class SlowClient extends SseEmitter {

        private final List<String> written = new CopyOnWriteArrayList<>();
        private final Semaphore reading = new Semaphore(Integer.MAX_VALUE);
        private volatile boolean completed;

        @Override
        public void send(SseEventBuilder builder) throws IOException {
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
                Counter.builder("test.overflows").register(meters), Timer.builder("test.send").register(meters))
                .start(senders);
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
        assertThat(meters.get("test.overflows").counter().count()).isEqualTo(1);
        // Nothing more is queued for a connection that is gone.
        connection.send("final", null, "late");
        assertThat(abandoned).hasSize(1);
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
