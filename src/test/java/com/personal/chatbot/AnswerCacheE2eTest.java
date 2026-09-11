package com.personal.chatbot;

import com.personal.chatbot.models.chat.ChatRequest;
import com.personal.chatbot.models.chat.ChatResponse;
import com.personal.chatbot.models.chat.ChatStreamEvent;
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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * K02 gate on the real stack (docs/cache-plan.md §4): a golden question asked again is served from the
 * answer cache — no model call, well under 200 ms — with the citations it was first answered with, over
 * the synchronous call and the stream alike. Run on its own:
 * {@code ./gradlew test -PincludeTags=e2e --tests '*AnswerCacheE2eTest'}.
 */
@Tag("e2e")
@SpringBootTest
@Import(StageCosts.WholeRun.class)
class AnswerCacheE2eTest {

    private static final String QUESTION = "How do I restart the payments service?";
    private static final String DOCUMENT = "payments-runbook";
    /** What a hit may take: a key, a lookup and a history write, against 5–50 s for a computed answer (§2.1). */
    private static final long HIT_BUDGET_MS = 200;

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void realStackWithTheAnswerCache(DynamicPropertyRegistry registry) {
        registry.add("chatbot.data-dir", () -> dataDir.toString());
        registry.add("chatbot.index.in-memory", () -> "true");
        registry.add("chatbot.embedding.onnx.model-dir", () -> ChatE2eTest.modelDir().toString());
        registry.add("embabel.models.default-llm", ChatE2eTest::llm);
        registry.add("chatbot.cache.answer.enabled", () -> "true");
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
    void aGoldenQuestionAskedAgainIsServedFromTheCacheWithTheSameCitations() throws IOException {
        // The one document the question is answered from: what is measured is the repeat, not the corpus.
        Path file = Path.of("src/test/resources/eval/docs", DOCUMENT + ".md");
        documents.upload(new DocumentService.Upload(file.getFileName().toString(), "text/markdown", Files.size(file),
                Files.newInputStream(file), DOCUMENT));
        await().atMost(Duration.ofMinutes(2)).untilAsserted(() ->
                assertThat(registry.findAll()).isNotEmpty().allMatch(d -> d.status() == DocumentStatus.READY));

        ChatResponse computed = chat.chat(new ChatRequest(null, QUESTION, null));
        assertThat(computed.citations()).anyMatch(c -> c.documentTitle().equals(DOCUMENT));
        long modelCalls = modelCalls();

        long started = System.nanoTime();
        ChatResponse served = chat.chat(new ChatRequest(null, QUESTION, null));
        long servedMs = (System.nanoTime() - started) / 1_000_000;

        List<ChatStreamEvent> events = new CopyOnWriteArrayList<>();
        started = System.nanoTime();
        chat.stream(new ChatRequest(null, QUESTION, null), events::add);
        long streamedMs = (System.nanoTime() - started) / 1_000_000;

        // Standard output rather than the log: the application's log allowlist suppresses test lines.
        System.out.printf("K02 repeat: computed %s in %d ms (retrieval %d ms, %d citations); "
                        + "served from the cache in %d ms, streamed from it in %d ms%n",
                computed.grounding(), computed.timings().totalMs(), computed.timings().retrievalMs(),
                computed.citations().size(), servedMs, streamedMs);
        assertThat(modelCalls()).as("model calls after the first answer").isEqualTo(modelCalls);
        assertThat(servedMs).isLessThan(HIT_BUDGET_MS);
        assertThat(streamedMs).isLessThan(HIT_BUDGET_MS);
        assertSameAnswer(served, computed);
        assertThat(served.timings().retrievalMs()).isZero();
        assertThat(served.timings().llmMs()).isZero();
        assertThat(events).hasSize(1).first().isInstanceOfSatisfying(ChatStreamEvent.Final.class,
                streamed -> assertSameAnswer(streamed.response(), computed));
    }

    private static void assertSameAnswer(ChatResponse actual, ChatResponse expected) {
        assertThat(actual.answer()).isEqualTo(expected.answer());
        assertThat(actual.grounding()).isEqualTo(expected.grounding());
        assertThat(actual.citations()).isEqualTo(expected.citations());
        assertThat(actual.retrievalTraceId()).isEqualTo(expected.retrievalTraceId());
        assertThat(actual.messageId()).isNotEqualTo(expected.messageId());
    }

    /** Logical AI operations so far: a hit adds none (docs/cache-plan.md §3.6). */
    private long modelCalls() {
        return meters.find("chatbot.ai.operation").timers().stream().mapToLong(Timer::count).sum();
    }
}
