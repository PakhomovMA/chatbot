package com.personal.chatbot.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import org.springframework.util.unit.DataSize;

import java.nio.file.Path;
import java.util.Set;

/**
 * Application-level settings under the {@code chatbot.*} prefix.
 *
 * @param dataDir   root directory for persistent local state: Lucene index, document registry,
 *                  uploaded originals and embedding model files (docs/system-plan.md §9).
 * @param embedding embedding provider settings (docs/system-plan.md D3).
 */
@Validated
@ConfigurationProperties(prefix = "chatbot")
public record ChatbotProperties(
        @NotNull Path dataDir,
        @Valid @DefaultValue Embedding embedding,
        @Valid @DefaultValue Knowledge knowledge,
        @Valid @DefaultValue Index index,
        @Valid @DefaultValue Ingestion ingestion
) {

    /**
     * @param provider             {@code onnx} (in-process EmbeddingGemma) or {@code ollama} (fallback).
     *                             Any other value creates no embedding bean; tests use {@code fake}.
     * @param batchSize            texts per backend call.
     * @param maxConcurrentBatches backend calls allowed to run in parallel (CPU protection).
     * @param normalize            L2-normalise vectors after the backend (Lucene uses cosine).
     */
    public record Embedding(
            @NotBlank @DefaultValue("onnx") String provider,
            @Valid @DefaultValue Onnx onnx,
            @Valid @DefaultValue Ollama ollama,
            @Min(1) @DefaultValue("16") int batchSize,
            @Min(1) @DefaultValue("2") int maxConcurrentBatches,
            @DefaultValue("true") boolean normalize
    ) {
    }

    /**
     * @param modelDir       directory with {@code model.onnx}, {@code model.onnx_data}, {@code tokenizer.json};
     *                       defaults to {@code <data-dir>/models/embeddinggemma-300m} when null.
     * @param maxTokens      tokenizer truncation limit (EmbeddingGemma context is 2048).
     * @param intraOpThreads ONNX Runtime intra-op threads; 0 = min(4, available processors).
     * @param dimensions     expected output dimensionality; startup fails if the model disagrees.
     */
    public record Onnx(
            @Nullable Path modelDir,
            @NotBlank @DefaultValue("model.onnx") String modelFile,
            @NotBlank @DefaultValue("tokenizer.json") String tokenizerFile,
            @NotBlank @DefaultValue("embeddinggemma-300m") String modelName,
            @Min(16) @DefaultValue("2048") int maxTokens,
            @Min(0) @DefaultValue("0") int intraOpThreads,
            @Min(1) @DefaultValue("768") int dimensions
    ) {
    }

    /**
     * @param maxUploadSize     hard limit for one uploaded file (also mirrored in spring.servlet.multipart).
     * @param allowedExtensions lower-case extensions accepted for upload (docs/system-plan.md D9).
     */
    public record Knowledge(
            @DefaultValue("20MB") DataSize maxUploadSize,
            @DefaultValue({"md", "markdown", "txt", "html", "htm", "pdf", "docx"}) Set<String> allowedExtensions
    ) {
    }

    /**
     * @param dir                directory holding {@code lucene/} and {@code manifest.json}; defaults to {@code <data-dir>/index}.
     * @param inMemory           keep the index in memory only (tests, experiments).
     * @param maxChunkSize       chunk size in characters (docs/system-plan.md D8).
     * @param overlapSize        overlap between consecutive chunks of one section.
     * @param embeddingBatchSize chunks per embedding call during ingestion.
     */
    public record Index(
            @Nullable Path dir,
            @DefaultValue("false") boolean inMemory,
            @Min(100) @DefaultValue("1200") int maxChunkSize,
            @Min(0) @DefaultValue("150") int overlapSize,
            @Min(1) @DefaultValue("32") int embeddingBatchSize
    ) {
    }

    /**
     * @param autoResume        on startup, queue documents that still need indexing (uploaded, pending re-index).
     * @param retryInterrupted  on startup, also re-queue documents whose ingestion was interrupted by a crash.
     */
    public record Ingestion(
            @DefaultValue("true") boolean autoResume,
            @DefaultValue("true") boolean retryInterrupted
    ) {
    }

    public record Ollama(
            @NotBlank @DefaultValue("http://localhost:11434") String baseUrl,
            @NotBlank @DefaultValue("embeddinggemma:300m") String model
    ) {
    }
}
