package com.personal.chatbot.controller;

import com.personal.chatbot.config.ChatbotProperties;
import com.personal.chatbot.support.ChatSettings;
import com.personal.chatbot.exceptions.ServiceStoppingException;
import com.personal.chatbot.models.chat.ChatRequest;
import com.personal.chatbot.service.chat.ChatService;
import com.personal.chatbot.service.sse.SseConnections;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * C08/C10: what the controller does when a streaming request meets the shutdown that is already
 * under way. Nothing here needs a container — the threads and the registration are the controller's.
 */
class ChatControllerLifecycleTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final SseConnections connections = new SseConnections(meters, new ChatbotProperties.Sse(256));
    private final ChatController controller = new ChatController(mock(ChatService.class), connections, ChatSettings.defaults());
    private final ChatRequest question = new ChatRequest(null, "anything", null);

    @AfterEach
    void tearDown() {
        controller.stopAccepting();
        connections.stopAccepting();
    }

    @Test
    void refusesQuestionsOnceItStopsAcceptingThem() {
        controller.stopAccepting();

        assertThatThrownBy(() -> controller.chatStream(question)).isInstanceOf(ServiceStoppingException.class);
        assertThatThrownBy(() -> controller.chat(question)).isInstanceOf(ServiceStoppingException.class);
        assertThat(controller.awaitQuiet(Duration.ofSeconds(5))).isTrue();
    }

    /**
     * The narrow window the report is about: the threads are already gone while the door is still
     * open. Whatever the request managed to create is given back, so the shutdown that is waiting is
     * not told about work that never started.
     */
    @Test
    void aRequestThatCannotBeStartedLeavesNothingBehind() {
        controller.interruptActive(); // the executors are gone, but questions are still being taken

        assertThatThrownBy(() -> controller.chatStream(question))
                .isInstanceOf(ServiceStoppingException.class)
                .hasMessageContaining("chat");

        assertThat(controller.awaitQuiet(Duration.ofSeconds(5))).as("nothing is left counted as running").isTrue();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(
                meters.get("chatbot.sse.connections").tag("stream", "chat").gauge().value()).isZero());
    }

    @Test
    void aFailureWhileOpeningTheConnectionReleasesTheRequest() {
        SseConnections unavailable = mock(SseConnections.class);
        when(unavailable.open(anyString(), any(), any())).thenThrow(new RejectedExecutionException("stopped"));
        ChatController failing = new ChatController(mock(ChatService.class), unavailable, ChatSettings.defaults());
        try {
            assertThatThrownBy(() -> failing.chatStream(question)).isInstanceOf(ServiceStoppingException.class);
        } finally {
            failing.stopAccepting();
        }
        assertThat(failing.awaitQuiet(Duration.ofSeconds(5))).as("connection creation is part of request cleanup").isTrue();
    }
}
