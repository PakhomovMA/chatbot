package com.personal.chatbot.service.sse;

import com.personal.chatbot.config.ChatbotProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Opens {@link SseConnection}s and owns the threads that write to them. One virtual thread per
 * connection: a write that blocks on a slow client parks that thread and nothing else.
 */
@Component
public class SseConnections implements AutoCloseable {

    private final MeterRegistry meterRegistry;
    private final int bufferSize;
    private final ExecutorService senders = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("sse-sender-", 0).factory());
    private final Map<String, AtomicInteger> open = new ConcurrentHashMap<>();

    public SseConnections(MeterRegistry meterRegistry, ChatbotProperties.Sse settings) {
        this.meterRegistry = meterRegistry;
        this.bufferSize = settings.bufferSize();
    }

    /**
     * @param stream      what this connection carries; becomes the metric tag
     * @param timeout     how long the container keeps the response open
     * @param onAbandoned called with the reason when the connection ends before its work does, so the
     *                    caller can stop producing for a reader that will never see it
     */
    public SseConnection open(String stream, Duration timeout, Consumer<String> onAbandoned) {
        AtomicInteger active = active(stream);
        active.incrementAndGet();
        Counter overflows = Counter.builder("chatbot.sse.overflows").tag("stream", stream).register(meterRegistry);
        Timer sends = Timer.builder("chatbot.sse.send").tag("stream", stream).register(meterRegistry);
        SseConnection connection = new SseConnection(stream, new SseEmitter(timeout.toMillis()), bufferSize,
                onAbandoned, active::decrementAndGet, overflows, sends);
        return connection.start(senders);
    }

    public int bufferSize() {
        return bufferSize;
    }

    private AtomicInteger active(String stream) {
        return open.computeIfAbsent(stream, name -> {
            AtomicInteger count = new AtomicInteger();
            Gauge.builder("chatbot.sse.connections", count, AtomicInteger::doubleValue)
                    .tag("stream", name).description("Open server-sent-events connections").register(meterRegistry);
            return count;
        });
    }

    @Override
    public void close() {
        senders.shutdownNow();
    }
}
