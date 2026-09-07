package com.personal.chatbot.service.knowledge;

import com.personal.chatbot.models.knowledge.Document;
import com.personal.chatbot.models.knowledge.DocumentEvent;
import com.personal.chatbot.models.knowledge.dto.DocumentStatusView;
import com.personal.chatbot.service.knowledge.DocumentRegistry.Change;
import org.springframework.context.ApplicationEventPublisher;

import java.util.function.UnaryOperator;

/**
 * The one place a document's registry entry changes during ingestion: apply the change if the
 * document is still there (and still at the expected version), persist it, and announce the new
 * status so the SSE feed stays in step. The event is published after the registry lock is released
 * and only when the change actually landed, so nothing announces a state the registry never held.
 */
public class DocumentStatusUpdater {

    private final DocumentRegistry registry;
    private final ApplicationEventPublisher events;

    public DocumentStatusUpdater(DocumentRegistry registry, ApplicationEventPublisher events) {
        this.registry = registry;
        this.events = events;
    }

    /** Applies {@code change} to whatever version the registry currently holds, and announces it. */
    public Change transition(String documentId, UnaryOperator<Document> change) {
        Change applied = registry.update(documentId, change);
        announce(applied);
        return applied;
    }

    /** Applies {@code change} only while the document is still at {@code expectedVersion}, and announces it. */
    public Change transition(String documentId, int expectedVersion, UnaryOperator<Document> change) {
        Change applied = apply(documentId, expectedVersion, change);
        announce(applied);
        return applied;
    }

    /**
     * Applies a versioned change without announcing it, for a caller that has to decide and write
     * under a lock of its own. Announcing sends SSE traffic, which must not happen under that lock:
     * pair this with {@link #announce} once it is released.
     */
    public Change apply(String documentId, int expectedVersion, UnaryOperator<Document> change) {
        return registry.update(documentId, expectedVersion, change);
    }

    /** Announces the new status of a change that landed; a no-op for one that did not. */
    public void announce(Change change) {
        if (change.applied() instanceof Document updated) {
            events.publishEvent(new DocumentEvent.StatusChanged(updated.id(), DocumentStatusView.of(updated)));
        }
    }
}
