package com.personal.chatbot.controller;

import com.jayway.jsonpath.JsonPath;
import com.personal.chatbot.models.agent.GroundedAnswerDraft;
import com.personal.chatbot.models.agent.RewrittenQueries;
import com.personal.chatbot.support.AbstractChatbotIntegrationTest;
import com.personal.chatbot.support.TestDocuments;
import org.hamcrest.Matchers;
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

import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 9a gate: the planner inserts {@code expandSearch} between retrieval and drafting exactly when
 * the first pass found weak evidence, and the answer is then written from the merged evidence.
 *
 * <p>The sufficiency floor is set above anything the fake embedder can score, so every question here
 * takes the weak branch; {@code REWRITE} is used because it is the strategy with a model call, which
 * the mocked LLM can stand in for.
 */
@AutoConfigureMockMvc
class ExpandSearchChatTest extends AbstractChatbotIntegrationTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void weakEvidenceEverywhere(DynamicPropertyRegistry registry) {
        registry.add("chatbot.data-dir", () -> dataDir.toString());
        registry.add("chatbot.retrieval.sufficient-cosine", () -> "0.99");
        registry.add("chatbot.chat.expand-search.strategy", () -> "REWRITE");
        registry.add("chatbot.chat.expand-search.queries", () -> "2");
    }

    @Autowired
    private MockMvc mockMvc;

    private static String documentId;

    @BeforeEach
    void indexRunbookOnce() throws Exception {
        if (documentId != null) {
            return;
        }
        MvcResult upload = mockMvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("file", "runbook.md", "text/markdown", TestDocuments.markdown()))
                        .param("title", "Payments Runbook"))
                .andExpect(status().isAccepted()).andReturn();
        documentId = JsonPath.read(upload.getResponse().getContentAsString(), "$.documentId");
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                mockMvc.perform(get("/api/documents/{id}/status", documentId)).andExpect(jsonPath("$.status").value("READY")));
    }

    @Test
    void weakEvidenceIsSearchedAgainWithTheRewrittenQueriesAndTheAnswerUsesTheMergedResult() throws Exception {
        whenCreateObject(p -> p.contains("Question: How do I restart the payment service?"), RewrittenQueries.class)
                .thenReturn(new RewrittenQueries(List.of("systemctl restart payments", "restart the payments service")));
        whenCreateObject(p -> p.contains("Evidence passages:"), GroundedAnswerDraft.class)
                .thenReturn(new GroundedAnswerDraft("Run `systemctl restart payments` [1].", List.of(1), true, null));

        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"How do I restart the payment service?\",\"options\":{\"includeDiagnostics\":true}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.citations", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.diagnostics.expansion.strategy").value("REWRITE"))
                .andExpect(jsonPath("$.diagnostics.expansion.queries",
                        Matchers.contains("systemctl restart payments", "restart the payments service")))
                .andExpect(jsonPath("$.diagnostics.query").value(Matchers.containsString("How do I restart the payment service?")))
                .andExpect(jsonPath("$.diagnostics.hits", Matchers.not(Matchers.empty())));
    }

    @Test
    void aQuestionThatRetrievesNothingIsNotSearchedAgainAndCostsNoModelCall() throws Exception {
        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"anything\",\"options\":{\"documentIds\":[\"no-such-document\"],\"includeDiagnostics\":true}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grounding").value("INSUFFICIENT_EVIDENCE"))
                .andExpect(jsonPath("$.citations", Matchers.empty()));
        verify(llmOperations, never()).createObject(any(), any(), any(), any(), any());
    }
}
