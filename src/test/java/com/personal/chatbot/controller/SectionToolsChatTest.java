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
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The document-level tools of the agentic branch with a mocked LLM: the model can read the corpus as a
 * table of contents, pull one section whole into evidence, and cannot reach outside the documents the
 * request restricted it to. Two documents are indexed because scope and ambiguity only exist with more
 * than one.
 */
@AutoConfigureMockMvc
class SectionToolsChatTest extends AbstractChatbotIntegrationTest {

    private static final Pattern CHUNK_ID = Pattern.compile("chunkId: (\\S+)");

    private static final String INCIDENTS = """
            # Incident Process

            ## Severity levels

            SEV-1 means customer money is at risk and pages the incident commander immediately.
            SEV-2 covers degraded payments that still complete within the hour.
            SEV-3 is everything else and waits for business hours.
            """;

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void isolatedDataDir(DynamicPropertyRegistry registry) {
        registry.add("chatbot.data-dir", () -> dataDir.toString());
    }

    @Autowired
    private MockMvc mockMvc;

    private static String runbookId;
    private static String incidentsId;

    @BeforeEach
    void indexBothDocumentsOnce() throws Exception {
        if (runbookId != null) {
            return;
        }
        // The title of an uploaded document is its file name, and it is what listSections prints.
        runbookId = upload("Payments Runbook.md", TestDocuments.markdown());
        incidentsId = upload("Incident Process.md", INCIDENTS.getBytes(StandardCharsets.UTF_8));
    }

    private String upload(String name, byte[] content) throws Exception {
        MvcResult upload = mockMvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("file", name, "text/markdown", content)))
                .andExpect(status().isAccepted()).andReturn();
        String id = JsonPath.read(upload.getResponse().getContentAsString(), "$.documentId");
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                mockMvc.perform(get("/api/documents/{id}/status", id)).andExpect(jsonPath("$.status").value("READY")));
        return id;
    }

    private static Tool tool(LlmInteraction interaction, String name) {
        return interaction.getTools().stream().filter(t -> t.getDefinition().getName().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("tool " + name + " not offered; had "
                        + interaction.getTools().stream().map(t -> t.getDefinition().getName()).toList()));
    }

    private static String call(LlmInteraction interaction, String name, String arguments) {
        return tool(interaction, name).call(arguments).toString();
    }

    @Test
    void theModelCanReadTheCorpusAsATableOfContents() throws Exception {
        String question = "What is in your knowledge base?";
        AtomicReference<String> listing = new AtomicReference<>();
        whenCreateObject(p -> p.contains("Question: " + question), AgenticDraft.class)
                .thenAnswer(invocation -> {
                    listing.set(call(invocation.getArgument(1), "knowledge_base_listSections", "{}"));
                    return new AgenticDraft("The knowledge base holds a payments runbook and an incident process.",
                            List.of(), true, null);
                });

        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"" + question + "\",\"options\":{\"mode\":\"AGENTIC\"}}"))
                .andExpect(status().isOk())
                // Nothing was retrieved because nothing needed to be: the answer describes the corpus.
                .andExpect(jsonPath("$.grounding").value("PARTIAL"))
                .andExpect(jsonPath("$.citations", Matchers.empty()))
                .andExpect(jsonPath("$.answer").value("The knowledge base holds a payments runbook and an incident process."));

        assertThat(listing.get())
                .contains("Document: Payments Runbook", "Document: Incident Process")
                .contains("- Restart", "- Rollback › Database rollback", "- Severity levels")
                .contains("chunks");
    }

    @Test
    void aSectionIsReadWholeAndItsChunksBecomeCitableEvidence() throws Exception {
        String question = "What are the severity levels?";
        AtomicReference<String> section = new AtomicReference<>();
        whenCreateObject(p -> p.contains("Question: " + question), AgenticDraft.class)
                .thenAnswer(invocation -> {
                    String output = call(invocation.getArgument(1), "knowledge_base_readSection",
                            "{\"sectionTitle\":\"Severity levels\"}");
                    section.set(output);
                    String chunkId = output.substring(output.indexOf("Chunk ID: ") + "Chunk ID: ".length()).split("\\s")[0];
                    return new AgenticDraft("SEV-1 pages the incident commander {{chunk:" + chunkId + "}}.",
                            List.of(chunkId), true, null);
                });

        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"" + question + "\",\"options\":{\"mode\":\"AGENTIC\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grounding").value("GROUNDED"))
                .andExpect(jsonPath("$.answer").value("SEV-1 pages the incident commander [1]."))
                .andExpect(jsonPath("$.citations", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.citations[0].documentId").value(incidentsId));

        // Whole and in order, so a table or a list arrives complete rather than as the hits a search ranked.
        assertThat(section.get()).contains("SEV-1", "SEV-2", "SEV-3").contains("Chunk ID: " + incidentsId + ":1:");
    }

    @Test
    void havingLookedAtTheCatalogueIsNotALicenceToAnswerFromNothing() throws Exception {
        String question = "Who signs off a release?";
        whenCreateObject(p -> p.contains("Question: " + question), AgenticDraft.class)
                .thenAnswer(invocation -> {
                    // A section read that found nothing counts as research, not as browsing: the answer
                    // that follows it has to be supported by passages like any other.
                    String missing = call(invocation.getArgument(1), "knowledge_base_readSection",
                            "{\"sectionTitle\":\"Release sign-off\"}");
                    assertThat(missing).contains("No section titled");
                    return new AgenticDraft("The release manager signs off.", List.of(), true, null);
                });

        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"" + question + "\",\"options\":{\"mode\":\"AGENTIC\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grounding").value("INSUFFICIENT_EVIDENCE"))
                .andExpect(jsonPath("$.answer").value(Matchers.not(Matchers.containsString("release manager"))));
    }

    @Test
    void aQuestionScopedToOneDocumentCannotSearchOrReadOutsideIt() throws Exception {
        String question = "How do I restart the payment service?";
        AtomicReference<String> hits = new AtomicReference<>();
        AtomicReference<String> outside = new AtomicReference<>();
        AtomicReference<String> catalogue = new AtomicReference<>();
        whenCreateObject(p -> p.contains("Question: " + question), AgenticDraft.class)
                .thenAnswer(invocation -> {
                    LlmInteraction interaction = invocation.getArgument(1);
                    String found = call(interaction, "knowledge_base_vectorSearch", "{\"query\":\"restart payments\",\"topK\":5}");
                    hits.set(found);
                    outside.set(call(interaction, "knowledge_base_readSection", "{\"sectionTitle\":\"Severity levels\"}"));
                    catalogue.set(call(interaction, "knowledge_base_listSections", "{}"));
                    Matcher matcher = CHUNK_ID.matcher(found);
                    assertThat(matcher.find()).as("scoped search still finds the runbook: %s", found).isTrue();
                    return new AgenticDraft("Run `systemctl restart payments` {{chunk:" + matcher.group(1) + "}}.",
                            List.of(matcher.group(1)), true, null);
                });

        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"" + question + "\",\"options\":{\"mode\":\"AGENTIC\",\"documentIds\":[\""
                                + runbookId + "\"]}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grounding").value("GROUNDED"))
                .andExpect(jsonPath("$.citations[0].documentId").value(runbookId));

        // The filter Embabel applies under the search tools, which the model can neither see nor lift.
        assertThat(hits.get()).doesNotContain(incidentsId);
        // Embabel builds the section tools without that filter, so the scope is applied by our own view.
        assertThat(outside.get()).contains("No section titled");
        assertThat(catalogue.get()).contains("Payments Runbook").doesNotContain("Incident Process");
    }
}
