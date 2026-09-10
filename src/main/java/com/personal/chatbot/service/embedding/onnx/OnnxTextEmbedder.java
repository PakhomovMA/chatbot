package com.personal.chatbot.service.embedding.onnx;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.personal.chatbot.exceptions.EmbeddingModelUnavailableException;
import com.personal.chatbot.observability.MonotonicClock;
import com.personal.chatbot.service.embedding.TextEmbedder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.LongBuffer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * In-process EmbeddingGemma inference with ONNX Runtime and the DJL HuggingFace tokenizer.
 *
 * <p>Verified against the {@code onnx-community/embeddinggemma-300m-ONNX} export: inputs
 * {@code input_ids} + {@code attention_mask}, outputs {@code last_hidden_state} and
 * {@code sentence_embedding} (pooling, dense projection and normalisation are inside the graph).
 * Embabel's own ONNX service cannot be used because it feeds {@code token_type_ids} and mean-pools
 * the first output.
 */
public final class OnnxTextEmbedder implements TextEmbedder {

    private static final Logger log = LoggerFactory.getLogger(OnnxTextEmbedder.class);

    static final String INPUT_IDS = "input_ids";
    static final String ATTENTION_MASK = "attention_mask";
    static final String TOKEN_TYPE_IDS = "token_type_ids";
    static final String SENTENCE_EMBEDDING = "sentence_embedding";
    private static final Set<String> SUPPORTED_INPUTS = Set.of(INPUT_IDS, ATTENTION_MASK, TOKEN_TYPE_IDS);
    private static final int MAX_INTRA_OP_THREADS = 4;

    private final String modelName;
    private final OrtEnvironment environment;
    private final OrtSession session;
    private final HuggingFaceTokenizer tokenizer;
    private final String outputName;
    private final boolean needsTokenTypeIds;
    private final int dimensions;
    private final String artifactHash;

    public OnnxTextEmbedder(OnnxModelFiles files, String modelName, int maxTokens, int intraOpThreads, int expectedDimensions) {
        this.modelName = modelName;
        this.environment = OrtEnvironment.getEnvironment();
        this.session = openSession(files.modelFile(), intraOpThreads);
        try {
            Set<String> inputs = session.getInputNames();
            Set<String> outputs = session.getOutputNames();
            log.info("ONNX model {} loaded; inputs={}, outputs={}", files.modelFile().getFileName(), inputs, outputs);
            validateInputs(inputs);
            this.needsTokenTypeIds = inputs.contains(TOKEN_TYPE_IDS);
            this.outputName = selectOutput(outputs);
            this.tokenizer = openTokenizer(files.tokenizerFile(), maxTokens);
            this.dimensions = probeDimensions(expectedDimensions);
            this.artifactHash = files.artifactHash();
        } catch (RuntimeException e) {
            close();
            throw e;
        }
    }

