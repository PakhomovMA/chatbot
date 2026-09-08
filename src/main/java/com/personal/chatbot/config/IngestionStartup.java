package com.personal.chatbot.config;

import com.personal.chatbot.service.knowledge.IngestionService;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Runs registry/index reconciliation once the context is up (after the data directory exists). */
@Component
@Order(100)
class IngestionStartup implements ApplicationRunner {

    private final IngestionService ingestionService;

    IngestionStartup(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @Override
    public void run(@NonNull ApplicationArguments args) {
        ingestionService.reconcile();
    }
}
