package com.personal.chatbot.service.embedding;

import com.personal.chatbot.exceptions.EmbeddingModelUnavailableException;
import com.personal.chatbot.models.embedding.EmbeddingMode;
import com.personal.chatbot.utils.EmbeddingModeScope;
import com.personal.chatbot.utils.EmbeddingPrompts;
import com.personal.chatbot.utils.Hashes;
import com.personal.chatbot.utils.VectorMath;

import com.personal.chatbot.service.embedding.ollama.OllamaTextEmbedder;
import com.personal.chatbot.service.embedding.onnx.OnnxModelFiles;
import com.personal.chatbot.service.embedding.onnx.OnnxTextEmbedder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Phase 1 gate against the real EmbeddingGemma ONNX files (docs/system-plan.md §14, R1/R2).
 * Needs {@code ~/.chatbot/models/embeddinggemma-300m} (or {@code CHATBOT_MODEL_DIR}); run with
 * {@code ./gradlew test -PincludeTags=model}.
 */
@Tag("model")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OnnxEmbeddingGemmaModelTest {

    private static final Logger log = LoggerFactory.getLogger(OnnxEmbeddingGemmaModelTest.class);
    private static final long GEMMA_BOS = 2;
    private static final long GEMMA_EOS = 1;

    private OnnxTextEmbedder backend;
    private PromptedEmbeddingService service;

    static Path modelDir() {
        String override = System.getenv("CHATBOT_MODEL_DIR");
        return override != null ? Path.of(override)
                : Path.of(System.getProperty("user.home"), ".chatbot", "models", "embeddinggemma-300m");
    }

    @BeforeAll
    void loadModel() {
        assumeTrue(Files.isRegularFile(modelDir().resolve("model.onnx")), "model files not present in " + modelDir());
        long started = System.nanoTime();
        OnnxModelFiles files = OnnxModelFiles.resolve(modelDir(), "model.onnx", "tokenizer.json");
        backend = new OnnxTextEmbedder(files, "embeddinggemma-300m", 2048, 0, 768);
        service = new PromptedEmbeddingService(backend, 16, 2, true, new SimpleMeterRegistry());
        service.warmUp();
        log.info("Model load + warm-up took {} ms", (System.nanoTime() - started) / 1_000_000);
        assertThat(service.warmupDuration()).isPresent();
    }

    @AfterAll
    void close() {
        if (service != null) {
            service.close();
        }
    }

    @Test
    void produces768DimensionalUnitVectors() {
        float[] vector = service.embed("Restart the service with systemctl restart payments.");
        assertThat(vector).hasSize(768);
        assertThat(VectorMath.norm(vector)).isCloseTo(1.0, within(1e-3));
        assertThat(service.fingerprint().value()).startsWith("onnx/embeddinggemma-300m/").endsWith("/768/" + EmbeddingPrompts.PREFIX_VERSION + "/l2");
        assertThat(service.fingerprint().artifactHash()).hasSize(Hashes.SHORT_DIGEST_LENGTH);
    }

    @Test
    void tokenizerAddsGemmaSpecialTokensAndTruncates() {
        long[] ids = backend.tokenIds("hello world");
        assertThat(ids[0]).isEqualTo(GEMMA_BOS);
        assertThat(ids[ids.length - 1]).isEqualTo(GEMMA_EOS);
        assertThat(ids.length).isBetween(3, 8);

        String longText = "word ".repeat(5000);
        assertThat(backend.tokenIds(longText).length).isLessThanOrEqualTo(2048);
        assertThat(backend.embed(List.of(longText)).getFirst()).hasSize(768);
    }

    @Test
    void semanticallySimilarTextsAreCloser() {
        float[] query = EmbeddingModeScope.inQueryMode(() -> service.embed("how do I restart the payment service?"));
        float[] relevant = service.embed("To restart the payment service run `systemctl restart payments` on the host.");
        float[] unrelated = service.embed("Bananas are grown in tropical climates and ripen after harvest.");
        double relevantScore = VectorMath.cosine(query, relevant);
        double unrelatedScore = VectorMath.cosine(query, unrelated);
        log.info("cosine relevant={} unrelated={}", relevantScore, unrelatedScore);
        assertThat(relevantScore).isGreaterThan(unrelatedScore + 0.15);
    }

    @Test
    void batchEqualsSingleAndOrderIsPreserved() {
        List<String> texts = List.of(
                "short",
                "A considerably longer sentence describing how to configure the retry policy of the ingestion queue.",
                "Medium length text about Lucene.",
                "Another one about ONNX runtime threads and batching behaviour on Apple Silicon machines.");
        List<float[]> batch = service.embed(texts);
        for (int i = 0; i < texts.size(); i++) {
            double cosine = VectorMath.cosine(batch.get(i), service.embed(texts.get(i)));
            assertThat(cosine).as("text %d", i).isCloseTo(1.0, within(1e-3));
        }
    }

    @Test
    void queryAndDocumentPrefixesProduceDifferentVectors() {
        String text = "restart the payment service";
        float[] asDocument = service.embed(text);
        float[] asQuery = EmbeddingModeScope.inQueryMode(() -> service.embed(text));
        assertThat(VectorMath.cosine(asDocument, asQuery)).isLessThan(0.999);
    }

    @Test
    void embedsThirtyTwoChunksWithinBudget() {
        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            chunks.add("Chunk " + i + ". " + "The ingestion pipeline parses documents with Tika, splits them into "
                    + "sections, embeds every chunk with EmbeddingGemma and writes vectors into the Lucene index. ".repeat(4));
        }
        service.embed(chunks); // warm
        long started = System.nanoTime();
        List<float[]> vectors = service.embed(chunks);
        long millis = (System.nanoTime() - started) / 1_000_000;
        log.info("Embedded 32 chunks (~{} chars each) in {} ms", chunks.getFirst().length(), millis);
        assertThat(vectors).hasSize(32);
        assertThat(millis).as("32 chunks should embed well under 10 s; plan guideline is 2 s").isLessThan(10_000);
    }

    /** R1: the ONNX export must agree with an independent implementation of the same model (Ollama GGUF). */
    @Test
    @Tag("e2e")
    void agreesWithOllamaReferenceImplementation() {
        OllamaTextEmbedder reference;
        try {
            reference = new OllamaTextEmbedder("http://localhost:11434", "embeddinggemma:300m");
        } catch (EmbeddingModelUnavailableException e) {
            assumeTrue(false, "Ollama reference not available: " + e.getMessage());
            return;
        }
        List<String> prefixed = List.of(
                EmbeddingPrompts.forMode(EmbeddingMode.DOCUMENT, "To restart the payment service run systemctl restart payments."),
                EmbeddingPrompts.forMode(EmbeddingMode.QUERY, "how do I restart the payment service?"),
                EmbeddingPrompts.forMode(EmbeddingMode.DOCUMENT, "Bananas are grown in tropical climates."));
        List<float[]> onnx = backend.embed(prefixed);
        List<float[]> ollama = reference.embed(prefixed);
        for (int i = 0; i < prefixed.size(); i++) {
            double cosine = VectorMath.cosine(VectorMath.normalized(onnx.get(i)), VectorMath.normalized(ollama.get(i)));
            log.info("ONNX vs Ollama cosine for text {}: {}", i, cosine);
            assertThat(cosine).as("text %d", i).isGreaterThan(0.95);
        }
    }
}
