package com.personal.chatbot.controller;

import com.jayway.jsonpath.JsonPath;
import com.personal.chatbot.controller.ChatStreamControllerTest.SseEvent;
import com.personal.chatbot.exceptions.ChatCancelledException;
import com.personal.chatbot.models.agent.ChatCancellation;
import com.personal.chatbot.models.agent.GroundedAnswerDraft;
import com.personal.chatbot.models.chat.ChatRequest;
import com.personal.chatbot.models.chat.ChatStreamEvent;
import com.personal.chatbot.models.retrieval.RetrievalQuery;
import com.personal.chatbot.service.chat.ChatService;
import com.personal.chatbot.service.index.KnowledgeIndexWriter;
import com.personal.chatbot.service.retrieval.Retriever;
import com.personal.chatbot.support.AbstractChatbotIntegrationTest;
import com.personal.chatbot.support.TestDocuments;
import io.micrometer.core.instrument.MeterRegistry;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * K02 gate: the answer cache end to end with the language model mocked (docs/cache-plan.md §3.3, §3.4, §5).
 * Every test asks questions of its own, so what one test caches is never another's hit.
 */
@AutoConfigureMockMvc
class AnswerCacheChatTest extends AbstractChatbotIntegrationTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void answerCacheOn(DynamicPropertyRegistry registry) {
        registry.add("chatbot.data-dir", () -> dataDir.toString());
        registry.add("chatbot.cache.answer.enabled", () -> "true");
        // The fake embedder scores every passage below the calibrated floor. Lowered, any hit counts as
        // sufficient evidence: there is no widening second pass to stub, and a model declaring the
        // evidence insufficient can be told apart from a retrieval that found too little.
        registry.add("chatbot.retrieval.sufficient-cosine", () -> "-1.0");
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ChatService chatService;
    @Autowired
    private KnowledgeIndexWriter index;
    @Autowired
    private Retriever retriever;
    @Autowired
    private MeterRegistry meters;

    private static String documentId;

    @BeforeEach
    void indexRunbookOnce() throws Exception {
        if (documentId == null) {
            documentId = upload("runbook.md", TestDocuments.markdown());
        }
    }

