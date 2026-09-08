package com.personal.chatbot;

import com.personal.chatbot.models.chat.AnswerMode;
import com.personal.chatbot.models.chat.ChatRequest;
import com.personal.chatbot.models.chat.ChatResponse;
import com.personal.chatbot.models.chat.ChatStreamEvent;
import com.personal.chatbot.models.chat.ConversationTurn;
import com.personal.chatbot.models.chat.Grounding;
import com.personal.chatbot.models.knowledge.DocumentStatus;
import com.personal.chatbot.models.retrieval.ExpansionStrategy;
import com.personal.chatbot.models.retrieval.SearchExpansion;
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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end grounded answers with the real stack: Ollama qwen3:14b + EmbeddingGemma ONNX + Lucene
 * (docs/system-plan.md §11, §14 Phase 5). Run with {@code ./gradlew test -PincludeTags=e2e}.
 */
@Tag("e2e")
@SpringBootTest
class ChatE2eTest {

    private static final Logger log = LoggerFactory.getLogger(ChatE2eTest.class);

    record Golden(String question, String expectedDocumentKey, List<String> mustContain) {
    }

    private static final List<Golden> GOLDEN = List.of(
            new Golden("How do I restart the payments service?", "payments-runbook", List.of("rollout restart")),
            new Golden("Where are secrets stored and how often are they rotated?", "deployment-guide", List.of("ninety days", "90 days")),
            new Golden("Which header prevents duplicate orders?", "api-reference", List.of("Idempotency-Key")),
            new Golden("How quickly must the primary on-call respond to a page?", "onboarding-handbook", List.of("fifteen minutes", "15 minutes")),
            new Golden("How do I declare an incident?", "incident-process", List.of("/incident declare")));

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void realStackInTempDir(DynamicPropertyRegistry registry) {
        registry.add("chatbot.data-dir", () -> dataDir.toString());
        registry.add("chatbot.index.in-memory", () -> "true");
        registry.add("chatbot.embedding.onnx.model-dir", () -> modelDir().toString());
        registry.add("server.port", () -> "0");
    }

    static Path modelDir() {
        String override = System.getenv("CHATBOT_MODEL_DIR");
        return override != null ? Path.of(override)
                : Path.of(System.getProperty("user.home"), ".chatbot", "models", "embeddinggemma-300m");
    }

    @BeforeAll
    static void requireLocalStack() {
        assumeTrue(Files.isRegularFile(modelDir().resolve("model.onnx")), "EmbeddingGemma ONNX files not present");
        assumeTrue(ollamaHasModel("qwen3:14b"), "Ollama with qwen3:14b not reachable");
    }

