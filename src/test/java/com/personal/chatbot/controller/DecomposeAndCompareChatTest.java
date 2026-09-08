package com.personal.chatbot.controller;

import com.jayway.jsonpath.JsonPath;
import com.personal.chatbot.models.agent.GroundedAnswerDraft;
import com.personal.chatbot.models.agent.RewrittenQueries;
import com.personal.chatbot.models.agent.SourceComparison;
import com.personal.chatbot.models.agent.SubQuestions;
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

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;

import static org.awaitility.Awaitility.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 9d gate: the planner sends a question that asks for several things through
 * {@code decomposeQuestion}, has {@code compareSources} relate the documents before drafting when the
 * question compares them, and leaves an ordinary question with the plan it had before this phase.
 */
@AutoConfigureMockMvc
class DecomposeAndCompareChatTest extends AbstractChatbotIntegrationTest {

    private static final String DEPLOYMENT_GUIDE = """
            # Deployment Guide

            ## Canary rollback

            The canary sends five percent of production traffic to the new version for thirty minutes.
            An aborted canary rolls back on its own and opens an incident of severity SEV-3.
            A canary rollback takes about two minutes and needs no operator.
            """;

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void bothBranchesOn(DynamicPropertyRegistry registry) {
        registry.add("chatbot.data-dir", () -> dataDir.toString());
        registry.add("chatbot.chat.decompose.enabled", () -> "true");
        registry.add("chatbot.chat.compare-sources.enabled", () -> "true");
        // Exercise the interaction with 9a: a valid split prevents widening, an unusable one permits it.
        registry.add("chatbot.chat.expand-search.strategy", () -> "REWRITE");
        registry.add("chatbot.retrieval.sufficient-cosine", () -> "0.99");
    }

    @Autowired
    private MockMvc mockMvc;

    private static boolean indexed;

    @BeforeEach
    void indexTwoDocumentsOnce() throws Exception {
        if (indexed) {
            return;
        }
        upload("runbook.md", "Payments Runbook", TestDocuments.markdown());
        upload("deployment.md", "Deployment Guide", DEPLOYMENT_GUIDE.getBytes(StandardCharsets.UTF_8));
        indexed = true;
    }

