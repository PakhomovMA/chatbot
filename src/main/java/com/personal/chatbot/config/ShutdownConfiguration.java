package com.personal.chatbot.config;

import com.personal.chatbot.controller.ChatController;
import com.personal.chatbot.service.knowledge.IngestionQueue;
import com.personal.chatbot.service.lifecycle.ActiveWork;
import com.personal.chatbot.service.lifecycle.ApplicationShutdown;
import com.personal.chatbot.service.lifecycle.ShutdownSequence;
import com.personal.chatbot.service.sse.SseConnections;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

/**
 * The ordered stop of everything that runs on threads of the application's own
 * (docs/concurrency-plan.md C08).
 *
 * <p>The order is the point. Chat goes first: cancelling the requests is what lets their threads,
 * and the streams they feed, end at all. The SSE connections follow, so no event stream is left open
 * when the server starts draining requests. The ingestion worker is last, because the chat requests
 * being cancelled may still be reading the index it writes to.
 *
 * <p>The waits are bounded together and stay under {@code spring.lifecycle.timeout-per-shutdown-phase},
 * so a stop that will not finish is reported instead of hanging the shutdown.
 */
@Configuration(proxyBeanMethods = false)
class ShutdownConfiguration {

    /** Time given to work that stops on its own, before it is interrupted. */
    private static final Duration GRACE = Duration.ofSeconds(20);
    /** Time given to work that had to be interrupted, before it is reported as still running. */
    private static final Duration AFTER_INTERRUPT = Duration.ofSeconds(5);

    @Bean
    ShutdownSequence shutdownSequence(ChatController chat, SseConnections connections, IngestionQueue queue) {
        List<ActiveWork> work = List.of(chat, connections, queue);
        return new ShutdownSequence(work, GRACE, AFTER_INTERRUPT);
    }

    @Bean
    ApplicationShutdown applicationShutdown(ShutdownSequence sequence) {
        return new ApplicationShutdown(sequence);
    }
}
