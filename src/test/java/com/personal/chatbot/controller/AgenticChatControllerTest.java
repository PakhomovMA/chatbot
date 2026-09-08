package com.personal.chatbot.controller;

import com.embabel.agent.api.tool.Tool;
import com.embabel.agent.core.support.LlmInteraction;
import com.embabel.chat.Message;
import com.jayway.jsonpath.JsonPath;
import com.personal.chatbot.models.agent.AgenticDraft;
import com.personal.chatbot.models.agent.GroundedAnswerDraft;
import com.personal.chatbot.models.agent.SubQuestions;
import com.personal.chatbot.support.AbstractChatbotIntegrationTest;
import com.personal.chatbot.support.TestDocuments;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 9c gate with a mocked LLM: the agentic action hands ToolishRag tools to the model, the tools
 * really run against the Lucene store, every chunk they return is captured as evidence, and the
 * model's chunk references become verified citations. The "model" here is a stub that calls the
 * vector search tool once and cites the first chunk it gets back.
 */
@AutoConfigureMockMvc
class AgenticChatControllerTest extends AbstractChatbotIntegrationTest {

    private static final Pattern CHUNK_ID = Pattern.compile("chunkId: (\\S+)");

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void isolatedDataDir(DynamicPropertyRegistry registry) {
        registry.add("chatbot.data-dir", () -> dataDir.toString());
        registry.add("chatbot.chat.decompose.enabled", () -> "true");
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
                        .file(new MockMultipartFile("file", "runbook.md", "text/markdown", TestDocuments.markdown())))
                .andExpect(status().isAccepted()).andReturn();
        documentId = JsonPath.read(upload.getResponse().getContentAsString(), "$.documentId");
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                mockMvc.perform(get("/api/documents/{id}/status", documentId)).andExpect(jsonPath("$.status").value("READY")));
    }

    private static Tool tool(LlmInteraction interaction, String name) {
        return interaction.getTools().stream().filter(t -> t.getDefinition().getName().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("tool " + name + " not offered; had "
                        + interaction.getTools().stream().map(t -> t.getDefinition().getName()).toList()));
    }

    @Test
    void agenticModeSearchesThroughToolishRagAndCitesWhatItSaw() throws Exception {
        AtomicReference<String> toolOutput = new AtomicReference<>();
        whenCreateObject(p -> p.contains("Question: How do I restart the payment service?"),
                AgenticDraft.class, AgenticChatControllerTest::instructsAgenticResearch)
                .thenAnswer(invocation -> {
                    LlmInteraction interaction = invocation.getArgument(1);
                    // The stubbed model "decides" to search once, exactly like the real tool loop would.
                    Tool vectorSearch = tool(interaction, "knowledge_base_vectorSearch");
                    String output = vectorSearch.call("{\"query\":\"restart payment service\",\"topK\":3}").toString();
                    toolOutput.set(output);
                    Matcher matcher = CHUNK_ID.matcher(output);
                    assertThat(matcher.find()).as("tool output lists chunk ids: %s", output).isTrue();
                    String chunkId = matcher.group(1);
                    return new AgenticDraft("Run `systemctl restart payments` on the host {{chunk:" + chunkId + "}}. Made-up {{chunk:doc:9:9}} claim.",
                            List.of(chunkId), true, null);
                });

        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"How do I restart the payment service?\",\"options\":{\"mode\":\"AGENTIC\",\"includeDiagnostics\":true}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grounding").value("GROUNDED"))
                .andExpect(jsonPath("$.answer").value("Run `systemctl restart payments` on the host [1]. Made-up claim."))
                .andExpect(jsonPath("$.citations", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.citations[0].documentId").value(documentId))
                .andExpect(jsonPath("$.citations[0].chunkId").value(Matchers.startsWith(documentId + ":1:")))
                .andExpect(jsonPath("$.citations[0].quote").value(Matchers.containsString("systemctl")))
                .andExpect(jsonPath("$.diagnostics.candidates").value(1))
                .andExpect(jsonPath("$.diagnostics.hits", Matchers.not(Matchers.empty())));

        assertThat(toolOutput.get()).contains("chunkId: " + documentId + ":1:");

        ArgumentCaptor<LlmInteraction> captor = ArgumentCaptor.forClass(LlmInteraction.class);
        verify(llmOperations).createObject(any(), captor.capture(), eq(AgenticDraft.class), any(), any());
        assertThat(captor.getValue().getTools()).extracting(t -> t.getDefinition().getName())
                .containsExactlyInAnyOrder("knowledge_base_vectorSearch", "knowledge_base_textSearch",
                        "knowledge_base_broadenChunk", "knowledge_base_zoomOut",
                        "knowledge_base_listSections", "knowledge_base_readSection");
    }

    @Test
    void agenticModeWithoutAnySearchYieldsInsufficientEvidence() throws Exception {
        whenCreateObject(p -> p.contains("Question: Which port does it listen on?"), AgenticDraft.class)
                .thenReturn(new AgenticDraft("I could not find the port.", List.of(), false, "the listening port"));
        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Which port does it listen on?\",\"options\":{\"mode\":\"AGENTIC\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grounding").value("INSUFFICIENT_EVIDENCE"))
                .andExpect(jsonPath("$.citations", Matchers.empty()))
                .andExpect(jsonPath("$.notes").value("the listening port"));
    }

    @Test
    void compositeAgenticQuestionReceivesPerPartEvidenceBeforeTheToolLoop() throws Exception {
        String question = "How do I restart the payment service and how do I roll it back?";
        whenCreateObject(p -> p.equals("Question: " + question), SubQuestions.class)
                .thenReturn(new SubQuestions(List.of("How do I restart the payment service?",
                        "How do I roll back the payment service?")));
        whenCreateObject(p -> p.contains("Question: " + question), AgenticDraft.class)
                .thenAnswer(invocation -> {
                    String input = invocation.<List<Message>>getArgument(0).stream()
                            .map(Message::getContent).collect(java.util.stream.Collectors.joining("\n"));
                    assertThat(input).contains("Pre-retrieved evidence", "systemctl restart payments");
                    Matcher matcher = CHUNK_ID.matcher(input);
                    assertThat(matcher.find()).isTrue();
                    String id = matcher.group(1);
                    return new AgenticDraft("Restart payments {{chunk:" + id + "}}.", List.of(id), true, null);
                });
        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"" + question + "\",\"options\":{\"mode\":\"AGENTIC\",\"includeDiagnostics\":true}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grounding").value("GROUNDED"))
                .andExpect(jsonPath("$.citations", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.diagnostics.decomposition.subQuestions", Matchers.hasSize(2)));
    }

    @Test
    void uncitedToolAnswerIsRegeneratedFromEvidenceWithoutRepeatingItsClaims() throws Exception {
        String question = "How are payments restarted?";
        whenCreateObject(p -> p.contains("Question: " + question), AgenticDraft.class)
                .thenAnswer(invocation -> {
                    tool(invocation.getArgument(1), "knowledge_base_vectorSearch")
                            .call("{\"query\":\"restart payment service\",\"topK\":3}");
                    return new AgenticDraft("An invented answer.", List.of(), true, null);
                });
        whenCreateObject(p -> p.contains("Question: " + question) && p.contains("Evidence passages:")
                        && !p.contains("An invented answer."), GroundedAnswerDraft.class)
                .thenReturn(new GroundedAnswerDraft("Run systemctl restart payments [1].", List.of(1), true, null));
        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"" + question + "\",\"options\":{\"mode\":\"AGENTIC\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("Run systemctl restart payments [1]."))
                .andExpect(jsonPath("$.citations", Matchers.hasSize(1)));
    }

    @Test
    void aRepairWithOnlyPhantomCitationsDoesNotPublishTheUnsupportedAnswer() throws Exception {
        String question = "Who operates payments?";
        whenCreateObject(p -> p.contains("Question: " + question), AgenticDraft.class)
                .thenAnswer(invocation -> {
                    tool(invocation.getArgument(1), "knowledge_base_vectorSearch")
                            .call("{\"query\":\"payments\",\"topK\":3}");
                    return new AgenticDraft("An invented answer.", List.of(), true, null);
                });
        whenCreateObject(p -> p.contains("Question: " + question), GroundedAnswerDraft.class)
                .thenReturn(new GroundedAnswerDraft("Invented again [999].", List.of(999), true, null));
        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"" + question + "\",\"options\":{\"mode\":\"AGENTIC\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grounding").value("INSUFFICIENT_EVIDENCE"))
                .andExpect(jsonPath("$.answer").value(Matchers.not(Matchers.containsString("Invented"))))
                .andExpect(jsonPath("$.citations", Matchers.empty()));
    }

    @Test
    void anUncitedPartialAnswerAlsoGetsCitationsForItsSupportedPart() throws Exception {
        String question = "What are the restart command and the service owner's name?";
        whenCreateObject(p -> p.equals("Question: " + question), SubQuestions.class)
                .thenReturn(new SubQuestions(List.of()));
        whenCreateObject(p -> p.contains("Question: " + question), AgenticDraft.class)
                .thenReturn(new AgenticDraft("Restart payments. The owner is not stated.", List.of(), false, "owner"));
        whenCreateObject(p -> p.contains("Question: " + question) && p.contains("Evidence passages:"), GroundedAnswerDraft.class)
                .thenReturn(new GroundedAnswerDraft("Run systemctl restart payments [1]. The owner is not stated.",
                        List.of(1), false, "owner"));
        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"" + question + "\",\"options\":{\"mode\":\"AGENTIC\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grounding").value("INSUFFICIENT_EVIDENCE"))
                .andExpect(jsonPath("$.notes").value("owner"))
                .andExpect(jsonPath("$.citations", Matchers.hasSize(1)));
    }

    @Test
    void aConfidentAnswerWithoutAnySearchIsNotPublished() throws Exception {
        String question = "Who owns this service?";
        whenCreateObject(p -> p.contains("Question: " + question), AgenticDraft.class)
                .thenReturn(new AgenticDraft("An invented company.", List.of(), true, null));
        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"" + question + "\",\"options\":{\"mode\":\"AGENTIC\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grounding").value("INSUFFICIENT_EVIDENCE"))
                .andExpect(jsonPath("$.answer").value(Matchers.not(Matchers.containsString("invented company"))));
    }

    /**
     * The agentic branch is now recognised by its prompt contributor rather than by the user prompt:
     * standing instructions travel in the system message, which the mocked {@code LlmOperations}
     * never sees in the message list.
     */
    private static boolean instructsAgenticResearch(LlmInteraction interaction) {
        return interaction.getPromptContributors().stream()
                .anyMatch(c -> c.contribution().contains("knowledge_base_vectorSearch"));
    }
}
