package com.personal.chatbot.controller;

import com.personal.chatbot.exceptions.ChatCancelledException;
import com.personal.chatbot.models.agent.ChatCancellation;
import com.personal.chatbot.models.agent.GroundedAnswerDraft;
import com.personal.chatbot.models.agent.SubQuestions;
import com.personal.chatbot.models.chat.ChatRequest;
import com.personal.chatbot.models.chat.ChatStreamEvent;
import com.personal.chatbot.models.knowledge.DocumentStatus;
import com.personal.chatbot.service.chat.ChatService;
import com.personal.chatbot.service.knowledge.DocumentRegistry;
import com.personal.chatbot.service.knowledge.DocumentService;
import com.personal.chatbot.support.AbstractChatbotIntegrationTest;
import com.personal.chatbot.support.TestDocuments;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DerivationCacheChatTest extends AbstractChatbotIntegrationTest {
    @TempDir static Path dataDir;

    @DynamicPropertySource
    static void cachesOn(DynamicPropertyRegistry registry) {
        registry.add("chatbot.data-dir", () -> dataDir.toString());
        registry.add("chatbot.cache.answer.enabled", () -> "true");
        registry.add("chatbot.cache.derivation.enabled", () -> "true");
        registry.add("chatbot.chat.decompose.enabled", () -> "true");
        registry.add("chatbot.retrieval.sufficient-cosine", () -> "-1.0");
    }

    @Autowired ChatService chat;
    @Autowired DocumentService documents;
    @Autowired DocumentRegistry registry;
    private static boolean indexed;

    @BeforeEach
    void corpus() throws Exception {
        if (!indexed) {
            upload("runbook.md");
            indexed = true;
        }
    }

    private void upload(String name) throws Exception {
        byte[] bytes = (new String(TestDocuments.markdown(), java.nio.charset.StandardCharsets.UTF_8)
                + "\n\n## Appendix\n" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        documents.upload(new DocumentService.Upload(name, "text/markdown", bytes.length, new ByteArrayInputStream(bytes), name));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(registry.findAll()).isNotEmpty().allMatch(d -> d.status() == DocumentStatus.READY));
    }

    private void split() {
        whenCreateObject(_ -> true, SubQuestions.class)
                .thenReturn(new SubQuestions(List.of("restart payment service", "roll back release")));
    }

    private void draft() {
        whenCreateObject(_ -> true, GroundedAnswerDraft.class)
                .thenReturn(new GroundedAnswerDraft("Follow the payment runbook [1].", List.of(1), true, null));
    }

    @Test
    void kbMutationInvalidatesTheAnswerButKeepsDerivationsAndRetrievesFreshEvidence() throws Exception {
        split();
        draft();
        var request = new ChatRequest(null, "How do I restart payments and roll back a release?", null);
        var first = chat.chat(request);
        assertThat(first.citations()).isNotEmpty();
        var hit = chat.chat(request);
        assertThat(hit.retrievalTraceId()).isEqualTo(first.retrievalTraceId());
        verify(llmOperations, times(1)).createObject(any(), any(), eq(SubQuestions.class), any(), any());
        verify(llmOperations, times(1)).createObject(any(), any(), eq(GroundedAnswerDraft.class), any(), any());

        upload("additional-runbook.md");
        clearInvocations(llmOperations);
        List<ChatStreamEvent> events = new ArrayList<>();
        chat.stream(request, events::add);
        verify(llmOperations, never()).createObject(any(), any(), eq(SubQuestions.class), any(), any());
        verify(llmOperations).createObject(any(), any(), eq(GroundedAnswerDraft.class), any(), any());
        assertThat(events).filteredOn(ChatStreamEvent.Final.class::isInstance).hasSize(1);
        var fresh = ((ChatStreamEvent.Final) events.getLast()).response();
        assertThat(fresh.retrievalTraceId()).isNotEqualTo(first.retrievalTraceId());
        assertThat(fresh.citations()).isNotEmpty();
        assertThat(events).noneMatch(e -> e instanceof ChatStreamEvent.Status s && s.stage().equals("decomposing"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"error", "cancel", "timeout"})
    void aSuccessfulDerivationIsDiscardedWhenTheAnswerRunDoesNotFinish(String failure) {
        split();
        var request = new ChatRequest(null, "How do I restart payments and roll back a release after " + failure + "?", null);
        var cancellation = ChatCancellation.none();
        whenCreateObject(_ -> true, GroundedAnswerDraft.class).thenAnswer(_ -> {
            if (failure.equals("error")) throw new IllegalStateException("model unavailable");
            cancellation.cancel(failure.equals("timeout") ? ChatCancellation.Cause.TIMEOUT : ChatCancellation.Cause.CLIENT_DISCONNECT,
                    "test cancellation");
            return new GroundedAnswerDraft("Follow the runbook [1].", List.of(1), true, null);
        });
        assertThatThrownBy(() -> chat.chat(request, cancellation)).satisfies(e -> {
            if (!failure.equals("error")) assertThat(ChatCancelledException.isCancellation(e)).isTrue();
        });
        draft();
        chat.chat(request);
        verify(llmOperations, times(2)).createObject(any(), any(), eq(SubQuestions.class), any(), any());
    }
}
