package com.personal.chatbot.support;

import com.personal.chatbot.service.embedding.TextEmbedder;

import java.util.List;

/** Fake backend that fails for any batch containing a trigger phrase, to simulate embedding outages. */
public final class FailingTextEmbedder implements TextEmbedder {

    private final FakeTextEmbedder delegate;
    private final String trigger;

    public FailingTextEmbedder(int dimensions, String trigger) {
        this.delegate = new FakeTextEmbedder(dimensions);
        this.trigger = trigger;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        if (texts.stream().anyMatch(t -> t.contains(trigger))) {
            throw new IllegalStateException("simulated embedding failure on '" + trigger + "'");
        }
        return delegate.embed(texts);
    }

    @Override
    public int dimensions() {
        return delegate.dimensions();
    }

    @Override
    public String provider() {
        return "fake";
    }

    @Override
    public String modelName() {
        return "failing-embedder";
    }

    @Override
    public String artifactHash() {
        return "ffffffffffff";
    }
}