    private void upload(String filename, String title, byte[] content) throws Exception {
        MvcResult upload = mockMvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("file", filename, "text/markdown", content))
                        .param("title", title))
                .andExpect(status().isAccepted()).andReturn();
        String documentId = JsonPath.read(upload.getResponse().getContentAsString(), "$.documentId");
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                mockMvc.perform(get("/api/documents/{id}/status", documentId)).andExpect(jsonPath("$.status").value("READY")));
    }

    @Test
    void aQuestionWithSeveralPartsIsSearchedPerPartAndAnsweredFromTheMergedEvidence() throws Exception {
        String question = "How do I restart the payment service and how do I roll back a release?";
        whenCreateObject(p -> p.equals("Question: " + question), SubQuestions.class)
                .thenReturn(new SubQuestions(List.of("restart the payment service", "roll back a release")));
        whenCreateObject(p -> p.contains("Evidence passages:"), GroundedAnswerDraft.class)
                .thenReturn(new GroundedAnswerDraft("Restart with `systemctl restart payments` [1], roll back with the deploy script [2].",
                        List.of(1, 2), true, null));

        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"" + question + "\",\"options\":{\"includeDiagnostics\":true}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.citations", Matchers.hasSize(2)))
                .andExpect(jsonPath("$.diagnostics.decomposition.subQuestions",
                        Matchers.contains("restart the payment service", "roll back a release")))
                .andExpect(jsonPath("$.diagnostics.query").value(Matchers.containsString(question)))
                .andExpect(jsonPath("$.diagnostics.hits", Matchers.not(Matchers.empty())));
        // The question does not ask how the documents relate, so nothing is compared.
        verify(llmOperations, never()).createObject(any(), any(), eq(SourceComparison.class), any(), any());
        verify(llmOperations, never()).createObject(any(), any(), eq(RewrittenQueries.class), any(), any());
    }

    @Test
    void aQuestionThatComparesDocumentsGetsTheComparisonIntoTheAnswerPrompt() throws Exception {
        String question = "What is the difference between the runbook rollback and the canary rollback?";
        whenCreateObject(p -> p.equals("Question: " + question), SubQuestions.class)
                .thenReturn(new SubQuestions(List.of("rollback in the payments runbook", "canary rollback deployment guide")));
        whenCreateObject(p -> p.contains("Evidence passages:") && p.endsWith("Question: " + question), SourceComparison.class)
                .thenReturn(new SourceComparison(List.of(
                        new SourceComparison.Aspect("who triggers it",
                                "The runbook rollback is run by an operator, the canary rolls back on its own.",
                                List.of(1, 2), true))));
        whenCreateObject(p -> p.contains("How the sources relate"), GroundedAnswerDraft.class)
                .thenReturn(new GroundedAnswerDraft(
                        "The runbook rollback is manual [1]; an aborted canary rolls back on its own [2].",
                        List.of(1, 2), true, null));

        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"" + question + "\",\"options\":{\"includeDiagnostics\":true}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value(Matchers.containsString("rolls back on its own")))
                .andExpect(jsonPath("$.citations", Matchers.hasSize(2)))
                // Both documents are in the evidence: that is what made the question worth comparing,
                // and the draft stub above only matches a prompt that carries the comparison.
                .andExpect(jsonPath("$.diagnostics.hits[*].provenance.documentTitle",
                        Matchers.hasItems("Payments Runbook", "Deployment Guide")));
        // The comparison saw the same numbered passages the answer was written from.
        verifyCreateObject(p -> p.contains("Evidence passages:"), SourceComparison.class);
    }

    @Test
    void anOrdinaryQuestionIsRetrievedOnceAndCostsNeitherBranchAModelCall() throws Exception {
        whenCreateObject(p -> p.contains("Question:"), RewrittenQueries.class)
                .thenReturn(new RewrittenQueries(List.of()));
        whenCreateObject(p -> p.contains("Evidence passages:"), GroundedAnswerDraft.class)
                .thenReturn(new GroundedAnswerDraft("Run `systemctl restart payments` [1].", List.of(1), true, null));

        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"How do I restart the payment service?\",\"options\":{\"includeDiagnostics\":true}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.citations", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.diagnostics.decomposition").doesNotExist());
        verify(llmOperations, never()).createObject(any(), any(), eq(SubQuestions.class), any(), any());
        verify(llmOperations, never()).createObject(any(), any(), eq(SourceComparison.class), any(), any());
    }

    @Test
    void duplicatePartsFallBackToExpansionBeforeTheSourcesAreCompared() throws Exception {
        String question = "What is the difference between the runbook rollback and the canary rollback?";
        List<String> stages = new ArrayList<>();
        whenCreateObject(p -> p.equals("Question: " + question), SubQuestions.class).thenAnswer(_ -> {
            stages.add("decompose");
            return new SubQuestions(List.of("rollback", "ROLLBACK"));
        });
        whenCreateObject(p -> p.equals("Question: " + question), RewrittenQueries.class).thenAnswer(_ -> {
            stages.add("expand");
            return new RewrittenQueries(List.of("rollback payments runbook", "canary rollback deployment guide"));
        });
        whenCreateObject(p -> p.contains("Evidence passages:"), SourceComparison.class).thenAnswer(_ -> {
            stages.add("compare");
            return new SourceComparison(List.of(new SourceComparison.Aspect("trigger",
                    "The canary rolls back automatically.", List.of(1, 2), false)));
        });
        whenCreateObject(p -> p.contains("How the sources relate"), GroundedAnswerDraft.class).thenAnswer(_ -> {
            stages.add("draft");
            return new GroundedAnswerDraft("The canary rolls back automatically [1].", List.of(1), true, null);
        });

        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"" + question + "\",\"options\":{\"includeDiagnostics\":true}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.diagnostics.decomposition").doesNotExist())
                .andExpect(jsonPath("$.diagnostics.expansion.strategy").value("REWRITE"))
                .andExpect(jsonPath("$.citations", Matchers.hasSize(1)));
        assertThat(stages).containsExactly("decompose", "expand", "compare", "draft");
    }
}
