package com.personal.chatbot.embedding.onnx;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.personal.chatbot.embedding.EmbeddingModelUnavailableException;
import com.personal.chatbot.embedding.TextEmbedder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * In-process EmbeddingGemma inference with ONNX Runtime and the DJL HuggingFace tokenizer.
 *
 * <p>Verified against the {@code onnx-community/embeddinggemma-300m-ONNX} export: inputs
 * {@code input_ids} + {@code attention_mask}, output {@code sentence_embedding} (pooling, dense
 * projection and normalisation are inside the graph). Embabel's own ONNX service cannot be used
 * because it feeds {@code token_type_ids} and mean-pools the first output.
 */
public final class OnnxTextEmbedder implements TextEmbedder {

    private static final Logger log = LoggerFactory.getLogger(OnnxTextEmbedder.class);

    static final String INPUT_IDS = "input_ids";
    static final String ATTENTION_MASK = "attention_mask";
    static final String TOKEN_TYPE_IDS = "token_type_ids";
    static final String SENTENCE_EMBEDDING = "sentence_embedding";
    private static final long PAD_TOKEN_ID = 0L;

    private final OrtEnvironment environment;
    private final OrtSession session;
    private final HuggingFaceTokenizer tokenizer;
    private final String outputName;
    private final boolean needsTokenTypeIds;
    private final int dimensions;
    private final String modelName;
    private final String artifactHash;

    public OnnxTextEmbedder(OnnxModelFiles files, String modelName, int maxTokens, int intraOpThreads, int expectedDimensions) {
        this.modelName = modelName;
        this.environment = OrtEnvironment.getEnvironment();
        long started = System.nanoTime();
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(intraOpThreads > 0 ? intraOpThreads
                    : Math.min(4, Runtime.getRuntime().availableProcessors()));
            this.session = environment.createSession(files.modelFile().toString(), options);
        } catch (OrtException e) {
            throw new EmbeddingModelUnavailableException("Cannot load ONNX model " + files.modelFile() + ": " + e.getMessage(), e);
        }
        Set<String> inputs = session.getInputNames();
        Set<String> outputs = session.getOutputNames();
        log.info("ONNX model {} loaded in {} ms; inputs={}, outputs={}",
                files.modelFile().getFileName(), (System.nanoTime() - started) / 1_000_000, inputs, outputs);
        if (!inputs.contains(INPUT_IDS) || !inputs.contains(ATTENTION_MASK)) {
            closeQuietly();
            throw new EmbeddingModelUnavailableException("ONNX model must accept " + INPUT_IDS + " and "
                    + ATTENTION_MASK + " but declares " + inputs);
        }
        this.needsTokenTypeIds = inputs.contains(TOKEN_TYPE_IDS);
        Set<String> unexpected = new java.util.HashSet<>(inputs);
        unexpected.removeAll(Set.of(INPUT_IDS, ATTENTION_MASK, TOKEN_TYPE_IDS));
        if (!unexpected.isEmpty()) {
            closeQuietly();
            throw new EmbeddingModelUnavailableException("ONNX model requires unsupported inputs " + unexpected);
        }
        this.outputName = outputs.contains(SENTENCE_EMBEDDING) ? SENTENCE_EMBEDDING : outputs.stream().reduce((a, b) -> b).orElseThrow();
        if (!SENTENCE_EMBEDDING.equals(outputName)) {
            log.warn("No '{}' output; using last output '{}' and expecting a [batch, dim] tensor", SENTENCE_EMBEDDING, outputName);
        }
        try {
            // DJL clamps maxLength to modelMaxLength (default 512) unless it is raised explicitly.
            this.tokenizer = HuggingFaceTokenizer.builder(Map.of("modelMaxLength", Integer.toString(maxTokens)))
                    .optTokenizerPath(files.tokenizerFile())
                    .optAddSpecialTokens(true)
                    .optTruncation(true)
                    .optPadding(false)
                    .optMaxLength(maxTokens)
                    .build();
        } catch (IOException e) {
            closeQuietly();
            throw new EmbeddingModelUnavailableException("Cannot load tokenizer " + files.tokenizerFile() + ": " + e.getMessage(), e);
        }
        this.dimensions = probeDimensions(expectedDimensions);
        this.artifactHash = files.artifactHash();
    }

    private int probeDimensions(int expected) {
        int actual = embed(List.of("dimension probe")).getFirst().length;
        if (actual != expected) {
            closeQuietly();
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
        Encoding[] encodings = encode(texts);
        int batch = encodings.length;
        int maxLen = 0;
        for (Encoding encoding : encodings) {
            maxLen = Math.max(maxLen, encoding.getIds().length);
        }
        long[] ids = new long[batch * maxLen];
        long[] mask = new long[batch * maxLen];
        if (PAD_TOKEN_ID != 0L) {
            java.util.Arrays.fill(ids, PAD_TOKEN_ID);
        }
        for (int i = 0; i < batch; i++) {
            long[] tokenIds = encodings[i].getIds();
            long[] attention = encodings[i].getAttentionMask();
            System.arraycopy(tokenIds, 0, ids, i * maxLen, tokenIds.length);
            System.arraycopy(attention, 0, mask, i * maxLen, attention.length);
        }
        long[] shape = {batch, maxLen};
        try (OnnxTensor idsTensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(ids), shape);
             OnnxTensor maskTensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(mask), shape);
             OnnxTensor typeTensor = needsTokenTypeIds
                     ? OnnxTensor.createTensor(environment, LongBuffer.wrap(new long[batch * maxLen]), shape)
                     : null) {
            Map<String, OnnxTensor> inputs = new LinkedHashMap<>();
            inputs.put(INPUT_IDS, idsTensor);
            inputs.put(ATTENTION_MASK, maskTensor);
            if (typeTensor != null) {
                inputs.put(TOKEN_TYPE_IDS, typeTensor);
            }
            try (OrtSession.Result result = session.run(inputs, Set.of(outputName))) {
                OnnxValue value = result.get(outputName).orElseThrow();
                Object raw = value.getValue();
                if (!(raw instanceof float[][] matrix)) {
                    throw new IllegalStateException("Output '" + outputName + "' is not a [batch, dim] float tensor: "
                            + raw.getClass().getSimpleName() + " (a 3-D output means token embeddings, not sentence embeddings)");
                }
                List<float[]> out = new ArrayList<>(batch);
                for (float[] row : matrix) {
                    out.add(row);
                }
                return out;
            }
        } catch (OrtException e) {
            throw new IllegalStateException("ONNX inference failed: " + e.getMessage(), e);
        }
    }

    /** Token ids for one text (after truncation and special tokens); exposed for diagnostics and tests. */
    public long[] tokenIds(String text) {
        return encode(List.of(text))[0].getIds();
    }

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
        closeQuietly();
    }

    private void closeQuietly() {
        try {
            if (tokenizer != null) {
                tokenizer.close();
            }
        } catch (RuntimeException e) {
            log.debug("Tokenizer close failed", e);
        }
        try {
            session.close();
        } catch (OrtException | IllegalStateException e) {
            log.debug("Session close failed", e);
        }
    }
}
