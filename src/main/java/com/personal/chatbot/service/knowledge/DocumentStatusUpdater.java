package com.personal.chatbot.service.knowledge;

import com.personal.chatbot.models.knowledge.Document;
import com.personal.chatbot.models.knowledge.DocumentEvent;
import com.personal.chatbot.models.knowledge.dto.DocumentStatusView;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;

import java.util.function.UnaryOperator;

/**
 * The one place a document's registry entry changes during ingestion: apply the change if the
 * document still exists, persist it, and announce the new status so the SSE feed stays in step.
 */
public class DocumentStatusUpdater {

    private final DocumentRegistry registry;
    private final ApplicationEventPublisher events;

    public DocumentStatusUpdater(DocumentRegistry registry, ApplicationEventPublisher events) {
        this.registry = registry;
        this.events = events;
    }

    /** @return the saved document, or null if it was deleted in the meantime */
    public @Nullable Document transition(String documentId, UnaryOperator<Document> change) {
        Document current = registry.findById(documentId).orElse(null);
        if (current == null) {
            return null;
        }
        Document updated = registry.save(change.apply(current));
        events.publishEvent(new DocumentEvent.StatusChanged(documentId, DocumentStatusView.of(updated)));
        return updated;
    }
}
