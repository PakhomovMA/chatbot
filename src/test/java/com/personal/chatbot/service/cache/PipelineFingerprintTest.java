package com.personal.chatbot.service.cache;

import com.personal.chatbot.config.ChatbotProperties;
import com.personal.chatbot.models.chat.AnswerLanguage;
import com.personal.chatbot.models.chat.AnswerMode;
import com.personal.chatbot.models.embedding.EmbeddingFingerprint;
import com.personal.chatbot.models.index.IndexManifest;
import com.personal.chatbot.service.chat.GroundingInstructions;
import com.personal.chatbot.support.ChatSettings;
import com.personal.chatbot.support.IndexStores;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** K01: the pipeline fingerprint moves with each of its components (docs/cache-plan.md §3.1). */
class PipelineFingerprintTest {

    private static final String LLM = "gemma4:12b";
    private static final String INSTRUCTIONS = "c".repeat(64);
    private static final ChatbotProperties.Chat CHAT = ChatSettings.defaults();
    private static final ChatbotProperties.Retrieval RETRIEVAL =
            new ChatbotProperties.Retrieval(8, 3, 60, 0.0, 0.0, 0.3, 0, 0.6, 200);
    private static final EmbeddingFingerprint EMBEDDING =
            new EmbeddingFingerprint("onnx", "embeddinggemma-300m", "abcdefabcdef", 768, "v1", true);
    private static final IndexManifest.Chunker CHUNKER = IndexStores.CHUNKER;

    @Test
    void theSameComponentsGiveTheSameFingerprint() {
        PipelineFingerprint fingerprint = PipelineFingerprint.of(LLM, INSTRUCTIONS, CHAT, RETRIEVAL, EMBEDDING, CHUNKER);

        assertThat(fingerprint.value()).matches("[0-9a-f]{64}");
        assertThat(PipelineFingerprint.of(LLM, INSTRUCTIONS, ChatSettings.defaults(), RETRIEVAL, EMBEDDING, CHUNKER))
                .isEqualTo(fingerprint);
    }

    @Test
    void eachComponentMovesIt() {
        PipelineFingerprint base = PipelineFingerprint.of(LLM, INSTRUCTIONS, CHAT, RETRIEVAL, EMBEDDING, CHUNKER);

        assertThat(List.of(
                PipelineFingerprint.of("qwen3:14b", INSTRUCTIONS, CHAT, RETRIEVAL, EMBEDDING, CHUNKER),
                PipelineFingerprint.of(LLM, "d".repeat(64), CHAT, RETRIEVAL, EMBEDDING, CHUNKER),
                PipelineFingerprint.of(LLM, INSTRUCTIONS, chatAtTemperature(0.3), RETRIEVAL, EMBEDDING, CHUNKER),
                PipelineFingerprint.of(LLM, INSTRUCTIONS, ChatSettings.of(ChatSettings.NO_EXPANSION,
                        new ChatbotProperties.Decompose(true, 3, 4), ChatSettings.NO_COMPARISON), RETRIEVAL, EMBEDDING, CHUNKER),
                PipelineFingerprint.of(LLM, INSTRUCTIONS, CHAT,
                        new ChatbotProperties.Retrieval(5, 3, 60, 0.0, 0.0, 0.3, 0, 0.6, 200), EMBEDDING, CHUNKER),
                PipelineFingerprint.of(LLM, INSTRUCTIONS, CHAT, RETRIEVAL,
                        new EmbeddingFingerprint("ollama", "embeddinggemma:300m", "abcdefabcdef", 768, "v1", true), CHUNKER),
                PipelineFingerprint.of(LLM, INSTRUCTIONS, CHAT, RETRIEVAL, EMBEDDING,
                        new IndexManifest.Chunker(800, 100, CHUNKER.transformerVersion()))))
                .doesNotContain(base)
                .doesNotHaveDuplicates();
    }

    /** The instructions digest covers what the templates render, parameters included. */
    @Test
    void aChangedInstructionChangesTheInstructionsDigest() {
        String digest = new GroundingInstructions(4, 3, 3, 4).digest();

        assertThat(new GroundingInstructions(4, 3, 3, 4).digest()).isEqualTo(digest);
        assertThat(new GroundingInstructions(5, 3, 3, 4).digest()).isNotEqualTo(digest);
        assertThat(new GroundingInstructions(4, 3, 3, 5).digest()).isNotEqualTo(digest);
    }

    private static ChatbotProperties.Chat chatAtTemperature(double temperature) {
        return new ChatbotProperties.Chat(AnswerMode.DETERMINISTIC, AnswerLanguage.AUTO, 4, 0.2, Duration.ofSeconds(20),
                Duration.ofMinutes(10), temperature, 6000, 600, 10, 1000, Duration.ofHours(24), ChatSettings.NO_EXPANSION,
                ChatSettings.NO_DECOMPOSITION, ChatSettings.NO_COMPARISON, ChatSettings.SECTION_TOOLS);
    }
}
