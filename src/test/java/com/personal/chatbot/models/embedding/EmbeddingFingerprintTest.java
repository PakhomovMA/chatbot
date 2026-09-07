package com.personal.chatbot.models.embedding;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddingFingerprintTest {

    @Test
    void valueIsCanonicalAndStable() {
        EmbeddingFingerprint a = new EmbeddingFingerprint("onnx", "embeddinggemma-300m", "abcdef012345", 768, "gemma-prefix-v1", true);
        EmbeddingFingerprint b = new EmbeddingFingerprint("onnx", "embeddinggemma-300m", "abcdef012345", 768, "gemma-prefix-v1", true);
        assertThat(a.value()).isEqualTo("onnx/embeddinggemma-300m/abcdef012345/768/gemma-prefix-v1/l2");
        assertThat(a).isEqualTo(b);
    }

    @Test
    void anyComponentChangesTheValue() {
        EmbeddingFingerprint base = new EmbeddingFingerprint("onnx", "m", "h", 768, "v1", true);
        assertThat(base.value())
                .isNotEqualTo(new EmbeddingFingerprint("ollama", "m", "h", 768, "v1", true).value())
                .isNotEqualTo(new EmbeddingFingerprint("onnx", "m", "h", 256, "v1", true).value())
                .isNotEqualTo(new EmbeddingFingerprint("onnx", "m", "h", 768, "v2", true).value())
                .isNotEqualTo(new EmbeddingFingerprint("onnx", "m", "h", 768, "v1", false).value());
        assertThat(new EmbeddingFingerprint("onnx", "m", "h", 768, "v1", false).value()).endsWith("/raw");
    }
}
