package com.personal.chatbot.controller;

import com.personal.chatbot.models.retrieval.RetrievalQuery;
import com.personal.chatbot.models.retrieval.RetrievalResult;
import com.personal.chatbot.service.retrieval.RetrievalService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Retrieval playground / diagnostics endpoint (docs/system-plan.md §8): runs a search and returns scored hits. */
@RestController
@RequestMapping("/api/retrieval")
public class RetrievalController {

    private final RetrievalService retrievalService;

    public RetrievalController(RetrievalService retrievalService) {
        this.retrievalService = retrievalService;
    }

    @PostMapping("/search")
    public RetrievalResult search(@Valid @RequestBody RetrievalQuery query) {
        return retrievalService.search(query);
    }
}
