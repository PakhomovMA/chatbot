package com.personal.chatbot.service.knowledge;

import com.personal.chatbot.models.knowledge.DocumentEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Fans document status changes out to SSE subscribers of {@code GET /api/knowledge-base/events}. */
@Component
public class StatusEventBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(StatusEventBroadcaster.class);
    static final Duration TIMEOUT = Duration.ofMinutes(30);
    static final String EVENT_NAME = "document-status";

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(TIMEOUT.toMillis());
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(_ -> emitters.remove(emitter));
        emitters.add(emitter);
        try {
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (IOException e) {
            emitters.remove(emitter);
        }
        return emitter;
    }

    @EventListener
    public void on(DocumentEvent.StatusChanged event) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(EVENT_NAME).id(event.documentId()).data(event.view()));
            } catch (IOException | IllegalStateException e) {
                log.debug("Dropping SSE subscriber: {}", e.toString());
                emitters.remove(emitter);
            }
        }
    }

    public int subscriberCount() {
        return emitters.size();
    }
}
