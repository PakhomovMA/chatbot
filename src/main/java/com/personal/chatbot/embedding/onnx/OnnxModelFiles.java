package com.personal.chatbot.embedding.onnx;

import com.personal.chatbot.embedding.EmbeddingFingerprint;
import com.personal.chatbot.embedding.EmbeddingModelUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Locates the EmbeddingGemma ONNX artifacts on disk and explains what is missing.
 *
 * @param modelFile     ONNX graph ({@code model.onnx})
 * @param dataFile      external weights ({@code model.onnx_data}), present for the fp32 export
 * @param tokenizerFile HuggingFace {@code tokenizer.json}
 */
public record OnnxModelFiles(Path modelFile, Optional<Path> dataFile, Path tokenizerFile) {

    private static final Logger log = LoggerFactory.getLogger(OnnxModelFiles.class);

    public static final String HF_REPO = "onnx-community/embeddinggemma-300m-ONNX";

    public static OnnxModelFiles resolve(Path modelDir, String modelFileName, String tokenizerFileName) {
        Path model = modelDir.resolve(modelFileName);
        Path tokenizer = modelDir.resolve(tokenizerFileName);
        Path data = modelDir.resolve(modelFileName + "_data");
        if (!Files.isRegularFile(model) || !Files.isRegularFile(tokenizer)) {
            throw new EmbeddingModelUnavailableException("""
                    EmbeddingGemma ONNX files not found in %s (missing: %s%s).
                    Download from https://huggingface.co/%s :
                      onnx/%s  and  onnx/%s_data  and  tokenizer.json
                    into that directory, or point chatbot.embedding.onnx.model-dir elsewhere, \
                    or switch to chatbot.embedding.provider=ollama.""".formatted(
                    modelDir,
                    Files.isRegularFile(model) ? "" : modelFileName + " ",
                    Files.isRegularFile(tokenizer) ? "" : tokenizerFileName,
                    HF_REPO, modelFileName, modelFileName));
        }
        Optional<Path> dataFile = Files.isRegularFile(data) ? Optional.of(data) : Optional.empty();
        if (dataFile.isEmpty()) {
            log.warn("No external weights file {} next to {}; assuming a self-contained ONNX model", data.getFileName(), model);
        }
        return new OnnxModelFiles(model, dataFile, tokenizer);
    }

    /** Digest of the weights: the external data file when present, otherwise the graph file. Cached in a sidecar file. */
    public String artifactHash() {
        Path hashed = dataFile.orElse(modelFile);
        long started = System.nanoTime();
        String hash = EmbeddingFingerprint.cachedSha256Prefix(hashed);
        log.info("Hashed {} in {} ms: {}", hashed.getFileName(), (System.nanoTime() - started) / 1_000_000, hash);
        return hash;
    }
}
