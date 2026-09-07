package com.personal.chatbot.support;

import com.personal.chatbot.service.embedding.TextEmbedder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Deterministic, model-free backend for hermetic tests: each word maps to a fixed pseudo-random
 * direction and a text is the sum of its words, so texts sharing words are closer than unrelated ones.
 * Records every batch it receives so tests can assert on prefixes and batching.
 */
public final class FakeTextEmbedder implements TextEmbedder {

    private final int dimensions;
    private final List<List<String>> batches = new CopyOnWriteArrayList<>();

    public FakeTextEmbedder(int dimensions) {
        this.dimensions = dimensions;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        batches.add(List.copyOf(texts));
        List<float[]> out = new ArrayList<>(texts.size());
        for (String text : texts) {
            float[] vector = new float[dimensions];
            for (String word : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
                if (word.isEmpty()) {
                    continue;
                }
                Random random = new Random(word.hashCode());
                for (int i = 0; i < dimensions; i++) {
                    vector[i] += (float) random.nextGaussian();
                }
            }
            out.add(vector);
        }
        return out;
    }

    public List<List<String>> batches() {
        return batches;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public String provider() {
        return "fake";
    }

    @Override
    public String modelName() {
        return "fake-embedder";
    }

    @Override
    public String artifactHash() {
        return "000000000000";
    }
}