    private static boolean ollamaHasModel(String name) {
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:11434/api/tags"))
                    .timeout(Duration.ofSeconds(3)).build(), HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 && response.body().contains("\"" + name + "\"");
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    @Autowired
    private DocumentService documents;
    @Autowired
    private DocumentRegistry registry;
    @Autowired
    private ChatService chat;

    @Test
    void goldenQuestionsGetGroundedAnswersWithCitations() throws IOException {
        run(AnswerMode.DETERMINISTIC, GOLDEN.size() - 1);
    }

    /** Phase 9c: the same questions through ToolishRag-driven research (qwen3 drives the search tools). */
    @Test
    void agenticModeAnswersGoldenQuestionsWithCitations() throws IOException {
        run(AnswerMode.AGENTIC, GOLDEN.size() - 2);
    }

    /**
     * Phase 9a: a question asked in the user's words, not the documentation's, stays under the
     * sufficiency floor on the first pass; the planner then inserts {@code expandSearch}, and the
     * answer is written from the merged evidence.
     */
    @Test
    void aWeaklyRetrievedQuestionIsSearchedAgainBeforeItIsAnswered() throws IOException {
        seedDocuments();
        String question = "How much of the real traffic sees a new version before it is everywhere?";
        ChatResponse response = chat.chat(new ChatRequest(null, question,
                new ChatRequest.Options(null, null, true, AnswerMode.DETERMINISTIC)));

        SearchExpansion expansion = response.diagnostics() != null ? response.diagnostics().expansion() : null;
        log.info("[expandSearch] Q: {}\n   -> {} in {} ms, widened with {}: {}", question, response.grounding(),
                response.timings().totalMs(), expansion == null ? "nothing" : expansion.queries(),
                response.answer().replace('\n', ' '));
        assertThat(expansion).as("the question should be weak enough to be searched again").isNotNull();
        assertThat(expansion.strategy()).isEqualTo(ExpansionStrategy.REWRITE);
        assertThat(response.citations()).isNotEmpty();
        assertThat(response.answer().toLowerCase(Locale.ROOT)).containsAnyOf("five percent", "5 percent", "5%");
    }

    /** Phase 9b: real stored history, Embabel rewrite, retrieval, grounding and topic switch. */
    @Test
    void followupsUseHistoryWithoutPullingANewTopicBackToTheOldOne() throws IOException {
        seedDocuments();
        ChatResponse first = chat.chat(new ChatRequest(null, "Where are service secrets stored?", null));
        ChatResponse followup = chat.chat(new ChatRequest(first.conversationId(), "How often are they rotated?",
                new ChatRequest.Options(null, null, true, AnswerMode.DETERMINISTIC)));
        assertThat(followup.diagnostics().query().toLowerCase(Locale.ROOT)).contains("secret");
        assertThat(followup.answer().toLowerCase(Locale.ROOT)).containsAnyOf("ninety days", "90 days");
        assertThat(followup.citations()).anyMatch(c -> c.documentTitle().equals("deployment-guide"));
        ChatResponse switched = chat.chat(new ChatRequest(first.conversationId(), "Which header prevents duplicate orders?",
                new ChatRequest.Options(null, null, true, AnswerMode.DETERMINISTIC)));
        assertThat(switched.answer()).containsIgnoringCase("Idempotency-Key");
        assertThat(switched.citations()).anyMatch(c -> c.documentTitle().equals("api-reference"));
        assertThat(chat.conversation(first.conversationId()).messages())
                .filteredOn(t -> t.role() == ConversationTurn.Role.USER).extracting(ConversationTurn::content)
                .containsExactly("Where are service secrets stored?", "How often are they rotated?",
                        "Which header prevents duplicate orders?");
        log.info("[conversation rewrite] followup query: {}; topic switch query: {}", followup.diagnostics().query(),
                switched.diagnostics().query());
    }

    @Test
    void russianFollowupStreamsAGroundedAnswerFromResolvedSubject() throws IOException {
        seedDocuments();
        ChatResponse first = chat.chat(new ChatRequest(null, "Расскажи про сервис payments.", null));
        List<ChatStreamEvent> events = new ArrayList<>();
        chat.stream(new ChatRequest(first.conversationId(), "А как его перезапустить?",
                new ChatRequest.Options(null, null, true, AnswerMode.DETERMINISTIC)), events::add);
        assertThat(events.getLast()).isInstanceOf(ChatStreamEvent.Final.class);
        ChatResponse response = ((ChatStreamEvent.Final) events.getLast()).response();
        assertThat(events).anyMatch(e -> e instanceof ChatStreamEvent.Status s && s.stage().equals("rewriting"));
        assertThat(response.diagnostics().query().toLowerCase(Locale.ROOT)).contains("payments");
        assertThat(response.answer()).contains("rollout restart");
        assertThat(response.answer()).containsPattern("[А-Яа-я]{3,}");
        assertThat(response.citations()).anyMatch(c -> c.documentTitle().equals("payments-runbook"));
        log.info("[conversation rewrite RU/SSE] query: {}; answer: {}", response.diagnostics().query(), response.answer());
    }

    private void run(AnswerMode mode, int minGrounded) throws IOException {
        seedDocuments();
        int grounded = 0;
        for (Golden golden : GOLDEN) {
            long started = System.nanoTime();
            ChatResponse response = chat.chat(new ChatRequest(null, golden.question(),
                    new ChatRequest.Options(null, null, true, mode)));
            long millis = (System.nanoTime() - started) / 1_000_000;
            String answer = response.answer().toLowerCase(Locale.ROOT);
            boolean mentions = golden.mustContain().stream().anyMatch(m -> answer.contains(m.toLowerCase(Locale.ROOT)));
            int searches = response.diagnostics() != null ? response.diagnostics().candidates() : -1;
            log.info("[{}] Q: {}\n   -> {} in {} ms ({} citations, retrieval {} ms, llm {} ms, searches {}): {}", mode, golden.question(),
                    response.grounding(), millis, response.citations().size(), response.timings().retrievalMs(),
                    response.timings().llmMs(), searches, response.answer().replace('\n', ' '));
            assertThat(response.citations()).as("citations for '%s' in %s mode", golden.question(), mode).isNotEmpty();
            assertThat(response.citations()).anyMatch(c -> c.documentTitle().equals(golden.expectedDocumentKey()));
            assertThat(mentions).as("answer to '%s' should mention %s but was: %s", golden.question(), golden.mustContain(), response.answer()).isTrue();
            if (response.grounding() == Grounding.GROUNDED) {
                grounded++;
            }
        }
        assertThat(grounded).as("grounded answers out of %d in %s mode", GOLDEN.size(), mode).isGreaterThanOrEqualTo(minGrounded);
    }

    private static boolean seeded;

    private void seedDocuments() throws IOException {
        if (seeded) {
            return;
        }
        try (Stream<Path> docs = Files.list(Path.of("src/test/resources/eval/docs"))) {
            for (Path file : docs.filter(p -> p.toString().endsWith(".md")).sorted().toList()) {
                documents.upload(new DocumentService.Upload(file.getFileName().toString(), "text/markdown", Files.size(file),
                        Files.newInputStream(file), file.getFileName().toString().replace(".md", "")));
            }
        }
        await().atMost(Duration.ofMinutes(2)).untilAsserted(() ->
                assertThat(registry.findAll()).isNotEmpty().allMatch(d -> d.status() == DocumentStatus.READY));
        seeded = true;
    }
}
