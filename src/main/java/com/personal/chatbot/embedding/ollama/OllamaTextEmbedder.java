package com.personal.chatbot.embedding.ollama;

import com.personal.chatbot.embedding.EmbeddingFingerprint;
import com.personal.chatbot.embedding.EmbeddingModelUnavailableException;
import com.personal.chatbot.embedding.TextEmbedder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;

import java.util.List;

/**
 * Fallback backend: EmbeddingGemma served by Ollama over HTTP (Spring AI {@link OllamaEmbeddingModel}).
 * Same prefixes as the ONNX path, but a different artifact (GGUF) and therefore a different fingerprint.
 */
public final class OllamaTextEmbedder implements TextEmbedder {

    private static final Logger log = LoggerFactory.getLogger(OllamaTextEmbedder.class);

    private final OllamaEmbeddingModel model;
    private final String modelName;
    private final String artifactHash;
    private final int dimensions;

    public OllamaTextEmbedder(String baseUrl, String modelName) {
        this.modelName = modelName;
        OllamaApi api = OllamaApi.builder().baseUrl(baseUrl).build();
        this.artifactHash = lookupDigest(api, baseUrl, modelName);
        this.model = OllamaEmbeddingModel.builder()
                .ollamaApi(api)
                .options(OllamaEmbeddingOptions.builder().model(modelName).build())
                .build();
        this.dimensions = model.embed("dimension probe").length;
        log.info("Ollama embedding model {} at {}: {} dimensions, digest {}", modelName, baseUrl, dimensions, artifactHash);
    }

    private static String lookupDigest(OllamaApi api, String baseUrl, String modelName) {
        List<OllamaApi.Model> models;
        try {
            models = api.listModels().models();
        } catch (RuntimeException e) {
            throw new EmbeddingModelUnavailableException("Ollama is not reachable at " + baseUrl + ": " + e.getMessage(), e);
        }
        return models.stream()
                .filter(m -> modelName.equals(m.name()) || modelName.equals(m.model()))
                .map(m -> EmbeddingFingerprint.shortHash(m.digest()))
                .findFirst()
                .orElseThrow(() -> new EmbeddingModelUnavailableException(
                        "Model " + modelName + " is not installed in Ollama at " + baseUrl + "; run: ollama pull " + modelName));
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        return model.embed(texts);
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public String provider() {
        return "ollama";
    }

    @Override
    public String modelName() {
        return modelName;
    }

    @Override
    public String artifactHash() {
        return artifactHash;
    }
}
