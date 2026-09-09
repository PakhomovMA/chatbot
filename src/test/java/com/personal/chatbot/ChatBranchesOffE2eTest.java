package com.personal.chatbot;

import com.personal.chatbot.models.chat.AnswerMode;
import com.personal.chatbot.models.chat.ChatRequest;
import com.personal.chatbot.models.chat.ChatResponse;
import com.personal.chatbot.models.chat.Citation;
import com.personal.chatbot.models.knowledge.DocumentStatus;
import com.personal.chatbot.service.chat.ChatService;
import com.personal.chatbot.service.knowledge.DocumentRegistry;
import com.personal.chatbot.service.knowledge.DocumentService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The Phase 9d questions answered with both of its branches switched off: the counterfactual the
 * retrieval eval cannot produce, since neither branch changes what a single query retrieves on a
 * corpus this small (docs/eval-log.md). Answers are logged rather than asserted in detail — this is
 * the baseline the branches are compared against, and what it must show is that an answer still
 * arrives, grounded, without them.
 */
@Tag("e2e")
@SpringBootTest
class ChatBranchesOffE2eTest {

    private static final Logger log = LoggerFactory.getLogger(ChatBranchesOffE2eTest.class);

    static final String TWO_PART_QUESTION =
            "How do I restart the payments service and how much traffic does the canary get?";
    static final String COMPARING_QUESTION =
            "What is the difference between rolling back a payments release by hand and the rollback an aborted canary does?";

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void bothBranchesOff(DynamicPropertyRegistry registry) {
        registry.add("chatbot.data-dir", () -> dataDir.toString());
        registry.add("chatbot.index.in-memory", () -> "true");
        registry.add("chatbot.embedding.onnx.model-dir", () -> ChatE2eTest.modelDir().toString());
        registry.add("embabel.models.default-llm", ChatE2eTest::llm);
        registry.add("chatbot.chat.decompose.enabled", () -> "false");
        registry.add("chatbot.chat.compare-sources.enabled", () -> "false");
        registry.add("server.port", () -> "0");
    }

    @BeforeAll
    static void requireLocalStack() {
        assumeTrue(Files.isRegularFile(ChatE2eTest.modelDir().resolve("model.onnx")), "EmbeddingGemma ONNX files not present");
        assumeTrue(ChatE2eTest.ollamaHasModel(ChatE2eTest.llm()), "Ollama with " + ChatE2eTest.llm() + " not reachable");
    }

    @Autowired
    private DocumentService documents;
    @Autowired
    private DocumentRegistry registry;
    @Autowired
    private ChatService chat;

    @Test
    void theSameTwoQuestionsWithoutDecompositionOrComparison() throws IOException {
        seedDocuments();
        for (String question : List.of(TWO_PART_QUESTION, COMPARING_QUESTION)) {
            long started = System.nanoTime();
            ChatResponse response = chat.chat(new ChatRequest(null, question,
                    new ChatRequest.Options(null, null, true, AnswerMode.DETERMINISTIC)));
            log.info("[9d off] Q: {}\n   -> {} in {} ms, documents {}: {}", question, response.grounding(),
                    (System.nanoTime() - started) / 1_000_000,
                    response.citations().stream().map(Citation::documentTitle).distinct().toList(),
                    response.answer().replace('\n', ' '));
            assertThat(response.diagnostics().decomposition()).as("no decomposition with the branch off").isNull();
            assertThat(response.citations()).isNotEmpty();
            assertThat(response.answer().toLowerCase(Locale.ROOT)).isNotBlank();
        }
    }

    private void seedDocuments() throws IOException {
        try (Stream<Path> docs = Files.list(Path.of("src/test/resources/eval/docs"))) {
            for (Path file : docs.filter(p -> p.toString().endsWith(".md")).sorted().toList()) {
                documents.upload(new DocumentService.Upload(file.getFileName().toString(), "text/markdown", Files.size(file),
                        Files.newInputStream(file), file.getFileName().toString().replace(".md", "")));
            }
        }
        await().atMost(Duration.ofMinutes(2)).untilAsserted(() ->
                assertThat(registry.findAll()).isNotEmpty().allMatch(d -> d.status() == DocumentStatus.READY));
    }
}
