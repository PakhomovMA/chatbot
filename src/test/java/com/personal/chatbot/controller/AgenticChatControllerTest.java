package com.personal.chatbot.controller;

import com.embabel.agent.api.tool.Tool;
import com.embabel.agent.core.support.LlmInteraction;
import com.jayway.jsonpath.JsonPath;
import com.personal.chatbot.models.agent.AgenticDraft;
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
        whenCreateObject(p -> p.contains("Question: How do I restart the payment service?")
                && p.contains("knowledge_base_vectorSearch"), AgenticDraft.class)
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
                        "knowledge_base_broadenChunk", "knowledge_base_zoomOut");
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
}
