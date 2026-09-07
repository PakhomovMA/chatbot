package com.personal.chatbot.controller;

import com.personal.chatbot.models.knowledge.dto.KnowledgeBaseStatus;
import com.personal.chatbot.service.knowledge.IngestionService;
import com.personal.chatbot.service.knowledge.KnowledgeBaseStatusService;
import com.personal.chatbot.service.knowledge.StatusEventBroadcaster;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/** Knowledge-base wide operations: status, full re-index, live status events (docs/system-plan.md §8). */
@RestController
@RequestMapping("/api/knowledge-base")
public class KnowledgeBaseController {

    private final KnowledgeBaseStatusService statusService;
    private final IngestionService ingestionService;
    private final StatusEventBroadcaster broadcaster;

    public KnowledgeBaseController(KnowledgeBaseStatusService statusService, IngestionService ingestionService,
                                   StatusEventBroadcaster broadcaster) {
        this.statusService = statusService;
        this.ingestionService = ingestionService;
        this.broadcaster = broadcaster;
    }

    @GetMapping("/status")
    public KnowledgeBaseStatus status() {
        return statusService.status();
    }

    /** Drops the index and re-ingests every document; returns how many were queued. */
    @PostMapping("/reindex")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Integer> reindexAll() {
        return Map.of("queued", ingestionService.reindexAll());
    }

    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events() {
        return broadcaster.subscribe();
    }
}
