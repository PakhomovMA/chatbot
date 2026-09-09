package com.personal.chatbot;

import com.personal.chatbot.models.chat.AnswerMode;
import com.personal.chatbot.models.chat.ChatRequest;
import com.personal.chatbot.models.chat.ChatResponse;
import com.personal.chatbot.models.chat.ChatStreamEvent;
import com.personal.chatbot.models.chat.Grounding;
import com.personal.chatbot.models.knowledge.DocumentStatus;
import com.personal.chatbot.service.chat.ChatService;
import com.personal.chatbot.service.knowledge.DocumentRegistry;
import com.personal.chatbot.service.knowledge.DocumentService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Regression for a compound RU question whose second answer was inferred from a brand name. */
@Tag("e2e")
@SpringBootTest
class AgenticDecompositionE2eTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void isolatedRealStack(DynamicPropertyRegistry registry) {
        registry.add("chatbot.data-dir", () -> dataDir.toString());
        registry.add("chatbot.index.in-memory", () -> "true");
        registry.add("chatbot.embedding.onnx.model-dir", () -> ChatE2eTest.modelDir().toString());
        registry.add("embabel.models.default-llm", ChatE2eTest::llm);
        registry.add("chatbot.chat.decompose.enabled", () -> "true");
        registry.add("server.port", () -> "0");
    }

    @BeforeAll
    static void requireLocalStack() {
        assumeTrue(Files.isRegularFile(ChatE2eTest.modelDir().resolve("model.onnx")));
        assumeTrue(ChatE2eTest.ollamaHasModel(ChatE2eTest.llm()));
    }

    @Autowired
    private DocumentService documents;
    @Autowired
    private DocumentRegistry registry;
    @Autowired
    private ChatService chat;

    @BeforeEach
    void seed() throws IOException {
        upload("glm", "# GLM-5.3\nGLM-5.3 supports a 1M-token context window.");
        upload("kimi", "# Kimi-K3\nKimi-K3 was developed by Moonshot AI. Kimi is the model brand, not the company's name.");
        upload("lyra", "# Lyra-R7\nLyra-R7 is Lyra's flagship model with a 256K-token context window.");
        await().atMost(Duration.ofMinutes(1)).untilAsserted(() ->
                assertThat(registry.findAll()).hasSize(3).allMatch(d -> d.status() == DocumentStatus.READY));
    }

    private void upload(String name, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        documents.upload(new DocumentService.Upload(name + ".md", "text/markdown", bytes.length,
                new ByteArrayInputStream(bytes), name));
    }

    @Test
    void explicitDeveloperIsAnsweredAndCitedAlongsideTheContextLength() {
        ChatResponse response = chat.chat(new ChatRequest(null,
                "Какая длина контекста у GLM-5.3 и кто разработал Kimi-K3?",
                new ChatRequest.Options(null, null, true, AnswerMode.AGENTIC)));
        assertThat(response.answer()).containsIgnoringCase("Moonshot AI").containsAnyOf("1M", "1 M", "1 млн", "миллион");
        assertThat(response.grounding()).isEqualTo(Grounding.GROUNDED);
        assertThat(response.citations()).anyMatch(c -> c.quote().contains("Moonshot AI"))
                .anyMatch(c -> c.quote().contains("1M-token"));
        assertThat(response.diagnostics().decomposition()).isNotNull();
        assertThat(response.diagnostics().decomposition().subQuestions()).hasSizeGreaterThanOrEqualTo(2);
        System.out.println("Explicit developer: " + response.answer());
    }

    @Test
    void streamingAnswerReportsMissingDeveloperInsteadOfInventingACompany() {
        List<ChatStreamEvent> events = new ArrayList<>();
        chat.stream(new ChatRequest(null, "Какая длина контекста у GLM-5.3 и кто разработал Lyra-R7?",
                new ChatRequest.Options(null, null, true, AnswerMode.AGENTIC)), events::add);
        assertThat(events.getLast()).isInstanceOf(ChatStreamEvent.Final.class);
        ChatResponse response = ((ChatStreamEvent.Final) events.getLast()).response();
        assertThat(events).anyMatch(e -> e instanceof ChatStreamEvent.Status s && s.stage().equals("decomposing"));
        assertThat(response.grounding()).isEqualTo(Grounding.INSUFFICIENT_EVIDENCE);
        assertThat(response.notes()).isNotBlank();
        assertThat(response.answer()).containsAnyOf("1M", "1 M", "1 млн", "миллион")
                .doesNotContain("компанией Lyra", "компания Lyra");
        assertThat(response.citations()).anyMatch(c -> c.quote().contains("1M-token"));
        System.out.println("Missing developer: " + response.answer());
    }
}
