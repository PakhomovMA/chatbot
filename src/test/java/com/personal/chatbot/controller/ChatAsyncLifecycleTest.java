package com.personal.chatbot.controller;

import com.personal.chatbot.config.ChatbotProperties;
import com.personal.chatbot.models.chat.ChatRequest;
import com.personal.chatbot.service.chat.ChatService;
import com.personal.chatbot.service.sse.SseConnections;
import com.personal.chatbot.support.ChatSettings;
import com.personal.chatbot.support.TestObservations;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class ChatAsyncLifecycleTest {
    @Test
    void aRefusedSenderIsRejectedBeforeStartingTheChat() {
        var observed = TestObservations.create();
        var service = mock(ChatService.class);
        var connections = new SseConnections(observed.sseObservations(), new ChatbotProperties.Sse(16));
        var controller = new ChatController(service, connections, ChatSettings.defaults(), observed.chatObservations());
        connections.stopAccepting();
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    controller.chatStream(new ChatRequest("one", "question", null)))
                    .isInstanceOf(com.personal.chatbot.exceptions.ServiceStoppingException.class);
            assertThat(observed.meters().get("chatbot.chat.request").tag("outcome", "rejected").timer().count()).isEqualTo(1);
            assertThat(observed.meters().get("chatbot.sse.completed").tag("outcome", "rejected").counter().count()).isEqualTo(1);
            assertThat(observed.meters().get("chatbot.chat.active").gauge().value()).isZero();
            verifyNoInteractions(service);
        } finally { controller.stopAccepting(); }
    }
    @Test
    void cancellationBeforeStartProducesOneTerminalRecordAndNeverRunsTheService() throws Exception {
        var observed = TestObservations.create();
        var service = mock(ChatService.class);
        var connections = new SseConnections(observed.sseObservations(), new ChatbotProperties.Sse(16));
        var gate = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor(); var timers = Executors.newSingleThreadScheduledExecutor()) {
            executor.submit(() -> { try { gate.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } });
            var controller = new ChatController(service, connections, ChatSettings.defaults(),
                    observed.chatObservations(), executor, timers);
            try {
                controller.chatStream(new ChatRequest("one", "question", null));
                controller.stopAccepting();
                assertThat(observed.meters().get("chatbot.chat.active").gauge().value()).isZero();
                assertThat(observed.meters().get("chatbot.chat.request").tag("outcome", "cancelled").timer().count()).isEqualTo(1);
                verifyNoInteractions(service);
            } finally {
                gate.countDown();
                controller.stopAccepting();
                connections.stopAccepting();
            }
        }
    }

    @Test
    void cancellationDoesNotReleaseAnAlreadyRunningRequest() throws Exception {
        var observed = TestObservations.create();
        var service = mock(ChatService.class);
        var entered = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        doAnswer(call -> { entered.countDown(); assertThat(finish.await(5, TimeUnit.SECONDS)).isTrue(); return null; })
                .when(service).stream(any(), any(), any());
        var connections = new SseConnections(observed.sseObservations(), new ChatbotProperties.Sse(16));
        var controller = new ChatController(service, connections, ChatSettings.defaults(), observed.chatObservations());
        try {
            controller.chatStream(new ChatRequest("one", "question", null));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            controller.stopAccepting();
            assertThat(observed.meters().get("chatbot.chat.active").gauge().value()).isEqualTo(1);
            assertThat(observed.meters().find("chatbot.chat.request").timers()).isEmpty();
            finish.countDown();
            assertThat(controller.awaitQuiet(Duration.ofSeconds(5))).isTrue();
            assertThat(observed.meters().get("chatbot.chat.active").gauge().value()).isZero();
        } finally {
            finish.countDown();
            controller.stopAccepting();
            connections.stopAccepting();
        }
    }
}
