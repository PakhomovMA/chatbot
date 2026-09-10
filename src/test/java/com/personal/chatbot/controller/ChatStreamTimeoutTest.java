package com.personal.chatbot.controller;

import com.jayway.jsonpath.JsonPath;
import com.personal.chatbot.models.agent.GroundedAnswerDraft;
import com.personal.chatbot.support.AbstractChatbotIntegrationTest;
import com.personal.chatbot.support.TestDocuments;
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

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The server's own deadline for a streaming answer (docs/eval-log.md, 2026-09-10). It used to end the
 * request the same way a disconnect does — silently — and a client that was still waiting got a
 * stream that simply stopped: no {@code final}, no {@code error}, nothing to show the user.
 */
@AutoConfigureMockMvc
class ChatStreamTimeoutTest extends AbstractChatbotIntegrationTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void shortDeadline(DynamicPropertyRegistry registry) {
        registry.add("chatbot.data-dir", () -> dataDir.toString());
        registry.add("chatbot.chat.stream-timeout", () -> "1s");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private io.micrometer.core.instrument.MeterRegistry meters;

    /** Something has to be retrievable, or the answer is written without ever calling the model. */
    @BeforeEach
    void indexRunbook() throws Exception {
        MvcResult upload = mockMvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("file", "runbook.md", "text/markdown", TestDocuments.markdown())))
                .andExpect(status().isAccepted()).andReturn();
        String documentId = JsonPath.read(upload.getResponse().getContentAsString(), "$.documentId");
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                mockMvc.perform(get("/api/documents/{id}/status", documentId)).andExpect(jsonPath("$.status").value("READY")));
    }

    @Test
    void aRequestThatOutlivesTheDeadlineIsToldSoInsteadOfGoingQuiet() throws Exception {
        CountDownLatch cancelled = new CountDownLatch(1);
        // A model that never answers: the run can only end at the deadline.
        whenCreateObject(_ -> true, GroundedAnswerDraft.class).thenAnswer(_ -> {
            assertThat(cancelled.await(30, TimeUnit.SECONDS)).as("released by the deadline").isTrue();
            return new GroundedAnswerDraft("too late", List.of(), true, null);
        });

        MvcResult started = mockMvc.perform(post("/api/chat/stream").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"A question nothing will answer\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        try {
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                    assertThat(started.getResponse().getContentAsString()).contains("event:error"));
        } finally {
            cancelled.countDown();
        }

        List<ChatStreamControllerTest.SseEvent> events =
                ChatStreamControllerTest.parse(started.getResponse().getContentAsString());
        assertThat(events).extracting(ChatStreamControllerTest.SseEvent::name).doesNotContain("final").contains("error");
        assertThat((String) JsonPath.read(events.getLast().data(), "$.message")).isEqualTo(ChatController.TIMED_OUT);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(meters.get("chatbot.chat.active").gauge().value()).isZero();
            assertThat(meters.get("chatbot.chat.request.active").longTaskTimers())
                    .isNotEmpty().allSatisfy(timer -> assertThat(timer.activeTasks()).isZero());
            assertThat(meters.get("chatbot.ai.operation.active").longTaskTimers())
                    .isNotEmpty().allSatisfy(timer -> assertThat(timer.activeTasks()).isZero());
        });
    }
}
