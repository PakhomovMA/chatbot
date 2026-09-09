package com.personal.chatbot.controller;

import com.embabel.agent.core.support.InvalidLlmReturnFormatException;
import com.jayway.jsonpath.JsonPath;
import com.personal.chatbot.models.agent.GroundedAnswerDraft;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 5 gate: chat contract with the language model mocked (docs/system-plan.md §11): grounded answers,
 * phantom citations, insufficient evidence, conversation memory.
 */
@AutoConfigureMockMvc
class ChatControllerTest extends AbstractChatbotIntegrationTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void isolatedDataDir(DynamicPropertyRegistry registry) {
        registry.add("chatbot.data-dir", () -> dataDir.toString());
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

    private static String body(String conversationId, String message) {
        return (conversationId == null ? "{" : "{\"conversationId\":\"" + conversationId + "\",")
                + "\"message\":\"" + message + "\",\"options\":{\"includeDiagnostics\":true}}";
    }

    @Test
    void groundedAnswerWithVerifiedCitationsAndConversationMemory() throws Exception {
        whenCreateObject(p -> p.contains("Question: How do I restart the payment service?"), GroundedAnswerDraft.class)
                .thenReturn(new GroundedAnswerDraft("Run `systemctl restart payments` on the host [1]. Unrelated claim [9].",
                        List.of(1), true, null));

        MvcResult first = mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content(body(null, "How do I restart the payment service?")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grounding").value("GROUNDED"))
                .andExpect(jsonPath("$.answer").value("Run `systemctl restart payments` on the host [1]. Unrelated claim."))
                .andExpect(jsonPath("$.citations", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.citations[0].marker").value(1))
                .andExpect(jsonPath("$.citations[0].documentId").value(documentId))
                .andExpect(jsonPath("$.citations[0].documentTitle").value("Payments Runbook"))
                .andExpect(jsonPath("$.citations[0].quote").value(Matchers.containsString("systemctl")))
                .andExpect(jsonPath("$.citations[0].chunkId").value(Matchers.startsWith(documentId + ":1:")))
                .andExpect(jsonPath("$.timings.totalMs").isNumber())
                .andExpect(jsonPath("$.retrievalTraceId").isString())
                .andExpect(jsonPath("$.diagnostics.hits", Matchers.not(Matchers.empty())))
                .andReturn();
        String conversationId = JsonPath.read(first.getResponse().getContentAsString(), "$.conversationId");

        // Second turn: the model sees the previous exchange in its prompt.
        whenCreateObject(p -> p.contains("Assistant: Run `systemctl restart payments`") && p.contains("Question: And how do I roll back?"),
                GroundedAnswerDraft.class)
                .thenReturn(new GroundedAnswerDraft("Deploy the previous image tag with the deploy script [1].", List.of(1), true, null));
        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content(body(conversationId, "And how do I roll back?")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId").value(conversationId))
                .andExpect(jsonPath("$.grounding").value(Matchers.in(List.of("GROUNDED", "PARTIAL"))));

        mockMvc.perform(get("/api/conversations/{id}", conversationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages", Matchers.hasSize(4)))
                .andExpect(jsonPath("$.messages[0].role").value("USER"))
                .andExpect(jsonPath("$.messages[1].role").value("ASSISTANT"))
                .andExpect(jsonPath("$.messages[1].citations", Matchers.hasSize(1)));
        mockMvc.perform(delete("/api/conversations/{id}", conversationId)).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/conversations/{id}", conversationId))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void modelReportingInsufficientEvidenceIsNotPresentedAsGrounded() throws Exception {
        whenCreateObject(p -> p.contains("Question: Which port does the service listen on?"), GroundedAnswerDraft.class)
                .thenReturn(new GroundedAnswerDraft("The runbook covers restarts [1] but does not mention a port.",
                        List.of(1), false, "the listening port"));
        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content(body(null, "Which port does the service listen on?")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grounding").value("INSUFFICIENT_EVIDENCE"))
                .andExpect(jsonPath("$.notes").value("the listening port"))
                .andExpect(jsonPath("$.citations", Matchers.hasSize(1)));
    }

    /**
     * Some models answer the structured call in Markdown, and Embabel hands that back as an empty
     * reply (docs/eval-log.md): everything before the first brace is taken for a thinking block, so
     * prose without one is stripped whole. The answer is kept and verified like any other.
     */
    @Test
    void anAnswerWrittenAsProseInsteadOfJsonIsKeptAndVerified() throws Exception {
        String prose = """
                To restart the service, run `systemctl restart payments` on the host [1].

                Do not restart every replica at once [1]. Unrelated claim [9].""";
        whenCreateObject(p -> p.contains("Question: How do I restart the payment service?"), GroundedAnswerDraft.class)
                .thenThrow(new InvalidLlmReturnFormatException(prose, GroundedAnswerDraft.class,
                        new RuntimeException("No content to map due to end-of-input")));

        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content(body(null, "How do I restart the payment service?")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grounding").value("GROUNDED"))
                .andExpect(jsonPath("$.answer").value(Matchers.containsString("`systemctl restart payments` on the host [1]")))
                .andExpect(jsonPath("$.answer").value(Matchers.containsString("Unrelated claim.")))
                .andExpect(jsonPath("$.citations", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.citations[0].marker").value(1));
    }

    /** Half-written JSON is not an answer to show anyone: it stays a failure, as before. */
    @Test
    void aTruncatedJsonReplyIsNotPresentedAsAnAnswer() throws Exception {
        whenCreateObject(p -> p.contains("Question: How is the service stopped?"), GroundedAnswerDraft.class)
                .thenThrow(new InvalidLlmReturnFormatException("{\"answer\": \"Stop it with", GroundedAnswerDraft.class,
                        new RuntimeException("Unexpected end-of-input")));

        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content(body(null, "How is the service stopped?")))
                .andExpect(status().is5xxServerError());
    }

    @Test
    void noRetrievedEvidenceShortCircuitsWithoutCallingTheModel() throws Exception {
        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"anything\",\"options\":{\"documentIds\":[\"no-such-document\"]}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grounding").value("INSUFFICIENT_EVIDENCE"))
                .andExpect(jsonPath("$.citations", Matchers.empty()))
                .andExpect(jsonPath("$.answer").value(Matchers.containsString("could not find")));
        verify(llmOperations, never()).createObject(any(), any(), any(), any(), any());
    }

    @Test
    void validation() throws Exception {
        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"x\",\"options\":{\"topK\":99}}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/conversations/{id}", "nope")).andExpect(status().isNotFound());
    }
}
