package com.personal.chatbot.service.embedding.ollama;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OllamaTextEmbedderTest {

    @Test
    void untaggedNameMeansLatest() {
        assertThat(OllamaTextEmbedder.withDefaultTag("embeddinggemma-2")).isEqualTo("embeddinggemma-2:latest");
        assertThat(OllamaTextEmbedder.withDefaultTag("embeddinggemma:300m")).isEqualTo("embeddinggemma:300m");
        assertThat(OllamaTextEmbedder.withDefaultTag("embeddinggemma-2:latest")).isEqualTo("embeddinggemma-2:latest");
    }
}