    @Test
    void aFirstQuestionAskedAgainIsAnsweredWithoutTheModelAndJoinsItsOwnConversation() throws Exception {
        answers("How do I restart the payment service?", "Run `systemctl restart payments` on the host [1].");
        String first = ask(body(null, "How do I restart the payment service?", "\"includeDiagnostics\":true"));
        clearInvocations(llmOperations);
        double hits = lookups("hit");

        String repeat = ask(body(null, "  how do I restart the PAYMENT service ", "\"includeDiagnostics\":true"));

        verifyNoInteractions();
        assertThat(lookups("hit")).isEqualTo(hits + 1);
        // notes is null here and left out of the JSON (non_null inclusion), so it is not compared.
        for (String field : List.of("$.answer", "$.grounding", "$.citations", "$.retrievalTraceId")) {
            assertThat((Object) JsonPath.read(repeat, field)).as(field).isEqualTo(JsonPath.read(first, field));
        }
        assertThat((List<?>) JsonPath.read(repeat, "$.citations")).isNotEmpty();
        assertThat((String) JsonPath.read(repeat, "$.messageId")).isNotEqualTo(JsonPath.read(first, "$.messageId"));
        String conversationId = JsonPath.read(repeat, "$.conversationId");
        assertThat(conversationId).isNotEqualTo(JsonPath.read(first, "$.conversationId"));
        assertThat((Integer) JsonPath.read(repeat, "$.timings.retrievalMs")).isZero();
        assertThat((Integer) JsonPath.read(repeat, "$.timings.llmMs")).isZero();
        assertThat((String) JsonPath.read(repeat, "$.diagnostics.traceId")).isEqualTo(JsonPath.read(first, "$.retrievalTraceId"));

        mockMvc.perform(get("/api/conversations/{id}", conversationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages.length()").value(2))
                .andExpect(jsonPath("$.messages[0].content").value("how do I restart the PAYMENT service"))
                .andExpect(jsonPath("$.messages[1].content").value((String) JsonPath.read(first, "$.answer")))
                .andExpect(jsonPath("$.messages[1].citations.length()").value(((List<?>) JsonPath.read(first, "$.citations")).size()));
    }

    @Test
    void aRepeatOverTheStreamIsOneFinalEventCarryingTheResponseTheSyncCallGets() throws Exception {
        supportsStreaming(true);
        whenGenerateStream(p -> p.contains("Question: How do I roll back a deployment?"))
                .thenReturn(Flux.just("Deploy the previous image tag ", "with the deploy script [1]."));
        String body = body(null, "How do I roll back a deployment?", "");
        List<SseEvent> computed = streamChat(body);
        assertThat(computed).extracting(SseEvent::name).contains("status", "delta").endsWith("final");
        clearInvocations(llmOperations);

        List<SseEvent> repeat = streamChat(body);
        String sync = ask(body);

        verifyNoInteractions();
        assertThat(repeat).extracting(SseEvent::name).containsExactly("final");
        String computedResponse = computed.getLast().data();
        for (String field : List.of("answer", "grounding", "citations", "retrievalTraceId")) {
            assertThat((Object) JsonPath.read(repeat.getFirst().data(), "$.response." + field)).as(field)
                    .isEqualTo(JsonPath.read(computedResponse, "$.response." + field));
            assertThat((Object) JsonPath.read(sync, "$." + field)).as(field)
                    .isEqualTo(JsonPath.read(computedResponse, "$.response." + field));
        }
        assertThat((List<?>) JsonPath.read(sync, "$.citations")).isNotEmpty();
    }

    @Test
    void aChangeToTheKnowledgeBaseBetweenTwoAsksMakesTheSecondOneComputeAgain() throws Exception {
        String question = "Who approves a hotfix?";
        answers(question, "The on-call lead approves it [1].");
        ask(body(null, question, ""));
        upload("notes.md", "# Notes\n\nThe release calendar lives in the wiki.\n".getBytes(StandardCharsets.UTF_8));
        clearInvocations(llmOperations);
        double misses = lookups("miss");

        ask(body(null, question, ""));

        verify(llmOperations).createObject(any(), any(), eq(GroundedAnswerDraft.class), any(), any());
        assertThat(lookups("miss")).isEqualTo(misses + 1);
    }

    @Test
    void aChangeToTheKnowledgeBaseWhileTheAnswerIsWrittenKeepsItOutOfTheCache() throws Exception {
        String question = "Where are the payment logs?";
        AtomicBoolean changeTheIndex = new AtomicBoolean(true);
        whenCreateObject(p -> p.contains("Question: " + question), GroundedAnswerDraft.class).thenAnswer(_ -> {
            if (changeTheIndex.getAndSet(false)) {
                index.deleteDocument("no-such-document"); // any operation that may change the content moves the revision
            }
            return new GroundedAnswerDraft("In the payments log directory [1].", List.of(1), true, null);
        });
        double stale = stores("stale");
        double stored = stores("stored");

        ask(body(null, question, ""));
        assertThat(stores("stale")).isEqualTo(stale + 1);
        assertThat(stores("stored")).isEqualTo(stored);

        ask(body(null, question, ""));
        assertThat(stores("stored")).isEqualTo(stored + 1);
        verify(llmOperations, times(2)).createObject(any(), any(), eq(GroundedAnswerDraft.class), any(), any());

        clearInvocations(llmOperations);
        ask(body(null, question, ""));
        verifyNoInteractions();
    }

    /** A run that failed, was abandoned by its caller or ran out of stream time leaves nothing behind (§3.3). */
    @Test
    void nothingIsKeptFromARunThatFailedWasCancelledOrTimedOut() throws Exception {
        double decisions = stores("stored") + stores("ineligible") + stores("stale");

        String failing = "How do I rotate the payment keys?";
        whenCreateObject(p -> p.contains("Question: " + failing), GroundedAnswerDraft.class)
                .thenThrow(new IllegalStateException("model unavailable"));
        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content(body(null, failing, "")))
                .andExpect(status().is5xxServerError());

        String abandoned = "How do I drain a payment node?";
        ChatCancellation disconnect = ChatCancellation.none();
        cancelsWhileAnswering(abandoned, () -> disconnect.cancel(ChatCancellation.Cause.CLIENT_DISCONNECT, "client went away"));
        assertThatThrownBy(() -> chatService.chat(new ChatRequest(null, abandoned, null), disconnect))
                .satisfies(e -> assertThat(ChatCancelledException.isCancellation(e)).isTrue());

        String timedOut = "How do I scale the payment workers?";
        ChatCancellation deadline = ChatCancellation.none();
        cancelsWhileAnswering(timedOut, () -> deadline.cancel(ChatCancellation.Cause.TIMEOUT, "stream timed out"));
        List<ChatStreamEvent> events = new CopyOnWriteArrayList<>();
        chatService.stream(new ChatRequest(null, timedOut, null), events::add, deadline);
        assertThat(events).noneMatch(ChatStreamEvent.Final.class::isInstance);

        assertThat(stores("stored") + stores("ineligible") + stores("stale")).isEqualTo(decisions);
        double hits = lookups("hit");
        for (String question : List.of(failing, abandoned, timedOut)) {
            answers(question, "Follow the runbook [1].");
            ask(body(null, question, ""));
        }
        assertThat(lookups("hit")).isEqualTo(hits);
    }

    @Test
    void insufficientEvidenceIsKeptOnlyWhenRetrievalItselfFoundTooLittle() throws Exception {
        String question = "Which port does the payment service listen on?";
        whenCreateObject(p -> p.contains("Question: " + question), GroundedAnswerDraft.class)
                .thenReturn(new GroundedAnswerDraft("The runbook covers restarts [1] but not the port.", List.of(1), false,
                        "the listening port"));
        double ineligible = stores("ineligible");
        String declined = ask(body(null, question, ""));
        assertThat((String) JsonPath.read(declined, "$.grounding")).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(stores("ineligible")).isEqualTo(ineligible + 1);
        clearInvocations(llmOperations);
        ask(body(null, question, ""));
        verify(llmOperations).createObject(any(), any(), eq(GroundedAnswerDraft.class), any(), any());

        // Nothing retrieved at all is a fact about this revision, and the fixed reply is kept.
        String nothing = body(null, "Is there anything on the moon base?", "\"documentIds\":[\"no-such-document\"]");
        String first = ask(nothing);
        assertThat((String) JsonPath.read(first, "$.grounding")).isEqualTo("INSUFFICIENT_EVIDENCE");
        double hits = lookups("hit");
        String repeat = ask(nothing);
        assertThat(lookups("hit")).isEqualTo(hits + 1);
        assertThat((String) JsonPath.read(repeat, "$.answer")).isEqualTo(JsonPath.read(first, "$.answer"));
    }

    @Test
    void aFollowUpAndAnotherTopKOrDocumentFilterDoNotShareTheAnswer() throws Exception {
        String question = "How do I check the payment queue depth?";
        answers(question, "Look at the queue dashboard [1].");
        String first = ask(body(null, question, ""));
        String conversationId = JsonPath.read(first, "$.conversationId");
        double bypasses = lookups("bypass");
        double misses = lookups("miss");
        clearInvocations(llmOperations);

        ask(body(conversationId, question, ""));
        ask(body(null, question, "\"topK\":3"));
        ask(body(null, question, "\"documentIds\":[\"" + documentId + "\"]"));

        verify(llmOperations, times(3)).createObject(any(), any(), eq(GroundedAnswerDraft.class), any(), any());
        assertThat(lookups("bypass")).isEqualTo(bypasses + 1);
        assertThat(lookups("miss")).isEqualTo(misses + 2);
    }

    @Test
    void theRetrievalOfAServedAnswerCanBeLookedUpAfterTheTraceRingMovedOn() throws Exception {
        String question = "How do I pause payment retries?";
        answers(question, "Set the retry switch to off [1].");
        String traceId = JsonPath.read(ask(body(null, question, "")), "$.retrievalTraceId");
        for (int i = 0; i < 201; i++) {
            retriever.search(new RetrievalQuery("unrelated search " + i, 1, null, null));
        }
        mockMvc.perform(get("/api/diagnostics/retrieval/{id}", traceId)).andExpect(status().isNotFound());

        String repeat = ask(body(null, question, ""));

        assertThat((String) JsonPath.read(repeat, "$.retrievalTraceId")).isEqualTo(traceId);
        mockMvc.perform(get("/api/diagnostics/retrieval/{id}", traceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.traceId").value(traceId))
                .andExpect(jsonPath("$.hits").isNotEmpty());
    }

    private void answers(String question, String answer) {
        whenCreateObject(p -> p.contains("Question: " + question), GroundedAnswerDraft.class)
                .thenReturn(new GroundedAnswerDraft(answer, List.of(1), true, null));
    }

    /** The model answers, and the caller is gone by the time it has. */
    private void cancelsWhileAnswering(String question, Runnable cancel) {
        whenCreateObject(p -> p.contains("Question: " + question), GroundedAnswerDraft.class).thenAnswer(_ -> {
            cancel.run();
            return new GroundedAnswerDraft("Follow the runbook [1].", List.of(1), true, null);
        });
    }

    private String upload(String filename, byte[] content) throws Exception {
        MvcResult upload = mockMvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("file", filename, "text/markdown", content)))
                .andExpect(status().isAccepted()).andReturn();
        String id = JsonPath.read(upload.getResponse().getContentAsString(), "$.documentId");
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                mockMvc.perform(get("/api/documents/{id}/status", id)).andExpect(jsonPath("$.status").value("READY")));
        return id;
    }

    private static String body(@Nullable String conversationId, String message, String options) {
        return "{" + (conversationId == null ? "" : "\"conversationId\":\"" + conversationId + "\",")
                + "\"message\":\"" + message + "\",\"options\":{" + options + "}}";
    }

    private String ask(String body) throws Exception {
        return mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private List<SseEvent> streamChat(String body) throws Exception {
        MvcResult started = mockMvc.perform(post("/api/chat/stream").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(request().asyncStarted())
                .andReturn();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(started.getResponse().getContentAsString()).containsAnyOf("event:final", "event:error"));
        return ChatStreamControllerTest.parse(started.getResponse().getContentAsString());
    }

    private double lookups(String result) {
        return meters.get("chatbot.cache.lookup").tags("layer", "answer", "result", result).counter().count();
    }

    private double stores(String result) {
        return meters.get("chatbot.cache.store").tags("layer", "answer", "result", result).counter().count();
    }
}
