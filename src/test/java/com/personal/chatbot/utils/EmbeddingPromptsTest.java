package com.personal.chatbot.utils;

import com.personal.chatbot.models.embedding.EmbeddingMode;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddingPromptsTest {

    @Test
    void usesEmbeddingGemmaRetrievalPrefixes() {
        assertThat(EmbeddingPrompts.forMode(EmbeddingMode.QUERY, "how to restart"))
                .isEqualTo("task: search result | query: how to restart");
        assertThat(EmbeddingPrompts.forMode(EmbeddingMode.DOCUMENT, "Restart with systemctl."))
                .isEqualTo("title: none | text: Restart with systemctl.");
    }

    @Test
    void defaultModeIsDocumentAndQueryModeIsScoped() {
        assertThat(EmbeddingModeScope.current()).isEqualTo(EmbeddingMode.DOCUMENT);
        EmbeddingMode inside = EmbeddingModeScope.inQueryMode(EmbeddingModeScope::current);
        assertThat(inside).isEqualTo(EmbeddingMode.QUERY);
        assertThat(EmbeddingModeScope.current()).isEqualTo(EmbeddingMode.DOCUMENT);
    }
}
