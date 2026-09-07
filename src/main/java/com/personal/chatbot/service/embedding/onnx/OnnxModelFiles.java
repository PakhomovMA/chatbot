package com.personal.chatbot.service.embedding.onnx;

import com.personal.chatbot.exceptions.EmbeddingModelUnavailableException;
import com.personal.chatbot.utils.Hashes;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Locates the EmbeddingGemma ONNX artifacts on disk and explains what is missing.
 *
 * @param modelFile     ONNX graph ({@code model.onnx})
 * @param dataFile      external weights ({@code model.onnx_data}); null for self-contained exports
 * @param tokenizerFile HuggingFace {@code tokenizer.json}
 */
public record OnnxModelFiles(Path modelFile, @Nullable Path dataFile, Path tokenizerFile) {

    private static final Logger log = LoggerFactory.getLogger(OnnxModelFiles.class);

    public static final String HF_REPO = "onnx-community/embeddinggemma-300m-ONNX";
    private static final String DATA_SUFFIX = "_data";

    public static OnnxModelFiles resolve(Path modelDir, String modelFileName, String tokenizerFileName) {
        Path model = modelDir.resolve(modelFileName);
        Path tokenizer = modelDir.resolve(tokenizerFileName);
        Path data = modelDir.resolve(modelFileName + DATA_SUFFIX);
        boolean hasModel = Files.isRegularFile(model);
        boolean hasTokenizer = Files.isRegularFile(tokenizer);
        if (!hasModel || !hasTokenizer) {
            throw new EmbeddingModelUnavailableException("""
                    EmbeddingGemma ONNX files not found in %s (missing: %s%s).
                    Download from https://huggingface.co/%s :
                      onnx/%s  and  onnx/%s%s  and  %s
                    into that directory, or point chatbot.embedding.onnx.model-dir elsewhere, \
                    or switch to chatbot.embedding.provider=ollama.""".formatted(
                    modelDir,
                    hasModel ? "" : modelFileName + " ",
                    hasTokenizer ? "" : tokenizerFileName,
                    HF_REPO, modelFileName, modelFileName, DATA_SUFFIX, tokenizerFileName));
        }
        if (!Files.isRegularFile(data)) {
            log.warn("No external weights file {} next to {}; assuming a self-contained ONNX model",
                    data.getFileName(), model);
            return new OnnxModelFiles(model, null, tokenizer);
        }
        return new OnnxModelFiles(model, data, tokenizer);
    }

    /** The file that carries the weights: external data when present, otherwise the graph itself. */
    public Path weightsFile() {
        return dataFile != null ? dataFile : modelFile;
    }

    /** Short digest of {@link #weightsFile()}, cached in a sidecar file across restarts. */
    public String artifactHash() {
        Path weights = weightsFile();
        long started = System.nanoTime();
        String hash = Hashes.cachedSha256Prefix(weights);
        log.info("Weights digest of {} resolved in {} ms: {}", weights.getFileName(),
                (System.nanoTime() - started) / 1_000_000, hash);
        return hash;
    }
}