    private OrtSession openSession(java.nio.file.Path modelFile, int intraOpThreads) {
        long started = MonotonicClock.SYSTEM.nanoTime();
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            int threads = intraOpThreads > 0 ? intraOpThreads
                    : Math.min(MAX_INTRA_OP_THREADS, Runtime.getRuntime().availableProcessors());
            options.setIntraOpNumThreads(threads);
            OrtSession created = environment.createSession(modelFile.toString(), options);
            log.info("ONNX session created in {} ms with {} intra-op threads", MonotonicClock.SYSTEM.since(started).toMillis(), threads);
            return created;
        } catch (OrtException e) {
            throw new EmbeddingModelUnavailableException("Cannot load ONNX model " + modelFile + ": " + e.getMessage(), e);
        }
    }

    private static void validateInputs(Set<String> inputs) {
        if (!inputs.contains(INPUT_IDS) || !inputs.contains(ATTENTION_MASK)) {
            throw new EmbeddingModelUnavailableException("ONNX model must accept " + INPUT_IDS + " and "
                    + ATTENTION_MASK + " but declares " + inputs);
        }
        Set<String> unexpected = new HashSet<>(inputs);
        unexpected.removeAll(SUPPORTED_INPUTS);
        if (!unexpected.isEmpty()) {
            throw new EmbeddingModelUnavailableException("ONNX model requires unsupported inputs " + unexpected);
        }
    }

    private static String selectOutput(Set<String> outputs) {
        if (outputs.contains(SENTENCE_EMBEDDING)) {
            return SENTENCE_EMBEDDING;
        }
        if (outputs.isEmpty()) {
            throw new EmbeddingModelUnavailableException("ONNX model declares no outputs");
        }
        String last = List.copyOf(outputs).getLast();
        log.warn("No '{}' output; using last output '{}' and expecting a [batch, dim] tensor", SENTENCE_EMBEDDING, last);
        return last;
    }

    private static HuggingFaceTokenizer openTokenizer(java.nio.file.Path tokenizerFile, int maxTokens) {
        try {
            // DJL clamps maxLength to modelMaxLength (default 512) unless it is raised explicitly.
            return HuggingFaceTokenizer.builder(Map.of("modelMaxLength", Integer.toString(maxTokens)))
                    .optTokenizerPath(tokenizerFile)
                    .optAddSpecialTokens(true)
                    .optTruncation(true)
                    .optPadding(false)
                    .optMaxLength(maxTokens)
                    .build();
        } catch (IOException e) {
            throw new EmbeddingModelUnavailableException("Cannot load tokenizer " + tokenizerFile + ": " + e.getMessage(), e);
        }
    }

    private int probeDimensions(int expected) {
        int actual = embed(List.of("dimension probe")).getFirst().length;
        if (actual != expected) {
            throw new EmbeddingModelUnavailableException("ONNX model produces " + actual
                    + " dimensions but chatbot.embedding.onnx.dimensions=" + expected);
        }
        return actual;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }
        PaddedBatch batch = PaddedBatch.of(encode(texts));
        try (OnnxTensor ids = batch.tensor(environment, batch.ids);
             OnnxTensor mask = batch.tensor(environment, batch.mask);
             OnnxTensor typeIds = needsTokenTypeIds ? batch.tensor(environment, new long[batch.ids.length]) : null) {
            Map<String, OnnxTensor> inputs = new LinkedHashMap<>();
            inputs.put(INPUT_IDS, ids);
            inputs.put(ATTENTION_MASK, mask);
            if (typeIds != null) {
                inputs.put(TOKEN_TYPE_IDS, typeIds);
            }
            try (OrtSession.Result result = session.run(inputs, Set.of(outputName))) {
                return switch (result.get(outputName).orElseThrow().getValue()) {
                    case float[][] matrix -> Arrays.asList(matrix);
                    case Object other -> throw new IllegalStateException("Output '" + outputName
                            + "' is not a [batch, dim] float tensor but " + other.getClass().getSimpleName()
                            + " (a 3-D output means token embeddings, not sentence embeddings)");
                };
            }
        } catch (OrtException e) {
            throw new IllegalStateException("ONNX inference failed: " + e.getMessage(), e);
        }
    }

    /** Token ids for one text (after truncation and special tokens); exposed for diagnostics and tests. */
    public long[] tokenIds(String text) {
        return encode(List.of(text))[0].getIds();
    }

    /**
     * The DJL tokenizer is not documented as safe for concurrent use and wraps native state, so
     * batches are encoded one at a time. Inference itself is not serialised — ONNX Runtime handles
     * concurrent {@code run} calls, and the batch limit above it is the real throttle
     * (docs/concurrency-plan.md C09).
     */
    private Encoding[] encode(List<String> texts) {
        synchronized (tokenizer) {
            return tokenizer.batchEncode(texts);
        }
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public String provider() {
        return "onnx";
    }

    @Override
    public String modelName() {
        return modelName;
    }

    @Override
    public String artifactHash() {
        return artifactHash;
    }

    @Override
    public void close() {
        if (tokenizer != null) {
            try {
                tokenizer.close();
            } catch (RuntimeException e) {
                log.debug("Tokenizer close failed", e);
            }
        }
        try {
            session.close();
        } catch (OrtException | IllegalStateException e) {
            log.debug("Session close failed", e);
        }
    }

    /** Right-padded {@code [batch, maxLen]} id/mask matrices (pad id 0 = Gemma {@code <pad>}). */
    private record PaddedBatch(long[] ids, long[] mask, long[] shape) {

        static PaddedBatch of(Encoding[] encodings) {
            int batch = encodings.length;
            int maxLen = Arrays.stream(encodings).mapToInt(encoding -> encoding.getIds().length).max().orElse(0);
            long[] ids = new long[batch * maxLen];
            long[] mask = new long[batch * maxLen];
            for (int i = 0; i < batch; i++) {
                long[] tokenIds = encodings[i].getIds();
                long[] attention = encodings[i].getAttentionMask();
                System.arraycopy(tokenIds, 0, ids, i * maxLen, tokenIds.length);
                System.arraycopy(attention, 0, mask, i * maxLen, attention.length);
            }
            return new PaddedBatch(ids, mask, new long[]{batch, maxLen});
        }

        OnnxTensor tensor(OrtEnvironment environment, long[] values) throws OrtException {
            return OnnxTensor.createTensor(environment, LongBuffer.wrap(values), shape);
        }
    }
}
