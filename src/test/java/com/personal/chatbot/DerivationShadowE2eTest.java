package com.personal.chatbot;

import com.personal.chatbot.models.chat.ChatRequest;
import com.personal.chatbot.models.chat.ChatResponse;
import com.personal.chatbot.models.chat.ChatStreamEvent;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import com.personal.chatbot.models.knowledge.DocumentStatus;
import com.personal.chatbot.service.chat.ChatService;
import com.personal.chatbot.service.knowledge.DocumentRegistry;
import com.personal.chatbot.service.knowledge.DocumentService;
import com.personal.chatbot.support.StageCosts;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** K03: repeated follow-ups in two fresh conversations, behind the real answer cache. */
@Tag("e2e")
@SpringBootTest(properties = "chatbot.cache.derivation.shadow=true")
@Import(StageCosts.WholeRun.class)
class DerivationShadowE2eTest {

    private static final String QUESTION = "How do I restart the payments service?";
    private static final String DOCUMENT = "payments-runbook";

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void realStackWithTheAnswerCache(DynamicPropertyRegistry registry) {
        registry.add("chatbot.data-dir", () -> dataDir.toString());
        registry.add("chatbot.index.in-memory", () -> "true");
        registry.add("chatbot.embedding.onnx.model-dir", () -> ChatE2eTest.modelDir().toString());
        registry.add("embabel.models.default-llm", ChatE2eTest::llm);
        registry.add("chatbot.cache.answer.enabled", () -> "true");
        registry.add("chatbot.chat.mode", () -> "DETERMINISTIC");
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
    @Autowired
    private MeterRegistry meters;

    @AfterEach
    void recordStageCosts() throws IOException {
        StageCosts.write(meters, getClass());
    }

    @Test
    void repeatedDialogMeasuresWouldHitsWithoutServingDerivations() throws IOException {
        Path file = Path.of("src/test/resources/eval/docs", DOCUMENT + ".md");
        documents.upload(new DocumentService.Upload(file.getFileName().toString(), "text/markdown", Files.size(file),
                Files.newInputStream(file), DOCUMENT));
        await().atMost(Duration.ofMinutes(2)).untilAsserted(() ->
                assertThat(registry.findAll()).isNotEmpty().allMatch(d -> d.status() == DocumentStatus.READY));
        ChatResponse first = chat.chat(new ChatRequest(null, QUESTION, null));
        ChatResponse repeated = chat.chat(new ChatRequest(null, QUESTION, null));
        assertThat(repeated.answer()).isEqualTo(first.answer());
        String followUp = "And how do I check that it is healthy?";
        chat.chat(new ChatRequest(first.conversationId(), followUp, null));
        long before = rewriteCalls();
        List<ChatStreamEvent> events = new CopyOnWriteArrayList<>();
        chat.stream(new ChatRequest(repeated.conversationId(), followUp, null), events::add);
        assertThat(events.getLast()).isInstanceOf(ChatStreamEvent.Final.class);
        assertThat(rewriteCalls()).isEqualTo(before + (serving() ? 0 : 1));
        if (serving()) {
            assertThat(events).noneMatch(e -> e instanceof ChatStreamEvent.Status status && status.stage().equals("rewriting"));
        }
        assertThat(lookups("miss")).isEqualTo(1);
        assertThat(lookups("hit")).isEqualTo(1);
        System.out.printf("K03 repeated dialogs: hit=%.0f miss=%.0f rewriteCalls=%d%n",
                lookups("hit"), lookups("miss"), rewriteCalls());
    }

    protected boolean serving() { return false; }

    private double lookups(String result) {
        return meters.get("chatbot.cache.lookup").tags("layer", "derivation", "result", result).counter().count();
    }

    private long rewriteCalls() {
        return meters.find("chatbot.ai.operation").tag("operation", "conversation-query-rewrite")
                .timers().stream().mapToLong(Timer::count).sum();
    }
}
