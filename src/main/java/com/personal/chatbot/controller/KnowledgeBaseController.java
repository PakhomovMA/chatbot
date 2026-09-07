package com.personal.chatbot.controller;

import com.personal.chatbot.models.knowledge.dto.KnowledgeBaseStatus;
import com.personal.chatbot.service.knowledge.DocumentService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Knowledge-base wide operations; re-index and live status events arrive in Phase 3. */
@RestController
@RequestMapping("/api/knowledge-base")
public class KnowledgeBaseController {

    private final DocumentService documentService;

    public KnowledgeBaseController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @GetMapping("/status")
    public KnowledgeBaseStatus status() {
        return documentService.knowledgeBaseStatus();
    }
}
