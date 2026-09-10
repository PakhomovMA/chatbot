package com.personal.chatbot.service.sse;

import com.personal.chatbot.config.ChatbotProperties;
import com.personal.chatbot.observability.SseObservations;
import com.personal.chatbot.service.lifecycle.ActiveWork;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Opens {@link SseConnection}s and owns the threads that write to them. One virtual thread per
 * connection: a write that blocks on a slow client parks that thread and nothing else.
 *
 * <p>Owning the senders makes this the {@link ActiveWork} that ends the open streams at shutdown
 * (docs/concurrency-plan.md C08). It has to happen before the server starts draining requests: an
 * event stream nobody closed would otherwise keep an async request open for the whole grace period.
 */
@Component
public class SseConnections implements ActiveWork {

    private final SseObservations observations;
    private final int bufferSize;
    private final ExecutorService senders = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("sse-sender-", 0).factory());
    /** Connections handed out, pruned as new ones are opened; the senders are the source of truth. */
    private final Set<SseConnection> open = ConcurrentHashMap.newKeySet();

    public SseConnections(SseObservations observations, ChatbotProperties.Sse settings) {
        this.observations = observations;
        this.bufferSize = settings.bufferSize();
    }

    /**
     * @param stream      what this connection carries; becomes the metric tag
     * @param timeout     how long the container keeps the response open
     * @param onAbandoned called with the reason when the connection ends before its work does, so the
     *                    caller can stop producing for a reader that will never see it
     */
    public SseConnection open(String stream, Duration timeout, Consumer<String> onAbandoned) {
        open.removeIf(connection -> !connection.isOpen());
        SseConnection connection = new SseConnection(stream, new SseEmitter(timeout.toMillis()), bufferSize,
                onAbandoned, observations.opened(stream), observations);
        open.add(connection);
        return connection.start(senders);
    }

    public int bufferSize() {
        return bufferSize;
    }

    @Override
    public String name() {
        return "sse";
    }

    /**
     * Ends every open stream and stops handing out senders. A connection opened after this cannot
     * get one: {@link SseConnection#start} sees the rejection and closes itself instead.
     */
    @Override
    public void stopAccepting() {
        open.forEach(connection -> connection.abandon("application shutdown"));
        open.clear();
        senders.shutdown();
    }

    @Override
    public boolean awaitQuiet(Duration timeout) {
        try {
            return senders.awaitTermination(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** A sender stuck writing to a socket nobody reads only lets go when it is interrupted. */
    @Override
    public void interruptActive() {
        senders.shutdownNow();
    }
}
