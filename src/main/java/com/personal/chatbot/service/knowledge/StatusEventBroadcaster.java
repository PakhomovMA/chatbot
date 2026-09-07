package com.personal.chatbot.service.knowledge;

import com.personal.chatbot.models.knowledge.DocumentEvent;
import com.personal.chatbot.service.sse.SseConnection;
import com.personal.chatbot.service.sse.SseConnections;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Fans document status changes out to SSE subscribers of {@code GET /api/knowledge-base/events}. */
@Component
public class StatusEventBroadcaster {

    static final Duration TIMEOUT = Duration.ofMinutes(30);
    static final String EVENT_NAME = "document-status";

    private final SseConnections connections;
    private final List<SseConnection> subscribers = new CopyOnWriteArrayList<>();

    public StatusEventBroadcaster(SseConnections connections) {
        this.connections = connections;
    }

    public SseEmitter subscribe() {
        forgetClosed();
        // Nothing to cancel behind this stream: it reports on work that was not started for its sake.
        SseConnection connection = connections.open("knowledge", TIMEOUT, _ -> forgetClosed());
        subscribers.add(connection);
        connection.comment("connected");
        return connection.emitter();
    }

    /**
     * Runs on the ingestion worker. Events are queued per connection and written by that connection's
     * own sender, so a browser tab that stopped reading cannot hold ingestion up.
     */
    @EventListener
    public void on(DocumentEvent.StatusChanged event) {
        forgetClosed();
        for (SseConnection connection : subscribers) {
            connection.send(EVENT_NAME, event.documentId(), event.view());
        }
    }

    public int subscriberCount() {
        forgetClosed();
        return subscribers.size();
    }

    private void forgetClosed() {
        subscribers.removeIf(connection -> !connection.isOpen());
    }
}
